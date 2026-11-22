package com.example.server.dto;

/**
 * 自动意图路由的对外判定结果:AI 依据分析目标推断出的最合适模式,以及一句可展示给用户的理由。
 *
 * <p>不变量:{@link #mode} 一定是四个<strong>具体</strong>业务模式之一 (GENERAL/LEARNING/REVIEW/CREATION),绝不会是"待路由 /
 * AUTO"这种中间态。 因为路由必须在生成任何幂等 key <em>之前</em>落定为具体模式,否则写端(提交/消费)
 * 与读端(状态查询/重跑)会用不同的键,导致"永远查不到结果、消费者反复重算"。 前端拿到本结果后,即以 {@code mode} 作为后续所有请求的模式,保证读写两端 key 完全一致。
 *
 * <p>紧凑构造器对 null 做兜底,任何异常路径都能得到一个可用的通用模式判定。
 *
 * @param mode 最终采用的具体分析模式
 * @param reason 面向用户的判定理由(简短中文)
 */
public record RouteDecision(AnalysisMode mode, String reason) {

  public RouteDecision {
    if (mode == null) {
      mode = AnalysisMode.GENERAL;
    }
    if (reason == null || reason.isBlank()) {
      reason = "已按通用模式分析";
    }
  }
}
