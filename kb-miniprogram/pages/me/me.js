const { api } = require('../../utils/request')
const { isLoggedIn, getUser, clearSession, setSession } = require('../../utils/auth')
const {
  getBaseUrl,
  setBaseUrl,
  DEFAULT_BASE_URL,
  getShareWebOrigin,
  setShareWebOrigin,
  isDebugUi,
  isWxMockLogin,
  setWxMockLogin,
  getWxLoginCode,
  getEnvVersion
} = require('../../utils/config')

Page({
  data: {
    user: {},
    avatarLetter: 'U',
    baseUrl: '',
    shareWebOrigin: '',
    version: '0.7.0',
    showDebug: true,
    wxBound: false,
    wxMock: true,
    wxBusy: false,
    envVersion: 'develop'
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
      version: (getApp().globalData && getApp().globalData.version) || '0.7.0',
      showDebug: isDebugUi(),
      wxBound: !!user.wxMiniBound,
      wxMock: isWxMockLogin(),
      envVersion: getEnvVersion()
    })
    this.refreshMe()
  },

  async refreshMe() {
    try {
      const me = await api.me()
      setSession(require('../../utils/auth').getToken(), me)
      const letter = (me.nickname || me.email || 'U').charAt(0).toUpperCase()
      this.setData({
        user: me,
        avatarLetter: letter,
        wxBound: !!me.wxMiniBound
      })
    } catch (e) {
      // 静默
    }
  },

  goFolders() {
    wx.navigateTo({ url: '/pages/folders/folders' })
  },

  goTags() {
    wx.navigateTo({ url: '/pages/tags/tags' })
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

  onMockChange(e) {
    const on = !!(e.detail && e.detail.value)
    setWxMockLogin(on)
    this.setData({ wxMock: on })
    wx.showToast({ title: on ? '模拟微信已开' : '将使用真实 wx.login', icon: 'none' })
  },

  async onBindWx() {
    if (this.data.wxBusy) return
    this.setData({ wxBusy: true })
    try {
      const code = await getWxLoginCode()
      const me = await api.wxMiniBindCurrent(code)
      setSession(require('../../utils/auth').getToken(), me)
      this.setData({ user: me, wxBound: !!me.wxMiniBound })
      wx.showToast({ title: '已绑定微信', icon: 'success' })
    } catch (e) {
      wx.showToast({ title: e.message || '绑定失败', icon: 'none' })
    } finally {
      this.setData({ wxBusy: false })
    }
  },

  onUnbindWx() {
    wx.showModal({
      title: '解绑微信',
      content: '解绑后需重新绑定才能微信一键登录',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ wxBusy: true })
        try {
          const me = await api.wxMiniUnbind()
          setSession(require('../../utils/auth').getToken(), me)
          this.setData({ user: me, wxBound: !!me.wxMiniBound })
          wx.showToast({ title: '已解绑', icon: 'success' })
        } catch (e) {
          wx.showToast({ title: e.message || '解绑失败', icon: 'none' })
        } finally {
          this.setData({ wxBusy: false })
        }
      }
    })
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
