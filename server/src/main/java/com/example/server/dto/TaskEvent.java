package com.example.server.dto;

public record TaskEvent(TaskStatus.State state, String result, String message, String stage) {

    public static TaskEvent of(TaskStatus status, String stage) {
        return new TaskEvent(status.state(), status.result(), status.message(), stage);
    }

    public boolean terminal() {
        return state == TaskStatus.State.COMPLETED || state == TaskStatus.State.FAILED;
    }
}
