<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>
    <div class="ambient-glow"></div>

    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <span class="brand-do">DO</span>
          <span class="brand-video">Video</span>
          <span class="beta-badge">PRO</span>
        </div>

        <div class="nav-controls">
          <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            </span>
            登录 / 注册
          </button>

          <div v-else class="user-profile">
            <span class="user-name">:: {{ currentUser.nickname }} ::</span>
            <button class="logout-btn" @click="logout" title="退出登录">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
            </button>
          </div>

          <div class="status-pill" :class="{ 'is-active': uploading }">
            <div class="status-dot"></div>
            <span class="status-text">{{ uploading ? '数据传输中...' : '系统就绪' }}</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="hero-section">
        <h1 class="slogan-main">DECODE YOUR VIDEO</h1>
        <p class="slogan-sub">影视重构 · 算力赋能</p>

        <div class="upload-wrapper">
          <input
              type="file"
              id="file-input"
              @change="handleFileChange"
              accept="video/*"
              hidden
          />

          <div
              class="upload-magnet"
              :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="isDragOver = false"
              @drop.prevent="handleDrop"
          >
            <div class="split-container" v-if="!uploading">

              <label for="file-input" class="skew-pane pane-local">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                  </div>
                  <span class="magnet-title">LOCAL FILE</span>
                  <span class="magnet-desc">{{ isDragOver ? '松手上传' : '点击 / 拖拽本地文件' }}</span>
                </div>
              </label>

              <div class="split-gap"></div>

              <div class="skew-pane pane-url">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
                  </div>
                  <span class="magnet-title">WEB LINK</span>
                  <span class="magnet-desc">B站 / YouTube / 抖音</span>

                  <div class="url-input-box" @click.stop>
                    <input
                        v-model="videoUrl"
                        type="text"
                        placeholder="粘贴视频链接..."
                        @keyup.enter="handleUrlUpload"
                    />
                    <button class="url-go-btn" @click="handleUrlUpload">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                    </button>
                  </div>
                </div>
              </div>

            </div>

            <div class="magnet-content busy" v-else>
              <div class="quantum-loader"></div>
              <span class="busy-text">正在建立通道并解析资源...</span>
            </div>

            <div class="border-glow"></div>
          </div>
        </div>
        <transition name="toast-pop">
          <div v-if="message" class="notification-bar" :class="{ 'error': messageIsError }">
            {{ message }}
          </div>
        </transition>
      </section>

      <section v-if="list.length > 0" class="workspace-section">
        <div class="section-header"><h3>工作台</h3><div class="count-chip">{{ list.length }} TASKS</div></div>
        <div class="card-grid">
          <div v-for="item in list" :key="item.id" class="project-card">

            <button class="delete-btn" @click.stop="deleteItem(item)" title="删除此项">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
            <div class="card-meta">
              <div class="meta-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              </div>
              <div class="meta-info">
                <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                <div class="meta-tags">
                  <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                  <span class="status-indicator" :class="item.status.toLowerCase()">
                    {{ item.status === 'COMPLETED' ? 'READY' : 'PROCESSING' }}
                  </span>
                </div>
              </div>
            </div>

            <div class="action-dock">
              <button class="dock-item" @click="downloadAudio(item)">
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                </span>
                <span class="item-label">下载音频</span>
              </button>

              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="transcribe(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                </span>
                <span class="item-label">提取文字</span>
              </button>

              <button
                  class="dock-item ai-core"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="openAgent(item)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                </span>
                <div class="label-group">
                  <span class="item-label">Video Agent</span>
                </div>
                <div class="shimmer"></div>
              </button>
            </div>
          </div>
        </div>
      </section>

      <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
      <div class="sidebar-panel" :class="{ 'is-open': sidebar.visible }">
        <div class="sidebar-header">
          <div class="sidebar-title">
            <span class="icon" v-if="sidebar.type === 'ai'">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
            </span>
            <span class="icon" v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            </span>
            {{ sidebar.title }}
          </div>
          <button class="close-btn" @click="closeSidebar">×</button>
        </div>
        <div class="sidebar-body">
          <div v-if="sidebar.type === 'ai' && sidebar.mode === 'compose'" class="agent-composer">
            <p class="agent-caption">告诉 Agent 你希望从视频中得到什么产物</p>
            <textarea v-model="sidebar.goal" maxlength="500" placeholder="例如：梳理核心观点，给出带时间戳的证据和可执行建议"></textarea>
            <div class="goal-presets">
              <button v-for="preset in goalPresets" :key="preset" @click="sidebar.goal = preset">{{ preset }}</button>
            </div>
            <button class="agent-run-btn" :disabled="!sidebar.goal.trim()" @click="submitAgent">开始分析</button>
          </div>

          <div v-else-if="sidebar.loading" class="agent-running">
            <div class="loading-state"><div class="quantum-loader small"></div><p>Agent 正在分析视频证据...</p></div>
            <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
              <span class="meta-label">任务计划</span>
              <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
            </div>
            <div v-if="traceStages.length" class="agent-meta-block">
              <span class="meta-label">已完成阶段</span>
              <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}ms</span></div>
            </div>
          </div>

          <div v-else>
            <div v-if="sidebar.type === 'ai'">
              <div class="markdown-content" v-html="renderedMarkdown"></div>
              <div v-if="sidebar.plan?.tasks?.length || traceStages.length" class="agent-inspector">
                <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
                  <span class="meta-label">Planner 任务</span>
                  <div v-if="sidebar.editingPlan" class="plan-editor">
                    <div v-for="(_, index) in sidebar.planDraft" :key="index" class="plan-editor-row">
                      <input v-model="sidebar.planDraft[index]" maxlength="500" :aria-label="`任务 ${index + 1}`" />
                      <button type="button" title="删除任务" @click="removePlanTask(index)">×</button>
                    </div>
                    <button v-if="sidebar.planDraft.length < 8" type="button" @click="addPlanTask">添加任务</button>
                    <div class="plan-editor-actions">
                      <button type="button" @click="cancelPlanEdit">取消</button>
                      <button type="button" :disabled="sidebar.rerunLoading" @click="rerunWithPlan">
                        {{ sidebar.rerunLoading ? '提交中' : '按新计划重跑' }}
                      </button>
                    </div>
                  </div>
                  <template v-else>
                    <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
                    <button type="button" class="plan-edit-trigger" @click="startPlanEdit">调整计划</button>
                  </template>
                </div>
                <div v-if="traceStages.length" class="agent-meta-block">
                  <span class="meta-label">执行轨迹</span>
                  <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}ms</span></div>
                </div>
                <div v-if="sidebar.evaluation && Object.keys(sidebar.evaluation).length" class="quality-row">
                  <span>结构完整 {{ sidebar.evaluation.structuredValid ? '通过' : '待完善' }}</span>
                  <span>证据支持 {{ formatPercent(sidebar.evaluation.evidenceSupportRate) }}</span>
                  <span>Critic {{ sidebar.evaluation.criticPassed ? '通过' : '达到轮次上限' }}</span>
                </div>
              </div>
              <div class="follow-up-box">
                <textarea v-model="sidebar.followUp" maxlength="500" placeholder="基于视频继续追问..."></textarea>
                <button :disabled="sidebar.followUpLoading || !sidebar.followUp.trim()" @click="submitFollowUp">
                  {{ sidebar.followUpLoading ? '分析中' : '追问' }}
                </button>
              </div>
              <div class="feedback-row">
                <span>这个结果有帮助吗？</span>
                <button :class="{ active: sidebar.feedback === 1 }" @click="sendFeedback(1)" title="有帮助">赞</button>
                <button :class="{ active: sidebar.feedback === -1 }" @click="sendFeedback(-1)" title="需改进">踩</button>
              </div>
            </div>
            <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>
          </div>
        </div>
      </div>

      <div v-if="showAuthModal" class="auth-backdrop">
        <div class="auth-panel">
          <div class="auth-header">
            <h2 class="auth-title">{{ authMode === 'login' ? '用户登录' : '新用户注册' }}</h2>
            <button class="close-btn" @click="closeAuthModal">×</button>
          </div>
          <div class="auth-body">
            <div class="input-group">
              <label>USERNAME</label>
              <input v-model="authForm.username" type="text" placeholder="输入账号" />
            </div>
            <div class="input-group">
              <label>PASSWORD</label>
              <input v-model="authForm.password" type="password" placeholder="输入密码" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label>NICKNAME (昵称)</label>
              <input v-model="authForm.nickname" type="text" placeholder="设置一个好听的名字" />
            </div>
            <div class="auth-action">
              <button class="cyber-btn" @click="handleAuth" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? '立即登录' : '提交注册' }}</span>
                <span v-else>请求处理中...</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? '没有账号?' : '已有账号?' }}</span>
              <button class="toggle-link" @click="switchAuthMode">{{ authMode === 'login' ? '去注册' : '去登录' }}</button>
            </div>
            <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { apiRequest, clearAuthToken, hasAuthToken, setAuthToken } from './api'
