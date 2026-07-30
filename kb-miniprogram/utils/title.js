const DEFAULT_TITLE = '未命名笔记'

function stripTags(html) {
  return String(html || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function extractTitleFromHtml(html) {
  if (!html) return DEFAULT_TITLE
  const h1 = html.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i)
  if (h1) {
    const t = stripTags(h1[1])
    return t ? t.slice(0, 200) : DEFAULT_TITLE
  }
  const plain = stripTags(html)
  return plain ? plain.slice(0, 200) : DEFAULT_TITLE
}

function extractTitleFromMarkdown(md) {
  if (!md) return DEFAULT_TITLE
  const lines = String(md).split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim()
    if (!t) continue
    const m = t.match(/^#\s+(.+)$/)
    if (m) return m[1].trim().slice(0, 200) || DEFAULT_TITLE
    return t.replace(/^#+\s*/, '').slice(0, 200) || DEFAULT_TITLE
  }
  return DEFAULT_TITLE
}

function extractTitle(content, format) {
  return format === 'markdown' ? extractTitleFromMarkdown(content) : extractTitleFromHtml(content)
}

function emptyMarkdownDoc(title) {
  return `# ${title || DEFAULT_TITLE}\n\n`
}

function emptyHtmlDoc(title) {
  const t = (title || DEFAULT_TITLE).replace(/</g, '').replace(/>/g, '')
  return `<h1>${t}</h1><p><br/></p>`
}

function ensureMarkdownHasTitle(md, title) {
  const body = String(md || '')
  const first = body.split(/\r?\n/).find((l) => l.trim())
  if (first && /^#\s+/.test(first.trim())) return body
  const t = (title && title.trim()) || DEFAULT_TITLE
  if (!body.trim()) return `# ${t.slice(0, 200)}\n\n`
  return `# ${t.slice(0, 200)}\n\n${body.replace(/^\n+/, '')}`
}

function ensureHtmlHasTitle(html, title) {
  const body = String(html || '').trim()
  if (/^<h1\b/i.test(body)) return body || emptyHtmlDoc(title)
  const t = (title && title.trim()) || DEFAULT_TITLE
  const head = `<h1>${t.replace(/</g, '').slice(0, 200)}</h1>`
  if (!body || body === '<p><br></p>' || body === '<p><br/></p>') return `${head}<p><br/></p>`
  return head + body
}

function splitMarkdownDoc(md) {
  const ensured = ensureMarkdownHasTitle(md || '')
  const lines = ensured.split(/\r?\n/)
  let title = DEFAULT_TITLE
  let i = 0
  while (i < lines.length && !lines[i].trim()) i++
  if (i < lines.length) {
    const m = lines[i].trim().match(/^#\s+(.*)$/)
    if (m) {
      title = m[1].trim() || DEFAULT_TITLE
      i++
      if (i < lines.length && !lines[i].trim()) i++
    }
  }
  return { title: title.slice(0, 200), body: lines.slice(i).join('\n') }
}

function joinMarkdownDoc(title, body) {
  const t = (title && title.trim()) || DEFAULT_TITLE
  const b = (body || '').replace(/^\n+/, '')
  return b ? `# ${t.slice(0, 200)}\n\n${b}` : `# ${t.slice(0, 200)}\n\n`
}

module.exports = {
  DEFAULT_TITLE,
  extractTitle,
  emptyMarkdownDoc,
  emptyHtmlDoc,
  ensureMarkdownHasTitle,
  ensureHtmlHasTitle,
  splitMarkdownDoc,
  joinMarkdownDoc
}
