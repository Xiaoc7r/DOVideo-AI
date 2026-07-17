import { computed, ref } from 'vue'
import { apiRequest } from './api'
import { DEMO_EVALUATION, DEMO_ITEM, DEMO_PLAN, DEMO_RESULT, DEMO_TRACE } from './demoData'
import { renderMarkdown } from './markdown'

const DEFAULT_GOAL = '理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议'
const GOAL_PRESETS = [
  {
    title: '学习笔记',
    description: '章节、知识点与复习建议',
    prompt: '生成结构化学习笔记，按章节提炼知识点，引用关键时间戳，并给出复习建议'
  },
  {
    title: '会议纪要',
    description: '结论、分歧与待办事项',
    prompt: '生成会议纪要，整理核心议题、明确结论、分歧点和待办事项，并引用对应时间戳'
  },
  {
    title: '操作手册',
    description: '步骤、条件与异常处理',
    prompt: '生成可执行操作手册，提取前置条件、操作步骤、注意事项和异常处理，并引用对应时间戳'
  }
]
const STAGE_LABELS = {
  VIDEO_CONTEXT: '解析语音与画面',
  RETRIEVAL: '检索相关证据',
  PLANNER: '拆解分析任务',
  EXECUTOR: '生成结构化结果',
  CRITIC: '核验结论与证据'
}

function createSidebarState() {
  return {
    visible: false,
    type: 'ai',
    mode: 'compose',
    title: '',
    content: '',
    error: '',
    loading: false,
    mediaId: null,
    goal: DEFAULT_GOAL,
    playbackUrl: '',
    playbackLoading: false,
    followUp: '',
    followUpLoading: false,
    plan: null,
    trace: null,
    evaluation: null,
    feedback: null,
    editingPlan: false,
    planDraft: [],
    rerunLoading: false
  }
}

