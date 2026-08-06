/**
 * 浏览器端 HTML 消毒（allowlist），与 PC 工具台策略对齐。
 */
const ALLOWED_TAGS = new Set([
  'a', 'abbr', 'b', 'blockquote', 'br', 'code', 'del', 'div', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'hr', 'i', 'img', 'li', 'ol', 'p', 'pre', 's', 'span', 'strong', 'sub', 'sup', 'table', 'tbody',
  'td', 'th', 'thead', 'tr', 'u', 'ul', 'figure', 'figcaption', 'video', 'source', 'audio'
])

const ALLOWED_ATTR = new Set([
  'href', 'src', 'alt', 'title', 'class', 'id', 'width', 'height', 'colspan', 'rowspan',
  'align', 'target', 'rel', 'type', 'controls', 'preload', 'poster',
  'data-kb-md-img', 'data-kb-file-id', 'data-w-e-type', 'data-href'
])

const SAFE_URL = /^(https?:|mailto:|\/|#|blob:|data:image\/(png|jpe?g|gif|webp|svg\+xml);base64,)/i

function isSafeUrl(value) {
  const v = (value || '').trim()
  if (!v) return false
  if (/^javascript:/i.test(v) || /^vbscript:/i.test(v)) return false
  if (/^data:/i.test(v) && !/^data:image\//i.test(v)) return false
  return SAFE_URL.test(v) || v.startsWith('/')
}

export function sanitizeHtml(dirty) {
  if (!dirty) return ''
  if (typeof DOMParser === 'undefined') {
    return String(dirty)
      .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
      .replace(/<iframe\b[^<]*(?:(?!<\/iframe>)<[^<]*)*<\/iframe>/gi, '')
      .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
      .replace(/\s(href|src)\s*=\s*(['"])\s*javascript:[^'"]*\2/gi, '')
  }
  const doc = new DOMParser().parseFromString(dirty, 'text/html')
  const nodes = Array.from(doc.body.querySelectorAll('*'))
  for (const el of nodes) {
    const tag = el.tagName.toLowerCase()
    if (!ALLOWED_TAGS.has(tag)) {
      const parent = el.parentNode
      if (parent) {
        while (el.firstChild) parent.insertBefore(el.firstChild, el)
        parent.removeChild(el)
      } else {
        el.remove()
      }
      continue
    }
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase()
      const value = (attr.value || '').trim()
      if (name.startsWith('on') || name === 'srcdoc' || name === 'xlink:href') {
        el.removeAttribute(attr.name)
        continue
      }
      if (name === 'style') {
        if (/expression\s*\(|javascript:|behavior\s*:|@import/i.test(value)) {
          el.removeAttribute(attr.name)
        }
        continue
      }
      if (!ALLOWED_ATTR.has(name) && !name.startsWith('data-')) {
        el.removeAttribute(attr.name)
        continue
      }
      if ((name === 'href' || name === 'src' || name === 'poster') && !isSafeUrl(value)) {
        el.removeAttribute(attr.name)
        continue
      }
      if (name === 'target' && value === '_blank') {
        el.setAttribute('rel', 'noopener noreferrer')
      }
    }
  }
  return doc.body.innerHTML
}

/** 仅允许站内相对路径，防开放重定向 */
export function safeAppRedirect(raw, fallback = '/notes') {
  if (typeof raw !== 'string' || !raw) return fallback
  const s = raw.trim()
  if (!s.startsWith('/') || s.startsWith('//') || s.includes('://')) return fallback
  if (s.includes('\\') || s.includes('\n') || s.includes('\r')) return fallback
  return s
}
