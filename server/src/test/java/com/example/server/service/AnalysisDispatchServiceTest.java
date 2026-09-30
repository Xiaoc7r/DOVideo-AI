package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisDispatchServiceTest {
    @Test
    void identicalContentInDifferentMediaRecordsGetsIndependentTasksButSameMediaCannotOverlap() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> active = new HashMap<>();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> active.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(redis.hasKey(anyString())).thenAnswer(call -> active.containsKey(call.getArgument(0)));
        RedissonClient redisson = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(true);
        MediaService media = mock(MediaService.class);
        when(media.contentHash(anyLong())).thenReturn("a".repeat(32));
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        AnalysisDispatchService dispatch = new AnalysisDispatchService(mock(AiService.class), media, redis,
                mq, redisson, mock(TaskEventService.class), "analysis");
        MediaFile first = new MediaFile(); first.setId(7L); first.setUserId(1L);
        MediaFile second = new MediaFile(); second.setId(8L); second.setUserId(2L);

        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, dispatch.submit(first, "goal", null));
        assertFalse(dispatch.isActive(8L, "goal"));
        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, dispatch.submit(second, "goal", null));
        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE, dispatch.submit(first, "goal", null));
        AgentFeedback revision = mock(AgentFeedback.class);
        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE, dispatch.submit(first, "goal", revision));
        assertTrue(dispatch.isActive(7L, "goal"));
        assertTrue(dispatch.isActive(8L, "goal"));
        verify(mq, times(2)).convertAndSend(eq("analysis"), any(AnalysisTaskMsg.class));
    }
}
