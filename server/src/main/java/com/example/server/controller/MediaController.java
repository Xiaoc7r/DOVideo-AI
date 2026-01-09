package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    // 【新增】注入 Redis 工具
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 【新增】注入 JSON 转换工具 (Spring Boot 自带，不用额外配)
    @Autowired
    private ObjectMapper objectMapper;

    // 上传路径
    private static final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";

    // === 1. 上传接口 (修改：上传后清除缓存) ===
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "userId", required = false) Long userId) throws IOException {

        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();

        String originalFilename = file.getOriginalFilename();
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String newFilename = uuid + "_" + originalFilename;
        File saveFile = new File(dir, newFilename);

        file.transferTo(saveFile);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(originalFilename);
        mediaFile.setFilePath(saveFile.getAbsolutePath());
        mediaFile.setStatus("COMPLETED");

        if (userId != null) {
            mediaFile.setUserId(userId);
            System.out.println("用户 [" + userId + "] 上传了文件: " + originalFilename);
        } else {
            System.out.println("匿名用户上传了文件: " + originalFilename);
        }

        mediaFileMapper.insert(mediaFile);

        // 【Redis 关键点】数据变了，要把旧缓存删掉，防止用户看到老数据
        // key 的格式要和下面 getList 保持一致
        if (userId != null) {
            String cacheKey = "media:list:user:" + userId;
            redisTemplate.delete(cacheKey);
            System.out.println("🔥 缓存已清除: " + cacheKey);
        }

        return "上传成功";
    }

    // === 2. 列表接口 (修改：加入 Redis 缓存查询) ===
    @GetMapping("/list")
    public List<MediaFile> getList(@RequestParam(value = "userId", required = false) Long userId) {
        // 1. 定义缓存的 Key (比如: media:list:user:1)
        String cacheKey = "media:list:user:" + (userId == null ? "anon" : userId);

        try {
            // 2. 先去 Redis 查查有没有
            String json = redisTemplate.opsForValue().get(cacheKey);

            if (json != null) {
                // 3. 如果有，直接把 JSON 转回 List 返回 (不查数据库了，极速！)
                System.out.println("🚀 命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            // 缓存如果报错（比如 Redis 挂了），不要影响主业务，打印个日志就行
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        // 4. 如果 Redis 没有，老老实实去查数据库
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        if (userId != null) {
            query.eq("user_id", userId);
        } else {
            return List.of();
        }
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        // 5. 查完数据库，顺手存入 Redis (设置 30 分钟过期)
        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("💾 已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    // === 3. 删除接口 (修改：删除后清除缓存) ===
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        try {
            File file = new File(media.getFilePath());
            if (file.exists()) file.delete();
        } catch (Exception e) {
            System.out.println("硬盘文件删除失败: " + e.getMessage());
        }

        mediaFileMapper.deleteById(id);

        // 【Redis 关键点】删了数据，也要删缓存
        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("🔥 缓存已清除: " + cacheKey);
        }

        return "删除成功";
    }
}