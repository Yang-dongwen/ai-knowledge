<template>
  <header class="app-header">
    <div class="header-inner">
      <!-- Logo -->
      <div class="logo" @click="router.push('/home')">
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
            <div
              v-show="openGroup === group.key"
              class="nav-dropdown"
              :class="[`cols-${group.cols}`, `items-${Math.min(group.children.length, 6)}`]"
            >
              <div class="dropdown-head">
                <div class="dropdown-head-left">
                  <span class="dropdown-head-icon" :style="{ background: group.accentSoft, color: group.accent }">
                    <component :is="group.icon" />
                  </span>
                  <div class="dropdown-head-text">
                    <div class="dropdown-head-title">{{ group.title }}</div>
                    <div class="dropdown-head-desc">{{ group.description }}</div>
                  </div>
                </div>
              </div>

              <div
                class="dropdown-grid"
                :style="{ gridTemplateColumns: `repeat(${group.cols}, minmax(0, 1fr))` }"
              >
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
                  <span class="item-chevron" aria-hidden="true">›</span>
                </button>
              </div>
            </div>
          </transition>
        </div>
      </nav>

      <!-- 右侧操作 -->
      <div class="header-right">
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
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'
import {
  RobotOutlined,
  VideoCameraOutlined,
  PictureOutlined,
  ToolOutlined,
  SyncOutlined,
  BellOutlined,
  DownOutlined,
  TeamOutlined,
  AppstoreOutlined
} from '@ant-design/icons-vue'
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

const TOOLS_KEYS = new Set(['home', 'video-extract', 'video-generate', 'image-generate', 'ai-chat'])
const ADMIN_KEYS = new Set(['user-manage'])
const SUPER_ADMIN_ONLY_GROUPS = new Set(['admin'])

