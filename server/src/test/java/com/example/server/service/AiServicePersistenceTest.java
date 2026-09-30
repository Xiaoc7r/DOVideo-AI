package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServicePersistenceTest {
    @Test
    void replayingOwnResultPreservesTheOriginalEvidenceFrameReferences() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        MediaFile media = new MediaFile(); media.setId(7L); media.setFilePath("source");
        when(mapper.selectById(7L)).thenReturn(media);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        VideoContext context = new VideoContext("source", "", List.of(
                new VideoContext.VideoSegment(0, 1000, "transcript", List.of(), List.of("frames/original.jpg"))));
        when(checkpoints.loadContext(7L)).thenReturn(context);
        AiService service = new AiService(mapper, mock(VideoContextService.class),
                mock(LongVideoContextService.class), mock(AgentLoopService.class), checkpoints, mock(AgentTelemetry.class),
                mock(MediaService.class), mock(TaskEventService.class), mock(RedissonClient.class),
                mock(StringRedisTemplate.class), mock(ModeRegistry.class), mock(DeepSeekUtils.class), new ObjectMapper());
        AgentState result = new AgentState("goal", null,
                new AnalysisResult("summary", List.of("done"), List.of(), List.of(), List.of()), null, 1);

        assertTrue(service.reuseResult(7L, 7L, result, AnalysisMode.GENERAL));
        verify(checkpoints, never()).saveContext(anyLong(), any(VideoContext.class));
        assertFalse(service.reuseResult(7L, 8L, result, AnalysisMode.GENERAL));
        when(checkpoints.loadContext(8L)).thenReturn(context);
        assertTrue(service.reuseResult(7L, 8L, result, AnalysisMode.GENERAL));
        verify(checkpoints).saveContext(eq(7L), argThat(reused ->
                reused.segments().getFirst().evidenceFrames().equals(List.of("source#timestampMs=0"))));
    }

    @Test
    void savesSummarySeparatelyAndOnlyFillsMissingTranscript() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentLoopService loop = mock(AgentLoopService.class);
        MediaFile oldSnapshot = new MediaFile();
        oldSnapshot.setId(7L);
        oldSnapshot.setUserId(3L);
        oldSnapshot.setFilename("original.mp4");
        oldSnapshot.setTranscriptText("stale transcript");
        when(mapper.selectById(7L)).thenReturn(oldSnapshot);
        VideoContext context = new VideoContext("source", "goal", List.of(
                new VideoContext.VideoSegment(0, 1000, "context transcript", List.of(), List.of())));
        when(checkpoints.loadContext(7L)).thenReturn(context);
        AgentState result = new AgentState("goal", null,
                new AnalysisResult("summary", List.of("done"), List.of(), List.of(), List.of()), null, 1);
        when(loop.run(eq(7L), any(VideoContext.class), any())).thenReturn(result);
        AiService service = new AiService(mapper, mock(VideoContextService.class),
                mock(LongVideoContextService.class), loop, checkpoints, mock(AgentTelemetry.class),
                mock(MediaService.class), mock(TaskEventService.class), mock(RedissonClient.class),
                mock(StringRedisTemplate.class), mock(ModeRegistry.class), mock(DeepSeekUtils.class), new ObjectMapper());

        service.asyncAnalyze(7L, "goal", AnalysisMode.GENERAL);

        ArgumentCaptor<MediaFile> summary = ArgumentCaptor.forClass(MediaFile.class);
        verify(mapper).updateById(summary.capture());
        assertEquals(result.result().toMarkdown(), summary.getValue().getAiSummary());
        assertNull(summary.getValue().getTranscriptText());
        assertNull(summary.getValue().getFilename());
        ArgumentCaptor<MediaFile> transcript = ArgumentCaptor.forClass(MediaFile.class);
        ArgumentCaptor<Wrapper<MediaFile>> condition = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(transcript.capture(), condition.capture());
        assertTrue(transcript.getValue().getTranscriptText().contains("context transcript"));
        assertTrue(condition.getValue().getSqlSegment().contains("transcript_text IS NULL"));
        assertTrue(condition.getValue().getSqlSegment().contains("id ="));
    }
}
