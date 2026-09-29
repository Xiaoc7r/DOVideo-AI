package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaServiceTest {
    @Test
    void deletionAttemptsIndependentCleanupAndInvalidatesListAfterFailures() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
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
        verify(redis).delete("media:list:v2:user:7");
    }
}
