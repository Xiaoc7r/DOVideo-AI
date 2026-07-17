package com.example.server.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class VideoTranscriptionService {

    private final SegmentedTranscriptionService transcriptionService;

    public VideoTranscriptionService(SegmentedTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    private String processVideoToText(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) throw new IllegalArgumentException("视频路径为空");
        if (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath))) {
            throw new IllegalArgumentException("视频文件不存在");
        }

        return transcriptionService.transcribeToText(inputPath);
    }
}
