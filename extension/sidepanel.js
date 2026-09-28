import { API_BASE } from './lib/config.js'
import { isValidHttpUrl, isSameVideo, parseVideoUrl, withTimestamp } from './lib/urlParser.js'
import { createSseFrameParser, isTerminalTaskEvent } from './lib/sseParser.js'
import { renderResult } from './lib/markdownView.js'
import { ANALYSIS_STAGES, analysisStageLabelOf, analysisStageOf } from './lib/stageProgress.js'

const TOKEN_KEY = 'authToken'
const DEFAULT_GOAL = '理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议'
const MAX_RECONNECT = 3
const STAGES = ANALYSIS_STAGES

const $ = id => document.getElementById(id)
const els = {
  topStatus: $('top-status'),
  notice: $('notice'),
  loginView: $('login-view'),
  loginForm: $('login-form'),
  loginUsername: $('login-username'),
  loginPassword: $('login-password'),
  loginSubmit: $('login-submit'),
  readyView: $('ready-view'),
  videoUrl: $('video-url'),
  grabBtn: $('grab-btn'),
  goal: $('goal'),
  mode: $('mode'),
  submitBtn: $('submit-btn'),
  runningView: $('running-view'),
  stageList: $('stage-list'),
  statusMessage: $('status-message'),
  result: $('result'),
  backBtn: $('back-btn')
}

let onAuthExpired = () => {}
let streamController = null
let noticeTimer = null
let lastProgressStage = null

async function getToken() {
  const data = await chrome.storage.local.get(TOKEN_KEY)
  return data[TOKEN_KEY] || null
}

async function setToken(token) {
  await chrome.storage.local.set({ [TOKEN_KEY]: token })
}

async function clearToken() {
  await chrome.storage.local.remove(TOKEN_KEY)
}

function isEnvelope(payload) {
  return payload !== null
    && typeof payload === 'object'
    && !Array.isArray(payload)
    && typeof payload.code === 'number'
    && 'message' in payload
}

function dataAsText(data) {
  if (data === null || data === undefined) return ''
  return typeof data === 'string' ? data : JSON.stringify(data)
}

function unwrap(response, envelope) {
  const payload = envelope.data ?? null
  return {
    ok: response.ok,
    status: response.status,
    headers: response.headers,
    json: async () => payload,
    text: async () => (response.ok ? dataAsText(payload) : (envelope.message || ''))
  }
}

async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = await getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  } catch (error) {
    if (error?.name === 'AbortError') throw error
    throw new Error(`无法连接后端服务（${API_BASE}），请先启动 server`, { cause: error })
  }
  if (response.status === 401 && !path.startsWith('/user/')) {
    await clearToken()
    onAuthExpired()
  }

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) return response

  let envelope
  try {
    envelope = await response.clone().json()
  } catch {
    return response
  }
  if (!isEnvelope(envelope)) return response
  return unwrap(response, envelope)
}

async function requestJson(path, options) {
  const response = await apiRequest(path, options)
  if (!response.ok) {
    throw new Error((await response.text()) || `请求失败（HTTP ${response.status}）`)
  }
  return response.json()
}

// 4xx 中除请求超时与限流外都不会自行恢复，继续重连只是空转（同 client 端策略）。
function isTerminalStatus(status) {
  return status >= 400 && status < 500 && status !== 408 && status !== 429
}

function showNotice(text, isError = false) {
  clearTimeout(noticeTimer)
  els.notice.textContent = text
  els.notice.classList.toggle('error', isError)
  els.notice.hidden = false
  if (!isError) {
    noticeTimer = setTimeout(() => {
      els.notice.hidden = true
    }, 4000)
  }
}

function showView(name) {
  els.loginView.hidden = name !== 'login'
  els.readyView.hidden = name !== 'ready'
  els.runningView.hidden = name !== 'running'
}

function setBusy(busy) {
  els.submitBtn.disabled = busy
  els.grabBtn.disabled = busy
  els.loginSubmit.disabled = busy
}

function renderStageList() {
  els.stageList.replaceChildren(...STAGES.map(([key, label]) => {
    const li = document.createElement('li')
    li.dataset.stage = key
    li.textContent = label
    return li
  }))
}

