package com.example.server.service;

import com.example.server.entity.MediaFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class AudioExportService {

  private static final long CONVERSION_TIMEOUT_MINUTES = 15;

  private final MediaService mediaService;

  public AudioExportService(MediaService mediaService) {
    this.mediaService = mediaService;
  }

  public Path exportMp3(MediaFile mediaFile) {
    String inputPath = mediaService.readableSource(mediaFile.getFilePath());
    if (inputPath == null
        || inputPath.isBlank()
        || (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath)))) {
      throw new NoSuchElementException("视频源文件不存在");
    }

    Path outputPath = null;
    Process process = null;
    try {
      outputPath = Files.createTempFile("dovideo-audio-", ".mp3");
      process =
          new ProcessBuilder(
                  "ffmpeg",
                  "-y",
                  "-i",
                  inputPath,
                  "-vn",
                  "-acodec",
                  "libmp3lame",
                  "-q:a",
                  "2",
                  outputPath.toString())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("音频转换超时");
      }
      if (process.exitValue() != 0) throw new IllegalStateException("音频转换失败");
      return outputPath;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      if (outputPath != null) {
        try {
          Files.deleteIfExists(outputPath);
        } catch (Exception cleanupError) {
          e.addSuppressed(cleanupError);
        }
      }
      if (e instanceof RuntimeException runtimeException) throw runtimeException;
      throw new IllegalStateException("音频转换失败", e);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }
}
