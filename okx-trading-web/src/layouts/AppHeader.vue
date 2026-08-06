<template>
  <header class="app-header">
    <div class="header-inner">
      <!-- 移动端：菜单按钮 -->
      <button
        type="button"
        class="icon-btn mobile-menu-btn"
        aria-label="打开导航菜单"
        @click="mobileNavOpen = true"
      >
        <MenuOutlined />
      </button>

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

      <div class="header-divider desktop-only" />

      <!-- 主导航（桌面） -->
      <nav class="nav-list desktop-only">
        <div
          v-for="group in menuGroups"
          :key="group.key"
          class="nav-item-wrap"
          @mouseenter="onGroupEnter(group)"
          @mouseleave="onGroupLeave"
        >
          <button
            type="button"
            class="nav-trigger"
            :class="{
              active: activeGroupKey === group.key,
              open: openGroup === group.key
            }"
            @click="toggleGroup(group)"
          >
            <span class="nav-trigger-icon">
              <component :is="group.icon" />
            </span>
            <span class="nav-trigger-label">{{ group.title }}</span>
            <span
              v-if="activeGroupKey === group.key && currentPageTitle && group.children.length > 1"
              class="nav-current-page"
            >
              {{ currentPageTitle }}
            </span>
            <DownOutlined
              v-if="group.children.length > 1"
              class="nav-arrow"
              :class="{ rotated: openGroup === group.key }"
            />
          </button>

          <!-- 仅多子项展示下拉；单入口（如知识库）点击顶栏直接跳转，避免 hover 收起导致点不到 -->
          <transition v-if="group.children.length > 1" name="dropdown-fade">
            <div
              v-show="openGroup === group.key"
              class="nav-dropdown"
              :class="[`cols-${group.cols}`, `items-${Math.min(group.children.length, 6)}`]"
            >
              <div class="dropdown-head">
                <div class="dropdown-head-left">
                  <span class="dropdown-head-icon">
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
                  @click.stop="goTo(item.key)"
                >
                  <span class="item-icon">
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

      <!-- 移动端当前页标题（替代顶栏导航） -->
      <div v-if="currentPageTitle" class="mobile-page-title">{{ currentPageTitle }}</div>

      <!-- 右侧操作 -->
      <div class="header-right">
        <ThemeToggle />
        <a-tooltip title="刷新页面">
          <button type="button" class="icon-btn desktop-only" @click="handleRefresh">
            <SyncOutlined />
          </button>
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
                <a-menu-item key="member">会员中心</a-menu-item>
                <a-menu-item key="logout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>

        <ProfileCardModal v-model:open="profileOpen" />
      </div>
    </div>

    <!-- 移动端导航抽屉（桌面不渲染交互，仅小屏打开） -->
    <a-drawer
      v-model:open="mobileNavOpen"
      placement="left"
      :width="300"
      :body-style="{ padding: '12px 14px 24px' }"
      class="mobile-nav-drawer"
      title="导航"
      @close="mobileNavOpen = false"
    >
      <div class="mobile-nav">
        <section v-for="group in menuGroups" :key="group.key" class="mobile-nav-group">
          <div class="mobile-nav-group-title">
            <span class="mobile-nav-group-icon">
              <component :is="group.icon" />
            </span>
            {{ group.title }}
          </div>
          <button
            v-for="item in group.children"
            :key="item.key"
            type="button"
            class="mobile-nav-item"
            :class="{ active: currentRouteKey === item.key }"
            @click="goToMobile(item.key)"
          >
            <span class="mobile-nav-item-icon">
              <component :is="item.icon" />
            </span>
            <span class="mobile-nav-item-body">
              <span class="mobile-nav-item-title">{{ item.title }}</span>
              <span class="mobile-nav-item-desc">{{ item.description }}</span>
            </span>
          </button>
        </section>
        <section class="mobile-nav-group">
          <div class="mobile-nav-group-title">账户</div>
          <button type="button" class="mobile-nav-item" @click="goToMobile('member')">
            <span class="mobile-nav-item-body">
              <span class="mobile-nav-item-title">会员中心</span>
              <span class="mobile-nav-item-desc">查看会员状态与开通</span>
            </span>
          </button>
        </section>
      </div>
    </a-drawer>
  </header>
