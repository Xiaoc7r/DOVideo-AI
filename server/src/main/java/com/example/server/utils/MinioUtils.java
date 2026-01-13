package com.example.server.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Component
public class MinioUtils {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    /**
     * 上传文件并返回访问 URL
     */
    public String uploadFile(MultipartFile file) throws Exception {
        // 1. 生成新文件名 (UUID防止重名)
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + suffix;

        // 2. 上传到 MinIO
        InputStream inputStream = file.getInputStream();
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(newFilename)
                        .stream(inputStream, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        // 3. 拼接返回 Public 访问地址
        return endpoint + "/" + bucketName + "/" + newFilename;
    }

    /**
     * 【新增】从 MinIO 删除文件
     * @param fileUrl 文件的完整 URL
     */
    public void removeFile(String fileUrl) {
        try {
            // 解析文件名
            String objectName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);

            // 调用 MinIO 删除
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );

            System.out.println(" MinIO 文件已删除: " + objectName);
        } catch (Exception e) {
            System.err.println(" MinIO 删除失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】上传本地 File 对象到 MinIO
     */
    public String uploadLocalFile(java.io.File file) throws Exception {
        java.io.FileInputStream inputStream = new java.io.FileInputStream(file);

        minioClient.putObject(
                io.minio.PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(file.getName()) // 文件名已经包含 UUID
                        .stream(inputStream, file.length(), -1)
                        .contentType("video/mp4") // 默认当 mp4 处理
                        .build()
        );
        inputStream.close();

        return endpoint + "/" + bucketName + "/" + file.getName();
    }
}