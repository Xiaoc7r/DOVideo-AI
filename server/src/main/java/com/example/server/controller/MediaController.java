package com.example.server.controller;

import com.example.server.dto.MediaSummary;
import com.example.server.entity.MediaFile;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
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

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/media")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final ChunkUploadService chunkUploadService;
    private final MediaIngestService mediaIngestService;
    private final MediaService mediaService;

    public MediaController(ChunkUploadService chunkUploadService,
                           MediaIngestService mediaIngestService,
                           MediaService mediaService) {
        this.chunkUploadService = chunkUploadService;
        this.mediaIngestService = mediaIngestService;
        this.mediaService = mediaService;
    }

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload(@RequestParam String filename,
                                             @RequestParam int totalChunks,
                                             @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        try {
            return ResponseEntity.ok(chunkUploadService.initialize(filename, totalChunks, userId));
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
        return ResponseEntity.ok(chunkUploadService.uploadedChunks(uploadId, userId));
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<String> uploadChunk(@RequestParam String uploadId,
                                              @RequestParam int chunkIndex,
                                              @RequestParam int totalChunks,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        try {
            chunkUploadService.uploadChunk(uploadId, chunkIndex, totalChunks, file, userId);
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
            chunkUploadService.complete(uploadId, userId);
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
        try {
            mediaIngestService.ingestFile(file, userId);
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
        try {
            mediaIngestService.ingestUrl(url, userId);
            return ResponseEntity.ok("Upload success");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("url_upload_failed userId={}", userId, e);
            return ResponseEntity.internalServerError().body("Upload failed");
        }
    }

    @GetMapping("/list")
    public List<MediaSummary> getList(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return mediaService.listByUser(userId).stream().map(MediaSummary::from).toList();
    }

    @GetMapping("/playback")
    public ResponseEntity<String> playback(@RequestParam Long id,
                                           @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return ResponseEntity.ok(mediaService.readableSource(mediaFile.getFilePath()));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> delete(@RequestParam("id") Long id,
                                         @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.deleteOwnedMedia(id, userId);
        return ResponseEntity.ok("删除成功");
    }
}
