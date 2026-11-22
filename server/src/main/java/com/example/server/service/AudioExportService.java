package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.utils.MediaProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

@Service
public class AudioExportService {

  private static final long CONVERSION_TIMEOUT_MINUTES = 15;

  private final MediaService mediaService;
  private final MediaProcessRunner processes;

  public AudioExportService(MediaService mediaService, MediaProcessRunner processes) {
    this.mediaService = mediaService;
    this.processes = processes;
  }

  public Path exportMp3(MediaFile mediaFile) {
    String inputPath = mediaService.readableSource(mediaFile.getFilePath());
    if (inputPath == null
        || inputPath.isBlank()
        || (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath)))) {
      throw new NoSuchElementException("视频源文件不存在");
    }

    Path outputPath = null;
    try {
      outputPath = Files.createTempFile("dovideo-audio-", ".mp3");
      processes.ffmpeg(
          List.of(
              "-y",
              "-i",
              inputPath,
              "-vn",
              "-acodec",
              "libmp3lame",
              "-q:a",
              "2",
              outputPath.toString()),
          null,
          Duration.ofMinutes(CONVERSION_TIMEOUT_MINUTES));
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
    }
  }
}
