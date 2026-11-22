package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.ModeClassification;
import com.example.server.dto.RouteDecision;
import com.example.server.utils.DeepSeekUtils;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 意图路由器:把用户的自然语言分析目标交给 LLM,归类到某个具体的 {@link AnalysisMode}。
 *
 * <h2>设计约束</h2>
 *
 * <ol>
 *   <li><b>绝不阻断主流程。</b>路由只是"锦上添花"的增强——模型超时、返回空、给出无法识别的 模式名,统统安全回退到 {@link
 *       AnalysisMode#GENERAL}。因此本类<em>对外不抛任何异常</em>, {@link #route(String)} 永远返回一个可用的 {@link
 *       RouteDecision}。
 *   <li><b>只产出具体模式。</b>路由必须在提交(生成幂等 key)之前完成,产物一定是四个具体 业务模式之一,绝不会是"AUTO/待路由"的中间态。前端据此发起真正的分析请求,
 *       从而保证读写两端的任务 key 完全一致。
 *   <li><b>宽松解析。</b>模型输出不可控,统一用 {@link AnalysisMode#fromNullable(String)} 解析(大小写归一 +
 *       非法值兜底),把不确定性收敛在这一层。
 * </ol>
 */
@Service
public class ModeRouter {

  private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);
  private static final int USER_ROUTES_PER_MINUTE = 10;
  private static final int GLOBAL_ROUTES_PER_MINUTE = 60;

  private final DeepSeekUtils deepSeekUtils;
  private final RedissonClient redissonClient;

  public ModeRouter(DeepSeekUtils deepSeekUtils, RedissonClient redissonClient) {
    this.deepSeekUtils = deepSeekUtils;
    this.redissonClient = redissonClient;
  }

  /** 依据分析目标推断分析模式。任何异常/空目标/无法识别的返回都回退 {@link AnalysisMode#GENERAL}, 保证调用方总能拿到一个具体、可用的模式。 */
  public RouteDecision route(String goal) {
    if (goal == null || goal.isBlank()) {
      return new RouteDecision(AnalysisMode.GENERAL, "未提供分析目标,已按通用模式分析");
    }
    try {
      ModeClassification classification = deepSeekUtils.classifyMode(goal.trim());
      AnalysisMode mode =
          AnalysisMode.fromNullable(classification == null ? null : classification.mode());
      String reason = pickReason(classification, mode);
      return new RouteDecision(mode, reason);
    } catch (Exception e) {
      // 路由失败绝不能拖垮分析:记录后回退通用模式,让任务照常进行。
      log.warn("自动意图路由失败,回退 GENERAL。goalLength={}", goal.length(), e);
      return new RouteDecision(AnalysisMode.GENERAL, "意图识别暂不可用,已按通用模式分析");
    }
  }

  /** 面向用户请求的路由入口。自动路由本身也会消耗一次模型调用，因此使用独立配额； 配额不足或 Redis 不可用时直接按 GENERAL 继续，不让增强能力拖垮主流程。 */
  public RouteDecision route(String goal, Long userId) {
    if (!tryAcquireQuota(userId)) {
      return new RouteDecision(AnalysisMode.GENERAL, "自动路由当前繁忙,已按通用模式分析");
    }
    return route(goal);
  }

  private boolean tryAcquireQuota(Long userId) {
    if (userId == null) return false;
    try {
      RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:route:user:" + userId);
      userLimiter.trySetRate(RateType.OVERALL, USER_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
      if (!userLimiter.tryAcquire()) return false;

      RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:route:global");
      globalLimiter.trySetRate(
          RateType.OVERALL, GLOBAL_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
      return globalLimiter.tryAcquire();
    } catch (RuntimeException e) {
      log.warn("自动意图路由限流器不可用,回退 GENERAL。userId={}", userId, e);
      return false;
    }
  }

  /** 优先用模型给出的理由;缺失时按最终落定的模式给一句兜底说明。 */
  private String pickReason(ModeClassification classification, AnalysisMode mode) {
    if (classification != null
        && classification.reason() != null
        && !classification.reason().isBlank()) {
      return classification.reason().trim();
    }
    return switch (mode) {
      case LEARNING -> "目标偏向知识梳理与复习,已选学习模式";
      case REVIEW -> "目标偏向查错与观点核验,已选审查模式";
      case CREATION -> "目标偏向内容再创作,已选创作模式";
      case GENERAL -> "目标较为通用,已选通用模式";
    };
  }
}
