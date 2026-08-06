const { api, absolutizeMediaInHtml } = require('../../utils/request')
const { renderMarkdown, enhanceHtmlForRichText } = require('../../utils/markdown')
const { sanitizeHtml } = require('../../utils/sanitizeHtml')

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
}

Page({
  data: {
    token: '',
    loading: true,
    error: '',
    note: null,
    contentHtml: ''
  },

  onLoad(query) {
    const token = (query && (query.token || query.t)) || ''
    this.setData({ token })
    if (token) this.load(token)
    else {
      this.setData({ loading: false, error: '无效链接' })
    }
  },

  async load(token) {
    this.setData({ loading: true, error: '' })
    try {
      const note = await api.fetchPublicNote(token)
      let contentHtml = ''
      if (note.contentFormat === 'html') {
        contentHtml = sanitizeHtml(enhanceHtmlForRichText(absolutizeMediaInHtml(note.content || '')))
      } else {
        contentHtml = sanitizeHtml(absolutizeMediaInHtml(renderMarkdown(note.content || '')))
      }
      this.setData({
        note: {
          ...note,
          dateText: formatTime(note.updatedAt || note.publishedAt),
          tags: note.tags || []
        },
        contentHtml,
        loading: false
      })
      wx.setNavigationBarTitle({ title: note.title || '分享阅读' })
    } catch (e) {
      this.setData({
        note: null,
        contentHtml: '',
        loading: false,
        error: e.message || '分享不存在或已关闭'
      })
    }
  },

  onShareAppMessage() {
    const title = (this.data.note && this.data.note.title) || '知识库分享'
    const token = this.data.token
    return {
      title,
      path: token ? `/pages/share/share?token=${encodeURIComponent(token)}` : '/pages/notes/notes'
    }
  }
})
