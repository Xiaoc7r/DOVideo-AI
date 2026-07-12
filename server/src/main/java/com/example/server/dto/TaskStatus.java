package com.example.server.dto;

public record TaskStatus(State state, String result, String message) {

    public enum State {
        NOT_STARTED,
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public static TaskStatus of(State state, String message) {
        return new TaskStatus(state, null, message);
    }

    public static TaskStatus completed(String result) {
        return new TaskStatus(State.COMPLETED, result, "任务完成");
    }
}
