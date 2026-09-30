package com.example.server.repository;

import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStage;
import com.example.server.mapper.AgentCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCheckpointRepositoryTest {
    @Test
    void reloadingAnOldPlanCannotRewindTheCurrentStage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        Map<Object, Object> cache = new HashMap<>();
        when(hashes.get(eq("key"), any())).thenAnswer(call -> cache.get(call.getArgument(1)));
        doAnswer(call -> { cache.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(hashes).put(eq("key"), any(), any());
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(mapper.findPayload(7L, "goal:plan")).thenReturn("{\"understoodGoal\":\"goal\",\"tasks\":[\"task\"]}");
        when(mapper.findStage(7L, "goal:plan")).thenReturn("PLAN_COMPLETED");
        when(mapper.findStage(7L, "goal:stage")).thenReturn("CRITIC_STARTED");
        AgentCheckpointRepository repository = new AgentCheckpointRepository(redis, new ObjectMapper(), mapper);

        assertNotNull(repository.read(7L, "goal:plan", "key", "plan", AgentState.AgentPlan.class));
        assertEquals(TaskStage.CRITIC_STARTED, repository.readStage(7L, "goal:stage", "key"));
        assertEquals("CRITIC_STARTED", cache.get("stage"));
    }
}
