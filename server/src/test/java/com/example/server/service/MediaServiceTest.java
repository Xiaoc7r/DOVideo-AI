package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaServiceTest {
    @Test
    void deletionAttemptsIndependentCleanupAndInvalidatesListAfterFailures() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("media:list:v3:user:7:generation")).thenReturn("before");
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        VideoContextService contexts = mock(VideoContextService.class);
        MediaService service = new MediaService(mapper, redis, mock(MinioUtils.class),
                new ObjectMapper(), checkpoints, telemetry, vectors, contexts);
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        when(mapper.selectById(42L)).thenReturn(media);
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));
        doThrow(new IllegalStateException("frame cleanup failed")).when(contexts).deleteEvidenceFrames(null);
        doThrow(new IllegalStateException("telemetry unavailable")).when(telemetry).deleteTask(42L);

        assertDoesNotThrow(() -> service.deleteOwnedMedia(42L, 7L));

        verify(mapper).deleteById(42L);
        verify(checkpoints).deleteMedia(42L);
        verify(vectors).deleteMedia(42L);
        verify(redis).delete("media:list:v3:user:7:before");
    }

    @Test
    void aListQueryFinishingAfterDeletionCannotRestoreTheOldCacheForFutureReaders() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> cache = new HashMap<>();
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString()))
                .thenAnswer(call -> cache.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString());
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)) != null);
        MediaService service = new MediaService(mapper, redis, mock(MinioUtils.class), new ObjectMapper(),
                mock(AgentCheckpointService.class), mock(AgentTelemetry.class),
                mock(QdrantVectorStore.class), mock(VideoContextService.class));
        MediaFile deleted = new MediaFile(); deleted.setId(42L);
        when(mapper.selectList(any())).thenAnswer(call -> {
            service.invalidateUserList(7L);
            return List.of(deleted); // Snapshot was read just before the delete committed.
        }).thenReturn(List.of());

        assertEquals(1, service.listByUser(7L).size());
        assertTrue(service.listByUser(7L).isEmpty());
        assertTrue(service.listByUser(7L).isEmpty());
        verify(mapper, times(2)).selectList(any());
    }
}
