package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisStatusServiceTest {
  @Test
  void cacheOutageKeepsSavedResultsReadableButDoesNotCompletePendingOrMissingTasks() {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
    when(dispatch.isActive(7L, "goal", AnalysisMode.GENERAL))
        .thenThrow(new IllegalStateException("Redis unavailable"));
    AgentState saved =
        new AgentState(
            "goal",
            null,
            new AnalysisResult("done", List.of(), List.of(), List.of(), List.of()),
            null,
            1);
    when(checkpoints.loadResult(7L, "goal", AnalysisMode.GENERAL)).thenReturn(saved);
    AnalysisStatusService status = new AnalysisStatusService(checkpoints, dispatch);
    assertEquals(TaskStatus.State.COMPLETED, status.current(7L, "goal").state());
    when(checkpoints.isRevisionPending(7L, "goal", AnalysisMode.GENERAL)).thenReturn(true);
    assertThrows(IllegalStateException.class, () -> status.current(7L, "goal"));
    when(checkpoints.isRevisionPending(7L, "goal", AnalysisMode.GENERAL)).thenReturn(false);
    when(checkpoints.loadResult(7L, "goal", AnalysisMode.GENERAL)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> status.current(7L, "goal"));
  }

  @Test
  void completedTasksDoNotQueryMissingRevisionCheckpoints() {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
    when(checkpoints.loadResult(7L, "goal", AnalysisMode.GENERAL))
        .thenReturn(
            new AgentState(
                "goal",
                null,
                new AnalysisResult("done", List.of(), List.of(), List.of(), List.of()),
                null,
                1));
    assertEquals(
        TaskStatus.State.COMPLETED,
        new AnalysisStatusService(checkpoints, dispatch).current(7L, "goal").state());
    verify(checkpoints, never()).isRevisionPending(anyLong(), anyString(), any());
    verify(dispatch, times(1)).isActive(7L, "goal", AnalysisMode.GENERAL);
  }

  @Test
  void queuedRevisionDoesNotReturnOldCompletionButFailedDispatchKeepsOldResultReadable() {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
    AgentState previous =
        new AgentState(
            "goal",
            null,
            new AnalysisResult("old", List.of("old result"), List.of(), List.of(), List.of()),
            null,
            1);
    when(checkpoints.loadResult(7L, "goal", AnalysisMode.LEARNING)).thenReturn(previous);
    when(checkpoints.isRevisionPending(7L, "goal", AnalysisMode.LEARNING)).thenReturn(true);
    when(dispatch.isActive(7L, "goal", AnalysisMode.LEARNING)).thenReturn(true);
    AnalysisStatusService status = new AnalysisStatusService(checkpoints, dispatch);
    assertEquals(
        TaskStatus.State.QUEUED, status.current(7L, "goal", AnalysisMode.LEARNING).state());
    when(dispatch.isActive(7L, "goal", AnalysisMode.LEARNING)).thenReturn(false);
    assertEquals(
        TaskStatus.State.COMPLETED, status.current(7L, "goal", AnalysisMode.LEARNING).state());
  }
}
