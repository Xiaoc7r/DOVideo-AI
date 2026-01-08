package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekUtils {

    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    @Value("${ai.deepseek.base-url}")
    private String baseUrl;

    // 配置 HTTP 客户端，超时时间设置长一点，因为 AI 思考需要时间
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * 真·AI 深度思考
     */
    public String analyzeContent(String content) {
        // 1. 准备请求地址
        String url = baseUrl + "/chat/completions";

        // 2. 准备系统提示词 (这里就是你抄 AI-Media2Doc 的地方)
        String systemPrompt = """
            你是一个专业的视频内容分析专家。
            请对用户输入的内容进行深度总结。
            要求：
            1. 使用 Markdown 格式。
            2. 提炼 3-5 个核心观点。
            3. 语气专业、干练，不要废话。
            """;

        // 3. 组装 JSON 参数
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B"); // 或者是 deepseek-ai/DeepSeek-R1 (看你账号支持哪个)
        jsonBody.put("stream", false);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));
        messages.add(JSONObject.of("role", "user", "content", content));
        jsonBody.put("messages", messages);

        // 4. 发送请求
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // 如果报错（比如没余额），这里会把错误原因返回去
                return "❌ AI 请求失败: " + response.code() + " - " + response.body().string();
            }

            // 5. 解析 AI 返回的 JSON
            String resultJson = response.body().string();
            JSONObject jsonObject = JSON.parseObject(resultJson);

            // 提取真正的回答内容
            return jsonObject.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (IOException e) {
            e.printStackTrace();
            return "❌ 网络连接出错: " + e.getMessage();
        }
    }
}