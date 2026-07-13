package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AgentCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpointService.class);
    private static final int MAX_FEEDBACK_SAMPLES = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentCheckpointService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public VideoContext loadContext(Long mediaId) {
        return read(checkpointKey(mediaId), "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "plan", AgentState.AgentPlan.class);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "criticState", AgentState.class);
    }

    public String loadStage(Long mediaId, String goal) {
        Object stage = redisTemplate.opsForHash().get(goalKey(mediaId, goal), "stage");
        return stage == null ? null : stage.toString();
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        try {
            Object value = redisTemplate.opsForHash().get(checkpointKey(mediaId), "chunks");
            return value == null
                    ? null
                    : objectMapper.readValue(value.toString(), new TypeReference<List<VideoChunk>>() { });
        } catch (Exception e) {
            log.warn("video_chunk_checkpoint_read_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    public void saveContext(Long mediaId, VideoContext context) {
        VideoContext reusableContext = new VideoContext(context.source(), "", context.segments());
        write(checkpointKey(mediaId), "context", "CONTEXT_COMPLETED", reusableContext);
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        write(checkpointKey(mediaId), "chunks", "CHUNKS_COMPLETED", List.copyOf(chunks));
    }

    public void saveResult(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "ANALYSIS_COMPLETED" : "ANALYSIS_COMPLETED_WITH_WARNINGS";
        write(goalKey(mediaId, state.goal()), "result", stage, state);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        write(goalKey(mediaId, goal), "plan", "PLAN_COMPLETED", plan);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "CRITIC_PASSED" : "CRITIC_RETRY_REQUIRED";
        write(goalKey(mediaId, state.goal()), "criticState", stage, state);
    }

    public void resetForRerun(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = goalKey(mediaId, goal);
        redisTemplate.delete(key);
        if (plan != null) savePlan(mediaId, goal, plan);
    }

    public void saveFeedback(AgentFeedback feedback) {
        try {
            redisTemplate.opsForList().rightPush(
                    feedbackKey(feedback.mediaId()), objectMapper.writeValueAsString(feedback.normalized()));
            redisTemplate.opsForList().trim(feedbackKey(feedback.mediaId()), -MAX_FEEDBACK_SAMPLES, -1);
            redisTemplate.expire(feedbackKey(feedback.mediaId()), 30, TimeUnit.DAYS);
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
        redisTemplate.opsForHash().put(key, "stage", "FAILED");
        redisTemplate.opsForHash().put(key, "failedStage", failedStage);
        redisTemplate.opsForHash().put(key, "errorType", error.getClass().getSimpleName());
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    private <T> T read(String key, String field, Class<T> type) {
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

    private void write(String key, String field, String stage, Object value) {
        try {
            redisTemplate.opsForHash().putAll(key, Map.of(
                    field, objectMapper.writeValueAsString(value),
                    "stage", stage));
            redisTemplate.expire(key, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", e);
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
}
