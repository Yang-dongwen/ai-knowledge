<template>
  <div class="user-manage-page">
    <div class="page-header-row">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-subtitle">系统管理 · 查询用户并启用/禁用账号</p>
      </div>
      <a-button @click="load" :loading="loading">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <div class="page-card filters">
      <a-space wrap :size="12">
        <a-input
          v-model:value="filters.keyword"
          allow-clear
          placeholder="邮箱 / 昵称"
          style="width: 220px"
          @press-enter="onSearch"
        />
        <a-select
          v-model:value="filters.role"
          allow-clear
          placeholder="角色"
          style="width: 150px"
          :options="roleOptions"
        />
        <a-select
          v-model:value="filters.status"
          allow-clear
          placeholder="状态"
          style="width: 120px"
          :options="statusOptions"
        />
        <a-button type="primary" @click="onSearch">查询</a-button>
        <a-button @click="onReset">重置</a-button>
      </a-space>
    </div>

    <div class="page-card">
      <a-table
        :columns="columns"
        :data-source="list"
        :loading="loading"
        row-key="id"
        size="middle"
        :pagination="pagination"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'user'">
            <div class="user-cell">
              <a-avatar :size="32" class="avatar">{{ avatarLetter(record) }}</a-avatar>
              <div class="user-cell-text">
                <div class="nick">{{ record.nickname || '—' }}</div>
                <div class="email">{{ record.email }}</div>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'role'">
            <a-tag :color="roleColor(record.role)">
              {{ roleLabel(record.role, record.roleLabel) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'emailVerified'">
            <a-tag :color="record.emailVerified ? 'success' : 'default'">
              {{ record.emailVerified ? '已验证' : '未验证' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'processing' : 'error'">
              {{ record.status === 1 ? '正常' : '已禁用' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button
                v-if="record.status === 1"
                type="link"
                danger
                size="small"
                :disabled="isSelf(record)"
                :loading="actingId === record.id"
                @click="onDisable(record)"
              >
                禁用
              </a-button>
              <a-button
                v-else
                type="link"
                size="small"
                :disabled="isSelf(record)"
                :loading="actingId === record.id"
                @click="onEnable(record)"
              >
                启用
              </a-button>
              <span v-if="isSelf(record)" class="self-tip">当前账号</span>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '@/api/admin.api'
import { roleLabel, type AuthUser } from '@/api/auth.api'
import { useAuthStore } from '@/stores/auth.store'

const auth = useAuthStore()
const loading = ref(false)
const list = ref<AuthUser[]>([])
const actingId = ref('')
const total = ref(0)

const filters = reactive({
  keyword: '',
  role: undefined as string | undefined,
  status: undefined as number | undefined
})

const pageState = reactive({
  page: 0,
  size: 20
})

const roleOptions = [
  { value: 'USER', label: '普通用户' },
  { value: 'MEMBER', label: '会员' },
  { value: 'SUPER_ADMIN', label: '超级管理员' }
]

const statusOptions = [
  { value: 1, label: '正常' },
  { value: 0, label: '已禁用' }
]

const columns = [
  { title: '用户', key: 'user', width: 260 },
  { title: '角色', key: 'role', width: 120 },
  { title: '邮箱验证', key: 'emailVerified', width: 100 },
  { title: '状态', key: 'status', width: 90 },
  { title: '最近登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', width: 170 },
  { title: '注册时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' as const }
]

const pagination = computed(() => ({
  current: pageState.page + 1,
  pageSize: pageState.size,
  total: total.value,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 人`,
  pageSizeOptions: ['10', '20', '50']
}))

function avatarLetter(u: AuthUser) {
  const n = u.nickname || u.email || 'U'
  return n.charAt(0).toUpperCase()
}

function roleColor(role?: string) {
  const r = (role || 'USER').toUpperCase()
  if (r === 'SUPER_ADMIN') return 'purple'
  if (r === 'MEMBER') return 'gold'
  return 'blue'
}

function isSelf(u: AuthUser) {
  return !!auth.user?.id && u.id === auth.user.id
}

async function load() {
  loading.value = true
  try {
    const res = await adminApi.listUsers({
      page: pageState.page,
      size: pageState.size,
      keyword: filters.keyword || undefined,
      role: filters.role || undefined,
      status: filters.status === undefined || filters.status === null ? undefined : filters.status
    })
    list.value = res.data?.items || []
    total.value = res.data?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pageState.page = 0
  load()
}

function onReset() {
  filters.keyword = ''
  filters.role = undefined
  filters.status = undefined
  pageState.page = 0
  load()
}

function onTableChange(pag: { current?: number; pageSize?: number }) {
  pageState.page = (pag.current || 1) - 1
  pageState.size = pag.pageSize || 20
  load()
}

function onDisable(record: AuthUser) {
  Modal.confirm({
    title: '禁用账号',
    content: `确定禁用用户「${record.nickname || record.email}」？禁用后将无法登录。`,
    okText: '禁用',
    okType: 'danger',
    cancelText: '取消',
    onOk: () => setStatus(record, 0)
  })
}

function onEnable(record: AuthUser) {
  Modal.confirm({
    title: '启用账号',
    content: `确定启用用户「${record.nickname || record.email}」？`,
    okText: '启用',
    cancelText: '取消',
    onOk: () => setStatus(record, 1)
  })
}

async function setStatus(record: AuthUser, status: 0 | 1) {
  actingId.value = record.id
  try {
    const res = await adminApi.updateUserStatus(record.id, status)
    const updated = res.data
    const idx = list.value.findIndex((u) => u.id === record.id)
    if (idx >= 0 && updated) {
      list.value[idx] = { ...list.value[idx], ...updated }
    } else {
      await load()
    }
    message.success(status === 1 ? '已启用' : '已禁用')
  } finally {
    actingId.value = ''
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.user-manage-page {
  padding: 4px 0 24px;
}

.page-header-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--primary-strong);
}

.page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

.page-card {
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.filters {
  padding-top: 14px;
  padding-bottom: 14px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  background: var(--btn-primary-bg) !important;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-cell-text {
  min-width: 0;
}

.nick {
  font-weight: 600;
  color: var(--primary-strong);
  line-height: 1.3;
}

.email {
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
}

.self-tip {
  font-size: 12px;
  color: var(--text-muted);
}

</style>
