import assert from 'node:assert/strict'
import test from 'node:test'
import { marked } from 'marked'
import { linkTimestampTokens } from './videoTimestamps.js'

const render = markdown => marked.parser(linkTimestampTokens(marked.lexer(markdown)))

test('timestamps in prose, lists and tables link correctly, including long videos', () => {
  const html = render('**证据 [01:02]**\n\n- [123:45]\n\n| 时间 |\n| --- |\n| [2:03:04] |')
  assert.match(html, /href="#video-t=62"/)
  assert.match(html, /href="#video-t=7425"/)
  assert.match(html, /href="#video-t=7384"/)
})

test('code, escaped timestamps and existing link destinations remain literal', () => {
  const source = '`[01:02]`\n\n```js\nconst t = "[01:02]"\n```\n\n\\[01:02] [[01:02]](https://example.com)'
  const html = render(source)
  assert.doesNotMatch(html, /#video-t=/)
  assert.match(html, /<code>\[01:02\]<\/code>/)
  assert.match(html, /href="https:\/\/example.com"/)
})

test('invalid times and unsafe integers stay text without corrupting surrounding prose', () => {
  const html = render('[01:99] [1:60:00] [99999999999999999999:00] valid [00:00] end')
  assert.equal((html.match(/#video-t=/g) || []).length, 1)
  assert.match(html, /\[01:99\]/)
  assert.match(html, /#video-t=0/)
  assert.match(html, /end/)
})

test('raw HTML links and inline code are not given nested timestamp anchors', () => {
  const html = render('<a href="https://example.com">[01:02]</a> <code>[01:02]</code> [00:03]')
  assert.equal((html.match(/#video-t=/g) || []).length, 1)
  assert.match(html, /#video-t=3/)
})
