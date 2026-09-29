import test from 'node:test'
import assert from 'node:assert/strict'
import { isTerminalStatus } from './taskEventsPolicy.js'
import { createTaskStreams } from './taskEvents.js'

const flush = () => new Promise(resolve => setImmediate(resolve))

async function until(predicate) {
  for (let i = 0; i < 30 && !predicate(); i += 1) await flush()
  assert.ok(predicate(), 'stream did not reach the expected state')
}

test('SSE only retries recoverable HTTP statuses', () => {
  assert.equal(isTerminalStatus(400), true)
  assert.equal(isTerminalStatus(404), true)
  assert.equal(isTerminalStatus(408), false)
  assert.equal(isTerminalStatus(429), false)
  assert.equal(isTerminalStatus(500), false)
})

test('stopping during an event drops remaining buffered events and cancels the reader', async () => {
  globalThis.localStorage = { getItem: () => 'token' }
  let cancelled = false
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(
        'data: {"state":"PROCESSING"}\n\ndata: {"state":"COMPLETED"}\n\n'))
    },
    cancel() { cancelled = true }
  })
  globalThis.fetch = async () => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
  const streams = createTaskStreams()
  const events = []
  streams.start(1, 'ai', 'goal', '/events', event => {
    events.push(event.state)
    streams.stopAll()
  })
  await until(() => cancelled)
  assert.deepEqual(events, ['PROCESSING'])
  assert.equal(streams.hasMedia(1), false)
})

test('a terminal event releases an open transport without waiting for server EOF', async () => {
  globalThis.localStorage = { getItem: () => 'token' }
  let cancelled = false
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('data: {"state":"COMPLETED","result":"done"}\n\n'))
    },
    cancel() { cancelled = true }
  })
  globalThis.fetch = async () => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
  const streams = createTaskStreams()
  const events = []
  streams.start(1, 'ai', 'goal', '/events', event => events.push(event.result))
  await until(() => cancelled && !streams.hasMedia(1))
  assert.deepEqual(events, ['done'])
})

test('a stopped pending request cannot report a late terminal HTTP error', async () => {
  globalThis.localStorage = { getItem: () => 'token' }
  let respond
  globalThis.fetch = () => new Promise(resolve => { respond = resolve })
  const streams = createTaskStreams()
  const errors = []
  streams.start(1, 'ai', 'goal', '/events', () => {}, error => errors.push(error))
  streams.stopAll()
  respond(new Response('missing', { status: 404 }))
  await flush()
  assert.deepEqual(errors, [])
})
