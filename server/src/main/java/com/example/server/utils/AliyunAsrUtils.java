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

    // 复用你的 DeepSeek Key (因为它们是一家公司的服务)
    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "❌ 错误：找不到文件 -> " + filePath;
        }

        // 1. 准备请求地址 (硅基流动官方语音识别接口)
        String url = "https://api.siliconflow.cn/v1/audio/transcriptions";

        // 2. 构造请求体 (上传文件 + 指定模型)
        // 使用 SenseVoiceSmall 模型，速度快，效果好
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", "FunAudioLLM/SenseVoiceSmall")
                //FunAudioLLM/SenseVoiceSmall
                .build();

        // 3. 构造请求
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey) // 用的是 DeepSeek 的 Key
                .post(requestBody)
                .build();

        // 4. 发送并解析
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "❌ 语音识别失败: " + response.code() + " - " + response.message();
            }

            String resultJson = response.body().string();
            JSONObject jsonObject = JSON.parseObject(resultJson);

            // 提取文字
            if (jsonObject.containsKey("text")) {
                return jsonObject.getString("text");
            } else {
                return "识别结果为空: " + resultJson;
            }

        } catch (IOException e) {
            e.printStackTrace();
            return "❌ 网络请求出错: " + e.getMessage();
        }
    }
}