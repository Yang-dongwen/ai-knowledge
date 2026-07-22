<template>
  <a-tooltip :title="tooltip">
    <button
      type="button"
      class="theme-toggle"
      :class="{ floating }"
      aria-label="切换主题"
      @click="themeStore.cycle()"
    >
      <DesktopOutlined v-if="themeStore.mode === 'system'" />
      <SkinOutlined v-else-if="themeStore.isDark" />
      <BulbOutlined v-else />
    </button>
  </a-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BulbOutlined, DesktopOutlined, SkinOutlined } from '@ant-design/icons-vue'
import { useThemeStore } from '@/stores/theme.store'

withDefaults(
  defineProps<{
    floating?: boolean
  }>(),
  { floating: false }
)

const themeStore = useThemeStore()

const tooltip = computed(() => {
  if (themeStore.mode === 'system') return '主题：跟随系统（点击切换）'
  if (themeStore.mode === 'dark') return '主题：暗色（点击切换）'
  return '主题：亮色（点击切换）'
})
</script>

<style scoped lang="scss">
.theme-toggle {
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.16s ease;
  font-size: 16px;

  &:hover {
    background: var(--nav-hover-bg);
    border-color: var(--nav-hover-border);
    color: var(--text-primary);
    box-shadow: var(--shadow-sm);
  }

  &.floating {
    position: fixed;
    top: 16px;
    right: 16px;
    z-index: 50;
    background: var(--surface-1);
    border-color: var(--border-color);
    box-shadow: var(--card-shadow);
  }
}
</style>
