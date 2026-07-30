const { api } = require('../../utils/request')
const { isLoggedIn } = require('../../utils/auth')

function formatTime(t) {
  if (!t) return ''
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

Page({
  data: {
    keyword: '',
    notes: [],
    page: 0,
    size: 20,
    total: 0,
    hasMore: false,
    loading: false,
    loadingMore: false,
    trashMode: false,
    trashCount: 0,
    categories: [],
    filterCategoryId: ''
  },

  onShow() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadMeta()
    this.reload()
  },

  onPullDownRefresh() {
    this.reload().finally(() => wx.stopPullDownRefresh())
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.reload()
  },

  async loadMeta() {
    try {
      const [cats, tc] = await Promise.all([api.listCategories(), api.trashCount()])
      const flat = []
      const walk = (nodes, depth) => {
        ;(nodes || []).forEach((n) => {
          flat.push({ id: String(n.id), name: n.name, depth })
          if (n.children && n.children.length) walk(n.children, depth + 1)
        })
      }
      walk(cats || [], 0)
      this.setData({
        categories: flat.slice(0, 10),
        trashCount: Number((tc && tc.count) || 0)
      })
    } catch (e) {
      /* ignore */
    }
  },

  async reload() {
    this.setData({ page: 0, loading: true })
    try {
      const data = await api.listNotes({
        page: 0,
        size: this.data.size,
        keyword: this.data.keyword || undefined,
        onlyDeleted: this.data.trashMode || undefined,
        categoryId: !this.data.trashMode && this.data.filterCategoryId
          ? this.data.filterCategoryId
          : undefined
      })
      const items = (data.items || []).map((n) => ({
        ...n,
        updatedAtText: formatTime(n.updatedAt),
        formatLabel: n.contentFormat === 'markdown' ? 'MD' : '富文本'
      }))
      const total = data.total || 0
      this.setData({
        notes: items,
        total,
        page: 0,
        hasMore: items.length < total,
        trashCount: this.data.trashMode ? total : this.data.trashCount
      })
      if (!this.data.trashMode) {
        const tc = await api.trashCount()
        this.setData({ trashCount: Number((tc && tc.count) || 0) })
      }
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (!this.data.hasMore || this.data.loadingMore) return
    const next = this.data.page + 1
    this.setData({ loadingMore: true })
    try {
      const data = await api.listNotes({
        page: next,
        size: this.data.size,
        keyword: this.data.keyword || undefined,
        onlyDeleted: this.data.trashMode || undefined,
        categoryId: !this.data.trashMode && this.data.filterCategoryId
          ? this.data.filterCategoryId
          : undefined
      })
      const items = (data.items || []).map((n) => ({
        ...n,
        updatedAtText: formatTime(n.updatedAt),
        formatLabel: n.contentFormat === 'markdown' ? 'MD' : '富文本'
      }))
      const notes = this.data.notes.concat(items)
      const total = data.total || 0
      this.setData({
        notes,
        page: next,
        total,
        hasMore: notes.length < total
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loadingMore: false })
    }
  },

  toggleTrash() {
    this.setData({
      trashMode: !this.data.trashMode,
      filterCategoryId: '',
      page: 0
    })
    this.reload()
  },

  clearFilter() {
    this.setData({ trashMode: false, filterCategoryId: '', page: 0 })
    this.reload()
  },

  filterCat(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ trashMode: false, filterCategoryId: id, page: 0 })
    this.reload()
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
  },

  emptyTrash() {
    wx.showModal({
      title: '清空回收站',
      content: '将永久删除全部笔记及附件，不可恢复',
      confirmColor: '#ef4444',
      success: async (res) => {
        if (!res.confirm) return
        try {
          const r = await api.emptyTrash()
          wx.showToast({
            title: r && r.deleted ? `已清空${r.deleted}条` : '已空',
            icon: 'none'
          })
          this.reload()
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        }
      }
    })
  }
})
