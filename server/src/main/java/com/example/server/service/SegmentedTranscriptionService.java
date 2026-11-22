package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.MediaProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SegmentedTranscriptionService {

  private static final Logger log = LoggerFactory.getLogger(SegmentedTranscriptionService.class);
  private static final long SEGMENT_MS = 60_000L;
  private static final Pattern AUDIO_PART = Pattern.compile("audio_[0-9]+\\.mp3");

  private final AliyunAsrUtils aliyunAsrUtils;
  private final AgentTelemetry telemetry;
  private final MediaProcessRunner processes;

  public SegmentedTranscriptionService(
      AliyunAsrUtils aliyunAsrUtils, AgentTelemetry telemetry, MediaProcessRunner processes) {
    this.aliyunAsrUtils = aliyunAsrUtils;
    this.telemetry = telemetry;
    this.processes = processes;
  }

  public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId)
      throws Exception {
    Files.createDirectories(audioDir);
    Path outputPattern = audioDir.resolve("audio_%03d.mp3");
    processes.ffmpeg(
        List.of(
            "-y",
            "-i",
            videoPath,
            "-vn",
            "-acodec",
            "libmp3lame",
            "-f",
            "segment",
            "-segment_time",
            "60",
            "-reset_timestamps",
            "1",
            outputPattern.toString()),
        null,
        Duration.ofMinutes(15));

    List<Path> audioFiles;
    try (var paths = Files.list(audioDir)) {
      audioFiles =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> AUDIO_PART.matcher(path.getFileName().toString()).matches())
              .sorted(Comparator.comparingInt(this::segmentIndex))
              .toList();
    }

    List<TranscriptSegment> result = new ArrayList<>();
    int failedSegments = 0;
    RuntimeException lastSegmentError = null;
    for (int i = 0; i < audioFiles.size(); i++) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException("ASR interrupted");
      Path audioFile = audioFiles.get(i);
      long segmentIndex = segmentIndex(audioFile);
      try {
        telemetry.increment(traceId, "asrCalls", 1);
        String text = aliyunAsrUtils.audioToText(audioFile.toString());
        if (text != null && !text.isBlank()) {
          result.add(
              new TranscriptSegment(
                  segmentIndex * SEGMENT_MS, (segmentIndex + 1) * SEGMENT_MS, text));
        }
      } catch (RuntimeException e) {
        failedSegments++;
        lastSegmentError = e;
        telemetry.increment(traceId, "asrSegmentFailures", 1);
        log.warn("asr_segment_failed segment={} file={}", i, audioFile.getFileName(), e);
      }
    }
    if (result.isEmpty() && failedSegments > 0) {
      // 必须带上 cause：AliyunAsrUtils 已经区分了「429/5xx 可重试」与「4xx 请求本身有问题」，
      // 这里若丢掉原因，消费者只能看到一个笼统的 IllegalStateException，
      // 于是参数错误也会被当成抖动反复重试整条 ASR+LLM 流水线。
      throw new IllegalStateException("所有 ASR 分片均处理失败", lastSegmentError);
    }
    return result;
  }

  private int segmentIndex(Path file) {
    String name = file.getFileName().toString();
    return Integer.parseInt(name.substring("audio_".length(), name.length() - ".mp3".length()));
  }

  public String transcribeToText(String videoPath) {
    Path workDir =
        Path.of(System.getProperty("java.io.tmpdir"), "transcription-" + UUID.randomUUID());
    try {
      return transcribe(videoPath, workDir, null).stream()
          .map(TranscriptSegment::text)
          .filter(text -> !text.isBlank())
          .collect(java.util.stream.Collectors.joining("\n"));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("视频转写失败", e);
    } finally {
      deleteDirectory(workDir);
    }
  }

  private void deleteDirectory(Path directory) {
    if (!Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception e) {
                  log.warn("transcription_temporary_file_cleanup_failed path={}", path, e);
                }
              });
    } catch (Exception e) {
      log.warn("transcription_temporary_directory_cleanup_failed path={}", directory, e);
    }
  }
}