function markStages(currentStage, failed = false) {
  const currentIndex = STAGES.findIndex(([key]) => key === currentStage)
  for (const li of els.stageList.children) {
    const index = STAGES.findIndex(([key]) => key === li.dataset.stage)
    li.classList.toggle('done', !failed && currentIndex >= 0 && index < currentIndex)
    li.classList.toggle('active', !failed && index === currentIndex)
    li.classList.toggle('failed', failed && index === currentIndex)
  }
}

function resetRunningView() {
  els.statusMessage.textContent = ''
  els.result.replaceChildren()
  els.result.className = ''
  els.backBtn.hidden = true
  lastProgressStage = null
  markStages(null)
  setTopStatus('')
}

function stopStream() {
  streamController?.abort()
  streamController = null
}

function setTopStatus(text) {
  els.topStatus.textContent = text
}

function finishRunning({ failed, text }) {
  els.result.replaceChildren()
  if (failed) {
    // 失败信息是后端给的纯文本，直接作为文本节点放入，不做 Markdown 解析。
    els.result.textContent = text
  } else {
    els.result.append(renderResult(text, { onSeek: seekTo }))
  }
  els.result.className = `visible${failed ? ' failed' : ''}`
  els.statusMessage.textContent = ''
  els.backBtn.hidden = false
  setBusy(false)
  setTopStatus(failed ? '分析失败' : '分析完成')
}

/**
 * 点击结果里的时间戳胶囊时，把当前标签页跳到视频对应位置。
 * 跳转前先确认当前页确实是本次分析的那个视频 —— 分析期间用户可能已经切到别的页面，
 * 在无关页面上做 t 参数跳转既无意义也可能覆盖用户正在看的内容。
 */
async function seekTo(seconds) {
  const source = els.videoUrl.value.trim()
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  if (!tab?.url || !isSameVideo(tab.url, source)) {
    showNotice('当前标签页不是本次分析的视频，无法跳转', true)
    return
  }
  const target = withTimestamp(tab.url, seconds)
  if (!target) {
    showNotice('无法为该地址生成跳转链接', true)
    return
  }
  await chrome.tabs.update(tab.id, { url: target })
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  const parser = createSseFrameParser()
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      const text = decoder.decode(value || new Uint8Array(), { stream: !done })
      for (const event of parser.push(text)) {
        await onEvent(event)
        if (isTerminalTaskEvent(event)) return true
      }
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}

function handleTaskEvent(event) {
  const { state, stage, message, result } = event
  const progressStage = analysisStageOf(stage)
  if (progressStage) {
    lastProgressStage = progressStage
    markStages(progressStage)
  }
  if (state === 'COMPLETED') {
    for (const li of els.stageList.children) {
      li.classList.remove('active', 'failed')
      li.classList.add('done')
    }
    stopStream()
    finishRunning({
      failed: false,
      text: typeof result === 'string' ? result : JSON.stringify(result, null, 2)
    })
  } else if (state === 'FAILED') {
    if (lastProgressStage) markStages(lastProgressStage, true)
    stopStream()
    finishRunning({ failed: true, text: message || '分析失败，请稍后重试' })
  } else {
    els.statusMessage.textContent = message
      || (state === 'QUEUED' ? '任务已受理，排队中…' : '分析进行中…')
    const stageLabel = analysisStageLabelOf(stage)
    setTopStatus(stageLabel || '处理中…')
  }
}

function sleep(delay, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise(resolve => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })

    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}

function startStream(mediaId, goal, mode) {
  stopStream()
  const controller = new AbortController()
  streamController = controller
  let attempt = 0
  const params = new URLSearchParams({ id: String(mediaId), goal, mode })

  const run = async () => {
    while (!controller.signal.aborted && attempt < MAX_RECONNECT) {
      try {
        const response = await apiRequest(`/analysis/analysis-events?${params}`, {
          headers: { Accept: 'text/event-stream' },
          signal: controller.signal
        })
        if (!response.ok) {
          const error = new Error(
            (await response.text()) || `事件流连接失败（HTTP ${response.status}）`)
          if (isTerminalStatus(response.status)) {
            finishRunning({ failed: true, text: error.message })
            return
          }
          throw error
        }
        if (!response.body) throw new Error('服务端未返回事件流')
        const terminal = await consumeStream(response.body, handleTaskEvent, controller.signal)
        if (terminal) return
      } catch (error) {
        if (controller.signal.aborted || error?.name === 'AbortError') return
      }
      const delay = Math.min(15000, 1000 * 2 ** attempt++)
      els.statusMessage.textContent = `连接中断，${Math.round(delay / 1000)} 秒后重连（第 ${attempt} 次）…`
      await sleep(delay, controller.signal)
    }
    if (!controller.signal.aborted) {
      finishRunning({ failed: true, text: '事件流多次重连失败，请稍后重新提交' })
    }
  }

  run()
}

