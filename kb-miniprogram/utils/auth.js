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

module.exports = {
  getToken,
  setSession,
  clearSession,
  getUser,
  isLoggedIn
}
