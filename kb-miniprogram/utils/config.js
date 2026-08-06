/**
 * 小程序运行配置。
 *
 * 默认连线上：https://www.dwcode.cloud
 * 本地调试可在「我的」里临时改成 http://局域网IP:8080，并关闭合法域名校验。
 * 生产包不展示调试配置区。
 */
const DEFAULT_BASE_URL = 'https://www.dwcode.cloud'

/** 生产包请改为 false；或依赖 envVersion=release 自动隐藏 */
const FORCE_SHOW_DEBUG = false

function getEnvVersion() {
  try {
    const info = wx.getAccountInfoSync && wx.getAccountInfoSync()
    return (info && info.miniProgram && info.miniProgram.envVersion) || 'develop'
  } catch (e) {
    return 'develop'
  }
}

/** 是否展示服务端地址 / 模拟微信等调试项 */
function isDebugUi() {
  if (FORCE_SHOW_DEBUG) return true
  const v = getEnvVersion()
  return v === 'develop' || v === 'trial'
}

function getBaseUrl() {
  try {
    const stored = wx.getStorageSync('kb_base_url')
    if (stored && typeof stored === 'string' && stored.trim()) {
      return stored.trim().replace(/\/$/, '')
    }
  } catch (e) {
    // ignore
  }
  return DEFAULT_BASE_URL
}

function setBaseUrl(url) {
  const v = (url || '').trim().replace(/\/$/, '')
  if (!v) {
    wx.removeStorageSync('kb_base_url')
    return DEFAULT_BASE_URL
  }
  wx.setStorageSync('kb_base_url', v)
  return v
}

/**
 * 分享阅读页前端域名（H5 或 PC 工具台）
 */
function getShareWebOrigin() {
  try {
    const stored = wx.getStorageSync('kb_share_web_origin')
    if (stored && typeof stored === 'string' && stored.trim()) {
      return stored.trim().replace(/\/$/, '')
    }
  } catch (e) {
    // ignore
  }
  // 默认 PC/H5 分享阅读域名，避免复制出相对路径无法打开
  return 'https://www.dwcode.cloud'
}

function setShareWebOrigin(url) {
  const v = (url || '').trim().replace(/\/$/, '')
  if (!v) {
    wx.removeStorageSync('kb_share_web_origin')
    return ''
  }
  wx.setStorageSync('kb_share_web_origin', v)
  return v
}

/**
 * 后端 mock 模式用稳定 openid；正式 jscode2session 用 wx.login code。
 * storage: kb_wx_use_mock = true|false，默认 develop 为 true
 */
function isWxMockLogin() {
  try {
    const v = wx.getStorageSync('kb_wx_use_mock')
    if (v === true || v === '1' || v === 1) return true
    if (v === false || v === '0' || v === 0) return false
  } catch (e) {
    /* ignore */
  }
  return getEnvVersion() === 'develop'
}

function setWxMockLogin(on) {
  wx.setStorageSync('kb_wx_use_mock', !!on)
}

function getStableMockOpenid() {
  let id = ''
  try {
    id = wx.getStorageSync('kb_mock_openid') || ''
  } catch (e) {
    id = ''
  }
  if (!id) {
    id = 'dev' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
    try {
      wx.setStorageSync('kb_mock_openid', id)
    } catch (e) {
      /* ignore */
    }
  }
  return 'mock:' + id
}

/**
 * 获取发给后端的微信 code
 * @returns {Promise<string>}
 */
function getWxLoginCode() {
  if (isWxMockLogin()) {
    return Promise.resolve(getStableMockOpenid())
  }
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (res && res.code) resolve(res.code)
        else reject(new Error('wx.login 未返回 code'))
      },
      fail(err) {
        reject(new Error((err && err.errMsg) || 'wx.login 失败'))
      }
    })
  })
}

module.exports = {
  DEFAULT_BASE_URL,
  getEnvVersion,
  isDebugUi,
  getBaseUrl,
  setBaseUrl,
  getShareWebOrigin,
  setShareWebOrigin,
  isWxMockLogin,
  setWxMockLogin,
  getWxLoginCode
}
