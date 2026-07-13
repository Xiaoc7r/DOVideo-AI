package com.example.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AgentTelemetry {

    private static final Logger log = LoggerFactory.getLogger(AgentTelemetry.class);
    private static final int MAX_TRACES = 500;
    private static final Duration TRACE_TTL = Duration.ofDays(7);

    private final Map<String, TraceData> traces = new ConcurrentHashMap<>();
    private final Map<Long, String> latestTraceByTask = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTrace = new ThreadLocal<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentTelemetry(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String start(Long taskId) {
        if (traces.size() >= MAX_TRACES) {
            traces.values().stream()
                    .min(Comparator.comparing(trace -> trace.startedAt))
                    .ifPresent(trace -> {
                        traces.remove(trace.traceId);
                        latestTraceByTask.remove(trace.taskId, trace.traceId);
                    });
        }
        String traceId = UUID.randomUUID().toString();
        traces.put(traceId, new TraceData(traceId, taskId));
        latestTraceByTask.put(taskId, traceId);
        currentTrace.set(traceId);
        persist(traces.get(traceId));
        log.info("agent_trace traceId={} taskId={} stage=START status=SUCCESS", traceId, taskId);
        return traceId;
    }

    public void bind(String traceId) {
        currentTrace.set(traceId);
    }

    public void clear() {
        currentTrace.remove();
    }

    public void flush(String traceId) {
        TraceData trace = traces.get(traceId);
        if (trace != null) persist(trace);
    }

    public void stage(String traceId, String stage, long startedNanos, boolean success) {
        TraceData trace = traces.get(traceId);
        if (trace == null) return;
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        trace.stageDurations.merge(stage, durationMs, Long::sum);
        trace.increment(stage + "Calls", 1);
        if (!success) trace.increment("failedStages", 1);
        log.info("agent_trace traceId={} taskId={} stage={} durationMs={} status={}",
                traceId, trace.taskId, stage, durationMs, success ? "SUCCESS" : "FAILED");
        persist(trace);
    }

    public void increment(String traceId, String metric, long amount) {
        if (traceId == null) return;
        TraceData trace = traces.get(traceId);
        if (trace != null) trace.increment(metric, amount);
    }

    public void incrementCurrent(String metric, long amount) {
        String traceId = currentTrace.get();
        if (traceId != null) increment(traceId, metric, amount);
    }

    public void valueCurrent(String metric, double value) {
        String traceId = currentTrace.get();
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace != null) trace.values.put(metric, value);
    }

    public void failCurrentStage(String stage, long startedNanos) {
        String traceId = currentTrace.get();
        if (traceId != null) stage(traceId, stage, startedNanos, false);
    }

    public void modelCall(String stage,
                          String prompt,
                          String response,
                          double inputPricePerMillion,
                          double outputPricePerMillion,
                          long startedNanos) {
        String traceId = currentTrace.get();
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace == null) return;

        long inputTokens = estimateTokens(prompt);
        long outputTokens = estimateTokens(response);
        trace.increment("modelCalls", 1);
        trace.increment("inputTokensEstimated", inputTokens);
        trace.increment("outputTokensEstimated", outputTokens);
        trace.estimatedCost.add(
                inputTokens * inputPricePerMillion / 1_000_000D
                        + outputTokens * outputPricePerMillion / 1_000_000D);
        stage(traceId, stage, startedNanos, true);
    }

    public Map<String, Object> latest(Long taskId) {
        String traceId = latestTraceByTask.get(taskId);
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace != null) return trace.snapshot();
        try {
            if (traceId == null) traceId = redisTemplate.opsForValue().get(latestTraceKey(taskId));
            String snapshot = traceId == null ? null : redisTemplate.opsForValue().get(traceKey(traceId));
            return snapshot == null
                    ? Map.of()
                    : objectMapper.readValue(snapshot, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("agent_trace_read_failed taskId={}", taskId, e);
            return Map.of();
        }
    }

    private void persist(TraceData trace) {
        try {
            redisTemplate.opsForValue().set(
                    traceKey(trace.traceId), objectMapper.writeValueAsString(trace.snapshot()), TRACE_TTL);
            redisTemplate.opsForValue().set(
                    latestTraceKey(trace.taskId), trace.traceId, TRACE_TTL);
        } catch (Exception e) {
            log.warn("agent_trace_persist_failed traceId={} taskId={}", trace.traceId, trace.taskId, e);
        }
    }

    private String traceKey(String traceId) {
        return "agent:trace:" + traceId;
    }

    private String latestTraceKey(Long taskId) {
        return "agent:trace:task:" + taskId;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long nonAscii = text.codePoints().filter(codePoint -> codePoint > 127).count();
        long ascii = text.codePoints().count() - nonAscii;
        return Math.max(1, nonAscii + (ascii + 3) / 4);
    }

    private static class TraceData {
        private final String traceId;
        private final Long taskId;
        private final Instant startedAt = Instant.now();
        private final Map<String, Long> stageDurations = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final Map<String, Double> values = new ConcurrentHashMap<>();
        private final DoubleAdder estimatedCost = new DoubleAdder();

        private TraceData(String traceId, Long taskId) {
            this.traceId = traceId;
            this.taskId = taskId;
        }

        private void increment(String metric, long amount) {
            counters.computeIfAbsent(metric, key -> new LongAdder()).add(amount);
        }

        private Map<String, Object> snapshot() {
            Map<String, Long> counterSnapshot = new LinkedHashMap<>();
            counters.forEach((key, value) -> counterSnapshot.put(key, value.sum()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", traceId);
            result.put("taskId", taskId);
            result.put("startedAt", startedAt);
            result.put("stageDurationMs", new LinkedHashMap<>(stageDurations));
            result.put("counters", counterSnapshot);
            result.put("values", new LinkedHashMap<>(values));
            result.put("estimatedCost", estimatedCost.sum());
            return result;
        }
    }
}
