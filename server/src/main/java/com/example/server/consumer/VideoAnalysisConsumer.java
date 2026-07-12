package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.service.AiService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-group}")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    private final AiService aiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;

    public VideoAnalysisConsumer(AiService aiService,
                                 RedissonClient redissonClient,
                                 StringRedisTemplate redisTemplate) {
        this.aiService = aiService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        if (msg == null || msg.getMediaId() == null || msg.getUserGoal() == null
                || msg.getUserGoal().isBlank()) {
            throw new IllegalArgumentException("invalid video analysis message");
        }
        Long mediaId = msg.getMediaId();
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal());
        String lockKey = AnalysisTaskKeys.lock(contentHash, goalDigest);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        String completedKey = AnalysisTaskKeys.completed(mediaId, goalDigest);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            if (!acquired || Boolean.TRUE.equals(redisTemplate.hasKey(completedKey))) {
                log.info("video_analysis_skipped mediaId={} acquired={}", mediaId, acquired);
                return;
            }
            aiService.asyncAnalyze(mediaId, msg.getUserGoal());
            redisTemplate.opsForValue().set(completedKey, "1", java.time.Duration.ofDays(7));
        } catch (Exception e) {
            log.error("video_analysis_consume_failed mediaId={}", mediaId, e);
            throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", e);
        } finally {
            if (acquired) {
                redisTemplate.delete(activeKey);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
