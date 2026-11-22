package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.entity.MediaFile;
import com.example.server.utils.MinioUtils;
import java.io.File;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

class ChunkUploadServiceTest {
  private static final String UPLOAD_ID = "1bd4f411-f4d2-4e4b-8b6e-07e032ae3bd2";
  private static final String KEY = "upload:chunked:" + UPLOAD_ID;
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
  private final SetOperations<String, String> sets = mock(SetOperations.class);
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final MinioUtils minio = mock(MinioUtils.class);
  private final MediaService mediaService = mock(MediaService.class);
  private final RedissonClient redisson = mock(RedissonClient.class);
  private final Map<String, String> receipts = new java.util.concurrent.ConcurrentHashMap<>();
  private final MediaFile media = new MediaFile();
  private ChunkUploadService service;

  @BeforeEach
  void setUp() throws Exception {
    when(redis.opsForHash()).thenReturn(hashes);
    when(redis.opsForSet()).thenReturn(sets);
    when(redis.opsForValue()).thenReturn(values);
    Map<Object, Object> metadata =
        new HashMap<>(Map.of("filename", "sample.mp4", "totalChunks", "2", "userId", "7"));
    when(hashes.entries(KEY)).thenReturn(metadata);
    when(sets.members(KEY + ":parts")).thenReturn(Set.of("0", "1"));
    // Model Redis deletions so a test observes the actual post-merge status.
    doAnswer(
            call -> {
              for (Object key : call.getArgument(0, java.util.Collection.class)) {
                if (KEY.equals(key)) metadata.clear();
              }
              return 1L;
            })
        .when(redis)
        .delete(anyCollection());
    when(values.get(anyString())).thenAnswer(call -> receipts.get(call.getArgument(0)));
    doAnswer(
            call -> {
              receipts.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    RLock lock = mock(RLock.class);
    RReadWriteLock state = mock(RReadWriteLock.class);
    when(redisson.getReadWriteLock(anyString())).thenReturn(state);
    when(state.readLock()).thenReturn(lock);
    when(state.writeLock()).thenReturn(lock);
    when(lock.tryLock()).thenReturn(true);
    when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenReturn(true);
    doAnswer(
            call -> {
              call.getArgument(1, OutputStream.class).write(new byte[] {1, 2, 3});
              return null;
            })
        .when(minio)
        .copyObjectTo(anyString(), any(OutputStream.class));
    when(minio.uploadLocalFile(any(File.class), eq("sample.mp4")))
        .thenReturn("http://localhost:9000/media/sample.mp4");
    media.setId(42L);
    media.setUserId(7L);
    when(mediaService.saveUploadedMedia(anyString(), anyString(), eq(7L), anyString()))
        .thenReturn(media);
    when(mediaService.requireOwnedMedia(42L, 7L)).thenReturn(media);
    service = new ChunkUploadService(redis, redisson, minio, mediaService);
  }

  @Test
  void mergeCannotReadPartsWhileAChunkReplacementIsStillInFlight() throws Exception {
    java.util.concurrent.locks.ReentrantReadWriteLock gate =
        new java.util.concurrent.locks.ReentrantReadWriteLock();
    RReadWriteLock distributed = mock(RReadWriteLock.class);
    RLock read = mock(RLock.class);
    RLock write = mock(RLock.class);
    when(redisson.getReadWriteLock(anyString())).thenReturn(distributed);
    when(distributed.readLock()).thenReturn(read);
    when(distributed.writeLock()).thenReturn(write);
    when(read.tryLock(anyLong(), any(TimeUnit.class)))
        .thenAnswer(call -> gate.readLock().tryLock(5, TimeUnit.SECONDS));
    when(read.isHeldByCurrentThread()).thenAnswer(call -> gate.getReadHoldCount() > 0);
    doAnswer(
            call -> {
              gate.readLock().unlock();
              return null;
            })
        .when(read)
        .unlock();
    when(write.tryLock()).thenAnswer(call -> gate.writeLock().tryLock());
    when(write.isHeldByCurrentThread()).thenAnswer(call -> gate.isWriteLockedByCurrentThread());
    doAnswer(
            call -> {
              gate.writeLock().unlock();
              return null;
            })
        .when(write)
        .unlock();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(minio.uploadObject(anyString(), any(), anyLong(), anyString()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return "part";
            });
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var uploading =
          executor.submit(
              () -> {
                service.uploadChunk(
                    UPLOAD_ID, 0, 2, new MockMultipartFile("file", new byte[] {4, 5, 6}), 7L);
                return null;
              });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var conflict =
            assertThrows(
                com.example.server.exception.BusinessException.class,
                () -> service.complete(UPLOAD_ID, 7L));
        assertEquals(40900, conflict.errorCode().code());
        verify(minio, never()).copyObjectTo(anyString(), any());
      } finally {
        release.countDown();
      }
      uploading.get(5, TimeUnit.SECONDS);
      assertSame(media, service.complete(UPLOAD_ID, 7L));
    }
    assertEquals(0, gate.getReadLockCount());
    assertFalse(gate.isWriteLocked());
  }

