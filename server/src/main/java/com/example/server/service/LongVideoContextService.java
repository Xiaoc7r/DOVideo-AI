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
    private static final int MAX_CONTEXT_CHARS = 24_000;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final AgentTelemetry telemetry;
    private final AgentCheckpointService checkpointService;
    private final QdrantVectorStore vectorStore;

    public LongVideoContextService(DeepSeekUtils deepSeekUtils,
                                   EmbeddingUtils embeddingUtils,
                                   AgentTelemetry telemetry,
                                   AgentCheckpointService checkpointService,
                                   QdrantVectorStore vectorStore) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.telemetry = telemetry;
        this.checkpointService = checkpointService;
        this.vectorStore = vectorStore;
    }

    public VideoContext selectRelevant(VideoContext context) {
        return selectRelevant(null, context);
    }

    public VideoContext selectRelevant(Long mediaId, VideoContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return withinBudget(context, context.segments());
        }

        List<VideoChunk> chunks = mediaId == null ? null : checkpointService.loadChunks(mediaId);
        if (chunks == null || chunks.isEmpty()) {
            chunks = buildChunks(context.segments());
            if (mediaId != null) {
                checkpointService.saveChunks(mediaId, chunks);
                indexChunks(mediaId, chunks);
            }
        } else {
            telemetry.incrementCurrent("chunkCheckpointHits", 1);
        }
        List<Double> queryEmbedding = safeEmbed(context.userGoal());

        List<VideoChunk> rankedChunks = rankChunks(
                mediaId, context.userGoal(), queryEmbedding, chunks);
        if (!rankedChunks.isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore",
                    hybridScore(context.userGoal(), queryEmbedding, rankedChunks.get(0)));
            telemetry.incrementCurrent("retrievalChunks", rankedChunks.size());
        }

        List<VideoContext.VideoSegment> selectedSegments = rankedChunks.stream()
                .flatMap(chunk -> chunk.rawSegments().stream())
                .toList();
        return withinBudget(context, selectedSegments);
    }

    public VideoContext refineForCritique(Long mediaId,
                                          VideoContext fullContext,
                                          VideoContext selectedContext,
                                          AgentState.CriticResult critique) {
        Map<String, VideoContext.VideoSegment> segments = new LinkedHashMap<>();
        List<Long> requiredTimestamps = critique == null ? List.of() : critique.requiredTimestamps();
        fullContext.segments().stream()
                .filter(segment -> requiredTimestamps.stream().anyMatch(timestamp ->
                        nearSegment(timestamp, segment)))
                .forEach(segment -> segments.put(segmentKey(segment), segment));
        selectedContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));

        String critiqueQuery = critiqueQuery(fullContext.userGoal(), critique);
        VideoContext retryContext = selectRelevant(mediaId,
                new VideoContext(fullContext.source(), critiqueQuery, fullContext.segments()));
        retryContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));
        return withinBudget(fullContext, new ArrayList<>(segments.values()));
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

    private VideoContext withinBudget(VideoContext context,
                                      List<VideoContext.VideoSegment> candidates) {
        List<VideoContext.VideoSegment> selected = new ArrayList<>();
        int usedChars = 0;
        for (VideoContext.VideoSegment segment : candidates) {
            int segmentChars = segment.transcript().length()
                    + segment.ocrTexts().stream().mapToInt(String::length).sum();
            if (!selected.isEmpty() && usedChars + segmentChars > MAX_CONTEXT_CHARS) continue;
            selected.add(segment);
            usedChars += segmentChars;
        }
        telemetry.incrementCurrent("contextSegmentsDropped", candidates.size() - selected.size());
        telemetry.valueCurrent("contextChars", usedChars);
        selected.sort(Comparator.comparingLong(VideoContext.VideoSegment::startMs));
        return new VideoContext(context.source(), context.userGoal(), selected);
    }

    private boolean nearSegment(long timestamp, VideoContext.VideoSegment segment) {
        long margin = Math.max(60_000L, segment.endMs() - segment.startMs());
        return timestamp >= Math.max(0, segment.startMs() - margin)
                && timestamp < segment.endMs() + margin;
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
        return cosine(queryEmbedding, chunk.embedding()) * 0.7 + keywordScore(goal, chunk) * 0.3;
    }

    private double keywordScore(String goal, VideoChunk chunk) {
        String normalizedGoal = normalize(goal);
        long matched = chunk.keywords().stream()
                .filter(keyword -> !keyword.isBlank() && normalizedGoal.contains(normalize(keyword)))
                .count();
        return chunk.keywords().isEmpty() ? 0 : (double) matched / chunk.keywords().size();
    }

    private List<VideoChunk> rankChunks(Long mediaId,
                                        String goal,
                                        List<Double> queryEmbedding,
                                        List<VideoChunk> chunks) {
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        if (mediaId != null && !queryEmbedding.isEmpty()) {
            try {
                vectorStore.search(mediaId, queryEmbedding, Math.max(TOP_K * 2, TOP_K)).forEach(hit ->
                        vectorScores.put(hit.startMs() + ":" + hit.endMs(), hit.score()));
            } catch (RuntimeException e) {
                telemetry.incrementCurrent("vectorStoreFallbacks", 1);
            }
        }
        return chunks.stream()
                .sorted(Comparator.comparingDouble((VideoChunk chunk) -> {
                    Double score = vectorScores.get(chunk.startMs() + ":" + chunk.endMs());
                    return score == null
                            ? hybridScore(goal, queryEmbedding, chunk)
                            : score * 0.7 + keywordScore(goal, chunk) * 0.3;
                }).reversed())
                .limit(TOP_K)
                .toList();
    }

    private void indexChunks(Long mediaId, List<VideoChunk> chunks) {
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可用本地向量和关键词完成检索，分析链路不用跟着停。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
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
