/**
 * Markdown 正文中的图片尺寸：用 HTML <img style="width:..%"> 表达，
 * 预览可缩放；存库仍落在 Markdown 文档里。
 */

const IMG_TAG_RE =
  /<img\b([^>]*?)\/?\s*>/gi

function normalizeSrc(src: string): string {
  let s = (src || '').trim()
  try {
    if (/^https?:\/\//i.test(s)) {
      s = new URL(s).pathname + (new URL(s).search || '')
    }
  } catch {
    /* ignore */
  }
  return s
    .replace(/[?&](access_token|token)=[^&]*/gi, '')
    .replace(/[?&]$/, '')
    .replace(/\?&/, '?')
}

function sameImage(a: string, b: string): boolean {
  const na = normalizeSrc(a)
  const nb = normalizeSrc(b)
  if (na === nb) return true
  if (na.includes(nb) || nb.includes(na)) return true
  const idA = na.match(/\/files\/(\d+)\//)?.[1]
  const idB = nb.match(/\/files\/(\d+)\//)?.[1]
  return !!(idA && idB && idA === idB)
}

function getAttr(attrs: string, name: string): string | null {
  const re = new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i')
  const m = attrs.match(re)
  if (!m) return null
  return m[2] ?? m[3] ?? m[4] ?? null
}

function setAttr(attrs: string, name: string, value: string): string {
  const re = new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i')
  if (re.test(attrs)) {
    return attrs.replace(re, `${name}="${value}"`)
  }
  return `${attrs.trim()} ${name}="${value}"`
}

function setWidthInStyle(style: string | null, widthPct: number | 'auto'): string {
  const parts = (style || '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean)
    .filter((s) => !/^width\s*:/i.test(s) && !/^max-width\s*:/i.test(s) && !/^height\s*:/i.test(s))
  if (widthPct === 'auto') {
    parts.push('max-width:100%', 'height:auto')
  } else {
    parts.push(`max-width:100%`, `width:${widthPct}%`, 'height:auto')
  }
  return parts.join('; ')
}

/** 将 ![alt](url) 转为可缩放的 HTML img（仅匹配给定 src） */
export function mdImageToHtml(md: string, srcHint: string, widthPct: number | 'auto' = 100): string {
  const hint = normalizeSrc(srcHint)
  // ![alt](url) or ![alt](url "title")
  return md.replace(
    /!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g,
    (full, alt: string, url: string) => {
      if (!sameImage(url, hint)) return full
      const style = setWidthInStyle(null, widthPct)
      const a = (alt || '').replace(/"/g, '&quot;')
      const clean = normalizeSrc(url).split('?')[0]
      return `<img src="${clean}" alt="${a}" data-kb-md-img="1" style="${style}" />`
    }
  )
}

/** 设置内容中某张图的宽度（支持 HTML img 与 markdown 图片语法） */
export function setMarkdownImageWidth(
  content: string,
  srcHint: string,
  widthPct: number | 'auto'
): string {
  if (!content || !srcHint) return content
  const hint = normalizeSrc(srcHint)
  let next = content

  // 1) 已有 HTML img
  let touched = false
  next = next.replace(IMG_TAG_RE, (full, attrs: string) => {
    const src = getAttr(attrs, 'src')
    if (!src || !sameImage(src, hint)) return full
    touched = true
    let a = attrs
    const style = setWidthInStyle(getAttr(attrs, 'style'), widthPct)
    a = setAttr(a, 'style', style)
    a = setAttr(a, 'data-kb-md-img', '1')
    const cleanSrc = normalizeSrc(src).split('?')[0]
    a = setAttr(a, 'src', cleanSrc)
    return `<img${a.startsWith(' ') ? a : ` ${a}`} />`
  })

  if (!touched) {
    // 2) 纯 markdown 图片 → 转 HTML 并设宽
    const before = next
    next = mdImageToHtml(next, hint, widthPct)
    if (next === before) {
      // 再试：hint 带 token 时只比 path
      const pathOnly = hint.split('?')[0]
      next = mdImageToHtml(next, pathOnly, widthPct)
    }
  }

  return next
}

/** 插入 HTML 图片到 Markdown 正文末尾（或指定位置） */
export function appendMarkdownImage(
  body: string,
  src: string,
  alt = 'image',
  widthPct: number | 'auto' = 100
): string {
  const clean = normalizeSrc(src).split('?')[0]
  const style = setWidthInStyle(null, widthPct)
  const a = (alt || 'image').replace(/"/g, '&quot;')
  const tag = `<img src="${clean}" alt="${a}" data-kb-md-img="1" style="${style}" />`
  const b = (body || '').replace(/\s+$/, '')
  return b ? `${b}\n\n${tag}\n` : `${tag}\n`
}

export function imageSrcKey(src: string): string {
  return normalizeSrc(src).split('?')[0]
}
