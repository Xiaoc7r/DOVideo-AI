package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.springframework.stereotype.Service;

@Service
public class AgentLoopService {

    private static final int MAX_ROUNDS = 2;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;

    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService) {
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
    }

    public AgentState run(VideoContext context) {
        return run(null, context);
    }

    public AgentState run(Long mediaId, VideoContext context) {
        validateContext(context);
        VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, relevantContext.userGoal());
        AgentState.AgentPlan plan = savedState == null ? null : savedState.plan();
        if (plan == null && mediaId != null) {
            plan = checkpointService.loadPlan(mediaId, relevantContext.userGoal());
        }
        if (plan == null) {
            plan = deepSeekUtils.plan(relevantContext);
            if (mediaId != null) checkpointService.savePlan(mediaId, relevantContext.userGoal(), plan);
        }
        validatePlan(plan);
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = longVideoContextService.refineForCritique(
                    mediaId, context, relevantContext, state.critique());
        }

        if (state.round() >= MAX_ROUNDS && state.result() != null) return state;

        for (int round = state.round() + 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result = deepSeekUtils.execute(relevantContext, plan, state.critique());
            validateResult(result);
            AgentState.CriticResult critique = deepSeekUtils.critique(relevantContext, plan, result);
            critique = enforceEvidenceBounds(relevantContext, result, critique);
            telemetry.incrementCurrent("criticRounds", 1);
            if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);
            state = new AgentState(relevantContext.userGoal(), plan, result, critique, round);

            if (mediaId != null) checkpointService.saveCriticState(mediaId, state);
            if (critique.passed()) {
                break;
            }
            relevantContext = longVideoContextService.refineForCritique(
                    mediaId, context, relevantContext, critique);
        }
        if (state.result() == null) throw new IllegalStateException("Agent 未生成结果");
        if (mediaId != null) checkpointService.saveResult(mediaId, state);
        return state;
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal().isBlank() || context.segments().isEmpty()) {
            throw new IllegalArgumentException("Agent 需要目标和至少一个视频片段");
        }
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (plan == null || plan.understoodGoal().isBlank()
                || plan.tasks().isEmpty() || plan.tasks().size() > 8
                || plan.tasks().stream().anyMatch(task -> task == null || task.isBlank() || task.length() > 500)) {
            throw new IllegalStateException("Planner 返回了无效任务列表");
        }
    }

    private void validateResult(AnalysisResult result) {
        if (result == null || result.title().isBlank()
                || result.conclusions().isEmpty() || result.evidence().isEmpty()) {
            throw new IllegalStateException("Executor 未生成完整结构化结果");
        }
    }

    private AgentState.CriticResult enforceEvidenceBounds(VideoContext context,
                                                           AnalysisResult result,
                                                           AgentState.CriticResult critique) {
        if (critique == null) {
            critique = new AgentState.CriticResult(
                    false, java.util.List.of("Critic 未返回有效结果"),
                    java.util.List.of(), java.util.List.of(), java.util.List.of());
        }
        java.util.List<String> invalidEvidence = result.evidence().stream()
                .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
                .map(evidence -> "证据无法在原始 ASR/OCR 中核验: " + evidence.timestampMs())
                .toList();
        if (invalidEvidence.isEmpty()) return critique;

        java.util.List<String> unsupported = new java.util.ArrayList<>(critique.unsupportedClaims());
        unsupported.addAll(invalidEvidence);
        java.util.List<String> feedback = new java.util.ArrayList<>(critique.feedback());
        feedback.add("重新检索并绑定有效时间戳证据");
        java.util.List<Long> requiredTimestamps = new java.util.ArrayList<>(critique.requiredTimestamps());
        result.evidence().stream()
                .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
                .map(AnalysisResult.Evidence::timestampMs)
                .filter(timestamp -> !requiredTimestamps.contains(timestamp))
                .forEach(requiredTimestamps::add);
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                unsupported,
                requiredTimestamps);
    }
}
