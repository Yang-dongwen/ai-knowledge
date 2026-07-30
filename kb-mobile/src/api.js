const TOKEN_KEY = 'kb_token'
const USER_KEY = 'kb_user'
const BASE_KEY = 'kb_base_url'

export function getBaseUrl() {
  const stored = localStorage.getItem(BASE_KEY)
  if (stored != null && stored.trim() !== '') {
    return stored.trim().replace(/\/$/, '')
  }
  return ''
}

export function setBaseUrl(url) {
  const v = (url || '').trim().replace(/\/$/, '')
  if (!v) {
    localStorage.removeItem(BASE_KEY)
    return '(同源代理 /api → :8080)'
  }
  localStorage.setItem(BASE_KEY, v)
  return v
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export function setSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token || '')
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function isLoggedIn() {
  return !!getToken()
}

async function request(path, { method = 'GET', body, auth = true, formData = null, timeout = 60000 } = {}) {
  const headers = {}
  if (!formData) headers['Content-Type'] = 'application/json'
  if (auth) {
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  const base = getBaseUrl()
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeout)

  try {
    const res = await fetch(base + path, {
      method,
      headers,
      body: formData || (body != null ? JSON.stringify(body) : undefined),
      signal: ctrl.signal
    })

    // 文件流
    const ct = res.headers.get('content-type') || ''
    if (ct && !ct.includes('application/json') && res.ok) {
      return res
    }

    let data = null
    try {
      data = await res.json()
    } catch {
      data = null
    }

    if (res.status === 401) {
      clearSession()
      const err = new Error((data && data.message) || '未登录或登录已过期')
      err.code = 401
      throw err
    }

    if (!res.ok) {
      throw new Error((data && data.message) || `请求失败 ${res.status}`)
    }

    if (data && data.success === true) {
      return data.data
    }
    throw new Error((data && data.message) || '请求失败')
  } finally {
    clearTimeout(timer)
  }
}

export function kbMediaUrl(contentPath) {
  if (!contentPath) return ''
  if (contentPath.startsWith('blob:') || contentPath.startsWith('data:')) return contentPath
  let path = contentPath.trim()
  try {
    if (/^https?:\/\//i.test(path)) {
      const u = new URL(path)
      path = u.pathname
    }
  } catch {
    /* ignore */
  }
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = `/${path}`
  const token = getToken()
  if (!token) return (getBaseUrl() || '') + path
  const base = getBaseUrl() || ''
  return `${base}${path}${path.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(token)}`
}

export function injectKbMediaTokens(html) {
  if (!html || !html.includes('/api/v1/kb/files/')) return html || ''
  const token = getToken()
  if (!token) return html
  const base = getBaseUrl() || ''
  return html.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)([^"']*)(["'])/gi,
    (_m, pre, path) => {
      let clean = path.split('?')[0]
      if (!clean.startsWith('/')) clean = `/${clean}`
      try {
        if (/^https?:\/\//i.test(clean)) clean = new URL(clean).pathname
      } catch {
        /* ignore */
      }
      return `${pre}${base}${clean}?access_token=${encodeURIComponent(token)}${_m.endsWith('"') ? '"' : "'"}`.replace(
        /"$/,
        '"'
      )
    }
  )
}

// 更稳妥的 inject
export function injectMediaInHtml(html) {
  if (!html) return html || ''
  const base = getBaseUrl() || ''
  let out = html
  // 私有媒体：注入 JWT
  if (out.includes('/api/v1/kb/files/')) {
    const token = getToken()
    if (token) {
      out = out.replace(
        /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)[^"']*(["'])/gi,
        (_m, pre, path, post) => {
          let clean = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
          clean = clean ? clean[1] : path.split('?')[0]
          if (!clean.startsWith('/')) clean = `/${clean}`
          try {
            if (/^https?:\/\//i.test(clean)) clean = new URL(clean).pathname
          } catch {
            /* ignore */
          }
          return `${pre}${base}${clean}?access_token=${encodeURIComponent(token)}${post}`
        }
      )
    }
  }
  // 公开分享媒体或其它相对 /api 路径：补 base（代理模式下 base 为空即可）
  if (base && out.includes('/api/v1/kb/')) {
    out = out.replace(
      /((?:src|href)=["'])(\/api\/v1\/kb\/[^"']+)(["'])/gi,
      (_m, pre, path, post) => `${pre}${base}${path}${post}`
    )
  }
  return out
}

export function stripKbMediaTokens(html) {
  if (!html || !html.includes('/api/v1/kb/files/')) return html || ''
  return html.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)[^"']*(["'])/gi,
    (_m, pre, path, post) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return `${pre}${clean}${post}`
    }
  )
}

