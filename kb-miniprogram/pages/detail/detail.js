const { api, mediaUrl, absolutizeMediaInHtml } = require('../../utils/request')
const { requireLoginOrRedirect } = require('../../utils/auth')
const { renderMarkdown, enhanceHtmlForRichText } = require('../../utils/markdown')
const { getShareWebOrigin } = require('../../utils/config')
const { sanitizeHtml } = require('../../utils/sanitizeHtml')

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
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
    shareToken: '',
    shareLoading: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
  },

  onShow() {
    if (!requireLoginOrRedirect()) return
    if (this.data.id) this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const note = await api.getNote(this.data.id)
      let contentHtml = ''
      if (note.contentFormat === 'html') {
        contentHtml = sanitizeHtml(enhanceHtmlForRichText(absolutizeMediaInHtml(note.content || '')))
      } else {
        contentHtml = sanitizeHtml(absolutizeMediaInHtml(renderMarkdown(note.content || '')))
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

  buildShareState(st) {
    const enabled = !!(st && st.enabled)
    // 仅开启时暴露 token，避免关闭后仍预览/转发
    const shareToken = enabled && st.shareToken ? st.shareToken : ''
    let shareUrl = ''
    if (enabled && st.sharePath) {
      const origin = (getShareWebOrigin() || '').replace(/\/$/, '')
      shareUrl = origin ? origin + st.sharePath : st.sharePath
    }
    return { shareEnabled: enabled, shareUrl, shareToken }
  },

  async loadShare() {
    try {
      const st = await api.getShareStatus(this.data.id)
      this.setData(this.buildShareState(st))
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
      const state = this.buildShareState(st)
      this.setData(state)
      if (on && state.shareToken) {
        wx.showToast({ title: '已开启分享', icon: 'success' })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
      await this.loadShare()
    } finally {
      this.setData({ shareLoading: false })
    }
  },

  copyShare() {
    if (this.data.shareUrl) {
      wx.setClipboardData({
        data: this.data.shareUrl,
        success: () => wx.showToast({ title: '已复制网页链接', icon: 'success' })
      })
      return
    }
    if (this.data.shareToken) {
      // 无 H5 域名时提示用户使用小程序分享
      wx.showToast({ title: '请用下方「转发给好友」', icon: 'none' })
      return
    }
    wx.showToast({ title: '请先开启分享', icon: 'none' })
  },

  previewShare() {
    if (!this.data.shareToken) {
      wx.showToast({ title: '请先开启分享', icon: 'none' })
      return
    }
    wx.navigateTo({
      url: `/pages/share/share?token=${encodeURIComponent(this.data.shareToken)}`
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
          this.setData(this.buildShareState(st))
          if (this.data.shareUrl) {
            wx.setClipboardData({ data: this.data.shareUrl })
          } else {
            wx.showToast({ title: '已重置', icon: 'success' })
          }
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        } finally {
          this.setData({ shareLoading: false })
        }
      }
    })
  },

  onShareAppMessage() {
    const title = (this.data.note && this.data.note.title) || '知识库笔记'
    if (this.data.shareEnabled && this.data.shareToken) {
      return {
        title,
        path: `/pages/share/share?token=${encodeURIComponent(this.data.shareToken)}`
      }
    }
    // 未开启公开分享时仅分享给自己打开详情（需登录）
    return {
      title,
      path: this.data.id
        ? `/pages/detail/detail?id=${this.data.id}`
        : '/pages/notes/notes'
    }
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
