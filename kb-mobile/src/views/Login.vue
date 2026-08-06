<template>
  <div class="page no-tab login">
    <div class="hero">
      <div class="logo">KB</div>
      <h1>个人知识库</h1>
      <p class="muted">移动端 H5 · 与 PC / 小程序共用账号</p>
    </div>
    <div class="card form">
      <div class="field">
        <label>邮箱</label>
        <input v-model="email" type="email" autocomplete="username" placeholder="注册邮箱" />
      </div>
      <div class="field">
        <label>密码</label>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="登录密码"
          @keyup.enter="onLogin"
        />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <button class="btn btn-primary btn-block" :disabled="loading" @click="onLogin">
        {{ loading ? '登录中…' : '登录' }}
      </button>
      <p class="hint muted">开发环境默认代理到 http://127.0.0.1:8080</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, setSession } from '../api'
import { safeAppRedirect } from '../utils/sanitizeHtml'

const router = useRouter()
const route = useRoute()
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function onLogin() {
  error.value = ''
  if (!email.value.trim() || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    const data = await api.login(email.value.trim(), password.value)
    setSession(data.token, data.user)
    const redirect = safeAppRedirect(route.query.redirect, '/notes')
    router.replace(redirect)
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  background: linear-gradient(165deg, #0f172a 0%, #1e293b 40%, var(--bg) 40%);
  min-height: 100vh;
}

.hero {
  color: #f8fafc;
  padding: 28px 8px 20px;
}

.logo {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  margin-bottom: 14px;
}

.hero h1 {
  margin: 0 0 8px;
  font-size: 26px;
}

.form {
  padding: 20px 18px 24px;
}

.field {
  margin-bottom: 14px;
}

.field label {
  display: block;
  font-size: 13px;
  color: #64748b;
  margin-bottom: 6px;
  font-weight: 550;
}

.field input {
  width: 100%;
  color: #0f172a;
  background: #f8fafc;
}

.error {
  color: var(--danger);
  font-size: 13px;
  margin: 0 0 10px;
}

.hint {
  margin: 14px 0 0;
  text-align: center;
  font-size: 12px;
}
</style>
