<template>
  <a-layout-sider
    :width="220"
    class="app-sidebar"
    theme="light"
  >
    <!-- Logo -->
    <div class="sidebar-logo">
      <div class="logo-icon">✖</div>
      <span class="logo-title">OKX 自动交易助手</span>
    </div>

    <!-- Menu -->
    <a-menu
      v-model:selectedKeys="selectedKeys"
      mode="inline"
      class="sidebar-menu"
      :style="{ borderRight: 'none' }"
      @click="handleMenuClick"
    >
      <a-menu-item key="dashboard">
        <template #icon><HomeOutlined /></template>
        仪表盘
      </a-menu-item>
      <a-menu-item key="okx-config">
        <template #icon><SettingOutlined /></template>
        OKX 配置
      </a-menu-item>
      <a-menu-item key="strategies">
        <template #icon><ThunderboltOutlined /></template>
        策略管理
      </a-menu-item>
      <a-menu-item key="positions">
        <template #icon><WalletOutlined /></template>
        当前持仓
      </a-menu-item>
      <a-menu-item key="trades">
        <template #icon><SwapOutlined /></template>
        交易记录
      </a-menu-item>
      <a-menu-item key="orders">
        <template #icon><UnorderedListOutlined /></template>
        订单记录
      </a-menu-item>
      <a-menu-item key="run-logs">
        <template #icon><FileTextOutlined /></template>
        运行日志
      </a-menu-item>
      <a-menu-item key="system-settings">
        <template #icon><ControlOutlined /></template>
        系统设置
      </a-menu-item>
    </a-menu>

    <!-- Bottom Actions -->
    <div class="sidebar-bottom">
      <a-button
        class="stop-btn"
        :class="{ 'is-stopped': systemStatus === 'STOPPED' }"
        block
        @click="handleEmergencyStop"
      >
        <span class="stop-icon">⊘</span>
        {{ systemStatus === 'RUNNING' ? '一键停止' : '已停止' }}
      </a-button>
      <div class="run-mode-info">
        <span class="mode-dot" :class="runMode === 'PAPER' ? 'dot-green' : 'dot-red'" />
        <span class="mode-text">{{ runMode === 'PAPER' ? '模拟盘模式' : '实盘模式' }}</span>
      </div>
    </div>
  </a-layout-sider>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Modal } from 'ant-design-vue'
import {
  HomeOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  WalletOutlined,
  FileTextOutlined,
  ControlOutlined,
  SwapOutlined
} from '@ant-design/icons-vue'
import { useSystemStore } from '@/stores/system.store'

const router = useRouter()
const route = useRoute()
const systemStore = useSystemStore()

const selectedKeys = ref<string[]>([])

const systemStatus = computed(() => systemStore.systemStatus)
const runMode = computed(() => systemStore.runMode)

watch(
  () => route.path,
  (path) => {
    const key = path.split('/')[1] || 'dashboard'
    selectedKeys.value = [key]
  },
  { immediate: true }
)

function handleMenuClick({ key }: { key: string }) {
  router.push(`/${key}`)
}

function handleEmergencyStop() {
  if (systemStatus.value === 'STOPPED') {
    Modal.confirm({
      title: '恢复运行',
      content: '确认恢复系统运行？恢复后策略将继续执行交易。',
      okText: '恢复运行',
      cancelText: '取消',
      onOk: () => systemStore.resumeSystem()
    })
  } else {
    Modal.confirm({
      title: '⚠️ 一键停止',
      content: '确认停止所有交易？停止后系统将不再执行新的下单操作。',
      okText: '确认停止',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => systemStore.stopSystem()
    })
  }
}
</script>

<style lang="scss" scoped>
.app-sidebar {
  border-right: 1px solid var(--border-color);
  height: 100vh;
  position: sticky;
  top: 0;
  background: #fff !important;

  :deep(.ant-layout-sider-children) {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
}

.sidebar-logo {
  display: flex;
  align-items: center;
  padding: 18px 20px;
  gap: 10px;

  .logo-icon {
    font-size: 22px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .logo-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--text-primary);
    white-space: nowrap;
  }
}

.sidebar-menu {
  flex: 1;
  padding: 4px 12px;
  overflow-y: auto;

  :deep(.ant-menu-item) {
    height: 42px;
    line-height: 42px;
    border-radius: 8px;
    margin-bottom: 2px;
    font-size: 14px;
    color: var(--text-secondary);

    &.ant-menu-item-selected {
      background: #EBF5FF;
      color: var(--primary-color);
      font-weight: 500;
    }

    &:hover:not(.ant-menu-item-selected) {
      background: #F5F7FA;
      color: var(--text-primary);
    }
  }
}

.sidebar-bottom {
  padding: 16px 16px 20px;
  border-top: 1px solid var(--border-color);

  .stop-btn {
    height: 40px;
    border-radius: 8px;
    border: 1px solid var(--danger-color);
    color: var(--danger-color);
    font-weight: 500;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      background: #FEF2F2;
    }

    &.is-stopped {
      border-color: var(--border-color);
      color: var(--text-muted);
      background: #F9FAFB;
    }

    .stop-icon {
      font-size: 16px;
    }
  }

  .run-mode-info {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-top: 14px;

    .mode-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;

      &.dot-green {
        background: var(--success-color);
      }

      &.dot-red {
        background: var(--danger-color);
      }
    }

    .mode-text {
      font-size: 13px;
      color: var(--text-secondary);
    }
  }
}
</style>
