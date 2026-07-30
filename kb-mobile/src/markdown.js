import MarkdownIt from 'markdown-it'
import { injectMediaInHtml } from './api'

const md = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true
})

/** 渲染笔记正文：markdown 或 html */
export function renderNoteContent(content, format = 'markdown') {
  if (!content) return '<p class="md-empty">（无正文）</p>'
  if (format === 'html') {
    return injectMediaInHtml(content)
  }
  let html = md.render(String(content))
  return injectMediaInHtml(html)
}
