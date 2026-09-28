import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ANALYSIS_STAGES,
  analysisStageLabelOf,
  analysisStageOf
} from '../lib/stageProgress.js'

test('侧边栏固定展示五个产品阶段', () => {
  assert.deepEqual(ANALYSIS_STAGES.map(([key]) => key), [
    'VIDEO_CONTEXT',
    'RETRIEVAL',
    'PLANNER',
    'EXECUTOR',
    'CRITIC'
  ])
})

test('映射后端实际发送的 TaskStage', () => {
  const expected = {
    VIDEO_CONTEXT: 'VIDEO_CONTEXT',
    CONTEXT_COMPLETED: 'VIDEO_CONTEXT',
    CHUNKS_COMPLETED: 'RETRIEVAL',
    RETRIEVAL: 'RETRIEVAL',
    AGENT_LOOP: 'PLANNER',
    PLAN_COMPLETED: 'PLANNER',
    EXECUTOR_STARTED: 'EXECUTOR',
    EXECUTOR_COMPLETED: 'EXECUTOR',
    CRITIC_STARTED: 'CRITIC',
    CRITIC_PASSED: 'CRITIC',
    CRITIC_RETRY_REQUIRED: 'CRITIC',
    EVIDENCE_REFRESHED: 'CRITIC',
    ANALYSIS_COMPLETED: 'CRITIC',
    ANALYSIS_COMPLETED_WITH_WARNINGS: 'CRITIC',
    COMPLETED: 'CRITIC',
    COMPLETED_REUSED: 'CRITIC'
  }

  for (const [serverStage, uiStage] of Object.entries(expected)) {
    assert.equal(analysisStageOf(serverStage), uiStage, serverStage)
  }
})

test('未知、失败和重试状态不覆盖最后一个已知阶段', () => {
  for (const stage of [null, '', 'QUEUED', 'RETRYING', 'FAILED', 'DEAD_LETTERED', 'UNKNOWN']) {
    assert.equal(analysisStageOf(stage), null, String(stage))
  }
})

test('顶部状态文案也使用同一份后端阶段映射', () => {
  assert.equal(analysisStageLabelOf('PLAN_COMPLETED'), '拆解分析任务')
  assert.equal(analysisStageLabelOf('CRITIC_RETRY_REQUIRED'), '核验结论与证据')
  assert.equal(analysisStageLabelOf('RETRYING'), null)
})