export function useAnalysisWorkspace({
  demoMode,
  taskStreams,
  showMessage,
  refreshMediaList,
  findMediaItem
}) {
  const sidebar = ref(createSidebarState())
  const traceStages = computed(() => Object.entries(sidebar.value.trace?.stageDurationMs || {})
    .map(([stage, duration]) => [STAGE_LABELS[stage] || stage, formatDuration(duration)]))
  const renderedMarkdown = computed(() => renderMarkdown(sidebar.value.content))

  const openSidebar = (type, title) => {
    sidebar.value.visible = true
    sidebar.value.type = type
    sidebar.value.title = title
    sidebar.value.loading = true
    sidebar.value.content = ''
  }

  const closeSidebar = () => {
    if (sidebar.value.type === 'ai' && sidebar.value.mediaId) {
      saveGoalDraft(sidebar.value.mediaId, sidebar.value.goal)
    }
    sidebar.value.visible = false
  }

  const loadPlayback = async id => {
    sidebar.value.playbackLoading = true
    try {
      const response = await apiRequest(`/media/playback?id=${id}`)
      const url = await response.text()
      if (!response.ok) throw new Error(url || '视频加载失败')
      if (sidebar.value.mediaId === id) sidebar.value.playbackUrl = url
    } catch (error) {
      console.warn('Video preview unavailable', error)
    } finally {
      if (sidebar.value.mediaId === id) sidebar.value.playbackLoading = false
    }
  }

  const refreshAgentMeta = async (id, goal, includeEvaluation) => {
    const params = new URLSearchParams({ id: String(id), goal })
    try {
      const requests = [
        apiRequest(`/analysis/agent-plan?${params}`),
        apiRequest(`/analysis/agent-trace?${params}`)
      ]
      if (includeEvaluation) requests.push(apiRequest(`/analysis/agent-evaluation?${params}`))

      const responses = await Promise.all(requests)
      if (sidebar.value.mediaId !== id || sidebar.value.goal !== goal) return

      const planText = responses[0].ok ? await responses[0].text() : ''
      const traceText = responses[1].ok ? await responses[1].text() : ''
      if (planText && !sidebar.value.editingPlan) sidebar.value.plan = JSON.parse(planText)
      if (traceText) sidebar.value.trace = JSON.parse(traceText)
      if (includeEvaluation && responses[2]?.ok) {
        const evaluationText = await responses[2].text()
        if (evaluationText) sidebar.value.evaluation = JSON.parse(evaluationText)
      }
    } catch (error) {
      console.warn('Agent metadata unavailable', error)
    }
  }

  const startTaskStream = (id, type, goal = '') => {
    const scope = type === 'ai' ? goal : ''
    const isCurrentTask = () => sidebar.value.mediaId === id
      && sidebar.value.type === type
      && (type !== 'ai' || sidebar.value.goal === goal)
    const finish = async (result, failed = false) => {
      if (sidebar.value.visible && isCurrentTask()) {
        sidebar.value.content = failed && type === 'ai' ? '' : result
        sidebar.value.loading = false
        sidebar.value.error = failed && type === 'ai' ? result : ''
        if (failed && type === 'ai') sidebar.value.mode = 'compose'
        if (type === 'ai' && !failed) await refreshAgentMeta(id, goal, true)
      }
      showMessage(failed ? '任务执行失败，请稍后重试' : '任务完成', failed)
      taskStreams.stop(id, type, scope)
    }

    const params = new URLSearchParams({ id: String(id) })
    if (type === 'ai') params.set('goal', goal)
    const path = type === 'ai'
      ? `/analysis/analysis-events?${params}`
      : `/analysis/transcription-events?${params}`

    taskStreams.start(id, type, scope, path, async status => {
      if (isCurrentTask() && status.message) {
        sidebar.value.content = status.state === 'PROCESSING' || status.state === 'QUEUED'
          ? status.message
          : sidebar.value.content
      }
      if (type === 'ai' && status.stage && isCurrentTask()) {
        await refreshAgentMeta(id, goal, false)
      }
      if (status.state === 'COMPLETED') {
        await refreshMediaList()
        await finish(status.result || (type === 'ai' ? '分析完成' : ''))
      } else if (status.state === 'FAILED') {
        await finish(status.message || '任务执行失败', true)
      }
    }, error => {
      console.warn('task event stream reconnecting', error)
    })
  }

  const transcribe = async id => {
    const item = findMediaItem(id)
    if (demoMode) {
      openSidebar('text', 'ASR 转写结果')
      sidebar.value.content = item?.transcriptText || DEMO_ITEM.transcriptText
      sidebar.value.loading = false
      return
    }
    if (taskStreams.has(id, 'text')) {
      openSidebar('text', '全量文字提取')
      sidebar.value.mediaId = id
      sidebar.value.content = '📝 文字提取正在后台进行中...'
      return
    }

    openSidebar('text', '全量文字提取')
    sidebar.value.mediaId = id
    sidebar.value.content = '📝 提取任务已提交，正在识别语音流...'
    try {
      const current = await apiRequest(`/analysis/transcription-status?id=${id}`)
      if (!current.ok) throw new Error(await current.text())
      const currentStatus = await current.json()
      if (currentStatus.state === 'COMPLETED') {
        sidebar.value.content = currentStatus.result || ''
        sidebar.value.loading = false
        return
      }
      if (currentStatus.state === 'QUEUED' || currentStatus.state === 'PROCESSING') {
        startTaskStream(id, 'text')
        return
      }
      const response = await apiRequest(`/analysis/transcribe?id=${id}`, { method: 'POST' })
      if (!response.ok) throw new Error(await response.text())
      startTaskStream(id, 'text')
    } catch (error) {
      sidebar.value.content = error.message || '文字提取失败，请稍后重试'
      sidebar.value.loading = false
    }
  }

  const analyze = async (id, goal) => {
    if (taskStreams.has(id, 'ai', goal)) {
      sidebar.value.mode = 'result'
      sidebar.value.loading = true
      return
    }

    sidebar.value.loading = true
    sidebar.value.mode = 'result'
    sidebar.value.content = ''
    try {
      const params = new URLSearchParams({ id: String(id), goal })
      const response = await apiRequest(`/analysis/ai?${params}`, { method: 'POST' })
      const message = await response.text()
      if (response.status === 409) {
        startTaskStream(id, 'ai', goal)
        refreshAgentMeta(id, goal, false)
        return
      }
      if (!response.ok) {
        showMessage(message, true)
        sidebar.value.loading = false
        sidebar.value.mode = 'compose'
        sidebar.value.error = message
        return
      }
      startTaskStream(id, 'ai', goal)
      refreshAgentMeta(id, goal, false)
    } catch (error) {
      sidebar.value.mode = 'compose'
      sidebar.value.error = error.message || String(error)
      sidebar.value.loading = false
    }
  }

  const openAgent = async item => {
    const goal = loadGoalDraft(item.id)
    sidebar.value = {
      ...createSidebarState(),
      visible: true,
      title: `Video Agent · ${item.filename}`,
      mediaId: item.id,
      goal
    }
    if (demoMode) return

    loadPlayback(item.id)
    try {
      const params = new URLSearchParams({ id: String(item.id), goal })
      const response = await apiRequest(`/analysis/analysis-status?${params}`)
      if (!response.ok) return
      const status = await response.json()
      if (sidebar.value.mediaId !== item.id || sidebar.value.goal !== goal) return

      if (status.state === 'COMPLETED') {
        sidebar.value.mode = 'result'
        sidebar.value.content = status.result || ''
        sidebar.value.loading = false
        await refreshAgentMeta(item.id, goal, true)
      } else if (status.state === 'QUEUED' || status.state === 'PROCESSING') {
        sidebar.value.mode = 'result'
        sidebar.value.loading = true
        sidebar.value.content = status.message || '正在恢复分析任务...'
        startTaskStream(item.id, 'ai', goal)
        await refreshAgentMeta(item.id, goal, false)
      } else if (status.state === 'FAILED') {
        sidebar.value.error = status.message || '上次分析未完成，可以重新提交'
      }
    } catch (error) {
      console.warn('Previous analysis unavailable', error)
    }
  }

  const showDemoResult = () => {
    sidebar.value.mode = 'result'
    sidebar.value.loading = false
    sidebar.value.content = DEMO_RESULT
    if (!sidebar.value.plan) sidebar.value.plan = DEMO_PLAN
    sidebar.value.trace = DEMO_TRACE
    sidebar.value.evaluation = DEMO_EVALUATION
  }

  const submitAgent = () => {
    const goal = sidebar.value.goal.trim()
    if (!goal) return
    sidebar.value.error = ''
    saveGoalDraft(sidebar.value.mediaId, goal)
    if (demoMode) {
      sidebar.value.mode = 'result'
      sidebar.value.loading = true
      sidebar.value.plan = DEMO_PLAN
      sidebar.value.trace = DEMO_TRACE
      setTimeout(showDemoResult, 450)
      return
    }
    analyze(sidebar.value.mediaId, goal)
  }

  const startNewAnalysis = () => {
    sidebar.value.mode = 'compose'
    sidebar.value.loading = false
    sidebar.value.error = ''
  }

  const startPlanEdit = () => {
    sidebar.value.planDraft = [...(sidebar.value.plan?.tasks || [])]
    sidebar.value.editingPlan = true
  }

  const cancelPlanEdit = () => {
    sidebar.value.editingPlan = false
    sidebar.value.planDraft = []
  }

  const addPlanTask = () => {
    if (sidebar.value.planDraft.length < 5) sidebar.value.planDraft.push('')
  }

  const removePlanTask = index => {
    if (sidebar.value.planDraft.length > 1) sidebar.value.planDraft.splice(index, 1)
  }

  const rerunWithPlan = async () => {
    const tasks = sidebar.value.planDraft.map(task => task.trim()).filter(Boolean)
    if (!tasks.length || tasks.length > 5) {
      showMessage('计划需保留 1 至 5 个有效任务', true)
      return
    }
    if (demoMode) {
      sidebar.value.plan = { ...DEMO_PLAN, tasks }
      cancelPlanEdit()
      sidebar.value.loading = true
      setTimeout(showDemoResult, 450)
      return
    }

    sidebar.value.rerunLoading = true
    try {
      const response = await apiRequest('/analysis/agent-revise', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mediaId: sidebar.value.mediaId,
          goal: sidebar.value.goal,
          correctedTasks: tasks,
          comment: '用户调整 Planner 任务后重新执行'
        })
      })
      const message = await response.text()
      if (!response.ok) throw new Error(message || '重新提交失败')
      sidebar.value.plan = { ...sidebar.value.plan, tasks }
      cancelPlanEdit()
      sidebar.value.content = ''
      sidebar.value.loading = true
      startTaskStream(sidebar.value.mediaId, 'ai', sidebar.value.goal)
    } catch (error) {
      showMessage(error.message || '重新提交失败', true)
    } finally {
      sidebar.value.rerunLoading = false
    }
  }

  const submitFollowUp = async () => {
    const question = sidebar.value.followUp.trim()
    if (!question) return
    if (demoMode) {
      sidebar.value.content += `\n\n## 追问\n${question}\n\n根据 08:42 的讲解，迭代写法使用显式栈保存待访问节点，时间复杂度仍为 O(n)，额外空间复杂度为 O(h)。`
      sidebar.value.followUp = ''
      return
    }

    sidebar.value.followUpLoading = true
    try {
      const params = new URLSearchParams({ id: String(sidebar.value.mediaId), question })
      const response = await apiRequest(`/analysis/follow-up?${params}`, { method: 'POST' })
      const answer = await response.text()
      if (!response.ok) throw new Error(answer || '追问失败')
      sidebar.value.content += `\n\n## 追问\n${question}\n\n${answer}`
      sidebar.value.followUp = ''
    } catch (error) {
      showMessage(`❌ ${error.message}`, true)
    } finally {
      sidebar.value.followUpLoading = false
    }
  }

  const sendFeedback = async rating => {
    if (demoMode) {
      sidebar.value.feedback = rating
      showMessage('演示反馈已记录')
      return
    }
    try {
      const response = await apiRequest('/analysis/agent-feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mediaId: sidebar.value.mediaId, goal: sidebar.value.goal, rating })
      })
      if (!response.ok) throw new Error(await response.text())
      sidebar.value.feedback = rating
      showMessage('反馈已记录')
    } catch (error) {
      showMessage(`❌ ${error.message}`, true)
    }
  }

  return {
    sidebar,
    goalPresets: GOAL_PRESETS,
    traceStages,
    renderedMarkdown,
    transcribe,
    closeSidebar,
    openAgent,
    submitAgent,
    startNewAnalysis,
    showDemoResult,
    startPlanEdit,
    cancelPlanEdit,
    addPlanTask,
    removePlanTask,
    rerunWithPlan,
    submitFollowUp,
    sendFeedback,
    formatPercent: value => `${Math.round((Number(value) || 0) * 100)}%`
  }
}

function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${Math.round(milliseconds)} 毫秒`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} 秒`
}

function goalDraftKey(mediaId) {
  return `dovideo:goal:${mediaId}`
}

function loadGoalDraft(mediaId) {
  try {
    return localStorage.getItem(goalDraftKey(mediaId)) || DEFAULT_GOAL
  } catch {
    return DEFAULT_GOAL
  }
}

function saveGoalDraft(mediaId, goal) {
  if (!mediaId || !goal?.trim()) return
  try {
    localStorage.setItem(goalDraftKey(mediaId), goal.trim())
  } catch {
    // Private browsing can disable storage; the current session still works.
  }
}
