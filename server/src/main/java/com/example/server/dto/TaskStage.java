package com.example.server.dto;

public enum TaskStage {
  QUEUED,
  CONSUMING,
  VIDEO_CONTEXT,
  CONTEXT_COMPLETED,
  CHUNKS_COMPLETED,
  RETRIEVAL,
  AGENT_LOOP,
  PLAN_COMPLETED,
  EXECUTOR_STARTED,
  EXECUTOR_COMPLETED,
  CRITIC_STARTED,
  CRITIC_PASSED,
  CRITIC_RETRY_REQUIRED,
  EVIDENCE_REFRESHED,
  ANALYSIS_COMPLETED,
  ANALYSIS_COMPLETED_WITH_WARNINGS,
  BUDGET_EXHAUSTED,
  RETRYING,
  COMPLETED,
  COMPLETED_REUSED,
  FAILED,
  DEAD_LETTERED,
  MANUAL_REPLAY,
  REVISION_PENDING,
  REVISION_APPLIED,
  TRANSCRIPTION,
  ASR,
  DISPATCH_FAILED;

  public static TaskStage from(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return valueOf(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
