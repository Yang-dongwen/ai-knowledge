<template>
  <a-layout class="basic-layout">
    <AppHeader />
    <a-layout-content class="layout-content">
      <router-view />
      <div class="page-footer">© 2024 AI 工具台 · 仅供学习交流</div>
    </a-layout-content>
  </a-layout>
</template>

<script setup lang="ts">
import AppHeader from './AppHeader.vue'
</script>

<style lang="scss" scoped>
.basic-layout {
  min-height: 100vh;
  background: var(--page-bg);
}

.layout-content {
  padding: var(--content-padding);
  background: var(--page-bg);
  min-height: calc(100vh - 64px);
  overflow-y: auto;
}
</style>
