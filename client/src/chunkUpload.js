import { apiRequest } from './api'

const CHUNK_SIZE = 5 * 1024 * 1024
const UPLOAD_CONCURRENCY = 3
const MAX_TOTAL_CHUNKS = 410

export async function uploadVideoInChunks(file, onProgress = () => {}) {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  if (!totalChunks) throw new Error('视频文件为空')
  if (totalChunks > MAX_TOTAL_CHUNKS) throw new Error('视频大小不能超过 2GB')
  const storageKey = `upload:${file.name}:${file.size}:${file.lastModified}`
  let uploadId = localStorage.getItem(storageKey)
  let uploadedChunks = new Set()

  if (uploadId) {
    const response = await apiRequest(`/media/upload-status?uploadId=${encodeURIComponent(uploadId)}`)
    if (response.ok) {
      uploadedChunks = new Set(await response.json())
    } else {
      localStorage.removeItem(storageKey)
      uploadId = null
    }
  }

  if (!uploadId) {
    uploadId = await initializeUpload(file.name, totalChunks)
    localStorage.setItem(storageKey, uploadId)
  }

  const pendingChunks = Array.from({ length: totalChunks }, (_, index) => index)
    .filter(index => !uploadedChunks.has(index))
  let cursor = 0
  let completedChunks = uploadedChunks.size
  let uploadError = null
  onProgress({ phase: 'uploading', completedChunks, totalChunks })

  const worker = async () => {
    while (!uploadError && cursor < pendingChunks.length) {
      const index = pendingChunks[cursor++]
      try {
        await uploadChunk(file, uploadId, index, totalChunks)
        completedChunks += 1
        onProgress({ phase: 'uploading', completedChunks, totalChunks })
      } catch (error) {
        uploadError = error
      }
    }
  }
  await Promise.all(Array.from(
    { length: Math.min(UPLOAD_CONCURRENCY, pendingChunks.length) }, worker
  ))
  if (uploadError) throw uploadError

  onProgress({ phase: 'merging', completedChunks, totalChunks })
  const completeParams = new URLSearchParams({ uploadId })
  const completeResponse = await apiRequest(`/media/complete-upload?${completeParams}`, { method: 'POST' })
  if (!completeResponse.ok) {
    throw new Error(await completeResponse.text() || 'Upload merge failed')
  }
  localStorage.removeItem(storageKey)
}

async function initializeUpload(filename, totalChunks) {
  const params = new URLSearchParams({ filename, totalChunks: String(totalChunks) })
  const response = await apiRequest(`/media/init-upload?${params}`, { method: 'POST' })
  const body = await response.text()
  if (!response.ok) throw new Error(body || 'Failed to initialize upload')
  return body
}

async function uploadChunk(file, uploadId, chunkIndex, totalChunks) {
  const formData = new FormData()
  formData.append('uploadId', uploadId)
  formData.append('chunkIndex', String(chunkIndex))
  formData.append('totalChunks', String(totalChunks))
  formData.append('file', file.slice(
    chunkIndex * CHUNK_SIZE,
    Math.min(file.size, (chunkIndex + 1) * CHUNK_SIZE)
  ))

  const response = await apiRequest('/media/upload-chunk', { method: 'POST', body: formData })
  if (!response.ok) throw new Error(await response.text() || `Chunk ${chunkIndex} failed`)
}
