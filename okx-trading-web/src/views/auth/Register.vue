<template>
  <div class="auth-page">
    <ThemeToggle floating />
    <div class="auth-card">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <h1>注册账号</h1>
        <p>邮箱即用户名，需验证码验证</p>
      </div>

      <a-form layout="vertical" :model="form" @finish="onSubmit">
        <a-form-item label="邮箱" name="email" :rules="[{ required: true, type: 'email', message: '请输入有效邮箱' }]">
          <a-input v-model:value="form.email" size="large" placeholder="name@example.com" />
        </a-form-item>
        <a-form-item label="验证码" name="code" :rules="[{ required: true, message: '请输入验证码' }]">
          <div class="code-row">
            <a-input v-model:value="form.code" size="large" placeholder="6 位验证码" />
            <a-button size="large" :disabled="countdown > 0" :loading="sending" @click="sendCode">
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </a-button>
          </div>
        </a-form-item>
        <a-form-item
          label="密码"
          name="password"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '至少 8 位' },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '需同时包含字母和数字' }
          ]"
        >
          <a-input-password v-model:value="form.password" size="large" placeholder="至少 8 位，字母+数字" />
        </a-form-item>
        <a-form-item label="昵称（可选）" name="nickname">
          <a-input v-model:value="form.nickname" size="large" placeholder="默认取邮箱前缀" />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">
          注册并登录
        </a-button>
      </a-form>

      <div class="auth-footer">
        已有账号？
        <router-link to="/login">去登录</router-link>
      </div>
      <div class="dev-tip">开发环境验证码见后端日志 [MAIL-CONSOLE]</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { authApi } from '@/api/auth.api'
import { useAuthStore } from '@/stores/auth.store'
import ThemeToggle from '@/components/ThemeToggle.vue'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
let timer: number | null = null

const form = reactive({
  email: '',
  code: '',
  password: '',
  nickname: ''
})

async function sendCode() {
  if (!form.email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    message.warning('请先输入有效邮箱')
    return
  }
  sending.value = true
  try {
    await authApi.sendRegisterCode(form.email.trim())
    message.success('验证码已发送（开发环境请看后端日志）')
    countdown.value = 60
    timer = window.setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0 && timer) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch {
    // handled
  } finally {
    sending.value = false
  }
}

async function onSubmit() {
  loading.value = true
  try {
    await auth.register({
      email: form.email.trim(),
      password: form.password,
      code: form.code.trim(),
      nickname: form.nickname?.trim() || undefined
    })
    message.success('注册成功')
    router.replace('/video-extract')
  } catch {
    // handled
  } finally {
    loading.value = false
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style lang="scss" scoped>
@import './auth-shared.scss';
</style>
