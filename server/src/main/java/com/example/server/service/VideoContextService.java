package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.OcrUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VideoContextService {

    private static final long SEGMENT_MS = 60_000L;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

    @Autowired
    private AliyunAsrUtils aliyunAsrUtils;

    @Autowired
    private OcrUtils ocrUtils;

    public VideoContext build(String videoPath) {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        try {
            Files.createDirectories(workDir);
            List<TranscriptPart> transcripts = transcribeBySegments(videoPath, workDir.resolve("audio"));
            List<FramePart> frames = extractKeyFrames(videoPath, workDir.resolve("frames"));
            return new VideoContext(videoPath, merge(transcripts, frames));
        } catch (Exception e) {
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    private List<TranscriptPart> transcribeBySegments(String videoPath, Path audioDir) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame",
                "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
                outputPattern.toString()
        ), null);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<TranscriptPart> result = new ArrayList<>();
        for (int i = 0; i < audioFiles.size(); i++) {
            String text = aliyunAsrUtils.audioToText(audioFiles.get(i).toString());
            if (text != null && !text.startsWith("❌")) {
                result.add(new TranscriptPart(i * SEGMENT_MS, (i + 1) * SEGMENT_MS, text));
            }
        }
        return result;
    }

    private List<FramePart> extractKeyFrames(String videoPath, Path frameDir) throws Exception {
        Files.createDirectories(frameDir);
        List<Long> timestamps = new ArrayList<>();
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vf", "select=gt(scene\\,0.35),showinfo",
                "-vsync", "vfr",
                frameDir.resolve("frame_%06d.jpg").toString()
        ), timestamps);

        List<Path> frameFiles;
        try (var paths = Files.list(frameDir)) {
            frameFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<FramePart> result = new ArrayList<>();
        for (int i = 0; i < frameFiles.size(); i++) {
            long timestampMs = i < timestamps.size() ? timestamps.get(i) : i * SEGMENT_MS;
            String ocrText = ocrUtils.recognize(frameFiles.get(i).toFile());
            result.add(new FramePart(timestampMs, ocrText, frameFiles.get(i).getFileName().toString()));
        }
        return result;
    }

    private List<VideoContext.VideoSegment> merge(List<TranscriptPart> transcripts, List<FramePart> frames) {
        List<VideoContext.VideoSegment> result = new ArrayList<>();
        for (TranscriptPart transcript : transcripts) {
            List<String> ocrTexts = new ArrayList<>();
            List<String> evidenceFrames = new ArrayList<>();
            for (FramePart frame : frames) {
                if (frame.timestampMs() >= transcript.startMs() && frame.timestampMs() < transcript.endMs()) {
                    if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
                        ocrTexts.add(frame.ocrText());
                    }
                    evidenceFrames.add(frame.frameName());
                }
            }
            result.add(new VideoContext.VideoSegment(
                    transcript.startMs(),
                    transcript.endMs(),
                    transcript.text(),
                    ocrTexts,
                    evidenceFrames
            ));
        }
        return result;
    }

    private void runCommand(List<String> command, List<Long> timestamps) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (timestamps != null && line.contains("showinfo")) {
                    Matcher matcher = PTS_TIME.matcher(line);
                    if (matcher.find()) {
                        timestamps.add((long) (Double.parseDouble(matcher.group(1)) * 1000));
                    }
                }
            }
        }
        if (!process.waitFor(15, TimeUnit.MINUTES) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("FFmpeg 执行失败");
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
        }
    }

    private record TranscriptPart(long startMs, long endMs, String text) {
    }

    private record FramePart(long timestampMs, String ocrText, String frameName) {
    }
}
