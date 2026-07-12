package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LongVideoContextService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;
    private static final int TOP_K = 3;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final AgentTelemetry telemetry;
    private final AgentCheckpointService checkpointService;

    public LongVideoContextService(DeepSeekUtils deepSeekUtils,
                                   EmbeddingUtils embeddingUtils,
                                   AgentTelemetry telemetry,
                                   AgentCheckpointService checkpointService) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.telemetry = telemetry;
        this.checkpointService = checkpointService;
    }

    public VideoContext selectRelevant(VideoContext context) {
        return selectRelevant(null, context);
    }

    public VideoContext selectRelevant(Long mediaId, VideoContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return context;
        }

        List<VideoChunk> chunks = mediaId == null ? null : checkpointService.loadChunks(mediaId);
        if (chunks == null || chunks.isEmpty()) {
            chunks = buildChunks(context.segments());
            if (mediaId != null) checkpointService.saveChunks(mediaId, chunks);
        } else {
            telemetry.incrementCurrent("chunkCheckpointHits", 1);
        }
        List<Double> queryEmbedding = safeEmbed(context.userGoal());

        List<VideoChunk> rankedChunks = chunks.stream()
                .sorted(Comparator.comparingDouble(
                        (VideoChunk chunk) -> hybridScore(
                                context.userGoal(), queryEmbedding, chunk)
                ).reversed())
                .limit(TOP_K)
                .toList();
        if (!rankedChunks.isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore",
                    hybridScore(context.userGoal(), queryEmbedding, rankedChunks.get(0)));
            telemetry.incrementCurrent("retrievalChunks", rankedChunks.size());
        }

        List<VideoContext.VideoSegment> selectedSegments = rankedChunks.stream()
                .flatMap(chunk -> chunk.rawSegments().stream())
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();

        return new VideoContext(context.source(), context.userGoal(), selectedSegments);
    }

    public VideoContext refineForCritique(Long mediaId,
                                          VideoContext fullContext,
                                          VideoContext selectedContext,
                                          AgentState.CriticResult critique) {
        Map<String, VideoContext.VideoSegment> segments = new LinkedHashMap<>();
        selectedContext.segments().forEach(segment -> segments.put(segmentKey(segment), segment));

        List<Long> requiredTimestamps = critique == null ? List.of() : critique.requiredTimestamps();
        if (requiredTimestamps != null) {
            fullContext.segments().stream()
                    .filter(segment -> requiredTimestamps.stream().anyMatch(timestamp ->
                            timestamp >= segment.startMs() && timestamp < segment.endMs()))
                    .forEach(segment -> segments.put(segmentKey(segment), segment));
        }

        String critiqueQuery = critiqueQuery(fullContext.userGoal(), critique);
        VideoContext retryContext = selectRelevant(mediaId,
                new VideoContext(fullContext.source(), critiqueQuery, fullContext.segments()));
        retryContext.segments().forEach(segment -> segments.put(segmentKey(segment), segment));

        List<VideoContext.VideoSegment> merged = segments.values().stream()
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();
        return new VideoContext(fullContext.source(), fullContext.userGoal(), merged);
    }

    private String critiqueQuery(String goal, AgentState.CriticResult critique) {
        if (critique == null) return goal;
        return String.join("\n",
                goal,
                String.join(" ", critique.feedback() == null ? List.of() : critique.feedback()),
                String.join(" ", critique.missingRequirements() == null ? List.of() : critique.missingRequirements()),
                String.join(" ", critique.unsupportedClaims() == null ? List.of() : critique.unsupportedClaims()));
    }

    private String segmentKey(VideoContext.VideoSegment segment) {
        return segment.startMs() + ":" + segment.endMs();
    }

    private List<VideoChunk> buildChunks(List<VideoContext.VideoSegment> segments) {
        List<VideoChunk> chunks = new ArrayList<>();
        for (long start = 0; start <= segments.get(segments.size() - 1).startMs(); start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long chunkStart = start;
            List<VideoContext.VideoSegment> rawSegments = segments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            VideoChunk.ChunkSummary summary = summarizeChunk(rawSegments);
            String embeddingText = summary.segmentSummary() + "\n" + String.join(" ", summary.keywords());
            chunks.add(new VideoChunk(
                    start,
                    end,
                    summary.segmentSummary(),
                    summary.keywords(),
                    rawSegments,
                    safeEmbed(embeddingText)
            ));
        }
        return chunks;
    }

    private double hybridScore(String goal, List<Double> queryEmbedding, VideoChunk chunk) {
        // ponytail: 轻量关键词命中，数据量扩大后再升级分词器或 Reranker。
        String normalizedGoal = normalize(goal);
        long matched = chunk.keywords().stream()
                .filter(keyword -> !keyword.isBlank() && normalizedGoal.contains(normalize(keyword)))
                .count();
        double keywordScore = chunk.keywords().isEmpty() ? 0 : (double) matched / chunk.keywords().size();
        if (queryEmbedding.isEmpty() || chunk.embedding().isEmpty()) return keywordScore;
        return cosine(queryEmbedding, chunk.embedding()) * 0.7 + keywordScore * 0.3;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private List<Double> safeEmbed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            return List.of();
        }
    }

    private VideoChunk.ChunkSummary summarizeChunk(List<VideoContext.VideoSegment> segments) {
        try {
            return deepSeekUtils.summarizeChunk(segments);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("summaryFallbacks", 1);
            String rawText = segments.stream()
                    .map(segment -> segment.transcript() + " " + String.join(" ", segment.ocrTexts()))
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
            String summary = rawText.length() <= 500 ? rawText : rawText.substring(0, 500);
            return new VideoChunk.ChunkSummary(summary, List.of());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }
}
