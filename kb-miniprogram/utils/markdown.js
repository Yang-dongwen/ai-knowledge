/**
 * 轻量 Markdown → HTML（供 rich-text 使用）
 * 支持：标题、粗体/斜体、行内代码、代码块、列表、任务列表、引用、链接、
 *       分隔线、段落、GFM 表格
 * 全部块级/行内标签带 inline style（rich-text 无法应用页面 CSS）
 * 不执行任意 HTML 注入（先整体转义）
 */

const S = {
  h1: 'font-size:22px;font-weight:700;line-height:1.35;color:#0f172a;margin:18px 0 10px;',
  h2: 'font-size:19px;font-weight:700;line-height:1.35;color:#0f172a;margin:16px 0 8px;',
  h3: 'font-size:17px;font-weight:650;line-height:1.4;color:#0f172a;margin:14px 0 8px;',
  h4: 'font-size:16px;font-weight:650;line-height:1.4;color:#1e293b;margin:12px 0 6px;',
  h5: 'font-size:15px;font-weight:650;line-height:1.45;color:#1e293b;margin:12px 0 6px;',
  h6: 'font-size:14px;font-weight:650;line-height:1.45;color:#334155;margin:10px 0 6px;',
  p: 'font-size:15px;line-height:1.75;color:#1e293b;margin:0 0 12px;',
  ul: 'margin:0 0 12px;padding-left:22px;color:#1e293b;',
  ol: 'margin:0 0 12px;padding-left:22px;color:#1e293b;',
  li: 'font-size:15px;line-height:1.7;margin:0 0 4px;',
  blockquote:
    'margin:0 0 12px;padding:8px 12px;border-left:4px solid #4f46e5;background:#f8fafc;color:#475569;border-radius:0 8px 8px 0;',
  pre:
    'margin:0 0 12px;padding:12px;background:#0f172a;color:#e2e8f0;border-radius:10px;font-size:13px;line-height:1.55;overflow:auto;white-space:pre-wrap;word-break:break-word;',
  codeBlock: 'font-family:ui-monospace,Menlo,Consolas,monospace;color:#e2e8f0;background:transparent;',
  codeInline:
    'font-family:ui-monospace,Menlo,Consolas,monospace;font-size:0.9em;background:#f1f5f9;color:#be123c;padding:1px 5px;border-radius:4px;',
  hr: 'border:none;border-top:1px solid #e2e8f0;margin:16px 0;',
  a: 'color:#4f46e5;text-decoration:underline;',
  strong: 'font-weight:700;color:#0f172a;',
  em: 'font-style:italic;',
  img: 'max-width:100%;height:auto;border-radius:8px;display:block;margin:8px 0;',
  table: 'border-collapse:collapse;width:100%;margin:0 0 14px;font-size:14px;line-height:1.5;',
  th: 'border:1px solid #cbd5e1;background:#f1f5f9;padding:8px 10px;font-weight:650;color:#0f172a;',
  td: 'border:1px solid #e2e8f0;padding:8px 10px;color:#1e293b;vertical-align:top;',
  tableWrap: 'width:100%;overflow:auto;margin:0 0 4px;'
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function inline(text) {
  const imgs = []
  let raw = String(text).replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, alt, url) => {
    const i = imgs.length
    imgs.push({ alt: alt || '', url: url || '' })
    return `@@IMG${i}@@`
  })
  raw = raw.replace(/<img\b[^>]*\/?>/gi, (m) => {
    const srcM = m.match(/\bsrc=["']([^"']+)["']/i)
    const altM = m.match(/\balt=["']([^"']*)["']/i)
    if (!srcM) return escapeHtml(m)
    const i = imgs.length
    imgs.push({ alt: (altM && altM[1]) || '', url: srcM[1] })
    return `@@IMG${i}@@`
  })

  let s = escapeHtml(raw)
  s = s.replace(/`([^`]+)`/g, '<code style="' + S.codeInline + '">$1</code>')
  s = s.replace(/\*\*(.+?)\*\*/g, '<strong style="' + S.strong + '">$1</strong>')
  s = s.replace(/__(.+?)__/g, '<strong style="' + S.strong + '">$1</strong>')
  s = s.replace(/\*(.+?)\*/g, '<em style="' + S.em + '">$1</em>')
  s = s.replace(/_(.+?)_/g, '<em style="' + S.em + '">$1</em>')
  s = s.replace(
    /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
    '<a href="$2" style="' + S.a + '">$1</a>'
  )
  s = s.replace(/@@IMG(\d+)@@/g, (_m, idx) => {
    const img = imgs[Number(idx)]
    if (!img) return ''
    const alt = escapeHtml(img.alt)
    const url = String(img.url).replace(/"/g, '')
    return '<img src="' + url + '" alt="' + alt + '" style="' + S.img + '"/>'
  })
  return s
}

function isTableSep(line) {
  const t = String(line).trim()
  if (!t || t.indexOf('|') === -1) return false
  // |---|:---:|---| or ---|---
  const core = t.replace(/^\|/, '').replace(/\|$/, '')
  const parts = core.split('|')
  if (!parts.length) return false
  return parts.every((p) => /^:?-{3,}:?$/.test(p.trim()))
}

function isTableRow(line) {
  const t = String(line).trim()
  if (!t || t.indexOf('|') === -1) return false
  if (isTableSep(t)) return false
  return true
}

function splitTableRow(line) {
  let t = String(line).trim()
  if (t.startsWith('|')) t = t.slice(1)
  if (t.endsWith('|')) t = t.slice(0, -1)
  return t.split('|').map((c) => c.trim())
}

function parseAligns(sepLine) {
  let t = String(sepLine).trim()
  if (t.startsWith('|')) t = t.slice(1)
  if (t.endsWith('|')) t = t.slice(0, -1)
  return t.split('|').map((c) => {
    const p = c.trim()
    const left = p.startsWith(':')
    const right = p.endsWith(':')
    if (left && right) return 'center'
    if (right) return 'right'
    if (left) return 'left'
    return 'left'
  })
}

function cellStyle(base, align) {
  const a = align === 'center' ? 'center' : align === 'right' ? 'right' : 'left'
  return base + 'text-align:' + a + ';'
}

function renderTable(headerLine, sepLine, bodyLines) {
  const headers = splitTableRow(headerLine)
  const aligns = parseAligns(sepLine)
  let html = '<div style="' + S.tableWrap + '"><table style="' + S.table + '"><thead><tr>'
  headers.forEach((h, i) => {
    html +=
      '<th style="' +
      cellStyle(S.th, aligns[i] || 'left') +
      '">' +
      inline(h) +
      '</th>'
  })
  html += '</tr></thead><tbody>'
  bodyLines.forEach((row) => {
    const cells = splitTableRow(row)
    html += '<tr>'
    for (let i = 0; i < headers.length; i++) {
      const c = cells[i] != null ? cells[i] : ''
      html +=
        '<td style="' +
        cellStyle(S.td, aligns[i] || 'left') +
        '">' +
        inline(c) +
        '</td>'
    }
    html += '</tr>'
  })
  html += '</tbody></table></div>'
  return html
}

function headingStyle(level) {
  return S['h' + level] || S.h6
}

/**
 * 给已有 HTML（富文本笔记）补默认 style，便于 rich-text 阅读
 * @param {string} html
 * @returns {string}
 */
function enhanceHtmlForRichText(html) {
  if (!html) return ''
  let s = String(html)
  // 已有 style= 的标签不动；给常见裸标签补 style
  const pairs = [
    [/<(h1)(\s[^>]*)?>/gi, '<$1 style="' + S.h1 + '">'],
    [/<(h2)(\s[^>]*)?>/gi, '<$1 style="' + S.h2 + '">'],
    [/<(h3)(\s[^>]*)?>/gi, '<$1 style="' + S.h3 + '">'],
    [/<(h4)(\s[^>]*)?>/gi, '<$1 style="' + S.h4 + '">'],
    [/<(p)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.p + '">'],
    [/<(ul)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.ul + '">'],
    [/<(ol)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.ol + '">'],
    [/<(li)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.li + '">'],
    [/<(blockquote)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.blockquote + '">'],
    [/<(pre)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.pre + '">'],
    [/<(table)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.table + '">'],
    [/<(th)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.th + '">'],
    [/<(td)(\s(?![^>]*style=)[^>]*)?>/gi, '<$1 style="' + S.td + '">'],
    [/<(hr)(\s(?![^>]*style=)[^>]*)?\/?>/gi, '<hr style="' + S.hr + '"/>']
  ]
  // 简化：只处理完全无属性的开标签，避免破坏已有属性
  const simple = [
    [/<h1>/gi, '<h1 style="' + S.h1 + '">'],
    [/<h2>/gi, '<h2 style="' + S.h2 + '">'],
    [/<h3>/gi, '<h3 style="' + S.h3 + '">'],
    [/<h4>/gi, '<h4 style="' + S.h4 + '">'],
    [/<h5>/gi, '<h5 style="' + S.h5 + '">'],
    [/<h6>/gi, '<h6 style="' + S.h6 + '">'],
    [/<p>/gi, '<p style="' + S.p + '">'],
    [/<ul>/gi, '<ul style="' + S.ul + '">'],
    [/<ol>/gi, '<ol style="' + S.ol + '">'],
    [/<li>/gi, '<li style="' + S.li + '">'],
    [/<blockquote>/gi, '<blockquote style="' + S.blockquote + '">'],
    [/<pre>/gi, '<pre style="' + S.pre + '">'],
    [/<table>/gi, '<table style="' + S.table + '">'],
    [/<th>/gi, '<th style="' + S.th + '">'],
    [/<td>/gi, '<td style="' + S.td + '">'],
    [/<hr\s*\/?>/gi, '<hr style="' + S.hr + '"/>'],
    [/<img(\s[^>]*?)?\/?>/gi, (m) => {
      if (/style\s*=/i.test(m)) return m
      if (m.endsWith('/>')) return m.replace(/\s*\/?>$/, ' style="' + S.img + '"/>')
      return m.replace(/>$/, ' style="' + S.img + '">')
    }]
  ]
  simple.forEach(([re, rep]) => {
    s = s.replace(re, rep)
  })
  // 避免 unused
  void pairs
  return s
}

/**
 * @param {string} src
 * @returns {string} HTML
 */
function renderMarkdown(src) {
  if (!src || !String(src).trim()) {
    return '<p style="' + S.p + '">（无正文）</p>'
  }

  const lines = String(src).replace(/\r\n/g, '\n').split('\n')
  const out = []
  let i = 0
  let inCode = false
  let codeBuf = []
  let listType = null // 'ul' | 'ol'
  let listItems = []

  function flushList() {
    if (!listType || !listItems.length) {
      listType = null
      listItems = []
      return
    }
    const tag = listType
    const listStyle = tag === 'ol' ? S.ol : S.ul
    out.push('<' + tag + ' style="' + listStyle + '">')
    listItems.forEach((li) => {
      out.push('<li style="' + S.li + '">' + li + '</li>')
    })
    out.push('</' + tag + '>')
    listType = null
    listItems = []
  }

  function isBlockStart(line) {
    if (!line.trim()) return true
    if (/^```/.test(line)) return true
    if (/^(#{1,6})\s+/.test(line)) return true
    if (/^>\s?/.test(line)) return true
    if (/^[-*+]\s+/.test(line)) return true
    if (/^\d+\.\s+/.test(line)) return true
    if (/^(\*\s*\*\s*\*|-{3,}|_{3,})\s*$/.test(line)) return true
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) return true
    return false
  }

  while (i < lines.length) {
    const line = lines[i]

    // fenced code
    if (/^```/.test(line)) {
      if (inCode) {
        out.push(
          '<pre style="' +
            S.pre +
            '"><code style="' +
            S.codeBlock +
            '">' +
            escapeHtml(codeBuf.join('\n')) +
            '</code></pre>'
        )
        codeBuf = []
        inCode = false
      } else {
        flushList()
        inCode = true
      }
      i++
      continue
    }
    if (inCode) {
      codeBuf.push(line)
      i++
      continue
    }

    // table: header + sep + body
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      flushList()
      const headerLine = line
      const sepLine = lines[i + 1]
      i += 2
      const body = []
      while (i < lines.length && isTableRow(lines[i])) {
        body.push(lines[i])
        i++
      }
      out.push(renderTable(headerLine, sepLine, body))
      continue
    }

    // hr
    if (/^(\*\s*\*\s*\*|-{3,}|_{3,})\s*$/.test(line)) {
      flushList()
      out.push('<hr style="' + S.hr + '"/>')
      i++
      continue
    }

    // heading
    const hm = /^(#{1,6})\s+(.+)$/.exec(line)
    if (hm) {
      flushList()
      const level = hm[1].length
      out.push(
        '<h' +
          level +
          ' style="' +
          headingStyle(level) +
          '">' +
          inline(hm[2]) +
          '</h' +
          level +
          '>'
      )
      i++
      continue
    }

    // blockquote
    if (/^>\s?/.test(line)) {
      flushList()
      const q = []
      while (i < lines.length && /^>\s?/.test(lines[i])) {
        q.push(lines[i].replace(/^>\s?/, ''))
        i++
      }
      out.push(
        '<blockquote style="' +
          S.blockquote +
          '"><p style="' +
          S.p +
          'margin:0;">' +
          inline(q.join(' ')) +
          '</p></blockquote>'
      )
      continue
    }

    // task / unordered list
    const taskM = /^[-*+]\s+\[([ xX])\]\s+(.+)$/.exec(line)
    if (taskM) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      const checked = taskM[1].toLowerCase() === 'x'
      const mark = checked ? '☑ ' : '☐ '
      listItems.push(mark + inline(taskM[2]))
      i++
      continue
    }

    const ulm = /^[-*+]\s+(.+)$/.exec(line)
    if (ulm) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listItems.push(inline(ulm[1]))
      i++
      continue
    }

    // ordered list
    const olm = /^\d+\.\s+(.+)$/.exec(line)
    if (olm) {
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listItems.push(inline(olm[1]))
      i++
      continue
    }

    // blank
    if (!line.trim()) {
      flushList()
      i++
      continue
    }

    // paragraph
    flushList()
    const para = []
    while (i < lines.length && lines[i].trim()) {
      // stop if next line starts a block (peek without consuming blank)
      if (para.length && isBlockStart(lines[i])) break
      // table start
      if (
        isTableRow(lines[i]) &&
        i + 1 < lines.length &&
        isTableSep(lines[i + 1])
      ) {
        break
      }
      if (/^(#{1,6})\s+/.test(lines[i])) break
      if (/^```/.test(lines[i])) break
      if (/^>\s?/.test(lines[i])) break
      if (/^[-*+]\s+/.test(lines[i])) break
      if (/^\d+\.\s+/.test(lines[i])) break
      if (/^(\*\s*\*\s*\*|-{3,}|_{3,})\s*$/.test(lines[i])) break
      para.push(lines[i])
      i++
    }
    if (para.length) {
      out.push('<p style="' + S.p + '">' + inline(para.join(' ')) + '</p>')
    }
  }

  flushList()
  if (inCode) {
    out.push(
      '<pre style="' +
        S.pre +
        '"><code style="' +
        S.codeBlock +
        '">' +
        escapeHtml(codeBuf.join('\n')) +
        '</code></pre>'
    )
  }

  return out.join('')
}

module.exports = {
  renderMarkdown,
  enhanceHtmlForRichText
}