import { uploadVideoInChunks } from './chunkUpload'
import { DEMO_EVALUATION, DEMO_ITEM, DEMO_PLAN, DEMO_RESULT, DEMO_TRACE } from './demoData'
import { renderMarkdown } from './markdown'
import { createTaskStreams } from './taskEvents'

// --- 变量定义 ---
const DEMO_MODE = new URLSearchParams(window.location.search).has('demo')
const DEFAULT_GOAL = '理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议'
const goalPresets = ['生成学习笔记', '提炼会议结论', '梳理操作步骤']
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const messageIsError = ref(false)
const uploading = ref(false)
const list = ref([])
const isDragOver = ref(false)
const sidebar = ref({
  visible: false,
  type: 'ai',
  mode: 'compose',
  title: '',
  content: '',
  loading: false,
  mediaId: null,
  goal: DEFAULT_GOAL,
  followUp: '',
  followUpLoading: false,
  plan: null,
  trace: null,
  evaluation: null,
  feedback: null,
  editingPlan: false,
  planDraft: [],
  rerunLoading: false
})
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })
const taskStreams = createTaskStreams()
const traceStages = computed(() => Object.entries(sidebar.value.trace?.stageDurationMs || {}))

const renderedMarkdown = computed(() => renderMarkdown(sidebar.value.content))

// --- 核心业务逻辑 ---

