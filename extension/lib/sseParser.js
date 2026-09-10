/**
 * 增量 SSE 分帧解析器（移植自 client/src/taskEvents.js 的 consumeStream 内部逻辑）。
 * push() 接收一块解码后的文本，返回其中完整帧解析出的事件数组；
 * 未完结的尾帧留在内部缓冲，等下一次 push 拼接。
 */
export function createSseFrameParser() {
  let buffer = ''
  return {
    push(chunkText) {
      buffer += chunkText
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      const events = []
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        events.push(JSON.parse(data))
      }
      return events
    }
  }
}

/**
 * 后端 task-status 事件的终态判定：COMPLETED / FAILED 表示本轮任务结束，
 * 调用方收到后应停止重连循环。
 */
export function isTerminalTaskEvent(event) {
  return event?.state === 'COMPLETED' || event?.state === 'FAILED'
}
