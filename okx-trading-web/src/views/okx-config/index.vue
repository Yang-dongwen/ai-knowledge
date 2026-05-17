<template>
  <div class="okx-config-page">
    <div class="page-header-row">
      <div>
        <h2 class="page-title">OKX配置</h2>
        <p class="page-subtitle">配置并管理您的 OKX API 连接信息</p>
      </div>
    </div>

    <a-row :gutter="16">
      <!-- 左侧：API 连接配置 -->
      <a-col :span="14">
        <div class="page-card">
          <div class="card-title">API 连接配置</div>
          <a-form :model="form" layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
            <a-form-item label="API Key">
              <a-input-password v-model:value="form.apiKey" placeholder="请输入 OKX API Key" />
            </a-form-item>
            <a-form-item label="Secret Key">
              <a-input-password v-model:value="form.secretKey" placeholder="请输入 OKX Secret Key" />
            </a-form-item>
            <a-form-item label="Passphrase">
              <a-input-password v-model:value="form.passphrase" placeholder="请输入 OKX Passphrase" />
            </a-form-item>
            <a-form-item label="是否模拟盘">
              <a-switch v-model:checked="isSimulated" />
              <span class="switch-label">{{ isSimulated ? '是（模拟盘环境）' : '否（实盘环境）' }}</span>
            </a-form-item>
            <a-form-item label="连接状态">
              <span class="conn-status">
                <span class="status-dot" :class="config?.status === 'ENABLED' ? 'dot-green' : 'dot-red'" />
                {{ config?.status === 'ENABLED' ? '已连接' : '未连接' }}
              </span>
            </a-form-item>
            <a-form-item label="最近检测时间">
              {{ config?.lastCheckAt ? formatTime(config.lastCheckAt) : '—' }}
            </a-form-item>
            <a-form-item label="最近错误信息">
              {{ config?.lastError || '—' }}
            </a-form-item>
            <a-form-item :wrapper-col="{ offset: 5, span: 18 }">
              <a-space :size="12">
                <a-button type="primary" @click="handleSave" :loading="saving">
                  💾 保存配置
                </a-button>
                <a-button @click="handleTestConnection" :loading="testing">
                  (•)) 测试连接
                </a-button>
                <a-button @click="handleQueryBalance" :loading="querying">
                  💰 查询余额
                </a-button>
              </a-space>
            </a-form-item>
          </a-form>
        </div>
      </a-col>

      <!-- 右侧：账户状态 -->
      <a-col :span="10">
        <div class="page-card">
          <div class="card-title">账户状态</div>
          <div class="account-status">
            <div class="status-row">
              <span class="label">账户模式</span>
              <a-tag :color="config?.simulated === 1 ? 'blue' : 'red'">{{ config?.simulated === 1 ? '模拟盘' : '实盘' }}</a-tag>
            </div>
            <div class="status-row">
              <span class="label">连接状态</span>
              <span class="conn-status" :style="{ color: config?.status === 'ENABLED' ? 'var(--success-color)' : 'var(--danger-color)' }">
                <span class="status-dot" :class="config?.status === 'ENABLED' ? 'dot-green' : 'dot-red'" />
                {{ config?.status === 'ENABLED' ? '已连接' : '未连接' }}
              </span>
            </div>
            <div class="balance-section">
              <div class="balance-label">可用余额 (USDT)</div>
              <div class="balance-value">{{ balanceInfo || '--' }}</div>
              <div class="balance-sub" v-if="balanceNum > 0">≈ ¥{{ formatAmount(balanceNum * 7.15, 2) }}</div>
            </div>
          </div>
        </div>

        <div class="page-card">
          <div class="card-title">API 权限</div>
          <div class="permission-list">
            <span class="perm-item success">✓ 读取账户</span>
            <span class="perm-item success">✓ 读取行情</span>
            <span class="perm-item success">✓ 交易下单</span>
            <span class="perm-item danger">✗ 提币</span>
          </div>
        </div>

        <div class="page-card">
          <div class="card-title">🔒 安全提示</div>
          <a-alert type="info" :show-icon="false">
            <template #message>
              <div style="font-size: 13px; line-height: 1.8;">
                请确保您的 API Key 仅用于自动交易，不要泄露给他人。<br/>
                建议开启 IP 白名单限制以提高账户安全性。
              </div>
            </template>
          </a-alert>
        </div>
      </a-col>
    </a-row>

    <!-- 最近连接测试日志 -->
    <div class="page-card">
      <div class="card-header">
        <span class="card-title">最近连接测试日志</span>
        <a class="more-link">查看更多 ›</a>
      </div>
      <a-table
        :columns="logColumns"
        :data-source="checkLogs"
        :pagination="{ pageSize: 5, size: 'small' }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <span :style="{ color: record.success ? 'var(--success-color)' : 'var(--danger-color)' }">
              ● {{ record.success ? '成功' : '失败' }}
            </span>
          </template>
          <template v-if="column.key === 'mode'">
            <a-tag color="blue" size="small">模拟盘</a-tag>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { okxApi } from '@/api/okx.api'
