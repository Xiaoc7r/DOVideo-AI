package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.evaluation.enabled", havingValue = "true")
public class OfflineAgentEvaluationRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OfflineAgentEvaluationRunner.class);

  private final AgentLoopService agentLoopService;
  private final AgentEvaluationService evaluationService;
  private final AgentTelemetry telemetry;
  private final ObjectMapper objectMapper;

  public OfflineAgentEvaluationRunner(
      AgentLoopService agentLoopService,
      AgentEvaluationService evaluationService,
      AgentTelemetry telemetry,
      ObjectMapper objectMapper) {
    this.agentLoopService = agentLoopService;
    this.evaluationService = evaluationService;
    this.telemetry = telemetry;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    List<GoldenTask> tasks;
    try (InputStream input =
        new ClassPathResource("evaluation/golden-video-tasks.json").getInputStream()) {
      tasks = objectMapper.readValue(input, new TypeReference<List<GoldenTask>>() {});
    }

    int passed = 0;
    for (int index = 0; index < tasks.size(); index++) {
      GoldenTask task = tasks.get(index);
      String traceId = telemetry.start(-1L - index, task.context().userGoal());
      telemetry.bind(traceId);
      try {
        AgentState state = agentLoopService.run(task.context());
        Map<String, Object> metrics = evaluationService.evaluate(task.context(), state);
        double keywordCoverage =
            keywordCoverage(state.result().toMarkdown(), task.expectedKeywords());
        boolean success =
            Boolean.TRUE.equals(metrics.get("structuredValid"))
                && ((Number) metrics.get("claimEvidenceSupportRate")).doubleValue() >= 0.8
                && keywordCoverage >= 0.8;
        if (success) passed++;
        log.info(
            "offline_agent_evaluation name={} success={} keywordCoverage={} metrics={}",
            task.name(),
            success,
            keywordCoverage,
            metrics);
      } catch (RuntimeException e) {
        log.warn("offline_agent_evaluation_failed name={}", task.name(), e);
      } finally {
        telemetry.flush(traceId);
        telemetry.clear();
      }
    }
    log.info("offline_agent_evaluation_completed passed={} total={}", passed, tasks.size());
  }

  private double keywordCoverage(String output, List<String> expectedKeywords) {
    if (expectedKeywords.isEmpty()) return 1;
    String normalized = output.toLowerCase(Locale.ROOT);
    long hits =
        expectedKeywords.stream()
            .map(keyword -> keyword.toLowerCase(Locale.ROOT))
            .filter(normalized::contains)
            .count();
    return (double) hits / expectedKeywords.size();
  }

  public record GoldenTask(String name, VideoContext context, List<String> expectedKeywords) {
    public GoldenTask {
      if (name == null || name.isBlank())
        throw new IllegalArgumentException("task name is required");
      if (context == null) throw new IllegalArgumentException("task context is required");
      expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
    }
  }
}
