/**
 * 后端 TaskStage 到侧边栏五阶段时间线的适配层。
 *
 * 后端为了 Checkpoint、重试和可观测性会发送更细的状态（例如
 * PLAN_COMPLETED / EXECUTOR_STARTED / CRITIC_RETRY_REQUIRED），侧边栏只展示
 * 五个稳定的产品阶段。这里集中维护映射，避免 UI 直接依赖后端枚举的字面值。
 */
export const ANALYSIS_STAGES = Object.freeze([
  Object.freeze(['VIDEO_CONTEXT', '解析语音与画面']),
  Object.freeze(['RETRIEVAL', '检索相关证据']),
  Object.freeze(['PLANNER', '拆解分析任务']),
  Object.freeze(['EXECUTOR', '生成结构化结果']),
  Object.freeze(['CRITIC', '核验结论与证据'])
])

const SERVER_STAGE_TO_UI_STAGE = new Map([
  ['VIDEO_CONTEXT', 'VIDEO_CONTEXT'],
  ['CONTEXT_COMPLETED', 'VIDEO_CONTEXT'],
  ['CHUNKS_COMPLETED', 'RETRIEVAL'],
  ['RETRIEVAL', 'RETRIEVAL'],
  ['AGENT_LOOP', 'PLANNER'],
  ['PLAN_COMPLETED', 'PLANNER'],
  ['EXECUTOR_STARTED', 'EXECUTOR'],
  ['EXECUTOR_COMPLETED', 'EXECUTOR'],
  ['CRITIC_STARTED', 'CRITIC'],
  ['CRITIC_PASSED', 'CRITIC'],
  ['CRITIC_RETRY_REQUIRED', 'CRITIC'],
  ['EVIDENCE_REFRESHED', 'CRITIC'],
  ['ANALYSIS_COMPLETED', 'CRITIC'],
  ['ANALYSIS_COMPLETED_WITH_WARNINGS', 'CRITIC'],
  ['COMPLETED', 'CRITIC'],
  ['COMPLETED_REUSED', 'CRITIC']
])

/** 未知或非分析阶段返回 null；调用方应保留最后一个已知阶段。 */
export function analysisStageOf(serverStage) {
  return SERVER_STAGE_TO_UI_STAGE.get(serverStage) || null
}

export function analysisStageLabelOf(serverStage) {
  const uiStage = analysisStageOf(serverStage)
  return ANALYSIS_STAGES.find(([stage]) => stage === uiStage)?.[1] || null
}
