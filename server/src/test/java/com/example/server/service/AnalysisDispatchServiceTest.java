package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AnalysisDispatchServiceTest {
  @Test
  void evenImmediateConsumerCompletionCannotBeFollowedByAQueuedEvent() {
    Fixture fixture = fixture();
    java.util.List<TaskStage> stages = new java.util.ArrayList<>();
    doAnswer(
            call -> {
              stages.add(call.getArgument(4));
              return null;
            })
        .when(fixture.events)
        .publishAnalysis(anyLong(), anyString(), any(), any(), any());
    doAnswer(
            call -> {
              fixture.events.publishAnalysis(
                  7L,
                  "goal",
                  AnalysisMode.GENERAL,
                  TaskStatus.completed("done"),
                  TaskStage.COMPLETED);
              return null;
            })
        .when(fixture.mq)
        .convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    assertEquals(
        AnalysisDispatchService.SubmissionResult.ACCEPTED,
        fixture.service.submit(media(), "goal", null));
    assertEquals(java.util.List.of(TaskStage.QUEUED, TaskStage.COMPLETED), stages);
  }

  @Test
  void dispatchFailureTerminatesThePreviouslyPublishedQueuedState() {
    Fixture fixture = fixture();
    doThrow(new IllegalStateException("broker unavailable"))
        .when(fixture.mq)
        .convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    assertEquals(
        AnalysisDispatchService.SubmissionResult.FAILED,
        fixture.service.submit(media(), "goal", null));
    var order = inOrder(fixture.events);
    order
        .verify(fixture.events)
        .publishAnalysis(
            eq(7L),
            eq("goal"),
            eq(AnalysisMode.GENERAL),
            argThat(status -> status.state() == TaskStatus.State.QUEUED),
            eq(TaskStage.QUEUED));
    order
        .verify(fixture.events)
        .publishAnalysis(
            eq(7L),
            eq("goal"),
            eq(AnalysisMode.GENERAL),
            argThat(status -> status.state() == TaskStatus.State.FAILED),
            eq(TaskStage.DISPATCH_FAILED));
  }

  @Test
  void notificationFailureDoesNotPreventDispatch() {
    Fixture fixture = fixture();
    doThrow(new IllegalStateException("event transport unavailable"))
        .when(fixture.events)
        .publishAnalysis(anyLong(), anyString(), any(), any(), any());
    assertEquals(
        AnalysisDispatchService.SubmissionResult.ACCEPTED,
        fixture.service.submit(media(), "goal", null));
    verify(fixture.mq).convertAndSend(eq("analysis"), any(AnalysisTaskMsg.class));
  }

  private Fixture fixture() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    RedissonClient redisson = mock(RedissonClient.class);
    RRateLimiter limiter = mock(RRateLimiter.class);
    when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
    when(limiter.tryAcquire()).thenReturn(true);
    RocketMQTemplate mq = mock(RocketMQTemplate.class);
    TaskEventService events = mock(TaskEventService.class);
    return new Fixture(
        new AnalysisDispatchService(
            mock(AiService.class),
            mock(MediaService.class),
            redis,
            mq,
            redisson,
            events,
            "analysis"),
        mq,
        events);
  }

  private MediaFile media() {
    MediaFile media = new MediaFile();
    media.setId(7L);
    media.setUserId(1L);
    return media;
  }

  private record Fixture(
      AnalysisDispatchService service, RocketMQTemplate mq, TaskEventService events) {}

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
    AnalysisDispatchService dispatch =
        new AnalysisDispatchService(
            mock(AiService.class),
            media,
            redis,
            mq,
            redisson,
            mock(TaskEventService.class),
            "analysis");
    MediaFile first = new MediaFile();
    first.setId(7L);
    first.setUserId(1L);
    MediaFile second = new MediaFile();
    second.setId(8L);
    second.setUserId(2L);

    assertEquals(
        AnalysisDispatchService.SubmissionResult.ACCEPTED, dispatch.submit(first, "goal", null));
    assertFalse(dispatch.isActive(8L, "goal"));
    assertEquals(
        AnalysisDispatchService.SubmissionResult.ACCEPTED, dispatch.submit(second, "goal", null));
    assertEquals(
        AnalysisDispatchService.SubmissionResult.DUPLICATE, dispatch.submit(first, "goal", null));
    AgentFeedback revision = mock(AgentFeedback.class);
    assertEquals(
        AnalysisDispatchService.SubmissionResult.DUPLICATE,
        dispatch.submit(first, "goal", revision));
    assertTrue(dispatch.isActive(7L, "goal"));
    assertTrue(dispatch.isActive(8L, "goal"));
    verify(mq, times(2)).convertAndSend(eq("analysis"), any(AnalysisTaskMsg.class));
  }
}
