<template>
  <a-modal
    :open="open"
    :footer="null"
    :width="520"
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
            <span class="label">会员状态</span>
            <span class="value">{{ memberStatusText }}</span>
          </div>
          <div class="profile-row">
            <span class="label">会员到期</span>
            <span class="value">{{ profile.memberExpireAt || '—' }}</span>
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

        <div class="blog-bind">
          <div class="blog-bind-head">关联博客</div>
          <template v-if="blogBinding?.target === 'platform'">
            <p class="blog-hint muted">{{ blogBinding.hint || '超级管理员发文到平台博客' }}</p>
            <div v-if="blogBinding.bound" class="blog-site">{{ blogBinding.siteUrl }}</div>
          </template>
          <template v-else>
            <p v-if="blogBinding?.bound" class="blog-site">
              {{ blogBinding.publicUrl || blogBinding.siteUrl }}
              <span v-if="blogBinding.haloUsername"> · {{ blogBinding.haloUsername }}</span>
              <span v-if="blogBinding.tokenMasked"> · {{ blogBinding.tokenMasked }}</span>
            </p>
            <p v-else class="blog-hint muted">未关联则无法发布。填写你自己的 Halo 站点和个人令牌。</p>
            <a-input
              v-model:value="blogBaseUrl"
              class="blog-input"
              placeholder="站点地址 https://…"
              allow-clear
            />
            <a-input
              v-model:value="blogPublicUrl"
              class="blog-input"
              placeholder="对外地址（可空，默认与站点相同）"
              allow-clear
            />
            <a-input-password
              v-model:value="blogToken"
              class="blog-input"
              :placeholder="blogBinding?.bound ? '新令牌（不改可空）' : '个人令牌 pat_…'"
            />
            <div class="blog-actions">
              <a-button type="primary" :loading="blogSaving" @click="saveBlog">保存关联</a-button>
              <a-button
                v-if="blogBinding?.bound"
                danger
                :loading="blogSaving"
                @click="removeBlog"
              >
                解除
              </a-button>
            </div>
          </template>
        </div>

        <div class="profile-footer">
          <a-button type="primary" block @click="goMember">会员中心 / 开通续费</a-button>
        </div>
      </div>

      <a-empty v-else-if="!loading" description="暂无用户信息" />
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { authApi, roleLabel, type AuthUser } from '@/api/auth.api'
import { kbApi, type HaloBinding } from '@/api/kb.api'
import { useAuthStore } from '@/stores/auth.store'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const profile = ref<AuthUser | null>(null)
const blogBinding = ref<HaloBinding | null>(null)
const blogSaving = ref(false)
const blogBaseUrl = ref('')
const blogPublicUrl = ref('')
const blogToken = ref('')

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
const memberStatusText = computed(() => {
  const r = (profile.value?.role || 'USER').toUpperCase()
  if (r === 'SUPER_ADMIN') return '超级管理员（无需购买）'
  if (profile.value?.memberActive) return '有效会员'
  if (profile.value?.memberExpireAt) return '已过期'
  return '未开通'
})

function goMember() {
  emit('update:open', false)
  router.push('/member')
}

async function saveBlog() {
  const baseUrl = blogBaseUrl.value.trim()
  if (!baseUrl) {
    message.warning('请填写站点地址')
    return
  }
  if (!blogBinding.value?.bound && !blogToken.value.trim()) {
    message.warning('请填写个人令牌')
    return
  }
  blogSaving.value = true
  try {
    const res = await kbApi.saveHaloBinding({
      baseUrl,
      publicBaseUrl: blogPublicUrl.value.trim() || undefined,
      token: blogToken.value.trim() || undefined
    })
    blogBinding.value = res.data
    blogToken.value = ''
    message.success('已保存博客关联')
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    blogSaving.value = false
  }
}

async function removeBlog() {
  blogSaving.value = true
  try {
    await kbApi.deleteHaloBinding()
    blogBinding.value = {
      bound: false,
      target: 'personal',
      hint: '请先关联博客账户'
    }
    blogToken.value = ''
    message.success('已解除关联')
  } catch (e: any) {
    message.error(e?.message || '解除失败')
  } finally {
    blogSaving.value = false
  }
}

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
    try {
      const b = await kbApi.getHaloBinding()
      blogBinding.value = b.data
      blogBaseUrl.value = b.data?.siteUrl || ''
      blogPublicUrl.value = b.data?.publicUrl || ''
      blogToken.value = ''
    } catch {
      blogBinding.value = null
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
  color: var(--primary-strong);
}

.profile-card {
  padding: 4px 0 8px;
}

.profile-footer {
  margin-top: 16px;
}

.blog-bind {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid var(--border-color);
  border-radius: 12px;
}

.blog-bind-head {
  font-weight: 650;
  font-size: 14px;
  margin-bottom: 8px;
}

.blog-hint,
.blog-site {
  font-size: 12.5px;
  margin: 0 0 10px;
  line-height: 1.5;
  word-break: break-all;
}

.blog-input {
  margin-bottom: 8px;
}

.blog-actions {
  display: flex;
  gap: 8px;
}

.muted {
  color: var(--text-secondary);
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border-radius: 12px;
  background: var(--surface-hover);
  border: 1px solid var(--border-color);
  margin-bottom: 16px;
}

.profile-avatar {
  background: var(--btn-primary-bg) !important;
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
  color: var(--primary-strong);
  line-height: 1.3;
  word-break: break-all;
}

.profile-email {
  margin-top: 2px;
  font-size: 13px;
  color: var(--text-secondary);
  word-break: break-all;
}

.profile-tags {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.profile-rows {
  border: 1px solid var(--border-subtle);
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
    background: var(--surface-2);
  }

  & + & {
    border-top: 1px solid var(--surface-3);
  }

  .label {
    color: var(--text-muted);
    flex-shrink: 0;
    min-width: 72px;
  }

  .value {
    color: var(--primary-strong);
    text-align: right;
    word-break: break-all;
  }

  .mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    color: var(--soft-accent-text);
  }
}

</style>
