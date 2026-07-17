<template>
  <a-modal
    :open="open"
    :footer="null"
    :width="420"
    :centered="true"
    destroy-on-close
    class="profile-card-modal"
    @cancel="emit('update:open', false)"
  >
    <template #title>
      <span class="modal-title">个人资料</span>
    </template>

    <a-spin :spinning="loading">
      <div v-if="profile" class="profile-card">
        <div class="profile-hero">
          <a-avatar :size="72" class="profile-avatar">{{ avatarLetter }}</a-avatar>
          <div class="profile-hero-text">
            <div class="profile-name">{{ displayName }}</div>
            <div class="profile-email">{{ profile.email }}</div>
            <div class="profile-tags">
              <a-tag :color="roleTagColor">{{ displayRole }}</a-tag>
              <a-tag :color="profile.emailVerified ? 'success' : 'default'">
                {{ profile.emailVerified ? '邮箱已验证' : '邮箱未验证' }}
              </a-tag>
              <a-tag :color="statusOk ? 'processing' : 'error'">
                {{ statusOk ? '正常' : '已禁用' }}
              </a-tag>
            </div>
          </div>
        </div>

        <div class="profile-rows">
          <div class="profile-row">
            <span class="label">用户 ID</span>
            <span class="value mono">{{ profile.id }}</span>
          </div>
          <div class="profile-row">
            <span class="label">昵称</span>
            <span class="value">{{ profile.nickname || '—' }}</span>
          </div>
          <div class="profile-row">
            <span class="label">登录邮箱</span>
            <span class="value">{{ profile.email }}</span>
          </div>
          <div class="profile-row">
            <span class="label">角色权限</span>
            <span class="value">{{ displayRole }}</span>
          </div>
          <div class="profile-row">
            <span class="label">账号状态</span>
            <span class="value">{{ statusOk ? '正常' : '已禁用' }}</span>
          </div>
          <div class="profile-row">
            <span class="label">最近登录</span>
            <span class="value">{{ profile.lastLoginAt || '—' }}</span>
          </div>
          <div class="profile-row">
            <span class="label">注册时间</span>
            <span class="value">{{ profile.createdAt || '—' }}</span>
          </div>
        </div>
      </div>

      <a-empty v-else-if="!loading" description="暂无用户信息" />
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { authApi, roleLabel, type AuthUser } from '@/api/auth.api'
import { useAuthStore } from '@/stores/auth.store'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

const auth = useAuthStore()
const loading = ref(false)
const profile = ref<AuthUser | null>(null)

const displayName = computed(
  () => profile.value?.nickname || profile.value?.email || '用户'
)
const avatarLetter = computed(() => {
  const n = displayName.value
  return n ? n.charAt(0).toUpperCase() : 'U'
})
const statusOk = computed(
  () => profile.value?.status == null || profile.value.status === 1
)
const displayRole = computed(() =>
  roleLabel(profile.value?.role, profile.value?.roleLabel)
)
const roleTagColor = computed(() => {
  const r = (profile.value?.role || 'USER').toUpperCase()
  if (r === 'SUPER_ADMIN') return 'purple'
  if (r === 'MEMBER') return 'gold'
  return 'blue'
})

watch(
  () => props.open,
  async (v) => {
    if (!v) return
    await loadProfile()
  }
)

async function loadProfile() {
  loading.value = true
  try {
    // 走后端 GET /auth/me 查库（非本地缓存）
    const res = await authApi.me()
    profile.value = res.data
    if (res.data) {
      auth.user = res.data
      localStorage.setItem('okx_auth_user', JSON.stringify(res.data))
    }
  } catch (e: any) {
    // 失败时展示本地缓存，不强制登出（401 由 request 拦截器处理）
    profile.value = auth.user
    if (!auth.user) {
      message.error(e?.message || '获取用户信息失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.modal-title {
  font-weight: 600;
  font-size: 16px;
  color: #111827;
}

.profile-card {
  padding: 4px 0 8px;
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border-radius: 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  margin-bottom: 16px;
}

.profile-avatar {
  background: #1f2937 !important;
  font-size: 28px;
  font-weight: 700;
  flex-shrink: 0;
  box-shadow: none;
}

.profile-hero-text {
  min-width: 0;
  flex: 1;
}

.profile-name {
  font-size: 18px;
  font-weight: 700;
  color: #111827;
  line-height: 1.3;
  word-break: break-all;
}

.profile-email {
  margin-top: 2px;
  font-size: 13px;
  color: #6b7280;
  word-break: break-all;
}

.profile-tags {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.profile-rows {
  border: 1px solid #f3f4f6;
  border-radius: 12px;
  overflow: hidden;
}

.profile-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 14px;
  font-size: 13px;

  &:nth-child(odd) {
    background: #fafafa;
  }

  & + & {
    border-top: 1px solid #f3f4f6;
  }

  .label {
    color: #9ca3af;
    flex-shrink: 0;
    min-width: 72px;
  }

  .value {
    color: #111827;
    text-align: right;
    word-break: break-all;
  }

  .mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    color: #4b5563;
  }
}
</style>
