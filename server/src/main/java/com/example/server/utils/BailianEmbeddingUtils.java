package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（DashScope）文本向量化实现，由 {@code ai.embedding.provider=bailian} 启用。
 *
 * <p>走百炼的 OpenAI 兼容向量接口：
 * <pre>
 * POST {base}/embeddings   # base 默认 https://dashscope.aliyuncs.com/compatible-mode/v1
 * {"model": "text-embedding-v4", "input": "..."}
 * </pre>
 *
 * <p>与原 {@link EmbeddingUtils}（硅基流动 BAAI/bge-m3）保持同样的契约：
 * 失败直接抛出运行时异常，由调用方降级为关键词检索。
 */
@Component
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "bailian")
public class BailianEmbeddingUtils implements EmbeddingClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public BailianEmbeddingUtils(@Value("${ai.bailian.api-key:}") String apiKey,
                                 @Value("${ai.bailian.base-url:}") String baseUrl,
                                 @Value("${ai.bailian.embedding.model:text-embedding-v4}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ai.embedding.provider=bailian 需要配置 ALIYUN_API_KEY（百炼 API Key）");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    @Override
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("input", text);

            Request request = new Request.Builder()
                    .url(baseUrl + "/embeddings")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestJson.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException(
                            "Embedding API failed: HTTP " + response.code() + " " + truncate(body));
                }
                JSONObject json = JSON.parseObject(body);
                JSONArray data = json.getJSONArray("data");
                if (data == null || data.isEmpty()) throw new IllegalStateException("Embedding data is empty");
                JSONArray values = data.getJSONObject(0).getJSONArray("embedding");
                if (values == null || values.isEmpty()) throw new IllegalStateException("Embedding vector is empty");
                List<Double> embedding = new ArrayList<>(values.size());
                for (Object value : values) {
                    embedding.add(((Number) value).doubleValue());
                }
                return embedding;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 生成失败", e);
        }
    }

    private String truncate(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
