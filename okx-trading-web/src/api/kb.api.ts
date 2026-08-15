import request, { handleAuthFailure } from './request'
import axios from 'axios'

const TOKEN_KEY = 'okx_auth_token'

export interface KbTagBrief {
  id: string
  name: string
}

export type KbContentFormat = 'html' | 'markdown'

export interface KbNoteItem {
  id: string
  title: string
  content?: string
  contentFormat?: KbContentFormat
  snippet?: string
  /** 搜索命中片段 */
  matchSnippet?: string
  categoryId?: string | null
  categoryName?: string | null
  tags?: KbTagBrief[]
  pinned: boolean
  deleted: boolean
  haloPostName?: string | null
  haloPermalink?: string | null
  haloPublishedAt?: string | null
  unresolvedMedia?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface KbNoteRevision {
  id: string
  noteId: string
  title?: string
  content?: string
  contentFormat?: KbContentFormat
  source?: string
  snippet?: string
  createdAt?: string
}

export interface KbNotePage {
  items: KbNoteItem[]
  total: number
  page: number
  size: number
}

export interface KbCategory {
  id: string
  name: string
  parentId?: string | null
  sortOrder?: number
  children?: KbCategory[]
  createdAt?: string
  updatedAt?: string
}

/** 目录树节点（folder | note） */
export interface KbExplorerNode {
  type: 'folder' | 'note' | string
  id: string
  name: string
  parentId?: string | null
  contentFormat?: KbContentFormat | string
  pinned?: boolean
  snippet?: string
  updatedAt?: string
  sortOrder?: number
  children?: KbExplorerNode[]
}

export interface KbExplorerTree {
  roots: KbExplorerNode[]
  folderCount: number
  noteCount: number
}

export interface KbTag {
  id: string
  name: string
  noteCount?: number
  createdAt?: string
}

export type KbFileKind = 'image' | 'video' | 'audio' | 'pdf' | 'office' | 'other'

export interface KbFileItem {
  id: string
  noteId?: string | null
  originalName: string
  contentType?: string
  sizeBytes: number
  kind: KbFileKind
  contentPath: string
  createdAt?: string
}

export interface NoteCreateBody {
  title?: string
  content?: string
  contentFormat?: KbContentFormat
  categoryId?: string | null
  tagIds?: string[]
  pinned?: boolean
}

export interface HaloTermItem {
  name: string
  displayName: string
}

export interface BlogPublishOptions {
  published: boolean
  permalink?: string | null
  categories: HaloTermItem[]
  tags: HaloTermItem[]
  selectedCategoryNames: string[]
  selectedTagNames: string[]
  mediaCount: number
}

export interface BlogPublishBody {
  categoryNames?: string[]
  tagNames?: string[]
}

export interface NoteUpdateBody {
  title?: string
  content?: string
  contentFormat?: KbContentFormat
  categoryId?: string | null
  clearCategory?: boolean
  tagIds?: string[]
  pinned?: boolean
}

/** 知识库媒体路径（正文存库用干净路径，不含 token） */
const KB_FILE_CONTENT_RE = /\/api\/v1\/kb\/files\/\d+\/content/i

/** 给 <img>/<video>/iframe 用：query 带 JWT（无法设 Authorization 头） */
export function kbMediaUrl(contentPath: string): string {
  if (!contentPath) return ''
  if (contentPath.startsWith('blob:') || contentPath.startsWith('data:')) {
    return contentPath
  }
  // 去掉旧 token，避免叠加
  let path = contentPath.trim()
  try {
    if (/^https?:\/\//i.test(path)) {
      const u = new URL(path)
      path = u.pathname + (u.search || '')
    }
  } catch {
    /* ignore */
  }
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/\?&/, '?').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = `/${path}`
  const token = localStorage.getItem(TOKEN_KEY) || ''
  if (!token) return path
  const sep = path.includes('?') ? '&' : '?'
  return `${path}${sep}access_token=${encodeURIComponent(token)}`
}

/**
 * 存库前：去掉 HTML 内媒体 URL 上的 access_token，只保留 /api/v1/kb/files/{id}/content
 * 否则 token 过期或转义后图片在正文里加载失败，看起来像「只在附件里有」。
 * 无知识库媒体路径时直接返回，避免大文档无意义正则。
 */
