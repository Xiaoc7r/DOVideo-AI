package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.OcrUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
    private final ThreadPoolTaskExecutor asrExecutor;
    private final ThreadPoolTaskExecutor ocrExecutor;
    private final AgentTelemetry telemetry;

    public VideoContextService(SegmentedTranscriptionService transcriptionService,
                               OcrUtils ocrUtils,
                               MinioUtils minioUtils,
                               @Qualifier("asrExecutor") ThreadPoolTaskExecutor asrExecutor,
                               @Qualifier("ocrExecutor") ThreadPoolTaskExecutor ocrExecutor,
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
        // 纯音频没有画面流：抽帧既跑不出结果、也没有证据可展示，直接跳过该分支。
        // 其余链路（ASR、合并、分块检索、AgentLoop）与视频完全一致。
        boolean hasVideo = hasVideoStream(readableVideoPath, traceId);
        CountDownLatch branchesFinished = new CountDownLatch(hasVideo ? 2 : 1);
        boolean cleanupWorkDir = true;
        try {
            Files.createDirectories(workDir);
            // 两条分支各跑各的，单路挂掉还能带着另一半信息继续往下走。
            Future<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
                    asrExecutor,
                    branchesFinished,
                    () -> transcriptionService.transcribe(
                            readableVideoPath, workDir.resolve("audio"), traceId));
            Future<BranchResult<FramePart>> frameFuture = hasVideo
                    ? submitBranch(
                            ocrExecutor,
                            branchesFinished,
                            () -> extractKeyFrames(
                                    readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames))
                    : CompletableFuture.completedFuture(BranchResult.success(List.of()));
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
                BranchResult<TranscriptSegment> transcriptResult = awaitBranch(transcriptFuture, deadline);
                BranchResult<FramePart> frameResult = awaitBranch(frameFuture, deadline);
                return finishContext(
                        videoPath, userGoal, traceId, transcriptResult, frameResult,
                        uploadedEvidenceFrames, hasVideo);
            } catch (TimeoutException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
            } catch (InterruptedException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VideoContext 构建被中断", e);
            } catch (ExecutionException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支执行失败", e.getCause());
            }
        } catch (Exception e) {
            deleteEvidenceFrames(uploadedEvidenceFrames);
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            if (cleanupWorkDir) {
                deleteDirectory(workDir);
            } else {
                log.warn("video_context_workdir_retained path={} reason=branch_still_running", workDir);
            }
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

    private VideoContext finishContext(String videoPath,
                                       String userGoal,
                                       String traceId,
                                       BranchResult<TranscriptSegment> transcriptResult,
                                       BranchResult<FramePart> frameResult,
                                       List<String> uploadedEvidenceFrames,
                                       boolean hasVideo) {
        if (transcriptResult.failed() && frameResult.failed()) {
            IllegalStateException failure = new IllegalStateException(
                    hasVideo ? "ASR 和 OCR 分支均失败" : "音频未解析出有效语音", transcriptResult.error());
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
        if (segments.isEmpty()) {
            throw new IllegalStateException(hasVideo ? "视频未解析出有效语音或画面文字" : "音频未解析出有效语音");
        }
        return new VideoContext(videoPath, userGoal, segments);
    }

    /**
     * 判断源文件是否含可抽帧的视频流。
     *
     * <p>纯音频（mp3/wav/m4a 等）不该走抽帧与 OCR：ffmpeg 抽帧会直接报错，
     * 白白占用 OCR 线程池并产生一条无意义的失败分支。这里用 ffprobe 提前判定。
     *
     * <p>注意 mp3 的 ID3 封面会以一个 {@code attached_pic=1} 的视频流出现，
     * 它只是一张静态封面，不能当作视频轨，否则会去对封面做 OCR 并把它当成证据帧。
     *
     * <p>探测失败时按「有视频」处理，退回原有完整链路，避免因环境缺少 ffprobe 而漏掉 OCR。
     */
    private boolean hasVideoStream(String path, String traceId) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "v",
                    "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
                    "-of", "json", path)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return true;
            }
            boolean hasVideo = hasPlayableVideoStream(output);
            telemetry.increment(traceId, hasVideo ? "videoSources" : "audioSources", 1);
            return hasVideo;
        } catch (Exception e) {
            log.warn("media_stream_probe_failed path={}", path, e);
            return true;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private boolean hasPlayableVideoStream(String ffprobeJson) {
        JSONArray streams = JSON.parseObject(ffprobeJson).getJSONArray("streams");
        if (streams == null || streams.isEmpty()) return false;
        for (int i = 0; i < streams.size(); i++) {
            JSONObject stream = streams.getJSONObject(i);
            if (!"video".equals(stream.getString("codec_type"))) continue;
            JSONObject disposition = stream.getJSONObject("disposition");
            boolean attachedPicture = disposition != null && disposition.getIntValue("attached_pic") == 1;
            if (!attachedPicture) return true;
        }
        return false;
    }

    private <T> Future<BranchResult<T>> submitBranch(
            ThreadPoolTaskExecutor executor,
            CountDownLatch branchesFinished,
            ThrowingSupplier<List<T>> work) {
        try {
            return executor.submit(() -> {
                try {
                    return BranchResult.success(work.get());
                } catch (Exception e) {
                    return BranchResult.failure(e);
                } finally {
                    branchesFinished.countDown();
                }
            });
        } catch (RuntimeException e) {
            branchesFinished.countDown();
            return CompletableFuture.completedFuture(BranchResult.failure(e));
        }
    }

    private <T> BranchResult<T> awaitBranch(Future<BranchResult<T>> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new TimeoutException("VideoContext 总时间预算已耗尽");
        return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private boolean cancelBranches(CountDownLatch branchesFinished, Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) future.cancel(true);
        }
        try {
            return branchesFinished.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
                // 用 -fps_mode 而非 -vsync：后者自 ffmpeg 5.0 起弃用、8.0 起移除，
                // 在 ffmpeg 9 上会直接报 "Unrecognized option 'vsync'" 导致抽帧失败。
                "-fps_mode", "vfr",
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
