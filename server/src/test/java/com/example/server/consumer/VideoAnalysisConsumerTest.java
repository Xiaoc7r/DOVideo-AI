package com.example.server.consumer;

import com.example.server.dto.*;
import com.example.server.service.*;
import com.example.server.utils.AnalysisTaskKeys;
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
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        consumer = new VideoAnalysisConsumer(ai, redisson, redis, checkpoints, mq,
                mock(FailedAnalysisTaskService.class), media, events, "dead-letter");
    }

    @Test
    void distinctMediaWaitsForSharedContentThenReceivesItsOwnResult() throws Exception {
        String hash = "a".repeat(32);
        String digest = AnalysisTaskKeys.goalDigest("goal");
        RLock contentLock = mock(RLock.class);
        when(redisson.getLock(AnalysisTaskKeys.lock(hash, digest))).thenReturn(contentLock);
        when(contentLock.isHeldByCurrentThread()).thenReturn(true);
        when(media.exists(7L)).thenReturn(true);
        AgentState result = new AgentState("goal", null,
                new AnalysisResult("shared", List.of("done"), List.of(), List.of(), List.of()), null, 1);
        doAnswer(call -> {
            when(values.get(AnalysisTaskKeys.completed(hash, digest))).thenReturn("6");
            return null;
        }).when(contentLock).lockInterruptibly();
        when(checkpoints.loadResult(6L, "goal", AnalysisMode.GENERAL)).thenReturn(result);
        when(ai.reuseResult(7L, 6L, result, AnalysisMode.GENERAL)).thenReturn(true);

        consumer.onMessage(new AnalysisTaskMsg(7L, AnalysisTaskMsg.START_ANALYSIS, hash, "goal"));

        var ordered = inOrder(contentLock, ai);
        ordered.verify(contentLock).lockInterruptibly();
        ordered.verify(ai).reuseResult(7L, 6L, result, AnalysisMode.GENERAL);
        verify(ai, never()).asyncAnalyze(anyLong(), anyString(), any());
        verify(events).publishAnalysis(eq(7L), eq("goal"), eq(AnalysisMode.GENERAL),
                argThat(status -> status.state() == TaskStatus.State.COMPLETED), eq(TaskStage.COMPLETED_REUSED));
        verify(values).increment(AnalysisTaskKeys.attempts("media-7", digest));
        verify(contentLock).unlock();
        verify(lock).unlock();
    }

    @Test
    void interruptionWhileWaitingRetainsTaskForRetryAndReleasesOnlyOwnedLocks() throws Exception {
        String hash = "a".repeat(32);
        RLock contentLock = mock(RLock.class);
        when(redisson.getLock(AnalysisTaskKeys.lock(hash, AnalysisTaskKeys.goalDigest("goal"))))
                .thenReturn(contentLock);
        doThrow(new InterruptedException()).when(contentLock).lockInterruptibly();
        try {
            assertThrows(IllegalStateException.class, () -> consumer.onMessage(
                    new AnalysisTaskMsg(7L, AnalysisTaskMsg.START_ANALYSIS, hash, "goal")));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(redis, never()).delete(anyCollection());
            verify(lock).unlock();
            verify(contentLock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
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
