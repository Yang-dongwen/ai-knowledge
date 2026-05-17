<template>
  <div class="trade-page">
    <div class="page-header-row">
      <h2 class="page-title">交易记录</h2>
      <span class="page-subtitle-inline">历史真实成交记录</span>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">今日成交次数</div>
        <div class="stat-value">{{ summary.todayCount || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日买入金额 (USDT)</div>
        <div class="stat-value">{{ formatAmount(summary.todayBuyAmount) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日卖出金额 (USDT)</div>
        <div class="stat-value">{{ formatAmount(summary.todaySellAmount) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日已实现盈亏 (USDT)</div>
        <div class="stat-value" :class="getPnlClass(summary.todayRealizedPnl)">
          {{ formatPnlValue(summary.todayRealizedPnl) }}
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-label">累计手续费 (USDT)</div>
        <div class="stat-value">{{ formatAmount(summary.totalFee) }}</div>
      </div>
    </div>

    <div class="page-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <a-space :size="12">
          <a-select v-model:value="filters.symbol" placeholder="交易对" allow-clear style="width: 130px" :options="SYMBOL_OPTIONS" />
          <a-select v-model:value="filters.side" placeholder="方向" allow-clear style="width: 100px">
            <a-select-option value="BUY">买入</a-select-option>
            <a-select-option value="SELL">卖出</a-select-option>
          </a-select>
          <a-button type="primary" @click="fetchTrades">查询</a-button>
          <a-button @click="resetFilters">重置</a-button>
        </a-space>
      </div>

      <!-- 成交表格 -->
      <a-table
        :columns="columns"
        :data-source="trades"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 15, size: 'small', showTotal: (total: number) => `共 ${total} 条` }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'tradeTime'">
            {{ formatTime(record.tradeTime) }}
          </template>
          <template v-if="column.key === 'side'">
            <a-tag :color="record.side === 'BUY' ? 'green' : 'red'" size="small">
              {{ record.side === 'BUY' ? '买入' : '卖出' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'price'">
            {{ formatAmount(record.price, 2) }}
          </template>
          <template v-if="column.key === 'quantity'">
            {{ formatAmount(record.quantity, 6) }}
          </template>
          <template v-if="column.key === 'notional'">
            {{ formatAmount(record.notional, 2) }}
          </template>
          <template v-if="column.key === 'fee'">
            {{ record.fee ? formatAmount(record.fee, 6) : '--' }}
            <span v-if="record.feeCurrency" style="color: var(--text-muted); font-size: 11px;"> {{ record.feeCurrency }}</span>
          </template>
          <template v-if="column.key === 'realizedPnl'">
            <span :class="getPnlClass(record.realizedPnl)">
              {{ record.realizedPnl ? formatPnlValue(record.realizedPnl) : '--' }}
            </span>
          </template>
          <template v-if="column.key === 'action'">
            <a-button size="small" type="text" @click="showDetail(record)">详情</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 成交详情抽屉 -->
    <a-drawer title="成交详情" :open="detailVisible" :width="480" @close="detailVisible = false">
      <template v-if="currentTrade">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="成交时间" :span="2">{{ formatTime(currentTrade.tradeTime) }}</a-descriptions-item>
          <a-descriptions-item label="交易对">{{ currentTrade.symbol }}</a-descriptions-item>
          <a-descriptions-item label="方向">
            <a-tag :color="currentTrade.side === 'BUY' ? 'green' : 'red'" size="small">{{ currentTrade.side === 'BUY' ? '买入' : '卖出' }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="成交价格">{{ formatAmount(currentTrade.price, 2) }}</a-descriptions-item>
          <a-descriptions-item label="成交数量">{{ formatAmount(currentTrade.quantity, 6) }}</a-descriptions-item>
          <a-descriptions-item label="成交金额">{{ formatAmount(currentTrade.notional, 2) }} USDT</a-descriptions-item>
          <a-descriptions-item label="手续费">{{ currentTrade.fee ? formatAmount(currentTrade.fee, 6) + ' ' + (currentTrade.feeCurrency || '') : '--' }}</a-descriptions-item>
          <a-descriptions-item label="已实现盈亏" :span="2">
            <span :class="getPnlClass(currentTrade.realizedPnl)">{{ currentTrade.realizedPnl ? formatPnlValue(currentTrade.realizedPnl) + ' USDT' : '--' }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="关联订单ID">{{ currentTrade.orderId || '--' }}</a-descriptions-item>
          <a-descriptions-item label="OKX成交ID">{{ currentTrade.okxTradeId || '--' }}</a-descriptions-item>
          <a-descriptions-item label="OKX订单ID" :span="2">{{ currentTrade.okxOrderId || '--' }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { tradeApi } from '@/api/trade.api'
import { SYMBOL_OPTIONS } from '@/constants/enums'
import { formatAmount, formatTime, getPnlClass } from '@/utils/format'

const loading = ref(false)
const trades = ref<any[]>([])
const summary = reactive({ todayCount: 0, todayBuyAmount: 0, todaySellAmount: 0, todayRealizedPnl: 0, totalFee: 0 })
const detailVisible = ref(false)
const currentTrade = ref<any>(null)
const filters = reactive({ symbol: undefined as string | undefined, side: undefined as string | undefined })

function formatPnlValue(value: any): string {
  if (!value) return '0.00'
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '0.00'
  return (num >= 0 ? '+' : '') + num.toFixed(2)
}

const columns = [
  { title: '成交时间', key: 'tradeTime', width: 150 },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '方向', key: 'side', width: 60 },
  { title: '成交价格', key: 'price', width: 100 },
  { title: '成交数量', key: 'quantity', width: 100 },
  { title: '成交金额', key: 'notional', width: 100 },
  { title: '手续费', key: 'fee', width: 110 },
  { title: '已实现盈亏', key: 'realizedPnl', width: 110 },
  { title: '操作', key: 'action', width: 60 }
]

onMounted(() => {
  fetchTrades()
  fetchSummary()
})

async function fetchTrades() {
  loading.value = true
  try {
    const res = await tradeApi.list(filters)
    trades.value = (res as any).data || []
  } finally { loading.value = false }
}

async function fetchSummary() {
  try {
    const res = await tradeApi.getSummary()
    const d = (res as any).data
    if (d) Object.assign(summary, d)
  } catch { /* ignore */ }
}

function resetFilters() {
  filters.symbol = undefined
  filters.side = undefined
  fetchTrades()
}

function showDetail(record: any) {
  currentTrade.value = record
  detailVisible.value = true
}
</script>

<style lang="scss" scoped>
.trade-page {
  .page-header-row { display: flex; align-items: baseline; gap: 16px; margin-bottom: 16px;
    .page-subtitle-inline { font-size: 13px; color: var(--text-secondary); }
  }

  .stat-row {
    display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 16px;
    .stat-card {
      background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 10px; padding: 16px 18px;
      .stat-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
      .stat-value { font-size: 20px; font-weight: 700; color: var(--text-primary); }
    }
  }

  .filter-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
}
</style>
