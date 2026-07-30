const { isLoggedIn } = require('./utils/auth')

App({
  globalData: {
    version: '0.3.0'
  },

  onLaunch() {
    // 冷启动：未登录则进入登录页（由各页 onShow 再校验）
    if (!isLoggedIn()) {
      // 不在这里 reLaunch，避免干扰开发者工具首次打开路径
    }
  }
})
