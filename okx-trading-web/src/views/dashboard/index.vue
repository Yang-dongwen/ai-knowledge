<template>
  <div class="dashboard-page">
    <div class="page-header-row">
      <h2 class="page-title">仪表盘</h2>
      <span class="update-time">◎ 更新时间：{{ currentTime }}</span>
    </div>

    <!-- 顶部统计卡片：交易资产视角 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">账户权益 (USDT)</div>
        <div class="stat-value">{{ formatAmount(data.account.totalEquity) }}</div>
        <div class="stat-sub">≈ ¥{{ formatAmount(parseFloat(data.account.totalEquity) * 7.15, 2) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">可用余额 (USDT)</div>
        <div class="stat-value">{{ formatAmount(data.account.availableBalance) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">持仓市值 (USDT)</div>
        <div class="stat-value">{{ formatAmount(data.positions.marketValue) }}</div>
        <div class="stat-sub">{{ data.positions.count }} 个交易对</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">浮动盈亏 (USDT)</div>
        <div class="stat-value" :class="getPnlClass(data.positions.unrealizedPnl)">
          {{ formatPnlValue(data.positions.unrealizedPnl) }}
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已实现盈亏 (USDT)</div>
        <div class="stat-value" :class="getPnlClass(data.positions.realizedPnl)">
          {{ formatPnlValue(data.positions.realizedPnl) }}
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-label">运行中策略</div>
        <div class="stat-value">
          {{ data.strategies.enabled }}
          <span style="font-size: 13px; font-weight: 400; color: var(--text-muted);">/ {{ data.strategies.total }}</span>
        </div>
        <div class="stat-sub">
          <span class="status-dot" :class="data.systemStatus === 'RUNNING' ? 'dot-green' : 'dot-red'" />
          {{ data.systemStatus === 'RUNNING' ? '系统运行中' : '系统已停止' }}
        </div>
      </div>
    </div>

    <!-- 中间：K线图 + 当前持仓 -->
    <a-row :gutter="16">
      <a-col :span="14">
        <div class="page-card kline-card">
          <div class="kline-header">
            <span class="kline-title">BTC-USDT 1小时</span>
          </div>
          <div class="chart-area">K线图表区域（接入 ECharts）</div>
        </div>
      </a-col>
      <a-col :span="10">
        <div class="page-card position-card">
          <div class="card-header">
            <span class="card-title">当前持仓</span>
            <a class="more-link" @click="$router.push('/positions')">查看全部 ›</a>
          </div>
          <template v-if="data.positions.list.length > 0">
            <div class="position-item" v-for="pos in data.positions.list.slice(0, 3)" :key="pos.id">
              <div class="pos-header">
                <span class="pos-symbol">{{ pos.symbol }}</span>
                <span :class="getPnlClass(pos.unrealizedPnl)" class="pos-pnl">{{ formatPnlValue(pos.unrealizedPnl) }}</span>
              </div>
              <div class="pos-grid">
                <div class="pos-item"><span class="label">数量</span><span class="value">{{ formatAmount(pos.quantity, 6) }}</span></div>
                <div class="pos-item"><span class="label">均价</span><span class="value">{{ formatAmount(pos.avgPrice, 2) }}</span></div>
                <div class="pos-item"><span class="label">现价</span><span class="value">{{ formatAmount(pos.currentPrice, 2) }}</span></div>
                <div class="pos-item"><span class="label">市值</span><span class="value">{{ formatAmount(parseFloat(pos.quantity) * parseFloat(pos.currentPrice), 2) }}</span></div>
              </div>
            </div>
          </template>
          <a-empty v-else description="暂无持仓" :image-style="{ height: '60px' }" />
        </div>
      </a-col>
    </a-row>

    <!-- 下方：持仓列表 + 最近成交 -->
    <a-row :gutter="16">
      <a-col :span="12">
        <div class="page-card">
          <div class="card-header">
            <span class="card-title">最近成交</span>
            <a class="more-link" @click="$router.push('/trades')">更多 ›</a>
          </div>
          <a-table :columns="tradeColumns" :data-source="data.recentTrades" :pagination="false" size="small" :loading="loading">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'side'">
                <a-tag :color="record.side === 'BUY' ? 'green' : 'red'" size="small">{{ record.side === 'BUY' ? '买入' : '卖出' }}</a-tag>
              </template>
              <template v-if="column.key === 'tradeTime'">{{ formatTime(record.tradeTime, 'MM-DD HH:mm:ss') }}</template>
              <template v-if="column.key === 'price'">{{ formatAmount(record.price, 2) }}</template>
              <template v-if="column.key === 'quantity'">{{ formatAmount(record.quantity, 6) }}</template>
              <template v-if="column.key === 'realizedPnl'">
                <span :class="getPnlClass(record.realizedPnl)">{{ formatPnlValue(record.realizedPnl) }}</span>
              </template>
            </template>
          </a-table>
        </div>
      </a-col>
      <a-col :span="12">
        <div class="page-card">
          <div class="card-header">
            <span class="card-title">最近订单</span>
            <a class="more-link" @click="$router.push('/orders')">更多 ›</a>
          </div>
          <a-table :columns="orderColumns" :data-source="data.recentOrders" :pagination="false" size="small" :loading="loading">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'side'">
                <a-tag :color="record.side === 'BUY' ? 'green' : 'red'" size="small">{{ record.side === 'BUY' ? '买入' : '卖出' }}</a-tag>
              </template>
              <template v-if="column.key === 'status'">
                <a-tag :color="ORDER_STATUS_MAP[record.status]?.color || 'default'" size="small">{{ ORDER_STATUS_MAP[record.status]?.label || record.status }}</a-tag>
              </template>
              <template v-if="column.key === 'createdAt'">{{ formatTime(record.createdAt, 'MM-DD HH:mm:ss') }}</template>
            </template>
          </a-table>
        </div>
      </a-col>
    </a-row>

    <!-- 底部：运行日志 -->
    <div class="page-card">
      <div class="card-header">
        <span class="card-title">最近策略运行日志</span>
        <a class="more-link" @click="$router.push('/run-logs')">更多 ›</a>
      </div>
      <a-table :columns="logColumns" :data-source="data.recentLogs" :pagination="false" size="small" :loading="loading">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'signal'">
            <a-tag :color="SIGNAL_MAP[record.signal]?.color || 'default'" size="small">{{ record.signal }}</a-tag>
          </template>
          <template v-if="column.key === 'createdAt'">{{ formatTime(record.createdAt, 'MM-DD HH:mm:ss') }}</template>
          <template v-if="column.key === 'closePrice'">{{ formatAmount(record.closePrice, 2) }}</template>
          <template v-if="column.key === 'fastMa'">{{ formatAmount(record.fastMa, 2) }}</template>
          <template v-if="column.key === 'slowMa'">{{ formatAmount(record.slowMa, 2) }}</template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { dashboardApi } from '@/api/dashboard.api'
import { ORDER_STATUS_MAP, SIGNAL_MAP } from '@/constants/enums'
import { formatAmount, formatTime, getPnlClass } from '@/utils/format'
import dayjs from 'dayjs'

const loading = ref(false)
const currentTime = ref(dayjs().format('YYYY-MM-DD HH:mm:ss'))

const data = reactive({
  systemStatus: 'RUNNING',
  account: { totalEquity: '0', availableBalance: '0' },
  positions: { count: 0, marketValue: 0, unrealizedPnl: 0, realizedPnl: 0, list: [] as any[] },
  strategies: { total: 0, enabled: 0, list: [] as any[] },
  recentTrades: [] as any[],
  recentOrders: [] as any[],
  recentLogs: [] as any[]
})

function formatPnlValue(value: any): string {
  if (!value) return '0.00'
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '0.00'
  return (num >= 0 ? '+' : '') + num.toFixed(2)
}

const tradeColumns = [
  { title: '时间', key: 'tradeTime', width: 120 },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '方向', key: 'side', width: 60 },
  { title: '成交价', key: 'price', width: 90 },
  { title: '数量', key: 'quantity', width: 90 },
  { title: '盈亏', key: 'realizedPnl', width: 80 }
]

const orderColumns = [
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '方向', key: 'side', width: 60 },
  { title: '类型', dataIndex: 'orderType', key: 'orderType', width: 70 },
  { title: '状态', key: 'status', width: 70 },
  { title: '时间', key: 'createdAt', width: 120 }
]

const logColumns = [
  { title: '时间', key: 'createdAt', width: 130 },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '周期', dataIndex: 'timeframe', key: 'timeframe', width: 60 },
  { title: '收盘价', key: 'closePrice', width: 90 },
  { title: '快线MA', key: 'fastMa', width: 90 },
  { title: '慢线MA', key: 'slowMa', width: 90 },
  { title: '信号', key: 'signal', width: 70 },
  { title: '动作', dataIndex: 'action', key: 'action', width: 70 },
  { title: '说明', dataIndex: 'message', key: 'message', ellipsis: true }
]

onMounted(async () => {
  loading.value = true
  currentTime.value = dayjs().format('YYYY-MM-DD HH:mm:ss')
  try {
    const res = await dashboardApi.getOverview()
    const d = (res as any).data
    if (d) {
      data.systemStatus = d.systemStatus || 'RUNNING'
      data.account = d.account || { totalEquity: '0', availableBalance: '0' }
      data.positions = d.positions || { count: 0, marketValue: 0, unrealizedPnl: 0, realizedPnl: 0, list: [] }
      data.strategies = d.strategies || { total: 0, enabled: 0, list: [] }
      data.recentTrades = d.recentTrades || []
      data.recentOrders = d.recentOrders || []
      data.recentLogs = d.recentLogs || []
    }
  } catch {
    // 接口未就绪时忽略
  } finally {
    loading.value = false
  }
})
</script>

<style lang="scss" scoped>
.dashboard-page {
  .page-header-row { display: flex; align-items: baseline; gap: 16px; margin-bottom: 20px;
    .update-time { font-size: 13px; color: var(--text-muted); }
  }

  .stat-row {
    display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 16px;
    .stat-card {
      background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 10px; padding: 16px 18px;
      .stat-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
      .stat-value { font-size: 20px; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
      .stat-sub { font-size: 12px; color: var(--text-muted); margin-top: 4px; display: flex; align-items: center; gap: 4px; }
    }
  }

  .kline-card {
    .kline-header { margin-bottom: 12px; .kline-title { font-size: 15px; font-weight: 600; } }
    .chart-area { height: 300px; background: #FAFBFC; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--text-muted); }
  }

  .position-card {
    .position-item {
      padding: 12px 0; border-bottom: 1px solid #F3F4F6;
      &:last-child { border-bottom: none; }
      .pos-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;
        .pos-symbol { font-weight: 600; font-size: 14px; }
        .pos-pnl { font-weight: 600; font-size: 14px; }
      }
      .pos-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px;
        .pos-item { display: flex; justify-content: space-between; font-size: 12px;
          .label { color: var(--text-muted); }
          .value { color: var(--text-primary); font-weight: 500; }
        }
      }
    }
  }

  .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
  .card-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
  .more-link { font-size: 13px; color: var(--primary-color); cursor: pointer; }
  .status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%;
    &.dot-green { background: var(--success-color); }
    &.dot-red { background: var(--danger-color); }
  }
}
</style>
