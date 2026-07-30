const { getBaseUrl } = require('./config')
const { getToken, clearSession } = require('./auth')

function request(options) {
  const { url, method = 'GET', data, auth = true, timeout = 60000 } = options
  const header = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = getToken()
    if (token) header.Authorization = `Bearer ${token}`
  }

  return new Promise((resolve, reject) => {
    wx.request({
      url: getBaseUrl() + url,
      method,
      data,
      header,
      timeout,
      success(res) {
        const status = res.statusCode
        const body = res.data || {}
        if (status === 401) {
          clearSession()
          reject(new Error(body.message || '未登录或登录已过期'))
          const pages = getCurrentPages()
          const cur = pages[pages.length - 1]
          if (!cur || cur.route !== 'pages/login/login') {
            wx.reLaunch({ url: '/pages/login/login' })
          }
          return
        }
        if (status >= 200 && status < 300) {
          if (body && body.success === true) {
            resolve(body.data)
            return
          }
          reject(new Error((body && body.message) || '请求失败'))
          return
        }
        reject(new Error((body && body.message) || `网络错误 ${status}`))
      },
      fail(err) {
        const msg = (err && err.errMsg) || '网络连接失败'
        reject(new Error(msg.includes('timeout') ? '请求超时' : msg))
      }
    })
  })
}

function uploadFile(filePath, noteId) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: getBaseUrl() + '/api/v1/kb/files',
      filePath,
      name: 'file',
      formData: noteId ? { noteId: String(noteId) } : {},
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success(res) {
        try {
          const body = JSON.parse(res.data || '{}')
          if (body.success) resolve(body.data)
          else reject(new Error(body.message || '上传失败'))
        } catch (e) {
          reject(new Error('上传响应解析失败'))
        }
      },
      fail(err) {
        reject(new Error((err && err.errMsg) || '上传失败'))
      }
    })
  })
}

const api = {
  login(email, password) {
    return request({
      url: '/api/auth/login',
      method: 'POST',
      auth: false,
      data: { email, password }
    })
  },
  me() {
    return request({ url: '/api/auth/me' })
  },
  listNotes(params = {}) {
    const data = {
      page: params.page != null ? params.page : 0,
      size: params.size != null ? params.size : 20
    }
    if (params.keyword) data.keyword = params.keyword
    if (params.onlyDeleted) data.onlyDeleted = true
    if (params.categoryId) data.categoryId = params.categoryId
    return request({ url: '/api/v1/kb/notes', method: 'GET', data })
  },
  getNote(id) {
    return request({ url: `/api/v1/kb/notes/${id}`, timeout: 120000 })
  },
  createNote(body) {
    return request({ url: '/api/v1/kb/notes', method: 'POST', data: body })
  },
  updateNote(id, body) {
    return request({ url: `/api/v1/kb/notes/${id}`, method: 'PUT', data: body })
  },
  deleteNote(id) {
    return request({ url: `/api/v1/kb/notes/${id}`, method: 'DELETE' })
  },
  restoreNote(id) {
    return request({ url: `/api/v1/kb/notes/${id}/restore`, method: 'POST' })
  },
  permanentDeleteNote(id) {
    return request({ url: `/api/v1/kb/notes/${id}/permanent`, method: 'DELETE' })
  },
  trashCount() {
    return request({ url: '/api/v1/kb/notes/trash/count' })
  },
  emptyTrash() {
    return request({ url: '/api/v1/kb/notes/trash', method: 'DELETE' })
  },
  listCategories() {
    return request({ url: '/api/v1/kb/categories' })
  },
  listTags() {
    return request({ url: '/api/v1/kb/tags' })
  },
  listFiles(noteId) {
    return request({ url: '/api/v1/kb/files', data: { noteId } })
  },
  deleteFile(id) {
    return request({ url: `/api/v1/kb/files/${id}`, method: 'DELETE' })
  },
  bindFile(id, noteId) {
    return request({ url: `/api/v1/kb/files/${id}/bind`, method: 'POST', data: { noteId } })
  },
  getShareStatus(noteId) {
    return request({ url: `/api/v1/kb/notes/${noteId}/share` })
  },
  enableShare(noteId) {
    return request({ url: `/api/v1/kb/notes/${noteId}/share`, method: 'POST' })
  },
  disableShare(noteId) {
    return request({ url: `/api/v1/kb/notes/${noteId}/share`, method: 'DELETE' })
  },
  rotateShare(noteId) {
    return request({ url: `/api/v1/kb/notes/${noteId}/share/rotate`, method: 'POST' })
  },
  uploadFile
}

function mediaUrl(contentPath) {
  const { getBaseUrl } = require('./config')
  const { getToken } = require('./auth')
  if (!contentPath) return ''
  let path = contentPath.trim()
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = `/${path}`
  const token = getToken()
  const base = getBaseUrl()
  if (!token) return base + path
  return `${base}${path}${path.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(token)}`
}

function stripMediaTokens(text) {
  if (!text || !text.includes('/api/v1/kb/files/')) return text || ''
  let s = text.replace(
    /((?:src|href)=["'])([^"']*\/api\/v1\/kb\/files\/\d+\/content)[^"']*(["'])/gi,
    (_m, pre, path, post) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return pre + clean + post
    }
  )
  s = s.replace(
    /(\]\()([^)\s]*\/api\/v1\/kb\/files\/\d+\/content)[^)]*(\))/gi,
    (_m, pre, path, post) => {
      const m = path.match(/^(.*?\/api\/v1\/kb\/files\/\d+\/content)/i)
      const clean = m ? m[1] : path.split('?')[0]
      return pre + clean + post
    }
  )
  return s
}

function mdImageSyntax(contentPath, alt) {
  let path = (contentPath || '').trim()
  path = path.replace(/[?&](access_token|token)=[^&]*/gi, '').replace(/[?&]$/, '')
  if (!path.startsWith('/')) path = '/' + path
  const safeAlt = String(alt || 'image').replace(/[[\]]/g, '')
  return '![' + safeAlt + '](' + path + ')'
}

module.exports = {
  request,
  api,
  mediaUrl,
  stripMediaTokens,
  mdImageSyntax
}
