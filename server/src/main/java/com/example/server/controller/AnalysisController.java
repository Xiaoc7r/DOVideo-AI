package com.example.server.controller;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AnalysisStatusService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AiService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import org.springframework.http.HttpStatus;
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

    public AnalysisController(AiService aiService,
                              AnalysisDispatchService dispatchService,
                              AgentCheckpointService checkpointService,
                              AgentEvaluationService evaluationService,
                              AgentTelemetry telemetry,
                              MediaService mediaService,
                              TaskEventService taskEventService,
                              AnalysisStatusService statusService) {
        this.aiService = aiService;
        this.dispatchService = dispatchService;
        this.checkpointService = checkpointService;
        this.evaluationService = evaluationService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.statusService = statusService;
    }

    @PostMapping("/ai")
    public ResponseEntity<String> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedGoal = normalizeText(goal, "分析目标");
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if (checkpointService.loadResult(id, normalizedGoal) != null) {
            return ResponseEntity.ok("已有可复用的分析结果");
        }
        return submissionResponse(dispatchService.submit(mediaFile, normalizedGoal, null));
    }

    @PostMapping("/follow-up")
    public ResponseEntity<String> followUp(
            @RequestParam Long id,
            @RequestParam String question,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedQuestion = normalizeText(question, "追问内容");
        mediaService.requireOwnedMedia(id, userId);
        return ResponseEntity.ok(aiService.followUp(id, normalizedQuestion));
    }

    @PostMapping("/agent-feedback")
    public ResponseEntity<String> agentFeedback(
            @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        validateFeedback(feedback);
        mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        checkpointService.saveFeedback(feedback.normalized());
        return ResponseEntity.ok("反馈已保存为 Agent 评测样本");
    }

    @PostMapping("/agent-revise")
    public ResponseEntity<String> reviseAgentResult(
            @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        validateFeedback(feedback);
        MediaFile mediaFile = mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        String revisedGoal = aiService.revisionGoal(feedback);
        return submissionResponse(dispatchService.submit(mediaFile, revisedGoal, feedback));
    }

    @GetMapping("/agent-feedback")
    public List<AgentFeedback> agentFeedback(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return checkpointService.loadFeedback(id);
    }

    @GetMapping("/agent-plan")
    public AgentState.AgentPlan agentPlan(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return checkpointService.loadPlan(id, normalizeText(goal, "分析目标"));
    }

    @GetMapping("/analysis-status")
    public TaskStatus analysisStatus(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        return statusService.current(id, normalizedGoal);
    }

    @GetMapping(value = "/analysis-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analysisEvents(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        return taskEventService.subscribe(
                id,
                TaskEventService.ANALYSIS,
                normalizedGoal,
                statusService.current(id, normalizedGoal),
                statusService.stage(id, normalizedGoal));
    }

    @GetMapping("/agent-evaluation")
    public Map<String, Object> agentEvaluation(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return evaluationService.evaluate(id, normalizeText(goal, "分析目标"));
    }

    @GetMapping("/agent-trace")
    public Map<String, Object> agentTrace(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return telemetry.latest(id, normalizeText(goal, "分析目标"));
    }

    private String normalizeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException(field + "不能为空且不能超过 " + MAX_GOAL_LENGTH + " 字");
        }
        return value.trim();
    }

    private ResponseEntity<String> submissionResponse(AnalysisDispatchService.SubmissionResult result) {
        return switch (result) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body("任务已提交");
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("系统繁忙，请稍后再试");
            case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("相同视频和分析目标正在处理中");
            case FAILED -> ResponseEntity.internalServerError().body("任务提交失败");
        };
    }

    private void validateFeedback(AgentFeedback feedback) {
        if (feedback == null || feedback.mediaId() == null) {
            throw new IllegalArgumentException("mediaId 不能为空");
        }
        normalizeText(feedback.goal(), "分析目标");
        if (feedback.rating() != null && feedback.rating() != -1 && feedback.rating() != 1) {
            throw new IllegalArgumentException("rating 只能是 -1 或 1");
        }
        if (feedback.comment() != null && feedback.comment().length() > 2_000) {
            throw new IllegalArgumentException("反馈说明不能超过 2000 字");
        }
        if (feedback.correctedGoal() != null && !feedback.correctedGoal().isBlank()) {
            normalizeText(feedback.correctedGoal(), "修正后的分析目标");
        }
        if (feedback.errorType() != null && feedback.errorType().length() > 64) {
            throw new IllegalArgumentException("错误类型不能超过 64 字");
        }
        if (feedback.correctedTasks() != null
                && (feedback.correctedTasks().size() > 5
                || feedback.correctedTasks().stream().anyMatch(task -> task == null || task.length() > 500))) {
            throw new IllegalArgumentException("修正任务最多 5 条且每条不能超过 500 字");
        }
        if (feedback.evidenceTimestamp() != null && feedback.evidenceTimestamp() < 0) {
            throw new IllegalArgumentException("证据时间戳不能为负数");
        }
    }
}
