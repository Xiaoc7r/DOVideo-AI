package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.repository.AgentCheckpointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对外只暴露 Agent 领域里的计划、上下文、Critic 和结果 Checkpoint。
 *
 * <p>目标级 Checkpoint 的键 = (mediaId, goalDigest(goal, mode))。每个目标级方法都提供
 * “带 {@link AnalysisMode}” 与 “不带(默认 GENERAL)” 两个重载:前者让不同模式的同一目标互不
 * 覆盖,后者保证历史调用/遗漏调用安全降级为 GENERAL 且键逐字节不变。上下文、分块、反馈按
 * mediaId(内容级)存储,与模式无关,不受影响。
 */
@Service
public class AgentCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpointService.class);
    private static final int MAX_FEEDBACK_SAMPLES = 200;
    private static final Duration FEEDBACK_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointRepository checkpointRepository;

    public AgentCheckpointService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  AgentCheckpointRepository checkpointRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointRepository = checkpointRepository;
    }

    public VideoContext loadContext(Long mediaId) {
        return checkpointRepository.read(mediaId, mediaCheckpoint("context"), checkpointKey(mediaId),
                "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return loadResult(mediaId, goal, AnalysisMode.GENERAL);
    }

    public AgentState loadResult(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, mode, "result"), goalKey(mediaId, goal, mode),
                "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return loadPlan(mediaId, goal, AnalysisMode.GENERAL);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, mode, "plan"), goalKey(mediaId, goal, mode),
                "plan", AgentState.AgentPlan.class);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return loadCriticState(mediaId, goal, AnalysisMode.GENERAL);
    }

    public AgentState loadCriticState(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, mode, "criticState"), goalKey(mediaId, goal, mode),
                "criticState", AgentState.class);
    }

    public TaskStage loadStage(Long mediaId, String goal) {
        return loadStage(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStage loadStage(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointRepository.readStage(
                mediaId, goalCheckpoint(goal, mode, "stage"), goalKey(mediaId, goal, mode));
    }

    public void saveStage(Long mediaId, String goal, TaskStage stage) {
        saveStage(mediaId, goal, AnalysisMode.GENERAL, stage);
    }

    public void saveStage(Long mediaId, String goal, AnalysisMode mode, TaskStage stage) {
        String key = goalKey(mediaId, goal, mode);
        checkpointRepository.writeStage(mediaId, goalCheckpoint(goal, mode, "stage"), key, stage);
        rememberGoalKey(mediaId, key);
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        return checkpointRepository.read(
                mediaId,
                mediaCheckpoint("chunks"),
                checkpointKey(mediaId),
                "chunks",
                new TypeReference<List<VideoChunk>>() { });
    }

    public void saveContext(Long mediaId, VideoContext context) {
        VideoContext reusableContext = new VideoContext(context.source(), "", context.segments());
        checkpointRepository.write(mediaId, mediaCheckpoint("context"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "context", TaskStage.CONTEXT_COMPLETED, reusableContext);
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        checkpointRepository.write(mediaId, mediaCheckpoint("chunks"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "chunks", TaskStage.CHUNKS_COMPLETED, List.copyOf(chunks));
    }

    public void saveResult(Long mediaId, AgentState state) {
        saveResult(mediaId, state, AnalysisMode.GENERAL);
    }

    public void saveResult(Long mediaId, AgentState state, AnalysisMode mode) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.ANALYSIS_COMPLETED : TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
        String key = goalKey(mediaId, state.goal(), mode);
        checkpointRepository.write(mediaId, goalCheckpoint(state.goal(), mode, "result"),
                goalCheckpoint(state.goal(), mode, "stage"),
                key, "result", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        savePlan(mediaId, goal, AnalysisMode.GENERAL, plan);
    }

    public void savePlan(Long mediaId, String goal, AnalysisMode mode, AgentState.AgentPlan plan) {
        String key = goalKey(mediaId, goal, mode);
        checkpointRepository.write(mediaId, goalCheckpoint(goal, mode, "plan"), goalCheckpoint(goal, mode, "stage"),
                key, "plan", TaskStage.PLAN_COMPLETED, plan);
        rememberGoalKey(mediaId, key);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        saveCriticState(mediaId, state, AnalysisMode.GENERAL);
    }

    public void saveCriticState(Long mediaId, AgentState state, AnalysisMode mode) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.CRITIC_PASSED : TaskStage.CRITIC_RETRY_REQUIRED;
        String key = goalKey(mediaId, state.goal(), mode);
        checkpointRepository.write(mediaId, goalCheckpoint(state.goal(), mode, "criticState"),
                goalCheckpoint(state.goal(), mode, "stage"),
                key, "criticState", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void saveExecutionState(Long mediaId, AgentState state) {
        saveExecutionState(mediaId, state, AnalysisMode.GENERAL);
    }

    public void saveExecutionState(Long mediaId, AgentState state, AnalysisMode mode) {
        String key = goalKey(mediaId, state.goal(), mode);
        checkpointRepository.write(mediaId,
                goalCheckpoint(state.goal(), mode, "criticState"),
                goalCheckpoint(state.goal(), mode, "stage"),
                key, "criticState", TaskStage.EXECUTOR_COMPLETED, state);
        rememberGoalKey(mediaId, key);
    }

    public void stageRevision(Long mediaId, String goal, AgentState.AgentPlan plan) {
        stageRevision(mediaId, goal, AnalysisMode.GENERAL, plan);
    }

    public void stageRevision(Long mediaId, String goal, AnalysisMode mode, AgentState.AgentPlan plan) {
        String key = revisionKey(mediaId, goal, mode);
        checkpointRepository.writeStandalone(
                mediaId,
                revisionCheckpoint(goal, mode),
                key,
                "revision",
                TaskStage.REVISION_PENDING,
                new RevisionCheckpoint(plan, false));
        rememberGoalKey(mediaId, key);
    }

    public boolean beginStagedRevision(Long mediaId, String goal) {
        return beginStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean isRevisionPending(Long mediaId, String goal, AnalysisMode mode) {
        RevisionCheckpoint revision = checkpointRepository.read(mediaId, revisionCheckpoint(goal, mode),
                revisionKey(mediaId, goal, mode), "revision", RevisionCheckpoint.class);
        return revision != null && !revision.applied();
    }

    @Transactional
    public boolean beginStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        String key = revisionKey(mediaId, goal, mode);
        RevisionCheckpoint revision = checkpointRepository.read(
                mediaId, revisionCheckpoint(goal, mode), key, "revision", RevisionCheckpoint.class);
        if (revision == null) return false;
        if (revision.applied()) return true;

        checkpointRepository.deleteByPrefix(mediaId, goalCheckpoint(goal, mode, ""));
        redisTemplate.delete(goalKey(mediaId, goal, mode));
        if (revision.plan() != null) savePlan(mediaId, goal, mode, revision.plan());
        checkpointRepository.writeStandalone(
                mediaId,
                revisionCheckpoint(goal, mode),
                key,
                "revision",
                TaskStage.REVISION_APPLIED,
                new RevisionCheckpoint(revision.plan(), true));
        return true;
    }

    public void completeStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointRepository.delete(
                mediaId, revisionCheckpoint(goal, mode), revisionKey(mediaId, goal, mode));
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointRepository.delete(
                mediaId, revisionCheckpoint(goal, mode), revisionKey(mediaId, goal, mode));
    }

    public void saveFeedback(AgentFeedback feedback) {
        try {
            redisTemplate.opsForList().rightPush(
                    feedbackKey(feedback.mediaId()), objectMapper.writeValueAsString(feedback.normalized()));
            redisTemplate.opsForList().trim(feedbackKey(feedback.mediaId()), -MAX_FEEDBACK_SAMPLES, -1);
            redisTemplate.expire(feedbackKey(feedback.mediaId()), FEEDBACK_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent 用户反馈失败", e);
        }
    }

    public List<AgentFeedback> loadFeedback(Long mediaId) {
        List<String> values = redisTemplate.opsForList().range(feedbackKey(mediaId), 0, -1);
        if (values == null) return List.of();
        return values.stream().map(value -> {
            try {
                return objectMapper.readValue(value, AgentFeedback.class);
            } catch (Exception e) {
                log.warn("agent_feedback_deserialize_failed mediaId={}", mediaId, e);
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    public void saveFailure(Long mediaId, String goal, TaskStage failedStage, Exception error) {
        saveFailure(mediaId, goal, AnalysisMode.GENERAL, failedStage, error);
    }

    public void saveFailure(Long mediaId, String goal, AnalysisMode mode, TaskStage failedStage, Exception error) {
        String key = goalKey(mediaId, goal, mode);
        checkpointRepository.writeStage(
                mediaId, goalCheckpoint(goal, mode, "stage"), key, TaskStage.FAILED);
        redisTemplate.opsForHash().put(key, "failedStage", failedStage.name());
        redisTemplate.opsForHash().put(key, "errorType", error.getClass().getSimpleName());
        redisTemplate.expire(key, Duration.ofDays(7));
        rememberGoalKey(mediaId, key);
    }

    public void deleteMedia(Long mediaId) {
        checkpointRepository.deleteByMediaId(mediaId);
        try {
            Set<String> goalKeys = redisTemplate.opsForSet().members(goalIndexKey(mediaId));
            List<String> keys = new ArrayList<>();
            keys.add(checkpointKey(mediaId));
            keys.add(feedbackKey(mediaId));
            keys.add(goalIndexKey(mediaId));
            if (goalKeys != null) keys.addAll(goalKeys);
            redisTemplate.delete(keys);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_cache_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void rememberGoalKey(Long mediaId, String key) {
        try {
            redisTemplate.opsForSet().add(goalIndexKey(mediaId), key);
            redisTemplate.expire(goalIndexKey(mediaId), Duration.ofDays(7));
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_index_write_failed mediaId={} key={}", mediaId, key, e);
        }
    }

    private String checkpointKey(Long mediaId) {
        return "agent:checkpoint:" + mediaId;
    }

    private String goalKey(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointKey(mediaId) + ":goal:" + AnalysisTaskKeys.goalDigest(goal, mode);
    }

    private String feedbackKey(Long mediaId) {
        return "agent:feedback:" + mediaId;
    }

    private String revisionKey(Long mediaId, String goal, AnalysisMode mode) {
        return goalKey(mediaId, goal, mode) + ":revision";
    }

    private String revisionCheckpoint(String goal, AnalysisMode mode) {
        return "revision:" + AnalysisTaskKeys.goalDigest(goal, mode);
    }

    private String goalIndexKey(Long mediaId) {
        return checkpointKey(mediaId) + ":goals";
    }

    private String mediaCheckpoint(String field) {
        return "media:" + field;
    }

    private String goalCheckpoint(String goal, AnalysisMode mode, String field) {
        return "goal:" + AnalysisTaskKeys.goalDigest(goal, mode) + ":" + field;
    }

    private record RevisionCheckpoint(AgentState.AgentPlan plan, boolean applied) {
    }
}
