package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class FailedAnalysisTaskService {

    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

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
        task.setMediaId(message.getMediaId());
        task.setAction(message.getAction());
        task.setContentHash(message.getContentHash());
        task.setUserGoal(message.getUserGoal());
        task.setAttemptCount((int) attempts);
        task.setErrorType(root.getClass().getSimpleName());
        task.setErrorMessage(truncate(root.getMessage(), 1_000));
        task.setStatus("FAILED");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
    }

    public List<FailedAnalysisTask> latest() {
        return taskMapper.selectList(new QueryWrapper<FailedAnalysisTask>()
                .orderByDesc("id")
                .last("LIMIT 100"));
    }

    public void replay(Long id) {
        FailedAnalysisTask task = taskMapper.selectById(id);
        if (task == null) throw new NoSuchElementException("失败任务不存在");
        if (!"FAILED".equals(task.getStatus())) {
            throw new IllegalStateException("该失败任务已经重放");
        }

        String contentHash = AnalysisTaskKeys.normalizeContentHash(task.getMediaId(), task.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(task.getUserGoal());
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(task.getMediaId()), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("相同任务正在处理中");

        try {
            redisTemplate.delete(AnalysisTaskKeys.attempts(contentHash, goalDigest));
            rocketMQTemplate.convertAndSend(analysisTopic, new AnalysisTaskMsg(
                    task.getMediaId(), task.getAction(), contentHash, task.getUserGoal()));
            task.setStatus("REQUEUED");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            taskEventService.publishAnalysis(task.getMediaId(), task.getUserGoal(),
                    TaskStatus.of(TaskStatus.State.QUEUED, "失败任务已由管理员重新入队"),
                    "MANUAL_REPLAY");
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            throw e;
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
}
