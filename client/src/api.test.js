import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { apiRequest, setAuthToken } from './api.js'

beforeEach(() => {
  const storage = new Map()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  globalThis.window = new EventTarget()
})

test('API network failures produce an actionable message', async () => {
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async () => {
    throw new TypeError('fetch failed')
  }

  await assert.rejects(apiRequest('/health'), /请确认后端已启动且地址配置正确/)
})

test('a stale 401 cannot log out a newer login', async () => {
  setAuthToken('old-token')
  let respond
  globalThis.fetch = () => new Promise(resolve => { respond = resolve })
  let expired = 0
  window.addEventListener('auth-expired', () => { expired += 1 })
  const request = apiRequest('/media/list')
  setAuthToken('new-token')
  respond(new Response('expired', { status: 401 }))
  await request
  assert.equal(localStorage.getItem('authToken'), 'new-token')
  assert.equal(expired, 0)
})

test('a current 401 clears login and reports expiry once', async () => {
  setAuthToken('current-token')
  globalThis.fetch = async () => new Response('expired', { status: 401 })
  let expired = 0
  window.addEventListener('auth-expired', () => { expired += 1 })
  await apiRequest('/media/list')
  assert.equal(localStorage.getItem('authToken'), null)
  assert.equal(expired, 1)
})

test('SSE and binary responses are never consumed by JSON unwrapping', async () => {
  for (const contentType of ['text/event-stream', 'audio/mpeg']) {
    const response = new Response('payload', { headers: { 'Content-Type': contentType } })
    globalThis.fetch = async () => response
    assert.equal(await apiRequest('/analysis/events'), response)
    assert.equal(response.bodyUsed, false)
  }
})
