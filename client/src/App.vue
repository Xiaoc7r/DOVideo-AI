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
        <div class="status-pill" :class="{ 'is-active': uploading }">
          <div class="status-dot"></div>
          <span class="status-text">{{ uploading ? 'DATA STREAMING...' : 'SYSTEM READY' }}</span>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="hero-section">
        <h1 class="slogan-main">DECODE REALITY</h1>
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
              :class="{ 'processing': uploading }"
          >
            <div class="magnet-content" v-if="!uploading">
              <div class="magnet-icon">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 16 12 12 8 16"></polyline><line x1="12" y1="12" x2="12" y2="21"></line><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"></path><polyline points="16 16 12 12 8 16"></polyline></svg>
              </div>
              <span class="magnet-title">接入视频源</span>
              <span class="magnet-desc">DRAG & DROP / CLICK TO UPLOAD</span>
            </div>

            <div class="magnet-content busy" v-else>
              <div class="quantum-loader"></div>
              <span class="busy-text">正在上传并进行深度转码...</span>
            </div>

            <div class="border-glow"></div>
          </label>
        </div>

        <transition name="toast-pop">
          <div v-if="message" class="notification-bar">
            {{ message }}
          </div>
        </transition>
      </section>

      <section v-if="list.length > 0" class="workspace-section">
        <div class="section-header">
          <h3>工作台</h3>
          <div class="count-chip">{{ list.length }} TASKS</div>
        </div>

        <div class="card-grid">
          <div v-for="item in list" :key="item.id" class="project-card">

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
          <button class="close-btn" @click="closeSidebar">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
          </button>
        </div>

        <div class="sidebar-body">
          <div v-if="sidebar.loading" class="loading-state">
            <div class="quantum-loader small"></div>
            <p>数据流处理中...</p>
          </div>

          <div v-else>
            <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
            <div v-else class="text-content">
              <pre>{{ sidebar.content }}</pre>
            </div>
          </div>
        </div>
      </div>

    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { marked } from 'marked'

const file = ref(null)
const message = ref('')
const uploading = ref(false)
const list = ref([])

const sidebar = ref({
  visible: false,
  type: 'ai',
  title: '',
  content: '',
  loading: false
})

const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

const handleFileChange = async (e) => {
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
  try {
    const res = await fetch('http://localhost:9090/media/upload', {
      method: 'POST',
      body: formData
    })
    if (!res.ok) throw new Error('Upload failed')
    message.value = '✅ 上传完成'
    fetchList()
  } catch (error) {
    console.error(error)
    message.value = '❌ 上传中断'
  } finally {
    uploading.value = false
    setTimeout(() => { if(!uploading.value) message.value = '' }, 4000)
  }
}

const fetchList = async () => {
  try {
    const res = await fetch('http://localhost:9090/media/list')
    const data = await res.json()
    list.value = data.reverse()
  } catch (error) {
    console.error(error)
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

// 1. 下载音频
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
    alert("下载失败，请检查后端控制台")
  }
}

// 2. 提取文字
const transcribe = async (id) => {
  openSidebar('text', '全量文字提取')
  try {
    const res = await fetch(`http://localhost:9090/debug/transcribe?id=${id}`)
    const text = await res.text()
    sidebar.value.content = text
  } catch (e) {
    sidebar.value.content = "Error: " + e
  } finally {
    sidebar.value.loading = false
  }
}

// 3. AI 总结
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

onMounted(() => { fetchList() })
</script>

<style>
/* --- 字体引入 --- */
@import url('https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@300;500;700&family=Syncopate:wght@700&display=swap');

/* --- 1. 基础变量体系 (已经替换为你需要的黑绿配色) --- */
:root {
  --bg-deep: #0b0c10;  /* 【修改点】 换成了你参考代码里的深色 */
  --bg-card: #121418;  /* 【修改点】 换成了你参考代码里的卡片色 */

  --accent-lime: #c5f946;
  --accent-purple: #8a2be2;
  --text-main: #e0e0e0; /* 使用参考代码的 primary text */
  --text-sub: #71757a;  /* 使用参考代码的 secondary text */
  --text-inverse: #0b0c10;

  --border-tech: #2a2d35; /* 使用参考代码的 border color */

  --shadow-float: 0 10px 30px -10px rgba(0, 0, 0, 0.7);
  --shadow-glow-lime: 0 0 20px rgba(197, 249, 70, 0.2);
}

