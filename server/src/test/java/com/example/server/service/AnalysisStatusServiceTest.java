package com.example.server.service;

import com.example.server.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisStatusServiceTest {
    @Test
    void queuedRevisionDoesNotReturnOldCompletionButFailedDispatchKeepsOldResultReadable() {
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
        AgentState previous = new AgentState("goal", null,
                new AnalysisResult("old", List.of("old result"), List.of(), List.of(), List.of()), null, 1);
        when(checkpoints.loadResult(7L, "goal", AnalysisMode.LEARNING)).thenReturn(previous);
        when(checkpoints.isRevisionPending(7L, "goal", AnalysisMode.LEARNING)).thenReturn(true);
        when(dispatch.isActive(7L, "goal", AnalysisMode.LEARNING)).thenReturn(true);
        AnalysisStatusService status = new AnalysisStatusService(checkpoints, dispatch);
        assertEquals(TaskStatus.State.QUEUED, status.current(7L, "goal", AnalysisMode.LEARNING).state());
        when(dispatch.isActive(7L, "goal", AnalysisMode.LEARNING)).thenReturn(false);
        assertEquals(TaskStatus.State.COMPLETED, status.current(7L, "goal", AnalysisMode.LEARNING).state());
    }
}
