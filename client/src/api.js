const API_BASE = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:9090').replace(/\/$/, '')
const TOKEN_KEY = 'authToken'

export function hasAuthToken() {
  return Boolean(localStorage.getItem(TOKEN_KEY))
}

export function setAuthToken(token) {
  if (!token) throw new Error('登录接口未返回有效令牌')
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  if (response.status === 401 && !path.startsWith('/user/')) {
    clearAuthToken()
    window.dispatchEvent(new Event('auth-expired'))
  }
  return response
}
