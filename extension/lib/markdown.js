/**
 * 分析结果的 Markdown → 普通数据结构的转换层。
 *
 * 这里刻意不碰 DOM，也不生成 HTML 字符串：扩展带有 tabs/storage 权限，
 * 一旦把模型输出当 HTML 解析，注入脚本的后果比普通网页严重得多。
 * 渲染层（markdownView.js）只消费本模块产出的数据，用 createElement/textContent 建树，
 * 因此 XSS 在构造上就不可能发生。
 */

const THINK_BLOCK = /<think>[\s\S]*?<\/think>/gi
const CLOSE_THINK = '</think>'
// 只认不带链接的时间戳，避免把 [00:30](...) 这类已有链接二次包装。
const BARE_TIMESTAMP = /\[((?:\d{1,2}:)?\d{1,2}:\d{2})\](?!\()/g
const TIMESTAMP_HREF = /^#video-t=(\d+)$/
const HEADING = /^(#{1,6})\s+(.*)$/
const FENCE = /^```(\w*)\s*$/
const QUOTE_LINE = /^>\s?(.*)$/
const LIST_ITEM = /^\s*(?:[-*+]|\d+[.)])\s+(.*)$/
const ORDERED_ITEM = /^\s*\d+[.)]\s+/
const HORIZONTAL_RULE = /^(?:-{3,}|\*{3,}|_{3,})$/
// 顺序敏感：** 必须排在 * 之前，否则粗体会被当成两个斜体。
const INLINE = /(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)|(\[([^\]]*)\]\(([^)\s]+)\))/g

/** 剔除模型可能带出的 <think> 推理残留；若整段都是推理，则回退原文避免结果为空。 */
export function stripThink(markdown) {
  const source = String(markdown ?? '')
  const withoutBlocks = source.replace(THINK_BLOCK, '')
  const cleaned = withoutBlocks.includes(CLOSE_THINK)
    ? withoutBlocks.split(CLOSE_THINK).pop()
    : withoutBlocks
  return cleaned.trim() ? cleaned : source
}

/** [00:30] → [00:30](#video-t=30)，沿用网页端既有约定，渲染层据此产出时间戳胶囊。 */
export function linkifyTimestamps(markdown) {
  return String(markdown ?? '').replace(BARE_TIMESTAMP, (_, label) => `[${label}](#video-t=${toSeconds(label)})`)
}

/** 时间戳标签转秒数，兼容 mm:ss 与 h:mm:ss 两种写法。 */
export function toSeconds(label) {
  const parts = String(label).split(':').map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
}

/** 行内元素解析。返回扁平的 token 数组，不做嵌套（后端产物用不到）。 */
export function parseInline(text) {
  const source = String(text ?? '')
  const tokens = []
  let cursor = 0

  for (const match of source.matchAll(INLINE)) {
    if (match.index > cursor) tokens.push({ type: 'text', value: source.slice(cursor, match.index) })
    tokens.push(inlineTokenOf(match))
    cursor = match.index + match[0].length
  }
  if (cursor < source.length) tokens.push({ type: 'text', value: source.slice(cursor) })
  return tokens
}

function inlineTokenOf(match) {
  const [, , strong, , emphasis, , code, , label, href] = match
  if (strong !== undefined) return { type: 'strong', value: strong }
  if (emphasis !== undefined) return { type: 'em', value: emphasis }
  if (code !== undefined) return { type: 'code', value: code }
  const timestamp = TIMESTAMP_HREF.exec(href || '')
  return timestamp
    ? { type: 'timestamp', seconds: Number(timestamp[1]), label }
    : { type: 'link', href, label }
}

/** 文档级解析：把 Markdown 切成块数组。支持标题、列表、引用、围栏代码、水平线与段落。 */
export function parseBlocks(markdown) {
  const lines = linkifyTimestamps(stripThink(markdown)).split(/\r?\n/)
  const blocks = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) {
      index += 1
      continue
    }
    const [block, next] = readBlock(lines, index)
    blocks.push(block)
    index = next
  }
  return blocks
}

/** 按行首特征分派；每个 read* 返回 [块, 下一行下标]。 */
function readBlock(lines, index) {
  const line = lines[index]
  if (FENCE.test(line.trim())) return readFence(lines, index)
  if (HORIZONTAL_RULE.test(line.trim())) return [{ type: 'hr' }, index + 1]

  const heading = HEADING.exec(line)
  if (heading) {
    return [{ type: 'heading', level: heading[1].length, tokens: parseInline(heading[2]) }, index + 1]
  }
  if (QUOTE_LINE.test(line)) return readQuote(lines, index)
  if (LIST_ITEM.test(line)) return readList(lines, index)
  return readParagraph(lines, index)
}

function readFence(lines, index) {
  const info = FENCE.exec(lines[index].trim())[1]
  const body = []
  let cursor = index + 1
  while (cursor < lines.length && !FENCE.test(lines[cursor].trim())) {
    body.push(lines[cursor])
    cursor += 1
  }
  // 未闭合的围栏按「读到文末」处理，避免整段内容被吞掉。
  return [{ type: 'code', language: info || '', text: body.join('\n') }, Math.min(cursor + 1, lines.length)]
}

function readQuote(lines, index) {
  const body = []
  let cursor = index
  while (cursor < lines.length && QUOTE_LINE.test(lines[cursor])) {
    body.push(QUOTE_LINE.exec(lines[cursor])[1])
    cursor += 1
  }
  return [{ type: 'quote', tokens: parseInline(body.join(' ').trim()) }, cursor]
}

function readList(lines, index) {
  const ordered = ORDERED_ITEM.test(lines[index])
  const items = []
  let cursor = index
  while (cursor < lines.length) {
    const item = LIST_ITEM.exec(lines[cursor])
    if (!item) break
    // 与首项类型不一致时换一个列表，避免有序无序混成一个。
    if (ORDERED_ITEM.test(lines[cursor]) !== ordered) break
    items.push(parseInline(item[1].trim()))
    cursor += 1
  }
  return [{ type: 'list', ordered, items }, cursor]
}

function readParagraph(lines, index) {
  const body = []
  let cursor = index
  while (cursor < lines.length && lines[cursor].trim() && !startsNewBlock(lines[cursor])) {
    body.push(lines[cursor].trim())
    cursor += 1
  }
  return [{ type: 'paragraph', tokens: parseInline(body.join(' ')) }, cursor]
}

function startsNewBlock(line) {
  const trimmed = line.trim()
  return FENCE.test(trimmed)
    || HORIZONTAL_RULE.test(trimmed)
    || HEADING.test(line)
    || QUOTE_LINE.test(line)
    || LIST_ITEM.test(line)
}

/**
 * 按二三级标题把块数组切成卡片：标题之前的内容进 intro（用于承载结果提示的引用块），
 * 每个标题及其后续内容成为一张卡片。
 *
 * 后端会把整篇标题也用 `##` 输出，因此「标题下没有任何正文」的那一项其实是文档标题而非章节，
 * 标记为 titleOnly 交由渲染层按标题而非卡片处理，避免出现空卡片。
 */
export function groupSections(blocks) {
  const intro = []
  const sections = []
  let current = null

  for (const block of blocks) {
    const isSectionHeading = block.type === 'heading' && block.level >= 2 && block.level <= 3
    if (isSectionHeading) {
      current = { title: block, blocks: [] }
      sections.push(current)
      continue
    }
    if (current) current.blocks.push(block)
    else intro.push(block)
  }
  return {
    intro,
    sections: sections.map(section => ({ ...section, titleOnly: section.blocks.length === 0 }))
  }
}
