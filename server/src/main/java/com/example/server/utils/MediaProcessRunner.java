package com.example.server.utils;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owns native-process admission, deadlines and cleanup for media tools. */
@Component
public class MediaProcessRunner {
  private final Semaphore slots;
  private final String threads;
  private final String ffmpeg;
  private final Set<Process> active = new HashSet<>();
  private volatile boolean closed;

  public MediaProcessRunner(
      @Value("${tool.media.max-processes:2}") int maxProcesses,
      @Value("${tool.media.threads:1}") int threads,
      @Value("${tool.ffmpeg.dir:}") String ffmpegDir) {
    if (maxProcesses < 1 || threads < 1) {
      throw new IllegalArgumentException("Media process and thread limits must be positive");
    }
    slots = new Semaphore(maxProcesses, true);
    this.threads = Integer.toString(threads);
    String executable =
        System.getProperty("os.name").startsWith("Windows") ? "ffmpeg.exe" : "ffmpeg";
    ffmpeg = ffmpegDir.isBlank() ? executable : Path.of(ffmpegDir, executable).toString();
  }

  public void ffmpeg(List<String> arguments, Path logPath, Duration timeout)
      throws IOException, InterruptedException {
    List<String> command =
        new ArrayList<>(
            List.of(ffmpeg, "-nostdin", "-threads", threads, "-filter_threads", threads));
    command.addAll(arguments.subList(0, arguments.size() - 1));
    // FFmpeg has separate input-decoder and output-encoder thread options.
    command.addAll(List.of("-threads", threads, arguments.get(arguments.size() - 1)));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.redirectOutput(
        logPath == null
            ? ProcessBuilder.Redirect.DISCARD
            : ProcessBuilder.Redirect.to(logPath.toFile()));
    if (run(builder, timeout) != 0) throw new IllegalStateException("FFmpeg 执行失败");
  }

  public int run(ProcessBuilder builder, Duration timeout)
      throws IOException, InterruptedException {
    if (closed) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "媒体工具服务正在关闭");
    long deadline = System.nanoTime() + timeout.toNanos();
    long admissionWait = Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(30));
    if (!slots.tryAcquire(admissionWait, TimeUnit.NANOSECONDS)) {
      throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "媒体工具繁忙，请稍后重试");
    }
    Process process = null;
    try {
      builder.environment().put("OMP_THREAD_LIMIT", threads);
      synchronized (active) {
        if (closed) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "媒体工具服务正在关闭");
        process = builder.start();
        active.add(process);
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
        throw new IllegalStateException("媒体工具执行超时");
      }
      return process.exitValue();
    } finally {
      try {
        terminate(process);
      } finally {
        synchronized (active) {
          active.remove(process);
        }
        slots.release();
      }
    }
  }

  @PreDestroy
  public void close() {
    List<Process> processes;
    synchronized (active) {
      closed = true;
      processes = List.copyOf(active);
    }
    processes.forEach(this::terminate);
  }

  private void terminate(Process process) {
    if (process == null || !process.isAlive()) return;
    try (var descendants = process.descendants()) {
      descendants.forEach(ProcessHandle::destroyForcibly);
    }
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
