package com.example.server.consumer;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.service.AiService;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentLoopService;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.NoSuchElementException;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-group}",
        // maxReconsumeTimes 计的是「重投次数」，2 次重投 = 最多 3 次投递，与下面的
        // MAX_DELIVERY_ATTEMPTS（计投递次数）对齐。不设的话，一旦异常发生在 Redis 计数之前，
        // 应用侧上限失效，会退化成 MQ 默认的 16 次重投空转。
        //
        // 消费并发（consumeThreadNumber / consumeThreadMax）刻意没有在这里设置：
        // 两个属性名在 rocketmq-spring 各版本间有变更（consumeThreadMax 自 2.2.x 起废弃），
        // 若与容器默认的 min 值冲突会在启动期抛 "consumeThreadMin is larger than consumeThreadMax"。
        // 本环境无法编译校验属性名，故留给确认版本后再补，避免引入启动失败风险。
        maxReconsumeTimes = 2)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);
    /** 应用级投递上限，需与注解上的 maxReconsumeTimes 保持一致。 */
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    /** cause 链遍历深度上限，防御异常自引用导致的死循环。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    private final AiService aiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final AgentCheckpointService checkpointService;
    private final RocketMQTemplate rocketMQTemplate;
    private final FailedAnalysisTaskService failedTaskService;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final String deadLetterTopic;

    public VideoAnalysisConsumer(AiService aiService,
                                 RedissonClient redissonClient,
                                 StringRedisTemplate redisTemplate,
                                 AgentCheckpointService checkpointService,
                                 RocketMQTemplate rocketMQTemplate,
                                 FailedAnalysisTaskService failedTaskService,
                                 MediaService mediaService,
                                 TaskEventService taskEventService,
                                 @Value("${rocketmq.topic.video-analysis-dead:video-analysis-dead-topic}")
                                 String deadLetterTopic) {
        this.aiService = aiService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.checkpointService = checkpointService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.failedTaskService = failedTaskService;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        String rejection = rejectionReason(msg);
        if (rejection != null) {
            discardPoisonMessage(msg, rejection);
            return;
        }
        Long mediaId = msg.getMediaId();
        // 任务身份带上模式:与投递方(AnalysisDispatchService)和 checkpoint 保持同一套键,
        // 否则同一目标不同模式会互相串键。
        AnalysisMode mode = AnalysisMode.fromNullable(msg.getMode());
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal(), mode);
        String lockKey = AnalysisTaskKeys.lock(contentHash, goalDigest);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        String completedKey = AnalysisTaskKeys.completed(contentHash, goalDigest);
        String attemptsKey = AnalysisTaskKeys.attempts(contentHash, goalDigest);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        boolean retrying = false;
        long attempt = 0;
        try {
            acquired = lock.tryLock();
            if (!acquired) {
                log.info("video_analysis_skipped mediaId={} acquired={}", mediaId, acquired);
                return;
            }
            if (!mediaService.exists(mediaId)) {
                log.info("video_analysis_discarded_deleted_media mediaId={}", mediaId);
                return;
            }
            Long currentAttempt = redisTemplate.opsForValue().increment(attemptsKey);
            attempt = currentAttempt == null ? 1 : currentAttempt;
            redisTemplate.expire(attemptsKey, ACTIVE_TTL);
            taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "视频分析任务开始执行"),
                    TaskStage.CONSUMING);
            if (msg.isRevision()) {
                if (!checkpointService.beginStagedRevision(mediaId, msg.getUserGoal(), mode)) {
                    // The revision marker is removed after success. A broker redelivery after
                    // that point must acknowledge the saved result instead of retrying forever.
                    AgentState completed = checkpointService.loadResult(mediaId, msg.getUserGoal(), mode);
                    if (completed != null && completed.result() != null) {
                        taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                                TaskStatus.completed(completed), TaskStage.COMPLETED);
                        return;
                    }
                    throw new IllegalStateException("修订任务状态不存在，等待消息队列重试");
                }
                redisTemplate.delete(completedKey);
            } else {
                String completedMediaId = redisTemplate.opsForValue().get(completedKey);
                if (completedMediaId != null) {
                    Long sourceMediaId = parseMediaId(completedMediaId, completedKey);
                    AgentState reusable = sourceMediaId == null ? null
                            : checkpointService.loadResult(sourceMediaId, msg.getUserGoal(), mode);
                    if (reusable != null && reusable.result() != null
                            && aiService.reuseResult(mediaId, sourceMediaId, reusable, mode)) {
                        taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                                TaskStatus.completed(reusable), TaskStage.COMPLETED_REUSED);
                        log.info("video_analysis_reused mediaId={} sourceMediaId={}", mediaId, sourceMediaId);
                        return;
                    }
                    redisTemplate.delete(completedKey);
                }
            }
            saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.CONSUMING);
            aiService.asyncAnalyze(mediaId, msg.getUserGoal(), mode);
            if (msg.isRevision()) {
                checkpointService.completeStagedRevision(mediaId, msg.getUserGoal(), mode);
            }
            if (!mediaService.exists(mediaId)) {
                mediaService.purgeRuntimeArtifacts(mediaId);
                log.info("video_analysis_cleanup_after_media_deleted mediaId={}", mediaId);
                return;
            }
            redisTemplate.opsForValue().set(
                    completedKey, String.valueOf(mediaId), Duration.ofDays(7));
            AgentState completed = checkpointService.loadResult(mediaId, msg.getUserGoal(), mode);
            if (completed != null && completed.result() != null) {
                taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                        TaskStatus.completed(completed), TaskStage.COMPLETED);
            }
        } catch (AgentLoopService.BudgetExceededException e) {
            saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.BUDGET_EXHAUSTED);
            taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.FAILED, e.getMessage()),
                    TaskStage.BUDGET_EXHAUSTED);
            log.warn("video_analysis_budget_exhausted mediaId={} reason={}", mediaId, e.getMessage());
            return;
        } catch (Exception e) {
            // 参数非法、资源不存在、越权这类失败重投多少次都一样，直接收敛到失败台账，
            // 不再浪费两轮完整的 ASR + LLM 流水线。
            boolean permanent = isPermanentFailure(e);
            if (!permanent && acquired && attempt > 0 && attempt < MAX_DELIVERY_ATTEMPTS) {
                // 重试期间 active 不能掉，不然前端会以为任务结束，又塞进来一份相同工作。
                retrying = true;
                redisTemplate.expire(activeKey, ACTIVE_TTL);
                saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.RETRYING);
                taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                        TaskStatus.of(TaskStatus.State.PROCESSING, "本次执行失败，等待消息队列重试"),
                        TaskStage.RETRYING);
                log.warn("video_analysis_retry_scheduled mediaId={} attempt={}", mediaId, attempt, e);
                throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", e);
            }
            if (acquired && (permanent || attempt >= MAX_DELIVERY_ATTEMPTS)) {
                try {
                    // 到这里就别无限重放了，原消息留到失败主题，后面排查还有抓手。
                    try {
                        failedTaskService.record(msg, attempt, e);
                    } catch (RuntimeException recordError) {
                        e.addSuppressed(recordError);
                        log.error("failed_analysis_record_write_failed mediaId={}", mediaId, recordError);
                    }
                    rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
                    saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.DEAD_LETTERED);
                    taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                            TaskStatus.of(TaskStatus.State.FAILED, "分析失败，已进入人工处理队列"),
                            TaskStage.DEAD_LETTERED);
                    log.error("video_analysis_dead_lettered mediaId={} attempts={} permanent={}",
                            mediaId, attempt, permanent, e);
                    return;
                } catch (RuntimeException deadLetterError) {
                    retrying = true;
                    deadLetterError.addSuppressed(e);
                    log.error("video_analysis_dead_letter_dispatch_failed mediaId={}", mediaId, deadLetterError);
                    throw deadLetterError;
                }
            }
            log.error("video_analysis_consume_failed mediaId={}", mediaId, e);
            throw new IllegalStateException("视频分析消费失败", e);
        } finally {
            if (acquired) {
                try {
                    if (!retrying) redisTemplate.delete(java.util.List.of(activeKey, attemptsKey));
                } catch (RuntimeException cleanupError) {
                    // A transient cache failure must not skip unlock: the watchdog would keep
                    // renewing this lock and prevent later consumers from making progress.
                    log.warn("video_analysis_active_cleanup_failed mediaId={}", mediaId, cleanupError);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        }
    }

    /** 结构性校验：返回拒绝原因，合法则返回 null。 */
    private String rejectionReason(AnalysisTaskMsg msg) {
        if (msg == null) return "消息体为空";
        if (msg.getMediaId() == null) return "缺少 mediaId";
        if (msg.getUserGoal() == null || msg.getUserGoal().isBlank()) return "缺少分析目标";
        if (!msg.hasSupportedAction()) return "不支持的 action=" + msg.getAction();
        return null;
    }

    /**
     * 毒消息收敛。结构性非法的消息重投多少次都不会变好，因此落台账 + 转投失败主题后正常返回
     * （相当于 ACK）。这一步是必需的：原先校验失败直接抛出，既不进 catch 也不进 finally，
     * 会被 Broker 按默认次数反复重投，且台账、任务事件、日志三处都看不到它。
     *
     * <p>确认的前提是「至少留下了一条可追溯记录」：台账与失败主题只要有一个写成功就可以确认，
     * 两个都失败则必须拒绝确认，否则消息会被静默丢弃且无处可查。
     */
    private void discardPoisonMessage(AnalysisTaskMsg msg, String reason) {
        log.error("video_analysis_poison_message reason={} payload={}", reason, describe(msg));
        if (msg == null) return;

        IllegalArgumentException error =
                new IllegalArgumentException("invalid video analysis message: " + reason);
        boolean recorded = false;
        boolean deadLettered = false;
        try {
            failedTaskService.record(msg, 0, error);
            recorded = true;
        } catch (RuntimeException recordError) {
            log.error("poison_message_record_failed payload={}", describe(msg), recordError);
        }
        try {
            rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
            deadLettered = true;
        } catch (RuntimeException dispatchError) {
            log.error("poison_message_dead_letter_failed payload={}", describe(msg), dispatchError);
        }

        if (!recorded && !deadLettered) {
            // 台账与失败主题双双不可用（例如 DB 与 Broker 同时故障）时，确认消息等于把它彻底丢掉：
            // 既没有台账可查、也没有失败主题可捞。此处必须拒绝确认，让 RocketMQ 重投，
            // 等依赖恢复后再收敛。同时不清理幂等键，任务对用户仍保持“处理中”而不是凭空消失。
            throw new IllegalStateException(
                    "毒消息无法收敛：失败台账与失败主题均不可用，拒绝确认以避免消息丢失", error);
        }
        releasePoisonTaskState(msg);
    }

    /**
     * 毒消息在 try 之前就返回，走不到 finally 的清理逻辑。这里补上：
     * 不释放 activeKey 的话，投递方写入的幂等键会残留 6 小时，用户在此期间重复提交一律被判
     * DUPLICATE，前端也永远等不到终态。字段缺到算不出 key 时只能纯日志丢弃。
     */
    private void releasePoisonTaskState(AnalysisTaskMsg msg) {
        if (msg.getMediaId() == null || msg.getUserGoal() == null || msg.getUserGoal().isBlank()) {
            return;
        }
        try {
            AnalysisMode mode = AnalysisMode.fromNullable(msg.getMode());
            String contentHash = AnalysisTaskKeys.normalizeContentHash(
                    msg.getMediaId(), msg.getContentHash());
            String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal(), mode);
            redisTemplate.delete(java.util.List.of(
                    AnalysisTaskKeys.active(contentHash, goalDigest),
                    AnalysisTaskKeys.attempts(contentHash, goalDigest)));
            saveStage(msg.getMediaId(), msg.getUserGoal(), mode, TaskStage.DEAD_LETTERED);
            taskEventService.publishAnalysis(msg.getMediaId(), msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.FAILED, "任务消息非法，已终止"),
                    TaskStage.DEAD_LETTERED);
        } catch (RuntimeException e) {
            log.warn("poison_message_state_release_failed payload={}", describe(msg), e);
        }
    }

    /** 只记录消息摘要，不打印完整目标文本，避免日志里混入长文本或用户敏感内容。 */
    private String describe(AnalysisTaskMsg msg) {
        if (msg == null) return "null";
        return "mediaId=" + msg.getMediaId()
                + " action=" + msg.getAction()
                + " contentHash=" + msg.getContentHash()
                + " goalLength=" + (msg.getUserGoal() == null ? 0 : msg.getUserGoal().length());
    }

    /**
     * 判断是否为「重投也不会成功」的失败。必须沿 cause 链向下找：
     * AiService / VideoContextService / SegmentedTranscriptionService 会把根因层层包进
     * IllegalStateException，只看最外层异常类型什么都判断不出来。
     */
    private boolean isPermanentFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            // 含 NumberFormatException：解析脏数据是确定性失败，重投拿到的还是同一份数据，
            // 不会自愈，因此同样按永久失败收敛。
            if (current instanceof IllegalArgumentException
                    || current instanceof SecurityException
                    || current instanceof NoSuchElementException) {
                return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private Long parseMediaId(String value, String completedKey) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(completedKey);
            log.warn("invalid_completed_media_reference key={} value={}", completedKey, value);
            return null;
        }
    }

    private void saveStage(Long mediaId, String goal, AnalysisMode mode, TaskStage stage) {
        try {
            checkpointService.saveStage(mediaId, goal, mode, stage);
        } catch (RuntimeException e) {
            log.warn("analysis_stage_checkpoint_failed mediaId={} stage={}", mediaId, stage, e);
        }
    }
}
