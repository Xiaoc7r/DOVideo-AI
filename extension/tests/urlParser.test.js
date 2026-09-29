import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isSameVideo, isValidHttpUrl, normalizeHttpUrl, parseVideoUrl, videoKey, withTimestamp } from '../lib/urlParser.js'

test('识别 B 站视频页并保留查询参数', () => {
  const parsed = parseVideoUrl('https://www.bilibili.com/video/BV1xx411c7mD?p=2&spm_id_from=333')
  assert.equal(parsed.ok, true)
  assert.equal(parsed.url, 'https://www.bilibili.com/video/BV1xx411c7mD?p=2&spm_id_from=333')
})

test('识别 B 站移动端与短链域名', () => {
  assert.equal(parseVideoUrl('https://m.bilibili.com/video/BV1xx411c7mD').ok, true)
  assert.equal(parseVideoUrl('https://b23.tv/ab12cd3').ok, true)
})

test('拒绝 B 站非视频页', () => {
  assert.equal(parseVideoUrl('https://www.bilibili.com/').ok, false)
  assert.equal(parseVideoUrl('https://live.bilibili.com/12345').ok, false)
  assert.equal(parseVideoUrl('https://space.bilibili.com/946974').ok, false)
})

test('识别 YouTube 观看页、Shorts、直播与嵌入页', () => {
  assert.equal(parseVideoUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s').ok, true)
  assert.equal(parseVideoUrl('https://www.youtube.com/shorts/dQw4w9WgXcQ').ok, true)
  assert.equal(parseVideoUrl('https://www.youtube.com/live/dQw4w9WgXcQ').ok, true)
  assert.equal(parseVideoUrl('https://www.youtube.com/embed/dQw4w9WgXcQ').ok, true)
})

test('识别 youtu.be 短链', () => {
  const parsed = parseVideoUrl('https://youtu.be/dQw4w9WgXcQ?t=10')
  assert.equal(parsed.ok, true)
  assert.equal(parsed.url, 'https://youtu.be/dQw4w9WgXcQ?t=10')
})

test('拒绝 YouTube 非视频页与缺少 v 参数的观看页', () => {
  assert.equal(parseVideoUrl('https://www.youtube.com/').ok, false)
  assert.equal(parseVideoUrl('https://www.youtube.com/feed/subscriptions').ok, false)
  assert.equal(parseVideoUrl('https://www.youtube.com/watch?list=PL123').ok, false)
})

test('自动补全缺失的协议头', () => {
  const parsed = parseVideoUrl('bilibili.com/video/BV1xx411c7mD')
  assert.equal(parsed.ok, true)
  assert.equal(parsed.url, 'https://bilibili.com/video/BV1xx411c7mD')
})

test('拒绝无法解析或非视频站点的输入', () => {
  assert.equal(parseVideoUrl('').ok, false)
  assert.equal(parseVideoUrl('   ').ok, false)
  assert.equal(parseVideoUrl('随便一段文字').ok, false)
  assert.equal(parseVideoUrl('https://vimeo.com/76979871').ok, false)
})

test('手动粘贴兜底只校验 http/https 合法性', () => {
  assert.equal(isValidHttpUrl('https://vimeo.com/76979871'), true)
  assert.equal(isValidHttpUrl('http://example.com/video.mp4'), true)
  assert.equal(isValidHttpUrl('ftp://example.com/video.mp4'), false)
  assert.equal(isValidHttpUrl('不是链接'), false)
  assert.equal(isValidHttpUrl(''), false)
})

test('videoKey 忽略查询参数差异，归一同一视频', () => {
  assert.equal(
    videoKey('https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.1007'),
    videoKey('https://www.bilibili.com/video/BV1xx411c7mD?p=1')
  )
  assert.equal(
    videoKey('https://www.youtube.com/watch?v=dQw4w9WgXcQ'),
    videoKey('https://youtu.be/dQw4w9WgXcQ')
  )
})

test('Bilibili identity distinguishes parts and preserves the case-sensitive video ID', () => {
  const base = 'https://www.bilibili.com/video/BV1xx411c7mD'
  assert.equal(isSameVideo(base, `${base}?p=2`), false)
  assert.equal(isSameVideo(`${base}?p=2`, `${base}?p=2&t=30`), true)
  assert.equal(isSameVideo(base, base.replace('mD', 'md')), false)
  assert.equal(videoKey(`${base}?p=NaN`), null)
})

test('manual URLs are normalized before being submitted', () => {
  assert.equal(normalizeHttpUrl('  vimeo.com/76979871  '), 'https://vimeo.com/76979871')
  assert.equal(normalizeHttpUrl('ftp://example.com/video'), null)
  assert.equal(withTimestamp('https://youtu.be/abc', NaN), null)
  assert.equal(withTimestamp('https://youtu.be/abc', Infinity), null)
})

test('videoKey 对短链与未知站点返回 null，交由调用方拒绝操作', () => {
  assert.equal(videoKey('https://b23.tv/ab12cd3'), null)
  assert.equal(videoKey('https://vimeo.com/76979871'), null)
  assert.equal(videoKey('不是链接'), null)
})

test('isSameVideo 任一方无法判定时返回 false', () => {
  assert.equal(isSameVideo('https://b23.tv/ab12cd3', 'https://b23.tv/ab12cd3'), false)
  assert.equal(
    isSameVideo('https://www.bilibili.com/video/BV1xx411c7mD', 'https://www.bilibili.com/video/BV2yy522d8nE'),
    false
  )
})

test('withTimestamp 按站点附加正确的起播参数', () => {
  assert.equal(
    withTimestamp('https://www.bilibili.com/video/BV1xx411c7mD', 30),
    'https://www.bilibili.com/video/BV1xx411c7mD?t=30'
  )
  assert.equal(
    withTimestamp('https://www.youtube.com/watch?v=dQw4w9WgXcQ', 30),
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s'
  )
})

test('withTimestamp 覆盖已有的 t 参数而非追加', () => {
  assert.equal(
    withTimestamp('https://www.bilibili.com/video/BV1xx411c7mD?t=5&p=2', 90),
    'https://www.bilibili.com/video/BV1xx411c7mD?t=90&p=2'
  )
})

test('withTimestamp 无法解析时返回 null', () => {
  assert.equal(withTimestamp('随便一段文字', 30), null)
})
