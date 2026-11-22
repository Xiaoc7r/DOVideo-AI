<template>
  <div class="project-card">
    <button
      class="delete-btn"
      :disabled="deleting"
      :title="deleting ? '正在删除…' : '删除视频'"
      :aria-label="`删除 ${item.filename}`"
      @click.stop="emit('delete', item)"
    >
      <svg
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <polyline points="3 6 5 6 21 6"></polyline>
        <path d="M19 6l-1 14H6L5 6"></path>
        <path d="M8 6V4h8v2"></path>
      </svg>
    </button>
    <div class="card-meta">
      <div class="meta-icon">
        <svg
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <polygon points="23 7 16 12 23 17 23 7"></polygon>
          <rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect>
        </svg>
      </div>
      <div class="meta-info">
        <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
        <div class="meta-tags">
          <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
          <span class="status-indicator" :class="statusClass" :title="statusTitle">
            {{ statusLabel }}
          </span>
        </div>
      </div>
    </div>

    <div class="action-dock">
      <button
        class="dock-item"
        :disabled="item.status !== 'COMPLETED'"
        :title="actionTitle('下载音频')"
        @click="emit('download', item)"
      >
        <span class="item-icon">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M9 18V5l12-2v13"></path>
            <circle cx="6" cy="18" r="3"></circle>
            <circle cx="18" cy="16" r="3"></circle>
          </svg>
        </span>
        <span class="item-label">下载音频</span>
      </button>

      <button
        class="dock-item"
        :disabled="item.status !== 'COMPLETED'"
        :title="actionTitle('提取文字')"
        @click="emit('transcribe', item.id)"
      >
        <span class="item-icon">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
            <line x1="16" y1="13" x2="8" y2="13"></line>
            <line x1="16" y1="17" x2="8" y2="17"></line>
            <polyline points="10 9 9 9 8 9"></polyline>
          </svg>
        </span>
        <span class="item-label">提取文字</span>
      </button>

      <button
        class="dock-item ai-core"
        :disabled="item.status !== 'COMPLETED'"
        :title="actionTitle('打开 Video Agent')"
        @click="emit('analyze', item)"
      >
        <span class="item-icon">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect>
            <rect x="9" y="9" width="6" height="6"></rect>
            <line x1="9" y1="1" x2="9" y2="4"></line>
            <line x1="15" y1="1" x2="15" y2="4"></line>
            <line x1="9" y1="20" x2="9" y2="23"></line>
            <line x1="15" y1="20" x2="15" y2="23"></line>
            <line x1="20" y1="9" x2="23" y2="9"></line>
            <line x1="20" y1="14" x2="23" y2="14"></line>
            <line x1="1" y1="9" x2="4" y2="9"></line>
            <line x1="1" y1="14" x2="4" y2="14"></line>
          </svg>
        </span>
        <div class="label-group">
          <span class="item-label">Video Agent</span>
        </div>
        <div class="shimmer"></div>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  item: { type: Object, required: true },
  task: { type: Object, default: null },
  deleting: { type: Boolean, default: false },
})
const emit = defineEmits(['delete', 'download', 'transcribe', 'analyze'])
const statusClass = computed(() =>
  props.task ? 'processing' : mediaStatusClass(props.item.status),
)
const statusLabel = computed(() =>
  props.task
    ? props.task.type === 'ai'
      ? 'ANALYZING'
      : 'TRANSCRIBING'
    : mediaStatusLabel(props.item.status),
)
const statusTitle = computed(() =>
  !props.task
    ? null
    : props.task.type === 'ai'
      ? 'AI 分析正在后台执行，完成后会提示你'
      : '文字提取正在后台执行，完成后会提示你',
)
const actionTitle = (label) =>
  props.item.status === 'COMPLETED' ? null : `视频尚未处理完成，暂时无法${label}`

