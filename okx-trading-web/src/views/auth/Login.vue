<template>
  <div class="auth-page">
    <ThemeToggle floating />
    <div class="auth-card">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <h1>登录 AI 工具台</h1>
        <p>使用注册邮箱或第三方账号登录</p>
      </div>

      <a-form layout="vertical" :model="form" @finish="onSubmit">
        <a-form-item label="邮箱" name="email" :rules="[{ required: true, type: 'email', message: '请输入有效邮箱' }]">
          <a-input v-model:value="form.email" size="large" placeholder="name@example.com" autocomplete="username" />
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" size="large" placeholder="密码" autocomplete="current-password" />
        </a-form-item>
        <div class="form-extra">
          <router-link to="/forgot-password">忘记密码？</router-link>
        </div>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">
          登录
        </a-button>
      </a-form>

      <template v-if="oauthProviders.length">
        <div class="oauth-divider">
          <span>或</span>
        </div>
        <div class="oauth-actions">
          <a-button
            v-if="oauthProviders.includes('google')"
            size="large"
            block
            class="oauth-btn"
            :loading="oauthLoading === 'google'"
            @click="startOAuth('google')"
          >
            <span class="oauth-icon" aria-hidden="true">G</span>
            使用 Google 登录
          </a-button>
          <a-button
            v-if="oauthProviders.includes('github')"
            size="large"
            block
            class="oauth-btn"
            :loading="oauthLoading === 'github'"
            @click="startOAuth('github')"
          >
            <svg class="oauth-icon github" viewBox="0 0 98 96" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <path
                fill-rule="evenodd"
                clip-rule="evenodd"
                fill="currentColor"
                d="M48.854 0C21.839 0 0 22 0 49.217c0 21.756 13.993 40.172 33.405 46.69 2.427.49 3.316-1.059 3.316-2.362 0-1.141-.08-5.052-.08-9.127-13.59 2.934-16.42-5.867-16.42-5.867-2.184-5.704-5.42-7.17-5.42-7.17-4.448-3.015.324-3.015.324-3.015 4.934.326 7.523 5.052 7.523 5.052 4.367 8.052 11.404 5.378 14.235 4.074.404-3.178 1.699-5.378 3.074-6.6-10.839-1.141-22.243-5.378-22.243-24.283 0-5.378 1.94-9.778 5.014-13.2-.485-1.222-2.184-6.275.486-13.038 0 0 4.125-1.304 13.426 5.052a46.97 46.97 0 0 1 12.214-1.63c4.125 0 8.33.571 12.213 1.63 9.302-6.356 13.427-5.052 13.427-5.052 2.67 6.763.97 11.816.485 13.038 3.155 3.422 5.015 7.822 5.015 13.2 0 18.905-11.404 23.06-22.324 24.283 1.78 1.548 3.316 4.481 3.316 9.126 0 6.6-.08 11.897-.08 13.526 0 1.304.89 2.853 3.316 2.364 19.412-6.52 33.405-24.935 33.405-46.691C97.707 22 75.788 0 48.854 0z"
              />
            </svg>
            使用 GitHub 登录
          </a-button>
        </div>
        <p v-if="oauthMock" class="oauth-mock-hint">开发模式：第三方登录为 mock，不会跳转 Google/GitHub</p>
      </template>

      <div class="auth-footer">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { authApi, oauthAuthorizeUrl } from '@/api/auth.api'
import { safeAppRedirect } from '@/api/request'
import { useAuthStore } from '@/stores/auth.store'
import ThemeToggle from '@/components/ThemeToggle.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const oauthLoading = ref<string | null>(null)
const oauthProviders = ref<string[]>([])
const oauthMock = ref(false)
const form = reactive({ email: '', password: '' })

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
    message.error(OAUTH_ERROR_MSG[err] || '第三方登录失败')
  }
  try {
    const res = await authApi.listOAuthProviders()
    oauthProviders.value = res.data?.providers || []
    oauthMock.value = !!res.data?.mock
  } catch {
    oauthProviders.value = []
  }
})

async function onSubmit() {
  loading.value = true
  try {
    await auth.login(form.email.trim(), form.password)
    message.success('登录成功')
    router.replace(safeAppRedirect(route.query.redirect, '/home'))
  } catch {
    // handled
  } finally {
    loading.value = false
  }
}

function startOAuth(provider: string) {
  oauthLoading.value = provider
  const redirect = safeAppRedirect(route.query.redirect, '/home')
  // 整页跳转；本地 vite 代理 /api → 8080（OAuth 授权页由另一工作流维护，此处仅安全 redirect）
  window.location.href = oauthAuthorizeUrl(provider, redirect)
}
</script>

<style lang="scss" scoped>
@import './auth-shared.scss';

.oauth-divider {
  display: flex;
  align-items: center;
  margin: 20px 0 14px;
  color: var(--text-secondary);
  font-size: 12px;

  &::before,
  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--border-color);
  }

  span {
    padding: 0 12px;
  }
}

.oauth-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.oauth-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-weight: 500;
}

.oauth-icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  background: #fff;
  color: #4285f4;
  border: 1px solid var(--border-color);
  flex-shrink: 0;

  &.github {
    background: transparent;
    border: none;
    border-radius: 0;
    color: var(--text-primary);
    padding: 0;
  }
}

.oauth-mock-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
  line-height: 1.4;
}
</style>
