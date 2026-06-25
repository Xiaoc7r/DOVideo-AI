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
    public record Evidence(
            long timestampMs,
            String source,
            String content
    ) {
    }

    public String toMarkdown() {
        StringBuilder result = new StringBuilder("## ").append(title).append("\n\n## 核心结论\n");
        if (conclusions != null) {
            conclusions.forEach(item -> result.append("- ").append(item).append('\n'));
        }
        result.append("\n## 视频证据\n");
        if (evidence != null) {
            evidence.forEach(item -> result.append("- [")
                    .append(formatTime(item.timestampMs()))
                    .append("] ")
                    .append(item.source())
                    .append("：")
                    .append(item.content())
                    .append('\n'));
        }
        result.append("\n## 建议\n");
        if (suggestions != null) {
            suggestions.forEach(item -> result.append("- ").append(item).append('\n'));
        }
        return result.toString();
    }

    private static String formatTime(long timestampMs) {
        long seconds = timestampMs / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
