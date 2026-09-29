package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);
    private static final String UPLOAD_KEY_PREFIX = "upload:chunked:";
    private static final long MAX_CHUNK_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 410;
    private static final String CHUNK_OBJECT_PREFIX = "chunk-uploads/";

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MinioUtils minioUtils;
    private final MediaService mediaService;

    public ChunkUploadService(StringRedisTemplate redisTemplate,
                              RedissonClient redissonClient,
                              MinioUtils minioUtils,
                              MediaService mediaService) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.minioUtils = minioUtils;
        this.mediaService = mediaService;
    }

    public String initialize(String filename, int totalChunks, Long userId) throws IOException {
        String normalizedFilename = mediaService.normalizeVideoFilename(filename);
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("totalChunks must be between 1 and " + MAX_TOTAL_CHUNKS);
        }
        if (userId == null) throw new SecurityException("missing authenticated user");

        String uploadId = UUID.randomUUID().toString();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("filename", normalizedFilename);
        metadata.put("totalChunks", String.valueOf(totalChunks));
        metadata.put("userId", String.valueOf(userId));
        redisTemplate.opsForHash().putAll(uploadKey(uploadId), metadata);
        redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
        return uploadId;
    }

    public Set<Integer> uploadedChunks(String uploadId, Long userId) {
        requireUpload(uploadId, userId);
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        Set<Integer> result = new TreeSet<>();
        if (members != null) {
            for (String member : members) result.add(Integer.parseInt(member));
        }
        return result;
    }

    public void uploadChunk(String uploadId,
                            int chunkIndex,
                            int totalChunks,
                            MultipartFile chunk,
                            Long userId) throws Exception {
        if (chunk == null || chunk.isEmpty()) throw new IllegalArgumentException("chunk is empty");
        if (chunk.getSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk size cannot exceed 5MB");
        }

        Map<Object, Object> metadata = requireUpload(uploadId, userId);
        int expectedChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        if (totalChunks != expectedChunks || chunkIndex < 0 || chunkIndex >= expectedChunks) {
            throw new IllegalArgumentException("invalid chunk index or totalChunks");
        }
        // A response can be lost after merging. Late chunk retries must not recreate
        // temporary objects for an upload that already has its final media record.
        if (completedUpload(uploadId, userId) != null) return;

        try (InputStream inputStream = chunk.getInputStream()) {
            minioUtils.uploadObject(
                    chunkObjectName(uploadId, chunkIndex),
                    inputStream,
                    chunk.getSize(),
                    "application/octet-stream");
        }
        redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
        redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
        redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
    }

    public MediaFile complete(String uploadId, Long userId) throws Exception {
        validateUploadId(uploadId);
        RLock mergeLock = redissonClient.getLock("lock:upload:merge:" + uploadId);
        // 这两种情况是客户端可重试的“状态冲突”，不是服务端故障：用 BusinessException 标成 409，
        // 避免和本类中真正的内部错误（如 md5Digest 的 IllegalStateException）混在一起被兜底成 500。
        if (!mergeLock.tryLock()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该上传任务正在合并中，请稍后重试");
        }

        try {
            MediaFile completed = completedUpload(uploadId, userId);
            if (completed != null) return completed;

            Map<Object, Object> metadata = requireUpload(uploadId, userId);
            String filename = String.valueOf(metadata.get("filename"));
            int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
            Set<Integer> uploadedChunks = uploadedChunks(uploadId, userId);
            if (uploadedChunks.size() != totalChunks) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "分片尚未全部上传完成（已传 " + uploadedChunks.size() + "/" + totalChunks + "）");
            }

            Path mergedFile = Files.createTempFile("dovideo-merged-", fileSuffix(filename));
            MessageDigest digest = md5Digest();
            try {
                try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
                     DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
                     BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
                    for (int i = 0; i < totalChunks; i++) {
                        minioUtils.copyObjectTo(chunkObjectName(uploadId, i), output);
                    }
                }

                String fileUrl = minioUtils.uploadLocalFile(mergedFile.toFile(), filename);
                MediaFile mediaFile = mediaService.saveUploadedMedia(
                        filename, fileUrl, userId, HexFormat.of().formatHex(digest.digest()));
                // 先记成功再清现场。清理失败或客户端重试，都不会再插一条媒体记录。
                // The receipt must outlive the resumable metadata, including the
                // small time gap between these Redis calls near expiry.
                redisTemplate.opsForValue().set(
                        completedKey(uploadId), String.valueOf(mediaFile.getId()), 25, TimeUnit.HOURS);
                redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
                redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
                cleanup(uploadId, totalChunks, mediaFile.getId());
                return mediaFile;
            } finally {
                Files.deleteIfExists(mergedFile);
            }
        } finally {
            if (mergeLock.isHeldByCurrentThread()) mergeLock.unlock();
        }
    }

    private MediaFile completedUpload(String uploadId, Long userId) {
        String mediaId = redisTemplate.opsForValue().get(completedKey(uploadId));
        if (mediaId == null) return null;
        try {
            return mediaService.requireOwnedMedia(Long.valueOf(mediaId), userId);
        } catch (NumberFormatException e) {
            redisTemplate.delete(completedKey(uploadId));
            return null;
        }
    }

    private Map<Object, Object> requireUpload(String uploadId, Long userId) {
        validateUploadId(uploadId);
        Map<Object, Object> metadata = redisTemplate.opsForHash().entries(uploadKey(uploadId));
        if (metadata.isEmpty()) {
            throw new IllegalArgumentException("uploadId does not exist or has expired");
        }
        if (!Objects.equals(String.valueOf(userId), String.valueOf(metadata.get("userId")))) {
            throw new SecurityException("无权访问该上传任务");
        }
        return metadata;
    }

    private void validateUploadId(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid uploadId");
        }
    }

    private void cleanup(String uploadId, int totalChunks, Long mediaId) {
        for (int i = 0; i < totalChunks; i++) {
            try {
                minioUtils.removeObject(chunkObjectName(uploadId, i));
            } catch (RuntimeException e) {
                log.warn("chunk_object_cleanup_failed uploadId={} chunkIndex={} mediaId={}",
                        uploadId, i, mediaId, e);
            }
        }
        // Keep the small ownership metadata and part indexes until their TTL expires.
        // A client that missed the successful response checks /upload-status first;
        // deleting them would make it start over and insert a duplicate media record.
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private String uploadKey(String uploadId) {
        return UPLOAD_KEY_PREFIX + uploadId;
    }

    private String partsKey(String uploadId) {
        return uploadKey(uploadId) + ":parts";
    }

    private String completedKey(String uploadId) {
        return uploadKey(uploadId) + ":completed";
    }

    private String chunkObjectName(String uploadId, int chunkIndex) {
        validateUploadId(uploadId);
        return CHUNK_OBJECT_PREFIX + uploadId + "/part-" + chunkIndex;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
