import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { hasUploadProgress, uploadVideoInChunks, validateVideoFile } from './chunkUpload.js'

const file = new File([new Uint8Array(8)], 'sample.mp4', { lastModified: 7 })
const json = data => Response.json({ code: 0, message: 'success', data })

beforeEach(() => {
  const storage = new Map()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
})

test('a lost merge response resumes directly to completion without uploading again', async () => {
  let initialized = 0
  let chunks = 0
  let completions = 0
  globalThis.fetch = async url => {
    if (url.startsWith('/media/init-upload')) { initialized += 1; return json('upload-id') }
    if (url.startsWith('/media/upload-status')) return json([0])
    if (url === '/media/upload-chunk') { chunks += 1; return json(null) }
    if (url.startsWith('/media/complete-upload')) {
      if (++completions === 1) throw new TypeError('connection lost after merge')
      return json({ id: 42, filename: file.name })
    }
    throw new Error(`unexpected request ${url}`)
  }
  await assert.rejects(uploadVideoInChunks(file, () => {}, undefined, 7))
  assert.equal(hasUploadProgress(file, 7), true)
  assert.equal(hasUploadProgress(file, 8), false)
  const result = await uploadVideoInChunks(file, () => {}, undefined, 7)
  assert.equal(result.id, 42)
  assert.equal(initialized, 1)
  assert.equal(chunks, 1)
  assert.equal(completions, 2)
  assert.equal(hasUploadProgress(file, 7), false)
})

test('a temporary status failure preserves resumable chunks', async () => {
  localStorage.setItem(`upload:7:${file.name}:${file.size}:${file.lastModified}`, 'upload-id')
  const requests = []
  globalThis.fetch = async url => {
    requests.push(url)
    return new Response('temporarily unavailable', { status: 503 })
  }
  await assert.rejects(uploadVideoInChunks(file, () => {}, undefined, 7), /temporarily unavailable/)
  assert.equal(hasUploadProgress(file, 7), true)
  assert.equal(requests.length, 1)
  assert.ok(requests[0].startsWith('/media/upload-status'))
})

test('server extension limits apply even if a browser reports a video MIME type', () => {
  assert.match(validateVideoFile(new File(['x'], 'unsupported.wmv', { type: 'video/x-ms-wmv' })), /仅支持/)
  assert.equal(validateVideoFile(new File(['x'], 'SUPPORTED.MP4')), '')
  assert.match(validateVideoFile(new File([], 'empty.mp4')), /大小为 0/)
})

test('legacy progress is migrated only after ownership is verified', async () => {
  const legacyKey = `upload:${file.name}:${file.size}:${file.lastModified}`
  localStorage.setItem(legacyKey, 'legacy-upload')
  globalThis.fetch = async url => {
    if (url.startsWith('/media/upload-status')) return json([0])
    if (url.startsWith('/media/complete-upload')) return new Response('retry later', { status: 503 })
    throw new Error('must not upload again')
  }
  await assert.rejects(uploadVideoInChunks(file, () => {}, undefined, 7), /retry later/)
  assert.equal(localStorage.getItem(legacyKey), null)
  assert.equal(hasUploadProgress(file, 7), true)
  assert.equal(hasUploadProgress(file, 8), false)
})

test('another account can start a new upload without destroying legacy progress', async () => {
  const legacyKey = `upload:${file.name}:${file.size}:${file.lastModified}`
  localStorage.setItem(legacyKey, 'legacy-upload')
  globalThis.fetch = async url => {
    if (url.startsWith('/media/upload-status')) return new Response('forbidden', { status: 403 })
    if (url.startsWith('/media/init-upload')) return json('new-upload')
    if (url === '/media/upload-chunk') return json(null)
    if (url.startsWith('/media/complete-upload')) return json({ id: 42 })
    throw new Error(`unexpected request ${url}`)
  }
  assert.equal((await uploadVideoInChunks(file, () => {}, undefined, 8)).id, 42)
  assert.equal(localStorage.getItem(legacyKey), 'legacy-upload')
})

test('failed storage migration preserves the legacy receipt for another retry', async () => {
  const legacyKey = `upload:${file.name}:${file.size}:${file.lastModified}`
  localStorage.setItem(legacyKey, 'legacy-upload')
  localStorage.setItem = () => { throw new DOMException('Storage full', 'QuotaExceededError') }
  globalThis.fetch = async url => {
    if (url.startsWith('/media/upload-status')) return json([0])
    if (url.startsWith('/media/complete-upload')) return new Response('retry later', { status: 503 })
    throw new Error('must not upload again')
  }
  await assert.rejects(uploadVideoInChunks(file, () => {}, undefined, 7), /retry later/)
  assert.equal(localStorage.getItem(legacyKey), 'legacy-upload')
})

test('cancelling while merging is reported as cancellation and retains progress', async () => {
  const controller = new AbortController()
  globalThis.fetch = async url => {
    if (url.startsWith('/media/init-upload')) return json('upload-id')
    if (url === '/media/upload-chunk') return json(null)
    if (url.startsWith('/media/complete-upload')) {
      controller.abort()
      throw new DOMException('cancelled', 'AbortError')
    }
    throw new Error(`unexpected request ${url}`)
  }
  await assert.rejects(uploadVideoInChunks(file, () => {}, controller.signal, 7), error => error.aborted === true)
  assert.equal(hasUploadProgress(file, 7), true)
})
