package com.example.server.utils;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.UUID;

@Component
public class MinioUtils {

    private static final Logger log = LoggerFactory.getLogger(MinioUtils.class);

    private final MinioClient minioClient;
    private final String bucketName;
    private final String endpoint;

    public MinioUtils(MinioClient minioClient,
                      @Value("${minio.bucketName}") String bucketName,
                      @Value("${minio.endpoint}") String endpoint) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.endpoint = endpoint.replaceAll("/+$", "");
    }

    public String uploadFile(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file is empty");
        String objectName = UUID.randomUUID() + fileSuffix(file.getOriginalFilename());
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType();
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        }
        return objectUrl(objectName);
    }

    public String uploadLocalFile(File file) throws Exception {
        return uploadLocalFile(file, file.getName());
    }

    public String uploadLocalFile(File file, String originalFilename) throws Exception {
        return uploadLocalFile(file, originalFilename, "");
    }

    public String uploadLocalFile(File file, String originalFilename, String objectPrefix) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("local file does not exist");
        String normalizedPrefix = objectPrefix == null || objectPrefix.isBlank()
                ? ""
                : objectPrefix.replaceAll("^/+|/+$", "") + "/";
        String objectName = normalizedPrefix + UUID.randomUUID() + fileSuffix(originalFilename);
        validateObjectName(objectName);
        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) contentType = "application/octet-stream";
        try (FileInputStream inputStream = new FileInputStream(file)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, file.length(), -1)
                    .contentType(contentType)
                    .build());
        }
        return objectUrl(objectName);
    }

    public String uploadObject(String objectName,
                               InputStream inputStream,
                               long size,
                               String contentType) throws Exception {
        validateObjectName(objectName);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(inputStream, size, -1)
                .contentType(contentType)
                .build());
        return objectUrl(objectName);
    }

    public void copyObjectTo(String objectName, OutputStream outputStream) {
        validateObjectName(objectName);
        try (GetObjectResponse inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build())) {
            inputStream.transferTo(outputStream);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件读取失败", e);
        }
    }

    public void removeFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return;
        if (!isManagedFile(fileUrl)) return;
        removeObject(objectName(fileUrl));
    }

    public void removeObject(String objectName) {
        validateObjectName(objectName);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            log.info("minio_object_deleted object={}", objectName);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件删除失败", e);
        }
    }

    public boolean isManagedFile(String fileUrl) {
        return fileUrl != null && fileUrl.startsWith(endpoint + "/" + bucketName + "/");
    }

    public boolean isManagedFile(String fileUrl, String objectPrefix) {
        if (!isManagedFile(fileUrl)) return false;
        try {
            return objectName(fileUrl).startsWith(objectPrefix + "/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String readableSource(String source) {
        if (source == null || !source.startsWith(endpoint + "/" + bucketName + "/")) return source;
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName(source))
                    .expiry(60 * 60)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 预签名地址生成失败", e);
        }
    }

    public String objectUrl(String objectName) {
        validateObjectName(objectName);
        return endpoint + "/" + bucketName + "/" + objectName;
    }

    private String objectName(String fileUrl) {
        String path = URI.create(fileUrl).getPath();
        String bucketPrefix = "/" + bucketName + "/";
        int start = path.indexOf(bucketPrefix);
        if (start < 0) throw new IllegalArgumentException("invalid MinIO object URL");
        String objectName = path.substring(start + bucketPrefix.length());
        validateObjectName(objectName);
        return objectName;
    }

    private void validateObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()
                || objectName.startsWith("/") || objectName.contains("..")) {
            throw new IllegalArgumentException("invalid MinIO object name");
        }
    }

    private String fileSuffix(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || filename.length() - dot > 11) return "";
        String suffix = filename.substring(dot).toLowerCase();
        return suffix.matches("\\.[a-z0-9]+") ? suffix : "";
    }
}
