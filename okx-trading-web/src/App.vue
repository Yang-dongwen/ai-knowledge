<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <!-- a-app：为 message / notification / modal 提供正确挂载与样式上下文 -->
    <a-app class="app-root">
      <router-view />
    </a-app>
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { theme as antdThemeAlgo, message, notification } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useThemeStore } from '@/stores/theme.store'

const themeStore = useThemeStore()

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

const fontFamily =
  "'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"

const antdTheme = computed(() => {
  const dark = themeStore.isDark
  return {
    algorithm: dark ? antdThemeAlgo.darkAlgorithm : antdThemeAlgo.defaultAlgorithm,
    token: {
      colorPrimary: dark ? '#e5e7eb' : '#1f2937',
      borderRadius: 10,
      controlHeight: 36,
      colorBgBase: dark ? '#0b1220' : '#ffffff',
      colorBgContainer: dark ? '#111827' : '#ffffff',
      colorBgElevated: dark ? '#1e293b' : '#ffffff',
      colorBgLayout: dark ? '#0b1220' : '#f5f6f8',
      colorBgSpotlight: dark ? '#1e293b' : '#ffffff',
      colorBorder: dark ? '#334155' : '#e5e7eb',
      colorBorderSecondary: dark ? '#1e293b' : '#f0f0f0',
      colorText: dark ? '#e5e7eb' : '#0f172a',
      colorTextSecondary: dark ? '#94a3b8' : '#64748b',
      colorTextTertiary: dark ? '#64748b' : '#94a3b8',
      colorTextQuaternary: dark ? '#475569' : '#9ca3af',
      colorLink: dark ? '#e5e7eb' : '#1f2937',
      colorLinkHover: dark ? '#f9fafb' : '#111827',
      zIndexPopupBase: 2000,
      fontFamily
    }
  }
})
</script>

<style scoped>
.app-root {
  min-height: 100vh;
}
</style>