</template>

<script setup lang="ts">
import { ref, computed, watch, markRaw, onMounted, onUnmounted, type Component } from 'vue'
import { useRouter, useRoute, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'
import {
  RobotOutlined,
  VideoCameraOutlined,
  PictureOutlined,
  ToolOutlined,
  SyncOutlined,

  DownOutlined,
  MenuOutlined,
  TeamOutlined,
  AppstoreOutlined,
  FileTextOutlined,
  BookOutlined
} from '@ant-design/icons-vue'
import ProfileCardModal from '@/components/ProfileCardModal.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { roleLabel } from '@/api/auth.api'

interface MenuItem {
  key: string
  title: string
  description: string
  icon: Component
}

interface MenuGroup {
  key: string
  title: string
  description: string
  icon: Component
  cols: number
  children: MenuItem[]
}

const TOOLS_KEYS = new Set([
  'home',
  'video-extract',
  'video-generate',
  'image-generate',
  'article-extract',
  'ai-chat'
])
/** 知识库独立一级菜单（与 AI 工具平级） */
const KB_KEYS = new Set(['kb'])
const ADMIN_KEYS = new Set(['user-manage'])
const SUPER_ADMIN_ONLY_GROUPS = new Set(['admin'])

const ALL_MENU_GROUPS: MenuGroup[] = [
  {
    key: 'tools',
    title: 'AI 工具',
    description: '工作台、对话、视频、文生图与文章提取',
    icon: markRaw(ToolOutlined),
    cols: 2,
    children: [
      {
        key: 'home',
        title: '工作台',
        description: '工具门户与快捷入口',
        icon: markRaw(AppstoreOutlined)
      },
      {
        key: 'ai-chat',
        title: 'AI 对话',
        description: '纯聊天助手，可自由切换模型',
        icon: markRaw(RobotOutlined)
      },
      {
        key: 'video-extract',
        title: '视频提取',
        description: '粘贴链接，自动转录并提炼核心内容',
        icon: markRaw(VideoCameraOutlined)
      },
      {
        key: 'video-generate',
        title: 'AI 视频生成',
        description: '输入提示词，自动规划分镜并生成视频',
        icon: markRaw(RobotOutlined)
      },
      {
        key: 'image-generate',
        title: 'AI 文生图',
        description: '提示词驱动，NVIDIA FLUX 生成图片',
        icon: markRaw(PictureOutlined)
      },
      {
        key: 'article-extract',
        title: '文章提取',
        description: '链接或粘贴正文，提取核心并二次创作',
        icon: markRaw(FileTextOutlined)
      }
    ]
  },
  {
    key: 'kb',
    title: '知识库',
    description: '个人笔记整理与检索，独立于 AI 工具',
    icon: markRaw(BookOutlined),
    cols: 1,
    children: [
      {
        key: 'kb',
        title: '知识库工作台',
        description: '富文本/Markdown 笔记、附件与分类标签',
        icon: markRaw(BookOutlined)
      }
    ]
  },
  {
    key: 'admin',
    title: '系统管理',
    description: '用户与权限等管理功能',
    icon: markRaw(TeamOutlined),
    cols: 1,
    children: [
      {
        key: 'user-manage',
        title: '用户管理',
        description: '查询用户并启用/禁用账号',
        icon: markRaw(TeamOutlined)
      }
    ]
  }
]

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const openGroup = ref<string | null>(null)
const profileOpen = ref(false)
const mobileNavOpen = ref(false)

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
  if (KB_KEYS.has(currentRouteKey.value)) return 'kb'
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

function isSingleEntry(group: MenuGroup) {
  return group.children.length === 1
}

function onGroupEnter(group: MenuGroup) {
  // 单入口菜单不展开空下拉，避免鼠标移出就关导致“点了没反应”
  if (isSingleEntry(group)) return
  openGroup.value = group.key
}

function onGroupLeave() {
  openGroup.value = null
}

function toggleGroup(group: MenuGroup | string) {
  const g =
    typeof group === 'string'
      ? menuGroups.value.find((x) => x.key === group)
      : group
  if (!g) return
  // 知识库等单入口：直接跳转
  if (isSingleEntry(g)) {
    goTo(g.children[0].key)
    return
  }
  openGroup.value = openGroup.value === g.key ? null : g.key
}

function goTo(key: string) {
  openGroup.value = null
  const path = key.startsWith('/') ? key : `/${key}`
  router.push(path).catch((err: unknown) => {
    // Vue Router 4：重复/取消导航会 reject，不应当成失败
    if (
      isNavigationFailure(err, NavigationFailureType.duplicated) ||
      isNavigationFailure(err, NavigationFailureType.cancelled) ||
      isNavigationFailure(err, NavigationFailureType.aborted)
    ) {
      return
    }
    console.error('导航失败', path, err)
    const detail =
      err instanceof Error
        ? err.message
        : typeof err === 'string'
          ? err
          : '未知错误'
    message.error(`页面打开失败：${detail.slice(0, 120)}`)
  })
}

function goToMobile(key: string) {
  mobileNavOpen.value = false
  goTo(key)
}

function handleRefresh() {
  window.location.reload()
}

async function onUserMenu({ key }: { key: string }) {
  if (key === 'profile') {
    openProfileCard()
    return
  }
  if (key === 'member') {
    goTo('member')
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
  background: var(--header-bg);
  backdrop-filter: saturate(180%) blur(18px);
  -webkit-backdrop-filter: saturate(180%) blur(18px);
  border-bottom: 1px solid var(--header-border);
  box-shadow:
    0 1px 0 var(--header-inset) inset,
    var(--header-shadow);

  @media (max-width: 768px) {
    height: 56px;
  }
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
  background: var(--primary-color);
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
    background: var(--logo-sheen);
    pointer-events: none;
  }
}

.logo-mark-inner {
  color: var(--text-on-primary);
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
  color: var(--primary-strong);
}

.logo-subtitle {
  font-size: 11px;
  color: var(--text-tertiary);
  letter-spacing: 0.4px;
}

.header-divider {
  width: 1px;
  height: 28px;
  background: var(--border-color);
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
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.18s ease;
  font-size: 14px;

  &:hover,
  &.open {
    background: var(--nav-hover-bg);
    border-color: var(--nav-hover-border);
    color: var(--text-primary);
    box-shadow: 0 4px 14px color-mix(in srgb, var(--text-primary) 5%, transparent);
  }

  &.active {
    background: var(--surface-3);
    border-color: var(--border-color);
    color: var(--primary-strong);
    box-shadow: none;

    .nav-trigger-icon {
      color: var(--primary-strong);
    }
  }
}

.nav-trigger-icon {
  display: inline-flex;
  font-size: 15px;
  color: var(--text-muted);
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
  background: var(--surface-3);
  color: var(--soft-accent-text);
  font-size: 12px;
  font-weight: 500;
}

.nav-arrow {
  font-size: 10px;
  color: var(--text-tertiary);
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
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  box-shadow:
    0 4px 6px -1px color-mix(in srgb, var(--text-primary) 5%, transparent),
    0 16px 36px -10px color-mix(in srgb, var(--text-primary) 12%, transparent);
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
  border-bottom: 1px solid var(--surface-3);
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
  background: var(--icon-soft-bg);
  color: var(--icon-soft-fg-strong);
}

.dropdown-head-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--primary-strong);
  line-height: 1.3;
}

