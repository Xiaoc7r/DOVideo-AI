package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.*;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class FailedAnalysisTaskServiceTest {
  @Test
  void cyclicExceptionCausesCannotHangTheWorkerWhileRecordingFailure() {
    var mapper = mock(FailedAnalysisTaskMapper.class);
    var service =
        new FailedAnalysisTaskService(
            mapper,
            mock(RocketMQTemplate.class),
            mock(StringRedisTemplate.class),
            mock(TaskEventService.class),
            "analysis");
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () ->
            service.record(
                new AnalysisTaskMsg(7L, AnalysisTaskMsg.START_ANALYSIS, "hash", "goal"), 3, first));
    var task = ArgumentCaptor.forClass(FailedAnalysisTask.class);
    verify(mapper).insert(task.capture());
    assertEquals("RuntimeException", task.getValue().getErrorType());
    assertTrue(List.of("first", "second").contains(task.getValue().getErrorMessage()));
  }

  @Test
  void immediateReplayCompletionIsNotFollowedByQueuedStatus() {
    var fixture = fixture();
    List<TaskStage> stages = new ArrayList<>();
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
    fixture.service.replay(1L);
    assertEquals(List.of(TaskStage.MANUAL_REPLAY, TaskStage.COMPLETED), stages);
  }

  @Test
  void failedReplayDispatchTerminatesTheQueuedStateAndLeavesTheLedgerRetryable() {
    var fixture = fixture();
    doThrow(new IllegalStateException("broker down"))
        .when(fixture.mq)
        .convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    assertThrows(IllegalStateException.class, () -> fixture.service.replay(1L));
    verify(fixture.events)
        .publishAnalysis(
            eq(7L),
            eq("goal"),
            eq(AnalysisMode.GENERAL),
            argThat(status -> status.state() == TaskStatus.State.FAILED),
            eq(TaskStage.DISPATCH_FAILED));
    verify(fixture.mapper, never()).updateById(any(FailedAnalysisTask.class));
  }

  private Fixture fixture() {
    var mapper = mock(FailedAnalysisTaskMapper.class);
    var task = new FailedAnalysisTask();
    task.setId(1L);
    task.setMediaId(7L);
    task.setUserGoal("goal");
    task.setAction(AnalysisTaskMsg.START_ANALYSIS);
    task.setStatus("FAILED");
    when(mapper.selectById(1L)).thenReturn(task);
    when(mapper.updateById(any(FailedAnalysisTask.class))).thenReturn(1);
    var redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    var mq = mock(RocketMQTemplate.class);
    var events = mock(TaskEventService.class);
    return new Fixture(
        new FailedAnalysisTaskService(mapper, mq, redis, events, "analysis"), mapper, mq, events);
  }

  private record Fixture(
      FailedAnalysisTaskService service,
      FailedAnalysisTaskMapper mapper,
      RocketMQTemplate mq,
      TaskEventService events) {}
}
