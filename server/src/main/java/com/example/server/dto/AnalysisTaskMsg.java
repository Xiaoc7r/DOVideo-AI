package com.example.server.dto;

import java.io.Serializable;

public class AnalysisTaskMsg implements Serializable {

  public static final String START_ANALYSIS = "START_ANALYSIS";
  public static final String REVISE_ANALYSIS = "REVISE_ANALYSIS";

  private Long mediaId;
  private String action;
  private String contentHash;
  private String userGoal;

  /** 分析模式名(见 {@link AnalysisMode})。可空,历史消息或缺省时按 GENERAL 处理。 */
  private String mode;

  public AnalysisTaskMsg() {}

  /** 兼容旧调用方的四参构造器,模式默认 GENERAL。 */
  public AnalysisTaskMsg(Long mediaId, String action, String contentHash, String userGoal) {
    this(mediaId, action, contentHash, userGoal, AnalysisMode.GENERAL.name());
  }

  public AnalysisTaskMsg(
      Long mediaId, String action, String contentHash, String userGoal, String mode) {
    this.mediaId = mediaId;
    this.action = action;
    this.contentHash = contentHash;
    this.userGoal = userGoal;
    this.mode = mode;
  }

  public Long getMediaId() {
    return mediaId;
  }

  public void setMediaId(Long mediaId) {
    this.mediaId = mediaId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public String getUserGoal() {
    return userGoal;
  }

  public void setUserGoal(String userGoal) {
    this.userGoal = userGoal;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public boolean isRevision() {
    return REVISE_ANALYSIS.equals(action);
  }

  public boolean hasSupportedAction() {
    return isSupportedAction(action);
  }

  /** 供失败台账等场景在没有消息实例时复用同一套 action 判定，避免两处规则漂移。 */
  public static boolean isSupportedAction(String action) {
    return START_ANALYSIS.equals(action) || REVISE_ANALYSIS.equals(action);
  }
}
