import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: BasicLayout,
    redirect: '/video-extract',
    children: [
      // ---------- 交易管理 ----------
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘', group: 'trading' }
      },
      {
        path: 'okx-config',
        name: 'OkxConfig',
        component: () => import('@/views/okx-config/index.vue'),
        meta: { title: 'OKX配置', group: 'trading' }
      },
      {
        path: 'strategies',
        name: 'Strategies',
        component: () => import('@/views/strategy/index.vue'),
        meta: { title: '策略管理', group: 'trading' }
      },
      {
        path: 'positions',
        name: 'Positions',
        component: () => import('@/views/position/index.vue'),
        meta: { title: '当前持仓', group: 'trading' }
      },
      {
        path: 'trades',
        name: 'Trades',
        component: () => import('@/views/trade/index.vue'),
        meta: { title: '交易记录', group: 'trading' }
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/order/index.vue'),
        meta: { title: '订单记录', group: 'trading' }
      },
      {
        path: 'run-logs',
        name: 'RunLogs',
        component: () => import('@/views/run-log/index.vue'),
        meta: { title: '运行日志', group: 'trading' }
      },
      {
        path: 'system-settings',
        name: 'SystemSettings',
        component: () => import('@/views/system-settings/index.vue'),
        meta: { title: '系统设置', group: 'trading' }
      },
      {
        path: 'ai-chat',
        name: 'AiChat',
        component: () => import('@/views/ai-chat/index.vue'),
        meta: { title: 'AI 助手', group: 'trading' }
      },
      // ---------- 工具使用 ----------
      {
        path: 'video-extract',
        name: 'VideoExtract',
        component: () => import('@/views/video-extract/index.vue'),
        meta: { title: '视频提取', group: 'tools' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

const APP_TITLE = 'AI 工具台'

router.afterEach((to) => {
  const pageTitle = (to.meta?.title as string | undefined) || ''
  document.title = pageTitle ? `${pageTitle} · ${APP_TITLE}` : APP_TITLE
})

export default router