/* --- 2. 全局重置 --- */
* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important;
  padding: 0 !important;
  width: 100vw !important;
  max-width: 100vw !important;
  min-height: 100vh !important;
  overflow-x: hidden;
  background-color: var(--bg-deep); /* 确保背景色统一 */
}

.app-stage {
  position: relative;
  z-index: 1;
  width: 100%;
  min-height: 100vh;
  color: var(--text-main);
  font-family: 'Space Grotesk', 'Noto Sans SC', monospace;
}

/* --- 3. 环境氛围层 (已经替换为你需要的参数) --- */
.ambient-noise {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%;
  /* 【修改点】 参考代码的噪点 */
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)' opacity='0.05'/%3E%3C/svg%3E");
  pointer-events: none; z-index: -1;
}

.ambient-glow {
  position: fixed;
  top: -20%; left: 20%; /* 【修改点】 参考代码的 Glow 位置 */
  width: 60vw; height: 60vh;
  /* 【修改点】 参考代码的 Glow 渐变 */
  background: radial-gradient(circle, rgba(197, 249, 70, 0.08) 0%, rgba(11, 12, 16, 0) 70%);
  pointer-events: none; z-index: -2;
}

/* --- 下面是你喜欢的布局样式，我一点都没动，只让它们使用新的变量 --- */

.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1.2rem 0; background: rgba(11, 12, 16, 0.85); backdrop-filter: blur(12px); border-bottom: 1px solid var(--border-tech); }
.nav-content { max-width: 1400px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; }
.brand { display: flex; align-items: baseline; gap: 2px; }
.brand-do { font-family: 'Dela Gothic One', sans-serif; font-size: 1.8rem; color: var(--text-main); letter-spacing: -1px; }
.brand-video { font-family: 'Space Grotesk', sans-serif; font-size: 1.8rem; font-weight: 300; }
.beta-badge { font-size: 0.7rem; font-weight: 700; background: var(--accent-lime); color: var(--text-inverse); padding: 2px 6px; border-radius: 2px; margin-left: 8px; transform: translateY(-4px); box-shadow: 0 0 5px var(--accent-lime); }
.status-pill { display: flex; align-items: center; gap: 8px; background: var(--bg-card); padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border-tech); font-size: 0.8rem; color: var(--text-sub); }
.status-dot { width: 6px; height: 6px; background: var(--accent-lime); border-radius: 50%; }
.status-pill.is-active .status-dot { animation: pulse-lime 1.5s infinite alternate; }

.main-container { max-width: 1200px; margin: 0 auto; padding: 4rem 2rem; }
.hero-section { text-align: center; margin-bottom: 6rem; animation: slideUpFade 0.8s forwards; }
.slogan-main { font-family: 'Syncopate', sans-serif; font-size: clamp(2.5rem, 6vw, 4.5rem); font-weight: 700; margin-bottom: 0.5rem; text-shadow: 0 0 20px rgba(197, 249, 70, 0.2); }
.slogan-sub { font-size: 1.1rem; color: var(--text-sub); letter-spacing: 2px; margin-bottom: 3rem; }
.upload-wrapper { max-width: 680px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }
.upload-magnet { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 280px; background: var(--bg-card); border-radius: 16px; cursor: pointer; position: relative; transition: all 0.3s; box-shadow: var(--shadow-float); border: 2px solid var(--border-tech); overflow: hidden; background-image: linear-gradient(rgba(197, 249, 70, 0.02) 1px, transparent 1px), linear-gradient(90deg, rgba(197, 249, 70, 0.02) 1px, transparent 1px); background-size: 30px 30px; }
.upload-magnet:hover { transform: translateY(-8px) scale(1.01); border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); }
.magnet-content { z-index: 2; display: flex; flex-direction: column; align-items: center; }
.magnet-icon { color: var(--accent-lime); margin-bottom: 1.5rem; transition: transform 0.3s; filter: drop-shadow(0 0 5px var(--accent-lime)); }
.upload-magnet:hover .magnet-icon { transform: scale(1.1); filter: drop-shadow(0 0 15px var(--accent-lime)); }
.magnet-title { font-size: 1.5rem; font-weight: 700; }
.magnet-desc { font-size: 0.9rem; color: var(--text-sub); font-family: monospace; }
.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; box-shadow: 0 0 10px var(--accent-lime); }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }
.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 4px; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); }

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

/* --- Sidebar --- */
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

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.5; box-shadow: 0 0 5px var(--accent-lime); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-lime); } }
@keyframes blink { 50% { opacity: 0.5; } }
</style>