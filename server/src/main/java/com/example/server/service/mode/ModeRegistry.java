package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模式注册表:集中持有各 {@link AnalysisMode} 的 {@link ModeProfile}。
 *
 * <p>这是整套多模式能力的扩展点——新增一个业务模式,只需在 {@link AnalysisMode} 加枚举值、 在本类构造器里 {@code register(...)} 一份
 * Profile 即可,核心编排(Planner/Executor/Critic、 时序上下文、证据绑定、检查点)完全复用,不需要任何改动。
 *
 * <p>GENERAL 注册的是"空指令"Profile,确保未指定模式时行为与引入模式体系之前一致。
 */
@Component
public class ModeRegistry {

  private final Map<AnalysisMode, ModeProfile> profiles = new EnumMap<>(AnalysisMode.class);

  public ModeRegistry() {
    // 默认模式:空指令 = 完全等于现有行为,作为兜底
    register(new ModeProfile(AnalysisMode.GENERAL, "通用分析", "", "", "", List.of()));

    // 学习模式:强化知识结构化
    register(
        new ModeProfile(
            AnalysisMode.LEARNING,
            "学习复习",
            "按知识主题而非时间顺序拆解任务,覆盖核心概念、原理与易混点。",
            "在结论之外,额外产出以下产物段落:"
                + "key=outline 知识点大纲、key=keypoints 重点难点、"
                + "key=quiz 自测题(每题附答案)、key=pitfalls 易错点。",
            "额外检查:知识点是否成体系、讲解是否有跳步、自测题是否覆盖核心概念。",
            List.of("outline", "keypoints", "quiz", "pitfalls")));

    // 审查模式:强化 Critic 质疑
    register(
        new ModeProfile(
            AnalysisMode.REVIEW,
            "内容审查",
            "把目标拆成对每个主要论点的可验证审查项。",
            "在结论之外,额外产出以下产物段落:"
                + "key=fallacies 逻辑漏洞、key=exaggerations 夸大表述、"
                + "key=omissions 遗漏点、key=doubtful 存疑结论(附理由)。",
            "以更严格的门槛质疑:论据是否充分、有无偷换概念、结论是否被证据支持;" + "证据不足时必须判定不通过。",
            List.of("fallacies", "exaggerations", "omissions", "doubtful")));

    // 创作模式:强化时间戳爆点定位
    register(
        new ModeProfile(
            AnalysisMode.CREATION,
            "内容创作",
            "围绕'可发布资产'拆解:定位爆点、可切片段落与传播钩子。",
            "在结论之外,额外产出以下产物段落:"
                + "key=highlights 爆点片段(每条含起止时间戳)、key=titles 备选标题、"
                + "key=intro 简介文案、key=script 口播脚本要点。",
            "检查每个爆点是否有真实时间戳支撑、文案是否贴合视频实际内容,不得虚构。",
            List.of("highlights", "titles", "intro", "script")));

    // 启动自检:每个 AnalysisMode 都必须注册 Profile。否则该模式在写端(AgentLoop 用
    // of(mode).mode())会回退成 GENERAL 键,而读端(status/asyncAnalyze)仍用该模式键,
    // 造成"前端永远查不到结果、消费者反复重算"的致命键不对称。宁可启动即失败也不让它上线。
    for (AnalysisMode value : AnalysisMode.values()) {
      if (!profiles.containsKey(value)) {
        throw new IllegalStateException("AnalysisMode 未注册 ModeProfile: " + value);
      }
    }
  }

  private void register(ModeProfile profile) {
    profiles.put(profile.mode(), profile);
  }

  /** 取指定模式的 Profile;未注册时回退到 GENERAL,保证永远有可用配置。 */
  public ModeProfile of(AnalysisMode mode) {
    return profiles.getOrDefault(mode, profiles.get(AnalysisMode.GENERAL));
  }
}
