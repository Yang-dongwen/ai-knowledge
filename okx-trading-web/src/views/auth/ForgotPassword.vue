<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <h1>找回密码</h1>
        <p>通过注册邮箱接收验证码并设置新密码</p>
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
          label="新密码"
          name="newPassword"
          :rules="[
            { required: true, message: '请输入新密码' },
            { min: 8, message: '至少 8 位' },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '需同时包含字母和数字' }
          ]"
        >
          <a-input-password v-model:value="form.newPassword" size="large" placeholder="至少 8 位，字母+数字" />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">
          重置密码
        </a-button>
      </a-form>

      <div class="auth-footer">
        <router-link to="/login">返回登录</router-link>
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

const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
let timer: number | null = null

const form = reactive({
  email: '',
  code: '',
  newPassword: ''
})

async function sendCode() {
  if (!form.email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    message.warning('请先输入有效邮箱')
    return
  }
  sending.value = true
  try {
    await authApi.sendResetCode(form.email.trim())
    message.success('若邮箱已注册，验证码将发送（开发环境请看后端日志）')
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
    await authApi.resetPassword({
      email: form.email.trim(),
      code: form.code.trim(),
      newPassword: form.newPassword
    })
    message.success('密码已重置，请登录')
    router.replace('/login')
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
