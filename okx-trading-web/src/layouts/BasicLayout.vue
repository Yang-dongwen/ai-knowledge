<template>
  <a-layout
    class="basic-layout"
    :class="{ 'is-immersive': isImmersive, 'tools-ui': isToolsPage }"
  >
    <div class="ambient" aria-hidden="true">
      <span class="orb orb-a" />
      <span class="orb orb-b" />
      <span class="orb orb-c" />
      <span class="grid-fade" />
    </div>
    <AppHeader />
    <a-layout-content class="layout-content">
      <div class="layout-content-body">
        <router-view />
      </div>
      <div v-if="!isImmersive" class="page-footer">© 2024 AI 工具台 · 仅供学习交流</div>
    </a-layout-content>
  </a-layout>
</template>

<script setup lang="ts">
import { computed, watch, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './AppHeader.vue'

const route = useRoute()
/** 沉浸式页面（如 AI 对话）：无页脚、无外层滚动，高度锁死在视口内 */
const isImmersive = computed(() => route.meta?.immersive === true)
/** AI 工具相关页：启用极简控件样式 */
const isToolsPage = computed(() => {
  const group = route.meta?.group as string | undefined
  return group === 'tools' || group === 'home' || route.path === '/home'
})

// 弹窗挂到 body，同步 class 以便按钮/checkbox 样式一致
watch(
  isToolsPage,
  (v) => {
    document.body.classList.toggle('tools-ui', v)
  },
  { immediate: true }
)

onUnmounted(() => {
  document.body.classList.remove('tools-ui')
})
</script>

<style lang="scss" scoped>
.basic-layout {
  position: relative;
  min-height: 100vh;
  background: transparent;
  isolation: isolate;

  &.is-immersive {
    height: 100vh;
    max-height: 100vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;

    :deep(.ant-layout-header) {
      flex-shrink: 0;
    }

    .layout-content {
      flex: 1 1 auto;
      min-height: 0 !important;
      height: auto !important;
      max-height: none;
      overflow: hidden !important;
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
    }

    .layout-content-body {
      flex: 1 1 auto;
      min-height: 0;
      height: 100%;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
  }
}

/* 全站氛围层：柔光 + 微网格，营造科技感 */
.ambient {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
  /* 更克制的环境光：低饱和灰青，避免整页偏紫 */
  background:
    radial-gradient(1000px 520px at 12% -8%, rgba(148, 163, 184, 0.18), transparent 55%),
    radial-gradient(800px 460px at 90% 4%, rgba(203, 213, 225, 0.2), transparent 52%),
    radial-gradient(640px 380px at 48% 100%, rgba(226, 232, 240, 0.35), transparent 55%),
    linear-gradient(180deg, #f8f9fb 0%, #f3f4f6 50%, #eef0f4 100%);
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(48px);
  opacity: 0.35;
  animation: orb-drift 20s ease-in-out infinite alternate;
}

.orb-a {
  width: 320px;
  height: 320px;
  left: -70px;
  top: 14%;
  background: radial-gradient(circle, rgba(148, 163, 184, 0.45), transparent 70%);
}

.orb-b {
  width: 380px;
  height: 380px;
  right: -90px;
  top: 30%;
  background: radial-gradient(circle, rgba(203, 213, 225, 0.5), transparent 70%);
  animation-delay: -6s;
  animation-duration: 24s;
}

.orb-c {
  width: 260px;
  height: 260px;
  left: 42%;
  bottom: -50px;
  background: radial-gradient(circle, rgba(226, 232, 240, 0.55), transparent 70%);
  animation-delay: -11s;
  animation-duration: 22s;
}

.grid-fade {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background-image:
    linear-gradient(rgba(15, 23, 42, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(15, 23, 42, 0.035) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 30%, #000 20%, transparent 75%);
}

@keyframes orb-drift {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: translate3d(24px, -18px, 0) scale(1.06);
  }
}

.layout-content {
  position: relative;
  z-index: 1;
  padding: var(--content-padding);
  background: transparent;
  min-height: calc(100vh - 64px);
  overflow-y: auto;
}

.layout-content-body {
  min-height: 0;
  position: relative;
  z-index: 1;
}

.page-footer {
  position: relative;
  z-index: 1;
  margin-top: 28px;
  padding: 14px 0 6px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.03em;
}
</style>
