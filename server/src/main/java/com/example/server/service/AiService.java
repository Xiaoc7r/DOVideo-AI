package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    private final VideoContextService videoContextService;
    private final AgentLoopService agentLoopService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     VideoContextService videoContextService,
                     AgentLoopService agentLoopService,
                     AgentCheckpointService checkpointService,
                     AgentTelemetry telemetry,
                     MediaService mediaService,
                     StringRedisTemplate redisTemplate) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.videoContextService = videoContextService;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
    }

    public void asyncAnalyze(Long mediaId, String userGoal) {
        String traceId = telemetry.start(mediaId);
        telemetry.bind(traceId);
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.clear();
            throw new IllegalArgumentException("media does not exist: " + mediaId);
        }

        try {
            AgentState agentState = checkpointService.loadResult(mediaId, userGoal);
            if (agentState != null && agentState.result() != null) {
                persistResult(mediaFile, agentState);
                telemetry.increment(traceId, "checkpointHits", 1);
                return;
            }

            VideoContext videoContext = checkpointService.loadContext(mediaId);
            if (videoContext == null) {
                long contextStarted = System.nanoTime();
                try {
                    videoContext = videoContextService.build(mediaFile.getFilePath(), userGoal, traceId);
                    checkpointService.saveContext(mediaId, videoContext);
                    telemetry.stage(traceId, "VIDEO_CONTEXT", contextStarted, true);
                } catch (RuntimeException e) {
                    telemetry.stage(traceId, "VIDEO_CONTEXT", contextStarted, false);
                    throw e;
                }
            } else {
                videoContext = new VideoContext(videoContext.source(), userGoal, videoContext.segments());
                telemetry.increment(traceId, "contextCheckpointHits", 1);
            }

            mediaFile.setTranscriptText(videoContext.transcriptText());
            long agentStarted = System.nanoTime();
            try {
                agentState = agentLoopService.run(mediaId, videoContext);
                telemetry.stage(traceId, "AGENT_LOOP", agentStarted, true);
            } catch (RuntimeException e) {
                telemetry.stage(traceId, "AGENT_LOOP", agentStarted, false);
                throw e;
            }
            persistResult(mediaFile, agentState);
            log.info("agent_analysis_completed traceId={} mediaId={} rounds={}",
                    traceId, mediaId, agentState.round());
        } catch (Exception e) {
            mediaFile.setAiSummary("❌ 分析失败，请稍后重试");
            mediaFileMapper.updateById(mediaFile);
            mediaService.invalidateUserList(mediaFile.getUserId());
            checkpointService.saveFailure(mediaId, userGoal, "AI_ANALYSIS", e);
            log.error("agent_analysis_failed traceId={} mediaId={}", traceId, mediaId, e);
            throw new IllegalStateException("AI analysis failed", e);
        } finally {
            telemetry.clear();
        }
    }

    public String followUp(Long mediaId, String question) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new IllegalStateException("视频尚未完成 VideoContext 构建");

        String traceId = telemetry.start(mediaId);
        telemetry.bind(traceId);
        try {
            VideoContext followUpContext = new VideoContext(context.source(), question, context.segments());
            return agentLoopService.run(followUpContext).result().toMarkdown();
        } finally {
            telemetry.clear();
        }
    }

    public String reviseAndRerun(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        checkpointService.saveFeedback(normalized);

        VideoContext context = checkpointService.loadContext(normalized.mediaId());
        if (context == null) throw new IllegalStateException("视频尚未完成 VideoContext 构建");

        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal().trim();
        AgentState.AgentPlan correctedPlan = normalized.correctedTasks().isEmpty()
                ? null
                : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        checkpointService.resetForRerun(normalized.mediaId(), goal, correctedPlan);

        String traceId = telemetry.start(normalized.mediaId());
        telemetry.bind(traceId);
        long started = System.nanoTime();
        try {
            VideoContext revisedContext = new VideoContext(context.source(), goal, context.segments());
            AgentState state = agentLoopService.run(normalized.mediaId(), revisedContext);
            telemetry.stage(traceId, "HUMAN_REVISE", started, true);
            return state.result().toMarkdown();
        } catch (RuntimeException e) {
            telemetry.stage(traceId, "HUMAN_REVISE", started, false);
            throw e;
        } finally {
            telemetry.clear();
        }
    }

    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            clearTranscriptionActive(mediaId);
            return;
        }

        try {
            setTranscriptionState(mediaId, TaskStatus.State.PROCESSING, Duration.ofHours(2));
            mediaFile.setTranscriptText(aiAnalysisStrategy.transcribe(
                    mediaService.readableSource(mediaFile.getFilePath())));
            mediaFileMapper.updateById(mediaFile);
            mediaService.invalidateUserList(mediaFile.getUserId());
            setTranscriptionState(mediaId, TaskStatus.State.COMPLETED, Duration.ofDays(7));
            log.info("transcription_completed mediaId={}", mediaId);
        } catch (Exception e) {
            setTranscriptionState(mediaId, TaskStatus.State.FAILED, Duration.ofHours(1));
            log.error("transcription_failed mediaId={}", mediaId, e);
        } finally {
            clearTranscriptionActive(mediaId);
        }
    }

    public boolean queueTranscription(Long mediaId) {
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                transcriptionActiveKey(mediaId), "1", Duration.ofHours(2));
        if (Boolean.TRUE.equals(accepted)) {
            setTranscriptionState(mediaId, TaskStatus.State.QUEUED, Duration.ofHours(2));
            return true;
        }
        return false;
    }

    public TaskStatus transcriptionStatus(MediaFile mediaFile) {
        if (mediaFile.getTranscriptText() != null && !mediaFile.getTranscriptText().isBlank()) {
            return TaskStatus.completed(mediaFile.getTranscriptText());
        }
        String stateValue = redisTemplate.opsForValue().get(transcriptionStateKey(mediaFile.getId()));
        if (stateValue == null) {
            boolean active = Boolean.TRUE.equals(redisTemplate.hasKey(
                    transcriptionActiveKey(mediaFile.getId())));
            return active
                    ? TaskStatus.of(TaskStatus.State.PROCESSING, "正在提取文字")
                    : TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交文字提取任务");
        }
        TaskStatus.State state;
        try {
            state = TaskStatus.State.valueOf(stateValue);
        } catch (IllegalArgumentException e) {
            log.warn("invalid_transcription_state mediaId={} state={}", mediaFile.getId(), stateValue);
            return TaskStatus.of(TaskStatus.State.NOT_STARTED, "任务状态不可用");
        }
        return switch (state) {
            case COMPLETED -> TaskStatus.completed(mediaFile.getTranscriptText());
            case FAILED -> TaskStatus.of(state, "文字提取失败，请稍后重试");
            case QUEUED -> TaskStatus.of(state, "文字提取任务已排队");
            case PROCESSING -> TaskStatus.of(state, "正在提取文字");
            case NOT_STARTED -> TaskStatus.of(state, "尚未提交文字提取任务");
        };
    }

    private void setTranscriptionState(Long mediaId, TaskStatus.State state, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(transcriptionStateKey(mediaId), state.name(), ttl);
        } catch (RuntimeException e) {
            log.warn("transcription_state_write_failed mediaId={} state={}", mediaId, state, e);
        }
    }

    private void clearTranscriptionActive(Long mediaId) {
        try {
            redisTemplate.delete(transcriptionActiveKey(mediaId));
        } catch (RuntimeException e) {
            log.warn("transcription_active_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private String transcriptionActiveKey(Long mediaId) {
        return "transcription:active:" + mediaId;
    }

    private String transcriptionStateKey(Long mediaId) {
        return "transcription:state:" + mediaId;
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState) {
        if (agentState.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        mediaFile.setAiSummary(agentState.result().toMarkdown());
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
