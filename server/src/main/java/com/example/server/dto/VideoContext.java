package com.example.server.dto;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 */
public record VideoContext(
        String source,
        String userGoal,
        List<VideoSegment> segments
) {
    public VideoContext {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("video source is required");
        userGoal = userGoal == null ? "" : userGoal.trim();
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames
    ) {
        public VideoSegment {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
            transcript = transcript == null ? "" : transcript.trim();
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
            evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
        }
    }

    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
