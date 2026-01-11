package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService; // 【新增】导入异步服务
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    // 【新增】注入异步业务服务 (线程池入口)
    @Autowired
    private AiService aiService;

    // === 1. AI 总结接口 (升级为：异步非阻塞模式) ===
    @GetMapping("/ai")
    public String testAi(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到文件记录";

        // 【核心修改】不再直接调用 Strategy 阻塞等待，而是丢给 Service 里的线程池
        aiService.asyncAnalyze(id);

        System.out.println("🚀 主线程已释放，任务交给线程池处理");

        // 立刻返回给前端，不需要等几十秒了
        return "✅ 任务已后台运行！请稍后刷新列表查看结果。";
    }

    // === 2. 纯文字提取接口 异步===
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到文件记录";

        // 【修改】调用异步服务
        aiService.asyncTranscribe(id);

        return "✅ 提取任务已后台运行！请稍后查看结果。";
    }

    // === 3. 下载音频接口 (兼容 MinIO，保持原样) ===
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return ResponseEntity.notFound().build();

        String inputPath = mediaFile.getFilePath(); // 可能是 http URL

        // 检查：如果是本地文件且不存在
        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        // 临时输出路径
        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";

        System.out.println("⬇️ 下载请求，正在从源地址转码音频: " + inputPath);

        // 复用 FFmpeg 逻辑
        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

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

    // 临时的 FFmpeg 工具方法 (专门给下载用)
    private boolean runFfmpeg(String inputPath, String outputPath) {
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
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            return process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}