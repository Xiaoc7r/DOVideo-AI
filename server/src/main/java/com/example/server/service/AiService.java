package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.AnalysisTaskKeys;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 视频分析的应用层入口，负责串起上下文构建、AgentLoop 和结果落库。 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    /**
     * 等待同一视频上下文构建完成的窗口。ASR + 关键帧 OCR 是分钟级作业，取 5 分钟可以覆盖
     * 绝大多数并发争用；超出后不再本地死等，改由 RocketMQ 重投（见 resolveContext）。
     */
    private static final long CONTEXT_LOCK_WAIT_SECONDS = 300;
    /** 内容级归属索引的有效期，与结果复用键保持同一量级。 */
    private static final Duration CONTEXT_OWNER_TTL = Duration.ofDays(7);
    /** 追问历史在 Redis 中的前缀。 */
    private static final String FOLLOW_UP_HISTORY_KEY_PREFIX = "followup:history:";
    /** 每个视频最多保留的追问轮数。 */
    private static final int FOLLOW_UP_HISTORY_MAX = 10;
    /** 追问历史的保留时间。 */
    private static final Duration FOLLOW_UP_HISTORY_TTL = Duration.ofDays(7);

    private final MediaFileMapper mediaFileMapper;
    private final VideoContextService videoContextService;
    private final LongVideoContextService longVideoContextService;
    private final AgentLoopService agentLoopService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ModeRegistry modeRegistry;
    private final DeepSeekUtils deepSeekUtils;
    private final ObjectMapper objectMapper;

    public AiService(MediaFileMapper mediaFileMapper,
                     VideoContextService videoContextService,
                     LongVideoContextService longVideoContextService,
                     AgentLoopService agentLoopService,
                     AgentCheckpointService checkpointService,
                     AgentTelemetry telemetry,
                     MediaService mediaService,
                     TaskEventService taskEventService,
                     RedissonClient redissonClient,
                     StringRedisTemplate redisTemplate,
                     ModeRegistry modeRegistry,
                     DeepSeekUtils deepSeekUtils,
                     ObjectMapper objectMapper) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoContextService = videoContextService;
        this.longVideoContextService = longVideoContextService;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.modeRegistry = modeRegistry;
        this.deepSeekUtils = deepSeekUtils;
        this.objectMapper = objectMapper;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 分析。 */
    public void asyncAnalyze(Long mediaId, String userGoal) {
        asyncAnalyze(mediaId, userGoal, AnalysisMode.GENERAL);
    }

    public void asyncAnalyze(Long mediaId, String userGoal, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        String traceId = telemetry.start(mediaId, userGoal, resolvedMode);
        telemetry.bind(traceId);
        TaskStage currentStage = TaskStage.VIDEO_CONTEXT;
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.flush(traceId);
            telemetry.clear();
            throw new IllegalArgumentException("media does not exist: " + mediaId);
        }

        try {
            // Checkpoint 键 = (mediaId, goalDigest(goal, mode)):不同模式的同一目标互不串键,
            // 因此这里带 mode 读取,不会误取别的模式已完成的结果。
            AgentState agentState = checkpointService.loadResult(mediaId, userGoal, resolvedMode);
            if (agentState != null && agentState.result() != null) {
                persistResult(mediaFile, agentState, null);
                telemetry.increment(traceId, "checkpointHits", 1);
                return;
            }

            VideoContext videoContext = resolveContext(mediaFile, userGoal, traceId, resolvedMode);
            currentStage = TaskStage.AGENT_LOOP;
            taskEventService.publishAnalysis(mediaId, userGoal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "多模态上下文已就绪，Agent 开始分析"),
                    TaskStage.AGENT_LOOP);
            long agentStarted = System.nanoTime();
            try {
                agentState = agentLoopService.run(mediaId, videoContext, modeRegistry.of(resolvedMode));
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, true);
            } catch (RuntimeException e) {
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, false);
                throw e;
            }
            persistResult(mediaFile, agentState, videoContext.transcriptText());
            log.info("agent_analysis_completed traceId={} mediaId={} rounds={}",
                    traceId, mediaId, agentState.round());
        } catch (Exception e) {
            try {
                checkpointService.saveFailure(mediaId, userGoal, resolvedMode, currentStage, e);
            } catch (RuntimeException checkpointError) {
                e.addSuppressed(checkpointError);
                log.error("agent_failure_checkpoint_write_failed traceId={} mediaId={}",
                        traceId, mediaId, checkpointError);
            }
            log.error("agent_analysis_failed traceId={} mediaId={}", traceId, mediaId, e);
            if (e instanceof AgentLoopService.BudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            }
            throw new IllegalStateException("AI analysis failed", e);
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    /**
     * 解析视频上下文（ASR + 关键帧 OCR）。
     *
     * <p>ASR/OCR 是只取决于视频内容的确定性预处理，与用户目标无关，因此按内容级（contentHash）
     * 复用：同一个视频换个分析目标、或被不同用户重复上传，都不该再烧一遍算力与第三方额度。
     * 复用顺序为：本 mediaId 检查点 → 内容级检查点 → 加内容锁后真正构建。
     */
    private VideoContext resolveContext(MediaFile mediaFile,
                                        String userGoal,
                                        String traceId,
                                        AnalysisMode mode) {
        VideoContext checkpoint = checkpointService.loadContext(mediaFile.getId());
        if (checkpoint != null) {
            telemetry.increment(traceId, "contextCheckpointHits", 1);
            return new VideoContext(checkpoint.source(), userGoal, checkpoint.segments());
        }

        String contentHash = AnalysisTaskKeys.normalizeContentHash(
                mediaFile.getId(), mediaService.contentHash(mediaFile.getId()));
        VideoContext reused = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
        if (reused != null) return reused;

        // 同一视频被多个目标同时提交时，只让一个消费者真正跑 ASR/OCR，其余等待后复用。
        RLock contextLock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked = false;
        try {
            locked = contextLock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            // 无论是否抢到锁都要重查，且必须先查自身检查点：同一个 mediaId 换目标并发提交时，
            // 先完成者登记的归属正是这个 mediaId，只查归属索引会被 "owner == 自己" 判空而漏掉，
            // 于是又重跑一遍完整 ASR/OCR。
            VideoContext own = checkpointService.loadContext(mediaFile.getId());
            if (own != null) {
                telemetry.increment(traceId, "contextCheckpointHits", 1);
                return new VideoContext(own.source(), userGoal, own.segments());
            }
            VideoContext afterWait = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
            if (afterWait != null) return afterWait;

            if (!locked) {
                // 没抢到锁说明同一视频的上下文仍在被另一个消费者构建。此处绝不能自己再跑一遍——
                // 那正是要消除的重复 ASR/OCR。本地等待只负责消化短时争用，超出等待窗口就交给
                // RocketMQ 做跨时间重投：等对方落盘后，重投的这条消息会在上面两次复用检查中直接命中。
                telemetry.increment(traceId, "contextLockContentions", 1);
                log.warn("context_build_in_progress mediaId={} contentHash={} waitedSeconds={}",
                        mediaFile.getId(), contentHash, CONTEXT_LOCK_WAIT_SECONDS);
                throw new IllegalStateException("同一视频的上下文正在构建中，稍后重试");
            }
            return buildContext(mediaFile, userGoal, traceId, contentHash, mode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待视频上下文构建锁被中断", e);
        } finally {
            if (locked && contextLock.isHeldByCurrentThread()) contextLock.unlock();
        }
    }

    /** 命中内容级上下文时，把它挂到当前 mediaId 上并改写素材地址，跳过整段 ASR/OCR。 */
    private VideoContext reuseContentContext(MediaFile mediaFile,
                                             String userGoal,
                                             String traceId,
                                             String contentHash) {
        Long ownerMediaId = contextOwner(contentHash);
        if (ownerMediaId == null || ownerMediaId.equals(mediaFile.getId())) return null;

        VideoContext ownerContext = checkpointService.loadContext(ownerMediaId);
        if (ownerContext == null) {
            // 归属记录过期或对应检查点已被清理，丢弃这条索引，走正常构建。
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        }

        VideoContext localized = reusableContext(mediaFile.getFilePath(), ownerContext);
        checkpointService.saveContext(mediaFile.getId(), localized);
        telemetry.increment(traceId, "contextContentReuses", 1);
        log.info("video_context_reused mediaId={} sourceMediaId={} contentHash={}",
                mediaFile.getId(), ownerMediaId, contentHash);
        return new VideoContext(localized.source(), userGoal, localized.segments());
    }

    private VideoContext buildContext(MediaFile mediaFile,
                                      String userGoal,
                                      String traceId,
                                      String contentHash,
                                      AnalysisMode mode) {
        taskEventService.publishAnalysis(mediaFile.getId(), userGoal, mode,
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
            // 先落盘再登记归属：登记成功即意味着该上下文确实可被读取，避免别人拿到空索引。
            rememberContextOwner(contentHash, mediaFile.getId());
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, true);
            return context;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, false);
            throw e;
        }
    }

    private Long contextOwner(String contentHash) {
        try {
            String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        } catch (RuntimeException e) {
            // 复用只是省钱优化，Redis 故障时退回正常构建，不能因此让分析失败。
            log.warn("context_owner_read_failed contentHash={}", contentHash, e);
            return null;
        }
    }

    private void rememberContextOwner(String contentHash, Long mediaId) {
        try {
            redisTemplate.opsForValue().set(
                    AnalysisTaskKeys.contextOwner(contentHash),
                    String.valueOf(mediaId),
                    CONTEXT_OWNER_TTL);
        } catch (RuntimeException e) {
            log.warn("context_owner_write_failed contentHash={} mediaId={}", contentHash, mediaId, e);
        }
    }

    public String followUp(Long mediaId, String originalGoal, String question) {
        return followUp(mediaId, originalGoal, question, AnalysisMode.GENERAL);
    }

    public String followUp(Long mediaId,
                           String originalGoal,
                           String question,
                           AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, question, resolvedMode);
        telemetry.bind(traceId);
        try {
            return agentLoopService.executeWithinBudget(() -> {
                AgentState previous = originalGoal == null
                        ? null : checkpointService.loadResult(mediaId, originalGoal, resolvedMode);
                String historyKey = followUpHistoryKey(mediaId, originalGoal, resolvedMode);
                List<String> history = loadFollowUpHistory(historyKey, mediaId);
                String evidenceSummary = retrieveEvidenceWithFunctionCalling(mediaId, context, question);
                String followUpGoal = contextualQuestion(originalGoal, previous, question, history, evidenceSummary);
                VideoContext followUpContext = new VideoContext(
                        context.source(), followUpGoal, context.segments());
                String answer = agentLoopService.run(
                        mediaId, followUpContext, modeRegistry.of(resolvedMode)).result().toMarkdown();
                saveFollowUpHistory(historyKey, mediaId, question, answer);
                return answer;
            });
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, String query) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, query);
        telemetry.bind(traceId);
        long started = System.nanoTime();
        try {
            VideoContext searchContext = new VideoContext(
                    context.source(), query, context.segments());
            List<VideoEvidenceHit> hits =
                    longVideoContextService.searchEvidence(mediaId, searchContext);
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, true);
            return hits;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, false);
            throw e;
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 暂存修正。 */
    public void stageRevision(AgentFeedback feedback) {
        stageRevision(feedback, AnalysisMode.GENERAL);
    }

    public void stageRevision(AgentFeedback feedback, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        AgentFeedback normalized = feedback.normalized(resolvedMode);
        checkpointService.saveFeedback(normalized);

        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal().trim();
        AgentState.AgentPlan correctedPlan = normalized.correctedTasks().isEmpty()
                ? null
                : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        checkpointService.stageRevision(normalized.mediaId(), goal, resolvedMode, correctedPlan);
    }

    public String revisionGoal(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        return normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal();
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointService.cancelStagedRevision(mediaId, goal, mode);
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 复用。 */
    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state) {
        return reuseResult(mediaId, sourceMediaId, state, AnalysisMode.GENERAL);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state, AnalysisMode mode) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("media does not exist: " + mediaId);

        VideoContext sourceContext = checkpointService.loadContext(sourceMediaId);
        if (sourceContext == null) return false;
        checkpointService.saveContext(mediaId, reusableContext(mediaFile.getFilePath(), sourceContext));
        checkpointService.saveResult(mediaId, new AgentState(
                state.goal(), state.plan(), state.result(), state.critique(), state.round()), mode);
        persistResult(mediaFile, state, null);
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

    private String contextualQuestion(String originalGoal, AgentState previous, String question, List<String> history, String evidenceSummary) {
        StringBuilder sb = new StringBuilder("这是对同一视频的继续追问。请结合原始视频证据和已有分析回答当前问题。\n");
        if (originalGoal != null && !originalGoal.isBlank()) {
            sb.append("原始目标：").append(originalGoal).append("\n");
        }
        if (previous != null && previous.result() != null) {
            String previousResult = previous.result().toMarkdown();
            if (previousResult.length() > 4_000) previousResult = previousResult.substring(0, 4_000);
            sb.append("已有分析：").append(previousResult).append("\n");
        }
        if (history != null && !history.isEmpty()) {
            sb.append("历史对话：\n");
            for (String item : history) {
                sb.append(item).append("\n");
            }
        }
        if (evidenceSummary != null && !evidenceSummary.isBlank()) {
            sb.append("Function Calling 检索到的证据：\n").append(evidenceSummary).append("\n");
        }
        sb.append("当前追问：").append(question);
        return sb.toString();
    }

    private List<String> loadFollowUpHistory(String key, Long mediaId) {
        try {
            List<String> history = redisTemplate.opsForList().range(key, 0, FOLLOW_UP_HISTORY_MAX - 1);
            return history == null ? List.of() : history;
        } catch (Exception e) {
            log.warn("读取追问历史失败 mediaId={}", mediaId, e);
            return List.of();
        }
    }

    private void saveFollowUpHistory(String key, Long mediaId, String question, String answer) {
        try {
            String entry = "问：" + question + "\n答：" + answer;
            redisTemplate.opsForList().rightPush(key, entry);
            redisTemplate.opsForList().trim(key, -FOLLOW_UP_HISTORY_MAX, -1);
            redisTemplate.expire(key, FOLLOW_UP_HISTORY_TTL);
        } catch (Exception e) {
            log.warn("保存追问历史失败 mediaId={}", mediaId, e);
        }
    }

    private String followUpHistoryKey(Long mediaId, String originalGoal, AnalysisMode mode) {
        String scopedGoal = originalGoal == null || originalGoal.isBlank()
                ? "__FOLLOW_UP_WITHOUT_ORIGINAL_GOAL__"
                : originalGoal;
        return FOLLOW_UP_HISTORY_KEY_PREFIX + mediaId + ":"
                + AnalysisTaskKeys.goalDigest(scopedGoal, mode);
    }

    /**
     * 轻量 Function Calling：让模型通过 searchEvidence 工具检索视频证据，
     * 再把检索结果摘要返回给上层。任何异常都不会阻断追问主流程。
     */
    private String retrieveEvidenceWithFunctionCalling(Long mediaId, VideoContext context, String question) {
        try {
            ToolSpecification searchTool = ToolSpecification.builder()
                    .name("searchEvidence")
                    .description("在视频的时间轴证据中检索与问题相关的片段，返回时间戳、语音文字和 OCR 文本。")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("query", "检索关键词或问题描述")
                            .required("query")
                            .build())
                    .build();

            String systemPrompt = """
                    你是视频证据检索助手。根据用户的追问，调用 searchEvidence 工具检索相关视频证据。
                    如果一次检索不够，可以多次调用。最后用简短中文总结检索到的证据要点。
                    """;
            String userPrompt = "当前追问：" + question;

            return deepSeekUtils.chatWithTools(
                    "FOLLOW_UP_RETRIEVAL",
                    systemPrompt,
                    userPrompt,
                    List.of(searchTool),
                    arguments -> {
                        String query = extractQuery(arguments);
                        VideoContext searchContext = new VideoContext(
                                context.source(), query, context.segments());
                        List<VideoEvidenceHit> hits = longVideoContextService.searchEvidence(mediaId, searchContext);
                        return formatHits(hits);
                    },
                    3
            );
        } catch (AgentExecutionBudget.DeadlineExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Function Calling 检索证据失败，跳过增强检索 mediaId={}", mediaId, e);
            return "";
        }
    }

    private String extractQuery(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode query = node.get("query");
            return query == null ? "" : query.asText();
        } catch (Exception e) {
            log.warn("解析 searchEvidence 参数失败: {}", arguments, e);
            return "";
        }
    }

    private String formatHits(List<VideoEvidenceHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关证据";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(hits.size(), 5);
        for (int i = 0; i < limit; i++) {
            VideoEvidenceHit hit = hits.get(i);
            sb.append(i + 1).append(". [")
                    .append(hit.startMs()).append("ms-").append(hit.endMs()).append("ms] ")
                    .append(hit.snippet() == null ? "" : hit.snippet()).append("\n");
            if (hit.transcript() != null && !hit.transcript().isBlank()) {
                sb.append("语音：").append(hit.transcript()).append("\n");
            }
            if (hit.ocrTexts() != null && !hit.ocrTexts().isEmpty()) {
                sb.append("OCR：").append(String.join(" ", hit.ocrTexts())).append("\n");
            }
        }
        return sb.toString();
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState, String transcript) {
        if (agentState.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        MediaFile update = new MediaFile();
        update.setId(mediaFile.getId());
        update.setAiSummary(agentState.result().toMarkdown());
        // Do not write back the stale transcript loaded before the long-running analysis.
        mediaFileMapper.updateById(update);
        // Preserve the existing analysis-to-transcript behavior without overwriting
        // a separate transcription that completed while the Agent was running.
        if (transcript != null && !transcript.isBlank()) {
            MediaFile transcriptUpdate = new MediaFile();
            transcriptUpdate.setTranscriptText(transcript);
            mediaFileMapper.update(transcriptUpdate, new UpdateWrapper<MediaFile>()
                    .eq("id", mediaFile.getId())
                    .and(query -> query.isNull("transcript_text").or().eq("transcript_text", "")));
        }
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
