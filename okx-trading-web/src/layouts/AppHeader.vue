<template>
  <header class="app-header">
    <div class="header-inner">
      <!-- Logo -->
      <div class="logo" @click="router.push('/video-extract')">
        <div class="logo-mark">
          <span class="logo-mark-inner">AI</span>
        </div>
        <div class="logo-text">
          <span class="logo-title">AI 工具台</span>
          <span class="logo-subtitle">Workspace</span>
        </div>
      </div>

      <div class="header-divider" />

      <!-- 主导航 -->
      <nav class="nav-list">
        <div
          v-for="group in menuGroups"
          :key="group.key"
          class="nav-item-wrap"
          @mouseenter="openGroup = group.key"
          @mouseleave="openGroup = null"
        >
          <button
            type="button"
            class="nav-trigger"
            :class="{
              active: activeGroupKey === group.key,
              open: openGroup === group.key
            }"
            @click="toggleGroup(group.key)"
          >
            <span class="nav-trigger-icon">
              <component :is="group.icon" />
            </span>
            <span class="nav-trigger-label">{{ group.title }}</span>
            <span v-if="activeGroupKey === group.key && currentPageTitle" class="nav-current-page">
              {{ currentPageTitle }}
            </span>
            <DownOutlined class="nav-arrow" :class="{ rotated: openGroup === group.key }" />
          </button>

          <transition name="dropdown-fade">
            <div v-show="openGroup === group.key" class="nav-dropdown" :class="`cols-${group.cols}`">
              <div class="dropdown-head">
                <div class="dropdown-head-left">
                  <span class="dropdown-head-icon" :style="{ background: group.accentSoft, color: group.accent }">
                    <component :is="group.icon" />
                  </span>
                  <div>
                    <div class="dropdown-head-title">{{ group.title }}</div>
                    <div class="dropdown-head-desc">{{ group.description }}</div>
                  </div>
                </div>
              </div>

              <div class="dropdown-grid" :style="{ gridTemplateColumns: `repeat(${group.cols}, minmax(0, 1fr))` }">
                <button
                  v-for="item in group.children"
                  :key="item.key"
                  type="button"
                  class="dropdown-item"
                  :class="{ active: currentRouteKey === item.key }"
                  @click="goTo(item.key)"
                >
                  <span class="item-icon" :style="{ background: item.iconBg, color: item.iconColor }">
                    <component :is="item.icon" />
                  </span>
                  <span class="item-body">
                    <span class="item-title">
                      {{ item.title }}
                      <span v-if="currentRouteKey === item.key" class="item-badge">当前</span>
                    </span>
                    <span class="item-desc">{{ item.description }}</span>
                  </span>
                </button>
              </div>
            </div>
          </transition>
        </div>
      </nav>

      <!-- 右侧操作 -->
      <div class="header-right">
        <template v-if="isTradingRoute">
          <div class="status-chip" :class="runMode === 'PAPER' ? 'chip-paper' : 'chip-live'">
            <span class="chip-dot" />
            {{ runMode === 'PAPER' ? '模拟盘' : '实盘' }}
          </div>
          <div class="status-chip" :class="systemStatus === 'RUNNING' ? 'chip-running' : 'chip-stopped'">
            <span class="chip-dot" />
            {{ systemStatus === 'RUNNING' ? '运行中' : '已停止' }}
          </div>
          <button
            type="button"
            class="action-stop"
            :class="{ stopped: systemStatus === 'STOPPED' }"
            @click="handleEmergencyStop"
          >
            {{ systemStatus === 'RUNNING' ? '一键停止' : '恢复运行' }}
          </button>
          <div class="header-divider thin" />
        </template>

        <a-tooltip title="刷新页面">
          <button type="button" class="icon-btn" @click="handleRefresh">
            <SyncOutlined />
          </button>
        </a-tooltip>
        <a-tooltip title="通知">
          <a-badge :count="0" :offset="[-2, 2]">
            <button type="button" class="icon-btn">
              <BellOutlined />
            </button>
          </a-badge>
        </a-tooltip>

        <div class="user-card">
          <div class="user-main" title="查看个人资料" @click="openProfileCard">
            <a-avatar :size="32" class="user-avatar">{{ avatarLetter }}</a-avatar>
            <div class="user-meta">
              <span class="user-name">{{ displayName }}</span>
              <span class="user-role">{{ headerRoleText }}</span>
            </div>
          </div>
          <a-dropdown placement="bottomRight">
            <span class="user-caret-wrap" @click.stop>
              <DownOutlined class="user-caret" />
            </span>
            <template #overlay>
              <a-menu @click="onUserMenu">
                <a-menu-item key="profile">个人资料</a-menu-item>
                <a-menu-item key="logout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>

        <ProfileCardModal v-model:open="profileOpen" />
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, computed, watch, markRaw, onMounted, onUnmounted, type Component } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'
import {
  HomeOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  WalletOutlined,
  FileTextOutlined,
  ControlOutlined,
  SwapOutlined,
  RobotOutlined,
  VideoCameraOutlined,
  FundOutlined,
  ToolOutlined,
  SyncOutlined,
  BellOutlined,
  DownOutlined,
  TeamOutlined
} from '@ant-design/icons-vue'
import { useSystemStore } from '@/stores/system.store'
import ProfileCardModal from '@/components/ProfileCardModal.vue'
import { roleLabel } from '@/api/auth.api'

