const { api, stripMediaTokens, mdImageSyntax } = require('../../utils/request')
const { requireLoginOrRedirect } = require('../../utils/auth')
const {
  extractTitle,
  emptyMarkdownDoc,
  emptyHtmlDoc,
  ensureMarkdownHasTitle,
  ensureHtmlHasTitle,
  splitMarkdownDoc,
  joinMarkdownDoc
} = require('../../utils/title')

Page({
  data: {
    id: '',
    format: 'html',
    formatLocked: false,
    title: '未命名笔记',
    mdBody: '',
    htmlContent: '',
    categoryId: '',
    categoryLabel: '未分类',
    categoryIndex: 0,
    categories: [],
    allTags: [],
    selectedTagIds: [],
    pinned: false,
    saving: false,
    files: [],
    pendingFileIds: []
  },

  onShow() {
    if (!requireLoginOrRedirect()) return
    const draft = wx.getStorageSync('kb_edit_draft')
    if (draft && draft.id) {
      this.loadFromDraft(draft)
      wx.removeStorageSync('kb_edit_draft')
      wx.removeStorageSync('kb_quick_draft')
      return
    }
    const quick = wx.getStorageSync('kb_quick_draft')
    if (quick && (quick.title || quick.mdBody || quick.htmlContent || quick.id)) {
      this.setData({
        id: quick.id || '',
        format: quick.format || 'html',
        formatLocked: !!quick.id,
        title: quick.title || '未命名笔记',
        mdBody: quick.mdBody || '',
        htmlContent: quick.htmlContent || emptyHtmlDoc(),
        categoryId: quick.categoryId || '',
        selectedTagIds: quick.selectedTagIds || [],
        pinned: !!quick.pinned,
        pendingFileIds: quick.pendingFileIds || []
      })
      wx.setNavigationBarTitle({ title: quick.id ? '编辑笔记' : '快记' })
      this.loadMeta()
      if (quick.id) this.loadFiles()
      this.refreshCategoryLabel()
      this.markTagSelected()
      return
    }
    if (!this.data.id) {
      this.setData({
        id: '',
        format: 'html',
        formatLocked: false,
        title: '未命名笔记',
        mdBody: '',
        htmlContent: emptyHtmlDoc(),
        categoryId: '',
        categoryLabel: '未分类',
        selectedTagIds: [],
        pinned: false,
        files: [],
        pendingFileIds: []
      })
      wx.setNavigationBarTitle({ title: '快记' })
    }
    this.loadMeta()
  },

  onHide() {
    // 保存成功后的跳转也会触发 onHide；勿把刚保存内容写回草稿，否则下次「快记」变成编辑旧笔记
    if (this.data.saving || this._skipDraftOnce) {
      this._skipDraftOnce = false
      return
    }
    wx.setStorageSync('kb_quick_draft', {
      id: this.data.id,
      format: this.data.format,
      title: this.data.title,
      mdBody: this.data.mdBody,
      htmlContent: this.data.htmlContent,
      categoryId: this.data.categoryId,
      selectedTagIds: this.data.selectedTagIds,
      pinned: this.data.pinned,
      pendingFileIds: this.data.pendingFileIds
    })
  },

  loadFromDraft(draft) {
    const format = draft.contentFormat === 'markdown' ? 'markdown' : 'html'
    let title = draft.title || '未命名笔记'
    let mdBody = ''
    let htmlContent = ''
    if (format === 'markdown') {
      const ensured = ensureMarkdownHasTitle(draft.content || '', title)
      const parts = splitMarkdownDoc(ensured)
      title = parts.title
      mdBody = parts.body
    } else {
      htmlContent = ensureHtmlHasTitle(draft.content || '', title)
      title = extractTitle(htmlContent, 'html')
    }
    const selectedTagIds = (draft.tags || []).map((t) => String(t.id))
    this.setData({
      id: draft.id,
      format,
      formatLocked: true,
      title,
      mdBody,
      htmlContent,
      categoryId: draft.categoryId ? String(draft.categoryId) : '',
      selectedTagIds,
      pinned: !!draft.pinned
    })
    wx.setNavigationBarTitle({ title: '编辑笔记' })
    this.loadMeta()
    this.loadFiles()
  },

  async loadMeta() {
    try {
      const [cats, tags] = await Promise.all([api.listCategories(), api.listTags()])
      const flat = [{ id: '', name: '未分类' }]
      const walk = (nodes, depth) => {
        ;(nodes || []).forEach((n) => {
          flat.push({
            id: String(n.id),
            name: (depth ? '— '.repeat(depth) : '') + n.name
          })
          if (n.children && n.children.length) walk(n.children, depth + 1)
        })
      }
      walk(cats || [], 0)
      this.setData({ categories: flat, allTags: tags || [] })
      this.refreshCategoryLabel()
      this.markTagSelected()
    } catch (e) {
      /* ignore */
    }
  },

  refreshCategoryLabel() {
    const list = this.data.categories || []
    let idx = list.findIndex((x) => x.id === (this.data.categoryId || ''))
    if (idx < 0) idx = 0
    const c = list[idx]
    this.setData({
      categoryIndex: idx,
      categoryLabel: c ? c.name : '未分类',
      categoryId: c ? c.id : ''
    })
  },

  markTagSelected() {
    const set = new Set((this.data.selectedTagIds || []).map(String))
    const allTags = (this.data.allTags || []).map((t) => ({
      ...t,
      id: String(t.id),
      selected: set.has(String(t.id))
    }))
    this.setData({ allTags })
  },

  async loadFiles() {
    if (!this.data.id) return
    try {
      const files = (await api.listFiles(this.data.id)) || []
      this.setData({ files })
    } catch (e) {
      this.setData({ files: [] })
    }
  },

  pickFormat(e) {
    if (this.data.formatLocked) return
    const format = e.currentTarget.dataset.format
    this.setData({
      format,
      formatLocked: true,
      htmlContent: format === 'html' ? emptyHtmlDoc(this.data.title) : this.data.htmlContent,
      mdBody: format === 'markdown' ? '' : this.data.mdBody
    })
  },

  onTitle(e) {
    this.setData({ title: e.detail.value })
  },

  onMdBody(e) {
    this.setData({ mdBody: e.detail.value })
  },

  onHtmlContent(e) {
    this.setData({ htmlContent: e.detail.value })
  },

  /** 轻量格式工具：在正文末尾插入片段（移动端无选区时最稳） */
  applyFmt(e) {
    const cmd = e.currentTarget.dataset.cmd
    if (this.data.format === 'markdown') {
      let body = this.data.mdBody || ''
      if (cmd === 'md-bold') body += (body && !body.endsWith('\n') ? '\n' : '') + '**粗体文字**'
      else if (cmd === 'md-ul') body += (body && !body.endsWith('\n') ? '\n' : '') + '- 列表项\n'
      else if (cmd === 'md-quote') body += (body && !body.endsWith('\n') ? '\n' : '') + '> 引用\n'
      this.setData({ mdBody: body })
      return
    }
    let html = this.data.htmlContent || ''
    if (cmd === 'bold') html += '<p><strong>粗体文字</strong></p>'
    else if (cmd === 'ul') html += '<ul><li>列表项</li></ul>'
    else if (cmd === 'quote') html += '<blockquote>引用</blockquote>'
    else if (cmd === 'h2') html += '<h2>小标题</h2>'
    this.setData({ htmlContent: html })
  },

  onCategory(e) {
    const idx = Number(e.detail.value)
    const c = this.data.categories[idx]
    this.setData({
      categoryIndex: idx,
      categoryId: c ? c.id : '',
      categoryLabel: c ? c.name : '未分类'
    })
  },

  toggleTag(e) {
    const id = String(e.currentTarget.dataset.id)
    const set = new Set((this.data.selectedTagIds || []).map(String))
    if (set.has(id)) set.delete(id)
    else set.add(id)
    this.setData({ selectedTagIds: Array.from(set) })
    this.markTagSelected()
  },

  createTag() {
    wx.showModal({
      title: '新建标签',
      editable: true,
      placeholderText: '标签名称',
      success: async (res) => {
        if (!res.confirm) return
        const name = (res.content || '').trim()
        if (!name) {
          wx.showToast({ title: '名称不能为空', icon: 'none' })
          return
        }
        try {
          const tag = await api.createTag(name)
          const id = String(tag.id)
          const selected = new Set((this.data.selectedTagIds || []).map(String))
          selected.add(id)
          this.setData({ selectedTagIds: Array.from(selected) })
          await this.loadMeta()
          wx.showToast({ title: '已创建并选中', icon: 'success' })
        } catch (e) {
          wx.showToast({ title: e.message || '创建失败', icon: 'none' })
        }
      }
    })
  },

  onPinned(e) {
    this.setData({ pinned: !!e.detail.value })
  },

  onClear() {
    this.setData({
      id: '',
      title: '未命名笔记',
      mdBody: '',
      htmlContent: emptyHtmlDoc(),
      format: 'html',
      formatLocked: false,
      categoryId: '',
      categoryLabel: '未分类',
      selectedTagIds: [],
      pinned: false,
      files: [],
      pendingFileIds: []
    })
    this.markTagSelected()
    wx.removeStorageSync('kb_quick_draft')
    wx.setNavigationBarTitle({ title: '快记' })
  },

  pickImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: async (res) => {
        const path = res.tempFiles[0].tempFilePath
        wx.showLoading({ title: '上传中' })
        try {
          const file = await api.uploadFile(path, this.data.id || undefined)
          const p = file.contentPath.startsWith('/') ? file.contentPath : '/' + file.contentPath
          if (!this.data.id) {
            const pending = (this.data.pendingFileIds || []).concat([file.id])
            this.setData({ pendingFileIds: pending })
          }
          if (this.data.format === 'html') {
            const html =
              (this.data.htmlContent || '') +
              `<p><img src="${p}" style="max-width:100%"/></p>`
            this.setData({ htmlContent: html })
          } else {
            const body = (this.data.mdBody || '') + `\n\n${mdImageSyntax(p)}\n`
            this.setData({ mdBody: body })
          }
          if (this.data.id) await this.loadFiles()
          wx.showToast({ title: '已插入', icon: 'success' })
        } catch (e) {
          wx.showToast({ title: e.message || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  pickFile() {
    if (!this.data.id) {
      wx.showToast({ title: '请先保存笔记', icon: 'none' })
      return
    }
    wx.chooseMessageFile({
      count: 5,
      type: 'file',
      success: async (res) => {
        wx.showLoading({ title: '上传中' })
        try {
          for (const f of res.tempFiles || []) {
            await api.uploadFile(f.path, this.data.id)
          }
          await this.loadFiles()
          wx.showToast({ title: '已上传', icon: 'success' })
        } catch (e) {
          wx.showToast({ title: e.message || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  removeFile(e) {
    const id = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name || '附件'
    wx.showModal({
      title: '删除附件',
      content: name,
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.deleteFile(id)
          await this.loadFiles()
        } catch (err) {
          wx.showToast({ title: err.message || '删除失败', icon: 'none' })
        }
      }
    })
  },

  async onSave() {
    this.setData({ saving: true })
    try {
      let content
      const format = this.data.format
      if (format === 'markdown') {
        content = joinMarkdownDoc(this.data.title, this.data.mdBody)
        content = ensureMarkdownHasTitle(content, this.data.title)
      } else {
        content = ensureHtmlHasTitle(this.data.htmlContent || '', this.data.title)
      }
      content = stripMediaTokens(content)
      const title = extractTitle(content, format)
      const body = {
        title,
        content,
        contentFormat: format,
        categoryId: this.data.categoryId || null,
        clearCategory: !this.data.categoryId,
        tagIds: this.data.selectedTagIds || [],
        pinned: this.data.pinned
      }
      if (this.data.id) {
        await api.updateNote(this.data.id, body)
      } else {
        const note = await api.createNote(body)
        this.setData({ id: note.id, formatLocked: true })
        for (const fid of this.data.pendingFileIds || []) {
          try {
            await api.bindFile(fid, note.id)
          } catch (e) {
            /* ignore */
          }
        }
        this.setData({ pendingFileIds: [] })
      }
      wx.removeStorageSync('kb_quick_draft')
      this._skipDraftOnce = true
      wx.showToast({ title: '已保存', icon: 'success' })
      // 保持 saving=true 直到离开，防止 onHide 在 finally 后重写草稿
      setTimeout(() => {
        this.setData({ saving: false })
        wx.switchTab({ url: '/pages/notes/notes' })
      }, 400)
      return
    } catch (e) {
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    }
    this.setData({ saving: false })
  }
})
