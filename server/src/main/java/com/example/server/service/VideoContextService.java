package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.OcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);
    private static final String EVIDENCE_OBJECT_PREFIX = "evidence-frames";
    private static final long SEGMENT_MS = 60_000L;
    private static final long FALLBACK_FRAME_INTERVAL_MS = 30_000L;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

    private final SegmentedTranscriptionService transcriptionService;
    private final OcrUtils ocrUtils;
    private final MinioUtils minioUtils;
    private final Executor asrExecutor;
    private final Executor ocrExecutor;
    private final AgentTelemetry telemetry;

    public VideoContextService(SegmentedTranscriptionService transcriptionService,
                               OcrUtils ocrUtils,
                               MinioUtils minioUtils,
                               @Qualifier("asrExecutor") Executor asrExecutor,
                               @Qualifier("ocrExecutor") Executor ocrExecutor,
                               AgentTelemetry telemetry) {
        this.transcriptionService = transcriptionService;
        this.ocrUtils = ocrUtils;
        this.minioUtils = minioUtils;
        this.asrExecutor = asrExecutor;
        this.ocrExecutor = ocrExecutor;
        this.telemetry = telemetry;
    }

    public VideoContext build(String videoPath, String userGoal) {
        return build(videoPath, userGoal, null);
    }

    public VideoContext build(String videoPath, String userGoal, String traceId) {
        String readableVideoPath = minioUtils.readableSource(videoPath);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        List<String> uploadedEvidenceFrames = new CopyOnWriteArrayList<>();
        try {
            Files.createDirectories(workDir);
            // 两条分支各跑各的，单路挂掉还能带着另一半信息继续往下走。
            CompletableFuture<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
                    asrExecutor,
                    () -> transcriptionService.transcribe(
                            readableVideoPath, workDir.resolve("audio"), traceId));
            CompletableFuture<BranchResult<FramePart>> frameFuture = submitBranch(
                    ocrExecutor,
                    () -> extractKeyFrames(
                            readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames));
            try {
                CompletableFuture.allOf(transcriptFuture, frameFuture).get(60, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                transcriptFuture.cancel(true);
                frameFuture.cancel(true);
                throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
            } catch (InterruptedException e) {
                transcriptFuture.cancel(true);
                frameFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VideoContext 构建被中断", e);
            }
            BranchResult<TranscriptSegment> transcriptResult = transcriptFuture.join();
            BranchResult<FramePart> frameResult = frameFuture.join();
            if (transcriptResult.failed() && frameResult.failed()) {
                IllegalStateException failure = new IllegalStateException(
                        "ASR 和 OCR 分支均失败", transcriptResult.error());
                failure.addSuppressed(frameResult.error());
                throw failure;
            }
            if (transcriptResult.failed()) {
                telemetry.increment(traceId, "asrBranchFailures", 1);
                log.warn("video_context_asr_branch_failed", transcriptResult.error());
            }
            if (frameResult.failed()) {
                telemetry.increment(traceId, "ocrBranchFailures", 1);
                log.warn("video_context_ocr_branch_failed", frameResult.error());
                deleteEvidenceFrames(uploadedEvidenceFrames);
                uploadedEvidenceFrames.clear();
            }
            List<VideoContext.VideoSegment> segments = merge(transcriptResult.items(), frameResult.items());
            if (segments.isEmpty()) throw new IllegalStateException("视频未解析出有效语音或画面文字");
            return new VideoContext(videoPath, userGoal, segments);
        } catch (Exception e) {
            deleteEvidenceFrames(uploadedEvidenceFrames);
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    public void deleteEvidenceFrames(VideoContext context) {
        if (context == null) return;
        deleteEvidenceFrames(context.segments().stream()
                .flatMap(segment -> segment.evidenceFrames().stream())
                .distinct()
                .toList());
    }

    private void deleteEvidenceFrames(List<String> frames) {
        frames.stream()
                .filter(frame -> minioUtils.isManagedFile(frame, EVIDENCE_OBJECT_PREFIX))
                .distinct()
                .forEach(frame -> {
                    try {
                        minioUtils.removeFile(frame);
                    } catch (RuntimeException e) {
                        log.warn("evidence_frame_cleanup_failed frame={}", frame, e);
                    }
                });
    }

    private <T> CompletableFuture<BranchResult<T>> submitBranch(
            Executor executor, ThrowingSupplier<List<T>> work) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return BranchResult.success(work.get());
                } catch (Exception e) {
                    return BranchResult.failure(e);
                }
            }, executor);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(BranchResult.failure(e));
        }
    }

    private List<FramePart> extractKeyFrames(String videoPath,
                                             Path frameDir,
                                             String traceId,
                                             List<String> uploadedEvidenceFrames) throws Exception {
        Files.createDirectories(frameDir);
        List<Long> timestamps = new ArrayList<>();
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vf", "select=eq(n\\,0)+gt(scene\\,0.35)+gte(t-prev_selected_t\\,30),showinfo",
                "-vsync", "vfr",
                frameDir.resolve("frame_%06d.jpg").toString()
        ), timestamps);

        List<Path> frameFiles;
        try (var paths = Files.list(frameDir)) {
            frameFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<FramePart> result = new ArrayList<>();
        Long previousHash = null;
        int failedFrames = 0;
        for (int i = 0; i < frameFiles.size(); i++) {
            long imageHash = differenceHash(frameFiles.get(i).toFile());
            if (previousHash != null && Long.bitCount(previousHash ^ imageHash) <= 5) {
                continue;
            }
            previousHash = imageHash;
            long timestampMs = i < timestamps.size() ? timestamps.get(i) : i * FALLBACK_FRAME_INTERVAL_MS;
            String ocrText;
            try {
                telemetry.increment(traceId, "ocrCalls", 1);
                ocrText = ocrUtils.recognize(frameFiles.get(i).toFile());
            } catch (RuntimeException e) {
                failedFrames++;
                telemetry.increment(traceId, "ocrFrameFailures", 1);
                log.warn("ocr_frame_failed frame={} timestampMs={}",
                        frameFiles.get(i).getFileName(), timestampMs, e);
                continue;
            }
            String frameUrl;
            try {
                frameUrl = minioUtils.uploadLocalFile(
                        frameFiles.get(i).toFile(),
                        frameFiles.get(i).getFileName().toString(),
                        EVIDENCE_OBJECT_PREFIX);
                uploadedEvidenceFrames.add(frameUrl);
            } catch (Exception e) {
                telemetry.increment(traceId, "frameUploadFailures", 1);
                log.warn("evidence_frame_upload_failed frame={} timestampMs={}",
                        frameFiles.get(i).getFileName(), timestampMs, e);
                frameUrl = videoPath + "#timestampMs=" + timestampMs;
            }
            result.add(new FramePart(timestampMs, ocrText, frameUrl));
        }
        if (result.isEmpty() && failedFrames > 0) {
            throw new IllegalStateException("所有 OCR 关键帧均处理失败");
        }
        return result;
    }

    private List<VideoContext.VideoSegment> merge(List<TranscriptSegment> transcripts, List<FramePart> frames) {
        Map<Long, SegmentBuilder> windows = new TreeMap<>();
        for (TranscriptSegment transcript : transcripts) {
            long windowStart = windowStart(transcript.startMs());
            windows.computeIfAbsent(windowStart, SegmentBuilder::new).transcripts.add(transcript.text());
        }
        for (FramePart frame : frames) {
            long windowStart = windowStart(frame.timestampMs());
            SegmentBuilder segment = windows.computeIfAbsent(windowStart, SegmentBuilder::new);
            if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
                segment.ocrTexts.add(frame.ocrText());
            }
            segment.evidenceFrames.add(frame.frameName());
        }
        return windows.values().stream().map(SegmentBuilder::build).toList();
    }

    private long windowStart(long timestampMs) {
        return timestampMs / SEGMENT_MS * SEGMENT_MS;
    }

    private long differenceHash(File imageFile) throws Exception {
        BufferedImage source = ImageIO.read(imageFile);
        if (source == null) return 0;
        BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }

        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (scaled.getRGB(x, y) > scaled.getRGB(x + 1, y)) hash |= 1;
            }
        }
        return hash;
    }

    private void runCommand(List<String> command, List<Long> timestamps) throws Exception {
        Path logPath = Files.createTempFile("dovideo-ffmpeg-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
            if (timestamps != null) {
                try (Stream<String> lines = Files.lines(logPath)) {
                    lines.forEach(line -> appendTimestamp(line, timestamps));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(logPath);
        }
    }

    private void appendTimestamp(String line, List<Long> timestamps) {
        if (!line.contains("showinfo")) return;
        Matcher matcher = PTS_TIME.matcher(line);
        if (matcher.find()) {
            timestamps.add((long) (Double.parseDouble(matcher.group(1)) * 1000));
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    private record FramePart(long timestampMs, String ocrText, String frameName) {
    }

    private record BranchResult<T>(List<T> items, Exception error) {
        private static <T> BranchResult<T> success(List<T> items) {
            return new BranchResult<>(items, null);
        }

        private static <T> BranchResult<T> failure(Exception error) {
            return new BranchResult<>(List.of(), error);
        }

        private boolean failed() {
            return error != null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static class SegmentBuilder {
        private final long startMs;
        private final List<String> transcripts = new ArrayList<>();
        private final List<String> ocrTexts = new ArrayList<>();
        private final List<String> evidenceFrames = new ArrayList<>();

        private SegmentBuilder(long startMs) {
            this.startMs = startMs;
        }

        private VideoContext.VideoSegment build() {
            return new VideoContext.VideoSegment(
                    startMs,
                    startMs + SEGMENT_MS,
                    String.join("\n", transcripts),
                    ocrTexts,
                    evidenceFrames
            );
        }
    }
}
