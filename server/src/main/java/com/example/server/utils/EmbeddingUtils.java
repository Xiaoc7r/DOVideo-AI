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
 * 硅基流动文本向量化实现，由 {@code ai.embedding.provider=siliconflow} 启用（默认）。
 *
 * <p>复用 {@code ai.deepseek.*} 这一组 OpenAI 兼容的 key/base-url 配置（项目历史命名），
 * 模型默认 {@code BAAI/bge-m3}。逻辑未改动，只是实现 {@link EmbeddingClient} 接口，
 * 以便与 {@link BailianEmbeddingUtils} 通过配置切换。
 */
@Component
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "siliconflow", matchIfMissing = true)
public class EmbeddingUtils implements EmbeddingClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public EmbeddingUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.deepseek.base-url}") String baseUrl,
                          @Value("${ai.embedding.model:BAAI/bge-m3}") String model) {
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
                    .post(RequestBody.create(
                            requestJson.toString(),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("Embedding API failed: " + response.code());
                }
                JSONObject json = JSON.parseObject(response.body().string());
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
}