const handleFileChange = async (e) => {
  if (!currentUser.value) {
    e.target.value = ''
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  if (!selectedFile.type.startsWith('video/')) {
    e.target.value = ''
    showMsg('⚠️ 仅支持上传视频文件', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleDrop = async (e) => {
  isDragOver.value = false
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return
  const selectedFile = droppedFiles[0]
  if (!selectedFile.type.startsWith('video/')) {
    showMsg('⚠️ 仅支持上传视频文件', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const uploadFile = async () => {
  if (!file.value) return
  if (DEMO_MODE) {
    showMsg('演示模式：已模拟完成分片上传')
    return
  }
  uploading.value = true

  try {
    await uploadVideoInChunks(file.value, progress => {
      messageIsError.value = false
      message.value = progress.phase === 'merging'
        ? '分片上传完成，正在合并文件...'
        : `正在上传分片 ${progress.completedChunks}/${progress.totalChunks}...`
    })
    showMsg('✅ 本地上传完成')
    fetchList()
  } catch (error) {
    console.error(error)
    showMsg('❌ 上传失败: ' + error.message, true)
  } finally {
    uploading.value = false
  }
}

const handleUrlUpload = async () => {
  if (!videoUrl.value) return
  if (DEMO_MODE) {
    videoUrl.value = ''
    showMsg('演示模式：已模拟完成链接解析')
    return
  }

  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }

  let parsedUrl
  try {
    parsedUrl = new URL(videoUrl.value)
  } catch {
    parsedUrl = null
  }
  if (!parsedUrl || !['http:', 'https:'].includes(parsedUrl.protocol)) {
    showMsg('⚠️ 请输入合法的 http/https 链接', true)
    return
  }

  uploading.value = true
  messageIsError.value = false
  message.value = '正在解析链接并极速下载 (低码率模式)...'

  const formData = new FormData()
  formData.append('url', videoUrl.value)

  try {
    const res = await apiRequest('/media/upload-url', {
      method: 'POST',
      body: formData
    })
    const text = await res.text()
    if (!res.ok) throw new Error(text)

    showMsg('✅ 链接资源已入库')
    videoUrl.value = ''
    fetchList()
  } catch (error) {
    console.error(error)
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = "不支持该平台链接"
    showMsg('❌ 解析失败: ' + errMsg, true)
  } finally {
    uploading.value = false
  }
}

const showMsg = (msg, isError = false) => {
  message.value = msg
  messageIsError.value = isError
  setTimeout(() => {
    if (message.value === msg) {
      message.value = ''
      messageIsError.value = false
    }
  }, 4000)
}

const fetchList = async () => {
  if (DEMO_MODE) return
  try {
    let url = '/media/list'
    if (currentUser.value) {
      const timestamp = new Date().getTime()
      url += `?_t=${timestamp}`

      const res = await apiRequest(url)
      if (!res.ok) throw new Error('加载视频列表失败')
      const data = await res.json()
      list.value = data
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

const deleteItem = async (item) => {
  if (DEMO_MODE) {
    list.value = list.value.filter(i => i.id !== item.id)
    showMsg('演示任务已移除')
    return
  }
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return
  try {
    const res = await apiRequest(`/media/delete?id=${item.id}`, { method: 'DELETE' })
    const text = await res.text()
    if (text === '删除成功') {
      showMsg('文件已销毁')
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('❌ 删除请求失败', true)
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  if (DEMO_MODE) {
    showMsg(`演示模式：${item.filename} 音频已准备`)
    return
  }
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('正在转码并下载...')
    const res = await apiRequest(`/analysis/download?id=${item.id}`)
    if(!res.ok) throw new Error("Fail")
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ 下载完成')
  } catch (e) {
    alert("下载失败")
  }
}

const transcribe = async (id) => {
  const item = list.value.find(i => i.id === id)
  if (DEMO_MODE) {
    openSidebar('text', 'ASR 转写结果')
    sidebar.value.content = item?.transcriptText || DEMO_ITEM.transcriptText
    sidebar.value.loading = false
    return
  }
  if (taskStreams.has(id, 'text')) {
    openSidebar('text', '全量文字提取')
    sidebar.value.mediaId = id
    sidebar.value.loading = true
    sidebar.value.content = "📝 文字提取正在后台进行中..."
    return
  }
  openSidebar('text', '全量文字提取')
  sidebar.value.mediaId = id
  sidebar.value.loading = true
  sidebar.value.content = "📝 提取任务已提交，正在识别语音流..."
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
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

const aiAnalyze = async (id, goal) => {
  if (taskStreams.has(id, 'ai')) {
    sidebar.value.mode = 'result'
    sidebar.value.loading = true
    return
  }

  sidebar.value.loading = true
  sidebar.value.mode = 'result'
  sidebar.value.content = ''

  try {
    const params = new URLSearchParams({ id: String(id), goal })
    const res = await apiRequest(`/analysis/ai?${params}`, { method: 'POST' })
    const text = await res.text()
    if (!res.ok) {
      showMsg(text, true)
      sidebar.value.loading = false
      sidebar.value.content = text
      return
    }

    startTaskStream(id, 'ai', goal)
    refreshAgentMeta(id, goal, false)

  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

const startTaskStream = (id, type, goal = '') => {
  const finish = async (result, failed = false) => {
    if (sidebar.value.visible && sidebar.value.type === type && sidebar.value.mediaId === id) {
      sidebar.value.content = result
      sidebar.value.loading = false
      if (type === 'ai' && !failed) await refreshAgentMeta(id, goal, true)
    }
    showMsg(failed ? '任务执行失败，请稍后重试' : '任务完成', failed)
    taskStreams.stop(id, type)
  }

  const params = new URLSearchParams({ id: String(id) })
  if (type === 'ai') params.set('goal', goal)
  const path = type === 'ai'
    ? `/analysis/analysis-events?${params}`
    : `/analysis/transcription-events?${params}`

  taskStreams.start(id, type, path, async status => {
    if (sidebar.value.mediaId === id && sidebar.value.type === type && status.message) {
      sidebar.value.content = status.state === 'PROCESSING' || status.state === 'QUEUED'
        ? status.message
        : sidebar.value.content
    }
    if (type === 'ai' && status.stage && sidebar.value.mediaId === id) {
      await refreshAgentMeta(id, goal, false)
    }
    if (status.state === 'COMPLETED') {
      await fetchList()
      await finish(status.result || (type === 'ai' ? '分析完成' : ''))
    } else if (status.state === 'FAILED') {
      await finish(status.message || '任务执行失败', true)
    }
  }, error => {
    console.warn('task event stream reconnecting', error)
  })
}

const openSidebar = (type, title) => {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}
const closeSidebar = () => { sidebar.value.visible = false }

const openAgent = (item) => {
  sidebar.value = {
    visible: true,
    type: 'ai',
    mode: 'compose',
    title: `Video Agent · ${item.filename}`,
    content: '',
    loading: false,
    mediaId: item.id,
    goal: DEFAULT_GOAL,
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

const submitAgent = () => {
  const goal = sidebar.value.goal.trim()
  if (!goal) return
  if (DEMO_MODE) {
    sidebar.value.mode = 'result'
    sidebar.value.loading = true
    sidebar.value.plan = DEMO_PLAN
    sidebar.value.trace = DEMO_TRACE
    setTimeout(showDemoResult, 450)
    return
  }
  aiAnalyze(sidebar.value.mediaId, goal)
}

const showDemoResult = () => {
  sidebar.value.mode = 'result'
  sidebar.value.loading = false
  sidebar.value.content = DEMO_RESULT
  if (!sidebar.value.plan) sidebar.value.plan = DEMO_PLAN
  sidebar.value.trace = DEMO_TRACE
  sidebar.value.evaluation = DEMO_EVALUATION
}

const refreshAgentMeta = async (id, goal, includeEvaluation) => {
  const params = new URLSearchParams({ id: String(id), goal })
  try {
    const requests = [
      apiRequest(`/analysis/agent-plan?${params}`),
      apiRequest(`/analysis/agent-trace?id=${id}`)
    ]
    if (includeEvaluation) requests.push(apiRequest(`/analysis/agent-evaluation?${params}`))
    const responses = await Promise.all(requests)
    if (sidebar.value.mediaId !== id) return
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

const startPlanEdit = () => {
  sidebar.value.planDraft = [...(sidebar.value.plan?.tasks || [])]
  sidebar.value.editingPlan = true
}

const cancelPlanEdit = () => {
  sidebar.value.editingPlan = false
  sidebar.value.planDraft = []
}

const addPlanTask = () => {
  if (sidebar.value.planDraft.length < 8) sidebar.value.planDraft.push('')
}

const removePlanTask = (index) => {
  if (sidebar.value.planDraft.length > 1) sidebar.value.planDraft.splice(index, 1)
}

const rerunWithPlan = async () => {
  const tasks = sidebar.value.planDraft.map(task => task.trim()).filter(Boolean)
  if (!tasks.length || tasks.length > 8) {
    showMsg('计划需保留 1 至 8 个有效任务', true)
    return
  }
  if (DEMO_MODE) {
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
    sidebar.value.editingPlan = false
    sidebar.value.planDraft = []
    sidebar.value.content = ''
    sidebar.value.loading = true
    startTaskStream(sidebar.value.mediaId, 'ai', sidebar.value.goal)
  } catch (error) {
    showMsg(error.message || '重新提交失败', true)
  } finally {
    sidebar.value.rerunLoading = false
  }
}

const submitFollowUp = async () => {
  const question = sidebar.value.followUp.trim()
  if (!question) return
  if (DEMO_MODE) {
    sidebar.value.content += `\n\n## 追问\n${question}\n\n根据 08:42 的讲解，迭代写法使用显式栈保存待访问节点，时间复杂度仍为 O(n)，额外空间复杂度为 O(h)。`
    sidebar.value.followUp = ''
    return
  }
  sidebar.value.followUpLoading = true
  try {
    const params = new URLSearchParams({ id: String(sidebar.value.mediaId), question })
    const res = await apiRequest(`/analysis/follow-up?${params}`, { method: 'POST' })
    const answer = await res.text()
    if (!res.ok) throw new Error(answer || '追问失败')
    sidebar.value.content += `\n\n## 追问\n${question}\n\n${answer}`
    sidebar.value.followUp = ''
  } catch (error) {
    showMsg(`❌ ${error.message}`, true)
  } finally {
    sidebar.value.followUpLoading = false
  }
}

const sendFeedback = async (rating) => {
  if (DEMO_MODE) {
    sidebar.value.feedback = rating
    showMsg('演示反馈已记录')
    return
  }
  try {
    const res = await apiRequest('/analysis/agent-feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mediaId: sidebar.value.mediaId, goal: sidebar.value.goal, rating })
    })
    if (!res.ok) throw new Error(await res.text())
    sidebar.value.feedback = rating
    showMsg('反馈已记录')
  } catch (error) {
    showMsg(`❌ ${error.message}`, true)
  }
}

const formatPercent = (value) => `${Math.round((Number(value) || 0) * 100)}%`

const openAuthModal = () => {
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}
const closeAuthModal = () => { showAuthModal.value = false }
const switchAuthMode = () => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authMessage.value = ''
}
const handleAuth = async () => {
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = '请输入完整的账号和密码'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''
  const endpoint = authMode.value === 'login' ? '/user/login' : '/user/register'
  try {
    const res = await apiRequest(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    const data = await res.json()
    if (data.code === 200) {
      if (authMode.value === 'login') {
        currentUser.value = data.userInfo
        localStorage.setItem('user', JSON.stringify(data.userInfo))
        setAuthToken(data.token)
        closeAuthModal()
        showMsg(`欢迎回来，${data.userInfo.nickname}`)
        fetchList()
      } else {
        authMessage.value = '注册成功，请直接登录'
        authError.value = false
        setTimeout(() => switchAuthMode(), 1000)
      }
    } else {
      authMessage.value = data.msg || '操作失败'
      authError.value = true
    }
  } catch (e) {
    console.error(e)
    authMessage.value = '网络连接错误'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}
const logout = () => {
  if (hasAuthToken()) {
    apiRequest('/user/logout', { method: 'POST' }).catch(() => {})
  }
  taskStreams.stopAll()
  currentUser.value = null
  localStorage.removeItem('user')
  clearAuthToken()
  list.value = []
  showMsg('已退出系统')
}

const handleAuthExpired = () => {
  taskStreams.stopAll()
  currentUser.value = null
  list.value = []
  sidebar.value.visible = false
  localStorage.removeItem('user')
  showMsg('登录状态已失效，请重新登录', true)
  openAuthModal()
}

onMounted(() => {
  window.addEventListener('auth-expired', handleAuthExpired)
  if (DEMO_MODE) {
    currentUser.value = { id: 1, nickname: 'Agent Demo' }
    list.value = [DEMO_ITEM]
    openAgent(DEMO_ITEM)
    showDemoResult()
    return
  }
  const savedUser = localStorage.getItem('user')
  if (savedUser && hasAuthToken()) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch(e) {}
  }
  fetchList()
})
onUnmounted(() => {
  window.removeEventListener('auth-expired', handleAuthExpired)
  taskStreams.stopAll()
})
</script>

<style>
/* 确保字体引用在最上方 */
@import url('https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@300;500;700&family=Syncopate:wght@700&display=swap');

:root {
  --bg-deep: #0b0c10;
  --bg-card: #121418;
  --accent-lime: #c5f946;
  --accent-purple: #8a2be2;
  --text-main: #e0e0e0;
  --text-sub: #71757a;
  --text-inverse: #0b0c10;
  --border-tech: #2a2d35;
  --shadow-float: 0 10px 30px -10px rgba(0, 0, 0, 0.7);
  --shadow-glow-lime: 0 0 20px rgba(197, 249, 70, 0.2);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important; padding: 0 !important; width: 100vw !important;
  max-width: 100vw !important; min-height: 100vh !important;
  overflow-x: hidden; background-color: var(--bg-deep);
}

.app-stage { position: relative; z-index: 1; width: 100%; min-height: 100vh; color: var(--text-main); font-family: 'Space Grotesk', 'Noto Sans SC', monospace; }

.ambient-noise { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)' opacity='0.05'/%3E%3C/svg%3E"); pointer-events: none; z-index: -1; }
.ambient-glow { position: fixed; top: -20%; left: 20%; width: 60vw; height: 60vh; background: radial-gradient(circle, rgba(197, 249, 70, 0.08) 0%, rgba(11, 12, 16, 0) 70%); pointer-events: none; z-index: -2; }

/* 导航 */
.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1.2rem 0; background: rgba(11, 12, 16, 0.85); backdrop-filter: blur(12px); border-bottom: 1px solid var(--border-tech); }
.nav-content { max-width: 1400px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; }
.brand { display: flex; align-items: baseline; gap: 2px; }
.brand-do { font-family: 'Dela Gothic One', sans-serif; font-size: 1.8rem; color: var(--text-main); letter-spacing: -1px; }
.brand-video { font-family: 'Space Grotesk', sans-serif; font-size: 1.8rem; font-weight: 300; }
.beta-badge { font-size: 0.7rem; font-weight: 700; background: var(--accent-lime); color: var(--text-inverse); padding: 2px 6px; border-radius: 2px; margin-left: 8px; transform: translateY(-4px); box-shadow: 0 0 5px var(--accent-lime); }

.nav-controls { display: flex; align-items: center; gap: 15px; }
.auth-btn { background: transparent; border: 1px solid var(--border-tech); color: var(--accent-lime); padding: 6px 16px; border-radius: 4px; font-family: 'Noto Sans SC', sans-serif; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.3s; font-size: 0.85rem; }
.auth-btn:hover { background: rgba(197, 249, 70, 0.1); border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(197, 249, 70, 0.2); }
.user-profile { display: flex; align-items: center; gap: 10px; font-family: monospace; font-size: 0.9rem; color: var(--text-main); }
.user-name { color: var(--accent-lime); }
.logout-btn { background: none; border: none; color: var(--text-sub); cursor: pointer; padding: 4px; display: flex; align-items: center; transition: color 0.3s; }
.logout-btn:hover { color: #ff4757; }

.status-pill { display: flex; align-items: center; gap: 8px; background: var(--bg-card); padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border-tech); font-size: 0.8rem; color: var(--text-sub); }
.status-dot { width: 6px; height: 6px; background: var(--accent-lime); border-radius: 50%; }
.status-pill.is-active .status-dot { animation: pulse-lime 1.5s infinite alternate; }

/* Hero */
.main-container { max-width: 1200px; margin: 0 auto; padding: 4rem 2rem; }
.hero-section { text-align: center; margin-bottom: 6rem; animation: slideUpFade 0.8s forwards; }
.slogan-main { font-family: 'Syncopate', sans-serif; font-size: clamp(2.5rem, 6vw, 4.5rem); font-weight: 700; margin-bottom: 0.5rem; text-shadow: 0 0 20px rgba(197, 249, 70, 0.2); }
.slogan-sub { font-size: 1.1rem; color: var(--text-sub); letter-spacing: 2px; margin-bottom: 3rem; }

/* === [START] 核心重构：Upload Wrapper (Physical Skew) === */
.upload-wrapper { max-width: 800px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }

.upload-magnet {
  position: relative; height: 300px;
  background: var(--bg-card);
  border-radius: 16px;
  box-shadow: var(--shadow-float);
  border: 2px solid var(--border-tech);
  overflow: hidden; /* 必须隐藏溢出 */
  transition: all 0.3s;
}
.upload-magnet:hover { border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); transform: translateY(-5px); }

/* 容器布局 */
.split-container {
  display: flex; height: 100%; width: 100%;
  position: relative; overflow: hidden;
}

/* 左右面板 (物理倾斜) */
.skew-pane {
  flex: 1; height: 100%; position: relative; cursor: pointer;
  background: rgba(11, 12, 16, 0.5); /* 默认深色底 */
  transition: all 0.4s ease;
  display: flex; align-items: center; justify-content: center;
  z-index: 1;
  /* 核心：直接对容器进行 skew，而不是 clip-path */
  transform: skewX(-10deg);
}

/* 增加左右面板的宽度，确保覆盖边缘 */
.pane-local { margin-left: -20px; padding-right: 20px; border-right: 2px solid var(--accent-lime); }
.pane-url { margin-right: -20px; padding-left: 20px; }

/* 鼠标悬停逻辑：只改变背景色，不加外发光，防止穿模 */
.skew-pane:hover {
  background: rgba(197, 249, 70, 0.05); /* 极淡的绿色背景，限制在斜框内 */
  z-index: 10;
}

/* 中间缝隙 */
.split-gap { width: 4px; background: transparent; transform: skewX(-10deg); }

/* 内容回正 */
.pane-content {
  /* 必须反向 skew 回来，否则文字是斜的 */
  transform: skewX(10deg);
  display: flex; flex-direction: column; align-items: center;
  z-index: 2; transition: transform 0.3s;
}
.skew-pane:hover .pane-content { transform: skewX(10deg) scale(1.05); }

/* 互斥变暗 */
.split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: 0.3; filter: grayscale(1); }

.magnet-icon { color: var(--accent-lime); margin-bottom: 1rem; filter: drop-shadow(0 0 5px var(--accent-lime)); }
.magnet-title { font-size: 1.4rem; font-weight: 700; letter-spacing: 1px; margin-bottom: 5px; font-family: 'Dela Gothic One', sans-serif; }
.magnet-desc { font-size: 0.8rem; color: var(--text-sub); font-family: monospace; }

/* URL 输入框 (需回正) */
.url-input-box {
  display: flex; margin-top: 15px; border-bottom: 2px solid var(--border-tech);
  transition: all 0.3s; position: relative; z-index: 30;
}
.skew-pane:hover .url-input-box { border-color: var(--accent-lime); }
.url-input-box input {
  background: transparent; border: none; outline: none; color: var(--text-main);
  font-family: monospace; padding: 8px 5px; width: 180px; font-size: 0.9rem;
}
.url-go-btn {
  background: transparent; border: none; color: var(--accent-lime); cursor: pointer;
  padding: 0 8px; opacity: 0.7; transition: all 0.3s;
}
.url-go-btn:hover { opacity: 1; transform: translateX(3px); }

/* 处理中状态 */
.magnet-content.busy {
  height: 100%; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bg-card); position: relative; z-index: 50;
}
.busy-text { margin-top: 15px; color: var(--accent-lime); font-family: monospace; animation: pulse-lime 2s infinite; }
/* === [END] 重构结束 === */

.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 4px; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); }
.notification-bar.error { background: #ff4757; color: #fff; }

.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; box-shadow: 0 0 10px var(--accent-lime); }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }

/* Workspace */
.workspace-section { opacity: 0; animation: slideUpFade 0.8s 0.4s forwards; }
.section-header { display: flex; align-items: center; gap: 12px; margin-bottom: 2rem; border-bottom: 2px solid var(--border-tech); padding-bottom: 10px; }
.section-header h3 { font-size: 1.5rem; font-weight: 700; }
.count-chip { background: var(--border-tech); padding: 4px 10px; border-radius: 4px; font-size: 0.75rem; font-family: monospace; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
.project-card { background: var(--bg-card); border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); border: 1px solid var(--border-tech); overflow: hidden; transition: transform 0.2s; position: relative; }
.project-card:hover { transform: translateY(-2px); border-color: var(--accent-lime); }
.card-meta { display: flex; gap: 1.5rem; padding: 1.5rem; align-items: center; border-bottom: 1px solid var(--border-tech); background: rgba(18, 21, 18, 0.5); }
.meta-icon { width: 56px; height: 56px; background: rgba(197, 249, 70, 0.05); border: 1px solid var(--accent-lime); border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--accent-lime); }
.filename-mask { font-size: 1.1rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.meta-tags { display: flex; gap: 12px; font-size: 0.85rem; font-family: monospace; margin-top: 5px; }
.time-tag { color: var(--text-sub); }
.status-indicator { font-weight: 600; padding: 2px 8px; border-radius: 4px; }
.status-indicator.completed { color: var(--accent-lime); border: 1px solid var(--accent-lime); background: rgba(197, 249, 70, 0.1); }
.status-indicator.processing { color: var(--accent-purple); border: 1px solid var(--accent-purple); animation: blink 1s infinite; }

.action-dock { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 12px; padding: 12px; background: rgba(5, 8, 5, 0.5); }
.dock-item { position: relative; border: 1px solid var(--border-tech); background: var(--bg-card); border-radius: 8px; padding: 16px; display: flex; align-items: center; justify-content: center; gap: 10px; cursor: pointer; transition: all 0.3s; color: var(--text-sub); font-family: monospace; overflow: hidden; }
.dock-item:hover:not(:disabled) { color: var(--accent-lime); border-color: var(--accent-lime); background: rgba(197, 249, 70, 0.05); }
.dock-item:disabled { opacity: 0.3; cursor: not-allowed; }
.dock-item.ai-core { border-color: var(--accent-purple); color: var(--accent-purple); }
.dock-item.ai-core .label-group { display: flex; flex-direction: column; align-items: flex-start; z-index: 1; }
.dock-item.ai-core .item-sub { font-size: 0.75rem; color: var(--accent-purple); opacity: 0.8; }
.dock-item.ai-core:hover:not(:disabled) { border-color: var(--accent-lime); color: var(--text-inverse); background: var(--accent-lime); }
.dock-item.ai-core:hover:not(:disabled) .item-sub { color: var(--text-inverse); }

/* Sidebar */
.sidebar-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.6); backdrop-filter: blur(4px); z-index: 998; }
.sidebar-panel { position: fixed; top: 0; right: -600px; width: 550px; max-width: 90vw; height: 100%; background: var(--bg-card); border-left: 2px solid var(--accent-lime); z-index: 999; transition: right 0.4s cubic-bezier(0.19, 1, 0.22, 1); display: flex; flex-direction: column; box-shadow: -10px 0 40px rgba(0,0,0,0.8); }
.sidebar-panel.is-open { right: 0; }
.sidebar-header { padding: 20px 30px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11, 12, 16, 0.9); }
.sidebar-title { font-size: 1.4rem; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 10px; }
.icon { color: var(--accent-lime); display: flex; align-items: center; }
.close-btn { background: none; border: none; color: var(--text-sub); padding: 5px; cursor: pointer; transition: color 0.3s; }
.close-btn:hover { color: var(--accent-lime); }
.sidebar-body { flex: 1; overflow-y: auto; padding: 30px; }
.loading-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--text-sub); gap: 20px; }
.markdown-content, .text-content { line-height: 1.8; color: var(--text-main); font-size: 0.95rem; }
.text-content pre { white-space: pre-wrap; font-family: monospace; background: #000; padding: 15px; border-radius: 8px; border: 1px solid var(--border-tech); color: #ccc; }
.markdown-content h1, .markdown-content h2, .markdown-content h3 { color: var(--accent-lime); margin-top: 1.5em; margin-bottom: 0.5em; font-family: 'Space Grotesk', sans-serif; }
.markdown-content h1 { border-bottom: 1px solid var(--border-tech); padding-bottom: 10px; }
.markdown-content ul { padding-left: 20px; }
.markdown-content li { margin-bottom: 8px; color: #d4d4d8; }
.markdown-content strong { color: var(--accent-lime); font-weight: 700; }
.markdown-content p { margin-bottom: 1em; }

/* Agent workspace */
.agent-composer { display: flex; flex-direction: column; gap: 18px; }
.agent-caption { color: var(--text-sub); line-height: 1.7; }
.agent-composer textarea, .follow-up-box textarea {
  width: 100%; min-height: 130px; resize: vertical; background: #090a0d; color: var(--text-main);
  border: 1px solid var(--border-tech); border-radius: 6px; padding: 14px; line-height: 1.6; outline: none;
}
.agent-composer textarea:focus, .follow-up-box textarea:focus { border-color: var(--accent-lime); }
.goal-presets { display: flex; flex-wrap: wrap; gap: 8px; }
.goal-presets button, .feedback-row button {
  border: 1px solid var(--border-tech); border-radius: 4px; background: transparent; color: var(--text-sub);
  padding: 7px 10px; cursor: pointer;
}
.goal-presets button:hover, .feedback-row button:hover, .feedback-row button.active {
  color: var(--accent-lime); border-color: var(--accent-lime); background: rgba(197, 249, 70, 0.08);
}
.agent-run-btn {
  border: 0; border-radius: 4px; padding: 13px 18px; background: var(--accent-lime); color: var(--text-inverse);
  font-weight: 700; cursor: pointer;
}
.agent-run-btn:disabled, .follow-up-box button:disabled { opacity: 0.4; cursor: not-allowed; }
.agent-running { display: flex; flex-direction: column; gap: 20px; }
.agent-running .loading-state { min-height: 210px; height: auto; }
.agent-inspector { margin-top: 28px; border-top: 1px solid var(--border-tech); padding-top: 20px; }
.agent-meta-block { margin-bottom: 18px; padding: 14px; background: #0c0e12; border-left: 2px solid var(--accent-lime); }
.meta-label { display: block; color: var(--accent-lime); font-size: 0.78rem; font-weight: 700; margin-bottom: 10px; }
.agent-meta-block ol { padding-left: 20px; color: #c9cbd0; }
.agent-meta-block li { margin: 7px 0; }
.plan-editor { display: grid; gap: 8px; margin-top: 12px; }
.plan-editor-row { display: grid; grid-template-columns: minmax(0, 1fr) 34px; gap: 8px; }
.plan-editor input { min-width: 0; border: 1px solid var(--border-tech); background: #090b0e; color: var(--text-main); padding: 9px 10px; }
.plan-editor button, .plan-edit-trigger { border: 1px solid var(--border-tech); background: transparent; color: var(--text-sub); padding: 7px 10px; cursor: pointer; }
.plan-editor button:hover, .plan-edit-trigger:hover { color: var(--accent-lime); border-color: var(--accent-lime); }
.plan-editor-actions { display: flex; justify-content: flex-end; gap: 8px; }
.plan-edit-trigger { margin-top: 8px; }
.stage-list { display: flex; flex-wrap: wrap; gap: 8px; }
.stage-list span, .quality-row span {
  border: 1px solid var(--border-tech); border-radius: 4px; padding: 6px 8px; color: var(--text-sub); font-size: 0.78rem;
}
.quality-row { display: flex; flex-wrap: wrap; gap: 8px; }
.follow-up-box { display: grid; grid-template-columns: 1fr auto; gap: 10px; margin-top: 24px; }
.follow-up-box textarea { min-height: 76px; }
.follow-up-box button {
  align-self: stretch; min-width: 76px; border: 1px solid var(--accent-lime); border-radius: 4px;
  background: rgba(197, 249, 70, 0.08); color: var(--accent-lime); cursor: pointer;
}
.feedback-row { display: flex; align-items: center; gap: 8px; margin-top: 18px; color: var(--text-sub); font-size: 0.85rem; }

/* 登录框 */
.auth-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); backdrop-filter: blur(5px); z-index: 2000; display: flex; justify-content: center; align-items: center; }
.auth-panel { width: 400px; max-width: 90vw; background: var(--bg-card); border: 1px solid var(--border-tech); border-top: 2px solid var(--accent-lime); box-shadow: 0 20px 50px rgba(0,0,0,0.8); display: flex; flex-direction: column; animation: slideUpFade 0.3s forwards; }
.auth-header { padding: 20px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11,12,16,0.9); }
.auth-title { font-family: 'Noto Sans SC', sans-serif; font-size: 1.2rem; color: var(--text-main); font-weight: 700; letter-spacing: 1px; }
.auth-body { padding: 30px; }
.input-group { margin-bottom: 20px; }
.input-group label { display: block; font-family: 'Noto Sans SC', monospace; color: var(--text-sub); font-size: 0.75rem; margin-bottom: 8px; letter-spacing: 1px; }
.input-group input { width: 100%; background: #000; border: 1px solid var(--border-tech); padding: 12px; color: var(--text-main); font-family: monospace; font-size: 1rem; outline: none; transition: all 0.3s; }
.input-group input:focus { border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(197, 249, 70, 0.2); }
.cyber-btn { width: 100%; background: var(--text-main); color: var(--bg-deep); border: none; padding: 12px; font-weight: 700; font-family: 'Noto Sans SC', sans-serif; cursor: pointer; transition: all 0.3s; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); margin-bottom: 20px; }
.cyber-btn:hover:not(:disabled) { background: var(--accent-lime); color: var(--text-inverse); box-shadow: 0 0 20px rgba(197, 249, 70, 0.4); }
.cyber-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.auth-toggle { text-align: center; font-size: 0.85rem; font-family: 'Noto Sans SC', sans-serif; color: var(--text-sub); }
.toggle-link { background: none; border: none; color: var(--accent-lime); cursor: pointer; font-weight: 700; margin-left: 5px; text-decoration: underline; }
.toggle-link:hover { color: #fff; }
.auth-msg { margin-top: 15px; text-align: center; font-family: 'Noto Sans SC', monospace; font-size: 0.8rem; color: var(--accent-lime); }
.auth-msg.error { color: #ff4757; }

/* 删除按钮 */
.delete-btn {
  position: absolute; top: 10px; right: 10px; background: transparent; border: none;
  color: #71757a; cursor: pointer; opacity: 0; transition: all 0.3s ease; z-index: 10; padding: 5px;
}
.project-card:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: #ff4757; transform: scale(1.2) rotate(90deg); }

@media (max-width: 720px) {
  .navbar { padding: 0.8rem 0; }
  .nav-content { padding: 0 1rem; }
  .brand-do, .brand-video { font-size: 1.25rem; }
  .status-pill { display: none; }
  .auth-btn { padding: 6px 10px; }
  .main-container { padding: 2.5rem 1rem; }
  .hero-section { margin-bottom: 3rem; }
  .slogan-main { font-size: 2rem; }
  .slogan-sub { margin-bottom: 2rem; }
  .upload-magnet { height: auto; min-height: 420px; border-radius: 8px; }
  .split-container { flex-direction: column; }
  .skew-pane, .pane-local, .pane-url { min-height: 210px; margin: 0; padding: 0; transform: none; }
  .pane-local { border-right: 0; border-bottom: 1px solid var(--accent-lime); }
  .pane-content, .skew-pane:hover .pane-content { transform: none; }
  .split-gap { display: none; }
  .card-grid { grid-template-columns: 1fr; }
  .action-dock { grid-template-columns: 1fr; }
  .filename-mask { max-width: 55vw; }
  .sidebar-panel { width: 100%; max-width: 100vw; right: -100vw; }
  .sidebar-header { padding: 16px 18px; }
  .sidebar-title { font-size: 1rem; max-width: calc(100vw - 70px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sidebar-body { padding: 20px 16px; }
  .follow-up-box { grid-template-columns: 1fr; }
  .follow-up-box button { min-height: 44px; }
}

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.5; box-shadow: 0 0 5px var(--accent-lime); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-lime); } }
@keyframes blink { 50% { opacity: 0.5; } }
</style>
