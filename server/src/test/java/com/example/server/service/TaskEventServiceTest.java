package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class TaskEventServiceTest {
    @Test
    void completedSubscriberDoesNotRemoveAReopenedOrDifferentModeStream() throws Exception {
        TaskEventService events = new TaskEventService(mock(StringRedisTemplate.class), new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new EventController(events)).build();
        MvcResult first = mvc.perform(get("/events").param("mode", "GENERAL")).andReturn();
        MvcResult learning = mvc.perform(get("/events").param("mode", "LEARNING")).andReturn();
        events.publishAnalysis(7L, "goal", AnalysisMode.GENERAL, TaskStatus.completed("first result"), TaskStage.COMPLETED);
        mvc.perform(asyncDispatch(first));
        assertFalse(learning.getResponse().getContentAsString().contains("first result"));

        MvcResult reopened = mvc.perform(get("/events").param("mode", "GENERAL")).andReturn();
        events.publishAnalysis(7L, "goal", AnalysisMode.GENERAL, TaskStatus.completed("second result"), TaskStage.COMPLETED);
        mvc.perform(asyncDispatch(reopened));
        assertTrue(reopened.getResponse().getContentAsString().contains("second result"));
        events.publishAnalysis(7L, "goal", AnalysisMode.LEARNING, TaskStatus.completed("learning result"), TaskStage.COMPLETED);
        mvc.perform(asyncDispatch(learning));
        assertTrue(learning.getResponse().getContentAsString().contains("learning result"));
    }

    @RestController
    static class EventController {
        private final TaskEventService events;
        EventController(TaskEventService events) { this.events = events; }
        @GetMapping(value = "/events", produces = "text/event-stream")
        SseEmitter events(@RequestParam AnalysisMode mode) {
            return events.subscribe(7L, TaskEventService.ANALYSIS, "goal", mode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "running"), TaskStage.CONSUMING);
        }
    }
}
