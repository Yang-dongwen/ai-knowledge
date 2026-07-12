<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <h1>登录 AI 工具台</h1>
        <p>使用注册邮箱登录</p>
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

      <div class="auth-footer">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ email: '', password: '' })

async function onSubmit() {
  loading.value = true
  try {
    await auth.login(form.email.trim(), form.password)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/video-extract'
    router.replace(redirect)
  } catch {
    // handled
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
@import './auth-shared.scss';
</style>
