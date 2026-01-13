package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;


    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "userId", required = false) Long userId) {
        try {
            System.out.println("正在上传到 MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO 上传成功，地址: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("缓存已清除: " + cacheKey);
            }

            return "上传成功";

        } catch (Exception e) {
            e.printStackTrace();
            return "上传失败: " + e.getMessage();
        }
    }

    // === 视频链接上传接口 (修复版：状态码控制) ===
    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     @RequestParam(value = "userId", required = false) Long userId) {
        File tempFile = null;
        try {
            System.out.println("🔗 收到链接上传请求: " + url);

            // 1. 调用 yt-dlp 下载 (后面我们会改 Utils 让他下最低画质)
            tempFile = ytDlpUtils.downloadVideo(url);

            // 2. 上传到 MinIO
            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            // 3. 存数据库
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            // 4. 清缓存
            if (userId != null) {
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("缓存已清除: " + cacheKey);
            }

            // 【关键】成功返回 200
            return org.springframework.http.ResponseEntity.ok("上传成功");

        } catch (Exception e) {
            e.printStackTrace();
            // 【关键】失败返回 500，前端 fetch 会抛出异常
            return org.springframework.http.ResponseEntity.status(500).body("上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    //列表接口
    @GetMapping("/list")
    public List<MediaFile> getList(@RequestParam(value = "userId", required = false) Long userId) {
        String cacheKey = "media:list:user:" + (userId == null ? "anon" : userId);

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        if (userId != null) {
            query.eq("user_id", userId);
        } else {
            return List.of();
        }
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    //删除接口
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return "删除成功";
    }
}