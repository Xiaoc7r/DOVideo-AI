package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 视频分析的应用层入口，负责串起上下文构建、AgentLoop 和结果落库。 */
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
        String traceId = telemetry.start(mediaId, userGoal);
        telemetry.bind(traceId);
        TaskStage currentStage = TaskStage.VIDEO_CONTEXT;
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

            VideoContext videoContext = resolveContext(mediaFile, userGoal, traceId);
            mediaFile.setTranscriptText(videoContext.transcriptText());
            currentStage = TaskStage.AGENT_LOOP;
            taskEventService.publishAnalysis(mediaId, userGoal,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "多模态上下文已就绪，Agent 开始分析"),
                    TaskStage.AGENT_LOOP);
            long agentStarted = System.nanoTime();
            try {
                agentState = agentLoopService.run(mediaId, videoContext);
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, true);
            } catch (RuntimeException e) {
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, false);
                throw e;
            }
            persistResult(mediaFile, agentState);
            log.info("agent_analysis_completed traceId={} mediaId={} rounds={}",
                    traceId, mediaId, agentState.round());
        } catch (Exception e) {
            try {
                checkpointService.saveFailure(mediaId, userGoal, currentStage, e);
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

    private VideoContext resolveContext(MediaFile mediaFile, String userGoal, String traceId) {
        VideoContext checkpoint = checkpointService.loadContext(mediaFile.getId());
        if (checkpoint != null) {
            telemetry.increment(traceId, "contextCheckpointHits", 1);
            return new VideoContext(checkpoint.source(), userGoal, checkpoint.segments());
        }

        taskEventService.publishAnalysis(mediaFile.getId(), userGoal,
                TaskStatus.of(TaskStatus.State.PROCESSING, "正在并行提取语音与关键帧"),
                TaskStage.VIDEO_CONTEXT);
        long started = System.nanoTime();
        try {
            VideoContext context = videoContextService.build(mediaFile.getFilePath(), userGoal, traceId);
            try {
                checkpointService.saveContext(mediaFile.getId(), context);
            } catch (RuntimeException e) {
                videoContextService.deleteEvidenceFrames(context);
                throw e;
            }
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, true);
            return context;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, false);
            throw e;
        }
    }

    public String followUp(Long mediaId, String question) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new IllegalStateException("视频尚未完成 VideoContext 构建");

        String traceId = telemetry.start(mediaId, question);
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
        checkpointService.saveContext(mediaId, reusableContext(mediaFile.getFilePath(), sourceContext));
        checkpointService.saveResult(mediaId, new AgentState(
                state.goal(), state.plan(), state.result(), state.critique(), state.round()));
        persistResult(mediaFile, state);
        return true;
    }

    private VideoContext reusableContext(String targetSource, VideoContext sourceContext) {
        return new VideoContext(targetSource, "", sourceContext.segments().stream()
                .map(segment -> new VideoContext.VideoSegment(
                        segment.startMs(),
                        segment.endMs(),
                        segment.transcript(),
                        segment.ocrTexts(),
                        segment.evidenceFrames().isEmpty()
                                ? java.util.List.of()
                                : java.util.List.of(targetSource + "#timestampMs=" + segment.startMs())))
                .toList());
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState) {
        if (agentState.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        mediaFile.setAiSummary(agentState.result().toMarkdown());
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
