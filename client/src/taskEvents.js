import { apiRequest } from './api'

export function createTaskStreams() {
  const streams = new Map()
  const keyOf = (id, type, scope = '') => `${type}:${id}:${scope}`

  const stop = (id, type, scope = '') => {
    const key = keyOf(id, type, scope)
    streams.get(key)?.abort()
    streams.delete(key)
  }

  const stopAll = () => {
    for (const controller of streams.values()) controller.abort()
    streams.clear()
  }

  const start = (id, type, scope, path, onEvent, onError) => {
    stop(id, type, scope)
    const key = keyOf(id, type, scope)
    const controller = new AbortController()
    streams.set(key, controller)

    const run = async () => {
      while (!controller.signal.aborted && streams.get(key) === controller) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          if (!response.ok || !response.body) throw new Error(await response.text())
          const terminal = await consumeStream(response.body, onEvent, controller.signal)
          if (terminal) {
            streams.delete(key)
            return
          }
        } catch (error) {
          if (controller.signal.aborted) return
          onError?.(error)
        }
        // SSE 断线只重连事件流，不再定时请求任务状态。
        await new Promise(resolve => setTimeout(resolve, 1500))
      }
    }

    run().catch(error => {
      if (!controller.signal.aborted) onError?.(error)
    })
  }

  return {
    has: (id, type, scope = '') => streams.has(keyOf(id, type, scope)),
    start,
    stop,
    stopAll
  }
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
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
    reader.releaseLock()
  }
}
