package com.example.server.service;

import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 后端阶段变化发布到这里，SSE 订阅者只关心事件，不反向依赖业务服务。 */
@Service
public class TaskEventService implements MessageListener {

    public static final String ANALYSIS = "analysis";
    public static final String TRANSCRIPTION = "transcription";
    public static final String REDIS_CHANNEL = "dovideo:task-events";

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                TaskStatus initialStatus,
                                TaskStage stage) {
        String key = key(mediaId, type, goal);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        send(key, emitter, TaskEvent.of(initialStatus, stage));
        return emitter;
    }

    public void publishAnalysis(Long mediaId, String goal, TaskStatus status, TaskStage stage) {
        publish(key(mediaId, ANALYSIS, goal), TaskEvent.of(status, stage));
    }

    public void publishTranscription(Long mediaId, TaskStatus status, TaskStage stage) {
        publish(key(mediaId, TRANSCRIPTION, ""), TaskEvent.of(status, stage));
    }

    private void publish(String key, TaskEvent event) {
        try {
            String payload = objectMapper.createObjectNode()
                    .put("key", key)
                    .set("event", objectMapper.valueToTree(event))
                    .toString();
            Long receivers = redisTemplate.convertAndSend(REDIS_CHANNEL, payload);
            if (receivers == null || receivers == 0) publishLocal(key, event);
        } catch (RuntimeException e) {
            log.warn("task_event_redis_publish_failed key={}", key, e);
            publishLocal(key, event);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = objectMapper.readTree(message.getBody());
            publishLocal(
                    payload.path("key").asText(),
                    objectMapper.treeToValue(payload.path("event"), TaskEvent.class));
        } catch (Exception e) {
            log.warn("task_event_redis_message_invalid", e);
        }
    }

    private void publishLocal(String key, TaskEvent event) {
        List<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null) return;
        emitters.forEach(emitter -> send(key, emitter, event));
    }

    private void send(String key, SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task-status").data(event));
            if (event.terminal()) {
                remove(key, emitter);
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            remove(key, emitter);
            emitter.completeWithError(e);
            log.debug("task_event_stream_closed key={}", key);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        subscribers.computeIfPresent(key, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private String key(Long mediaId, String type, String goal) {
        String suffix = ANALYSIS.equals(type) ? AnalysisTaskKeys.goalDigest(goal) : "default";
        return type + ":" + mediaId + ":" + suffix;
    }
}
