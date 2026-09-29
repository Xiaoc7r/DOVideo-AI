import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  groupSections,
  linkifyTimestamps,
  parseBlocks,
  parseInline,
  stripThink,
  toSeconds
} from '../lib/markdown.js'

test('stripThink 剔除成对的推理块', () => {
  assert.equal(stripThink('<think>先想想</think>正文在此'), '正文在此')
})

test('stripThink 处理残留的闭合标签，保留其后的正文', () => {
  assert.equal(stripThink('推理内容</think>正文在此'), '正文在此')
})

test('stripThink 只有开标签时无法判断边界，保持原样', () => {
  assert.equal(stripThink('<think>残缺正文在此'), '<think>残缺正文在此')
})

test('stripThink 整段都是推理时回退原文，避免结果为空', () => {
  assert.equal(stripThink('<think>全是推理</think>'), '<think>全是推理</think>')
})

test('stripThink 无标签时原样返回', () => {
  assert.equal(stripThink('普通正文'), '普通正文')
})

test('toSeconds 兼容 mm:ss 与 h:mm:ss', () => {
  assert.equal(toSeconds('00:30'), 30)
  assert.equal(toSeconds('02:05'), 125)
  assert.equal(toSeconds('1:02:03'), 3723)
})

test('timestamps do not rewrite code or allow impossible seconds', () => {
  const blocks = parseBlocks('```text\n[00:30]\n```\n\n`[00:30]` 与 [00:30]')
  assert.equal(blocks[0].text, '[00:30]')
  assert.deepEqual(blocks[1].tokens, [
    { type: 'code', value: '[00:30]' },
    { type: 'text', value: ' 与 ' },
    { type: 'timestamp', seconds: 30, label: '00:30' }
  ])
  assert.equal(Number.isNaN(toSeconds('02:99')), true)
  assert.equal(linkifyTimestamps('[02:99]'), '[02:99]')
})

test('linkifyTimestamps 把裸时间戳转成锚点链接', () => {
  assert.equal(linkifyTimestamps('[00:30] 现在在我手里的'), '[00:30](#video-t=30) 现在在我手里的')
})

test('linkifyTimestamps 不二次包装已有链接', () => {
  const source = '[00:30](#video-t=30)'
  assert.equal(linkifyTimestamps(source), source)
})

test('parseInline 混排粗体、行内代码与时间戳', () => {
  assert.deepEqual(parseInline('**重点** 与 `代码` 见 [00:30](#video-t=30)'), [
    { type: 'strong', value: '重点' },
    { type: 'text', value: ' 与 ' },
    { type: 'code', value: '代码' },
    { type: 'text', value: ' 见 ' },
    { type: 'timestamp', seconds: 30, label: '00:30' }
  ])
})

test('parseInline 识别普通链接', () => {
  assert.deepEqual(parseInline('[官网](https://example.com)'), [
    { type: 'link', href: 'https://example.com', label: '官网' }
  ])
})

test('parseInline 无标记时返回单个文本 token', () => {
  assert.deepEqual(parseInline('纯文本'), [{ type: 'text', value: '纯文本' }])
})

test('parseBlocks 识别标题层级', () => {
  const blocks = parseBlocks('# 一级\n\n## 二级\n\n### 三级')
  assert.deepEqual(blocks.map(block => [block.type, block.level]), [
    ['heading', 1],
    ['heading', 2],
    ['heading', 3]
  ])
})

test('parseBlocks 区分有序与无序列表', () => {
  const blocks = parseBlocks('- 甲\n- 乙\n\n1. 丙\n2. 丁')
  assert.equal(blocks.length, 2)
  assert.equal(blocks[0].ordered, false)
  assert.equal(blocks[1].ordered, true)
  assert.deepEqual(blocks[0].items, [
    [{ type: 'text', value: '甲' }],
    [{ type: 'text', value: '乙' }]
  ])
})

test('parseBlocks 合并多行引用', () => {
  const [block] = parseBlocks('> 第一行\n> 第二行')
  assert.equal(block.type, 'quote')
  assert.deepEqual(block.tokens, [{ type: 'text', value: '第一行 第二行' }])
})

test('parseBlocks 解析围栏代码块', () => {
  const [block] = parseBlocks('```js\nconst a = 1\n```')
  assert.deepEqual(block, { type: 'code', language: 'js', text: 'const a = 1' })
})

test('parseBlocks 未闭合的围栏读取到文末', () => {
  const [block] = parseBlocks('```\n未闭合内容')
  assert.equal(block.type, 'code')
  assert.equal(block.text, '未闭合内容')
})

test('parseBlocks 识别水平线', () => {
  assert.deepEqual(parseBlocks('---'), [{ type: 'hr' }])
})

test('parseBlocks 把连续文本行合成一个段落', () => {
  const blocks = parseBlocks('第一行\n第二行')
  assert.equal(blocks.length, 1)
  assert.deepEqual(blocks[0].tokens, [{ type: 'text', value: '第一行 第二行' }])
})

test('groupSections 把标题前的内容归入 intro', () => {
  const { intro, sections } = groupSections(parseBlocks('> 提示\n\n## 核心结论\n- 甲'))
  assert.equal(intro.length, 1)
  assert.equal(intro[0].type, 'quote')
  assert.equal(sections.length, 1)
  assert.deepEqual(sections[0].title.tokens, [{ type: 'text', value: '核心结论' }])
})

test('groupSections 按二三级标题切多张卡片', () => {
  const { sections } = groupSections(parseBlocks('## 甲\n- 1\n\n## 乙\n- 2\n\n### 丙\n- 3'))
  assert.deepEqual(sections.map(section => section.title.tokens[0].value), ['甲', '乙', '丙'])
  assert.equal(sections[2].blocks.length, 1)
})

test('groupSections 一级标题不进卡片，留在 intro', () => {
  const { intro, sections } = groupSections(parseBlocks('# 大标题\n\n## 卡片'))
  assert.equal(intro[0].type, 'heading')
  assert.equal(sections.length, 1)
})

// 后端把整篇标题也输出成 ##，这类「标题下没有正文」的项不该生成空卡片。
test('groupSections 把无正文的标题标记为 titleOnly', () => {
  const { sections } = groupSections(parseBlocks('## 苹果发布会亮点解析\n\n## 核心结论\n- 甲'))
  assert.equal(sections[0].titleOnly, true)
  assert.equal(sections[1].titleOnly, false)
})
