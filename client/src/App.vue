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
        <p class="slogan-sub">影音重构 · 算力赋能</p>

        <div class="upload-wrapper">
          <input
              type="file"
              id="file-input"
              @change="handleFileChange"
              accept="video/*"
              hidden
          />
          <label
              for="file-input"
              class="upload-magnet"
              :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="isDragOver = false"
              @drop.prevent="handleDrop"
          >
            <div class="magnet-content" v-if="!uploading">
              <div class="magnet-icon">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 16 12 12 8 16"></polyline><line x1="12" y1="12" x2="12" y2="21"></line><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"></path><polyline points="16 16 12 12 8 16"></polyline></svg>
              </div>
              <span class="magnet-title">接入视频源</span>
              <span class="magnet-desc">{{ isDragOver ? '松手即可上传' : '点击上传 / 拖拽文件入场' }}</span>
            </div>

            <div class="magnet-content busy" v-else>
              <div class="quantum-loader"></div>
              <span class="busy-text">正在上传并进行深度转码...</span>
            </div>

            <div class="border-glow"></div>
          </label>
        </div>

        <transition name="toast-pop">
          <div v-if="message" class="notification-bar">{{ message }}</div>
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
                  @click="aiAnalyze(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                </span>
                <div class="label-group">
                  <span class="item-label">AI 智能总结</span>
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
          <div v-if="sidebar.loading" class="loading-state"><div class="quantum-loader small"></div><p>数据流处理中...</p></div>
          <div v-else>
            <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
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
              <span class="toggle-text">
                {{ authMode === 'login' ? '没有账号?' : '已有账号?' }}
              </span>
              <button class="toggle-link" @click="switchAuthMode">
                {{ authMode === 'login' ? '去注册' : '去登录' }}
              </button>
            </div>

            <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { marked } from 'marked'

// --- 变量定义 ---
const file = ref(null)
const message = ref('')
const uploading = ref(false)
const list = ref([])

// --- 新增拖拽状态 ---
const isDragOver = ref(false)

// --- 新增拖拽处理函数 ---
const handleDrop = async (e) => {
  isDragOver.value = false // 拖放结束，取消高亮

  // 1. 检查登录 (复用之前的逻辑)
  if (!currentUser.value) {
    message.value = '⚠️ 权限受限：请先登录系统'
    setTimeout(() => message.value = '', 3000)
    openAuthModal()
    return
  }

  // 2. 获取拖进来的文件 (注意：这里是 dataTransfer)
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return

  const selectedFile = droppedFiles[0]

  // 3. 简单校验一下是不是视频
  if (!selectedFile.type.startsWith('video/')) {
    message.value = '⚠️ 仅支持上传视频文件'
    setTimeout(() => message.value = '', 3000)
    return
  }

  // 4. 开始上传
  file.value = selectedFile
  await uploadFile()
}

const sidebar = ref({ visible: false, type: 'ai', title: '', content: '', loading: false })

// 用户系统变量
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })

// Markdown 解析
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

// --- 核心业务逻辑 ---

const handleFileChange = async (e) => {
  if (!currentUser.value) {
    e.target.value = ''
    message.value = '⚠️ 权限受限：请先登录系统'
    setTimeout(() => message.value = '', 3000)
    openAuthModal()
    return
  }

  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  file.value = selectedFile
  await uploadFile()
}

const uploadFile = async () => {
  if (!file.value) return
  uploading.value = true
  message.value = '正在建立加密通道并上传数据...'
  const formData = new FormData()
  formData.append('file', file.value)

  if (currentUser.value) {
    formData.append('userId', currentUser.value.id)
  }

  try {
    const res = await fetch('http://localhost:9090/media/upload', {
      method: 'POST',
      body: formData
    })
    const text = await res.text()
    if (!res.ok) throw new Error('Upload failed')

    message.value = '✅ 上传完成'
    fetchList() // 上传成功后刷新列表
  } catch (error) {
    console.error(error)
    message.value = '❌ 上传失败 (请检查后端黑窗口报错)'
  } finally {
    uploading.value = false
    setTimeout(() => { if(!uploading.value) message.value = '' }, 4000)
  }
}

