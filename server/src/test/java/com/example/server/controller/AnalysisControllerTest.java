package com.example.server.controller;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.entity.MediaFile;
import com.example.server.service.*;
import com.example.server.service.mode.ModeRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisControllerTest {
    @Test
    void revisionUsesBodyModeWhenQueryIsMissingAndAllowsExplicitQueryOverride() {
        AiService ai = mock(AiService.class);
        AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
        MediaService media = mock(MediaService.class);
        MediaFile owned = new MediaFile();
        when(media.requireOwnedMedia(7L, 3L)).thenReturn(owned);
        when(ai.revisionGoal(any())).thenReturn("goal");
        when(dispatch.submit(any(), anyString(), any(), any()))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);
        AnalysisController controller = new AnalysisController(ai, dispatch,
                mock(AgentCheckpointService.class), mock(AgentEvaluationService.class),
                mock(AgentTelemetry.class), media, mock(TaskEventService.class),
                mock(AnalysisStatusService.class), mock(ModeRouter.class), Runnable::run);
        AgentFeedback feedback = new AgentFeedback(7L, "goal", "LEARNING", null,
                null, null, null, List.of("new task"), null, null, null);

        assertEquals(202, controller.reviseAgentResult(feedback, null, 3L).getStatusCode().value());
        verify(dispatch).submit(owned, "goal", feedback, AnalysisMode.LEARNING);
        assertEquals(202, controller.reviseAgentResult(feedback, "REVIEW", 3L).getStatusCode().value());
        verify(dispatch).submit(owned, "goal", feedback, AnalysisMode.REVIEW);
    }
}
