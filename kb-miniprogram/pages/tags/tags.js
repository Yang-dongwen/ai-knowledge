const { api } = require('../../utils/request')
const { requireLoginOrRedirect } = require('../../utils/auth')

Page({
  data: {
    tags: [],
    loading: true,
    showSheet: false,
    sheetMode: '', // create | rename
    inputName: '',
    targetId: '',
    targetName: '',
    busy: false
  },

  onShow() {
    if (!requireLoginOrRedirect()) return
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const tags = (await api.listTags()) || []
      this.setData({
        tags: tags.map((t) => ({
          id: String(t.id),
          name: t.name,
          noteCount: t.noteCount != null ? t.noteCount : 0
        })),
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false, tags: [] })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  openCreate() {
    this.setData({
      showSheet: true,
      sheetMode: 'create',
      inputName: '',
      targetId: '',
      targetName: ''
    })
  },

  openRename(e) {
    const { id, name } = e.currentTarget.dataset
    this.setData({
      showSheet: true,
      sheetMode: 'rename',
      inputName: name || '',
      targetId: id,
      targetName: name || ''
    })
  },

  closeSheet() {
    if (this.data.busy) return
    this.setData({ showSheet: false, sheetMode: '', inputName: '' })
  },

  onInputName(e) {
    this.setData({ inputName: e.detail.value })
  },

  onTagTap(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showActionSheet({
      itemList: ['筛选笔记', '重命名', '删除'],
      success: (res) => {
        if (res.tapIndex === 0) {
          // 跳转列表并带 tagId 需要 storage 中转
          wx.setStorageSync('kb_list_filter', { tagId: String(id) })
          wx.switchTab({ url: '/pages/notes/notes' })
        } else if (res.tapIndex === 1) {
          this.openRename({ currentTarget: { dataset: { id, name } } })
        } else if (res.tapIndex === 2) {
          this.confirmDelete(id, name)
        }
      }
    })
  },

  confirmDelete(id, name) {
    wx.showModal({
      title: '删除标签',
      content: `删除「${name}」？笔记本身不会删除，仅解除关联。`,
      confirmColor: '#ef4444',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.deleteTag(id)
          wx.showToast({ title: '已删除', icon: 'success' })
          this.load()
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        }
      }
    })
  },

  async submitSheet() {
    if (this.data.busy) return
    const name = (this.data.inputName || '').trim()
    if (!name) {
      wx.showToast({ title: '请输入标签名', icon: 'none' })
      return
    }
    this.setData({ busy: true })
    try {
      if (this.data.sheetMode === 'create') {
        await api.createTag(name)
        wx.showToast({ title: '已创建', icon: 'success' })
      } else {
        await api.updateTag(this.data.targetId, name)
        wx.showToast({ title: '已重命名', icon: 'success' })
      }
      this.setData({ showSheet: false, sheetMode: '', inputName: '' })
      await this.load()
    } catch (e) {
      wx.showToast({ title: e.message || '失败', icon: 'none' })
    } finally {
      this.setData({ busy: false })
    }
  }
})
