<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <!-- a-app：为 message / notification / modal 提供正确挂载与样式上下文 -->
    <a-app class="app-root">
      <router-view />
    </a-app>
  </a-config-provider>
</template>

<script setup lang="ts">
import { theme as antdThemeAlgo, message, notification } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

// 静态 API 也保证挂到 body 且层级足够
message.config({
  top: '24px',
  duration: 3,
  maxCount: 5,
  getContainer: () => document.body
})
notification.config({
  placement: 'topRight',
  duration: 4,
  getContainer: () => document.body
})

const antdTheme = {
  algorithm: antdThemeAlgo.defaultAlgorithm,
  token: {
    colorPrimary: '#1f2937',
    borderRadius: 10,
    controlHeight: 36,
    colorBgBase: '#ffffff',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorBgLayout: '#f5f6f8',
    colorBgSpotlight: '#ffffff',
    colorBorder: '#e5e7eb',
    colorBorderSecondary: '#f0f0f0',
    colorText: '#0f172a',
    colorTextSecondary: '#64748b',
    colorTextTertiary: '#94a3b8',
    colorTextQuaternary: '#9ca3af',
    colorLink: '#1f2937',
    colorLinkHover: '#111827',
    zIndexPopupBase: 2000,
    fontFamily:
      "'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"
  }
}
</script>

<style scoped>
.app-root {
  min-height: 100vh;
}
</style>
