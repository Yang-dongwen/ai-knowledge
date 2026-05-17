<template>
  <a-layout class="basic-layout">
    <AppSidebar />
    <a-layout>
      <AppHeader />
      <a-layout-content class="layout-content">
        <router-view />
        <div class="page-footer">© 2024 OKX 自动交易助手 · 仅供学习交流，不构成投资建议</div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<style lang="scss" scoped>
.basic-layout {
  min-height: 100vh;
}

.layout-content {
  padding: var(--content-padding);
  background: var(--page-bg);
  min-height: calc(100vh - 56px);
  overflow-y: auto;
}
</style>
