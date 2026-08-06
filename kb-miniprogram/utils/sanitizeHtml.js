/**
 * 小程序端 HTML 消毒（正则 allowlist 近似，无 DOMParser）。
 * rich-text 本身限制脚本，仍剥离危险标签与事件。
 */
function sanitizeHtml(dirty) {
  if (!dirty) return ''
  let s = String(dirty)
  s = s.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
  s = s.replace(/<iframe\b[^<]*(?:(?!<\/iframe>)<[^<]*)*<\/iframe>/gi, '')
  s = s.replace(/<object\b[^<]*(?:(?!<\/object>)<[^<]*)*<\/object>/gi, '')
  s = s.replace(/<embed\b[^>]*>/gi, '')
  s = s.replace(/<form\b[^<]*(?:(?!<\/form>)<[^<]*)*<\/form>/gi, '')
  s = s.replace(/<\/?(?:link|meta|base|svg|math|style)[^>]*>/gi, '')
  s = s.replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  s = s.replace(/\s(href|src|xlink:href)\s*=\s*(['"])\s*javascript:[^'"]*\2/gi, '')
  s = s.replace(/\s(href|src)\s*=\s*(['"])\s*data:(?!image\/)[^'"]*\2/gi, '')
  s = s.replace(/\ssrcdoc\s*=\s*("[^"]*"|'[^']*')/gi, '')
  return s
}

/** 仅允许站内相对路径 */
function safeReturnUrl(raw, fallback) {
  const fb = fallback || '/pages/notes/notes'
  if (typeof raw !== 'string' || !raw) return fb
  const s = raw.trim()
  if (!s.startsWith('/') || s.startsWith('//') || s.includes('://')) return fb
  if (s.includes('\\') || s.includes('\n') || s.includes('\r')) return fb
  // 小程序路径
  if (!s.startsWith('/pages/')) return fb
  return s
}

module.exports = {
  sanitizeHtml,
  safeReturnUrl
}
