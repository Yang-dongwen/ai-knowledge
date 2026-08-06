const TOKEN_KEY = 'kb_token'
const USER_KEY = 'kb_user'

function getToken() {
  try {
    return wx.getStorageSync(TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

function setSession(token, user) {
  wx.setStorageSync(TOKEN_KEY, token || '')
  if (user) {
    wx.setStorageSync(USER_KEY, user)
  }
}

function clearSession() {
  try {
    wx.removeStorageSync(TOKEN_KEY)
    wx.removeStorageSync(USER_KEY)
  } catch (e) {
    // ignore
  }
}

function getUser() {
  try {
    return wx.getStorageSync(USER_KEY) || null
  } catch (e) {
    return null
  }
}

function isLoggedIn() {
  return !!getToken()
}

const RETURN_URL_KEY = 'kb_login_return_url'

function setLoginReturnUrl(url) {
  try {
    if (url) wx.setStorageSync(RETURN_URL_KEY, url)
  } catch (e) {
    /* ignore */
  }
}

function consumeLoginReturnUrl() {
  try {
    const u = wx.getStorageSync(RETURN_URL_KEY) || ''
    wx.removeStorageSync(RETURN_URL_KEY)
    return u
  } catch (e) {
    return ''
  }
}

/**
 * 未登录时跳转登录页，并记住回跳路径（仅 /pages/*）
 */
function requireLoginOrRedirect(page) {
  if (isLoggedIn()) return true
  try {
    const pages = getCurrentPages()
    const cur = pages[pages.length - 1]
    if (cur && cur.route) {
      let path = '/' + cur.route
      const opts = cur.options || {}
      const qs = Object.keys(opts)
        .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(opts[k])}`)
        .join('&')
      if (qs) path += '?' + qs
      setLoginReturnUrl(path)
    }
  } catch (e) {
    /* ignore */
  }
  wx.reLaunch({ url: '/pages/login/login' })
  return false
}

module.exports = {
  getToken,
  setSession,
  clearSession,
  getUser,
  isLoggedIn,
  setLoginReturnUrl,
  consumeLoginReturnUrl,
  requireLoginOrRedirect
}
