<template>
  <div class="page">
    <header class="top"><h1>我的</h1></header>

    <div class="card profile">
      <div class="avatar">{{ avatarLetter }}</div>
      <div>
        <div class="name">{{ user?.nickname || user?.email || '用户' }}</div>
        <div class="muted email">{{ user?.email || '' }}</div>
      </div>
    </div>

    <div class="card menu">
      <button type="button" class="menu-item" @click="$router.push('/folders')">
        <span>文件夹</span><span class="muted">›</span>
      </button>
      <button type="button" class="menu-item" @click="$router.push('/tags')">
        <span>标签管理</span><span class="muted">›</span>
      </button>
    </div>

    <div v-if="showApiBase" class="card section">
      <div class="section-title">API 根地址（仅开发）</div>
      <p class="muted hint">
        留空 = 使用开发代理（推荐本地）。填完整地址仅在跨域已放行时使用。
      </p>
      <input v-model="baseUrlInput" class="base" placeholder="留空则同源 /api 代理" />
      <div class="row-btns">
        <button class="btn btn-ghost" @click="resetBase">恢复默认</button>
        <button class="btn btn-primary" @click="saveBase">保存</button>
      </div>
      <p class="muted small">当前：{{ baseDisplay }}</p>
    </div>

    <div class="card section">
      <div class="row">
        <span>版本</span>
        <span class="muted">0.7.0 H5</span>
      </div>
      <div class="row">
        <span>说明</span>
        <span class="muted">与小程序 Phase M1/M2 能力对齐</span>
      </div>
    </div>

    <button class="btn btn-block logout" @click="logout">退出登录</button>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  api,
  clearSession,
  getBaseUrl,
  getUser,
  setBaseUrl,
  setSession
} from '../api'

const router = useRouter()
const user = ref(getUser())
const showApiBase = import.meta.env.DEV === true
const baseUrlInput = ref(localStorage.getItem('kb_base_url') || '')
const baseDisplay = ref(getBaseUrl() || '(同源代理 /api → :8080)')

const avatarLetter = computed(() => {
  const n = user.value?.nickname || user.value?.email || 'U'
  return n.charAt(0).toUpperCase()
})

async function refreshMe() {
  try {
    const me = await api.me()
    setSession(localStorage.getItem('kb_token') || '', me)
    user.value = me
  } catch {
    /* ignore */
  }
}

function saveBase() {
  baseDisplay.value = setBaseUrl(baseUrlInput.value)
  baseUrlInput.value = localStorage.getItem('kb_base_url') || ''
  alert('已保存')
}

function resetBase() {
  setBaseUrl('')
  baseUrlInput.value = ''
  baseDisplay.value = '(同源代理 /api → :8080)'
}

function logout() {
  if (!confirm('确定退出？')) return
  clearSession()
  router.replace('/login')
}

onMounted(refreshMe)
</script>

<style scoped>
.top h1 {
  margin: 4px 0 12px;
  font-size: 22px;
}

.profile {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 16px;
  margin-bottom: 12px;
}

.avatar {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--primary);
  color: #fff;
  font-weight: 700;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.name {
  font-weight: 700;
  font-size: 17px;
}

.email {
  font-size: 13px;
}

.menu {
  margin-bottom: 12px;
  overflow: hidden;
}

.menu-item {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border: none;
  border-bottom: 1px solid #f1f5f9;
  background: #fff;
  font-size: 15px;
  font-weight: 550;
  cursor: pointer;
}

.menu-item:last-child {
  border-bottom: none;
}

.section {
  padding: 16px;
  margin-bottom: 12px;
}

.section-title {
  font-weight: 650;
  margin-bottom: 6px;
}

.hint {
  font-size: 12px;
  margin: 0 0 10px;
}

.base {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  background: #f8fafc;
  margin-bottom: 10px;
}

.row-btns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 8px;
}

.small {
  font-size: 12px;
  margin: 0;
  word-break: break-all;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  font-size: 14px;
  border-bottom: 1px solid #f1f5f9;
}

.row:last-child {
  border-bottom: none;
}

.logout {
  margin-top: 20px;
  background: #fff;
  color: var(--danger);
  border: 1px solid #fecaca;
  font-weight: 650;
  padding: 12px;
  border-radius: 12px;
  cursor: pointer;
}
</style>