export function stripKbMediaTokensInMarkdown(md) {
  if (!md || !md.includes('/api/v1/kb/files/')) return md || ''
  return md.replace(
    /(\]\()([^)\s]*\/api\/v1\/kb\/files\/\d+\/content)[^)]*(\))/gi,
    (_m, pre, path, post) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return `${pre}${clean}${post}`
    }
  )
}

export function stripKbMediaTokensAll(text) {
  return stripKbMediaTokensInMarkdown(stripKbMediaTokens(text || ''))
}

export function mdImageSyntax(contentPath, alt = 'image') {
  let path = (contentPath || '').trim()
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = `/${path}`
  const safeAlt = String(alt || 'image').replace(/[[\]]/g, '')
  return `![${safeAlt}](${path})`
}

export const api = {
  login(email, password) {
    return request('/api/auth/login', {
      method: 'POST',
      auth: false,
      body: { email, password }
    })
  },
  me() {
    return request('/api/auth/me')
  },
  listNotes({ page = 0, size = 20, keyword, onlyDeleted, categoryId, tagId, uncategorized } = {}) {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
    if (keyword) q.set('keyword', keyword)
    if (onlyDeleted) q.set('onlyDeleted', 'true')
    if (categoryId) q.set('categoryId', categoryId)
    if (tagId) q.set('tagId', tagId)
    if (uncategorized) q.set('uncategorized', 'true')
    return request(`/api/v1/kb/notes?${q}`)
  },
  getNote(id) {
    return request(`/api/v1/kb/notes/${id}`, { timeout: 120000 })
  },
  createNote(body) {
    return request('/api/v1/kb/notes', { method: 'POST', body })
  },
  updateNote(id, body) {
    return request(`/api/v1/kb/notes/${id}`, { method: 'PUT', body })
  },
  deleteNote(id) {
    return request(`/api/v1/kb/notes/${id}`, { method: 'DELETE' })
  },
  restoreNote(id) {
    return request(`/api/v1/kb/notes/${id}/restore`, { method: 'POST' })
  },
  permanentDeleteNote(id) {
    return request(`/api/v1/kb/notes/${id}/permanent`, { method: 'DELETE' })
  },
  trashCount() {
    return request('/api/v1/kb/notes/trash/count')
  },
  emptyTrash() {
    return request('/api/v1/kb/notes/trash', { method: 'DELETE' })
  },
  listCategories() {
    return request('/api/v1/kb/categories')
  },
  listTags() {
    return request('/api/v1/kb/tags')
  },
  listFiles(noteId) {
    return request(`/api/v1/kb/files?noteId=${encodeURIComponent(noteId)}`)
  },
  async uploadFile(file, noteId) {
    const fd = new FormData()
    fd.append('file', file)
    if (noteId) fd.append('noteId', noteId)
    return request('/api/v1/kb/files', { method: 'POST', formData: fd, timeout: 120000 })
  },
  deleteFile(id) {
    return request(`/api/v1/kb/files/${id}`, { method: 'DELETE' })
  },
  bindFile(id, noteId) {
    return request(`/api/v1/kb/files/${id}/bind`, { method: 'POST', body: { noteId } })
  },
  getShareStatus(noteId) {
    return request(`/api/v1/kb/notes/${noteId}/share`)
  },
  enableShare(noteId) {
    return request(`/api/v1/kb/notes/${noteId}/share`, { method: 'POST' })
  },
  disableShare(noteId) {
    return request(`/api/v1/kb/notes/${noteId}/share`, { method: 'DELETE' })
  },
  rotateShare(noteId) {
    return request(`/api/v1/kb/notes/${noteId}/share/rotate`, { method: 'POST' })
  },
  /** 公开分享（无需登录） */
  async fetchPublicNote(token) {
    const base = getBaseUrl()
    const res = await fetch(`${base}/api/v1/kb/public/s/${encodeURIComponent(token)}`)
    let data = null
    try {
      data = await res.json()
    } catch {
      data = null
    }
    if (!res.ok || !data?.success) {
      throw new Error((data && data.message) || '分享不存在或已关闭')
    }
    return data.data
  }
}

export function formatTime(t) {
  if (!t) return ''
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}