const ALL_MENU_GROUPS: MenuGroup[] = [
  {
    key: 'tools',
    title: 'AI 工具',
    description: '工作台、对话、视频与文生图',
    icon: markRaw(ToolOutlined),
    accent: '#1f2937',
    accentSoft: '#f3f4f6',
    cols: 2,
    children: [
      {
        key: 'home',
        title: '工作台',
        description: '工具门户与快捷入口',
        icon: markRaw(AppstoreOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#1f2937'
      },
      {
        key: 'ai-chat',
        title: 'AI 对话',
        description: '纯聊天助手，可自由切换模型',
        icon: markRaw(RobotOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#374151'
      },
      {
        key: 'video-extract',
        title: '视频提取',
        description: '粘贴链接，自动转录并提炼核心内容',
        icon: markRaw(VideoCameraOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#374151'
      },
      {
        key: 'video-generate',
        title: 'AI 视频生成',
        description: '输入提示词，自动规划分镜并生成视频',
        icon: markRaw(RobotOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#374151'
      },
      {
        key: 'image-generate',
        title: 'AI 文生图',
        description: '提示词驱动，NVIDIA FLUX 生成图片',
        icon: markRaw(PictureOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#374151'
      }
    ]
  },
  {
    key: 'admin',
    title: '系统管理',
    description: '用户与权限等管理功能',
    icon: markRaw(TeamOutlined),
    accent: '#1f2937',
    accentSoft: '#f3f4f6',
    cols: 1,
    children: [
      {
        key: 'user-manage',
        title: '用户管理',
        description: '查询用户并启用/禁用账号',
        icon: markRaw(TeamOutlined),
        iconBg: '#f3f4f6',
        iconColor: '#1f2937'
      }
    ]
  }
]

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const openGroup = ref<string | null>(null)
const profileOpen = ref(false)

/** 系统管理仅超管可见 */
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

const currentRouteKey = computed(() => route.path.split('/')[1] || 'home')

const activeGroupKey = computed(() => {
  if (TOOLS_KEYS.has(currentRouteKey.value)) return 'tools'
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
</script>

<style lang="scss" scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 200;
  height: 64px;
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: saturate(180%) blur(18px);
  -webkit-backdrop-filter: saturate(180%) blur(18px);
  border-bottom: 1px solid rgba(226, 232, 240, 0.72);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 4px 16px rgba(15, 23, 42, 0.04);
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
  background: #1f2937;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: none;
  position: relative;

  &::after {
    content: '';
    position: absolute;
    inset: 1px;
    border-radius: 9px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 55%);
    pointer-events: none;
  }
}

.logo-mark-inner {
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.4px;
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
  border-radius: 999px;
  background: transparent;
  color: #475569;
  cursor: pointer;
  transition: all 0.18s ease;
  font-size: 14px;

  &:hover,
  &.open {
    background: rgba(248, 250, 252, 0.95);
    border-color: rgba(226, 232, 240, 0.9);
    color: #0f172a;
    box-shadow: 0 4px 14px rgba(15, 23, 42, 0.05);
  }

  &.active {
    background: #f3f4f6;
    border-color: #e5e7eb;
    color: #111827;
    box-shadow: none;

    .nav-trigger-icon {
      color: #111827;
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
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #374151;
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
  min-width: 320px;
  max-width: min(920px, calc(100vw - 32px));
  padding: 12px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid #e5e7eb;
  box-shadow:
    0 4px 6px -1px rgba(15, 23, 42, 0.05),
    0 16px 36px -10px rgba(15, 23, 42, 0.12);
  z-index: 50;

  /* 单列工具菜单：固定舒适宽度，条目纵向排列 */
  &.cols-1 {
    min-width: 340px;
    width: 340px;
  }

  &.cols-2 {
    min-width: 560px;
  }

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
  padding: 8px 10px 12px;
  margin-bottom: 2px;
  border-bottom: 1px solid #F3F4F6;
}

.dropdown-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.dropdown-head-text {
  min-width: 0;
}

.dropdown-head-icon {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}

.dropdown-head-title {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
  line-height: 1.3;
}

.dropdown-head-desc {
  font-size: 12px;
  color: #9CA3AF;
  margin-top: 2px;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dropdown-grid {
  display: grid;
  gap: 6px;
  padding: 8px 2px 2px;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 12px 12px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
  box-sizing: border-box;

  &:hover {
    background: #f9fafb;
    border-color: #e5e7eb;

    .item-chevron {
      opacity: 1;
      transform: translateX(2px);
      color: #374151;
    }
  }

  &.active {
    background: #f3f4f6;
    border-color: #e5e7eb;
    box-shadow: none;

    .item-title {
      color: #111827;
    }

    .item-chevron {
      opacity: 1;
      color: #111827;
    }
  }
}

/* 单列菜单条目略增高，信息更易读 */
.nav-dropdown.cols-1 .dropdown-item {
  padding: 14px 14px;
  min-height: 68px;
}

.item-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.item-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.item-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1F2937;
  line-height: 1.3;
}

.item-badge {
  font-size: 11px;
  font-weight: 500;
  color: #374151;
  background: #e5e7eb;
  border-radius: 999px;
  padding: 0 7px;
  line-height: 18px;
  flex-shrink: 0;
}

.item-desc {
  font-size: 12px;
  color: #9CA3AF;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item-chevron {
  flex-shrink: 0;
  width: 18px;
  text-align: center;
  font-size: 18px;
  line-height: 1;
  color: #D1D5DB;
  opacity: 0.65;
  transition: opacity 0.15s ease, transform 0.15s ease, color 0.15s ease;
  font-weight: 300;
}

/* 多列网格时隐藏箭头，避免拥挤 */
.nav-dropdown.cols-2 .item-chevron,
.nav-dropdown.cols-3 .item-chevron {
  display: none;
}

.nav-dropdown.cols-2 .dropdown-item,
.nav-dropdown.cols-3 .dropdown-item {
  align-items: flex-start;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}


.icon-btn {
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.16s ease;
  font-size: 16px;

  &:hover {
    background: rgba(248, 250, 252, 0.95);
    border-color: #e2e8f0;
    color: #0f172a;
    box-shadow: 0 4px 12px rgba(15, 23, 42, 0.05);
  }
}

.user-card {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px 4px 4px;
  margin-left: 2px;
  border-radius: 999px;
  border: 1px solid #e5e7eb;
  background: #fff;
  box-shadow: none;
  transition: border-color 0.15s ease, background 0.15s ease;

  &:hover {
    border-color: #d1d5db;
    background: #fafafa;
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
  background: #1f2937 !important;
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
