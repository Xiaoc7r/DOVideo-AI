/**
 * 多模式 Agent 的模式定义与注册。
 *
 * <p>该子系统是"目标驱动分析"的扩展点:{@code ModeRegistry} 集中持有各 {@link com.example.server.dto.AnalysisMode} 对应的
 * {@code ModeProfile}(Planner / Executor / Critic 三段指令 + 是否强化时间戳)。新增一个业务模式只需加一个枚举值并注册一份
 * Profile,核心编排({@code AgentLoopService})无需改动。
 *
 * <p>依赖方向:被 {@code service} 层(AiService / AgentLoopService)读取;自身仅依赖 {@code dto},不反向依赖编排层,保持单向依赖。
 */
package com.example.server.service.mode;
