package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 受控 Agent 编排器：恢复状态、执行一轮分析、校验证据，再决定结束还是补跑。 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_ROUNDS = 2;
    private static final int MAX_PLAN_TASKS = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;
    private final TaskEventService taskEventService;

    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService,
                            TaskEventService taskEventService) {
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
        this.taskEventService = taskEventService;
    }

    public AgentState run(VideoContext context) {
        return run(null, context);
    }

    public AgentState run(Long mediaId, VideoContext context) {
        validateContext(context);
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, context.userGoal());
        boolean terminalCheckpoint = savedState != null && savedState.result() != null
                && (savedState.round() >= MAX_ROUNDS
                || (savedState.critique() != null && savedState.critique().passed()));
        if (terminalCheckpoint && isPlanValid(savedState.plan()) && isResultValid(savedState.result())) {
            checkpointService.saveResult(mediaId, savedState);
            telemetry.incrementCurrent("terminalCheckpointHits", 1);
            return savedState;
        }
        if (terminalCheckpoint) {
            telemetry.incrementCurrent("invalidTerminalCheckpointRepairs", 1);
            savedState = new AgentState(
                    savedState.goal(), savedState.plan(), savedState.result(), savedState.critique(), 0);
        }

        VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
        AgentState.AgentPlan plan = resolvePlan(mediaId, relevantContext, savedState);
        if (mediaId != null) {
            taskEventService.publishAnalysis(mediaId, relevantContext.userGoal(),
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 已完成任务拆解"),
                    TaskStage.PLAN_COMPLETED);
        }
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = contextForRetry(
                    mediaId, context, relevantContext, state.critique());
            plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique());
        }

        for (int round = state.round() + 1; round <= MAX_ROUNDS; round++) {
            state = executeRound(mediaId, relevantContext, plan, state.critique(), round);
            if (state.critique().passed()) break;
            if (round < MAX_ROUNDS) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique());
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique());
            }
        }
        validateResult(state.result());
        if (mediaId != null) checkpointService.saveResult(mediaId, state);
        return state;
    }

    private AgentState.AgentPlan resolvePlan(Long mediaId,
                                             VideoContext context,
                                             AgentState savedState) {
        AgentState.AgentPlan plan = mediaId == null
                ? null
                : checkpointService.loadPlan(mediaId, context.userGoal());
        if (plan == null && savedState != null) plan = savedState.plan();
        boolean shouldPersist = false;
        if (plan == null) {
            plan = deepSeekUtils.plan(context);
            shouldPersist = true;
        }
        if (!isPlanValid(plan)) {
            plan = deepSeekUtils.repairPlan(context, plan);
            telemetry.incrementCurrent("planStructureRepairs", 1);
            shouldPersist = true;
        }
        validatePlan(plan);
        if (mediaId != null && shouldPersist) {
            checkpointService.savePlan(mediaId, context.userGoal(), plan);
        }
        return plan;
    }

    private AgentState executeRound(Long mediaId,
                                    VideoContext context,
                                    AgentState.AgentPlan plan,
                                    AgentState.CriticResult previousCritique,
                                    int round) {
        AnalysisResult result = deepSeekUtils.execute(context, plan, previousCritique);
        AgentState.CriticResult critique = deepSeekUtils.critique(context, plan, result);
        critique = enforceStructureBounds(result, critique);
        critique = enforceEvidenceBounds(context, result, critique);
        telemetry.incrementCurrent("criticRounds", 1);
        if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);

        AgentState state = new AgentState(context.userGoal(), plan, result, critique, round);
        if (mediaId != null) {
            checkpointService.saveCriticState(mediaId, state);
            String message;
            TaskStage stage;
            if (critique.passed()) {
                message = "Critic 校验通过，正在整理结构化结果";
                stage = TaskStage.CRITIC_PASSED;
            } else if (round >= MAX_ROUNDS) {
                message = "Critic 达到最大校验轮次，正在保留警告并生成结果";
                stage = TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
            } else if (requiresEvidenceRefresh(critique)) {
                message = "Critic 发现证据缺口，正在定向补充证据";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            } else {
                message = "Critic 发现目标覆盖或结构问题，正在按反馈重写";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            }
            taskEventService.publishAnalysis(mediaId, context.userGoal(),
                    TaskStatus.of(TaskStatus.State.PROCESSING, message),
                    stage);
        }
        return state;
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal().isBlank() || context.segments().isEmpty()) {
            throw new IllegalArgumentException("Agent 需要目标和至少一个视频片段");
        }
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (!isPlanValid(plan)) {
            throw new IllegalStateException("Planner 返回了无效任务列表");
        }
    }

    private boolean isPlanValid(AgentState.AgentPlan plan) {
        return plan != null && !plan.understoodGoal().isBlank()
                && !plan.tasks().isEmpty() && plan.tasks().size() <= MAX_PLAN_TASKS
                && plan.tasks().stream().noneMatch(
                task -> task == null || task.isBlank() || task.length() > 500);
    }

    private void validateResult(AnalysisResult result) {
        if (!isResultValid(result)) {
            throw new IllegalStateException("Executor 未生成完整结构化结果");
        }
    }

    private boolean isResultValid(AnalysisResult result) {
        return result != null && !result.title().isBlank()
                && !result.conclusions().isEmpty() && !result.evidence().isEmpty();
    }

    private AgentState.CriticResult enforceEvidenceBounds(VideoContext context,
                                                           AnalysisResult result,
                                                           AgentState.CriticResult critique) {
        if (critique == null) {
            critique = new AgentState.CriticResult(
                    false, List.of("Critic 未返回有效结果"),
                    List.of(), List.of(), List.of());
        }
        boolean hasDeclaredProblems = !critique.feedback().isEmpty()
                || !critique.missingRequirements().isEmpty()
                || !critique.unsupportedClaims().isEmpty()
                || !critique.requiredTimestamps().isEmpty();
        if (critique.passed() && hasDeclaredProblems) {
            critique = new AgentState.CriticResult(
                    false,
                    critique.feedback(),
                    critique.missingRequirements(),
                    critique.unsupportedClaims(),
                    critique.requiredTimestamps());
        }
        if (!critique.passed()
                && critique.feedback().isEmpty()
                && critique.missingRequirements().isEmpty()
                && critique.unsupportedClaims().isEmpty()
                && critique.requiredTimestamps().isEmpty()) {
            critique = new AgentState.CriticResult(
                    false,
                    List.of("重新检查目标覆盖、结构完整性和证据绑定"),
                    List.of(), List.of(), List.of());
        }
        List<AnalysisResult.Evidence> invalidEvidence = result.evidence().stream()
                .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
                .toList();
        if (invalidEvidence.isEmpty()) return critique;

        List<String> unsupported = new ArrayList<>(critique.unsupportedClaims());
        invalidEvidence.stream()
                .map(evidence -> "证据无法在原始 ASR/OCR 中核验: " + evidence.timestampMs())
                .forEach(unsupported::add);
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.add("重新检索并绑定有效时间戳证据");
        List<Long> requiredTimestamps = new ArrayList<>(critique.requiredTimestamps());
        invalidEvidence.stream()
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

    private AgentState.CriticResult enforceStructureBounds(AnalysisResult result,
                                                            AgentState.CriticResult critique) {
        List<String> feedback = new ArrayList<>(critique == null ? List.of() : critique.feedback());
        if (result == null || result.title().isBlank()) feedback.add("补充明确的产物标题");
        if (result == null || result.conclusions().isEmpty()) feedback.add("补充覆盖 Planner 任务的核心结论");
        if (result == null || result.evidence().isEmpty()) feedback.add("为核心结论补充带时间戳的 ASR 或 OCR 证据");
        if (feedback.isEmpty() || critique == null) {
            return critique == null
                    ? new AgentState.CriticResult(false, feedback, List.of(), List.of(), List.of())
                    : critique;
        }
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                critique.unsupportedClaims(),
                critique.requiredTimestamps());
    }

    private VideoContext contextForRetry(Long mediaId,
                                         VideoContext fullContext,
                                         VideoContext selectedContext,
                                         AgentState.CriticResult critique) {
        if (!requiresEvidenceRefresh(critique)) {
            telemetry.incrementCurrent("criticRewriteOnlyRetries", 1);
            return selectedContext;
        }
        telemetry.incrementCurrent("criticEvidenceRefreshes", 1);
        return longVideoContextService.refineForCritique(
                mediaId, fullContext, selectedContext, critique);
    }

    private boolean requiresEvidenceRefresh(AgentState.CriticResult critique) {
        return critique != null
                && (!critique.requiredTimestamps().isEmpty()
                || !critique.missingRequirements().isEmpty()
                || !critique.unsupportedClaims().isEmpty());
    }

    private AgentState.AgentPlan revisePlanForRetry(Long mediaId,
                                                    VideoContext context,
                                                    AgentState.AgentPlan currentPlan,
                                                    AgentState.CriticResult critique) {
        if (critique == null || critique.missingRequirements().isEmpty()) return currentPlan;

        try {
            AgentState.AgentPlan revisedPlan = deepSeekUtils.replan(context, currentPlan, critique);
            validatePlan(revisedPlan);
            telemetry.incrementCurrent("planRevisions", 1);
            if (mediaId != null) {
                checkpointService.savePlan(mediaId, context.userGoal(), revisedPlan);
                taskEventService.publishAnalysis(mediaId, context.userGoal(),
                        TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 根据 Critic 反馈补充了遗漏任务"),
                        TaskStage.PLAN_COMPLETED);
            }
            return revisedPlan;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("planRevisionFallbacks", 1);
            log.warn("agent_replan_failed mediaId={}, fallback to current plan", mediaId, e);
            return currentPlan;
        }
    }
}
