const { api } = require('../../utils/request')
const { isLoggedIn, getUser, clearSession, setSession } = require('../../utils/auth')
const {
  getBaseUrl,
  setBaseUrl,
  DEFAULT_BASE_URL,
  getShareWebOrigin,
  setShareWebOrigin
} = require('../../utils/config')

Page({
  data: {
    user: {},
    avatarLetter: 'U',
    baseUrl: '',
    shareWebOrigin: '',
    version: '0.4.0'
  },

  onShow() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    const user = getUser() || {}
    const letter = (user.nickname || user.email || 'U').charAt(0).toUpperCase()
    this.setData({
      user,
      avatarLetter: letter,
      baseUrl: getBaseUrl(),
      shareWebOrigin: getShareWebOrigin(),
      version: (getApp().globalData && getApp().globalData.version) || '0.3.0'
    })
    this.refreshMe()
  },

  async refreshMe() {
    try {
      const me = await api.me()
      setSession(require('../../utils/auth').getToken(), me)
      const letter = (me.nickname || me.email || 'U').charAt(0).toUpperCase()
      this.setData({ user: me, avatarLetter: letter })
    } catch (e) {
      // 静默
    }
  },

  onBaseUrl(e) {
    this.setData({ baseUrl: e.detail.value })
  },

  saveBaseUrl() {
    const v = setBaseUrl(this.data.baseUrl)
    this.setData({ baseUrl: v })
    wx.showToast({ title: '已保存', icon: 'success' })
  },

  resetBaseUrl() {
    setBaseUrl('')
    this.setData({ baseUrl: DEFAULT_BASE_URL })
    wx.showToast({ title: '已恢复默认', icon: 'none' })
  },

  onShareOrigin(e) {
    this.setData({ shareWebOrigin: e.detail.value })
  },

  saveShareOrigin() {
    const v = setShareWebOrigin(this.data.shareWebOrigin)
    this.setData({ shareWebOrigin: v })
    wx.showToast({ title: '已保存', icon: 'success' })
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定退出？',
      success(res) {
        if (!res.confirm) return
        clearSession()
        wx.reLaunch({ url: '/pages/login/login' })
      }
    })
  }
})
