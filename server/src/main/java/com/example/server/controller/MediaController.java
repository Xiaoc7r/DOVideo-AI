package com.example.server.controller;

import com.example.server.dto.MediaSummary;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/media")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MinioUtils minioUtils;
    private final YtDlpUtils ytDlpUtils;
    private final MediaService mediaService;

    public MediaController(MinioUtils minioUtils, YtDlpUtils ytDlpUtils, MediaService mediaService) {
        this.minioUtils = minioUtils;
        this.ytDlpUtils = ytDlpUtils;
        this.mediaService = mediaService;
    }

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload(@RequestParam String filename,
                                             @RequestParam int totalChunks,
                                             @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        try {
            return ResponseEntity.ok(mediaService.initChunkedUpload(filename, totalChunks, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("chunk_upload_init_failed userId={}", userId, e);
            return ResponseEntity.internalServerError().body("Failed to initialize upload");
        }
    }

    @GetMapping("/upload-status")
    public ResponseEntity<Set<Integer>> uploadStatus(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return ResponseEntity.ok(mediaService.getUploadedChunks(uploadId, userId));
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<String> uploadChunk(@RequestParam String uploadId,
                                              @RequestParam int chunkIndex,
                                              @RequestParam int totalChunks,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        try {
            mediaService.uploadChunk(uploadId, chunkIndex, totalChunks, file, userId);
            return ResponseEntity.ok("Chunk uploaded");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("chunk_upload_failed uploadId={} chunkIndex={}", uploadId, chunkIndex, e);
            return ResponseEntity.internalServerError().body("Chunk upload failed");
        }
    }

    @PostMapping("/complete-upload")
    public ResponseEntity<String> completeUpload(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        try {
            mediaService.completeChunkedUpload(uploadId, userId);
            return ResponseEntity.ok("Upload success");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("chunk_merge_failed uploadId={}", uploadId, e);
            return ResponseEntity.internalServerError().body("Upload merge failed");
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
                                         @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        try {
            String filename = mediaService.normalizeVideoFilename(file.getOriginalFilename());
            String md5 = mediaService.calculateMd5(file);
            String fileUrl = minioUtils.uploadFile(file);
            mediaService.saveUploadedMedia(filename, fileUrl, userId, md5);
            return ResponseEntity.ok("Upload success");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("media_upload_failed userId={} filename={}", userId, file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body("Upload failed");
        }
    }

    @PostMapping("/upload-url")
    public ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("Upload failed: url is empty");
        }

        File tempFile = null;
        try {
            tempFile = ytDlpUtils.downloadVideo(url);
            String md5 = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);
            mediaService.saveUploadedMedia("WEB_" + tempFile.getName(), fileUrl, userId, md5);
            return ResponseEntity.ok("Upload success");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("url_upload_failed userId={}", userId, e);
            return ResponseEntity.internalServerError().body("Upload failed");
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("temporary_video_cleanup_failed path={}", tempFile.getAbsolutePath());
            }
        }
    }

    @GetMapping("/list")
    public List<MediaSummary> getList(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return mediaService.listByUser(userId).stream().map(MediaSummary::from).toList();
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> delete(@RequestParam("id") Long id,
                                         @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.deleteOwnedMedia(id, userId);
        return ResponseEntity.ok("删除成功");
    }
}