interface MenuItem {
  key: string
  title: string
  description: string
  icon: Component
  iconBg: string
  iconColor: string
}

interface MenuGroup {
  key: string
  title: string
  description: string
  icon: Component
  accent: string
  accentSoft: string
  cols: number
  children: MenuItem[]
}

const TRADING_KEYS = new Set([
  'dashboard',
  'okx-config',
  'strategies',
  'positions',
  'trades',
  'orders',
  'run-logs',
  'system-settings',
  'ai-chat'
])

const TOOLS_KEYS = new Set(['video-extract'])

/** 系统管理（仅超级管理员） */
const ADMIN_KEYS = new Set(['user-manage'])

/** 仅超管可见的大菜单 key */
const SUPER_ADMIN_ONLY_GROUPS = new Set(['trading', 'admin'])

const ALL_MENU_GROUPS: MenuGroup[] = [
  {
    key: 'tools',
    title: '工具使用',
    description: '常用效率与内容处理工具',
    icon: markRaw(ToolOutlined),
    accent: '#7C3AED',
    accentSoft: '#F3E8FF',
    cols: 1,
    children: [
      {
        key: 'video-extract',
        title: '视频提取',
        description: '提取视频核心内容与摘要',
        icon: markRaw(VideoCameraOutlined),
        iconBg: '#F3E8FF',
        iconColor: '#7C3AED'
      }
    ]
  },
  {
    key: 'trading',
    title: '交易管理',
    description: '策略、持仓与交易相关功能',
    icon: markRaw(FundOutlined),
    accent: '#1677FF',
    accentSoft: '#EBF5FF',
    cols: 3,
    children: [
      {
        key: 'dashboard',
        title: '仪表盘',
        description: '总览账户与运行概况',
        icon: markRaw(HomeOutlined),
        iconBg: '#EBF5FF',
        iconColor: '#1677FF'
      },
      {
        key: 'okx-config',
        title: 'OKX 配置',
        description: 'API 密钥与连接管理',
        icon: markRaw(SettingOutlined),
        iconBg: '#F0F9FF',
        iconColor: '#0284C7'
      },
      {
        key: 'strategies',
        title: '策略管理',
        description: '创建与启停交易策略',
        icon: markRaw(ThunderboltOutlined),
        iconBg: '#FEF3C7',
        iconColor: '#D97706'
      },
      {
        key: 'positions',
        title: '当前持仓',
        description: '查看持仓与浮动盈亏',
        icon: markRaw(WalletOutlined),
        iconBg: '#DCFCE7',
        iconColor: '#16A34A'
      },
      {
        key: 'trades',
        title: '交易记录',
        description: '历史成交明细查询',
        icon: markRaw(SwapOutlined),
        iconBg: '#E0E7FF',
        iconColor: '#4F46E5'
      },
      {
        key: 'orders',
        title: '订单记录',
        description: '委托单状态与详情',
        icon: markRaw(UnorderedListOutlined),
        iconBg: '#FCE7F3',
        iconColor: '#DB2777'
      },
      {
        key: 'run-logs',
        title: '运行日志',
        description: '策略信号与执行日志',
        icon: markRaw(FileTextOutlined),
        iconBg: '#F1F5F9',
        iconColor: '#475569'
      },
      {
        key: 'system-settings',
        title: '系统设置',
        description: '运行参数与安全选项',
        icon: markRaw(ControlOutlined),
        iconBg: '#FFEDD5',
        iconColor: '#EA580C'
      },
      {
        key: 'ai-chat',
        title: 'AI 助手',
        description: '交易相关智能对话',
        icon: markRaw(RobotOutlined),
        iconBg: '#EDE9FE',
        iconColor: '#7C3AED'
      }
    ]
  },
  {
    key: 'admin',
    title: '系统管理',
    description: '用户与权限等管理功能',
    icon: markRaw(TeamOutlined),
    accent: '#DB2777',
    accentSoft: '#FCE7F3',
    cols: 1,
    children: [
      {
        key: 'user-manage',
        title: '用户管理',
        description: '查询用户并启用/禁用账号',
        icon: markRaw(TeamOutlined),
        iconBg: '#FCE7F3',
        iconColor: '#DB2777'
      }
    ]
  }
]

