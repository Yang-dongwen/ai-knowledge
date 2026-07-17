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
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'Home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '工作台', group: 'home' }
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
      },
      {
        path: 'ai-chat',
        name: 'AiChat',
        component: () => import('@/views/ai-chat/index.vue'),
        meta: { title: 'AI 对话', group: 'tools', immersive: true }
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
    return { path: '/home' }
  }
  if (to.meta?.requiresSuperAdmin && auth.isLoggedIn && !auth.isSuperAdmin) {
    if (!auth.user?.role) {
      await auth.fetchMe()
    }
    if (!auth.isSuperAdmin) {
      return { path: '/home' }
    }
  }
  return true
})

router.afterEach((to) => {
  const pageTitle = (to.meta?.title as string | undefined) || ''
  document.title = pageTitle ? `${pageTitle} · ${APP_TITLE}` : APP_TITLE
  document.documentElement.classList.toggle('immersive-page', to.meta?.immersive === true)
})

export default router
