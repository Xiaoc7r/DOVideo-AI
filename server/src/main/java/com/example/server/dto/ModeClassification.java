package com.example.server.dto;

/**
 * LLM 意图分类的<strong>原始</strong>产物,专用于反序列化模型返回的 JSON。
 *
 * <p>{@code mode} 故意保留为字符串而非 {@link AnalysisMode} 枚举:模型可能返回大小写不一、 带多余空白甚至无法识别的值,直接反序列化成枚举会抛异常。改由上层
 * {@code ModeRouter} 用 {@link AnalysisMode#fromNullable(String)} 宽松解析并兜底, 把"模型输出不可控"隔离在这一层,不污染对外的
 * {@link RouteDecision} 契约。
 *
 * @param mode 模型给出的模式名(期望是 GENERAL/LEARNING/REVIEW/CREATION 之一,但不保证)
 * @param reason 模型给出的一句话判定理由,可能为空
 */
public record ModeClassification(String mode, String reason) {}