const mediaStatusClass = (status) =>
  ['COMPLETED', 'PROCESSING', 'FAILED'].includes(status) ? status.toLowerCase() : 'unknown'
const mediaStatusLabel = (status) =>
  ({
    COMPLETED: 'READY',
    PROCESSING: 'PROCESSING',
    FAILED: 'FAILED',
  })[status] || 'PENDING'

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  if (Number.isNaN(date.getTime())) return '--'
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.project-card {
  background: var(--bg-card);
  border-radius: 12px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.3);
  border: 1px solid var(--border-tech);
  overflow: hidden;
  transition: transform 0.2s;
  position: relative;
}
.project-card:hover {
  transform: translateY(-2px);
  border-color: var(--accent-lime);
}
.card-meta {
  display: flex;
  gap: 1.5rem;
  padding: 1.5rem;
  align-items: center;
  border-bottom: 1px solid var(--border-tech);
  background: rgba(18, 21, 18, 0.5);
}
.meta-icon {
  width: 56px;
  height: 56px;
  background: rgba(197, 249, 70, 0.05);
  border: 1px solid var(--accent-lime);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-lime);
}
.filename-mask {
  font-size: 1.1rem;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}
.meta-tags {
  display: flex;
  gap: 12px;
  font-size: 0.85rem;
  font-family: monospace;
  margin-top: 5px;
}
.time-tag {
  color: var(--text-sub);
}
.status-indicator {
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.status-indicator.completed {
  color: var(--accent-lime);
  border: 1px solid var(--accent-lime);
  background: rgba(197, 249, 70, 0.1);
}
.status-indicator.processing {
  color: var(--accent-purple);
  border: 1px solid var(--accent-purple);
  animation: blink 1s infinite;
}
.status-indicator.failed {
  color: #ff7c88;
  border: 1px solid #ff4757;
  background: rgba(255, 71, 87, 0.08);
}
.status-indicator.unknown {
  color: var(--text-sub);
  border: 1px solid var(--border-tech);
}

/* 删除按钮 */
.delete-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  background: transparent;
  border: none;
  width: 36px;
  height: 36px;
  color: #71757a;
  cursor: pointer;
  opacity: 0;
  transition:
    color 0.2s ease,
    opacity 0.2s ease;
  z-index: 10;
}
.project-card:hover .delete-btn,
.delete-btn:focus-visible {
  opacity: 1;
}
.delete-btn:hover:not(:disabled) {
  color: #ff4757;
}
.delete-btn:disabled {
  cursor: progress;
  opacity: 0.5;
}

.action-dock {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr;
  gap: 12px;
  padding: 12px;
  background: rgba(5, 8, 5, 0.5);
}
.dock-item {
  position: relative;
  border: 1px solid var(--border-tech);
  background: var(--bg-card);
  border-radius: 8px;
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  cursor: pointer;
  transition: all 0.3s;
  color: var(--text-sub);
  font-family: monospace;
  overflow: hidden;
}
.dock-item:hover:not(:disabled) {
  color: var(--accent-lime);
  border-color: var(--accent-lime);
  background: rgba(197, 249, 70, 0.05);
}
.dock-item:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.dock-item.ai-core {
  border-color: var(--accent-purple);
  color: var(--accent-purple);
}
.dock-item.ai-core .label-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  z-index: 1;
}
.dock-item.ai-core .item-sub {
  font-size: 0.75rem;
  color: var(--accent-purple);
  opacity: 0.8;
}
.dock-item.ai-core:hover:not(:disabled) {
  border-color: var(--accent-lime);
  color: var(--text-inverse);
  background: var(--accent-lime);
}
.dock-item.ai-core:hover:not(:disabled) .item-sub {
  color: var(--text-inverse);
}

@media (max-width: 720px) {
  .action-dock {
    grid-template-columns: 1fr;
  }
  .filename-mask {
    max-width: 55vw;
  }
  .delete-btn {
    opacity: 1;
  }
}
</style>
