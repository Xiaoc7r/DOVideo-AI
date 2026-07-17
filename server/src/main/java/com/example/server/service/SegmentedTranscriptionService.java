package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.AliyunAsrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SegmentedTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SegmentedTranscriptionService.class);
    private static final long SEGMENT_MS = 60_000L;

    private final AliyunAsrUtils aliyunAsrUtils;
    private final AgentTelemetry telemetry;

    public SegmentedTranscriptionService(AliyunAsrUtils aliyunAsrUtils, AgentTelemetry telemetry) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.telemetry = telemetry;
    }

    public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        runFfmpeg(videoPath, outputPattern);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<TranscriptSegment> result = new ArrayList<>();
        int failedSegments = 0;
        for (int i = 0; i < audioFiles.size(); i++) {
            Path audioFile = audioFiles.get(i);
            try {
                telemetry.increment(traceId, "asrCalls", 1);
                String text = aliyunAsrUtils.audioToText(audioFile.toString());
                if (text != null && !text.isBlank()) {
                    result.add(new TranscriptSegment(i * SEGMENT_MS, (i + 1) * SEGMENT_MS, text));
                }
            } catch (RuntimeException e) {
                failedSegments++;
                telemetry.increment(traceId, "asrSegmentFailures", 1);
                log.warn("asr_segment_failed segment={} file={}", i, audioFile.getFileName(), e);
            }
        }
        if (result.isEmpty() && failedSegments > 0) {
            throw new IllegalStateException("所有 ASR 分片均处理失败");
        }
        return result;
    }

    public String transcribeToText(String videoPath) {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "transcription-" + UUID.randomUUID());
        try {
            return transcribe(videoPath, workDir, null).stream()
                    .map(TranscriptSegment::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    private void runFfmpeg(String videoPath, Path outputPattern) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame",
                "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
                outputPattern.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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
