<template>
  <div class="position-page">
    <div class="page-header-row">
      <h2 class="page-title">持仓管理</h2>
      <span class="page-subtitle-inline">实时查看当前持仓及盈亏情况</span>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">持仓数量</div>
        <div class="stat-value">{{ positions.length }}</div>
        <div class="stat-sub">个交易对</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">总市值</div>
        <div class="stat-value">{{ formatAmount(totalMarketValue) }} <span class="unit">USDT</span></div>
        <div class="stat-sub">≈ ¥{{ formatAmount(totalMarketValue * 7.15, 2) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">浮动盈亏</div>
        <div class="stat-value" :class="getPnlClass(totalUnrealizedPnl)">{{ formatPnl(totalUnrealizedPnl) }} <span class="unit">USDT</span></div>
        <div class="stat-sub" :class="getPnlClass(totalUnrealizedPnl)">
          {{ totalMarketValue > 0 ? ((totalUnrealizedPnl / totalMarketValue) * 100).toFixed(2) + '%' : '--' }}
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已实现盈亏</div>
        <div class="stat-value" :class="getPnlClass(totalRealizedPnl)">{{ formatPnl(totalRealizedPnl) }} <span class="unit">USDT</span></div>
        <div class="stat-sub">累计</div>
      </div>
    </div>

    <!-- 持仓列表 -->
    <div class="page-card">
      <div class="card-header">
        <span class="card-title">持仓列表</span>
        <a-space>
          <a-input-search placeholder="搜索交易对" style="width: 180px" size="small" />
          <a-button size="small">筛选</a-button>
        </a-space>
      </div>
      <a-table
        :columns="columns"
        :data-source="positions"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, size: 'small' }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'symbol'">
            <div>
              <div style="font-weight: 500;">{{ record.symbol }}</div>
              <div style="font-size: 11px; color: var(--text-muted);">永续</div>
            </div>
          </template>
          <template v-if="column.key === 'quantity'">
            {{ formatAmount(record.quantity, 4) }}
          </template>
          <template v-if="column.key === 'avgPrice'">
            {{ formatAmount(record.avgPrice, 2) }}
          </template>
          <template v-if="column.key === 'currentPrice'">
            {{ formatAmount(record.currentPrice, 2) }}
          </template>
          <template v-if="column.key === 'unrealizedPnl'">
            <span :class="getPnlClass(record.unrealizedPnl)">
              {{ formatPnl(record.unrealizedPnl) }}
            </span>
          </template>
          <template v-if="column.key === 'realizedPnl'">
            <span :class="getPnlClass(record.realizedPnl)">
              {{ formatPnl(record.realizedPnl) }}
            </span>
          </template>
          <template v-if="column.key === 'status'">
            <a-tag :color="record.status === 'OPEN' ? 'green' : 'default'" size="small">
              {{ record.status === 'OPEN' ? '持仓中' : '已平仓' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'updatedAt'">
            {{ formatTime(record.updatedAt) }}
          </template>
        </template>
      </a-table>
    </div>

    <!-- 持仓详情 -->
    <div class="page-card" v-if="selectedPosition">
      <div class="card-title">持仓详情 · {{ selectedPosition.symbol }}</div>
      <a-row :gutter="16">
        <a-col :span="8">
          <div class="detail-info">
            <div class="detail-row"><span class="label">持仓方向</span><span class="value">📈 多</span></div>
            <div class="detail-row"><span class="label">数量</span><span class="value">{{ formatAmount(selectedPosition.quantity, 6) }} {{ selectedPosition.symbol.split('-')[0] }}</span></div>
            <div class="detail-row"><span class="label">持仓均价</span><span class="value">{{ formatAmount(selectedPosition.avgPrice, 2) }} USDT</span></div>
            <div class="detail-row"><span class="label">当前价格</span><span class="value">{{ formatAmount(selectedPosition.currentPrice, 2) }}</span></div>
            <div class="detail-row"><span class="label">市值</span><span class="value">{{ formatAmount(parseFloat(selectedPosition.quantity) * parseFloat(selectedPosition.currentPrice), 2) }} USDT</span></div>
            <div class="detail-row"><span class="label">浮动盈亏</span><span class="value" :class="getPnlClass(selectedPosition.unrealizedPnl)">{{ formatPnl(selectedPosition.unrealizedPnl) }} USDT</span></div>
            <div class="detail-row"><span class="label">已实现盈亏</span><span class="value" :class="getPnlClass(selectedPosition.realizedPnl)">{{ formatPnl(selectedPosition.realizedPnl) }} USDT</span></div>
            <div class="detail-row"><span class="label">更新时间</span><span class="value">{{ formatTime(selectedPosition.updatedAt) }}</span></div>
          </div>
        </a-col>
        <a-col :span="10">
          <div class="chart-placeholder">
            <div class="chart-title">{{ selectedPosition.symbol }} 价格走势</div>
            <div class="chart-area">K线图表区域（接入 ECharts）</div>
          </div>
        </a-col>
        <a-col :span="6">
          <div class="stop-profit-card">
            <div class="sp-title">止盈止损参考</div>
            <div class="sp-row"><span>持仓均价</span><span class="value">{{ formatAmount(selectedPosition.avgPrice, 2) }}</span></div>
            <div class="sp-row"><span>当前价格</span><span class="value">{{ formatAmount(selectedPosition.currentPrice, 2) }}</span></div>
            <div class="sp-row"><span>浮动盈亏</span><span class="value" :class="getPnlClass(selectedPosition.unrealizedPnl)">{{ formatPnl(selectedPosition.unrealizedPnl) }} USDT</span></div>
            <div class="sp-row"><span>状态</span><span class="value">{{ selectedPosition.status === 'OPEN' ? '持仓中' : '已平仓' }}</span></div>
          </div>
        </a-col>
      </a-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { positionApi } from '@/api/position.api'
import { formatAmount, formatTime, getPnlClass } from '@/utils/format'
import type { Position } from '@/types/api'

const loading = ref(false)
const positions = ref<Position[]>([])

const selectedPosition = computed(() => positions.value.length > 0 ? positions.value[0] : null)

const totalMarketValue = computed(() => positions.value.reduce((sum, p) => {
  return sum + (parseFloat(p.quantity) || 0) * (parseFloat(p.currentPrice) || 0)
}, 0))

const totalUnrealizedPnl = computed(() => positions.value.reduce((sum, p) => {
  return sum + (parseFloat(p.unrealizedPnl) || 0)
}, 0))

const totalRealizedPnl = computed(() => positions.value.reduce((sum, p) => {
  return sum + (parseFloat(p.realizedPnl) || 0)
}, 0))

function formatPnl(value: string | number | null | undefined): string {
  if (!value) return '--'
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '--'
  const sign = num > 0 ? '+' : ''
  return sign + num.toFixed(2)
}

const columns = [
  { title: '交易对', key: 'symbol', width: 120 },
  { title: '数量', key: 'quantity', width: 100 },
  { title: '可用数量', key: 'quantity', width: 100 },
  { title: '持仓均价', key: 'avgPrice', width: 110 },
  { title: '当前价格', key: 'currentPrice', width: 110 },
  { title: '市值 (USDT)', key: 'marketValue', width: 110 },
  { title: '浮动盈亏 (USDT)', key: 'unrealizedPnl', width: 140 },
  { title: '已实现盈亏 (USDT)', key: 'realizedPnl', width: 140 },
  { title: '状态', key: 'status', width: 80 },
  { title: '更新时间', key: 'updatedAt', width: 150 }
]

onMounted(async () => {
  loading.value = true
  try {
    const res = await positionApi.list()
    positions.value = (res as any).data || []
  } finally { loading.value = false }
})
</script>

<style lang="scss" scoped>
.position-page {
  .page-header-row {
    display: flex; align-items: baseline; gap: 16px; margin-bottom: 16px;
    .page-subtitle-inline { font-size: 13px; color: var(--text-secondary); }
  }

  .stat-row {
    display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px;
    .stat-card {
      background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 10px; padding: 18px 20px;
      .stat-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
      .stat-value { font-size: 24px; font-weight: 700; color: var(--text-primary); .unit { font-size: 13px; font-weight: 400; } }
      .stat-sub { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
    }
  }

  .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
  .card-title { font-size: 15px; font-weight: 600; color: var(--text-primary); margin-bottom: 16px; }

  .detail-info {
    .detail-row {
      display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px;
      .label { color: var(--text-secondary); }
      .value { color: var(--text-primary); font-weight: 500; }
    }
  }

  .chart-placeholder {
    .chart-title { font-size: 14px; font-weight: 500; margin-bottom: 12px; }
    .chart-area { height: 200px; background: #FAFBFC; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--text-muted); }
  }

  .stop-profit-card {
    background: #FAFBFC; border-radius: 10px; padding: 16px;
    .sp-title { font-size: 14px; font-weight: 500; margin-bottom: 12px; }
    .sp-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; color: var(--text-secondary); .value { font-weight: 500; } }
  }
}
</style>
