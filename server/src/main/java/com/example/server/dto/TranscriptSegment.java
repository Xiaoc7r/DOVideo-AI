package com.example.server.dto;

public record TranscriptSegment(long startMs, long endMs, String text) {

    public TranscriptSegment {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid transcript range");
        text = text == null ? "" : text.trim();
    }
}
