import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: BasicLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘' }
      },
      {
        path: 'okx-config',
        name: 'OkxConfig',
        component: () => import('@/views/okx-config/index.vue'),
        meta: { title: 'OKX配置' }
      },
      {
        path: 'strategies',
        name: 'Strategies',
        component: () => import('@/views/strategy/index.vue'),
        meta: { title: '策略管理' }
      },
      {
        path: 'positions',
        name: 'Positions',
        component: () => import('@/views/position/index.vue'),
        meta: { title: '当前持仓' }
      },
      {
        path: 'trades',
        name: 'Trades',
        component: () => import('@/views/trade/index.vue'),
        meta: { title: '交易记录' }
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/order/index.vue'),
        meta: { title: '订单记录' }
      },
      {
        path: 'run-logs',
        name: 'RunLogs',
        component: () => import('@/views/run-log/index.vue'),
        meta: { title: '运行日志' }
      },
      {
        path: 'system-settings',
        name: 'SystemSettings',
        component: () => import('@/views/system-settings/index.vue'),
        meta: { title: '系统设置' }
      },
      {
        path: 'ai-chat',
        name: 'AiChat',
        component: () => import('@/views/ai-chat/index.vue'),
        meta: { title: 'AI 助手' }
      },
      {
        path: 'video-extract',
        name: 'VideoExtract',
        component: () => import('@/views/video-extract/index.vue'),
        meta: { title: '视频提取' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
