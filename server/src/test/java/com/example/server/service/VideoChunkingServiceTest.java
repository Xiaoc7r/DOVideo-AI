package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext.VideoSegment;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoChunkingServiceTest {
  @Test
  void preservesSortedWindowBoundariesAndSkipsEmptyWindows() {
    DeepSeekUtils model = mock(DeepSeekUtils.class);
    EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
    when(model.summarizeChunk(anyList()))
        .thenReturn(new VideoChunk.ChunkSummary("summary", List.of("term")));
    when(embeddings.embed(anyString())).thenReturn(List.of(1.0));
    VideoChunkingService service =
        new VideoChunkingService(model, embeddings, mock(AgentTelemetry.class));
    VideoSegment first = segment(0);
    VideoSegment boundary = segment(300_000);
    VideoSegment beforeBoundary = segment(299_999);
    VideoSegment distant = segment(31_536_000_000L);

    List<VideoChunk> chunks =
        service.build(Arrays.asList(distant, boundary, null, beforeBoundary, first));

    assertEquals(
        List.of(0L, 300_000L, 31_536_000_000L),
        chunks.stream().map(VideoChunk::startTime).toList());
    assertEquals(List.of(first, beforeBoundary), chunks.get(0).rawSegments());
    assertEquals(List.of(boundary), chunks.get(1).rawSegments());
    assertEquals(List.of(distant), chunks.get(2).rawSegments());
    assertEquals(31_536_300_000L, chunks.get(2).endTime());
    verify(model, times(3)).summarizeChunk(anyList());
    verify(embeddings, times(3)).embed("summary\nterm");
    assertTrue(service.build(Arrays.asList((VideoSegment) null)).isEmpty());
  }

  @Test
  void providerFailuresKeepOriginalEvidenceAndFallbackSummary() {
    DeepSeekUtils model = mock(DeepSeekUtils.class);
    EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
    when(model.summarizeChunk(anyList())).thenThrow(new IllegalStateException("offline"));
    when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("offline"));
    VideoSegment evidence = new VideoSegment(0, 60_000, "speech", List.of("OCR"), List.of("frame"));
    List<VideoChunk> chunks =
        new VideoChunkingService(model, embeddings, mock(AgentTelemetry.class))
            .build(List.of(evidence));
    assertEquals("speech OCR", chunks.get(0).segmentSummary());
    assertEquals(List.of(evidence), chunks.get(0).rawSegments());
    assertTrue(chunks.get(0).embedding().isEmpty());
  }

  private VideoSegment segment(long start) {
    return new VideoSegment(start, start + 1, "text", List.of(), List.of());
  }
}