export function stripKbMediaTokens(html: string): string {
  if (!html) return html
  if (!html.includes('/api/v1/kb/files/')) return html
  return html.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)[^"']*(["'])/gi,
    (_m, pre: string, path: string, post: string) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return `${pre}${clean}${post}`
    }
  )
}

/** 展示前：给正文中的知识库媒体路径注入当前 JWT（HTML 属性） */
export function injectKbMediaTokens(html: string): string {
  if (!html) return html
  if (!html.includes('/api/v1/kb/files/')) return html
  const token = localStorage.getItem(TOKEN_KEY) || ''
  if (!token) return html
  return html.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)([^"']*)(["'])/gi,
    (_m, pre: string, path: string, _rest: string, post: string) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      let clean = m ? m[1] : path.split('?')[0]
      if (!clean.startsWith('/') && !/^https?:/i.test(clean)) clean = `/${clean}`
      try {
        if (/^https?:\/\//i.test(clean)) {
          clean = new URL(clean).pathname
        }
      } catch {
        /* ignore */
      }
      return `${pre}${clean}?access_token=${encodeURIComponent(token)}${post}`
    }
  )
}

/**
 * Markdown 正文存库前：去掉 ![](.../content?access_token=) 中的 token
 */
export function stripKbMediaTokensInMarkdown(md: string): string {
  if (!md) return md
  if (!md.includes('/api/v1/kb/files/')) return md
  return md.replace(
    /(\]\()([^)\s]*\/api\/v1\/kb\/files\/\d+\/content)[^)]*(\))/gi,
    (_m, pre: string, path: string, post: string) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return `${pre}${clean}${post}`
    }
  )
}

/** HTML + Markdown 统一清理 token */
export function stripKbMediaTokensAll(text: string): string {
  return stripKbMediaTokensInMarkdown(stripKbMediaTokens(text || ''))
}

/** 生成 Markdown 图片语法（存库用干净路径） */
export function mdImageSyntax(contentPath: string, alt = 'image'): string {
  let path = (contentPath || '').trim()
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = `/${path}`
  const safeAlt = (alt || 'image').replace(/[[\]]/g, '')
  return `![${safeAlt}](${path})`
}

export function isKbFileContentUrl(url: string): boolean {
  return KB_FILE_CONTENT_RE.test(url || '')
}

/**
 * 个人知识库 API（/api/v1/kb/*）
 */
