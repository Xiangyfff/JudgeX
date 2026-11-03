/** Tiny markdown renderer: enough for problem statements, no dependency. */

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function inline(s: string): string {
  let out = esc(s)
  out = out.replace(/`([^`]+)`/g, (_m, c) => `<code>${c}</code>`)
  out = out.replace(/\*\*([^*]+)\*\*/g, (_m, c) => `<strong>${c}</strong>`)
  out = out.replace(/\*([^*]+)\*/g, (_m, c) => `<em>${c}</em>`)
  return out
}

export function renderMarkdown(src: string): string {
  if (!src) return ''
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const out: string[] = []
  let inCode = false
  let inList = false
  let para: string[] = []

  const flushPara = () => {
    if (para.length) {
      out.push(`<p>${inline(para.join(' '))}</p>`)
      para = []
    }
  }
  const closeList = () => {
    if (inList) {
      out.push('</ul>')
      inList = false
    }
  }

  for (const raw of lines) {
    if (raw.trimStart().startsWith('```')) {
      if (inCode) {
        out.push('</code></pre>')
        inCode = false
      } else {
        flushPara()
        closeList()
        out.push('<pre><code>')
        inCode = true
      }
      continue
    }
    if (inCode) {
      out.push(esc(raw))
      continue
    }
    if (!raw.trim()) {
      flushPara()
      closeList()
      continue
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(raw)
    if (heading) {
      flushPara()
      closeList()
      const level = Math.min(heading[1].length + 1, 4)
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      continue
    }
    if (/^\s*[-*]\s+/.test(raw)) {
      flushPara()
      if (!inList) {
        out.push('<ul>')
        inList = true
      }
      out.push(`<li>${inline(raw.replace(/^\s*[-*]\s+/, ''))}</li>`)
      continue
    }
    if (/^\s*\d+\.\s+/.test(raw)) {
      flushPara()
      if (!inList) {
        out.push('<ul>')
        inList = true
      }
      out.push(`<li>${inline(raw.replace(/^\s*\d+\.\s+/, ''))}</li>`)
      continue
    }
    if (/^>\s?/.test(raw)) {
      flushPara()
      closeList()
      out.push(`<blockquote>${inline(raw.replace(/^>\s?/, ''))}</blockquote>`)
      continue
    }
    para.push(raw.trim())
  }
  flushPara()
  closeList()
  if (inCode) out.push('</code></pre>')
  return out.join('\n')
}
