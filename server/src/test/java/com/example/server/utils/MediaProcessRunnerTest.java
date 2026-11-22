package com.example.server.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.example.server.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaProcessRunnerTest {
  @TempDir Path directory;

  @Test
  void concurrentCommandsRespectTheSharedProcessLimitAndChildThreadLimit() throws Exception {
    MediaProcessRunner runner = new MediaProcessRunner(1, 1, "");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> runner.run(command("exclusive"), Duration.ofSeconds(15)));
      var second = executor.submit(() -> runner.run(command("exclusive"), Duration.ofSeconds(15)));
      assertEquals(0, first.get(20, TimeUnit.SECONDS));
      assertEquals(0, second.get(20, TimeUnit.SECONDS));
    }
    assertEquals("1", Files.readString(directory.resolve("threads")));
    assertFalse(Files.exists(directory.resolve("active")));
  }

  @Test
  void timeoutKillsTheProcessAndReleasesCapacityForTheNextCommand() throws Exception {
    MediaProcessRunner runner = new MediaProcessRunner(1, 1, "");
    assertThrows(
        IllegalStateException.class, () -> runner.run(command("sleep"), Duration.ofSeconds(2)));
    long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    assertEquals(0, runner.run(command("exclusive"), Duration.ofSeconds(10)));
  }

  @Test
  void saturatedAdmissionDoesNotStartAnotherProcessAndInterruptionReleasesCapacity()
      throws Exception {
    MediaProcessRunner runner = new MediaProcessRunner(1, 1, "");
    CountDownLatch stopped = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running =
          executor.submit(
              () -> {
                try {
                  runner.run(command("sleep"), Duration.ofSeconds(15));
                } catch (InterruptedException e) {
                  interrupted.set(true);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                } finally {
                  stopped.countDown();
                }
              });
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(directory.resolve("pid")) && System.nanoTime() < deadline)
          Thread.sleep(20);
        assertTrue(Files.exists(directory.resolve("pid")));
        long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
        BusinessException busy =
            assertThrows(
                BusinessException.class,
                () -> runner.run(command("exclusive"), Duration.ofMillis(50)));
        assertEquals(50300, busy.errorCode().code());
        assertFalse(Files.exists(directory.resolve("active")));
        running.cancel(true);
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(0, runner.run(command("exclusive"), Duration.ofSeconds(10)));
      } finally {
        running.cancel(true);
      }
    }
  }

  @Test
  void shutdownTerminatesOwnedProcessesAndRejectsNewWork() throws Exception {
    MediaProcessRunner runner = new MediaProcessRunner(1, 1, "");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = executor.submit(() -> runner.run(command("sleep"), Duration.ofSeconds(15)));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(directory.resolve("pid")) && System.nanoTime() < deadline)
          Thread.sleep(20);
        assertTrue(Files.exists(directory.resolve("pid")));
        long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
        runner.close();
        assertNotEquals(0, running.get(5, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertThrows(
            BusinessException.class, () -> runner.run(command("exclusive"), Duration.ofSeconds(1)));
      } finally {
        runner.close();
        running.cancel(true);
      }
    }
  }

  @Test
  void startupFailureReleasesCapacity() throws Exception {
    MediaProcessRunner runner = new MediaProcessRunner(1, 1, "");
    assertThrows(
        java.io.IOException.class,
        () ->
            runner.run(
                new ProcessBuilder(directory.resolve("missing-program").toString()),
                Duration.ofSeconds(1)));
    assertEquals(0, runner.run(command("exclusive"), Duration.ofSeconds(10)));
  }

  @Test
  void invalidLimitsFailAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> new MediaProcessRunner(0, 1, ""));
    assertThrows(IllegalArgumentException.class, () -> new MediaProcessRunner(1, 0, ""));
  }

  private ProcessBuilder command(String action) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classes =
        Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    return new ProcessBuilder(
            java, "-cp", classes, Probe.class.getName(), action, directory.toString())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      Path directory = Path.of(args[1]);
      Path pendingPid = directory.resolve("pid.pending");
      Files.writeString(pendingPid, Long.toString(ProcessHandle.current().pid()));
      Files.move(
          pendingPid, directory.resolve("pid"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      if (args[0].equals("sleep")) {
        Thread.sleep(30_000);
        return;
      }
      Path active = directory.resolve("active");
      Files.createFile(active);
      Files.writeString(directory.resolve("threads"), System.getenv("OMP_THREAD_LIMIT"));
      Thread.sleep(250);
      Files.delete(active);
    }
  }
}
