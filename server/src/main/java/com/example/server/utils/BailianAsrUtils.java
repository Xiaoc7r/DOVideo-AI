package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（DashScope）语音转写实现，由 {@code ai.asr.provider=bailian} 启用。
 *
 * <p>与硅基流动不同，百炼不提供 OpenAI 的 {@code /audio/transcriptions} 多段表单接口，
 * 而是把音频当作多模态消息投递给 {@code qwen3-asr-flash}：
 * <pre>
 * POST {base}/compatible-mode/v1/chat/completions
 * {
 *   "model": "qwen3-asr-flash",
 *   "messages": [{"role": "user", "content": [{"type": "input_audio",
 *                 "input_audio": {"data": "data:audio/mpeg;base64,..."}}]}],
 *   "stream": false,
 *   "asr_options": {"enable_itn": true}
 * }
 * </pre>
 *
 * <p>因此这里把音频读成内存再 Base64 编码为 data URI；单次请求的音频需控制在 10 MB 以内
 * （base64 会膨胀约 1/3，见 {@link #MAX_ENCODED_BYTES}）。上游按 60 秒切片，正常分片远小于该上限。
 *
 * <p>错误语义与原硅基流动实现保持一致：429/5xx 视为可重试，其余 4xx 属于请求本身不合法，
 * 直接抛不可重试异常，避免消费者把鉴权错误当成网络抖动反复重跑整条流水线。
 */
@Component
@ConditionalOnProperty(prefix = "ai.asr", name = "provider", havingValue = "bailian")
public class BailianAsrUtils implements AsrClient {

    private static final Logger log = LoggerFactory.getLogger(BailianAsrUtils.class);
    private static final int MAX_ATTEMPTS = 3;

    /** 百炼对 input_audio.data 的大小上限是 10 MB，这里按编码后的长度判断。 */
    private static final long MAX_ENCODED_BYTES = 10L * 1024 * 1024;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build();

    public BailianAsrUtils(@Value("${ai.bailian.api-key:}") String apiKey,
                           @Value("${ai.bailian.base-url:}") String baseUrl,
                           @Value("${ai.bailian.asr.model:qwen3-asr-flash}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ai.asr.provider=bailian 需要配置 ALIYUN_API_KEY（百炼 API Key）");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    @Override
    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.isFile()) throw new IllegalStateException("ASR audio file does not exist");

        String dataUri = toDataUri(file);
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String text = execute(dataUri);
                if (text == null || text.isBlank()) throw new IllegalStateException("ASR 返回空文本");
                return text.trim();
            } catch (IOException e) {
                lastError = e;
                log.warn("bailian_asr_attempt_failed attempt={} file={}", attempt + 1, file.getName(), e);
                if (attempt < MAX_ATTEMPTS - 1) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("ASR 调用失败，已达到最大重试次数", lastError);
    }

    private String toDataUri(File file) {
        try {
            long encodedLength = (file.length() + 2L) / 3L * 4L;
            if (encodedLength > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("音频分片超过百炼 10 MB 上限: "
                        + file.getName() + " (" + file.length() + " bytes)");
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            return "data:" + mediaType(file.getName()) + ";base64," + base64;
        } catch (IOException e) {
            throw new IllegalStateException("读取音频文件失败: " + file.getName(), e);
        }
    }

    private String mediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "audio/mpeg";
    }

    private String execute(String dataUri) throws IOException {
        JSONObject inputAudio = new JSONObject();
        inputAudio.put("data", dataUri);

        JSONObject content = new JSONObject();
        content.put("type", "input_audio");
        content.put("input_audio", inputAudio);

        JSONArray contentArray = new JSONArray();
        contentArray.add(content);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", contentArray);

        JSONArray messages = new JSONArray();
        messages.add(message);

        JSONObject asrOptions = new JSONObject();
        asrOptions.put("enable_itn", true);

        JSONObject requestJson = new JSONObject();
        requestJson.put("model", model);
        requestJson.put("messages", messages);
        requestJson.put("stream", false);
        requestJson.put("asr_options", asrOptions);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (response.code() == 429 || response.code() >= 500) {
                    throw new RetryableAsrException("ASR transient HTTP " + response.code() + ": " + body);
                }
                throw new IllegalArgumentException(
                        "ASR request rejected with HTTP " + response.code() + ": " + truncate(body));
            }
            return extractText(body);
        }
    }

    /** 兼容百炼可能返回的两种载荷：content 为 [{text}] 数组，或直接是字符串。 */
    private String extractText(String body) {
        JSONObject json = JSON.parseObject(body);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("ASR 响应缺少 choices: " + truncate(body));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) return null;
        Object content = message.get("content");
        if (content instanceof String text) return text;
        if (content instanceof JSONArray array) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (item instanceof JSONObject object && object.getString("text") != null) {
                    sb.append(object.getString("text"));
                } else if (item instanceof String text) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return null;
    }

    private String truncate(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
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
