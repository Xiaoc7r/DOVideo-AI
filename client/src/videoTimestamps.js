/** Only transform prose tokens; code and existing links must retain their original meaning. */
export function linkTimestampTokens(tokens) {
  let literalDepth = 0
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index]
    if (token.type === 'html') {
      for (const match of token.raw.matchAll(/<(\/?)(?:a|code|pre)\b[^>]*>/gi)) {
        literalDepth = Math.max(0, literalDepth + (match[1] ? -1 : 1))
      }
      continue
    }
    if (literalDepth || ['link', 'image', 'code', 'codespan', 'escape'].includes(token.type)) continue
    if (token.tokens) linkTimestampTokens(token.tokens)
    else if (token.type === 'text' && !token.escaped) {
      const parts = []
      let cursor = 0
      for (const match of token.text.matchAll(/\[(\d+:\d{2}(?::\d{2})?)\]/g)) {
        const values = match[1].split(':').map(Number)
        if (values.at(-1) >= 60 || (values.length === 3 && values[1] >= 60)) continue
        const seconds = values.reduce((total, value) => total * 60 + value, 0)
        if (!Number.isSafeInteger(seconds)) continue
        if (match.index > cursor) parts.push({ ...token, text: token.text.slice(cursor, match.index) })
        parts.push({ type: 'link', raw: match[0], href: `#video-t=${seconds}`, title: null,
          text: match[1], tokens: [{ type: 'text', raw: match[1], text: match[1] }] })
        cursor = match.index + match[0].length
      }
      if (parts.length) {
        if (cursor < token.text.length) parts.push({ ...token, text: token.text.slice(cursor) })
        tokens.splice(index, 1, ...parts)
        index += parts.length - 1
      }
    }
    if (token.type === 'list') token.items.forEach(item => linkTimestampTokens(item.tokens))
    if (token.type === 'table') {
      for (const cell of [...token.header, ...token.rows.flat()]) linkTimestampTokens(cell.tokens)
    }
  }
  return tokens
}
