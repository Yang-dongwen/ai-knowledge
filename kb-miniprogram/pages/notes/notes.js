const { api } = require('../../utils/request')
const { requireLoginOrRedirect } = require('../../utils/auth')

function formatTime(t) {
  if (!t) return ''
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

function mapNote(n) {
  return {
    ...n,
    id: String(n.id),
    updatedAtText: formatTime(n.updatedAt),
    formatLabel: n.contentFormat === 'markdown' ? 'MD' : '富文本',
    tags: (n.tags || []).map((t) => ({ id: String(t.id), name: t.name }))
  }
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
    tags: [],
    filterCategoryId: '',
    filterUncategorized: false,
    filterTagId: '',
    filterLabel: '全部',
    openId: '',
    _tx: 0
  },

  onShow() {
    if (!requireLoginOrRedirect()) {
      return
    }
    // 标签页「筛选笔记」中转
    const pending = wx.getStorageSync('kb_list_filter')
    if (pending && (pending.tagId || pending.categoryId || pending.uncategorized)) {
      wx.removeStorageSync('kb_list_filter')
      this.resetFilters({
        filterTagId: pending.tagId ? String(pending.tagId) : '',
        filterCategoryId: pending.categoryId ? String(pending.categoryId) : '',
        filterUncategorized: !!pending.uncategorized
      })
    }
    this.loadMeta()
    this.reload()
  },

  onPullDownRefresh() {
    Promise.all([this.loadMeta(), this.reload()]).finally(() => wx.stopPullDownRefresh())
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.reload()
  },

  buildFilterLabel() {
    if (this.data.trashMode) return '回收站'
    if (this.data.filterTagId) {
      const t = (this.data.tags || []).find((x) => x.id === this.data.filterTagId)
      return t ? '#' + t.name : '标签'
    }
    if (this.data.filterUncategorized) return '未分类'
    if (this.data.filterCategoryId) {
      const c = (this.data.categories || []).find((x) => x.id === this.data.filterCategoryId)
      return c ? c.name : '分类'
    }
    return '全部'
  },

  async loadMeta() {
    try {
      const [cats, tags, tc] = await Promise.all([
        api.listCategories(),
        api.listTags(),
        api.trashCount()
      ])
      const flat = []
      const walk = (nodes, depth) => {
        ;(nodes || []).forEach((n) => {
          flat.push({
            id: String(n.id),
            name: n.name,
            depth,
            label: (depth ? '··'.repeat(depth) : '') + n.name
          })
          if (n.children && n.children.length) walk(n.children, depth + 1)
        })
      }
      walk(cats || [], 0)
      const tagList = (tags || []).map((t) => ({
        id: String(t.id),
        name: t.name,
        noteCount: t.noteCount
      }))
      this.setData({
        categories: flat,
        tags: tagList,
        trashCount: Number((tc && tc.count) || 0),
        filterLabel: this.buildFilterLabel()
      })
    } catch (e) {
      /* ignore */
    }
  },

  listQuery(page) {
    const q = {
      page,
      size: this.data.size,
      keyword: this.data.keyword || undefined,
      onlyDeleted: this.data.trashMode || undefined
    }
    if (!this.data.trashMode) {
      if (this.data.filterTagId) q.tagId = this.data.filterTagId
      else if (this.data.filterUncategorized) q.uncategorized = true
      else if (this.data.filterCategoryId) q.categoryId = this.data.filterCategoryId
    }
    return q
  },

  async reload() {
    this.setData({ page: 0, loading: true, filterLabel: this.buildFilterLabel() })
    try {
      const data = await api.listNotes(this.listQuery(0))
      const items = (data.items || []).map(mapNote)
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
      const data = await api.listNotes(this.listQuery(next))
      const items = (data.items || []).map(mapNote)
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

  resetFilters(extra = {}) {
    this.setData({
      trashMode: false,
      filterCategoryId: '',
      filterUncategorized: false,
      filterTagId: '',
      page: 0,
      ...extra
    })
  },

  clearFilter() {
    this.resetFilters()
    this.reload()
  },

  filterUncategorized() {
    this.resetFilters({ filterUncategorized: true })
    this.reload()
  },

  filterCat(e) {
    const id = e.currentTarget.dataset.id
    this.resetFilters({ filterCategoryId: id })
    this.reload()
  },

  filterTag(e) {
    const id = e.currentTarget.dataset.id
    this.resetFilters({ filterTagId: id })
    this.reload()
  },

  toggleTrash() {
    const next = !this.data.trashMode
    this.resetFilters({ trashMode: next })
    this.reload()
  },

  onTouchStart(e) {
    const t = e.changedTouches && e.changedTouches[0]
    this._tx = t ? t.clientX : 0
  },

  onTouchEnd(e) {
    const t = e.changedTouches && e.changedTouches[0]
    if (!t || this.data.trashMode) return
    const dx = t.clientX - this._tx
    const id = e.currentTarget.dataset.id
    if (dx < -48) {
      this.setData({ openId: id })
    } else if (dx > 40 && this.data.openId === id) {
      this.setData({ openId: '' })
    }
  },

  onNoteLongPress(e) {
    if (this.data.trashMode) return
    const id = e.currentTarget.dataset.id
    const note = (this.data.notes || []).find((n) => String(n.id) === String(id))
    if (!note) return
    wx.showActionSheet({
      itemList: [note.pinned ? '取消置顶' : '置顶', '移入回收站'],
      success: (res) => {
        if (res.tapIndex === 0) this.togglePin(id, note.pinned)
        else if (res.tapIndex === 1) this.softDelete(id)
      }
    })
  },

  onTogglePin(e) {
    const id = e.currentTarget.dataset.id
    const pinned = e.currentTarget.dataset.pinned
    this.togglePin(id, pinned)
  },

  async togglePin(id, pinned) {
    try {
      await api.updateNote(id, { pinned: !pinned })
      this.setData({ openId: '' })
      this.reload()
    } catch (e) {
      wx.showToast({ title: e.message || '失败', icon: 'none' })
    }
  },

  onSoftDelete(e) {
    this.softDelete(e.currentTarget.dataset.id)
  },

  softDelete(id) {
    wx.showModal({
      title: '移入回收站',
      content: '可在回收站恢复',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.deleteNote(id)
          this.setData({ openId: '' })
          wx.showToast({ title: '已删除', icon: 'success' })
          this.reload()
        } catch (e) {
          wx.showToast({ title: e.message || '失败', icon: 'none' })
        }
      }
    })
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.openId === id) {
      this.setData({ openId: '' })
      return
    }
    if (this.data.openId) {
      this.setData({ openId: '' })
    }
    wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
  },

  goFolders() {
    wx.navigateTo({ url: '/pages/folders/folders' })
  },

  goTags() {
    wx.navigateTo({ url: '/pages/tags/tags' })
  },

  goCreate() {
    wx.switchTab({ url: '/pages/edit/edit' })
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
