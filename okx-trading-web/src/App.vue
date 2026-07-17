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
    colorPrimary: '#6366F1',
    borderRadius: 12,
    fontFamily:
      "'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"
  }
}
</script>
