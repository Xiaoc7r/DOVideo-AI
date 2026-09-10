/**
 * 把 markdown.js 产出的数据结构渲染成 DOM。
 *
 * 全程使用 createElement / textContent，**从不拼接 HTML 字符串、从不使用 innerHTML**。
 * 这是本模块与网页端渲染方案（marked + 白名单过滤）最根本的区别：
 * 网页端依赖过滤规则不出纰漏，这里让注入在构造上无法发生。
 */

import { groupSections, parseBlocks } from './markdown.js'

/**
 * 渲染分析结果。
 * @param {string} markdown 后端返回的结果原文
 * @param {{ onSeek?: (seconds: number) => void, document?: Document }} options
 * @returns {DocumentFragment}
 */
export function renderResult(markdown, { onSeek, document: doc = document } = {}) {
  const context = { doc, onSeek }
  const fragment = doc.createDocumentFragment()
  const { intro, sections } = groupSections(parseBlocks(markdown))

  for (const block of intro) fragment.append(renderBlock(block, context, { inIntro: true }))
  for (const section of sections) {
    fragment.append(section.titleOnly
      ? renderDocumentTitle(section, context)
      : renderCard(section, context))
  }
  return fragment
}

/** 没有正文的标题其实是整篇文档的标题，按标题渲染，不生成空卡片。 */
function renderDocumentTitle(section, context) {
  const heading = context.doc.createElement('h1')
  heading.className = 'result-title'
  heading.append(...renderInline(section.title.tokens, context))
  return heading
}

/** 章节卡片：左侧强调色竖线 + 标题 + 内容。 */
function renderCard(section, context) {
  const card = context.doc.createElement('section')
  card.className = 'card'

  const title = context.doc.createElement('h2')
  title.className = 'card-title'
  title.append(...renderInline(section.title.tokens, context))
  card.append(title)

  const body = context.doc.createElement('div')
  body.className = 'card-body'
  for (const block of section.blocks) body.append(renderBlock(block, context, { inIntro: false }))
  card.append(body)
  return card
}

function renderBlock(block, context, { inIntro }) {
  const { doc } = context
  switch (block.type) {
    // 一级标题只出现在结果开头，作为整篇的标题而非卡片。
    case 'heading': {
      const heading = doc.createElement(block.level === 1 ? 'h1' : 'h3')
      heading.className = block.level === 1 ? 'result-title' : 'sub-title'
      heading.append(...renderInline(block.tokens, context))
      return heading
    }
    case 'list': {
      const list = doc.createElement(block.ordered ? 'ol' : 'ul')
      for (const item of block.items) {
        const li = doc.createElement('li')
        li.append(...renderInline(item, context))
        list.append(li)
      }
      return list
    }
    // 结果提示这类引用块渲染成 callout，避免「部分结论未通过校验」被淹没在正文里。
    case 'quote': {
      const callout = doc.createElement('div')
      callout.className = inIntro ? 'callout' : 'quote'
      callout.append(...renderInline(block.tokens, context))
      return callout
    }
    case 'code': {
      const pre = doc.createElement('pre')
      pre.className = 'code'
      const code = doc.createElement('code')
      code.textContent = block.text
      pre.append(code)
      return pre
    }
    case 'hr':
      return doc.createElement('hr')
    default: {
      const paragraph = doc.createElement('p')
      paragraph.append(...renderInline(block.tokens, context))
      return paragraph
    }
  }
}

function renderInline(tokens, context) {
  return tokens.map(token => renderToken(token, context))
}

function renderToken(token, context) {
  const { doc, onSeek } = context
  switch (token.type) {
    case 'strong': {
      const strong = doc.createElement('strong')
      strong.textContent = token.value
      return strong
    }
    case 'em': {
      const emphasis = doc.createElement('em')
      emphasis.textContent = token.value
      return emphasis
    }
    case 'code': {
      const code = doc.createElement('code')
      code.textContent = token.value
      return code
    }
    // 时间戳用 button 而非 a：它触发的是「跳转视频」这个动作，不是导航语义。
    case 'timestamp': {
      const button = doc.createElement('button')
      button.type = 'button'
      button.className = 'timestamp'
      button.dataset.seconds = String(token.seconds)
      button.textContent = token.label
      button.title = `跳到视频 ${token.label}`
      if (onSeek) button.addEventListener('click', () => onSeek(token.seconds))
      else button.disabled = true
      return button
    }
    case 'link': {
      const link = doc.createElement('a')
      link.textContent = token.label
      // 只放行 http(s)：模型输出可能夹带 javascript: 这类伪协议。
      if (/^https?:\/\//i.test(token.href || '')) {
        link.href = token.href
        link.target = '_blank'
        link.rel = 'noopener noreferrer'
      }
      return link
    }
    default: {
      const span = doc.createElement('span')
      span.textContent = token.value
      return span
    }
  }
}
