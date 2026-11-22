package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record AgentFeedback(
    @NotNull(message = "mediaId 不能为空") Long mediaId,
    @NotBlank(message = "分析目标不能为空") @Size(max = 500, message = "分析目标不能超过 500 字") String goal,
    String mode,
    Integer rating,
    @Size(max = 64, message = "错误类型不能超过 64 字") String errorType,
    @Size(max = 2000, message = "反馈说明不能超过 2000 字") String comment,
    @Size(max = 500, message = "修正后的分析目标不能超过 500 字") String correctedGoal,
    @Size(max = 5, message = "修正任务最多 5 条")
        List<@Size(max = 500, message = "每条修正任务不能超过 500 字") String> correctedTasks,
    @PositiveOrZero(message = "证据时间戳不能为负数") Long evidenceTimestamp,
    Boolean evidenceAccepted,
    Instant createdAt) {
  public AgentFeedback normalized() {
    return normalized(AnalysisMode.fromNullable(mode));
  }

  public AgentFeedback normalized(AnalysisMode analysisMode) {
    return new AgentFeedback(
        mediaId,
        goal == null ? null : goal.trim(),
        (analysisMode == null ? AnalysisMode.GENERAL : analysisMode).name(),
        rating,
        errorType == null ? null : errorType.trim(),
        comment == null ? null : comment.trim(),
        correctedGoal == null ? null : correctedGoal.trim(),
        correctedTasks == null
            ? List.of()
            : correctedTasks.stream()
                .filter(task -> task != null && !task.isBlank())
                .map(String::trim)
                .toList(),
        evidenceTimestamp,
        evidenceAccepted,
        createdAt == null ? Instant.now() : createdAt);
  }
}
