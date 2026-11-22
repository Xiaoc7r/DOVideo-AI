package com.example.server.dto;

import java.util.Locale;

/**
 * 分析模式:决定 Agent 走哪条分析路径、产出哪种结构化产物。
 *
 * <p>{@link #GENERAL} 是默认与兜底模式,行为等价于引入模式体系之前的原有流程; 其余模式在此基础上叠加各自的 Planner/Executor/Critic 指令与产物段落。
 * 新增业务模式时只需在这里加一个枚举值,并在 {@code ModeRegistry} 注册一份 Profile, 无需改动核心编排。
 */
public enum AnalysisMode {

  /** 通用分析(默认):结论 + 时间戳证据 + 建议。 */
  GENERAL,
  /** 学习模式:知识点大纲 / 重点难点 / 自测题 / 易错点。 */
  LEARNING,
  /** 审查模式:逻辑漏洞 / 夸大表述 / 遗漏点 / 存疑结论。 */
  REVIEW,
  /** 创作模式:爆点片段 / 标题 / 简介 / 口播脚本。 */
  CREATION;

  /** 从可空字符串安全解析模式。空值或无法识别的值一律回退到 {@link #GENERAL}, 保证历史消息、异常入参都不会因为模式字段而中断任务。 */
  public static AnalysisMode fromNullable(String value) {
    if (value == null || value.isBlank()) return GENERAL;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return GENERAL;
    }
  }

  /** 解析来自 HTTP 请求的模式。请求缺省仍兼容 GENERAL，但显式传错模式时直接拒绝， 避免用户选择 REVIEW 却因拼写错误静默执行成 GENERAL。 */
  public static AnalysisMode fromRequest(String value) {
    if (value == null || value.isBlank()) return GENERAL;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的分析模式: " + value);
    }
  }
}
