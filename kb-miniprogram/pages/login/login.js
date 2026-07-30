const { api } = require('../../utils/request')
const { setSession, isLoggedIn } = require('../../utils/auth')

Page({
  data: {
    email: '',
    password: '',
    loading: false
  },

  onShow() {
    if (isLoggedIn()) {
      wx.switchTab({ url: '/pages/notes/notes' })
    }
  },

  onEmail(e) {
    this.setData({ email: e.detail.value })
  },

  onPassword(e) {
    this.setData({ password: e.detail.value })
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
      const data = await api.login(email, password)
      setSession(data.token, data.user)
      wx.showToast({ title: '登录成功', icon: 'success' })
      setTimeout(() => {
        wx.switchTab({ url: '/pages/notes/notes' })
      }, 300)
    } catch (e) {
      wx.showToast({ title: e.message || '登录失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
