/**
 * 小程序运行配置。
 *
 * 本地调试：在微信开发者工具中关闭「校验合法域名」，
 * baseUrl 指向本机或局域网后端，例如：
 *   http://127.0.0.1:8080
 *   http://192.168.1.10:8080
 *
 * 正式环境：改为 https 域名，并在微信公众平台配置 request 合法域名。
 */
const DEFAULT_BASE_URL = 'http://127.0.0.1:8080'

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
 * 分享阅读页前端域名（H5 或 PC 工具台），如 http://192.168.1.8:5174 或 https://app.example.com
 * 生成完整链接：origin + /s/{token}
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
  return ''
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

module.exports = {
  DEFAULT_BASE_URL,
  getBaseUrl,
  setBaseUrl,
  getShareWebOrigin,
  setShareWebOrigin
}
