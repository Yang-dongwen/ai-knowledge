<template>
  <div class="app-shell">
    <router-view />
    <nav v-if="showTab" class="tabbar">
      <router-link class="tab" to="/notes">笔记</router-link>
      <router-link class="tab" to="/edit">快记</router-link>
      <router-link class="tab" to="/me">我的</router-link>
    </nav>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const showTab = computed(() => !route.meta.noTab && route.path !== '/login')
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: calc(var(--tab-h) + var(--safe-b));
  padding-bottom: var(--safe-b);
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  background: #fff;
  border-top: 1px solid var(--border);
  z-index: 50;
}
.tab {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
}
.tab.router-link-active {
  color: var(--primary);
}
</style>
