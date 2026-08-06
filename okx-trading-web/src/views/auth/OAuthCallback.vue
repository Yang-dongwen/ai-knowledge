<template>
  <div class="auth-page">
    <ThemeToggle floating />
    <div class="auth-card oauth-callback-card">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <h1>{{ title }}</h1>
        <p>{{ subtitle }}</p>
      </div>
      <div v-if="loading" class="callback-loading">
        <a-spin size="large" />
      </div>
      <a-button v-else type="primary" size="large" block @click="goLogin">返回登录</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { safeAppRedirect } from '@/api/request'
import { useAuthStore } from '@/stores/auth.store'
import ThemeToggle from '@/components/ThemeToggle.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const title = ref('正在完成登录…')
const subtitle = ref('请稍候，正在校验第三方登录凭证')

const OAUTH_ERROR_MSG: Record<string, string> = {
  authorize_failed: '无法发起第三方登录',
  provider_denied: '已取消第三方授权',
  callback_failed: '第三方登录失败，请重试',
  network_failed: '无法访问 GitHub/Google（本机请开 Clash 代理，后端 auth.oauth.proxy 指向 7897）',
  email_required: '第三方账号未提供邮箱，请公开邮箱后重试',
  oauth_failed: '第三方登录失败'
}

onMounted(async () => {
  const err = route.query.oauth_error as string | undefined
  if (err) {
    loading.value = false
    title.value = '登录失败'
    subtitle.value = OAUTH_ERROR_MSG[err] || '第三方登录失败'
    message.error(subtitle.value)
    return
  }

  const ticket = route.query.ticket as string | undefined
  if (!ticket) {
    loading.value = false
    title.value = '登录失败'
    subtitle.value = '缺少登录凭证，请重新从登录页发起'
    message.error(subtitle.value)
    return
  }

  try {
    await auth.loginWithOAuthTicket(ticket)
    message.success('登录成功')
    await router.replace(safeAppRedirect(route.query.redirect, '/home'))
  } catch {
    loading.value = false
    title.value = '登录失败'
    subtitle.value = '凭证无效或已过期，请重新登录'
  }
})

function goLogin() {
  router.replace({ path: '/login' })
}
</script>

<style lang="scss" scoped>
@import './auth-shared.scss';

.oauth-callback-card {
  text-align: center;
}

.callback-loading {
  padding: 16px 0 8px;
  display: flex;
  justify-content: center;
}
</style>
