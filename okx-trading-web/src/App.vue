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
    // 中性墨色：主按钮/选中态更舒适，避免大块蓝底
    colorPrimary: '#1f2937',
    borderRadius: 10,
    controlHeight: 36,
    fontFamily:
      "'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"
  }
}
</script>
