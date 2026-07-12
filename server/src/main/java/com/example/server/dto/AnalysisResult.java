package com.example.server.dto;

import java.util.List;

/**
 * 固定 Agent 产物结构，避免模型只返回一段无法继续处理的自由文本。
 */
public record AnalysisResult(
        String title,
        List<String> conclusions,
        List<Evidence> evidence,
        List<String> suggestions
) {
    public AnalysisResult {
        title = title == null ? "未命名分析" : title.trim();
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public record Evidence(
            long timestampMs,
            String source,
            String content
    ) {
        public Evidence {
            if (timestampMs < 0) throw new IllegalArgumentException("evidence timestamp cannot be negative");
            source = source == null ? "UNKNOWN" : source.trim();
            content = content == null ? "" : content.trim();
        }
    }

    public String toMarkdown() {
        StringBuilder result = new StringBuilder("## ").append(title).append("\n\n## 核心结论\n");
        conclusions.forEach(item -> result.append("- ").append(item).append('\n'));
        result.append("\n## 视频证据\n");
        evidence.forEach(item -> result.append("- [")
                .append(formatTime(item.timestampMs()))
                .append("] ")
                .append(item.source())
                .append("：")
                .append(item.content())
                .append('\n'));
        result.append("\n## 建议\n");
        suggestions.forEach(item -> result.append("- ").append(item).append('\n'));
        return result.toString();
    }

    private static String formatTime(long timestampMs) {
        long seconds = timestampMs / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
