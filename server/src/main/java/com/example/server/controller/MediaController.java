package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.MediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/media")
@CrossOrigin(origins = "*") // 允许前端跨域访问，这一行很重要
public class MediaController {

    @Autowired
    private MediaService mediaService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    // 1. 上传接口 (保持不变)
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        try {
            return mediaService.convertVideoToAudio(file);
        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    // 2. [新增] 获取列表接口
    @GetMapping("/list")
    public List<MediaFile> getList() {
        // 直接查询数据库所有记录，Spring Boot 会自动把它变成 JSON 格式返回
        return mediaFileMapper.selectList(null);
    }
}