async function prefillFromActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  if (!tab?.url) return null
  const parsed = parseVideoUrl(tab.url)
  return parsed.ok ? parsed.url : null
}

async function enterReadyView({ silentPrefill = false } = {}) {
  showView('ready')
  if (!els.goal.value) els.goal.value = DEFAULT_GOAL
  const url = await prefillFromActiveTab()
  if (url) {
    els.videoUrl.value = url
    if (!silentPrefill) showNotice('已抓取当前页视频链接')
  } else if (!silentPrefill) {
    showNotice('未能识别当前页面的视频链接，请手动粘贴', true)
  }
}

async function submitAnalysis() {
  const url = els.videoUrl.value.trim()
  const goal = els.goal.value.trim()
  if (!isValidHttpUrl(url)) {
    showNotice('请填写合法的 http/https 视频链接', true)
    return
  }
  if (!goal) {
    showNotice('请填写分析目标', true)
    return
  }
  setBusy(true)
  showView('running')
  resetRunningView()
  setTopStatus('处理中…')
  els.statusMessage.textContent = '正在登记视频链接…'
  try {
    const form = new FormData()
    form.append('url', url)
    const media = await requestJson('/media/upload-url', { method: 'POST', body: form })
    const mediaId = media?.id
    if (!mediaId) throw new Error('后端未返回视频 ID，无法提交分析')

    els.statusMessage.textContent = '正在提交分析任务…'
    let mode = els.mode.value || 'AUTO'
    if (mode === 'AUTO') {
      try {
        const decision = await requestJson('/analysis/route', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ goal })
        })
        mode = decision?.mode || 'GENERAL'
      } catch {
        // 路由不可用时回退通用模式，不阻断分析（与 README 行为一致）。
        mode = 'GENERAL'
      }
    }

    const params = new URLSearchParams({ id: String(mediaId), goal, mode })
    const response = await apiRequest(`/analysis/ai?${params}`, { method: 'POST' })
    const message = await response.text()
    if (response.ok || response.status === 409) {
      // 202 受理 / 200 已有结果 / 409 同任务进行中，都通过 SSE 拿阶段与终态。
      els.statusMessage.textContent = '任务已提交，正在等待 Agent 流水线…'
      startStream(mediaId, goal, mode)
    } else {
      showView('ready')
      setBusy(false)
      showNotice(message || '提交失败', true)
    }
  } catch (error) {
    showView('ready')
    setBusy(false)
    showNotice(error.message || String(error), true)
  }
}

async function handleLogin(event) {
  event.preventDefault()
  const username = els.loginUsername.value.trim()
  const password = els.loginPassword.value
  if (!username || !password) {
    showNotice('请输入用户名和密码', true)
    return
  }
  setBusy(true)
  try {
    const data = await requestJson('/user/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    if (!data?.token) throw new Error('登录接口未返回有效令牌')
    await setToken(data.token)
    await enterReadyView({ silentPrefill: true })
  } catch (error) {
    showNotice(error.message || String(error), true)
  } finally {
    setBusy(false)
  }
}

async function init() {
  renderStageList()
  onAuthExpired = () => {
    stopStream()
    setBusy(false)
    showView('login')
    showNotice('登录已过期，请重新登录', true)
  }

  els.loginForm.addEventListener('submit', handleLogin)
  els.grabBtn.addEventListener('click', () => enterReadyView())
  els.submitBtn.addEventListener('click', submitAnalysis)
  els.backBtn.addEventListener('click', () => {
    stopStream()
    setBusy(false)
    showView('ready')
  })

  if (await getToken()) {
    await enterReadyView({ silentPrefill: true })
  } else {
    showView('login')
  }
}

init()
