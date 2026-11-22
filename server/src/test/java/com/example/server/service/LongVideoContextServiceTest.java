package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class LongVideoContextServiceTest {
  private final VideoContext context =
      new VideoContext(
          "source",
          "query",
          List.of(new VideoContext.VideoSegment(0, 600_000, "text", List.of(), List.of())));

  @Test
  void concurrentQueriesBuildAndIndexSharedChunksOnlyOnce() throws Exception {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    VideoChunkingService builder = mock(VideoChunkingService.class);
    VideoEvidenceRetrievalService retrieval = mock(VideoEvidenceRetrievalService.class);
    RedissonClient redis = mock(RedissonClient.class);
    RLock distributed = mock(RLock.class);
    ReentrantLock lock = new ReentrantLock();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch contended = new CountDownLatch(1);
    AtomicReference<List<VideoChunk>> saved = new AtomicReference<>();
    List<VideoChunk> chunks =
        List.of(new VideoChunk(0, 600_000, "summary", List.of(), context.segments(), List.of()));
    when(redis.getLock("lock:analysis-chunks:7")).thenReturn(distributed);
    when(distributed.tryLock(anyLong(), any()))
        .thenAnswer(
            call -> {
              if (lock.isLocked()) contended.countDown();
              return lock.tryLock(5, TimeUnit.SECONDS);
            });
    when(distributed.isHeldByCurrentThread()).thenAnswer(call -> lock.isHeldByCurrentThread());
    doAnswer(
            call -> {
              lock.unlock();
              return null;
            })
        .when(distributed)
        .unlock();
    when(checkpoints.loadChunks(7L)).thenAnswer(call -> saved.get());
    doAnswer(
            call -> {
              saved.set(call.getArgument(1));
              return null;
            })
        .when(checkpoints)
        .saveChunks(eq(7L), anyList());
    when(builder.build(context.segments()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return chunks;
            });
    LongVideoContextService service =
        new LongVideoContextService(
            mock(AgentTelemetry.class), checkpoints, builder, retrieval, redis);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> service.searchEvidence(7L, context));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var second = executor.submit(() -> service.searchEvidence(7L, context));
      assertTrue(contended.await(5, TimeUnit.SECONDS));
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    verify(builder, times(1)).build(context.segments());
    verify(checkpoints, times(1)).saveChunks(7L, chunks);
    verify(retrieval, times(1)).index(7L, chunks);
    verify(retrieval, times(2)).search(7L, "query", chunks);
    assertFalse(lock.isLocked());
  }

  @Test
  void expiredAgentBudgetAfterLockAcquisitionReleasesTheLockWithoutBuilding() throws Exception {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    VideoChunkingService builder = mock(VideoChunkingService.class);
    RedissonClient redis = mock(RedissonClient.class);
    RLock lock = mock(RLock.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    when(lock.isHeldByCurrentThread()).thenReturn(true);
    when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(
            call -> {
              assertTrue((Long) call.getArgument(0) <= 100L);
              Thread.sleep(120);
              return true;
            });
    LongVideoContextService service =
        new LongVideoContextService(
            mock(AgentTelemetry.class),
            checkpoints,
            builder,
            mock(VideoEvidenceRetrievalService.class),
            redis);
    try (var budget = AgentExecutionBudget.open(100)) {
      assertThrows(
          AgentExecutionBudget.DeadlineExceededException.class,
          () -> service.searchEvidence(7L, context));
    }
    verify(lock).unlock();
    verifyNoInteractions(builder);
  }

  @Test
  void failedBuildReleasesLockAndAllowsRetryWithoutCachingFailure() throws Exception {
    AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    VideoChunkingService builder = mock(VideoChunkingService.class);
    RedissonClient redis = mock(RedissonClient.class);
    RLock lock = mock(RLock.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    when(lock.tryLock(anyLong(), any())).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenReturn(true);
    when(builder.build(anyList())).thenThrow(new IllegalStateException("provider failed"));
    LongVideoContextService service =
        new LongVideoContextService(
            mock(AgentTelemetry.class),
            checkpoints,
            builder,
            mock(VideoEvidenceRetrievalService.class),
            redis);
    assertThrows(IllegalStateException.class, () -> service.searchEvidence(7L, context));
    verify(lock).unlock();
    verify(checkpoints, never()).saveChunks(anyLong(), anyList());
    when(lock.tryLock(anyLong(), any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.searchEvidence(7L, context));
    verify(builder, times(1)).build(anyList());
    when(lock.tryLock(anyLong(), any())).thenReturn(true);
    List<VideoChunk> recovered =
        List.of(new VideoChunk(0, 600_000, "recovered", List.of(), context.segments(), List.of()));
    doReturn(recovered).when(builder).build(anyList());
    assertDoesNotThrow(() -> service.searchEvidence(7L, context));
    verify(checkpoints).saveChunks(7L, recovered);
    verify(lock, times(2)).unlock();
  }
}
