package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.mapper.AgentCheckpointMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class AgentCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpointService.class);
    private static final int MAX_FEEDBACK_SAMPLES = 200;
    private static final Duration CHECKPOINT_TTL = Duration.ofDays(7);
    private static final Duration REVISION_TTL = Duration.ofHours(2);
    private static final Duration FEEDBACK_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointMapper checkpointMapper;

    public AgentCheckpointService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  AgentCheckpointMapper checkpointMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointMapper = checkpointMapper;
    }

    public VideoContext loadContext(Long mediaId) {
        return read(mediaId, mediaCheckpoint("context"), checkpointKey(mediaId),
                "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return read(mediaId, goalCheckpoint(goal, "result"), goalKey(mediaId, goal),
                "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return read(mediaId, goalCheckpoint(goal, "plan"), goalKey(mediaId, goal),
                "plan", AgentState.AgentPlan.class);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return read(mediaId, goalCheckpoint(goal, "criticState"), goalKey(mediaId, goal),
                "criticState", AgentState.class);
    }

    public String loadStage(Long mediaId, String goal) {
        try {
            Object stage = redisTemplate.opsForHash().get(goalKey(mediaId, goal), "stage");
            if (stage != null) return stage.toString();
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_stage_cache_read_failed mediaId={}", mediaId, e);
        }
        String persisted = checkpointMapper.findStage(mediaId, goalCheckpoint(goal, "stage"));
        if (persisted != null) cacheStage(goalKey(mediaId, goal), persisted);
        return persisted;
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        try {
            Object value = redisTemplate.opsForHash().get(checkpointKey(mediaId), "chunks");
            if (value != null) {
                return objectMapper.readValue(value.toString(), new TypeReference<List<VideoChunk>>() { });
            }
        } catch (Exception e) {
            log.warn("video_chunk_checkpoint_cache_read_failed mediaId={}", mediaId, e);
            try {
                redisTemplate.opsForHash().delete(checkpointKey(mediaId), "chunks");
            } catch (RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
        }
        try {
            String persisted = checkpointMapper.findPayload(mediaId, mediaCheckpoint("chunks"));
            if (persisted == null) return null;
            List<VideoChunk> chunks = objectMapper.readValue(
                    persisted, new TypeReference<List<VideoChunk>>() { });
            cacheField(checkpointKey(mediaId), "chunks", persisted, "CHUNKS_COMPLETED");
            return chunks;
        } catch (Exception e) {
            log.warn("video_chunk_checkpoint_read_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    public void saveContext(Long mediaId, VideoContext context) {
        VideoContext reusableContext = new VideoContext(context.source(), "", context.segments());
        write(mediaId, mediaCheckpoint("context"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "context", "CONTEXT_COMPLETED", reusableContext);
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        write(mediaId, mediaCheckpoint("chunks"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "chunks", "CHUNKS_COMPLETED", List.copyOf(chunks));
    }

    public void saveResult(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "ANALYSIS_COMPLETED" : "ANALYSIS_COMPLETED_WITH_WARNINGS";
        String key = goalKey(mediaId, state.goal());
        write(mediaId, goalCheckpoint(state.goal(), "result"), goalCheckpoint(state.goal(), "stage"),
                key, "result", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = goalKey(mediaId, goal);
        write(mediaId, goalCheckpoint(goal, "plan"), goalCheckpoint(goal, "stage"),
                key, "plan", "PLAN_COMPLETED", plan);
        rememberGoalKey(mediaId, key);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "CRITIC_PASSED" : "CRITIC_RETRY_REQUIRED";
        String key = goalKey(mediaId, state.goal());
        write(mediaId, goalCheckpoint(state.goal(), "criticState"), goalCheckpoint(state.goal(), "stage"),
                key, "criticState", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void stageRevision(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = revisionKey(mediaId, goal);
        try {
            redisTemplate.opsForHash().put(key, "pending", "1");
            if (plan != null) {
                redisTemplate.opsForHash().put(key, "plan", objectMapper.writeValueAsString(plan));
            }
            redisTemplate.expire(key, REVISION_TTL);
            rememberGoalKey(mediaId, key);
        } catch (Exception e) {
            throw new IllegalStateException("暂存 Agent 修正计划失败", e);
        }
    }

    public boolean beginStagedRevision(Long mediaId, String goal) {
        String revisionKey = revisionKey(mediaId, goal);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(revisionKey))) return false;

        AgentState.AgentPlan plan = readRedis(revisionKey, "plan", AgentState.AgentPlan.class);
        checkpointMapper.deleteByPrefix(mediaId, goalCheckpoint(goal, ""));
        redisTemplate.delete(goalKey(mediaId, goal));
        if (plan != null) savePlan(mediaId, goal, plan);
        redisTemplate.delete(revisionKey);
        return true;
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        redisTemplate.delete(revisionKey(mediaId, goal));
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

    public void saveFailure(Long mediaId, String goal, String failedStage, Exception error) {
        String key = goalKey(mediaId, goal);
        checkpointMapper.upsert(mediaId, goalCheckpoint(goal, "stage"), "FAILED", null);
        redisTemplate.opsForHash().put(key, "stage", "FAILED");
        redisTemplate.opsForHash().put(key, "failedStage", failedStage);
        redisTemplate.opsForHash().put(key, "errorType", error.getClass().getSimpleName());
        redisTemplate.expire(key, CHECKPOINT_TTL);
        rememberGoalKey(mediaId, key);
    }

    public void deleteMedia(Long mediaId) {
        checkpointMapper.deleteByMediaId(mediaId);
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

    private <T> T read(Long mediaId,
                       String checkpointName,
                       String redisKey,
                       String field,
                       Class<T> type) {
        T cached = readRedis(redisKey, field, type);
        if (cached != null) return cached;
        try {
            String payload = checkpointMapper.findPayload(mediaId, checkpointName);
            if (payload == null) return null;
            T value = objectMapper.readValue(payload, type);
            String stage = checkpointMapper.findStage(mediaId, checkpointName);
            cacheField(redisKey, field, payload, stage);
            return value;
        } catch (Exception e) {
            log.warn("agent_checkpoint_database_read_failed mediaId={} checkpoint={}",
                    mediaId, checkpointName, e);
            return null;
        }
    }

    private <T> T readRedis(String key, String field, Class<T> type) {
        try {
            Object value = redisTemplate.opsForHash().get(key, field);
            return value == null ? null : objectMapper.readValue(value.toString(), type);
        } catch (Exception e) {
            log.warn("agent_checkpoint_read_failed key={} field={}", key, field, e);
            try {
                redisTemplate.opsForHash().delete(key, field);
            } catch (RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            return null;
        }
    }

    private void write(Long mediaId,
                       String checkpointName,
                       String stageCheckpointName,
                       String redisKey,
                       String field,
                       String stage,
                       Object value) {
        try {
            String payload = objectMapper.writeValueAsString(value);
            // MySQL 是恢复真源，Redis 丢了只会变慢，不会让用户的 Agent 进度消失。
            checkpointMapper.upsert(mediaId, checkpointName, stage, payload);
            checkpointMapper.upsert(mediaId, stageCheckpointName, stage, null);
            cacheField(redisKey, field, payload, stage);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", e);
        }
    }

    private void cacheField(String key, String field, String payload, String stage) {
        try {
            redisTemplate.opsForHash().put(key, field, payload);
            if (stage != null) redisTemplate.opsForHash().put(key, "stage", stage);
            redisTemplate.expire(key, CHECKPOINT_TTL);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_cache_write_failed key={} field={}", key, field, e);
        }
    }

    private void cacheStage(String key, String stage) {
        try {
            redisTemplate.opsForHash().put(key, "stage", stage);
            redisTemplate.expire(key, CHECKPOINT_TTL);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_stage_cache_write_failed key={}", key, e);
        }
    }

    private void rememberGoalKey(Long mediaId, String key) {
        try {
            redisTemplate.opsForSet().add(goalIndexKey(mediaId), key);
            redisTemplate.expire(goalIndexKey(mediaId), CHECKPOINT_TTL);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_index_write_failed mediaId={} key={}", mediaId, key, e);
        }
    }

    private String checkpointKey(Long mediaId) {
        return "agent:checkpoint:" + mediaId;
    }

    private String goalKey(Long mediaId, String goal) {
        return checkpointKey(mediaId) + ":goal:" + AnalysisTaskKeys.goalDigest(goal);
    }

    private String feedbackKey(Long mediaId) {
        return "agent:feedback:" + mediaId;
    }

    private String revisionKey(Long mediaId, String goal) {
        return goalKey(mediaId, goal) + ":revision";
    }

    private String goalIndexKey(Long mediaId) {
        return checkpointKey(mediaId) + ":goals";
    }

    private String mediaCheckpoint(String field) {
        return "media:" + field;
    }

    private String goalCheckpoint(String goal, String field) {
        return "goal:" + AnalysisTaskKeys.goalDigest(goal) + ":" + field;
    }
}
