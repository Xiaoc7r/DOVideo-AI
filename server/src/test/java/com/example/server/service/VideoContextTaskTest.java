package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VideoContextTaskTest {
  @Test
  void cancellationBeforeExecutionReleasesCompletionExactlyOnceWithoutRunningWork() {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    var task = new VideoContextService.BranchTask<>(calls::incrementAndGet, completed);
    assertTrue(task.cancel(true));
    assertFalse(task.cancel(true));
    task.run();
    assertEquals(0, completed.getCount());
    assertEquals(0, calls.get());
  }

  @Test
  void cancellationOfRunningWorkDoesNotAllowCleanupBeforeItActuallyStops() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    var task =
        new VideoContextService.BranchTask<>(
            () -> {
              started.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                }
              }
              return 1;
            },
            completed);
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor.execute(task);
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(task.cancel(true));
        assertEquals(1, completed.getCount());
      } finally {
        release.countDown();
      }
      assertTrue(completed.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void normalCompletionAndFailureBothReleaseTheCompletionCounter() throws Exception {
    CountDownLatch completed = new CountDownLatch(2);
    var success = new VideoContextService.BranchTask<>(() -> "done", completed);
    var failure =
        new VideoContextService.BranchTask<>(
            () -> {
              throw new IllegalStateException("failed");
            },
            completed);
    success.run();
    failure.run();
    assertEquals("done", success.get());
    assertThrows(java.util.concurrent.ExecutionException.class, failure::get);
    assertEquals(0, completed.getCount());
  }
}
