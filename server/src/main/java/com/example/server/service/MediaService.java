package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MediaService {

    //注入数据库操作接口 (MyBatis-Plus 自动代理)
    @Autowired
    private MediaFileMapper mediaFileMapper;

    private final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";

    public MediaService() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public String convertVideoToAudio(MultipartFile file) throws IOException, InterruptedException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(file.getOriginalFilename());
        mediaFile.setStatus("PROCESSING"); //状态：处理中
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setFilePath(""); //暂时为空

        //这一步执行后，MySQL 里就会多一行数据
        mediaFileMapper.insert(mediaFile);

        // --- 下面是原有的文件处理逻辑 ---
        String fileId = UUID.randomUUID().toString();
        String inputPath = UPLOAD_DIR + fileId + "_input.mp4";
        String outputPath = UPLOAD_DIR + fileId + "_output.mp3";

        File inputFile = new File(inputPath);
        file.transferTo(inputFile);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");
        command.add("-acodec");
        command.add("libmp3lame");
        command.add("-q:a");
        command.add("2");
        command.add(new File(outputPath).getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (process.waitFor() == 0) {
            inputFile.delete(); // 删掉原视频

            // --- 数据库操作：更新状态为完成 ---
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFilePath(outputPath);
            mediaFileMapper.updateById(mediaFile); // 根据 ID 更新这一行

            return outputPath;
        } else {
            // --- 数据库操作：记录失败 ---
            mediaFile.setStatus("FAILED");
            mediaFileMapper.updateById(mediaFile);
            throw new RuntimeException("FFmpeg 转换失败");
        }
    }
}