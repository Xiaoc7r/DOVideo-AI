package com.example.server.consumer;

import com.example.server.dto.*;
import com.example.server.service.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoAnalysisConsumerTest {
    private final AiService ai = mock(AiService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    private final MediaService media = mock(MediaService.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    private final RLock lock = mock(RLock.class);
    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        consumer = new VideoAnalysisConsumer(ai, redisson, redis, checkpoints, mq,
                mock(FailedAnalysisTaskService.class), media, events, "dead-letter");
    }

    @Test
    void releasesWatchdogLockEvenWhenRedisCleanupFails() {
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertDoesNotThrow(() -> consumer.onMessage(new AnalysisTaskMsg(
                7L, AnalysisTaskMsg.START_ANALYSIS, "hash", "goal")));
        verify(lock).unlock();
    }

    @Test
    void completedRevisionRedeliveryPublishesSavedResultWithoutRerunning() {
        when(media.exists(7L)).thenReturn(true);
        AgentState result = new AgentState("goal", null,
                new AnalysisResult("result", List.of("done"), List.of(), List.of(), List.of()), null, 1);
        when(checkpoints.loadResult(7L, "goal", AnalysisMode.GENERAL)).thenReturn(result);
        assertDoesNotThrow(() -> consumer.onMessage(new AnalysisTaskMsg(
                7L, AnalysisTaskMsg.REVISE_ANALYSIS, "hash", "goal")));
        verify(events).publishAnalysis(eq(7L), eq("goal"), eq(AnalysisMode.GENERAL),
                argThat(status -> status.state() == TaskStatus.State.COMPLETED), eq(TaskStage.COMPLETED));
        verify(ai, never()).asyncAnalyze(anyLong(), anyString(), any());
        verifyNoInteractions(mq);
        verify(lock).unlock();
    }

    @Test
    void revisionWithoutMarkerOrResultStillRetriesInsteadOfReportingSuccess() {
        when(media.exists(7L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(new AnalysisTaskMsg(
                7L, AnalysisTaskMsg.REVISE_ANALYSIS, "hash", "goal")));
        verify(events, never()).publishAnalysis(anyLong(), anyString(), any(),
                argThat(status -> status.state() == TaskStatus.State.COMPLETED), any());
        verify(lock).unlock();
    }
}