const router = useRouter()
const route = useRoute()
const systemStore = useSystemStore()
const auth = useAuthStore()

const openGroup = ref<string | null>(null)
const profileOpen = ref(false)

/** 交易管理、系统管理仅超管可见；普通用户/会员只看工具 */
const menuGroups = computed(() =>
  ALL_MENU_GROUPS.filter((g) => !SUPER_ADMIN_ONLY_GROUPS.has(g.key) || auth.isSuperAdmin)
)

const displayName = computed(() => auth.user?.nickname || auth.user?.email || '用户')
const avatarLetter = computed(() => {
  const n = displayName.value
  return n ? n.charAt(0).toUpperCase() : 'U'
})
const headerRoleText = computed(() => {
  if (!auth.user) return '未登录'
  return roleLabel(auth.user.role, auth.user.roleLabel)
})

function openProfileCard() {
  profileOpen.value = true
}

const systemStatus = computed(() => systemStore.systemStatus)
const runMode = computed(() => systemStore.runMode)
const currentRouteKey = computed(() => route.path.split('/')[1] || 'video-extract')
const isTradingRoute = computed(() => TRADING_KEYS.has(currentRouteKey.value))

const activeGroupKey = computed(() => {
  if (TOOLS_KEYS.has(currentRouteKey.value)) return 'tools'
  if (TRADING_KEYS.has(currentRouteKey.value)) return 'trading'
  if (ADMIN_KEYS.has(currentRouteKey.value)) return 'admin'
  return ''
})

const currentPageTitle = computed(() => {
  for (const group of menuGroups.value) {
    const item = group.children.find((c) => c.key === currentRouteKey.value)
    if (item) return item.title
  }
  return ''
})

watch(
  () => route.path,
  () => {
    openGroup.value = null
  }
)

function onDocumentClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (!target?.closest('.nav-item-wrap')) {
    openGroup.value = null
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  // 登录后刷新角色，避免 localStorage 旧缓存导致菜单权限不准
  if (auth.isLoggedIn) {
    auth.fetchMe().catch(() => undefined)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})

function toggleGroup(key: string) {
  openGroup.value = openGroup.value === key ? null : key
}

function goTo(key: string) {
  openGroup.value = null
  router.push(`/${key}`)
}

function handleRefresh() {
  window.location.reload()
}

async function onUserMenu({ key }: { key: string }) {
  if (key === 'profile') {
    openProfileCard()
    return
  }
  if (key === 'logout') {
    await auth.logout()
    message.success('已退出登录')
    router.replace('/login')
  }
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
      title: '一键停止',
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
.app-header {
  position: sticky;
  top: 0;
  z-index: 200;
  height: 64px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid rgba(229, 231, 235, 0.9);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.8) inset, 0 8px 24px rgba(15, 23, 42, 0.04);
}

.header-inner {
  height: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;
  user-select: none;
  padding: 4px 2px;
  border-radius: 10px;
  transition: opacity 0.2s;

  &:hover {
    opacity: 0.9;
  }
}

.logo-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366F1 0%, #8B5CF6 50%, #A855F7 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 14px rgba(99, 102, 241, 0.35);
}

