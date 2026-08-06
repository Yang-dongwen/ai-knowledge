const { api } = require('../../utils/request')
const { setSession, isLoggedIn, consumeLoginReturnUrl } = require('../../utils/auth')
const { getWxLoginCode, isWxMockLogin } = require('../../utils/config')
const { safeReturnUrl } = require('../../utils/sanitizeHtml')

Page({
  data: {
    email: '',
    password: '',
    loading: false,
    wxLoading: false,
    needBind: false,
    mode: 'login', // login | bind
    mockHint: false
  },

  onShow() {
    if (isLoggedIn()) {
      wx.switchTab({ url: '/pages/notes/notes' })
      return
    }
    this.setData({ mockHint: isWxMockLogin() })
  },

  onEmail(e) {
    this.setData({ email: e.detail.value })
  },

  onPassword(e) {
    this.setData({ password: e.detail.value })
  },

  finishLogin(data) {
    setSession(data.token, data.user)
    wx.showToast({ title: '登录成功', icon: 'success' })
    setTimeout(() => {
      const ret = safeReturnUrl(consumeLoginReturnUrl(), '/pages/notes/notes')
      // tabBar 页必须 switchTab
      if (
        ret.indexOf('/pages/notes/notes') === 0
        || ret.indexOf('/pages/edit/edit') === 0
        || ret.indexOf('/pages/me/me') === 0
      ) {
        const tab = ret.split('?')[0]
        wx.switchTab({ url: tab })
      } else {
        wx.redirectTo({
          url: ret,
          fail() {
            wx.switchTab({ url: '/pages/notes/notes' })
          }
        })
      }
    }, 300)
  },

  async onLogin() {
    const email = (this.data.email || '').trim()
    const password = this.data.password || ''
    if (!email || !password) {
      wx.showToast({ title: '请输入邮箱和密码', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      if (this.data.needBind || this.data.mode === 'bind') {
        const code = await getWxLoginCode()
        const data = await api.wxMiniBind(code, email, password)
        if (data.needBind) {
          wx.showToast({ title: '绑定失败，请重试', icon: 'none' })
          return
        }
        this.finishLogin(data)
      } else {
        const data = await api.login(email, password)
        this.finishLogin(data)
      }
    } catch (e) {
      wx.showToast({ title: e.message || '登录失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async onWxLogin() {
    this.setData({ wxLoading: true })
    try {
      const code = await getWxLoginCode()
      const data = await api.wxMiniLogin(code)
      if (data && data.needBind) {
        this.setData({
          needBind: true,
          mode: 'bind'
        })
        wx.showToast({ title: '请绑定已有邮箱账号', icon: 'none' })
        return
      }
      if (data && data.token) {
        this.finishLogin(data)
        return
      }
      wx.showToast({ title: '登录失败', icon: 'none' })
    } catch (e) {
      wx.showToast({ title: e.message || '微信登录失败', icon: 'none' })
    } finally {
      this.setData({ wxLoading: false })
    }
  },

  switchEmailMode() {
    this.setData({ needBind: false, mode: 'login' })
  }
})
