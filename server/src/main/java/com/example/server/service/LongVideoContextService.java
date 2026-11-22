package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
public class LongVideoContextService {

  private static final long CHUNK_MS = 5 * 60 * 1000L;
  private static final int MAX_CONTEXT_CHARS = 24_000;

  private final AgentTelemetry telemetry;
  private final AgentCheckpointService checkpointService;
  private final VideoChunkingService chunkingService;
  private final VideoEvidenceRetrievalService retrievalService;
  private final RedissonClient redissonClient;

  public LongVideoContextService(
      AgentTelemetry telemetry,
      AgentCheckpointService checkpointService,
      VideoChunkingService chunkingService,
      VideoEvidenceRetrievalService retrievalService,
      RedissonClient redissonClient) {
    this.telemetry = telemetry;
    this.checkpointService = checkpointService;
    this.chunkingService = chunkingService;
    this.retrievalService = retrievalService;
    this.redissonClient = redissonClient;
  }

  public VideoContext selectRelevant(VideoContext context) {
    return selectRelevant(null, context);
  }

  public VideoContext selectRelevant(Long mediaId, VideoContext context) {
    if (context.segments().isEmpty()
        || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
      return withinBudget(context, context.segments());
    }

    List<VideoChunk> chunks = resolveChunks(mediaId, context.segments());
    List<VideoContext.VideoSegment> selectedSegments =
        retrievalService.retrieve(mediaId, context.userGoal(), chunks);
    return withinBudget(context, selectedSegments);
  }

  public List<VideoEvidenceHit> searchEvidence(Long mediaId, VideoContext context) {
    if (context.segments().isEmpty()) return List.of();
    List<VideoChunk> chunks = resolveChunks(mediaId, context.segments());
    return retrievalService.search(mediaId, context.userGoal(), chunks);
  }

  public VideoContext refineForCritique(
      Long mediaId,
      VideoContext fullContext,
      VideoContext selectedContext,
      AgentState.CriticResult critique) {
    Map<String, VideoContext.VideoSegment> segments = new LinkedHashMap<>();
    List<Long> requiredTimestamps = critique == null ? List.of() : critique.requiredTimestamps();
    fullContext.segments().stream()
        .filter(
            segment ->
                requiredTimestamps.stream().anyMatch(timestamp -> nearSegment(timestamp, segment)))
        .forEach(segment -> segments.put(segmentKey(segment), segment));

    String critiqueQuery = critiqueQuery(fullContext.userGoal(), critique);
    VideoContext retryContext =
        selectRelevant(
            mediaId, new VideoContext(fullContext.source(), critiqueQuery, fullContext.segments()));
    retryContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));
    selectedContext
        .segments()
        .forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));
    return withinBudget(fullContext, new ArrayList<>(segments.values()));
  }

  private String critiqueQuery(String goal, AgentState.CriticResult critique) {
    if (critique == null) return goal;
    return String.join(
        "\n",
        goal,
        String.join(" ", critique.feedback() == null ? List.of() : critique.feedback()),
        String.join(
            " ",
            critique.missingRequirements() == null ? List.of() : critique.missingRequirements()),
        String.join(
            " ", critique.unsupportedClaims() == null ? List.of() : critique.unsupportedClaims()));
  }

  private String segmentKey(VideoContext.VideoSegment segment) {
    return segment.startMs() + ":" + segment.endMs();
  }

  private VideoContext withinBudget(
      VideoContext context, List<VideoContext.VideoSegment> candidates) {
    List<VideoContext.VideoSegment> selected = new ArrayList<>();
    int usedChars = 0;
    for (VideoContext.VideoSegment segment : candidates) {
      int segmentChars =
          segment.transcript().length()
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

  private List<VideoChunk> resolveChunks(Long mediaId, List<VideoContext.VideoSegment> segments) {
    if (mediaId == null) return chunkingService.build(segments);
    List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
    if (chunks != null && !chunks.isEmpty()) {
      telemetry.incrementCurrent("chunkCheckpointHits", 1);
      return chunks;
    }

    RLock lock = redissonClient.getLock("lock:analysis-chunks:" + mediaId);
    boolean acquired = false;
    try {
      long waitMillis = Math.min(30_000L, AgentExecutionBudget.remainingMillis());
      acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
      AgentExecutionBudget.check("知识分块等待");
      if (!acquired) throw new IllegalStateException("视频知识分块正在生成，请稍后重试");
      // Different goals and evidence searches share the same durable chunks.
      chunks = checkpointService.loadChunks(mediaId);
      if (chunks != null && !chunks.isEmpty()) {
        telemetry.incrementCurrent("chunkCheckpointHits", 1);
        return chunks;
      }
      chunks = chunkingService.build(segments);
      checkpointService.saveChunks(mediaId, chunks);
      retrievalService.index(mediaId, chunks);
      return chunks;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待视频知识分块时被中断", e);
    } finally {
      if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
    }
  }
}
