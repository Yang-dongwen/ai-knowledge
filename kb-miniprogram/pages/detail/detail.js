const { api, mediaUrl } = require('../../utils/request')
const { isLoggedIn } = require('../../utils/auth')
const { renderMarkdown } = require('../../utils/markdown')
const { getShareWebOrigin } = require('../../utils/config')

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

function injectToken(html) {
  if (!html || !html.includes('/api/v1/kb/files/')) return html || ''
  const { getToken } = require('../../utils/auth')
  const { getBaseUrl } = require('../../utils/config')
  const token = getToken()
  if (!token) return html
  const base = getBaseUrl()
  return html.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)[^"']*(["'])/gi,
    (_m, pre, path, post) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      let clean = m ? m[1] : path.split('?')[0]
      if (!clean.startsWith('/')) clean = '/' + clean
      return `${pre}${base}${clean}?access_token=${encodeURIComponent(token)}${post}`
    }
  )
}

Page({
  data: {
    id: '',
    note: null,
    contentHtml: '',
    files: [],
    loading: true,
    shareOpen: false,
    shareEnabled: false,
    shareUrl: '',
    shareLoading: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
  },

  onShow() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (this.data.id) this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const note = await api.getNote(this.data.id)
      let contentHtml = ''
      if (note.contentFormat === 'html') {
        contentHtml = injectToken(note.content || '')
      } else {
        contentHtml = injectToken(renderMarkdown(note.content || ''))
      }
      let files = []
      try {
        files = (await api.listFiles(this.data.id)) || []
      } catch (e) {
        files = []
      }
      this.setData({
        note: {
          ...note,
          updatedAtText: formatTime(note.updatedAt),
          formatLabel: note.contentFormat === 'markdown' ? 'Markdown' : '富文本'
        },
        contentHtml,
        files
      })
      if (!note.deleted) {
        await this.loadShare()
      }
    } catch (e) {
      this.setData({ note: null })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadShare() {
    try {
      const st = await api.getShareStatus(this.data.id)
      const enabled = !!(st && st.enabled)
      let shareUrl = ''
      if (enabled && st.sharePath) {
        const origin = (getShareWebOrigin() || '').replace(/\/$/, '')
        shareUrl = origin ? origin + st.sharePath : st.sharePath
      }
      this.setData({
        shareEnabled: enabled,
        shareUrl
      })
    } catch (e) {
      /* ignore */
    }
  },

  toggleSharePanel() {
    this.setData({ shareOpen: !this.data.shareOpen })
  },

  async onShareSwitch(e) {
    const on = !!(e.detail && e.detail.value)
    this.setData({ shareLoading: true })
    try {
      const st = on
        ? await api.enableShare(this.data.id)
        : await api.disableShare(this.data.id)
      const enabled = !!(st && st.enabled)
      let shareUrl = ''
      if (enabled && st.sharePath) {
        const origin = (getShareWebOrigin() || '').replace(/\/$/, '')
        shareUrl = origin ? origin + st.sharePath : st.sharePath
      }
      this.setData({ shareEnabled: enabled, shareUrl })
      if (enabled && shareUrl) {
        wx.setClipboardData({
          data: shareUrl,
          success: () => wx.showToast({ title: '链接已复制', icon: 'success' })
        })
      } else if (enabled && !shareUrl) {
        wx.showToast({ title: '已开启，请配置分享域名', icon: 'none' })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
      await this.loadShare()
    } finally {
      this.setData({ shareLoading: false })
    }
  },

  copyShare() {
    if (!this.data.shareUrl) {
      wx.showToast({ title: '请先在「我的」配置分享域名', icon: 'none' })
      return
    }
    wx.setClipboardData({
      data: this.data.shareUrl,
      success: () => wx.showToast({ title: '已复制', icon: 'success' })
    })
  },

  onRotateShare() {
    wx.showModal({
      title: '重置链接',
      content: '旧链接将立即失效',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ shareLoading: true })
        try {
          const st = await api.rotateShare(this.data.id)
          const origin = (getShareWebOrigin() || '').replace(/\/$/, '')
          const shareUrl =
            st && st.sharePath ? (origin ? origin + st.sharePath : st.sharePath) : ''
          this.setData({
            shareEnabled: !!(st && st.enabled),
            shareUrl
          })
          if (shareUrl) {
            wx.setClipboardData({ data: shareUrl })
          }
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        } finally {
          this.setData({ shareLoading: false })
        }
      }
    })
  },

  onEdit() {
    wx.setStorageSync('kb_edit_draft', {
      id: this.data.note.id,
      title: this.data.note.title,
      content: this.data.note.content,
      contentFormat: this.data.note.contentFormat,
      categoryId: this.data.note.categoryId,
      pinned: this.data.note.pinned,
      tags: this.data.note.tags || []
    })
    wx.switchTab({ url: '/pages/edit/edit' })
  },

  onDelete() {
    wx.showModal({
      title: '移入回收站',
      content: '可在回收站恢复',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.deleteNote(this.data.id)
          wx.showToast({ title: '已删除', icon: 'success' })
          setTimeout(
            () => wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/notes/notes' }) }),
            300
          )
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        }
      }
    })
  },

  onRestore() {
    api
      .restoreNote(this.data.id)
      .then(() => {
        wx.showToast({ title: '已恢复', icon: 'success' })
        this.load()
      })
      .catch((e) => wx.showToast({ title: e.message || '失败', icon: 'none' }))
  },

  onPermanent() {
    wx.showModal({
      title: '永久删除',
      content: '含附件与存储对象，不可恢复',
      confirmColor: '#ef4444',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.permanentDeleteNote(this.data.id)
          wx.showToast({ title: '已永久删除', icon: 'success' })
          setTimeout(
            () => wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/notes/notes' }) }),
            300
          )
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        }
      }
    })
  },

  openFile(e) {
    const f = e.currentTarget.dataset.file
    if (!f) return
    const url = mediaUrl(f.contentPath)
    if (f.kind === 'image') {
      wx.previewImage({ urls: [url], current: url })
      return
    }
    wx.showLoading({ title: '打开中' })
    wx.downloadFile({
      url,
      success(res) {
        wx.hideLoading()
        if (res.statusCode === 200) {
          wx.openDocument({
            filePath: res.tempFilePath,
            showMenu: true,
            fail() {
              wx.showToast({ title: '无法预览，请用 PC 打开', icon: 'none' })
            }
          })
        }
      },
      fail() {
        wx.hideLoading()
        wx.showToast({ title: '下载失败', icon: 'none' })
      }
    })
  }
})
