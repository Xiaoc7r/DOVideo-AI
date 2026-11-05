package com.example.server.utils;

import java.util.List;

/**
 * 文本向量化（Embedding）服务商抽象。
 *
 * <p>由 {@code ai.embedding.provider} 选择具体实现：
 * <ul>
 *   <li>{@code siliconflow}（默认）—— {@link EmbeddingUtils}，模型 {@code BAAI/bge-m3}；</li>
 *   <li>{@code bailian} —— {@link BailianEmbeddingUtils}，模型 {@code text-embedding-v3/v4}。</li>
 * </ul>
 *
 * <p>注意：不同服务商的向量维度不同（bge-m3 为 1024，text-embedding-v4 默认 1024），
 * 切换实现且维度变化时，需要先重建 Qdrant collection，否则写入会被拒绝。
 */
public interface EmbeddingClient {

    /**
     * 把文本转换为向量。
     *
     * @return 向量；失败时抛出运行时异常，调用方已有降级到关键词检索的处理
     */
    List<Double> embed(String text);
}
