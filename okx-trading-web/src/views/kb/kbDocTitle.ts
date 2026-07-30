/**
 * 文档内标题：正文第一行 / 首个 H1 即笔记标题（与后端 title 字段同步）。
 */

const DEFAULT_TITLE = '未命名笔记'

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function unescapeHtml(s: string): string {
  return s
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

function stripTags(html: string): string {
  return unescapeHtml(html.replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim()
}

/** 从 HTML 正文提取标题（优先首个 h1） */
export function extractTitleFromHtml(html: string): string {
  if (!html) return DEFAULT_TITLE
  const h1 = html.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i)
  if (h1) {
    const t = stripTags(h1[1])
    return t ? t.slice(0, 200) : DEFAULT_TITLE
  }
  // 退化为第一段纯文本
  const p = html.match(/<(p|h[2-6]|div)\b[^>]*>([\s\S]*?)<\/\1>/i)
  if (p) {
    const t = stripTags(p[2])
    if (t) return t.slice(0, 200)
  }
  const plain = stripTags(html)
  return plain ? plain.slice(0, 200) : DEFAULT_TITLE
}

/** 从 Markdown 正文提取标题（优先首行 # 标题） */
export function extractTitleFromMarkdown(md: string): string {
  if (!md) return DEFAULT_TITLE
  const lines = md.replace(/^\uFEFF/, '').split(/\r?\n/)
  for (const line of lines) {
    const t = line.trim()
    if (!t) continue
    const m = t.match(/^#\s+(.+)$/)
    if (m) return m[1].trim().slice(0, 200) || DEFAULT_TITLE
    // 首行非标题也当作标题
    return t.replace(/^#+\s*/, '').slice(0, 200) || DEFAULT_TITLE
  }
  return DEFAULT_TITLE
}

export function extractTitle(content: string, format: 'html' | 'markdown'): string {
  return format === 'markdown' ? extractTitleFromMarkdown(content) : extractTitleFromHtml(content)
}

/** 确保 HTML 正文以 H1 标题开头（加载旧数据时） */
export function ensureHtmlHasTitle(html: string, title?: string | null): string {
  const body = (html || '').trim()
  if (/^<h1\b/i.test(body)) return body || emptyHtmlDoc()
  const t = (title && title.trim()) || DEFAULT_TITLE
  const head = `<h1 data-kb-title="1">${escapeHtml(t.slice(0, 200))}</h1>`
  if (!body || body === '<p><br></p>' || body === '<p></p>') {
    return `${head}<p><br></p>`
  }
  return `${head}${body.startsWith('<') ? body : `<p>${escapeHtml(body)}</p>`}`
}

/** 确保 Markdown 以 # 标题开头 */
export function ensureMarkdownHasTitle(md: string, title?: string | null): string {
  const body = (md || '').replace(/^\uFEFF/, '')
  const first = body.split(/\r?\n/).find((l) => l.trim())
  if (first && /^#\s+/.test(first.trim())) return body
  const t = (title && title.trim()) || DEFAULT_TITLE
  const head = `# ${t.slice(0, 200)}`
  if (!body.trim()) return `${head}\n\n`
  return `${head}\n\n${body.replace(/^\n+/, '')}`
}

export function emptyHtmlDoc(title = DEFAULT_TITLE): string {
  return `<h1 data-kb-title="1">${escapeHtml(title)}</h1><p><br></p>`
}

export function emptyMarkdownDoc(title = DEFAULT_TITLE): string {
  return `# ${title}\n\n`
}

/**
 * 是否为「空草稿」：仅默认标题、无实质正文。
 * 用于避免树操作 / 切换文档时误保存出空笔记。
 */
export function isBlankDraftContent(
  content: string | null | undefined,
  format: 'html' | 'markdown' | string
): boolean {
  const raw = (content || '').trim()
  if (!raw) return true
  if (format === 'markdown') {
    const { title, body } = splitMarkdownDoc(raw)
    const t = (title || '').trim()
    const b = (body || '').trim()
    const titleEmpty = !t || t === DEFAULT_TITLE
    return titleEmpty && !b
  }
  // html：去掉标签后仅剩默认标题或空白
  const title = extractTitleFromHtml(raw)
  const plain = stripTags(raw)
    .replace(title, '')
    .replace(DEFAULT_TITLE, '')
    .replace(/\s+/g, '')
  const titleEmpty = !title || title === DEFAULT_TITLE
  return titleEmpty && !plain
}

/** 拆分 Markdown：标题行 + 正文（编辑区用横线视觉分隔） */
export function splitMarkdownDoc(md: string): { title: string; body: string } {
  const ensured = ensureMarkdownHasTitle(md || '')
  const lines = ensured.replace(/^\uFEFF/, '').split(/\r?\n/)
  let title = DEFAULT_TITLE
  let i = 0
  // 跳过文件头空行
  while (i < lines.length && !lines[i].trim()) i++
  if (i < lines.length) {
    const m = lines[i].trim().match(/^#\s+(.*)$/)
    if (m) {
      title = m[1].trim() || DEFAULT_TITLE
      i++
      // 吃掉标题后一个空行
      if (i < lines.length && !lines[i].trim()) i++
    } else {
      title = lines[i].trim().slice(0, 200) || DEFAULT_TITLE
      i++
      if (i < lines.length && !lines[i].trim()) i++
    }
  }
  const body = lines.slice(i).join('\n')
  return { title: title.slice(0, 200), body }
}

/** 合并 Markdown 标题与正文 */
export function joinMarkdownDoc(title: string, body: string): string {
  const t = (title && title.trim()) || DEFAULT_TITLE
  const b = (body || '').replace(/^\n+/, '')
  return b ? `# ${t.slice(0, 200)}\n\n${b}` : `# ${t.slice(0, 200)}\n\n`
}

export { DEFAULT_TITLE }
