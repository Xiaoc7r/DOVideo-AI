package com.example.server.dto;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 */
public record VideoContext(
        String source,
        List<VideoSegment> segments
) {
    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames
    ) {
    }

    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }
}
