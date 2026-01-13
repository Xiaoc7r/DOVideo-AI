package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class AliyunAsrUtils {

    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return "❌ 错误：找不到文件";

        String url = "https://api.siliconflow.cn/v1/audio/transcriptions";
        int maxRetries = 3; // 最大重试次数
        String lastError = "";

        for (int i = 0; i < maxRetries; i++) {
            try {
                System.out.println("🎤 [ASR] 上传中 (第 " + (i + 1) + " 次尝试)...");

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(),
                                RequestBody.create(file, MediaType.parse("application/octet-stream")))
                        // 【核心修改】换成电信的大模型，更稳，准确率更高
                        .addFormDataPart("model", "TeleAI/TeleSpeechASR")
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String resultJson = response.body().string();
                        JSONObject jsonObject = JSON.parseObject(resultJson);
                        if (jsonObject.containsKey("text")) {
                            return jsonObject.getString("text");
                        }
                    } else {
                        // 如果是 500 错误，记录并重试
                        String errBody = response.body() != null ? response.body().string() : "";
                        lastError = "HTTP " + response.code() + ": " + errBody;
                        System.err.println("⚠️ ASR 失败 (" + (i + 1) + "/" + maxRetries + "): " + lastError);

                        // 遇到 500/502/503 等服务端错误，等待 2 秒再重试
                        if (response.code() >= 500) {
                            Thread.sleep(2000);
                            continue;
                        } else {
                            // 如果是 400/401 等客户端错误，直接退出不重试
                            return "❌ 识别失败: " + lastError;
                        }
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                System.err.println("⚠️ 网络异常 (" + (i + 1) + "/" + maxRetries + "): " + lastError);
            }
        }

        return "❌ 最终失败 (重试3次): " + lastError;
    }
}