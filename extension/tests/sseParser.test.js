import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createSseFrameParser, isTerminalTaskEvent, consumeTaskStream } from '../lib/sseParser.js'

test('cancellation discards buffered events and releases the transport', async () => {
  const controller = new AbortController()
  let cancelled = false
  const body = new ReadableStream({
    start(stream) {
      stream.enqueue(new TextEncoder().encode(
        'data: {"state":"PROCESSING"}\n\ndata: {"state":"COMPLETED"}\n\n'))
    },
    cancel() { cancelled = true }
  })
  const received = []
  await consumeTaskStream(body, event => {
    received.push(event.state)
    controller.abort()
  }, controller.signal)
  assert.deepEqual(received, ['PROCESSING'])
  assert.equal(cancelled, true)
  assert.equal(body.locked, false)
})

test('terminal completion cancels a stream even when the server leaves it open', async () => {
  let cancelled = false
  const body = new ReadableStream({
    start(stream) {
      stream.enqueue(new TextEncoder().encode('data: {"state":"COMPLETED"}\n\n'))
    },
    cancel() { cancelled = true }
  })
  assert.equal(await consumeTaskStream(body, () => {}, new AbortController().signal), true)
  assert.equal(cancelled, true)
  assert.equal(body.locked, false)
})

const encoder = new TextEncoder()
const toStream = text => new ReadableStream({
  start(controller) {
    controller.enqueue(encoder.encode(text))
    controller.close()
  }
})

test('跨 chunk 的事件帧能正确拼接解析', () => {
  const parser = createSseFrameParser()
  assert.deepEqual(parser.push('data: {"state":"QUEUED","stage":null}\n\nda'), [
    { state: 'QUEUED', stage: null }
  ])
  assert.deepEqual(parser.push('ta: {"state":"PROCESSING","stage":"VIDEO_CONTEXT"}\n\n'), [
    { state: 'PROCESSING', stage: 'VIDEO_CONTEXT' }
  ])
})

test('兼容 CRLF 帧分隔', () => {
  const parser = createSseFrameParser()
  assert.deepEqual(parser.push('data: {"state":"FAILED"}\r\n\r\n'), [{ state: 'FAILED' }])
})

test('多行 data 按换行拼接且 event 行被忽略', () => {
  const parser = createSseFrameParser()
  const events = parser.push([
    'event: task-status',
    'data: {"state":"PROCESSING",',
    'data: "stage":"CRITIC_STARTED"}',
    '',
    ''
  ].join('\n'))
  assert.deepEqual(events, [{ state: 'PROCESSING', stage: 'CRITIC_STARTED' }])
})

test('无 data 行的帧（注释/心跳）被跳过', () => {
  const parser = createSseFrameParser()
  assert.deepEqual(parser.push(': keep-alive\n\n\n\n'), [])
})

test('非法 JSON 抛出错误，由上层重连逻辑处理', () => {
  const parser = createSseFrameParser()
  assert.throws(() => parser.push('data: not-json\n\n'))
})

test('端到端：从 ReadableStream 消费直到终态', async () => {
  const body = toStream(
    'data: {"state":"QUEUED","stage":null}\n\n'
    + 'data: {"state":"PROCESSING","stage":"RETRIEVAL"}\n\n'
    + 'data: {"state":"COMPLETED","result":"# 分析报告"}\n\n'
    + 'data: {"state":"PROCESSING","stage":"CRITIC_STARTED"}\n\n'
  )
  const reader = body.getReader()
  const parser = createSseFrameParser()
  const decoder = new TextDecoder()
  const events = []
  let terminal = false
  while (!terminal) {
    const { value, done } = await reader.read()
    for (const event of parser.push(decoder.decode(value || new Uint8Array(), { stream: !done }))) {
      events.push(event)
      if (isTerminalTaskEvent(event)) {
        terminal = true
        break
      }
    }
    if (done) break
  }
  assert.equal(events.length, 3)
  assert.equal(terminal, true)
  assert.equal(events.at(-1).result, '# 分析报告')
})

test('终态判定只认 COMPLETED 与 FAILED', () => {
  assert.equal(isTerminalTaskEvent({ state: 'COMPLETED' }), true)
  assert.equal(isTerminalTaskEvent({ state: 'FAILED' }), true)
  assert.equal(isTerminalTaskEvent({ state: 'PROCESSING', stage: 'PLAN_COMPLETED' }), false)
  assert.equal(isTerminalTaskEvent({ state: 'QUEUED' }), false)
  assert.equal(isTerminalTaskEvent(null), false)
})
