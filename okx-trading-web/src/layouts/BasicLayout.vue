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
      <div v-if="!isImmersive" class="page-footer">© {{ new Date().getFullYear() }} AI 工具台 · 仅供学习交流</div>
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
/** AI 工具 / 知识库等业务页：启用极简控件样式 */
const isToolsPage = computed(() => {
  const group = route.meta?.group as string | undefined
  return group === 'tools' || group === 'kb' || group === 'home' || route.path === '/home'
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
  /* 不用 isolation:isolate，避免与 body 级浮层（message 等）叠层异常 */

  &.is-immersive {
    height: 100vh;
    height: 100dvh;
    max-height: 100vh;
    max-height: 100dvh;
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

/* 全站氛围层：柔光 + 微网格，跟随主题 token */
.ambient {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
  background: var(--ambient-bg);
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
  background: radial-gradient(circle, var(--orb-a), transparent 70%);
}

.orb-b {
  width: 380px;
  height: 380px;
  right: -90px;
  top: 30%;
  background: radial-gradient(circle, var(--orb-b), transparent 70%);
  animation-delay: -6s;
  animation-duration: 24s;
}

.orb-c {
  width: 260px;
  height: 260px;
  left: 42%;
  bottom: -50px;
  background: radial-gradient(circle, var(--orb-c), transparent 70%);
  animation-delay: -11s;
  animation-duration: 22s;
}

.grid-fade {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background-image:
    linear-gradient(var(--grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid-line) 1px, transparent 1px);
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
  min-height: calc(100dvh - 64px);
  overflow-y: auto;
  overflow-x: clip;
}

.layout-content-body {
  min-height: 0;
  position: relative;
  z-index: 1;
  max-width: 100%;
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

@media (max-width: 768px) {
  .layout-content {
    padding: 12px;
    min-height: calc(100vh - 56px);
    min-height: calc(100dvh - 56px);
  }

  .basic-layout.is-immersive .layout-content {
    padding: 8px;
    /* 底部安全区：避免被 iOS 手势条遮挡 */
    padding-bottom: max(8px, env(safe-area-inset-bottom, 0px));
  }

  .page-footer {
    margin-top: 16px;
    padding: 10px 0 4px;
  }
}
</style>
