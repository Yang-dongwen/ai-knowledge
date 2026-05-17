<template>
  <div class="order-page">
    <div class="page-header-row">
      <h2 class="page-title">订单记录</h2>
      <span class="update-time">◎ 更新于：{{ currentTime }}</span>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">总订单数</div>
        <div class="stat-value">{{ orders.length }}</div>
        <div class="stat-sub">全部订单</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">成交单</div>
        <div class="stat-value">{{ filledCount }}</div>
        <div class="stat-sub">成交率 {{ orders.length > 0 ? ((filledCount / orders.length) * 100).toFixed(1) : 0 }}%</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">失败订单</div>
        <div class="stat-value">{{ failedCount }}</div>
        <div class="stat-sub">失败率 {{ orders.length > 0 ? ((failedCount / orders.length) * 100).toFixed(1) : 0 }}%</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">买入金额 (USDT)</div>
        <div class="stat-value">{{ formatAmount(buyTotal) }}</div>
        <div class="stat-sub">买入订单</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">卖出金额 (USDT)</div>
        <div class="stat-value">{{ formatAmount(sellTotal) }}</div>
        <div class="stat-sub">卖出订单</div>
      </div>
    </div>

    <div class="page-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <a-space :size="12">
          <a-select v-model:value="filters.symbol" placeholder="交易对" allow-clear style="width: 130px" :options="SYMBOL_OPTIONS" />
          <a-select v-model:value="filters.status" placeholder="状态" allow-clear style="width: 120px">
            <a-select-option v-for="(item, key) in ORDER_STATUS_MAP" :key="key" :value="key">{{ item.label }}</a-select-option>
          </a-select>
          <a-range-picker style="width: 320px" />
        </a-space>
        <a-space>
          <a-input-search placeholder="搜索订单号 / OKX订单ID" style="width: 220px" />
          <a-button type="primary" @click="fetchOrders">查询</a-button>
        </a-space>
      </div>

      <!-- 订单表格 -->
      <a-table
        :columns="columns"
        :data-source="orders"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, size: 'small', showTotal: (total: number) => `共 ${total} 条` }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'side'">
            <a-tag :color="record.side === 'BUY' ? 'green' : 'red'" size="small">
              {{ record.side === 'BUY' ? '买入' : '卖出' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'status'">
            <a-tag :color="ORDER_STATUS_MAP[record.status]?.color || 'default'" size="small">
              {{ ORDER_STATUS_MAP[record.status]?.label || record.status }}
            </a-tag>
          </template>
          <template v-if="column.key === 'createdAt'">
            {{ formatTime(record.createdAt, 'MM-DD HH:mm:ss') }}
          </template>
          <template v-if="column.key === 'action'">
            <a-button size="small" type="text" @click="showDetail(record)">👁</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 订单详情抽屉 -->
    <a-drawer title="订单详情" :open="detailVisible" :width="480" @close="detailVisible = false">
      <template v-if="currentOrder">
        <div style="margin-bottom: 16px;">
          <a-tag :color="ORDER_STATUS_MAP[currentOrder.status]?.color" size="large">
            {{ ORDER_STATUS_MAP[currentOrder.status]?.label }}
          </a-tag>
        </div>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="订单号">{{ currentOrder.id }}</a-descriptions-item>
          <a-descriptions-item label="策略名称">--</a-descriptions-item>
          <a-descriptions-item label="交易对">{{ currentOrder.symbol }}</a-descriptions-item>
          <a-descriptions-item label="方向">
            <a-tag :color="currentOrder.side === 'BUY' ? 'green' : 'red'" size="small">
              {{ currentOrder.side === 'BUY' ? '买入' : '卖出' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="类型">{{ currentOrder.orderType }}</a-descriptions-item>
          <a-descriptions-item label="价格">{{ currentOrder.price || '--' }}</a-descriptions-item>
          <a-descriptions-item label="数量">{{ currentOrder.quantity || '--' }}</a-descriptions-item>
          <a-descriptions-item label="金额">{{ currentOrder.notional || '--' }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="ORDER_STATUS_MAP[currentOrder.status]?.color">{{ ORDER_STATUS_MAP[currentOrder.status]?.label }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="OKX订单ID">{{ currentOrder.okxOrderId || '--' }}</a-descriptions-item>
          <a-descriptions-item label="时间" :span="2">{{ formatTime(currentOrder.createdAt) }}</a-descriptions-item>
        </a-descriptions>

        <div style="margin-top: 20px;">
          <div style="font-weight: 500; margin-bottom: 8px;">响应结果</div>
          <pre class="raw-response">{{ currentOrder.rawResponse || '无' }}</pre>
        </div>

        <div style="margin-top: 16px;">
          <div style="font-weight: 500; margin-bottom: 8px;">错误信息</div>
          <span style="color: var(--text-secondary)">{{ currentOrder.errorMessage || '无' }}</span>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { orderApi } from '@/api/order.api'
import { ORDER_STATUS_MAP, SYMBOL_OPTIONS } from '@/constants/enums'
import { formatTime, formatAmount } from '@/utils/format'
import type { TradeOrder } from '@/types/api'
import dayjs from 'dayjs'

const loading = ref(false)
const orders = ref<TradeOrder[]>([])
const detailVisible = ref(false)
const currentOrder = ref<TradeOrder | null>(null)
const currentTime = dayjs().format('YYYY-MM-DD HH:mm:ss')

const filledCount = computed(() => orders.value.filter(o => o.status === 'FILLED').length)
const failedCount = computed(() => orders.value.filter(o => o.status === 'FAILED').length)
const buyTotal = computed(() => orders.value.filter(o => o.side === 'BUY').reduce((sum, o) => sum + (parseFloat(o.notional || '0') || 0), 0))
const sellTotal = computed(() => orders.value.filter(o => o.side === 'SELL').reduce((sum, o) => sum + (parseFloat(o.notional || '0') || 0), 0))

const filters = reactive({ symbol: undefined as string | undefined, status: undefined as string | undefined })

const columns = [
  { title: '订单号', dataIndex: 'id', key: 'id', width: 90 },
  { title: '策略名称', dataIndex: 'strategyName', key: 'strategyName', ellipsis: true },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '方向', key: 'side', width: 60 },
  { title: '类型', dataIndex: 'orderType', key: 'orderType', width: 70 },
  { title: '价格', dataIndex: 'price', key: 'price', width: 90 },
  { title: '数量', dataIndex: 'quantity', key: 'quantity', width: 80 },
  { title: '状态', key: 'status', width: 80 },
  { title: 'OKX订单ID', dataIndex: 'okxOrderId', key: 'okxOrderId', ellipsis: true, width: 150 },
  { title: '时间', key: 'createdAt', width: 120 },
  { title: '操作', key: 'action', width: 50 }
]

onMounted(() => fetchOrders())

async function fetchOrders() {
  loading.value = true
  try {
    const res = await orderApi.list(filters)
    orders.value = (res as any).data || []
  } finally { loading.value = false }
}

function showDetail(record: TradeOrder) {
  currentOrder.value = record
  detailVisible.value = true
}
</script>

<style lang="scss" scoped>
.order-page {
  .page-header-row {
    display: flex; align-items: baseline; gap: 16px; margin-bottom: 16px;
    .update-time { font-size: 13px; color: var(--text-muted); }
  }

  .stat-row {
    display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 16px;
    .stat-card {
      background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 10px; padding: 16px 18px;
      .stat-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
      .stat-value { font-size: 22px; font-weight: 700; color: var(--text-primary); }
      .stat-sub { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
    }
  }

  .filter-bar {
    display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
  }

  .raw-response {
    background: #F9FAFB; border: 1px solid var(--border-color); border-radius: 8px;
    padding: 12px; font-size: 12px; max-height: 200px; overflow-y: auto;
    white-space: pre-wrap; word-break: break-all;
  }
}
</style>
