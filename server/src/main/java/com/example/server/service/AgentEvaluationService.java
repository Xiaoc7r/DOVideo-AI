package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentEvaluationService {

  private final AgentCheckpointService checkpointService;
  private final EvidenceVerificationService evidenceVerificationService;

  public AgentEvaluationService(
      AgentCheckpointService checkpointService,
      EvidenceVerificationService evidenceVerificationService) {
    this.checkpointService = checkpointService;
    this.evidenceVerificationService = evidenceVerificationService;
  }

  public Map<String, Object> evaluate(Long mediaId, String goal) {
    return evaluate(mediaId, goal, AnalysisMode.GENERAL);
  }

  public Map<String, Object> evaluate(Long mediaId, String goal, AnalysisMode mode) {
    AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
    VideoContext context = checkpointService.loadContext(mediaId);
    AgentState state = checkpointService.loadResult(mediaId, goal, resolvedMode);
    if (state == null) state = checkpointService.loadCriticState(mediaId, goal, resolvedMode);

    List<AgentFeedback> feedback =
        checkpointService.loadFeedback(mediaId).stream()
            .filter(item -> goal.equals(item.goal()) || goal.equals(item.correctedGoal()))
            .filter(item -> AnalysisMode.fromNullable(item.mode()) == resolvedMode)
            .toList();

    Map<String, Object> metrics = new LinkedHashMap<>(evaluate(context, state));
    metrics.put("userAcceptanceRate", userAcceptanceRate(feedback));
    metrics.put("feedbackSamples", feedback.size());
    return metrics;
  }

  public Map<String, Object> evaluate(VideoContext context, AgentState state) {
    AnalysisResult result = state == null ? null : state.result();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("structuredValid", structuredValid(result));
    metrics.put("timestampCoverageRate", timestampCoverageRate(context, result));
    metrics.put("evidenceSupportRate", evidenceSupportRate(context, result));
    metrics.put("claimEvidenceSupportRate", claimEvidenceSupportRate(context, result));
    metrics.put(
        "criticPassed", state != null && state.critique() != null && state.critique().passed());
    return metrics;
  }

  private boolean structuredValid(AnalysisResult result) {
    return result != null
        && result.title() != null
        && !result.title().isBlank()
        && result.conclusions() != null
        && !result.conclusions().isEmpty()
        && result.evidence() != null
        && !result.evidence().isEmpty();
  }

  private double evidenceSupportRate(VideoContext context, AnalysisResult result) {
    if (context == null
        || result == null
        || result.evidence() == null
        || result.evidence().isEmpty()) return 0;
    long supported =
        result.evidence().stream()
            .filter(evidence -> evidenceVerificationService.supported(context, evidence))
            .count();
    return (double) supported / result.evidence().size();
  }

  private double timestampCoverageRate(VideoContext context, AnalysisResult result) {
    if (context == null || result == null || result.evidence().isEmpty()) return 0;
    long covered =
        result.evidence().stream()
            .filter(evidence -> evidenceVerificationService.timestampCovered(context, evidence))
            .count();
    return (double) covered / result.evidence().size();
  }

  private double claimEvidenceSupportRate(VideoContext context, AnalysisResult result) {
    if (context == null || result == null || result.conclusions().isEmpty()) return 0;
    long supported =
        result.conclusions().stream()
            .filter(
                claim ->
                    result.evidence().stream()
                        .anyMatch(
                            evidence ->
                                evidenceVerificationService.supportsClaim(
                                    context, claim, evidence)))
            .count();
    return (double) supported / result.conclusions().size();
  }

  private double userAcceptanceRate(List<AgentFeedback> feedback) {
    List<AgentFeedback> rated = feedback.stream().filter(item -> item.rating() != null).toList();
    if (rated.isEmpty()) return 0;
    long accepted = rated.stream().filter(item -> item.rating() > 0).count();
    return (double) accepted / rated.size();
  }
}
