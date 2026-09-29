package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.RouteDecision;
import com.example.server.dto.RouteRequest;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AnalysisStatusService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AiService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.service.VideoContextNotReadyException;
import com.example.server.service.mode.ModeRouter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private static final int MAX_GOAL_LENGTH = 500;

    private final AiService aiService;
    private final AnalysisDispatchService dispatchService;
    private final AgentCheckpointService checkpointService;
    private final AgentEvaluationService evaluationService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final AnalysisStatusService statusService;
    private final ModeRouter modeRouter;
    private final Executor aiTaskExecutor;

    public AnalysisController(AiService aiService,
                              AnalysisDispatchService dispatchService,
                              AgentCheckpointService checkpointService,
                              AgentEvaluationService evaluationService,
                              AgentTelemetry telemetry,
                              MediaService mediaService,
                              TaskEventService taskEventService,
                              AnalysisStatusService statusService,
                              ModeRouter modeRouter,
                              @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.aiService = aiService;
        this.dispatchService = dispatchService;
        this.checkpointService = checkpointService;
        this.evaluationService = evaluationService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.statusService = statusService;
        this.modeRouter = modeRouter;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    /**
     * 自动意图路由:仅凭分析目标文本,由 LLM 判定最合适的分析模式。
     *
     * <p>路由不依赖具体视频,故不做媒体归属校验;{@code userId} 由拦截器注入,确保仅登录用户可调用。
     * 返回的 {@link RouteDecision#mode()} 一定是<strong>具体</strong>模式(GENERAL/LEARNING/REVIEW/CREATION),
     * 前端拿到后即以该模式发起真正的分析请求,使提交、状态查询、重跑共用同一套任务身份 key。
     * 路由本身永不失败:内部异常会安全回退到 GENERAL。
     */
    @PostMapping("/route")
    public Result<RouteDecision> route(
            @Valid @RequestBody RouteRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(modeRouter.route(
                normalizeText(request.goal(), "分析目标"), userId));
    }

    @PostMapping("/ai")
    public ResponseEntity<Result<Void>> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedGoal = normalizeText(goal, "分析目标");
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if (checkpointService.loadResult(id, normalizedGoal, analysisMode) != null) {
            // 已有可复用结果，是“已完成”而非“已受理”，用 200 与异步受理区分开。
            return ResponseEntity.ok(Result.ok());
        }
        return submissionResponse(dispatchService.submit(mediaFile, normalizedGoal, null, analysisMode));
    }

    @PostMapping("/follow-up")
    public CompletableFuture<Result<String>> followUp(
            @RequestParam Long id,
            @RequestParam String question,
            @RequestParam(required = false) String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedQuestion = normalizeText(question, "追问内容");
        String normalizedGoal = goal == null || goal.isBlank()
                ? null : normalizeText(goal, "原始分析目标");
        mediaService.requireOwnedMedia(id, userId);
        requireVideoContext(id);
        dispatchService.requireAiQuota(userId);
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        return runInteractive(() -> aiService.followUp(
                id, normalizedGoal, normalizedQuestion, analysisMode));
    }

    @GetMapping("/evidence-search")
    public CompletableFuture<Result<List<VideoEvidenceHit>>> searchEvidence(
            @RequestParam Long id,
            @RequestParam String query,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        requireVideoContext(id);
        dispatchService.requireAiQuota(userId);
        String normalizedQuery = normalizeText(query, "检索问题");
        return runInteractive(() -> aiService.searchEvidence(id, normalizedQuery));
    }

    @PostMapping("/agent-feedback")
    public Result<Void> agentFeedback(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        checkpointService.saveFeedback(
                feedback.normalized(AnalysisMode.fromRequest(feedback.mode())));
        return Result.ok();
    }

    @PostMapping("/agent-revise")
    public ResponseEntity<Result<Void>> reviseAgentResult(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        MediaFile mediaFile = mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        String revisedGoal = aiService.revisionGoal(feedback);
        AnalysisMode analysisMode = AnalysisMode.fromRequest(
                mode == null || mode.isBlank() ? feedback.mode() : mode);
        return submissionResponse(
                dispatchService.submit(mediaFile, revisedGoal, feedback, analysisMode));
    }

    @GetMapping("/agent-feedback")
    public Result<List<AgentFeedback>> agentFeedback(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadFeedback(id));
    }

    @GetMapping("/agent-plan")
    public Result<AgentState.AgentPlan> agentPlan(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadPlan(
                id, normalizeText(goal, "分析目标"), AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/analysis-status")
    public Result<TaskStatus> analysisStatus(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        return Result.ok(statusService.current(id, normalizedGoal, AnalysisMode.fromRequest(mode)));
    }

    @GetMapping(value = "/analysis-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analysisEvents(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        return taskEventService.subscribe(
                id,
                TaskEventService.ANALYSIS,
                normalizedGoal,
                analysisMode,
                statusService.current(id, normalizedGoal, analysisMode),
                statusService.stage(id, normalizedGoal, analysisMode));
    }

    @GetMapping("/agent-evaluation")
    public Result<Map<String, Object>> agentEvaluation(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(evaluationService.evaluate(
                id, normalizeText(goal, "分析目标"), AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/agent-trace")
    public Result<Map<String, Object>> agentTrace(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(telemetry.latest(
                id, normalizeText(goal, "分析目标"), AnalysisMode.fromRequest(mode)));
    }

    /**
     * 请求参数的规整 + 校验：既做 trim 归一（结果参与幂等 key 计算，不能省），
     * 又限制长度。请求体（DTO）改用 Bean Validation，参数这里保留是因为它承担了归一职责。
     */
    private String normalizeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException(field + "不能为空且不能超过 " + MAX_GOAL_LENGTH + " 字");
        }
        return value.trim();
    }

    private void ensureRating(AgentFeedback feedback) {
        Integer rating = feedback.rating();
        if (rating != null && rating != -1 && rating != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "rating 只能是 -1 或 1");
        }
    }

    private void requireVideoContext(Long mediaId) {
        if (checkpointService.loadContext(mediaId) == null) {
            throw new VideoContextNotReadyException();
        }
    }

    private <T> CompletableFuture<Result<T>> runInteractive(Supplier<T> action) {
        try {
            return CompletableFuture.supplyAsync(() -> Result.ok(action.get()), aiTaskExecutor);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 请求较多，请稍后再试");
        }
    }

    /**
     * 异步任务受理返回 202 Accepted：明确告诉客户端“已接单、结果稍后轮询/订阅”，
     * 与同步完成的 200 区分开，便于前端区分“已完成”“已受理”“需重试”三种状态。
     */
    private ResponseEntity<Result<Void>> submissionResponse(
            AnalysisDispatchService.SubmissionResult result) {
        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted().body(Result.ok());
            case RATE_LIMITED -> throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
            case DUPLICATE -> throw new BusinessException(ErrorCode.CONFLICT, "相同视频和分析目标正在处理中");
            case FAILED -> throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务提交失败");
        };
    }
}