.logo-mark-inner {
  color: #fff;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.logo-title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}

.logo-subtitle {
  font-size: 11px;
  color: #9CA3AF;
  letter-spacing: 0.4px;
}

.header-divider {
  width: 1px;
  height: 28px;
  background: #E5E7EB;
  flex-shrink: 0;

  &.thin {
    height: 20px;
    margin: 0 4px;
  }
}

.nav-list {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.nav-item-wrap {
  position: relative;
}

.nav-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: #4B5563;
  cursor: pointer;
  transition: all 0.18s ease;
  font-size: 14px;

  &:hover,
  &.open {
    background: #F3F4F6;
    color: #111827;
  }

  &.active {
    background: linear-gradient(180deg, #F5F3FF 0%, #EEF2FF 100%);
    border-color: #E0E7FF;
    color: #4338CA;

    .nav-trigger-icon {
      color: #6366F1;
    }
  }
}

.nav-trigger-icon {
  display: inline-flex;
  font-size: 15px;
  color: #6B7280;
}

.nav-trigger-label {
  font-weight: 600;
  white-space: nowrap;
}

.nav-current-page {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(99, 102, 241, 0.1);
  color: #4F46E5;
  font-size: 12px;
  font-weight: 500;
}

.nav-arrow {
  font-size: 10px;
  color: #9CA3AF;
  transition: transform 0.18s ease;

  &.rotated {
    transform: rotate(180deg);
  }
}

.nav-dropdown {
  position: absolute;
  top: calc(100% + 10px);
  left: 0;
  min-width: 280px;
  padding: 12px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid #E5E7EB;
  box-shadow:
    0 4px 6px -1px rgba(15, 23, 42, 0.06),
    0 18px 40px -12px rgba(15, 23, 42, 0.18);
  z-index: 50;

  &.cols-3 {
    min-width: 720px;
  }

  &::before {
    content: '';
    position: absolute;
    top: -10px;
    left: 0;
    right: 0;
    height: 10px;
  }
}

.dropdown-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid #F3F4F6;
}

.dropdown-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dropdown-head-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

.dropdown-head-title {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}

.dropdown-head-desc {
  font-size: 12px;
  color: #9CA3AF;
  margin-top: 1px;
}

.dropdown-grid {
  display: grid;
  gap: 6px;
  padding-top: 8px;
}

.dropdown-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: all 0.16s ease;

  &:hover {
    background: #F8FAFC;
    border-color: #EEF2F7;
  }

  &.active {
    background: linear-gradient(180deg, #F5F3FF 0%, #EEF2FF 100%);
    border-color: #E0E7FF;

    .item-title {
      color: #4338CA;
    }
  }
}

.item-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}

.item-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.item-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #1F2937;
}

.item-badge {
  font-size: 11px;
  font-weight: 500;
  color: #6366F1;
  background: rgba(99, 102, 241, 0.12);
  border-radius: 999px;
  padding: 0 6px;
  line-height: 18px;
}

.item-desc {
  font-size: 12px;
  color: #9CA3AF;
  line-height: 1.35;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid transparent;

  .chip-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }

  &.chip-paper {
    color: #047857;
    background: #ECFDF5;
    border-color: #A7F3D0;

    .chip-dot {
      background: #10B981;
    }
  }

  &.chip-live {
    color: #B91C1C;
    background: #FEF2F2;
    border-color: #FECACA;

    .chip-dot {
      background: #EF4444;
    }
  }

  &.chip-running {
    color: #1D4ED8;
    background: #EFF6FF;
    border-color: #BFDBFE;

    .chip-dot {
      background: #3B82F6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.18);
    }
  }

  &.chip-stopped {
    color: #6B7280;
    background: #F9FAFB;
    border-color: #E5E7EB;

    .chip-dot {
      background: #9CA3AF;
    }
  }
}

.action-stop {
  height: 30px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid #FCA5A5;
  background: #FEF2F2;
  color: #DC2626;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.16s ease;

  &:hover {
    background: #FEE2E2;
    border-color: #F87171;
  }

  &.stopped {
    border-color: #D1D5DB;
    background: #F9FAFB;
    color: #6B7280;

    &:hover {
      background: #F3F4F6;
      color: #374151;
    }
  }
}

.icon-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: #6B7280;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.16s ease;
  font-size: 16px;

  &:hover {
    background: #F3F4F6;
    color: #111827;
  }
}

.user-card {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px 4px 4px;
  margin-left: 2px;
  border-radius: 999px;
  border: 1px solid #E5E7EB;
  background: #fff;
  transition: all 0.16s ease;

  &:hover {
    border-color: #D1D5DB;
    box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
  }
}

.user-main {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  border-radius: 999px;
  padding-right: 2px;
}

.user-avatar {
  background: linear-gradient(135deg, #6366F1, #8B5CF6) !important;
  font-size: 13px;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.user-role {
  font-size: 11px;
  color: #9CA3AF;
}

.user-caret-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  cursor: pointer;
  color: #9CA3AF;

  &:hover {
    background: #F3F4F6;
    color: #6B7280;
  }
}

.user-caret {
  font-size: 10px;
}

.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.dropdown-fade-enter-from,
.dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

@media (max-width: 1100px) {
  .nav-current-page {
    display: none;
  }

  .logo-subtitle,
  .user-meta {
    display: none;
  }

  .nav-dropdown.cols-3 {
    min-width: 520px;
  }
}
</style>
