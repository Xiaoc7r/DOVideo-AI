package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import java.util.List;

/**
 * 一个分析模式的全部差异化配置,集中在这一个数据结构里。
 *
 * <p>三段 instruction 会分别拼接到 Planner / Executor / Critic 的基础 prompt 之后, 从而在不改动 {@code
 * AgentLoopService} 编排逻辑的前提下改变各角色的行为;
 *
 * <p>GENERAL 模式的三段 instruction 均为空串, 因此叠加后与原有 prompt 完全一致——这是"引入模式不改变默认行为"的关键。
 *
 * @param mode 所属模式
 * @param displayName 面向用户的模式名
 * @param planInstruction 追加到 Planner:如何拆解任务
 * @param executeInstruction 追加到 Executor:额外产出哪些产物段落(section)
 * @param criticInstruction 追加到 Critic:重点校验什么
 * @param requiredSectionKeys 模式必须产出的结构化段落 key
 */
public record ModeProfile(
    AnalysisMode mode,
    String displayName,
    String planInstruction,
    String executeInstruction,
    String criticInstruction,
    List<String> requiredSectionKeys) {
  public ModeProfile {
    planInstruction = planInstruction == null ? "" : planInstruction;
    executeInstruction = executeInstruction == null ? "" : executeInstruction;
    criticInstruction = criticInstruction == null ? "" : criticInstruction;
    requiredSectionKeys =
        requiredSectionKeys == null ? List.of() : requiredSectionKeys.stream().distinct().toList();
  }
}
