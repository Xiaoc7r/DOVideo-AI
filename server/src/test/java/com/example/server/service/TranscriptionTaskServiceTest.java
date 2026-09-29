package com.example.server.service;

import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptionTaskServiceTest {
    @Test
    void transcriptionOnlyWritesTranscriptAndCannotRevertConcurrentAnalysis() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        VideoTranscriptionService transcriber = mock(VideoTranscriptionService.class);
        MediaService media = mock(MediaService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        TaskEventService events = mock(TaskEventService.class);
        MediaFile oldSnapshot = new MediaFile();
        oldSnapshot.setId(7L);
        oldSnapshot.setUserId(3L);
        oldSnapshot.setAiSummary("old summary before concurrent analysis");
        oldSnapshot.setFilename("old-name.mp4");
        oldSnapshot.setFilePath("source");
        when(mapper.selectById(7L)).thenReturn(oldSnapshot);
        when(media.readableSource("source")).thenReturn("readable");
        when(transcriber.transcribe("readable")).thenReturn("new transcript");
        when(mapper.updateById(any(MediaFile.class))).thenReturn(1);

        new TranscriptionTaskService(mapper, transcriber, media, redis, events).transcribe(7L);

        ArgumentCaptor<MediaFile> update = ArgumentCaptor.forClass(MediaFile.class);
        verify(mapper).updateById(update.capture());
        assertEquals(7L, update.getValue().getId());
        assertEquals("new transcript", update.getValue().getTranscriptText());
        assertNull(update.getValue().getAiSummary());
        assertNull(update.getValue().getFilename());
        verify(events).publishTranscription(7L, TaskStatus.completed("new transcript"), TaskStage.COMPLETED);
        verify(redis).delete("transcription:active:7");
    }

    @Test
    void failedDatabaseLookupReleasesTheActiveFlagAndReportsFailure() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        when(mapper.selectById(7L)).thenThrow(new IllegalStateException("DB unavailable"));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        TaskEventService events = mock(TaskEventService.class);
        TranscriptionTaskService service = new TranscriptionTaskService(mapper,
                mock(VideoTranscriptionService.class), mock(MediaService.class), redis, events);

        assertDoesNotThrow(() -> service.transcribe(7L));
        verify(redis).delete("transcription:active:7");
        verify(events).publishTranscription(eq(7L),
                argThat(status -> status.state() == TaskStatus.State.FAILED), eq(TaskStage.FAILED));
    }
}
