package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 将长视频整理成可持久化、可检索的五分钟知识块。 */
@Service
public class VideoChunkingService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingClient embeddingUtils;
    private final AgentTelemetry telemetry;

    public VideoChunkingService(DeepSeekUtils deepSeekUtils,
                                EmbeddingClient embeddingUtils,
                                AgentTelemetry telemetry) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.telemetry = telemetry;
    }

    public List<VideoChunk> build(List<VideoContext.VideoSegment> segments) {
        if (segments.isEmpty()) return List.of();

        List<VideoContext.VideoSegment> orderedSegments = segments.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();
        if (orderedSegments.isEmpty()) return List.of();

        List<VideoChunk> chunks = new ArrayList<>();
        for (long start = 0;
             start <= orderedSegments.get(orderedSegments.size() - 1).startMs();
             start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long chunkStart = start;
            List<VideoContext.VideoSegment> rawSegments = orderedSegments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            VideoChunk.ChunkSummary summary = summarize(rawSegments);
            List<String> keywords = normalizeTexts(summary.keywords());
            String embeddingText = summary.segmentSummary() + "\n" + String.join(" ", keywords);
            chunks.add(new VideoChunk(
                    start,
                    end,
                    summary.segmentSummary(),
                    keywords,
                    rawSegments,
                    embed(embeddingText)));
        }
        return chunks;
    }

    private VideoChunk.ChunkSummary summarize(List<VideoContext.VideoSegment> segments) {
        try {
            return deepSeekUtils.summarizeChunk(segments);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("summaryFallbacks", 1);
            String rawText = segments.stream()
                    .map(segment -> segment.transcript() + " "
                            + String.join(" ", normalizeTexts(segment.ocrTexts())))
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
            String summary = rawText.length() <= 500 ? rawText : rawText.substring(0, 500);
            return new VideoChunk.ChunkSummary(summary, List.of());
        }
    }

    private List<Double> embed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            return List.of();
        }
    }

    private List<String> normalizeTexts(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
