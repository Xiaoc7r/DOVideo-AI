package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.VideoChunk;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final AtomicBoolean collectionReady = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public QdrantVectorStore(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                             @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${vector.qdrant.collection:video_chunks}") String collection) {
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.collection = collection;
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled) return;
        List<VideoChunk> vectorized = chunks.stream().filter(chunk -> !chunk.embedding().isEmpty()).toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            JSONArray points = new JSONArray();
            for (VideoChunk chunk : vectorized) {
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", chunk.startMs());
                payload.put("endMs", chunk.endMs());

                JSONObject point = new JSONObject();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", chunk.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));

            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new VectorHit(
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 语义检索失败", e);
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        try {
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));
            JSONObject body = new JSONObject();
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            log.warn("qdrant_media_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady.get()) return;
        synchronized (collectionReady) {
            if (collectionReady.get()) return;
            Request.Builder lookup = request(baseUrl + "/collections/" + collection).get();
            try (Response response = client.newCall(lookup.build()).execute()) {
                if (response.isSuccessful()) {
                    collectionReady.set(true);
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Qdrant collection lookup failed: " + response.code());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant collection lookup failed", e);
            }

            JSONObject vectors = new JSONObject();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            JSONObject body = new JSONObject();
            body.put("vectors", vectors);
            execute(request(baseUrl + "/collections/" + collection)
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            collectionReady.set(true);
        }
    }

    private String execute(Request.Builder request) {
        try (Response response = client.newCall(withApiKey(request).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant API failed: " + response.code() + " " + body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request failed", e);
        }
    }

    private Request.Builder request(String url) {
        return withApiKey(new Request.Builder().url(url));
    }

    private Request.Builder withApiKey(Request.Builder request) {
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private String pointId(Long mediaId, VideoChunk chunk) {
        String source = mediaId + ":" + chunk.startMs() + ":" + chunk.endMs();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record VectorHit(long startMs, long endMs, double score) {
    }
}