  @Test
  void completionWhileWaitingForChunkLockDoesNotRecreateDeletedParts() throws Exception {
    RReadWriteLock state = redisson.getReadWriteLock("lock:upload:state:" + UPLOAD_ID);
    RLock read = state.readLock();
    when(read.tryLock(anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            call -> {
              receipts.put(KEY + ":completed", "42");
              return true;
            });
    service.uploadChunk(UPLOAD_ID, 0, 2, new MockMultipartFile("file", new byte[] {1}), 7L);
    verify(minio, never()).uploadObject(anyString(), any(), anyLong(), anyString());
    verify(sets, never()).add(anyString(), anyString());
    verify(read).unlock();
  }

  @Test
  void lostCompletionResponseCanResumeWithoutUploadingOrSavingTwice() throws Exception {
    assertSame(media, service.complete(UPLOAD_ID, 7L));
    assertEquals(Set.of(0, 1), service.uploadedChunks(UPLOAD_ID, 7L));
    assertSame(media, service.complete(UPLOAD_ID, 7L));

    verify(mediaService, times(1)).saveUploadedMedia(anyString(), anyString(), eq(7L), anyString());
    verify(redis).expire(KEY, 1, TimeUnit.DAYS);
    verify(redis).expire(KEY + ":parts", 1, TimeUnit.DAYS);
    verify(minio).removeObject("chunk-uploads/" + UPLOAD_ID + "/part-0");
    verify(minio).removeObject("chunk-uploads/" + UPLOAD_ID + "/part-1");
  }

  @Test
  void completedUploadIgnoresLateChunkRetriesButKeepsOwnershipChecks() throws Exception {
    service.complete(UPLOAD_ID, 7L);
    MockMultipartFile chunk = new MockMultipartFile("file", new byte[] {1, 2, 3});
    service.uploadChunk(UPLOAD_ID, 0, 2, chunk, 7L);
    verify(minio, never()).uploadObject(anyString(), any(), anyLong(), anyString());
    assertThrows(SecurityException.class, () -> service.uploadedChunks(UPLOAD_ID, 8L));
    assertThrows(SecurityException.class, () -> service.uploadChunk(UPLOAD_ID, 0, 2, chunk, 8L));
  }

  @Test
  void temporaryObjectCleanupFailureStillAllowsCompletionRetry() throws Exception {
    doThrow(new IllegalStateException("storage temporarily unavailable"))
        .when(minio)
        .removeObject(anyString());
    assertSame(media, service.complete(UPLOAD_ID, 7L));
    assertEquals(Set.of(0, 1), service.uploadedChunks(UPLOAD_ID, 7L));
    assertSame(media, service.complete(UPLOAD_ID, 7L));
    verify(mediaService, times(1)).saveUploadedMedia(anyString(), anyString(), eq(7L), anyString());
  }
}
