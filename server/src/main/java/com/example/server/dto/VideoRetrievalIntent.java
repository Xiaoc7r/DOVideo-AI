package com.example.server.dto;

import java.util.List;
import java.util.Objects;

/** 用户问题在视频检索阶段使用的语义与画面文字线索。 */
public record VideoRetrievalIntent(
    String semanticQuery, List<String> keywords, List<String> visualKeywords) {
  public VideoRetrievalIntent {
    semanticQuery = semanticQuery == null ? "" : semanticQuery.trim();
    keywords = normalizeTerms(keywords);
    visualKeywords = normalizeTerms(visualKeywords);
  }

  private static List<String> normalizeTerms(List<String> terms) {
    if (terms == null) return List.of();
    return terms.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(term -> !term.isBlank())
        .distinct()
        .limit(16)
        .toList();
  }
}
