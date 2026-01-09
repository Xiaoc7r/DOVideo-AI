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
// 【修正1】必须允许携带凭证，否则前端带 Cookie 访问会报错
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    @Autowired
    private AliyunAsrUtils aliyunAsrUtils;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    // =========================================================
    // 1. AI 总结接口 (你的逻辑 + 存入数据库)
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

            // 调用 DeepSeek
            String result = deepSeekUtils.analyzeContent("请对以下视频提取的文字进行总结，不需要废话，直接列出核心观点：\n" + text);

            // 【修正2】把结果存回数据库，这样刷新页面还在
            mediaFile.setAiSummary(result);
            mediaFileMapper.updateById(mediaFile);

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 流程炸了: " + e.getMessage();
        }
    }

    // =========================================================
    // 2. 纯文字提取接口 (你的逻辑 + 存入数据库)
    // =========================================================
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到 ID=" + id;

        String videoPath = mediaFile.getFilePath();
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) return "❌ 磁盘找不到文件";

        try {
            String outputMp3Path = videoFile.getParent() + File.separator + "temp_trans_" + UUID.randomUUID() + ".mp3";

            boolean success = extractAudio(videoPath, outputMp3Path);
            if (!success) return "❌ FFmpeg 转换失败";

            String text = aliyunAsrUtils.audioToText(outputMp3Path);

            new File(outputMp3Path).delete();

            // 【修正3】把文字结果存回数据库
            mediaFile.setTranscriptText(text);
            mediaFileMapper.updateById(mediaFile);

            return text;

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 提取失败: " + e.getMessage();
        }
    }

    // =========================================================
    // 3. 下载接口 (原封不动，只改了跨域支持)
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

    /**
     * 执行音频提取（防死锁终极版）
     * 核心改进：
     * 1. 使用 INHERIT 自动接管输出流，防止缓冲区填满导致的死锁。
     * 2. 增加超时机制，防止 bad case 导致线程永久挂起。
     */
    private boolean extractAudio(String inputPath, String outputPath) {
        // 定义进程对象，放在 try 外面以便 finally 里销毁
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y"); // 覆盖输出文件
            command.add("-i");
            command.add(inputPath);
            command.add("-vn"); // 去除视频流
            command.add("-acodec");
            command.add("libmp3lame"); // 使用 mp3 编码器
            command.add("-q:a");
            command.add("2"); // 这里的 2 是高质量 VBR
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);

            // 【关键点 1】合并 错误流 到 标准流
            // 这样我们要么看 stdout，要么看 stderr，不用管两个
            pb.redirectErrorStream(true);

            // 【关键点 2 - 解决死锁的核心】
            // Redirect.INHERIT 的意思是：让子进程(FFmpeg)的输出，直接借用父进程(Java)的控制台打印出来。
            // 这样数据就被“消费”掉了，不会堵塞在管道里，彻底根治死锁。
            // 而且你还能在黑窗口里看到 FFmpeg 的进度条，非常爽。
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            process = pb.start();

            // 【关键点 3 - 防止永久卡死】
            // 等待进程结束，但最多只等 10 分钟 (根据你的视频长度调整，10分钟足够处理几百兆的视频了)
            // waitFor 返回 true 代表进程在超时前正常结束了，false 代表超时了
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);

            if (finished) {
                // 进程正常结束，检查它的退出码
                // 0 代表成功，非 0 代表 FFmpeg 报错了（比如文件损坏）
                return process.exitValue() == 0;
            } else {
                // 超时了！说明视频可能太大，或者 FFmpeg 真的卡死了
                // 这种情况下，必须强制杀掉进程，否则它会变成僵尸进程占内存
                process.destroyForcibly();
                System.out.println("❌ 严重警告: FFmpeg 处理超时（超过10分钟），已强制终止");
                return false;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            // 【关键点 4 - 兜底清理】
            // 无论成功失败，确保进程对象被释放
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}