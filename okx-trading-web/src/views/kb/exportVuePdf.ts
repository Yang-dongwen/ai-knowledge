/**
 * Knowledge-base PDF export in Typora Vue style.
 * Renders HTML in the browser, then the backend prints it with Edge/Chrome (Skia).
 */
import { injectKbMediaTokens, kbApi } from '@/api/kb.api'
import { sanitizeHtml } from '@/utils/sanitizeHtml'

export type KbExportFormat = 'html' | 'markdown'

const TOKEN_KEY = 'okx_auth_token'

let mdRender: ((src: string) => string) | null = null

async function ensureMd(): Promise<(src: string) => string> {
  if (mdRender) return mdRender
  const mod = await import('markdown-it')
  const multimd = await import('markdown-it-multimd-table')
  const MarkdownIt = mod.default
  const tablePlugin = (multimd as { default?: unknown }).default || multimd
  const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(tablePlugin as never, {
    multiline: true,
    rowspan: true,
    headerless: true
  })
  mdRender = (src: string) => sanitizeHtml(md.render(src || ''))
  return mdRender
}

export function safePdfFilename(title?: string | null): string {
  const t = (title || '').replace(/[\\/:*?"<>|]/g, '_').trim()
  return t || '未命名笔记'
}

async function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(blob)
  })
}

async function inlineImages(html: string): Promise<string> {
  if (typeof DOMParser === 'undefined' || !html) return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const imgs = Array.from(doc.querySelectorAll('img[src]'))
  const token = localStorage.getItem(TOKEN_KEY) || ''
  await Promise.all(
    imgs.map(async (img) => {
      const src = (img.getAttribute('src') || '').trim()
      if (!src || src.startsWith('data:') || src.startsWith('blob:')) return
      try {
        const headers: Record<string, string> = {}
        if (token && !/[?&]access_token=/.test(src)) {
          headers.Authorization = `Bearer ${token}`
        }
        const res = await fetch(src, { headers, credentials: 'same-origin' })
        if (!res.ok) return
        const blob = await res.blob()
        if (!blob.type.startsWith('image/')) return
        img.setAttribute('src', await blobToDataUrl(blob))
      } catch {
        /* keep original src */
      }
    })
  )
  return doc.body.innerHTML
}

export async function renderExportHtml(content: string, format: KbExportFormat): Promise<string> {
  let html: string
  if (format === 'markdown') {
    const render = await ensureMd()
    html = render(content || '')
  } else {
    html = sanitizeHtml(content || '')
  }
  html = injectKbMediaTokens(html)
  return inlineImages(html)
}

export async function exportNoteAsVuePdf(opts: {
  noteId: string
  title: string
  content: string
  format: KbExportFormat
}): Promise<void> {
  const bodyHtml = await renderExportHtml(opts.content, opts.format)
  if (!bodyHtml.replace(/<[^>]+>/g, '').trim()) {
    throw new Error('文档为空，无法导出')
  }
  await kbApi.exportNotePdf(
    opts.noteId,
    { title: opts.title, html: bodyHtml },
    safePdfFilename(opts.title)
  )
}
