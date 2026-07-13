package com.example.server.controller;

import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.service.AudioExportService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.service.TranscriptionTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/analysis")
public class MediaProcessingController {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessingController.class);

    private final AudioExportService audioExportService;
    private final MediaService mediaService;
    private final TranscriptionTaskService transcriptionTaskService;
    private final TaskEventService taskEventService;

    public MediaProcessingController(AudioExportService audioExportService,
                                     MediaService mediaService,
                                     TranscriptionTaskService transcriptionTaskService,
                                     TaskEventService taskEventService) {
        this.audioExportService = audioExportService;
        this.mediaService = mediaService;
        this.transcriptionTaskService = transcriptionTaskService;
        this.taskEventService = taskEventService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<String> transcribe(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        if (!transcriptionTaskService.queue(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("文字提取任务正在处理中");
        }
        try {
            transcriptionTaskService.transcribe(id);
        } catch (RuntimeException e) {
            transcriptionTaskService.rejectQueued(id);
            log.warn("transcription_dispatch_rejected mediaId={} userId={}", id, userId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("任务队列已满，请稍后重试");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("提取任务已提交");
    }

    @GetMapping("/transcription-status")
    public TaskStatus transcriptionStatus(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return transcriptionTaskService.status(mediaFile);
    }

    @GetMapping(value = "/transcription-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter transcriptionEvents(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return taskEventService.subscribe(
                id,
                TaskEventService.TRANSCRIPTION,
                "",
                transcriptionTaskService.status(mediaFile),
                "TRANSCRIPTION");
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        Path outputPath = audioExportService.exportMp3(mediaFile);

        StreamingResponseBody body = output -> {
            try {
                Files.copy(outputPath, output);
            } finally {
                Files.deleteIfExists(outputPath);
            }
        };
        String filename = mediaFile.getFilename() == null
                ? "audio.mp3"
                : mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(body);
    }
}
