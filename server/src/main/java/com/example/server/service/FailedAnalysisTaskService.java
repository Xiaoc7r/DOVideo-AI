package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class FailedAnalysisTaskService {

    private static final Logger log = LoggerFactory.getLogger(FailedAnalysisTaskService.class);
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REQUEUED = "REQUEUED";
    /** 毒消息可能连 mediaId 都没有，用哨兵值占位，保证台账可写、管理台可见。 */
    private static final long UNKNOWN_MEDIA_ID = -1L;
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_ ]?key|token|secret)\\s*[=:]\\s*)[^\\s,;]{8,}");
    private static final Pattern PREFIXED_SECRET = Pattern.compile(
            "(?i)sk-[A-Za-z0-9_-]{16,}");

    private final FailedAnalysisTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;
    private final String analysisTopic;

    public FailedAnalysisTaskService(FailedAnalysisTaskMapper taskMapper,
                                     RocketMQTemplate rocketMQTemplate,
                                     StringRedisTemplate redisTemplate,
                                     TaskEventService taskEventService,
                                     @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                     String analysisTopic) {
        this.taskMapper = taskMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
        this.analysisTopic = analysisTopic;
    }

    public void record(AnalysisTaskMsg message, long attempts, Throwable error) {
        Throwable root = rootCause(error);
        FailedAnalysisTask task = new FailedAnalysisTask();
        // 台账各列都是 NOT NULL，而毒消息恰恰可能缺字段、或 goal 超过列宽。
        // 这里补占位值并按列宽截断：写台账本身失败的话，失败任务就彻底没有排查抓手了。
        task.setMediaId(message.getMediaId() == null ? UNKNOWN_MEDIA_ID : message.getMediaId());
        task.setAction(column(message.getAction(), "UNKNOWN", 32));
        task.setMode(AnalysisMode.fromNullable(message.getMode()).name());
        task.setContentHash(column(message.getContentHash(), "unknown", 128));
        task.setUserGoal(column(message.getUserGoal(), "(消息缺少分析目标)", 500));
        task.setAttemptCount((int) attempts);
        task.setErrorType(column(root.getClass().getSimpleName(), "UnknownError", 128));
        task.setErrorMessage(sanitizeError(root.getMessage()));
        task.setStatus(STATUS_FAILED);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
    }

    /** 判断台账记录是否由毒消息占位生成——这类记录没有可用于重投的原始参数。 */
    private boolean isPlaceholderRecord(FailedAnalysisTask task) {
        return task.getMediaId() == null
                || task.getMediaId() == UNKNOWN_MEDIA_ID
                || !AnalysisTaskMsg.isSupportedAction(task.getAction());
    }

    /** 补齐 NOT NULL 列并截断到列宽，避免台账写入因缺字段或超长而失败。 */
    private String column(String value, String fallback, int maxLength) {
        String text = value == null || value.isBlank() ? fallback : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public List<FailedAnalysisTask> latest() {
        return taskMapper.selectList(new QueryWrapper<FailedAnalysisTask>()
                .orderByDesc("id")
                .last("LIMIT 100"));
    }

    public void replay(Long id) {
        FailedAnalysisTask task = taskMapper.selectById(id);
        if (task == null) throw new NoSuchElementException("失败任务不存在");
        if (!STATUS_FAILED.equals(task.getStatus())) {
            throw new IllegalArgumentException("该失败任务已经重放");
        }
        // 毒消息台账里的字段是占位值，原样重投必然再次被判为非法消息，
        // 而记录会因此被置成 REQUEUED 再也无法重放。这里直接拒绝并保留 FAILED 状态。
        if (isPlaceholderRecord(task)) {
            throw new IllegalArgumentException("该记录来自非法任务消息，缺少可重放的原始参数");
        }

        AnalysisMode mode = AnalysisMode.fromNullable(task.getMode());
        String contentHash = AnalysisTaskKeys.normalizeContentHash(task.getMediaId(), task.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(task.getUserGoal(), mode);
        String taskScope = AnalysisTaskKeys.mediaScope(task.getMediaId());
        String activeKey = AnalysisTaskKeys.active(taskScope, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(task.getMediaId()), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) throw new IllegalArgumentException("相同任务正在处理中");

        boolean dispatched = false;
        try {
            redisTemplate.delete(AnalysisTaskKeys.attempts(taskScope, goalDigest));
            rocketMQTemplate.convertAndSend(analysisTopic, new AnalysisTaskMsg(
                    task.getMediaId(), task.getAction(), contentHash, task.getUserGoal(), mode.name()));
            dispatched = true;
            task.setStatus(STATUS_REQUEUED);
            task.setUpdatedAt(LocalDateTime.now());
            if (taskMapper.updateById(task) != 1) {
                throw new IllegalStateException("失败任务重放台账更新失败");
            }
        } catch (RuntimeException e) {
            if (!dispatched) {
                redisTemplate.delete(activeKey);
            } else {
                // 消息已发出时保留幂等键，避免台账更新失败诱发重复重放。
                log.error("failed_analysis_replay_bookkeeping_failed taskId={} mediaId={}",
                        id, task.getMediaId(), e);
            }
            throw e;
        }

        try {
            taskEventService.publishAnalysis(task.getMediaId(), task.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.QUEUED, "失败任务已由管理员重新入队"),
                    TaskStage.MANUAL_REPLAY);
        } catch (RuntimeException eventError) {
            log.warn("failed_analysis_replay_event_failed taskId={} mediaId={}",
                    id, task.getMediaId(), eventError);
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String sanitizeError(String value) {
        if (value == null) return null;
        String sanitized = BEARER_SECRET.matcher(value).replaceAll("$1****");
        sanitized = NAMED_SECRET.matcher(sanitized).replaceAll("$1****");
        sanitized = PREFIXED_SECRET.matcher(sanitized).replaceAll("****");
        return truncate(sanitized, 1_000);
    }
}
