package com.example.server.dto;

import java.time.Instant;
import java.util.List;

public record AgentFeedback(
        Long mediaId,
        String goal,
        Integer rating,
        String errorType,
        String comment,
        String correctedGoal,
        List<String> correctedTasks,
        Long evidenceTimestamp,
        Boolean evidenceAccepted,
        Instant createdAt
) {
    public AgentFeedback normalized() {
        return new AgentFeedback(
                mediaId,
                goal == null ? null : goal.trim(),
                rating,
                errorType == null ? null : errorType.trim(),
                comment == null ? null : comment.trim(),
                correctedGoal == null ? null : correctedGoal.trim(),
                correctedTasks == null ? List.of() : List.copyOf(correctedTasks),
                evidenceTimestamp,
                evidenceAccepted,
                createdAt == null ? Instant.now() : createdAt
        );
    }
}
