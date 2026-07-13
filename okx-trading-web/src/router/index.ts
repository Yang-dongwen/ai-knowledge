import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useAuthStore } from '@/stores/auth.store'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/auth/Register.vue'),
    meta: { title: '注册', public: true }
  },
  {
    path: '/forgot-password',
    name: 'ForgotPassword',
    component: () => import('@/views/auth/ForgotPassword.vue'),
    meta: { title: '找回密码', public: true }
  },
  {
    path: '/',
    component: BasicLayout,
    redirect: '/video-extract',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'okx-config',
        name: 'OkxConfig',
        component: () => import('@/views/okx-config/index.vue'),
        meta: { title: 'OKX配置', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'strategies',
        name: 'Strategies',
        component: () => import('@/views/strategy/index.vue'),
        meta: { title: '策略管理', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'positions',
        name: 'Positions',
        component: () => import('@/views/position/index.vue'),
        meta: { title: '当前持仓', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'trades',
        name: 'Trades',
        component: () => import('@/views/trade/index.vue'),
        meta: { title: '交易记录', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/order/index.vue'),
        meta: { title: '订单记录', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'run-logs',
        name: 'RunLogs',
        component: () => import('@/views/run-log/index.vue'),
        meta: { title: '运行日志', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'system-settings',
        name: 'SystemSettings',
        component: () => import('@/views/system-settings/index.vue'),
        meta: { title: '系统设置', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'ai-chat',
        name: 'AiChat',
        component: () => import('@/views/ai-chat/index.vue'),
        meta: { title: 'AI 助手', group: 'trading', requiresSuperAdmin: true }
      },
      {
        path: 'user-manage',
        name: 'UserManage',
        component: () => import('@/views/user-manage/index.vue'),
        meta: { title: '用户管理', group: 'admin', requiresSuperAdmin: true }
      },
      {
        path: 'video-extract',
        name: 'VideoExtract',
        component: () => import('@/views/video-extract/index.vue'),
        meta: { title: '视频提取', group: 'tools' }
      },
      {
        path: 'video-generate',
        name: 'VideoGenerate',
        component: () => import('@/views/video-generate/index.vue'),
        meta: { title: 'AI 视频生成', group: 'tools' }
      },
      {
        path: 'image-generate',
        name: 'ImageGenerate',
        component: () => import('@/views/image-generate/index.vue'),
        meta: { title: 'AI 文生图', group: 'tools' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

const APP_TITLE = 'AI 工具台'

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const isPublic = to.meta?.public === true
  if (!isPublic && !auth.isLoggedIn) {
    return {
      path: '/login',
      query: { redirect: to.fullPath }
    }
  }
  if (isPublic && auth.isLoggedIn && (to.path === '/login' || to.path === '/register')) {
    return { path: '/video-extract' }
  }
  // 交易管理等超管页面：无权限则回工具页
  if (to.meta?.requiresSuperAdmin && auth.isLoggedIn && !auth.isSuperAdmin) {
    // 尝试刷新一次角色（避免 localStorage 旧数据）
    if (!auth.user?.role) {
      await auth.fetchMe()
    }
    if (!auth.isSuperAdmin) {
      return { path: '/video-extract' }
    }
  }
  return true
})

router.afterEach((to) => {
  const pageTitle = (to.meta?.title as string | undefined) || ''
  document.title = pageTitle ? `${pageTitle} · ${APP_TITLE}` : APP_TITLE
})

export default router
