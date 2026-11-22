package com.example.server.dto;

import com.example.server.entity.FailedAnalysisTask;
import java.time.LocalDateTime;

/**
 * 失败任务的对外视图。
 *
 * <p>只暴露管理台需要的字段，刻意不含 {@code contentHash} 等内部字段，避免持久层结构直接 泄漏给外部、也避免表结构变动破坏 API 契约。
 */
public record FailedTaskView(
    Long id,
    Long mediaId,
    String action,
    String mode,
    String userGoal,
    Integer attemptCount,
    String errorType,
    String errorMessage,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
  public static FailedTaskView from(FailedAnalysisTask task) {
    return new FailedTaskView(
        task.getId(),
        task.getMediaId(),
        task.getAction(),
        task.getMode(),
        task.getUserGoal(),
        task.getAttemptCount(),
        task.getErrorType(),
        task.getErrorMessage(),
        task.getStatus(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }
}
