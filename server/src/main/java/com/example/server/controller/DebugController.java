package com.example.server.controller;

import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.entity.MediaFile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/debug")
@CrossOrigin(origins = "*")
public class DebugController {

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    @Autowired
    private AliyunAsrUtils aliyunAsrUtils;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    // =========================================================
    // 1. AI 总结接口 (保持原样，只修改了提示词，让它更简洁)
    // =========================================================
    @GetMapping("/ai")
    public String testAi(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 数据库找不到 ID=" + id;

        String videoPath = mediaFile.getFilePath();
        if (videoPath == null || videoPath.isEmpty()) return "❌ 路径为空";

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) return "❌ 磁盘找不到文件: " + videoPath;

        try {
            // 逻辑：视频 -> 临时MP3 -> 文字 -> AI
            String outputMp3Path = videoFile.getParent() + File.separator + "temp_" + UUID.randomUUID() + ".mp3";

            boolean success = extractAudio(videoPath, outputMp3Path);
            if (!success) return "❌ FFmpeg 转换失败";

            String text = aliyunAsrUtils.audioToText(outputMp3Path);

            new File(outputMp3Path).delete(); // 用完即删

            if (text.startsWith("❌")) return text;

            // 修改提示词：只要正常的总结
            return deepSeekUtils.analyzeContent("请对以下视频提取的文字进行总结，不需要废话，直接列出核心观点：\n" + text);

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 流程炸了: " + e.getMessage();
        }
    }

    // =========================================================
    // 2. 纯文字提取接口 (逻辑克隆自 /ai，但不调 DeepSeek)
    // =========================================================
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        // 1. 找文件 (和 /ai 一模一样)
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到 ID=" + id;

        String videoPath = mediaFile.getFilePath();
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) return "❌ 磁盘找不到文件";

        try {
            // 2. 转临时 MP3 (和 /ai 一模一样)
            String outputMp3Path = videoFile.getParent() + File.separator + "temp_trans_" + UUID.randomUUID() + ".mp3";

            boolean success = extractAudio(videoPath, outputMp3Path);
            if (!success) return "❌ FFmpeg 转换失败";

            // 3. 听
            String text = aliyunAsrUtils.audioToText(outputMp3Path);

            // 4. 删
            new File(outputMp3Path).delete();

            // 5. 直接返回文字，不经过 AI
            return text;

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 提取失败: " + e.getMessage();
        }
    }

    // =========================================================
    // 3. 下载接口 (逻辑克隆自 /ai，最后返回流)
    // =========================================================
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return ResponseEntity.notFound().build();

        String videoPath = mediaFile.getFilePath();
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) return ResponseEntity.notFound().build();

        // 转成临时 MP3 供下载
        String outputMp3Path = videoFile.getParent() + File.separator + "download_" + UUID.randomUUID() + ".mp3";

        System.out.println("下载请求，正在转码: " + outputMp3Path);
        boolean success = extractAudio(videoPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        // 设置下载文件名
        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    // FFmpeg 工具 (原封不动)
    private boolean extractAudio(String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}