package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@Service
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private final MinioUtils minioUtils;
    private final YtDlpUtils ytDlpUtils;
    private final MediaService mediaService;

    public MediaIngestService(MinioUtils minioUtils,
                              YtDlpUtils ytDlpUtils,
                              MediaService mediaService) {
        this.minioUtils = minioUtils;
        this.ytDlpUtils = ytDlpUtils;
        this.mediaService = mediaService;
    }

    public MediaFile ingestFile(MultipartFile file, Long userId) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");

        String filename = mediaService.normalizeMediaFilename(file.getOriginalFilename());
        String md5 = mediaService.calculateMd5(file);
        String fileUrl = minioUtils.uploadFile(file);
        return mediaService.saveUploadedMedia(filename, fileUrl, userId, md5);
    }

    public MediaFile ingestUrl(String url, Long userId) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("视频链接不能为空");

        File tempFile = null;
        try {
            tempFile = ytDlpUtils.downloadVideo(url);
            String md5 = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);
            return mediaService.saveUploadedMedia("WEB_" + tempFile.getName(), fileUrl, userId, md5);
        } finally {
            // 这份文件只是搬运工，进了 MinIO 就别继续占着本地磁盘了。
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("temporary_video_cleanup_failed path={}", tempFile.getAbsolutePath());
            }
        }
    }
}
