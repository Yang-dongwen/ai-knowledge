const DEFAULT_TITLE = '未命名笔记'

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function stripTags(html) {
  return String(html || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function extractTitleFromHtml(html) {
  if (!html) return DEFAULT_TITLE
  const h1 = html.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i)
  if (h1) {
    const t = stripTags(h1[1])
    return t ? t.slice(0, 200) : DEFAULT_TITLE
  }
  const plain = stripTags(html)
  return plain ? plain.slice(0, 200) : DEFAULT_TITLE
}

export function extractTitleFromMarkdown(md) {
  if (!md) return DEFAULT_TITLE
  for (const line of String(md).split(/\r?\n/)) {
    const t = line.trim()
    if (!t) continue
    const m = t.match(/^#\s+(.+)$/)
    if (m) return m[1].trim().slice(0, 200) || DEFAULT_TITLE
    return t.replace(/^#+\s*/, '').slice(0, 200) || DEFAULT_TITLE
  }
  return DEFAULT_TITLE
}

export function extractTitle(content, format) {
  return format === 'markdown' ? extractTitleFromMarkdown(content) : extractTitleFromHtml(content)
}

export function emptyHtmlDoc(title = DEFAULT_TITLE) {
  return `<h1 data-kb-title="1">${escapeHtml(title)}</h1><p><br></p>`
}

export function emptyMarkdownDoc(title = DEFAULT_TITLE) {
  return `# ${title}\n\n`
}

export function ensureHtmlHasTitle(html, title) {
  const body = (html || '').trim()
  if (/^<h1\b/i.test(body)) return body || emptyHtmlDoc()
  const t = (title && title.trim()) || DEFAULT_TITLE
  const head = `<h1 data-kb-title="1">${escapeHtml(t.slice(0, 200))}</h1>`
  if (!body || body === '<p><br></p>' || body === '<p></p>') return `${head}<p><br></p>`
  return `${head}${body.startsWith('<') ? body : `<p>${escapeHtml(body)}</p>`}`
}

export function ensureMarkdownHasTitle(md, title) {
  const body = String(md || '').replace(/^\uFEFF/, '')
  const first = body.split(/\r?\n/).find((l) => l.trim())
  if (first && /^#\s+/.test(first.trim())) return body
  const t = (title && title.trim()) || DEFAULT_TITLE
  if (!body.trim()) return `# ${t.slice(0, 200)}\n\n`
  return `# ${t.slice(0, 200)}\n\n${body.replace(/^\n+/, '')}`
}

export function splitMarkdownDoc(md) {
  const ensured = ensureMarkdownHasTitle(md || '')
  const lines = ensured.replace(/^\uFEFF/, '').split(/\r?\n/)
  let title = DEFAULT_TITLE
  let i = 0
  while (i < lines.length && !lines[i].trim()) i++
  if (i < lines.length) {
    const m = lines[i].trim().match(/^#\s+(.*)$/)
    if (m) {
      title = m[1].trim() || DEFAULT_TITLE
      i++
      if (i < lines.length && !lines[i].trim()) i++
    } else {
      title = lines[i].trim().slice(0, 200) || DEFAULT_TITLE
      i++
    }
  }
  return { title: title.slice(0, 200), body: lines.slice(i).join('\n') }
}

export function joinMarkdownDoc(title, body) {
  const t = (title && title.trim()) || DEFAULT_TITLE
  const b = (body || '').replace(/^\n+/, '')
  return b ? `# ${t.slice(0, 200)}\n\n${b}` : `# ${t.slice(0, 200)}\n\n`
}

export { DEFAULT_TITLE }
