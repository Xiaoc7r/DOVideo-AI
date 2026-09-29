import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { useAnalysisWorkspace } from './useAnalysisWorkspace.js'
import { clearAuthToken, setAuthToken } from './api.js'

const item = { id: 7, filename: 'sample.mp4' }
const json = data => Response.json({ code: 0, message: 'success', data })
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

beforeEach(() => {
  const storage = new Map()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  setAuthToken('token')
})

function setup() {
  const starts = []
  const workspace = useAnalysisWorkspace({
    demoMode: false,
    taskStreams: { has: () => false, start: (...args) => starts.push(args), stopMedia() {} },
    showMessage() {}, refreshMediaList: async () => {}, findMediaItem: () => item
  })
  return { ...workspace, starts }
}

test('late history cannot replace a new submission and old metadata is cleared', async () => {
  const history = deferred()
  globalThis.fetch = async url => {
    if (url.startsWith('/analysis/analysis-status')) return history.promise
    if (url.startsWith('/media/playback')) return json('https://example.com/video.mp4')
    return json(null)
  }
  const workspace = setup()
  const opening = workspace.openAgent(item)
  workspace.sidebar.value.plan = { tasks: ['old plan'] }
  workspace.sidebar.value.evaluation = { criticPassed: true }
  workspace.sidebar.value.feedback = 1
  await workspace.submitAgent()
  history.resolve(json({ state: 'COMPLETED', result: 'old result' }))
  await opening
  assert.equal(workspace.sidebar.value.mode, 'result')
  assert.equal(workspace.sidebar.value.content, '')
  assert.equal(workspace.sidebar.value.evaluation, null)
  assert.equal(workspace.sidebar.value.feedback, null)
  assert.equal(workspace.sidebar.value.plan, null)
  assert.equal(workspace.starts.length, 1)
})

test('logout during submission cannot restart task streams', async () => {
  const submission = deferred()
  globalThis.fetch = async url => url.startsWith('/analysis/ai?')
    ? submission.promise : json({ state: 'NOT_STARTED' })
  const workspace = setup()
  await workspace.openAgent(item)
  const submitting = workspace.submitAgent()
  clearAuthToken()
  workspace.resetWorkspace()
  submission.resolve(json(null))
  await submitting
  assert.equal(workspace.starts.length, 0)
  assert.equal(workspace.sidebar.value.mediaId, null)
})

test('terminal stream errors return the analysis panel to a visible retry state', async () => {
  globalThis.fetch = async () => json({ state: 'NOT_STARTED' })
  const workspace = setup()
  await workspace.openAgent(item)
  await workspace.submitAgent()
  const onError = workspace.starts[0][5]
  onError(new Error('没有权限读取任务'), 1, true)
  assert.equal(workspace.sidebar.value.loading, false)
  assert.equal(workspace.sidebar.value.mode, 'compose')
  assert.equal(workspace.sidebar.value.error, '没有权限读取任务')
})

test('closing an evidence search releases its busy state and discards the late result', async () => {
  const evidence = deferred()
  globalThis.fetch = async url => url.startsWith('/analysis/evidence-search')
    ? evidence.promise : json({ state: 'NOT_STARTED' })
  const workspace = setup()
  await workspace.openAgent(item)
  workspace.sidebar.value.evidenceQuery = 'queue'
  const searching = workspace.searchEvidence()
  workspace.closeSidebar()
  assert.equal(workspace.sidebar.value.evidenceLoading, false)
  evidence.resolve(json([{ snippet: 'late result' }]))
  await searching
  assert.deepEqual(workspace.sidebar.value.evidenceResults, [])
})

test('switching panels while transcription is submitted keeps its background stream', async () => {
  const submission = deferred()
  let submitted
  const started = new Promise(resolve => { submitted = resolve })
  globalThis.fetch = async url => {
    if (url.startsWith('/analysis/transcribe?')) {
      submitted()
      return submission.promise
    }
    return json({ state: 'NOT_STARTED' })
  }
  const workspace = setup()
  const transcribing = workspace.transcribe(item.id)
  await started
  await workspace.openAgent({ id: 8, filename: 'another.mp4' })
  submission.resolve(json(null))
  await transcribing
  assert.equal(workspace.sidebar.value.mediaId, 8)
  assert.equal(workspace.starts.length, 1)
  assert.equal(workspace.starts[0][0], 7)
  assert.equal(workspace.starts[0][1], 'text')
})