.dropdown-head-desc {
  font-size: 12px;
  color: var(--text-tertiary);
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
    background: var(--btn-default-hover);
    border-color: var(--border-color);

    .item-chevron {
      opacity: 1;
      transform: translateX(2px);
      color: var(--soft-accent-text);
    }
  }

  &.active {
    background: var(--surface-3);
    border-color: var(--border-color);
    box-shadow: none;

    .item-title {
      color: var(--primary-strong);
    }

    .item-chevron {
      opacity: 1;
      color: var(--primary-strong);
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
  background: var(--icon-soft-bg);
  color: var(--icon-soft-fg);
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
  color: var(--primary-color);
  line-height: 1.3;
}

.item-badge {
  font-size: 11px;
  font-weight: 500;
  color: var(--soft-accent-text);
  background: var(--border-color);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 18px;
  flex-shrink: 0;
}

.item-desc {
  font-size: 12px;
  color: var(--text-tertiary);
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
  color: var(--border-strong);
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
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.16s ease;
  font-size: 16px;

  &:hover {
    background: var(--nav-hover-bg);
    border-color: var(--border-soft);
    color: var(--text-primary);
    box-shadow: 0 4px 12px color-mix(in srgb, var(--text-primary) 5%, transparent);
  }
}

.user-card {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px 4px 4px;
  margin-left: 2px;
  border-radius: 999px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  box-shadow: none;
  transition: border-color 0.15s ease, background 0.15s ease;

  &:hover {
    border-color: var(--border-strong);
    background: var(--surface-2);
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
  background: var(--btn-primary-bg) !important;
  color: var(--btn-primary-text) !important;
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
  color: var(--primary-strong);
}

.user-role {
  font-size: 11px;
  color: var(--text-tertiary);
}

.user-caret-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  cursor: pointer;
  color: var(--text-tertiary);

  &:hover {
    background: var(--surface-3);
    color: var(--text-muted);
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

.mobile-menu-btn {
  display: none;
}

.mobile-page-title {
  display: none;
}

/* 移动端导航抽屉内容（挂在 body 下，非 scoped 覆盖用 :deep 不够，样式写在全局类） */
.mobile-nav {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.mobile-nav-group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  margin-bottom: 8px;
  padding: 0 4px;
}

.mobile-nav-group-icon {
  display: inline-flex;
  font-size: 13px;
  color: var(--text-secondary);
}

.mobile-nav-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 12px;
  margin-bottom: 6px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;

  &:hover,
  &:active {
    background: var(--surface-3);
    border-color: var(--border-color);
  }

  &.active {
    background: var(--surface-3);
    border-color: var(--border-strong);

    .mobile-nav-item-title {
      color: var(--primary-strong);
      font-weight: 650;
    }
  }
}

.mobile-nav-item-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
  background: var(--icon-soft-bg);
  color: var(--icon-soft-fg);
}

.mobile-nav-item-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.mobile-nav-item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.3;
}

.mobile-nav-item-desc {
  font-size: 12px;
  color: var(--text-tertiary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
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

/* 小屏：汉堡导航，桌面 UI 完全保留在更大断点 */
@media (max-width: 768px) {
  .header-inner {
    padding: 0 12px;
    gap: 8px;
  }

  .mobile-menu-btn {
    display: inline-flex;
    flex-shrink: 0;
  }

  .desktop-only {
    display: none !important;
  }

  .logo-text {
    display: none;
  }

  .logo-mark {
    width: 34px;
    height: 34px;
  }

  .mobile-page-title {
    display: block;
    flex: 1;
    min-width: 0;
    font-size: 14px;
    font-weight: 650;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .header-right {
    gap: 4px;
  }

  .user-card {
    padding: 2px 4px 2px 2px;
  }

  .user-main {
    padding: 0;
  }

  .user-avatar {
    width: 32px !important;
    height: 32px !important;
    line-height: 32px !important;
  }
}
</style>
