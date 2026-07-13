package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final TaskEventService taskEventService;
    private final String analysisTopic;

    public AnalysisDispatchService(AiService aiService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   RedissonClient redissonClient,
                                   TaskEventService taskEventService,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                   String analysisTopic) {
        this.aiService = aiService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redissonClient = redissonClient;
        this.taskEventService = taskEventService;
        this.analysisTopic = analysisTopic;
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = revision == null ? contentHash(mediaId) : "media-" + mediaId;
        String goalDigest = AnalysisTaskKeys.goalDigest(goal);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) return SubmissionResult.DUPLICATE;

        try {
            if (!tryAcquireQuota(mediaFile.getUserId())) {
                redisTemplate.delete(activeKey);
                return SubmissionResult.RATE_LIMITED;
            }
            // 旧结果先留着。消费者真正接手后再切 Checkpoint，MQ 投递失败时用户还有结果可看。
            if (revision != null) aiService.stageRevision(revision);
            rocketMQTemplate.convertAndSend(
                    analysisTopic,
                    new AnalysisTaskMsg(mediaId, action, contentHash, goal));
            taskEventService.publishAnalysis(mediaId, goal,
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已进入异步分析队列"), "QUEUED");
            return SubmissionResult.ACCEPTED;
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }
    }

    public boolean isActive(Long mediaId, String goal) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal);
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(contentHash(mediaId), goalDigest)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
    }

    private boolean tryAcquireQuota(Long userId) {
        RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:user:" + userId);
        userLimiter.trySetRate(RateType.OVERALL, USER_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        if (!userLimiter.tryAcquire()) return false;

        RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:global");
        globalLimiter.trySetRate(
                RateType.OVERALL, GLOBAL_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        return globalLimiter.tryAcquire();
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, redisTemplate.opsForValue().get("media:md5:" + mediaId));
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DUPLICATE,
        FAILED
    }
}