export const kbApi = {
  listNotes(params: {
    page?: number
    size?: number
    categoryId?: string | null
    tagId?: string | null
    keyword?: string
    includeDeleted?: boolean
    uncategorized?: boolean
    onlyDeleted?: boolean
    onlyPinned?: boolean
  } = {}): Promise<{ data: KbNotePage }> {
    return request.get('/v1/kb/notes', {
      params: {
        page: params.page ?? 0,
        size: params.size ?? 20,
        ...(params.categoryId ? { categoryId: params.categoryId } : {}),
        ...(params.tagId ? { tagId: params.tagId } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.includeDeleted ? { includeDeleted: true } : {}),
        ...(params.uncategorized ? { uncategorized: true } : {}),
        ...(params.onlyDeleted ? { onlyDeleted: true } : {}),
        ...(params.onlyPinned ? { onlyPinned: true } : {})
      }
    })
  },

  getNote(id: string): Promise<{ data: KbNoteItem }> {
    // 大正文可能超过默认 15s；详情单独放宽
    return request.get(`/v1/kb/notes/${id}`, { timeout: 120000 })
  },

  createNote(body: NoteCreateBody): Promise<{ data: KbNoteItem }> {
    return request.post('/v1/kb/notes', body)
  },

  updateNote(id: string, body: NoteUpdateBody): Promise<{ data: KbNoteItem }> {
    return request.put(`/v1/kb/notes/${id}`, body)
  },

  /** 软删除 → 回收站 */
  deleteNote(id: string): Promise<{ data: null }> {
    return request.delete(`/v1/kb/notes/${id}`)
  },

  restoreNote(id: string): Promise<{ data: KbNoteItem }> {
    return request.post(`/v1/kb/notes/${id}/restore`)
  },

  getBlogPublishOptions(id: string): Promise<{ data: BlogPublishOptions }> {
    return request.get(`/v1/kb/notes/${id}/publish-blog`, { timeout: 30000 })
  },

  publishNoteToBlog(
    id: string,
    body: BlogPublishBody = {}
  ): Promise<{ data: KbNoteItem }> {
    return request.post(`/v1/kb/notes/${id}/publish-blog`, body, { timeout: 180000 })
  },

  /** 永久删除（含 R2/本地附件对象） */
  permanentDeleteNote(id: string): Promise<{ data: null }> {
    return request.delete(`/v1/kb/notes/${id}/permanent`)
  },

  trashCount(): Promise<{ data: { count: number } }> {
    return request.get('/v1/kb/notes/trash/count')
  },

  emptyTrash(): Promise<{ data: { deleted: number } }> {
    return request.delete('/v1/kb/notes/trash')
  },

  getShareStatus(noteId: string): Promise<{ data: KbShareStatus }> {
    return request.get(`/v1/kb/notes/${noteId}/share`)
  },

  enableShare(noteId: string): Promise<{ data: KbShareStatus }> {
    return request.post(`/v1/kb/notes/${noteId}/share`)
  },

  disableShare(noteId: string): Promise<{ data: KbShareStatus }> {
    return request.delete(`/v1/kb/notes/${noteId}/share`)
  },

  rotateShare(noteId: string): Promise<{ data: KbShareStatus }> {
    return request.post(`/v1/kb/notes/${noteId}/share/rotate`)
  },

  /** 目录树：文件夹 + 文档叶子（无正文） */
  getExplorerTree(): Promise<{ data: KbExplorerTree }> {
    return request.get('/v1/kb/tree')
  },

  /** 移动文件夹或文档 */
  treeMove(body: {
    type: 'folder' | 'note'
    id: string
    targetFolderId?: string | null
    clearToRoot?: boolean
  }): Promise<{ data: null }> {
    return request.post('/v1/kb/tree/move', {
      type: body.type,
      id: body.id,
      ...(body.clearToRoot
        ? { clearToRoot: true }
        : body.targetFolderId != null
          ? { targetFolderId: body.targetFolderId }
          : { clearToRoot: true })
    })
  },

  /** 同级重排 */
  treeReorder(body: {
    type: 'folder' | 'note'
    parentFolderId?: string | null
    clearParent?: boolean
    orderedIds: string[]
  }): Promise<{ data: null }> {
    return request.post('/v1/kb/tree/reorder', {
      type: body.type,
      orderedIds: body.orderedIds,
      ...(body.clearParent || body.parentFolderId == null
        ? { clearParent: true }
        : { parentFolderId: body.parentFolderId })
    })
  },

  batchMoveNotes(body: {
    noteIds: string[]
    targetFolderId?: string | null
    clearToRoot?: boolean
  }): Promise<{ data: { moved: number } }> {
    return request.post('/v1/kb/notes/batch-move', {
      noteIds: body.noteIds,
      ...(body.clearToRoot
        ? { clearToRoot: true }
        : body.targetFolderId != null
          ? { targetFolderId: body.targetFolderId }
          : { clearToRoot: true })
    })
  },

  listCategories(): Promise<{ data: KbCategory[] }> {
    return request.get('/v1/kb/categories')
  },

  createCategory(body: {
    name: string
    parentId?: string | null
    sortOrder?: number
  }): Promise<{ data: KbCategory }> {
    return request.post('/v1/kb/categories', body)
  },

  updateCategory(
    id: string,
    body: {
      name?: string
      parentId?: string | null
      clearParent?: boolean
      sortOrder?: number
    }
  ): Promise<{ data: KbCategory }> {
    return request.put(`/v1/kb/categories/${id}`, body)
  },

  /**
   * 删除文件夹
   * @param mode reject | orphan | trash
   */
  deleteCategory(
    id: string,
    mode: 'reject' | 'orphan' | 'trash' = 'reject'
  ): Promise<{ data: { foldersDeleted: number; notesOrphaned: number; notesTrashed: number } }> {
    return request.delete(`/v1/kb/categories/${id}`, { params: { mode } })
  },

  listTags(): Promise<{ data: KbTag[] }> {
    return request.get('/v1/kb/tags')
  },

  createTag(name: string): Promise<{ data: KbTag }> {
    return request.post('/v1/kb/tags', { name })
  },

  updateTag(id: string, name: string): Promise<{ data: KbTag }> {
    return request.put(`/v1/kb/tags/${id}`, { name })
  },

  deleteTag(id: string): Promise<{ data: null }> {
    return request.delete(`/v1/kb/tags/${id}`)
  },

  async uploadFile(file: File, noteId?: string | null): Promise<{ data: KbFileItem }> {
    const fd = new FormData()
    fd.append('file', file)
    if (noteId) fd.append('noteId', noteId)
    const token = localStorage.getItem(TOKEN_KEY)
    try {
      const res = await axios.post('/api/v1/kb/files', fd, {
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        timeout: 120000
      })
      const body = res.data
      if (!body?.success) {
        throw new Error(body?.message || '上传失败')
      }
      return { data: body.data }
    } catch (e: any) {
      const status = e?.response?.status
      if (status === 401) {
        handleAuthFailure(401, e?.response?.data?.message)
      }
      throw e
    }
  },

  listFiles(noteId: string): Promise<{ data: KbFileItem[] }> {
    return request.get('/v1/kb/files', { params: { noteId } })
  },

  bindFile(fileId: string, noteId: string): Promise<{ data: KbFileItem }> {
    return request.post(`/v1/kb/files/${fileId}/bind`, { noteId })
  },

  deleteFile(fileId: string): Promise<{ data: null }> {
    return request.delete(`/v1/kb/files/${fileId}`)
  },

  duplicateNote(id: string): Promise<{ data: KbNoteItem }> {
    return request.post(`/v1/kb/notes/${id}/duplicate`)
  },

  listRevisions(noteId: string): Promise<{ data: KbNoteRevision[] }> {
    return request.get(`/v1/kb/notes/${noteId}/revisions`)
  },

  getRevision(noteId: string, revisionId: string): Promise<{ data: KbNoteRevision }> {
    return request.get(`/v1/kb/notes/${noteId}/revisions/${revisionId}`)
  },

  restoreRevision(noteId: string, revisionId: string): Promise<{ data: KbNoteItem }> {
    return request.post(`/v1/kb/notes/${noteId}/revisions/${revisionId}/restore`)
  },

  /** 导出 Markdown 并触发浏览器下载 */
  async exportNoteMarkdown(id: string, filenameHint?: string): Promise<void> {
    const token = localStorage.getItem(TOKEN_KEY)
    const res = await axios.get(`/api/v1/kb/notes/${id}/export`, {
      params: { format: 'md' },
      responseType: 'blob',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      timeout: 120000
    })
    if (res.status === 401) {
      handleAuthFailure(401, '未登录或登录已过期')
      throw new Error('未登录')
    }
    const blob = res.data as Blob
    let name = filenameHint?.trim() || `note-${id}.md`
    if (!name.toLowerCase().endsWith('.md')) name += '.md'
    const cd = res.headers?.['content-disposition'] as string | undefined
    if (cd) {
      const m = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(cd)
      const raw = decodeURIComponent((m?.[1] || m?.[2] || '').trim())
      if (raw) name = raw
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }
}

export interface KbShareStatus {
  noteId: string
  enabled: boolean
  shareToken?: string | null
  sharePath?: string | null
  enabledAt?: string | null
}

export interface KbPublicNote {
  title: string
  content: string
  contentFormat: 'html' | 'markdown' | string
  authorName?: string
  updatedAt?: string
  publishedAt?: string
  tags?: string[]
}

/** 公开分享（无需登录） */
export async function fetchPublicNote(token: string): Promise<KbPublicNote> {
  const res = await fetch(`/api/v1/kb/public/s/${encodeURIComponent(token)}`)
  const body = await res.json()
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || '分享不存在或已关闭')
  }
  return body.data as KbPublicNote
}
