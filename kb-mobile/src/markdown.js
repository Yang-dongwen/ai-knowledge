import MarkdownIt from 'markdown-it'
import { injectMediaInHtml } from './api'
import { sanitizeHtml } from './utils/sanitizeHtml'

const md = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true
})
// 确保 GFM 表格可用
if (typeof md.enable === 'function') {
  try {
    md.enable(['table'])
  } catch (e) {
    /* ignore */
  }
}

/** 渲染笔记正文：markdown 或 html（消毒后再注入媒体 token） */
export function renderNoteContent(content, format = 'markdown') {
  if (!content) return '<p class="md-empty">（无正文）</p>'
  if (format === 'html') {
    return sanitizeHtml(injectMediaInHtml(content))
  }
  let html = md.render(String(content))
  return sanitizeHtml(injectMediaInHtml(html))
}
