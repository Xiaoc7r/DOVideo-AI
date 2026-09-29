import { apiRequest, captureAuthSession } from './api.js'
import { isTerminalStatus } from './taskEventsPolicy.js'

/**
 * 后台任务的 SSE 连接池。
 * onActiveChange 让界面（例如资料库卡片）能感知“哪些视频正在跑任务”，
 * 用户关掉侧边栏之后仍然看得到任务在进行。
 */
export function createTaskStreams({ onActiveChange = () => {} } = {}) {
  const streams = new Map()
  const keyOf = (id, type, scope = '') => `${type}:${id}:${scope}`
  const publish = () => onActiveChange([...streams.values()]
    .map(({ id, type, scope }) => ({ id, type, scope })))

  const stop = (id, type, scope = '') => {
    const key = keyOf(id, type, scope)
    const entry = streams.get(key)
    if (!entry) return
    entry.controller.abort()
    streams.delete(key)
    publish()
  }

  const stopAll = () => {
    if (!streams.size) return
    for (const { controller } of streams.values()) controller.abort()
    streams.clear()
    publish()
  }

  const stopMedia = id => {
    let changed = false
    for (const [key, entry] of streams.entries()) {
      if (String(entry.id) !== String(id)) continue
      entry.controller.abort()
      streams.delete(key)
      changed = true
    }
    if (changed) publish()
  }

  const start = (id, type, scope, path, onEvent, onError) => {
    stop(id, type, scope)
    const key = keyOf(id, type, scope)
    const controller = new AbortController()
    const isCurrentSession = captureAuthSession()
    streams.set(key, { controller, id, type, scope })
    publish()
    let reconnectAttempt = 0
    const isActive = () => !controller.signal.aborted
      && streams.get(key)?.controller === controller && isCurrentSession()

    const release = () => {
      if (streams.get(key)?.controller !== controller) return
      streams.delete(key)
      publish()
    }

    const run = async () => {
      while (isActive()) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          if (!isActive()) {
            await response.body?.cancel().catch(() => {})
            release()
            return
          }
          if (!response.ok) {
            const error = new Error(
              (await response.text()) || `事件流连接失败（HTTP ${response.status}）`)
            error.status = response.status
            if (!isActive()) return
            // 目标不存在、无权访问、参数非法这类错误不会自愈，继续重连只是空转，
            // 还会让界面永远停在“重连中”。直接释放连接并告知调用方这是终态。
            if (isTerminalStatus(response.status)) {
              release()
              onError?.(error, reconnectAttempt + 1, true)
              return
            }
            throw error
          }
          if (!response.body) throw new Error('服务端未返回事件流')
          const terminal = await consumeStream(response.body, async event => {
            if (!isActive()) return
            reconnectAttempt = 0
            await onEvent(event, controller.signal)
          }, controller.signal)
          if (terminal) {
            release()
            return
          }
        } catch (error) {
          if (!isActive()) { release(); return }
          onError?.(error, reconnectAttempt + 1)
        }
        const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
        await waitForRetry(delay, controller.signal)
      }
      release()
    }

    run().catch(error => {
      if (!isActive()) { release(); return }
      // 走到这里说明重连循环本身异常退出，连接不会再恢复，同样按终态通知。
      release()
      onError?.(error, reconnectAttempt + 1, true)
    })
  }

  return {
    has: (id, type, scope = '') => streams.has(keyOf(id, type, scope)),
    hasMedia: id => [...streams.values()].some(entry => String(entry.id) === String(id)),
    start,
    stop,
    stopMedia,
    stopAll
  }
}

function waitForRetry(delay, signal) {
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

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const cancel = () => { reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (signal.aborted) return false
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        if (signal.aborted) return false
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        await onEvent(event)
        if (event.state === 'COMPLETED' || event.state === 'FAILED') return true
      }
      if (done) return false
    }
    return false
  } finally {
    signal.removeEventListener('abort', cancel)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
