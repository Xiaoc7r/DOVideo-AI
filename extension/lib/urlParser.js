/**
 * 把输入文本规整成绝对 URL；无法解析返回 null。
 * 已带 http/https 前缀的按原样解析；否则尝试补 https://。
 * 主机名必须包含点（或为 localhost），避免把裸文本误当成主机名。
 */
function toUrl(raw) {
  const text = String(raw || '').trim()
  if (!text) return null
  const candidates = [text]
  if (!/^https?:\/\//i.test(text)) candidates.push(`https://${text}`)
  for (const candidate of candidates) {
    try {
      const url = new URL(candidate)
      const isHttp = url.protocol === 'http:' || url.protocol === 'https:'
      const looksLikeHost = url.hostname.includes('.') || url.hostname === 'localhost'
      if (isHttp && looksLikeHost) return url
    } catch {
      // 换下一个候选继续尝试。
    }
  }
  return null
}

const BILI_HOST = /(?:^|\.)(?:bilibili\.com|b23\.tv)$/
const YOUTUBE_HOST = /(?:^|\.)youtube\.com$/

/**
 * 严格识别「当前标签页是否是可提交给后端 yt-dlp 的视频页」。
 * 返回 { ok: true, url } 或 { ok: false }；url 保留原始查询参数，
 * 由后端 yt-dlp 自行解析（清晰度、分 P 等参数都交给它处理）。
 */
export function parseVideoUrl(rawUrl) {
  const url = toUrl(rawUrl)
  if (!url) return { ok: false }

  if (BILI_HOST.test(url.hostname)) {
    // b23.tv 是 B 站短链，任意路径都指向视频，直接放行。
    if (url.hostname.endsWith('b23.tv')) return { ok: true, url: url.href }
    if (url.pathname.startsWith('/video/')) return { ok: true, url: url.href }
    return { ok: false }
  }
  if (YOUTUBE_HOST.test(url.hostname)) {
    if (url.pathname === '/watch' && url.searchParams.get('v')) return { ok: true, url: url.href }
    if (/^\/(shorts|live|embed)\/[\w-]+/.test(url.pathname)) return { ok: true, url: url.href }
    return { ok: false }
  }
  if (url.hostname === 'youtu.be' && /^\/[\w-]+/.test(url.pathname)) {
    return { ok: true, url: url.href }
  }
  return { ok: false }
}

/**
 * 手动粘贴兜底：后端 yt-dlp 支持大量站点，这里只做最基本的 http/https 校验，
 * 不做站点白名单限制。
 */
export function isValidHttpUrl(rawUrl) {
  const url = toUrl(rawUrl)
  return url !== null && (url.protocol === 'http:' || url.protocol === 'https:')
}

/**
 * 视频身份：把同一视频的不同写法（带/不带查询参数、短链域名差异）归一成同一个键。
 * 无法确定身份时返回 null —— 调用方据此拒绝操作，避免在错误的页面上跳转。
 * b23.tv 短链需要发请求才能展开，这里不做解析，一律视为无法判定。
 */
export function videoKey(rawUrl) {
  const url = toUrl(rawUrl)
  if (!url) return null

  if (BILI_HOST.test(url.hostname)) {
    if (url.hostname.endsWith('b23.tv')) return null
    const match = /^\/video\/(BV[\w]+)/i.exec(url.pathname)
    return match ? `bilibili:${match[1].toUpperCase()}` : null
  }
  if (YOUTUBE_HOST.test(url.hostname)) {
    const id = url.searchParams.get('v') || /^\/(?:shorts|live|embed)\/([\w-]+)/.exec(url.pathname)?.[1]
    return id ? `youtube:${id}` : null
  }
  if (url.hostname === 'youtu.be') {
    const id = /^\/([\w-]+)/.exec(url.pathname)?.[1]
    return id ? `youtube:${id}` : null
  }
  return null
}

/** 判断两个地址是否是同一个视频；任一方无法判定时返回 false。 */
export function isSameVideo(left, right) {
  const leftKey = videoKey(left)
  return leftKey !== null && leftKey === videoKey(right)
}

/**
 * 给视频地址附加起播时间。B 站与 YouTube 都用 t 参数，但 YouTube 要求带单位后缀。
 * 无法解析时返回 null，调用方应放弃跳转。
 */
export function withTimestamp(rawUrl, seconds) {
  const url = toUrl(rawUrl)
  if (!url) return null
  const offset = Math.max(0, Math.floor(seconds))
  const needsUnit = YOUTUBE_HOST.test(url.hostname) || url.hostname === 'youtu.be'
  url.searchParams.set('t', needsUnit ? `${offset}s` : String(offset))
  return url.href
}
