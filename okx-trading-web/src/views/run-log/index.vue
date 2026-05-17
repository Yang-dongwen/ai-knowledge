<template>
  <div class="run-log-page">
    <div class="page-header-row">
      <h2 class="page-title">运行日志</h2>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">运行次数</div>
        <div class="stat-value">{{ logs.length }} <span class="unit">次</span></div>
        <div class="stat-sub">当前查询结果</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">BUY 信号</div>
        <div class="stat-value" style="color: var(--success-color)">{{ buySignalCount }} <span class="unit">次</span></div>
        <div class="stat-sub">买入信号</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">SELL 信号</div>
        <div class="stat-value" style="color: var(--danger-color)">{{ sellSignalCount }} <span class="unit">次</span></div>
        <div class="stat-sub">卖出信号</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">HOLD 信号</div>
        <div class="stat-value">{{ holdSignalCount }} <span class="unit">次</span></div>
        <div class="stat-sub">无操作</div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="page-card">
      <div class="filter-bar">
        <a-space :size="12">
          <a-select placeholder="策略名称" allow-clear style="width: 140px">
            <a-select-option value="all">全部策略</a-select-option>
          </a-select>
          <a-select placeholder="交易对" allow-clear style="width: 130px" :options="SYMBOL_OPTIONS" />
          <a-select v-model:value="filters.signal" placeholder="信号类型" allow-clear style="width: 120px">
            <a-select-option value="BUY">BUY</a-select-option>
            <a-select-option value="SELL">SELL</a-select-option>
            <a-select-option value="HOLD">HOLD</a-select-option>
          </a-select>
          <a-range-picker style="width: 320px" />
        </a-space>
        <a-space>
          <a-button @click="fetchLogs">重置</a-button>
          <a-button type="primary" @click="fetchLogs">查询</a-button>
        </a-space>
      </div>

      <!-- 日志表格 -->
      <a-table
        :columns="columns"
        :data-source="logs"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, size: 'small', showTotal: (total: number) => `共 ${total} 条` }"
        size="middle"
        :row-class-name="(record: any) => record.id === selectedLog?.id ? 'row-selected' : ''"
        @row-click="(record: any) => selectedLog = record"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            {{ formatTime(record.createdAt, 'MM-DD HH:mm:ss') }}
          </template>
          <template v-if="column.key === 'signal'">
            <a-tag :color="SIGNAL_MAP[record.signal]?.color || 'default'" size="small">
              {{ record.signal }}
            </a-tag>
          </template>
          <template v-if="column.key === 'action'">
            <a-tag :color="record.signal === 'BUY' ? 'green' : record.signal === 'SELL' ? 'red' : 'default'" size="small">
              {{ record.action || '无操作' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'closePrice'">
            {{ formatAmount(record.closePrice) }}
          </template>
          <template v-if="column.key === 'fastMa'">
            {{ formatAmount(record.fastMa) }}
          </template>
          <template v-if="column.key === 'slowMa'">
            {{ formatAmount(record.slowMa) }}
          </template>
        </template>
      </a-table>
    </div>

    <!-- 日志详情 -->
    <a-row :gutter="16" v-if="selectedLog">
      <a-col :span="7">
        <div class="page-card">
          <div class="card-title">● 日志详情</div>
          <div class="detail-grid">
            <div class="detail-row"><span class="label">时间</span><span class="value">{{ formatTime(selectedLog.createdAt) }}</span></div>
            <div class="detail-row"><span class="label">策略名称</span><span class="value">BTC均线交叉策略</span></div>
            <div class="detail-row"><span class="label">交易对</span><span class="value">{{ selectedLog.symbol }}</span></div>
            <div class="detail-row"><span class="label">周期</span><span class="value">{{ selectedLog.timeframe }}</span></div>
            <div class="detail-row"><span class="label">信号类型</span><span class="value" :style="{ color: selectedLog.signal === 'BUY' ? 'var(--success-color)' : selectedLog.signal === 'SELL' ? 'var(--danger-color)' : '' }">{{ selectedLog.signal }}</span></div>
            <div class="detail-row"><span class="label">动作</span><span class="value">{{ selectedLog.action }}</span></div>
            <div class="detail-row"><span class="label">收盘价</span><span class="value">{{ selectedLog.closePrice }}</span></div>
            <div class="detail-row"><span class="label">快线MA</span><span class="value">{{ selectedLog.fastMa }}</span></div>
            <div class="detail-row"><span class="label">慢线MA</span><span class="value">{{ selectedLog.slowMa }}</span></div>
            <div class="detail-row"><span class="label">原因</span><span class="value">{{ selectedLog.message }}</span></div>
          </div>
        </div>
      </a-col>
      <a-col :span="10">
        <div class="page-card">
          <div class="card-title">K线快照（1小时）</div>
          <div class="chart-area">K线图表区域（接入 ECharts）</div>
        </div>
      </a-col>
      <a-col :span="7">
        <div class="page-card">
          <div class="card-title">信号说明</div>
          <div class="signal-explain">
            <a-tag :color="SIGNAL_MAP[selectedLog.signal]?.color" style="margin-bottom: 12px;">
              {{ SIGNAL_MAP[selectedLog.signal]?.label }}信号
            </a-tag>
            <p class="explain-text">{{ selectedLog.message }}</p>
          </div>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { logApi } from '@/api/log.api'
import { SIGNAL_MAP, SYMBOL_OPTIONS } from '@/constants/enums'
import { formatAmount, formatTime } from '@/utils/format'
import type { StrategyRunLog } from '@/types/api'

const loading = ref(false)
const logs = ref<StrategyRunLog[]>([])
const selectedLog = ref<StrategyRunLog | null>(null)
const filters = reactive({ signal: undefined as string | undefined })

const buySignalCount = computed(() => logs.value.filter(l => l.signal === 'BUY').length)
const sellSignalCount = computed(() => logs.value.filter(l => l.signal === 'SELL').length)
const holdSignalCount = computed(() => logs.value.filter(l => l.signal === 'HOLD').length)

const columns = [
  { title: '时间', key: 'createdAt', width: 130 },
  { title: '策略名称', dataIndex: 'strategyName', key: 'strategyName', ellipsis: true },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '周期', dataIndex: 'timeframe', key: 'timeframe', width: 60 },
  { title: '收盘价', key: 'closePrice', width: 90 },
  { title: '快线MA', key: 'fastMa', width: 90 },
  { title: '慢线MA', key: 'slowMa', width: 90 },
  { title: '信号', key: 'signal', width: 70 },
  { title: '动作', key: 'action', width: 70 },
  { title: '说明', dataIndex: 'message', key: 'message', ellipsis: true }
]

onMounted(() => fetchLogs())

async function fetchLogs() {
  loading.value = true
  try {
    const res = await logApi.list(filters)
    logs.value = (res as any).data || []
    if (logs.value.length > 0) selectedLog.value = logs.value[0]
  } finally { loading.value = false }
}
</script>

<style lang="scss" scoped>
.run-log-page {
  .page-header-row { margin-bottom: 16px; }

  .stat-row {
    display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px;
    .stat-card {
      background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 10px; padding: 16px 18px;
      .stat-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
      .stat-value { font-size: 22px; font-weight: 700; color: var(--text-primary); .unit { font-size: 13px; font-weight: 400; } }
      .stat-sub { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
    }
  }

  .filter-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
  .card-title { font-size: 15px; font-weight: 600; color: var(--text-primary); margin-bottom: 14px; }

  .detail-grid {
    .detail-row {
      display: flex; justify-content: space-between; padding: 5px 0; font-size: 13px;
      .label { color: var(--text-secondary); }
      .value { color: var(--text-primary); font-weight: 500; text-align: right; max-width: 60%; }
    }
  }

  .chart-area { height: 200px; background: #FAFBFC; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--text-muted); }

  .signal-explain {
    .explain-text { font-size: 13px; color: var(--text-secondary); line-height: 1.8; }
  }

  :deep(.row-selected td) { background: #EBF5FF !important; }
}
</style>
