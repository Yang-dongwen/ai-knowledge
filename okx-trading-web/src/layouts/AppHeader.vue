<template>
  <a-layout-header class="app-header">
    <div class="header-left">
      <!-- 留空，标题在内容区 -->
    </div>

    <div class="header-right">
      <div class="status-indicator">
        <span class="status-dot" :class="systemStatus === 'RUNNING' ? 'dot-green' : 'dot-red'" />
        <span class="status-text">{{ systemStatus === 'RUNNING' ? '运行中' : '已停止' }}</span>
      </div>
      <a-button type="text" class="header-btn" @click="handleRefresh">
        <template #icon><SyncOutlined /></template>
      </a-button>
      <a-badge :count="0" :offset="[-2, 2]">
        <a-button type="text" class="header-btn">
          <template #icon><BellOutlined /></template>
        </a-button>
      </a-badge>
      <div class="user-info">
        <a-avatar :size="30" style="background-color: #1677FF; font-size: 13px;">A</a-avatar>
        <span class="username">admin</span>
        <DownOutlined style="font-size: 10px; color: #9CA3AF;" />
      </div>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { SyncOutlined, BellOutlined, DownOutlined } from '@ant-design/icons-vue'
import { useSystemStore } from '@/stores/system.store'

const systemStore = useSystemStore()
const systemStatus = computed(() => systemStore.systemStatus)

function handleRefresh() {
  window.location.reload()
}
</script>

<style lang="scss" scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  height: 56px;
  padding: 0 24px;
  background: var(--card-bg);
  border-bottom: 1px solid var(--border-color);
  line-height: normal;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-right: 12px;

  .status-dot {
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

  .status-text {
    font-size: 13px;
    color: var(--text-secondary);
  }
}

.header-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);

  &:hover {
    background: #F5F7FA;
    color: var(--text-primary);
  }
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 8px;
  margin-left: 8px;

  &:hover {
    background: #F5F7FA;
  }

  .username {
    font-size: 14px;
    color: var(--text-primary);
  }
}
</style>
