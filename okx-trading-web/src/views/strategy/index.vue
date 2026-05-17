<template>
  <div class="strategy-page">
    <div class="page-header-row">
      <h2 class="page-title">策略管理</h2>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <a-button type="primary" @click="showDrawer('create')">+ 新建策略</a-button>
        <a-button @click="fetchStrategies">🔄 筛选</a-button>
        <a-input-search placeholder="搜索策略名称或交易对" style="width: 260px" allow-clear />
      </div>
      <div class="toolbar-right">
        <a-radio-group v-model:value="filterTab" button-style="solid" size="small">
          <a-radio-button value="all">全部 {{ strategies.length }}</a-radio-button>
          <a-radio-button value="running">运行中 {{ runningCount }}</a-radio-button>
          <a-radio-button value="stopped">已停止 {{ stoppedCount }}</a-radio-button>
          <a-radio-button value="paper">模拟盘 {{ paperCount }}</a-radio-button>
        </a-radio-group>
      </div>
    </div>

    <!-- 策略预览卡片 -->
    <div class="page-card preview-card" v-if="selectedStrategy">
      <a-row :gutter="24">
        <a-col :span="12">
          <div class="preview-header">
            <span class="preview-label">当前选中策略</span>
            <a-tag :color="selectedStrategy.enabled === 1 ? 'green' : 'default'" size="small">
              {{ selectedStrategy.enabled === 1 ? '运行中' : '已停止' }}
            </a-tag>
          </div>
          <h3 class="preview-name">{{ selectedStrategy.strategyName }}</h3>
          <p class="preview-symbol">{{ selectedStrategy.symbol }}</p>
          <p class="preview-desc">基于双均线金叉/死叉信号进行自动交易，趋势跟踪型策略</p>
          <div class="preview-meta">
            <span>✗ 运行模式 <strong>{{ selectedStrategy.runMode === 'PAPER' ? '模拟盘' : '实盘' }}</strong></span>
            <span>⏱ 周期 <strong>{{ selectedStrategy.timeframe }}</strong></span>
          </div>
        </a-col>
        <a-col :span="12">
          <div class="config-overview">
            <div class="config-header">
              <span>配置概览</span>
              <a class="edit-link" @click="showDrawer('edit', selectedStrategy)">编辑</a>
            </div>
            <div class="config-grid">
              <div class="config-item"><span class="label">快线周期</span><span class="value">{{ selectedStrategy.fastPeriod }}</span></div>
              <div class="config-item"><span class="label">慢线周期</span><span class="value">{{ selectedStrategy.slowPeriod }}</span></div>
              <div class="config-item"><span class="label">买入比例</span><span class="value">{{ (selectedStrategy.tradeAmountPct * 100).toFixed(0) }}%</span></div>
              <div class="config-item"><span class="label">止损</span><span class="value">{{ (selectedStrategy.stopLossPct * 100).toFixed(0) }}%</span></div>
              <div class="config-item"><span class="label">止盈</span><span class="value">{{ (selectedStrategy.takeProfitPct * 100).toFixed(0) }}%</span></div>
              <div class="config-item"><span class="label">运行模式</span><span class="value">{{ selectedStrategy.runMode === 'PAPER' ? '模拟盘' : '实盘' }}</span></div>
            </div>
            <div class="strategy-rule">
              <p>策略说明</p>
              <span>当快线上穿慢线时买入，当快线下穿慢线时卖出。</span>
            </div>
          </div>
        </a-col>
      </a-row>
    </div>

    <!-- 策略列表 -->
    <div class="page-card">
      <a-table
        :columns="columns"
        :data-source="filteredStrategies"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, size: 'small', showTotal: (total: number) => `共 ${total} 条` }"
        :row-class-name="(record: any) => record.id === selectedStrategy?.id ? 'row-selected' : ''"
        @row-click="(record: any) => selectedStrategy = record"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'strategyName'">
            <div>
              <div class="strategy-name">{{ record.strategyName }}</div>
              <a-tag
                :color="record.enabled === 1 ? 'green' : 'default'"
                size="small"
                style="font-size: 11px;"
              >
                {{ record.enabled === 1 ? '运行中' : '已停止' }}
              </a-tag>
            </div>
          </template>
          <template v-if="column.key === 'tradeAmountPct'">
            {{ (record.tradeAmountPct * 100).toFixed(0) }}%
          </template>
          <template v-if="column.key === 'stopLossPct'">
            {{ (record.stopLossPct * 100).toFixed(0) }}%
          </template>
          <template v-if="column.key === 'takeProfitPct'">
            {{ (record.takeProfitPct * 100).toFixed(0) }}%
          </template>
          <template v-if="column.key === 'runMode'">
            <a-tag :color="record.runMode === 'PAPER' ? 'blue' : 'red'" size="small">
              {{ record.runMode === 'PAPER' ? '模拟盘' : '实盘' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'enabled'">
            <span :style="{ color: record.enabled === 1 ? 'var(--success-color)' : 'var(--text-muted)' }">
              ● {{ record.enabled === 1 ? '运行中' : '已停止' }}
            </span>
          </template>
          <template v-if="column.key === 'updatedAt'">
            {{ formatTime(record.updatedAt) }}
          </template>
          <template v-if="column.key === 'action'">
            <a-space :size="4">
              <a-button size="small" type="text" @click.stop="showDrawer('edit', record)">✏️</a-button>
              <a-button size="small" type="text" @click.stop="toggleStrategy(record)">
                {{ record.enabled === 1 ? '⏸' : '▶' }}
              </a-button>
              <a-button size="small" type="text">⋯</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 新增/编辑抽屉 -->
    <a-drawer
      :title="drawerMode === 'create' ? '新建策略' : '编辑策略'"
      :open="drawerVisible"
      :width="480"
      @close="drawerVisible = false"
    >
      <a-form :model="form" layout="vertical" @finish="handleSubmit">
        <a-form-item label="策略名称" :rules="[{ required: true, message: '请输入' }]">
          <a-input v-model:value="form.strategyName" placeholder="如：BTC 1H 均线策略" />
        </a-form-item>
        <a-form-item label="交易对" :rules="[{ required: true, message: '请选择' }]">
          <a-select v-model:value="form.symbol" :options="SYMBOL_OPTIONS" placeholder="选择交易对" />
        </a-form-item>
        <a-form-item label="K线周期" :rules="[{ required: true, message: '请选择' }]">
          <a-select v-model:value="form.timeframe" :options="TIMEFRAME_OPTIONS" placeholder="选择周期" />
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="快线周期">
              <a-input-number v-model:value="form.fastPeriod" :min="2" :max="100" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="慢线周期">
              <a-input-number v-model:value="form.slowPeriod" :min="3" :max="200" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="单次买入比例">
          <a-input-number v-model:value="form.tradeAmountPct" :min="0.01" :max="1" :step="0.01" style="width: 100%" />
          <div class="form-tip">如 0.05 表示使用可用余额的 5%</div>
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="止损比例">
              <a-input-number v-model:value="form.stopLossPct" :min="0.001" :max="0.5" :step="0.01" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="止盈比例">
              <a-input-number v-model:value="form.takeProfitPct" :min="0.001" :max="1" :step="0.01" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="运行模式">
          <a-radio-group v-model:value="form.runMode">
            <a-radio value="PAPER">模拟盘</a-radio>
            <a-radio value="PROD">实盘</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="submitting" block>
            {{ drawerMode === 'create' ? '创建策略' : '保存修改' }}
          </a-button>
        </a-form-item>
      </a-form>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { strategyApi } from '@/api/strategy.api'
import { SYMBOL_OPTIONS, TIMEFRAME_OPTIONS } from '@/constants/enums'
import { formatTime } from '@/utils/format'
import type { Strategy } from '@/types/api'

const loading = ref(false)
const submitting = ref(false)
const strategies = ref<Strategy[]>([])
const selectedStrategy = ref<Strategy | null>(null)
const drawerVisible = ref(false)
const drawerMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const filterTab = ref('all')

const runningCount = computed(() => strategies.value.filter(s => s.enabled === 1).length)
const stoppedCount = computed(() => strategies.value.filter(s => s.enabled === 0).length)
const paperCount = computed(() => strategies.value.filter(s => s.runMode === 'PAPER').length)

const filteredStrategies = computed(() => {
  if (filterTab.value === 'running') return strategies.value.filter(s => s.enabled === 1)
  if (filterTab.value === 'stopped') return strategies.value.filter(s => s.enabled === 0)
  if (filterTab.value === 'paper') return strategies.value.filter(s => s.runMode === 'PAPER')
  return strategies.value
})

const form = reactive({
  strategyName: '',
  symbol: undefined as string | undefined,
  timeframe: undefined as string | undefined,
  fastPeriod: 5,
  slowPeriod: 20,
  tradeAmountPct: 0.05,
  stopLossPct: 0.02,
  takeProfitPct: 0.05,
  runMode: 'PAPER'
})

const columns = [
  { title: '策略名称', key: 'strategyName', width: 160 },
  { title: '交易对', dataIndex: 'symbol', key: 'symbol', width: 100 },
  { title: '周期', dataIndex: 'timeframe', key: 'timeframe', width: 60 },
  { title: '快线', dataIndex: 'fastPeriod', key: 'fastPeriod', width: 50 },
  { title: '慢线', dataIndex: 'slowPeriod', key: 'slowPeriod', width: 50 },
  { title: '买入比例', key: 'tradeAmountPct', width: 80 },
  { title: '止损', key: 'stopLossPct', width: 50 },
  { title: '止盈', key: 'takeProfitPct', width: 50 },
  { title: '运行模式', key: 'runMode', width: 80 },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '更新时间', key: 'updatedAt', width: 150 },
  { title: '操作', key: 'action', width: 100, fixed: 'right' }
]

onMounted(() => fetchStrategies())

async function fetchStrategies() {
  loading.value = true
  try {
    const res = await strategyApi.list()
    strategies.value = (res as any).data || []
    if (strategies.value.length > 0 && !selectedStrategy.value) {
      selectedStrategy.value = strategies.value[0]
    }
  } finally { loading.value = false }
}

function showDrawer(mode: 'create' | 'edit', record?: any) {
  drawerMode.value = mode
  if (mode === 'edit' && record) {
    editingId.value = record.id
    Object.assign(form, {
      strategyName: record.strategyName, symbol: record.symbol, timeframe: record.timeframe,
      fastPeriod: record.fastPeriod, slowPeriod: record.slowPeriod,
      tradeAmountPct: record.tradeAmountPct, stopLossPct: record.stopLossPct,
      takeProfitPct: record.takeProfitPct, runMode: record.runMode
    })
  } else {
    editingId.value = null
    Object.assign(form, { strategyName: '', symbol: undefined, timeframe: undefined, fastPeriod: 5, slowPeriod: 20, tradeAmountPct: 0.05, stopLossPct: 0.02, takeProfitPct: 0.05, runMode: 'PAPER' })
  }
  drawerVisible.value = true
}

async function handleSubmit() {
  submitting.value = true
  try {
    if (drawerMode.value === 'create') {
      await strategyApi.create(form)
      message.success('策略创建成功')
    } else {
      await strategyApi.update(editingId.value!, form)
      message.success('策略修改成功')
    }
    drawerVisible.value = false
    fetchStrategies()
  } finally { submitting.value = false }
}

async function toggleStrategy(record: Strategy) {
  if (record.enabled === 1) {
    await strategyApi.disable(record.id)
    message.success('策略已停用')
  } else {
    await strategyApi.enable(record.id)
    message.success('策略已启用')
  }
  fetchStrategies()
}
</script>

<style lang="scss" scoped>
.strategy-page {
  .page-header-row { margin-bottom: 16px; }

  .toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;

    .toolbar-left {
      display: flex;
      gap: 10px;
      align-items: center;
    }
  }

  .preview-card {
    .preview-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 4px;

      .preview-label { font-size: 12px; color: var(--text-muted); }
    }

    .preview-name { font-size: 20px; font-weight: 600; margin: 4px 0; }
    .preview-symbol { font-size: 13px; color: var(--text-secondary); margin-bottom: 6px; }
    .preview-desc { font-size: 13px; color: var(--text-secondary); margin-bottom: 12px; }
    .preview-meta {
      display: flex; gap: 20px; font-size: 13px; color: var(--text-secondary);
      strong { color: var(--text-primary); margin-left: 4px; }
    }

    .config-overview {
      .config-header {
        display: flex; justify-content: space-between; margin-bottom: 12px;
        font-size: 14px; font-weight: 500;
        .edit-link { color: var(--primary-color); font-size: 13px; cursor: pointer; }
      }

      .config-grid {
        display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;
        .config-item {
          .label { display: block; font-size: 12px; color: var(--text-muted); }
          .value { font-size: 18px; font-weight: 600; color: var(--text-primary); }
        }
      }

      .strategy-rule {
        margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-color);
        p { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }
        span { font-size: 13px; color: var(--text-secondary); }
      }
    }
  }

  .strategy-name { font-weight: 500; }
  .form-tip { font-size: 12px; color: var(--text-muted); margin-top: 4px; }

  :deep(.row-selected td) { background: #EBF5FF !important; }
}
</style>
