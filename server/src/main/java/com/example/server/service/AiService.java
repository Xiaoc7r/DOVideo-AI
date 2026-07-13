package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MediaFileMapper mediaFileMapper;
    private final VideoContextService videoContextService;
    private final AgentLoopService agentLoopService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;

    public AiService(MediaFileMapper mediaFileMapper,
                     VideoContextService videoContextService,
                     AgentLoopService agentLoopService,
                     AgentCheckpointService checkpointService,
                     AgentTelemetry telemetry,
                     MediaService mediaService,
                     TaskEventService taskEventService) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoContextService = videoContextService;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
    }

    public void asyncAnalyze(Long mediaId, String userGoal) {
        String traceId = telemetry.start(mediaId);
        telemetry.bind(traceId);
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.flush(traceId);
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
                taskEventService.publishAnalysis(mediaId, userGoal,
                        TaskStatus.of(TaskStatus.State.PROCESSING, "正在并行提取语音与关键帧"),
                        "VIDEO_CONTEXT");
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
            taskEventService.publishAnalysis(mediaId, userGoal,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "多模态上下文已就绪，Agent 开始分析"),
                    "AGENT_LOOP");
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
            try {
                checkpointService.saveFailure(mediaId, userGoal, "AI_ANALYSIS", e);
            } catch (RuntimeException checkpointError) {
                e.addSuppressed(checkpointError);
                log.error("agent_failure_checkpoint_write_failed traceId={} mediaId={}",
                        traceId, mediaId, checkpointError);
            }
            log.error("agent_analysis_failed traceId={} mediaId={}", traceId, mediaId, e);
            throw new IllegalStateException("AI analysis failed", e);
        } finally {
            telemetry.flush(traceId);
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
            return agentLoopService.run(mediaId, followUpContext).result().toMarkdown();
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public void stageRevision(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        checkpointService.saveFeedback(normalized);

        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal().trim();
        AgentState.AgentPlan correctedPlan = normalized.correctedTasks().isEmpty()
                ? null
                : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        checkpointService.stageRevision(normalized.mediaId(), goal, correctedPlan);
    }

    public String revisionGoal(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        return normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal();
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        checkpointService.cancelStagedRevision(mediaId, goal);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("media does not exist: " + mediaId);

        VideoContext sourceContext = checkpointService.loadContext(sourceMediaId);
        if (sourceContext == null) return false;
        checkpointService.saveContext(mediaId, new VideoContext(
                mediaFile.getFilePath(), "", sourceContext.segments()));
        List<VideoChunk> chunks = checkpointService.loadChunks(sourceMediaId);
        if (chunks != null && !chunks.isEmpty()) checkpointService.saveChunks(mediaId, chunks);
        checkpointService.saveResult(mediaId, new AgentState(
                state.goal(), state.plan(), state.result(), state.critique(), state.round()));
        persistResult(mediaFile, state);
        return true;
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState) {
        if (agentState.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        mediaFile.setAiSummary(agentState.result().toMarkdown());
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
