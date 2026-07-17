<template>
  <a-layout class="basic-layout" :class="{ 'is-immersive': isImmersive }">
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
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './AppHeader.vue'

const route = useRoute()
/** 沉浸式页面（如 AI 对话）：无页脚、无外层滚动，高度锁死在视口内 */
const isImmersive = computed(() => route.meta?.immersive === true)
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
  background:
    radial-gradient(1200px 600px at 8% -10%, rgba(99, 102, 241, 0.16), transparent 55%),
    radial-gradient(900px 500px at 92% 0%, rgba(168, 85, 247, 0.12), transparent 50%),
    radial-gradient(700px 420px at 50% 100%, rgba(56, 189, 248, 0.08), transparent 55%),
    linear-gradient(180deg, #f7f8fc 0%, #f1f4fb 45%, #eef2ff 100%);
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(40px);
  opacity: 0.55;
  animation: orb-drift 18s ease-in-out infinite alternate;
}

.orb-a {
  width: 360px;
  height: 360px;
  left: -80px;
  top: 12%;
  background: radial-gradient(circle, rgba(129, 140, 248, 0.55), transparent 70%);
}

.orb-b {
  width: 420px;
  height: 420px;
  right: -100px;
  top: 28%;
  background: radial-gradient(circle, rgba(192, 132, 252, 0.42), transparent 70%);
  animation-delay: -6s;
  animation-duration: 22s;
}

.orb-c {
  width: 280px;
  height: 280px;
  left: 40%;
  bottom: -60px;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.28), transparent 70%);
  animation-delay: -11s;
  animation-duration: 20s;
}

.grid-fade {
  position: absolute;
  inset: 0;
  opacity: 0.35;
  background-image:
    linear-gradient(rgba(99, 102, 241, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99, 102, 241, 0.04) 1px, transparent 1px);
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
