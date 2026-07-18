<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { theme as antdThemeAlgo } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

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
    fontFamily:
      "'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"
  }
}
</script>