import { formatTime, formatAmount } from '@/utils/format'
import type { OkxConfig } from '@/types/api'

const form = reactive({
  apiKey: '',
  secretKey: '',
  passphrase: ''
})
const isSimulated = ref(true)

const config = ref<OkxConfig | null>(null)
const balanceInfo = ref('')
const balanceNum = ref(0)
const saving = ref(false)
const testing = ref(false)
const querying = ref(false)

const logColumns = [
  { title: '检测时间', dataIndex: 'time', key: 'time' },
  { title: '连接状态', key: 'status' },
  { title: '延迟', dataIndex: 'latency', key: 'latency' },
  { title: '账户模式', key: 'mode' },
  { title: '可用余额 (USDT)', dataIndex: 'balance', key: 'balance' },
  { title: '错误信息', dataIndex: 'error', key: 'error' },
  { title: '操作', key: 'action' }
]

const checkLogs = ref<any[]>([])

onMounted(async () => {
  try {
    const res = await okxApi.getConfig()
    config.value = (res as any).data
  } catch { /* 未配置 */ }
})

async function handleSave() {
  saving.value = true
  try {
    await okxApi.saveConfig({ ...form, simulated: isSimulated.value ? 1 : 0 })
    message.success('配置保存成功')
    const res = await okxApi.getConfig()
    config.value = (res as any).data
  } finally { saving.value = false }
}

async function handleTestConnection() {
  testing.value = true
  try {
    await okxApi.testConnection()
    message.success('连接测试成功')
    const res = await okxApi.getConfig()
    config.value = (res as any).data
  } finally { testing.value = false }
}

async function handleQueryBalance() {
  querying.value = true
  try {
    const res = await okxApi.getBalance()
    const details = (res as any).data?.[0]?.details || []
    const usdt = details.find((d: any) => d.ccy === 'USDT')
    balanceNum.value = usdt ? parseFloat(usdt.availBal) : 0
    balanceInfo.value = usdt ? `${parseFloat(usdt.availBal).toLocaleString()}` : '0'
    message.success('余额查询成功')
  } finally { querying.value = false }
}
</script>

<style lang="scss" scoped>
.okx-config-page {
  .page-header-row {
    margin-bottom: 20px;
  }

  .card-title {
    font-size: 15px;
    font-weight: 600;
    margin-bottom: 20px;
    color: var(--text-primary);
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
  }

  .more-link {
    font-size: 13px;
    color: var(--primary-color);
    cursor: pointer;
  }

  .switch-label {
    margin-left: 10px;
    font-size: 13px;
    color: var(--text-secondary);
  }

  .conn-status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    color: var(--success-color);
  }

  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;

    &.dot-green { background: var(--success-color); }
    &.dot-red { background: var(--danger-color); }
  }

  .account-status {
    .status-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 0;

      .label {
        color: var(--text-secondary);
        font-size: 13px;
      }
    }

    .balance-section {
      margin-top: 16px;
      padding-top: 16px;
      border-top: 1px solid var(--border-color);

      .balance-label {
        font-size: 13px;
        color: var(--text-secondary);
        margin-bottom: 6px;
      }

      .balance-value {
        font-size: 28px;
        font-weight: 700;
        color: var(--text-primary);
      }

      .balance-sub {
        font-size: 12px;
        color: var(--text-muted);
        margin-top: 4px;
      }
    }
  }

  .permission-list {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;

    .perm-item {
      font-size: 13px;

      &.success { color: var(--success-color); }
      &.danger { color: var(--danger-color); }
    }
  }
}
</style>