// 【关键修改】拉取列表逻辑
const fetchList = async () => {
  try {
    let url = 'http://localhost:9090/media/list'

    // 如果已登录，带上 userId
    if (currentUser.value) {
      url += `?userId=${currentUser.value.id}`

      const res = await fetch(url)
      const data = await res.json()
      list.value = data.reverse()
    } else {
      // 没登录，直接清空列表，不发请求
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

// --- 新增：删除逻辑 ---
const deleteItem = async (item) => {
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return

  try {
    let url = `http://localhost:9090/media/delete?id=${item.id}`
    // 如果登录了，带上验证 ID
    if (currentUser.value) {
      url += `&userId=${currentUser.value.id}`
    }

    const res = await fetch(url, { method: 'DELETE' })
    const text = await res.text()

    if (text === '删除成功') {
      message.value = '文件已销毁'
      // 这里的优化：不用重新拉列表，直接在前端把这一项移除，体验更丝滑
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      message.value = '❌ ' + text
    }
    setTimeout(() => message.value = '', 3000)
  } catch (e) {
    console.error(e)
    message.value = '❌ 删除请求失败'
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  const url = `http://localhost:9090/debug/download?id=${item.id}`
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    message.value = '正在转码并下载...'
    const res = await fetch(url)
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
    message.value = '✅ 下载完成'
  } catch (e) {
    alert("下载失败")
  }
}

const transcribe = async (id) => {
  openSidebar('text', '全量文字提取')
  try {
    // 这里如果视频太长，fetch 可能会超时
    const res = await fetch(`http://localhost:9090/debug/transcribe?id=${id}`)
    const text = await res.text()
    sidebar.value.content = text
  } catch (e) {
    sidebar.value.content = "Error: 请求超时或失败 (建议使用短视频测试)"
  } finally {
    sidebar.value.loading = false
  }
}

const aiAnalyze = async (id) => {
  openSidebar('ai', 'AI 智能总结')
  try {
    const res = await fetch(`http://localhost:9090/debug/ai?id=${id}`)
    const text = await res.text()
    sidebar.value.content = text
  } catch (e) {
    sidebar.value.content = "Error: " + e
  } finally {
    sidebar.value.loading = false
  }
}

const openSidebar = (type, title) => {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}
const closeSidebar = () => { sidebar.value.visible = false }

// --- 登录注册逻辑 ---
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
    const res = await fetch(`http://localhost:9090${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    const data = await res.json()
    if (data.code === 200) {
      if (authMode.value === 'login') {
        // 1. 保存登录态
        currentUser.value = data.userInfo
        localStorage.setItem('user', JSON.stringify(data.userInfo))
        closeAuthModal()
        message.value = `欢迎回来，${data.userInfo.nickname}`

        // 【关键修复】登录成功后，立即拉取该用户的文件！
        fetchList()

        setTimeout(() => message.value = '', 3000)
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
  currentUser.value = null
  localStorage.removeItem('user')

  // 【关键修复】登出后，立即清空列表
  list.value = []

  message.value = '已退出系统'
  setTimeout(() => message.value = '', 3000)
}

// 【关键修复】页面加载时的逻辑顺序
onMounted(() => {
  // 1. 先尝试恢复登录态
  const savedUser = localStorage.getItem('user')
  if (savedUser) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch(e) {}
  }

  // 2. 恢复完了(或者没恢复)，再去拉取列表
  // 这样 fetchList 内部就能拿到 currentUser.id 了
  fetchList()
})
</script>

<style>
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

/* 氛围层 */
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
.upload-wrapper { max-width: 680px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }
.upload-magnet { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 280px; background: var(--bg-card); border-radius: 16px; cursor: pointer; position: relative; transition: all 0.3s; box-shadow: var(--shadow-float); border: 2px solid var(--border-tech); overflow: hidden; background-image: linear-gradient(rgba(197, 249, 70, 0.02) 1px, transparent 1px), linear-gradient(90deg, rgba(197, 249, 70, 0.02) 1px, transparent 1px); background-size: 30px 30px; }
.upload-magnet:hover { transform: translateY(-8px) scale(1.01); border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); }

/* 现在的 hover 样式 */
.upload-magnet:hover { transform: translateY(-8px) scale(1.01); border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); }

/* --- 新增：拖拽时的样式 (高亮更明显) --- */
.upload-magnet.is-dragover {
  border-color: var(--accent-lime);
  background: rgba(197, 249, 70, 0.1); /* 绿色背景淡入 */
  box-shadow: 0 0 30px rgba(197, 249, 70, 0.3);
  transform: scale(1.02);
}

.magnet-content { z-index: 2; display: flex; flex-direction: column; align-items: center; }
.magnet-icon { color: var(--accent-lime); margin-bottom: 1.5rem; transition: transform 0.3s; filter: drop-shadow(0 0 5px var(--accent-lime)); }
.upload-magnet:hover .magnet-icon { transform: scale(1.1); filter: drop-shadow(0 0 15px var(--accent-lime)); }
.magnet-title { font-size: 1.5rem; font-weight: 700; }
.magnet-desc { font-size: 0.9rem; color: var(--text-sub); font-family: monospace; }
.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; box-shadow: 0 0 10px var(--accent-lime); }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }
.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 4px; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); }

/* Workspace */
.workspace-section { opacity: 0; animation: slideUpFade 0.8s 0.4s forwards; }
.section-header { display: flex; align-items: center; gap: 12px; margin-bottom: 2rem; border-bottom: 2px solid var(--border-tech); padding-bottom: 10px; }
.section-header h3 { font-size: 1.5rem; font-weight: 700; }
.count-chip { background: var(--border-tech); padding: 4px 10px; border-radius: 4px; font-size: 0.75rem; font-family: monospace; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
.project-card { background: var(--bg-card); border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); border: 1px solid var(--border-tech); overflow: hidden; transition: transform 0.2s; }
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

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.5; box-shadow: 0 0 5px var(--accent-lime); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-lime); } }
@keyframes blink { 50% { opacity: 0.5; } }


/* --- 删除按钮样式 --- */
.project-card {
  position: relative; /* 这一句必须有，不然 X 号会飞到屏幕外面去 */
}

.delete-btn {
  position: absolute; /* 绝对定位：固定在右上角 */
  top: 10px;
  right: 10px;
  background: transparent;
  border: none;
  color: #71757a; /* 默认灰色 */
  cursor: pointer;
  opacity: 0; /* 默认完全透明(看不见) */
  transition: all 0.3s ease;
  z-index: 10;
  padding: 5px;
}

/* 只有鼠标移入卡片时，X 号才显示出来 */
.project-card:hover .delete-btn {
  opacity: 1;
}

/* 鼠标放在 X 号上时，变红、变大、旋转 */
.delete-btn:hover {
  color: #ff4757;
  transform: scale(1.2) rotate(90deg);
}
</style>