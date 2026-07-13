package com.example.server.service;

import com.example.server.utils.AliyunAsrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class VideoTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(VideoTranscriptionService.class);

    private final AliyunAsrUtils aliyunAsrUtils;

    public VideoTranscriptionService(AliyunAsrUtils aliyunAsrUtils) {
        this.aliyunAsrUtils = aliyunAsrUtils;
    }

    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    private String processVideoToText(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) throw new IllegalArgumentException("视频路径为空");
        if (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath))) {
            throw new IllegalArgumentException("视频文件不存在");
        }

        Path audioPath = null;
        try {
            audioPath = Files.createTempFile("dovideo-transcribe-", ".mp3");
            extractAudio(inputPath, audioPath);
            return aliyunAsrUtils.audioToText(audioPath.toString());
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败", e);
        } finally {
            if (audioPath != null) {
                try {
                    Files.deleteIfExists(audioPath);
                } catch (Exception cleanupError) {
                    log.warn("temporary_audio_cleanup_failed path={}", audioPath, cleanupError);
                }
            }
        }
    }

    private void extractAudio(String inputPath, Path outputPath) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-vn", "-acodec", "libmp3lame", "-q:a", "2", outputPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg execution timed out");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg conversion failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
