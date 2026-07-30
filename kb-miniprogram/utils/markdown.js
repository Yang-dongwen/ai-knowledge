/**
 * 轻量 Markdown → HTML（供 rich-text 使用）
 * 支持：标题、粗体/斜体、行内代码、代码块、列表、引用、链接、分隔线、段落
 * 不执行 HTML 注入（先整体转义）
 */

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function inline(text) {
  // 先抽出 markdown 图片，避免被 escape
  const imgs = []
  let raw = String(text).replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, alt, url) => {
    const i = imgs.length
    imgs.push({ alt: alt || '', url: url || '' })
    return `@@IMG${i}@@`
  })
  // 抽出已有 <img ...>
  raw = raw.replace(/<img\b[^>]*\/?>/gi, (m) => {
    const srcM = m.match(/\bsrc=["']([^"']+)["']/i)
    const altM = m.match(/\balt=["']([^"']*)["']/i)
    if (!srcM) return escapeHtml(m)
    const i = imgs.length
    imgs.push({ alt: (altM && altM[1]) || '', url: srcM[1] })
    return `@@IMG${i}@@`
  })

  let s = escapeHtml(raw)
  // code
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')
  // bold ** ** or __ __（先粗体再斜体，避免冲突）
  s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/__(.+?)__/g, '<strong>$1</strong>')
  // italic * * or _ _
  s = s.replace(/\*(.+?)\*/g, '<em>$1</em>')
  s = s.replace(/_(.+?)_/g, '<em>$1</em>')
  // links [t](url)
  s = s.replace(
    /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
    '<a href="$2">$1</a>'
  )
  // restore images
  s = s.replace(/@@IMG(\d+)@@/g, (_m, idx) => {
    const img = imgs[Number(idx)]
    if (!img) return ''
    const alt = escapeHtml(img.alt)
    const url = String(img.url).replace(/"/g, '')
    return `<img src="${url}" alt="${alt}" style="max-width:100%;height:auto"/>`
  })
  return s
}

/**
 * @param {string} src
 * @returns {string} HTML
 */
function renderMarkdown(src) {
  if (!src || !String(src).trim()) {
    return '<p>（无正文）</p>'
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
    out.push('<' + tag + '>')
    listItems.forEach((li) => {
      out.push('<li>' + inline(li) + '</li>')
    })
    out.push('</' + tag + '>')
    listType = null
    listItems = []
  }

  while (i < lines.length) {
    const line = lines[i]

    // fenced code
    if (/^```/.test(line)) {
      if (inCode) {
        out.push('<pre><code>' + escapeHtml(codeBuf.join('\n')) + '</code></pre>')
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

    // hr
    if (/^(\*\s*\*\s*\*|-{3,}|_{3,})\s*$/.test(line)) {
      flushList()
      out.push('<hr/>')
      i++
      continue
    }

    // heading
    const hm = /^(#{1,6})\s+(.+)$/.exec(line)
    if (hm) {
      flushList()
      const level = hm[1].length
      out.push('<h' + level + '>' + inline(hm[2]) + '</h' + level + '>')
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
      out.push('<blockquote><p>' + inline(q.join(' ')) + '</p></blockquote>')
      continue
    }

    // unordered list
    const ulm = /^[-*+]\s+(.+)$/.exec(line)
    if (ulm) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listItems.push(ulm[1])
      i++
      continue
    }

    // ordered list
    const olm = /^\d+\.\s+(.+)$/.exec(line)
    if (olm) {
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listItems.push(olm[1])
      i++
      continue
    }

    // blank
    if (!line.trim()) {
      flushList()
      i++
      continue
    }

    // paragraph (merge consecutive non-empty)
    flushList()
    const para = []
    while (i < lines.length && lines[i].trim() && !/^(#{1,6}\s|```|[-*+]\s|\d+\.\s|>\s?|(\*\s*\*\s*\*|-{3,}|_{3,})\s*$)/.test(lines[i])) {
      para.push(lines[i])
      i++
    }
    out.push('<p>' + inline(para.join(' ')) + '</p>')
  }

  flushList()
  if (inCode) {
    out.push('<pre><code>' + escapeHtml(codeBuf.join('\n')) + '</code></pre>')
  }

  return out.join('')
}

module.exports = {
  renderMarkdown
}
