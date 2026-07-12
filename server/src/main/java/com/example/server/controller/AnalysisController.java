package com.example.server.controller;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AiService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);
    private static final int MAX_GOAL_LENGTH = 500;

    private final MediaFileMapper mediaFileMapper;
    private final AiService aiService;
    private final AgentCheckpointService checkpointService;
    private final AgentEvaluationService evaluationService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final String analysisTopic;

    public AnalysisController(MediaFileMapper mediaFileMapper,
                              AiService aiService,
                              AgentCheckpointService checkpointService,
                              AgentEvaluationService evaluationService,
                              AgentTelemetry telemetry,
                              MediaService mediaService,
                              StringRedisTemplate redisTemplate,
                              RocketMQTemplate rocketMQTemplate,
                              RedissonClient redissonClient,
                              @Value("${rocketmq.topic.video-analysis:video-analysis-topic}") String analysisTopic) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiService = aiService;
        this.checkpointService = checkpointService;
        this.evaluationService = evaluationService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redissonClient = redissonClient;
        this.analysisTopic = analysisTopic;
    }

    @PostMapping("/ai")
    public ResponseEntity<String> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedGoal = normalizeText(goal, "分析目标");
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);

        RRateLimiter rateLimiter = redissonClient.getRateLimiter("limit:ai:global");
        rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.MINUTES);
        if (!rateLimiter.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("系统繁忙，请稍后再试");
        }

        String contentHash = contentHash(id);
        String goalDigest = AnalysisTaskKeys.goalDigest(normalizedGoal);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(id), 2, TimeUnit.HOURS);
        if (!Boolean.TRUE.equals(accepted)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("相同视频和分析目标正在处理中");
        }

        try {
            mediaFile.setAiSummary("分析任务已排队");
            mediaFileMapper.updateById(mediaFile);
            mediaService.invalidateUserList(mediaFile.getUserId());
            rocketMQTemplate.convertAndSend(
                    analysisTopic,
                    new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash, normalizedGoal));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body("任务已提交");
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            log.error("analysis_dispatch_failed mediaId={} userId={}", id, userId, e);
            return ResponseEntity.internalServerError().body("任务提交失败");
        }
    }

    @PostMapping("/transcribe")
    public ResponseEntity<String> transcribe(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        if (!aiService.queueTranscription(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("文字提取任务正在处理中");
        }
        aiService.asyncTranscribe(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("提取任务已提交");
    }

    @GetMapping("/transcription-status")
    public TaskStatus transcriptionStatus(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return aiService.transcriptionStatus(mediaFile);
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
        mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        return ResponseEntity.ok(aiService.reviseAndRerun(feedback));
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
        AgentState result = checkpointService.loadResult(id, normalizedGoal);
        if (result != null && result.result() != null) {
            return TaskStatus.completed(result.result().toMarkdown());
        }

        String stage = checkpointService.loadStage(id, normalizedGoal);
        String goalDigest = AnalysisTaskKeys.goalDigest(normalizedGoal);
        boolean active = Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(contentHash(id), goalDigest)));
        if (active) {
            TaskStatus.State state = stage == null
                    ? TaskStatus.State.QUEUED
                    : TaskStatus.State.PROCESSING;
            return TaskStatus.of(state, state == TaskStatus.State.QUEUED ? "任务已排队" : "正在分析");
        }
        if ("FAILED".equals(stage)) {
            return TaskStatus.of(TaskStatus.State.FAILED, "分析失败，请稍后重试");
        }
        return TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交分析任务");
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
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return telemetry.latest(id);
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        String inputPath = mediaService.readableSource(mediaFile.getFilePath());
        if (inputPath == null || inputPath.isBlank()) return ResponseEntity.notFound().build();
        if (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath))) {
            return ResponseEntity.notFound().build();
        }

        Path outputPath;
        try {
            outputPath = Files.createTempFile("dovideo-audio-", ".mp3");
            runFfmpeg(inputPath, outputPath);
        } catch (Exception e) {
            log.error("audio_conversion_failed mediaId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }

        StreamingResponseBody body = output -> {
            try {
                Files.copy(outputPath, output);
            } finally {
                Files.deleteIfExists(outputPath);
            }
        };
        String filename = mediaFile.getFilename() == null
                ? "audio.mp3"
                : mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(body);
    }

    private void runFfmpeg(String inputPath, Path outputPath) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-vn", "-acodec", "libmp3lame", "-q:a", "2", outputPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("FFmpeg 执行超时");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 转换失败");
    }

    private String normalizeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException(field + "不能为空且不能超过 " + MAX_GOAL_LENGTH + " 字");
        }
        return value.trim();
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, redisTemplate.opsForValue().get("media:md5:" + mediaId));
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
        if (feedback.errorType() != null && feedback.errorType().length() > 64) {
            throw new IllegalArgumentException("错误类型不能超过 64 字");
        }
        if (feedback.correctedTasks() != null
                && (feedback.correctedTasks().size() > 8
                || feedback.correctedTasks().stream().anyMatch(task -> task == null || task.length() > 500))) {
            throw new IllegalArgumentException("修正任务最多 8 条且每条不能超过 500 字");
        }
    }
}
