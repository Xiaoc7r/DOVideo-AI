package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class AliyunAsrUtils {

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrUtils.class);
    private static final int MAX_ATTEMPTS = 3;

    private final String apiKey;
    private final String transcriptionUrl;
    private final String model;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build();

    public AliyunAsrUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.asr.url}") String transcriptionUrl,
                          @Value("${ai.asr.model}") String model) {
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        this.model = model;
    }

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.isFile()) throw new IllegalArgumentException("ASR audio file does not exist");

        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String text = execute(file);
                if (text == null || text.isBlank()) throw new IllegalStateException("ASR 返回空文本");
                return text.trim();
            } catch (RetryableAsrException | IOException e) {
                lastError = e;
                log.warn("asr_attempt_failed attempt={} file={}", attempt + 1, file.getName(), e);
                if (attempt < MAX_ATTEMPTS - 1) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("ASR 调用失败，已达到最大重试次数", lastError);
    }

    private String execute(File file) throws IOException {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", model)
                .build();
        Request request = new Request.Builder()
                .url(transcriptionUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.isSuccessful()) {
                JSONObject json = JSON.parseObject(body);
                return json.getString("text");
            }
            if (response.code() == 429 || response.code() >= 500) {
                throw new RetryableAsrException("ASR transient HTTP " + response.code());
            }
            throw new IllegalStateException("ASR request rejected with HTTP " + response.code());
        }
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_000L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR retry interrupted", e);
        }
    }

    private static class RetryableAsrException extends IOException {
        private RetryableAsrException(String message) {
            super(message);
        }
    }
}
