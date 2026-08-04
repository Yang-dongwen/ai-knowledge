const { api } = require('../../utils/request')
const { isLoggedIn } = require('../../utils/auth')

function formatTime(t) {
  if (!t) return ''
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

Page({
  data: {
    loading: true,
    folderCount: 0,
    noteCount: 0,
    /** 扁平可见行 */
    rows: [],
    flatFolders: [],
    expandedIds: {},
    showSheet: false,
    sheetMode: '',
    sheetTitle: '',
    inputName: '',
    targetId: '',
    targetName: '',
    parentOptions: [],
    parentIndex: 0,
    deleteModeIndex: 1,
    deleteModes: [
      { value: 'reject', label: '仅空文件夹可删' },
      { value: 'orphan', label: '子项移到上级/未分类' },
      { value: 'trash', label: '文档进回收站' }
    ],
    busy: false,
    _tree: []
  },

  onShow() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const tree = await api.getExplorerTree()
      const roots = this.normalize(tree.roots || [], 0)
      const flatFolders = [{ id: '', name: '根目录' }]
      const walk = (nodes, depth) => {
        ;(nodes || []).forEach((n) => {
          if (n.type === 'folder') {
            flatFolders.push({
              id: String(n.id),
              name: (depth ? '— '.repeat(depth) : '') + n.name
            })
            if (n.children && n.children.length) walk(n.children, depth + 1)
          }
        })
      }
      walk(roots, 0)

      // 默认展开第一层文件夹
      const expandedIds = { ...this.data.expandedIds }
      roots.forEach((n) => {
        if (n.type === 'folder' && expandedIds[n.id] === undefined) {
          expandedIds[n.id] = true
        }
      })

      this._tree = roots
      this.setData({
        folderCount: tree.folderCount || 0,
        noteCount: tree.noteCount || 0,
        flatFolders,
        expandedIds,
        loading: false
      })
      this.rebuildRows()
    } catch (e) {
      this._tree = []
      this.setData({ loading: false, rows: [] })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  normalize(nodes, depth) {
    return (nodes || []).map((n) => {
      const type = n.type === 'folder' ? 'folder' : 'note'
      const children = type === 'folder' ? this.normalize(n.children || [], depth + 1) : []
      return {
        type,
        id: String(n.id),
        name: n.name || (type === 'folder' ? '未命名文件夹' : '未命名笔记'),
        pinned: !!n.pinned,
        formatLabel: type === 'note' ? (n.contentFormat === 'markdown' ? 'MD' : '富文本') : '',
        updatedAtText: formatTime(n.updatedAt),
        depth,
        children
      }
    })
  },

  rebuildRows() {
    const expanded = this.data.expandedIds || {}
    const rows = []
    const walk = (nodes) => {
      ;(nodes || []).forEach((n) => {
        const isFolder = n.type === 'folder'
        const hasChildren = isFolder && n.children && n.children.length > 0
        const expandedNow = !!(expanded[n.id])
        rows.push({
          type: n.type,
          id: n.id,
          typeId: n.type + '-' + n.id,
          name: n.name,
          pinned: n.pinned,
          formatLabel: n.formatLabel,
          depth: n.depth,
          hasChildren,
          expanded: expandedNow,
          pad: n.depth * 28 + 8
        })
        if (isFolder && expandedNow && hasChildren) {
          walk(n.children)
        }
      })
    }
    walk(this._tree || [])
    this.setData({ rows })
  },

  toggleExpand(e) {
    const id = e.currentTarget.dataset.id
    const expandedIds = { ...this.data.expandedIds }
    expandedIds[id] = !expandedIds[id]
    this.setData({ expandedIds })
    this.rebuildRows()
  },

  openNote(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
  },

  onFolderMore(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showActionSheet({
      itemList: ['新建子文件夹', '重命名', '上移', '下移', '移动到…', '删除'],
      success: (res) => {
        if (res.tapIndex === 0) this.openCreate(id)
        else if (res.tapIndex === 1) this.openRename(id, name)
        else if (res.tapIndex === 2) this.reorderSibling('folder', id, -1)
        else if (res.tapIndex === 3) this.reorderSibling('folder', id, 1)
        else if (res.tapIndex === 4) this.openMoveFolder(id, name)
        else if (res.tapIndex === 5) this.openDelete(id, name)
      }
    })
  },

  onNoteMore(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showActionSheet({
      itemList: ['移动到文件夹', '上移', '下移', '打开'],
      success: (res) => {
        if (res.tapIndex === 0) this.openMoveNote(id, name)
        else if (res.tapIndex === 1) this.reorderSibling('note', id, -1)
        else if (res.tapIndex === 2) this.reorderSibling('note', id, 1)
        else if (res.tapIndex === 3) {
          wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
        }
      }
    })
  },

  /** delta: -1 上移, +1 下移；同父级 folder 或 note 重排 */
  async reorderSibling(type, id, delta) {
    const parentInfo = this.findParentContext(type, String(id))
    if (!parentInfo || !parentInfo.siblings || parentInfo.siblings.length < 2) {
      wx.showToast({ title: '无法调整顺序', icon: 'none' })
      return
    }
    const ids = parentInfo.siblings.map((s) => s.id)
    const idx = ids.indexOf(String(id))
    const j = idx + delta
    if (idx < 0 || j < 0 || j >= ids.length) {
      wx.showToast({ title: delta < 0 ? '已在最上' : '已在最下', icon: 'none' })
      return
    }
    const next = ids.slice()
    const tmp = next[idx]
    next[idx] = next[j]
    next[j] = tmp
    try {
      const body = {
        type,
        orderedIds: next
      }
      if (parentInfo.parentId) body.parentFolderId = parentInfo.parentId
      else body.clearParent = true
      await api.treeReorder(body)
      wx.showToast({ title: '已调整', icon: 'success' })
      await this.load()
    } catch (e) {
      wx.showToast({ title: e.message || '排序失败', icon: 'none' })
    }
  },

  findParentContext(type, id) {
    let found = null
    const walk = (nodes, parentId) => {
      const list = nodes || []
      const sameType = list.filter((n) => n.type === type)
      if (sameType.some((n) => n.id === id)) {
        found = {
          parentId: parentId || '',
          siblings: sameType
        }
        return
      }
      list.forEach((n) => {
        if (n.type === 'folder' && n.children) walk(n.children, n.id)
      })
    }
    walk(this._tree || [], '')
    return found
  },

  openCreateRoot() {
    this.openCreate('')
  },

  openCreate(parentId) {
    const parentOptions = this.data.flatFolders
    let parentIndex = parentOptions.findIndex((x) => x.id === String(parentId || ''))
    if (parentIndex < 0) parentIndex = 0
    this.setData({
      showSheet: true,
      sheetMode: 'create',
      sheetTitle: '新建文件夹',
      inputName: '',
      parentOptions,
      parentIndex
    })
  },

  openRename(id, name) {
    this.setData({
      showSheet: true,
      sheetMode: 'rename',
      sheetTitle: '重命名文件夹',
      inputName: name || '',
      targetId: id,
      targetName: name || ''
    })
  },

  openDelete(id, name) {
    this.setData({
      showSheet: true,
      sheetMode: 'delete',
      sheetTitle: '删除文件夹',
      targetId: id,
      targetName: name || '',
      deleteModeIndex: 1
    })
  },

  openMoveNote(id, name) {
    this.setData({
      showSheet: true,
      sheetMode: 'moveNote',
      sheetTitle: '移动笔记',
      targetId: id,
      targetName: name || '',
      parentOptions: this.data.flatFolders,
      parentIndex: 0
    })
  },

  openMoveFolder(id, name) {
    const parentOptions = this.data.flatFolders.filter((f) => f.id !== String(id))
    this.setData({
      showSheet: true,
      sheetMode: 'moveFolder',
      sheetTitle: '移动文件夹',
      targetId: id,
      targetName: name || '',
      parentOptions,
      parentIndex: 0
    })
  },

  closeSheet() {
    if (this.data.busy) return
    this.setData({ showSheet: false, sheetMode: '', inputName: '' })
  },

  onInputName(e) {
    this.setData({ inputName: e.detail.value })
  },

  onParentPick(e) {
    this.setData({ parentIndex: Number(e.detail.value) })
  },

  onDeleteModePick(e) {
    this.setData({ deleteModeIndex: Number(e.detail.value) })
  },

  async submitSheet() {
    if (this.data.busy) return
    const mode = this.data.sheetMode
    this.setData({ busy: true })
    try {
      if (mode === 'create') {
        const name = (this.data.inputName || '').trim()
        if (!name) {
          wx.showToast({ title: '请输入名称', icon: 'none' })
          return
        }
        const opt = this.data.parentOptions[this.data.parentIndex]
        const parentId = opt && opt.id ? opt.id : null
        await api.createCategory({ name, parentId })
        wx.showToast({ title: '已创建', icon: 'success' })
      } else if (mode === 'rename') {
        const name = (this.data.inputName || '').trim()
        if (!name) {
          wx.showToast({ title: '请输入名称', icon: 'none' })
          return
        }
        await api.updateCategory(this.data.targetId, { name })
        wx.showToast({ title: '已重命名', icon: 'success' })
      } else if (mode === 'delete') {
        const dm = this.data.deleteModes[this.data.deleteModeIndex]
        await api.deleteCategory(this.data.targetId, dm.value)
        wx.showToast({ title: '已删除', icon: 'success' })
      } else if (mode === 'moveNote' || mode === 'moveFolder') {
        const opt = this.data.parentOptions[this.data.parentIndex]
        const targetFolderId = opt && opt.id ? opt.id : null
        await api.treeMove({
          type: mode === 'moveNote' ? 'note' : 'folder',
          id: this.data.targetId,
          ...(targetFolderId ? { targetFolderId } : { clearToRoot: true })
        })
        wx.showToast({ title: '已移动', icon: 'success' })
      }
      this.setData({ showSheet: false, sheetMode: '', inputName: '' })
      await this.load()
    } catch (e) {
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    } finally {
      this.setData({ busy: false })
    }
  }
})
