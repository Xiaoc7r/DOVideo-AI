package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final MinioUtils minioUtils;
    private final ObjectMapper objectMapper;

    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";
    private static final String MEDIA_MD5_KEY_PREFIX = "media:md5:";
    private static final long MAX_CHUNK_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 410;
    private static final Set<String> VIDEO_SUFFIXES = Set.of(
            ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v");
    private static final Path CHUNK_UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"), "dovideo-chunks");

    public MediaService(MediaFileMapper mediaFileMapper,
                        StringRedisTemplate redisTemplate,
                        MinioUtils minioUtils,
                        ObjectMapper objectMapper) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.minioUtils = minioUtils;
        this.objectMapper = objectMapper;
    }

    public String initChunkedUpload(String filename, int totalChunks, Long userId) throws IOException {
        String normalizedFilename = normalizeVideoFilename(filename);
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("totalChunks must be between 1 and " + MAX_TOTAL_CHUNKS);
        }
        if (userId == null) {
            throw new SecurityException("missing authenticated user");
        }

        String uploadId = UUID.randomUUID().toString();
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("filename", normalizedFilename);
        metadata.put("totalChunks", String.valueOf(totalChunks));
        metadata.put("userId", String.valueOf(userId));
        redisTemplate.opsForHash().putAll(redisKey, metadata);
        redisTemplate.expire(redisKey, 1, TimeUnit.DAYS);
        Files.createDirectories(uploadDirectory(uploadId));
        return uploadId;
    }

    public Set<Integer> getUploadedChunks(String uploadId, Long userId) {
        requireUpload(uploadId, userId);
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        Set<Integer> result = new TreeSet<>();
        if (members != null) {
            for (String member : members) {
                result.add(Integer.parseInt(member));
            }
        }
        return result;
    }

    public void uploadChunk(String uploadId,
                            int chunkIndex,
                            int totalChunks,
                            MultipartFile chunk,
                            Long userId) throws IOException {
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("chunk is empty");
        }
        if (chunk.getSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk size cannot exceed 5MB");
        }

        Map<Object, Object> metadata = requireUpload(uploadId, userId);
        int expectedChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        if (totalChunks != expectedChunks || chunkIndex < 0 || chunkIndex >= expectedChunks) {
            throw new IllegalArgumentException("invalid chunk index or totalChunks");
        }

        Path directory = uploadDirectory(uploadId);
        Files.createDirectories(directory);
        chunk.transferTo(chunkPath(directory, chunkIndex));
        redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
        redisTemplate.expire(CHUNK_UPLOAD_KEY_PREFIX + uploadId, 1, TimeUnit.DAYS);
        redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
    }

    public MediaFile completeChunkedUpload(String uploadId, Long userId) throws Exception {
        Map<Object, Object> metadata = requireUpload(uploadId, userId);
        String filename = String.valueOf(metadata.get("filename"));
        int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        Path directory = uploadDirectory(uploadId);

        Set<Integer> uploadedChunks = getUploadedChunks(uploadId, userId);
        if (uploadedChunks.size() != totalChunks) {
            throw new IllegalStateException("not all chunks have been uploaded");
        }

        Path mergedFile = directory.resolve("merged" + fileSuffix(filename));
        MessageDigest digest = md5Digest();
        try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunkPath(directory, i);
                if (!Files.isRegularFile(part)) {
                    throw new IllegalStateException("missing chunk: " + i);
                }
                Files.copy(part, output);
            }
        }

        String fileUrl = minioUtils.uploadLocalFile(mergedFile.toFile(), filename);
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(filename);
        mediaFile.setFilePath(fileUrl);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setUserId(Long.valueOf(String.valueOf(metadata.get("userId"))));
        try {
            mediaFileMapper.insert(mediaFile);
        } catch (RuntimeException e) {
            removeUploadedObject(fileUrl, e);
            throw e;
        }

        rememberContentHash(mediaFile.getId(), HexFormat.of().formatHex(digest.digest()));
        invalidateUserList(mediaFile.getUserId());
        try {
            cleanupUpload(uploadId);
        } catch (Exception e) {
            log.warn("chunk_upload_cleanup_failed uploadId={} mediaId={}", uploadId, mediaFile.getId(), e);
        }
        return mediaFile;
    }

    public String calculateMd5(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateMd5(File file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return calculateMd5(inputStream);
        }
    }

    public void rememberContentHash(Long mediaId, String md5) {
        try {
            redisTemplate.opsForValue().set(MEDIA_MD5_KEY_PREFIX + mediaId, md5);
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_write_failed mediaId={}", mediaId, e);
        }
    }

    public MediaFile saveUploadedMedia(String filename, String fileUrl, Long userId, String md5) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(normalizeVideoFilename(filename));
        mediaFile.setFilePath(fileUrl);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setUserId(userId);
        try {
            mediaFileMapper.insert(mediaFile);
            rememberContentHash(mediaFile.getId(), md5);
            invalidateUserList(userId);
            return mediaFile;
        } catch (RuntimeException e) {
            removeUploadedObject(fileUrl, e);
            throw e;
        }
    }

    public List<MediaFile> listByUser(Long userId) {
        String cacheKey = userListKey(userId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<MediaFile>>() { });
            }
        } catch (Exception e) {
            log.warn("media_list_cache_read_failed userId={}", userId, e);
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(
                query.select("id", "filename", "status", "cover_url", "upload_time")
                        .eq("user_id", userId)
                        .orderByDesc("id"));
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(mediaFiles), 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("media_list_cache_write_failed userId={}", userId, e);
        }
        return mediaFiles;
    }

    public void deleteOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = requireOwnedMedia(mediaId, userId);
        mediaFileMapper.deleteById(mediaId);
        if (mediaFile.getFilePath() != null && mediaFile.getFilePath().startsWith("http")) {
            try {
                minioUtils.removeFile(mediaFile.getFilePath());
            } catch (RuntimeException e) {
                log.warn("media_object_cleanup_failed mediaId={} path={}",
                        mediaId, mediaFile.getFilePath(), e);
            }
        }
        try {
            redisTemplate.delete(MEDIA_MD5_KEY_PREFIX + mediaId);
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_delete_failed mediaId={}", mediaId, e);
        }
        invalidateUserList(userId);
    }

    public void invalidateUserList(Long userId) {
        if (userId == null) return;
        try {
            redisTemplate.delete(userListKey(userId));
        } catch (RuntimeException e) {
            log.warn("media_list_cache_invalidation_failed userId={}", userId, e);
        }
    }

    public String readableSource(String source) {
        return minioUtils.readableSource(source);
    }

    public String normalizeVideoFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("视频文件名不能为空");
        }
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("视频文件名无效或过长");
        }
        String suffix = fileSuffix(normalized).toLowerCase(java.util.Locale.ROOT);
        if (!VIDEO_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频");
        }
        return normalized;
    }

    public MediaFile requireOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new NoSuchElementException("文件不存在");
        if (!Objects.equals(mediaFile.getUserId(), userId)) {
            throw new SecurityException("无权访问该文件");
        }
        return mediaFile;
    }

    private String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private Map<Object, Object> requireUpload(String uploadId, Long userId) {
        uploadDirectory(uploadId);
        Map<Object, Object> metadata = redisTemplate.opsForHash()
                .entries(CHUNK_UPLOAD_KEY_PREFIX + uploadId);
        if (metadata.isEmpty()) {
            throw new IllegalArgumentException("uploadId does not exist or has expired");
        }
        if (!Objects.equals(String.valueOf(userId), String.valueOf(metadata.get("userId")))) {
            throw new SecurityException("无权访问该上传任务");
        }
        return metadata;
    }

    private Path uploadDirectory(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid uploadId");
        }
        return CHUNK_UPLOAD_DIR.resolve(uploadId);
    }

    private Path chunkPath(Path directory, int chunkIndex) {
        return directory.resolve("part-" + chunkIndex);
    }

    private String partsKey(String uploadId) {
        return CHUNK_UPLOAD_KEY_PREFIX + uploadId + ":parts";
    }

    private String userListKey(Long userId) {
        return "media:list:v2:user:" + userId;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void cleanupUpload(String uploadId) throws IOException {
        Path directory = uploadDirectory(uploadId);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to clean upload files", e);
                    }
                });
            }
        }
        redisTemplate.delete(List.of(CHUNK_UPLOAD_KEY_PREFIX + uploadId, partsKey(uploadId)));
    }

    private void removeUploadedObject(String fileUrl, RuntimeException originalError) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
            log.warn("uploaded_object_rollback_failed path={}", fileUrl, cleanupError);
        }
    }
}
