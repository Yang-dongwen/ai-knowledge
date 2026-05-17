<template>
  <div class="system-settings-page">
    <div class="page-header-row">
      <div>
        <h2 class="page-title">系统设置</h2>
        <p class="page-subtitle">配置系统参数，管理运行行为与安全选项</p>
      </div>
    </div>

    <!-- 三列设置 -->
    <a-row :gutter="16">
      <!-- 交易控制 -->
      <a-col :span="8">
        <div class="page-card">
          <div class="card-title">⚙️ 交易控制</div>
          <div class="setting-row">
            <div class="setting-label">系统运行状态</div>
            <a-tag :color="systemStatus === 'RUNNING' ? 'green' : 'red'">
              {{ systemStatus === 'RUNNING' ? '运行中' : '已停止' }}
            </a-tag>
          </div>
          <div class="setting-row">
            <div>
              <div class="setting-label">一键停止</div>
              <div class="setting-desc">立即停止所有交易和策略</div>
            </div>
            <a-button danger size="small" @click="handleStop" :disabled="systemStatus === 'STOPPED'">
              ⊘ 一键停止
            </a-button>
          </div>
          <div class="setting-row">
            <div>
              <div class="setting-label">恢复运行</div>
              <div class="setting-desc">恢复所有交易和策略运行</div>
            </div>
            <a-button type="primary" size="small" @click="handleResume" :disabled="systemStatus === 'RUNNING'">
              ● 恢复运行
            </a-button>
          </div>
          <div class="setting-row">
            <div>
              <div class="setting-label">是否允许实盘</div>
              <div class="setting-desc">开启后允许使用真实资金交易</div>
            </div>
            <a-switch :checked="liveEnabled" @change="toggleLive" />
          </div>
        </div>
      </a-col>

      <!-- 默认策略参数 -->
      <a-col :span="8">
        <div class="page-card">
          <div class="card-title">≡ 默认策略参数</div>
          <a-form layout="horizontal" :label-col="{ span: 10 }" :wrapper-col="{ span: 14 }" size="small">
            <a-form-item label="默认买入比例 ⓘ">
              <a-input-number v-model:value="defaultParams.tradeAmountPct" :min="1" :max="100" addon-after="%" style="width: 100%" />
            </a-form-item>
            <a-form-item label="默认止损 ⓘ">
              <a-input-number v-model:value="defaultParams.stopLossPct" :min="0.1" :max="50" addon-after="%" style="width: 100%" />
            </a-form-item>
            <a-form-item label="默认止盈 ⓘ">
              <a-input-number v-model:value="defaultParams.takeProfitPct" :min="0.1" :max="100" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-form>
          <a-alert type="info" :show-icon="false" style="margin-top: 12px;">
            <template #message>
              <span style="font-size: 12px;">以上参数作为新建策略的默认值，可在策略详情中单独修改。</span>
            </template>
          </a-alert>
        </div>
      </a-col>

      <!-- 日志与通知 -->
      <a-col :span="8">
        <div class="page-card">
          <div class="card-title">🔔 日志与通知</div>
          <a-form layout="horizontal" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }" size="small">
            <a-form-item label="日志级别 ⓘ">
              <a-select v-model:value="notifySettings.logLevel" style="width: 100%">
                <a-select-option value="info">信息</a-select-option>
                <a-select-option value="warn">警告</a-select-option>
                <a-select-option value="error">错误</a-select-option>
              </a-select>
            </a-form-item>
          </a-form>
          <div class="setting-row">
            <div>
              <div class="setting-label">错误通知</div>
              <div class="setting-desc">发生错误时通过站内通知</div>
            </div>
            <a-switch v-model:checked="notifySettings.errorNotify" />
          </div>
          <div class="setting-row">
            <div>
              <div class="setting-label">邮件提醒</div>
              <div class="setting-desc">接收邮箱</div>
            </div>
            <a-switch v-model:checked="notifySettings.emailNotify" />
          </div>
          <a-input v-if="notifySettings.emailNotify" placeholder="admin@example.com" size="small" style="margin-top: 8px;" />
        </div>
      </a-col>
    </a-row>

    <!-- 安全设置 -->
    <div class="page-card">
      <div class="card-title">🔒 安全设置</div>
      <div class="security-grid">
        <div class="setting-row">
          <div>
            <div class="setting-label">敏感信息加密 ⓘ</div>
            <div class="setting-desc">加密存储 API Key、Secret 等敏感信息</div>
          </div>
          <a-switch :checked="true" disabled />
        </div>
        <div class="setting-row">
          <div>
            <div class="setting-label">操作确认 ⓘ</div>
            <div class="setting-desc">关键操作需二次确认</div>
          </div>
          <a-switch :checked="true" />
        </div>
        <div class="setting-row">
          <div>
            <div class="setting-label">风险提示 ⓘ</div>
            <div class="setting-desc">高风险操作前显示风险提示</div>
          </div>
          <a-switch :checked="true" />
        </div>
      </div>
    </div>

    <!-- 操作日志 -->
    <div class="page-card">
      <div class="card-header">
        <span class="card-title">操作日志</span>
        <a-button size="small">🔄 刷新</a-button>
      </div>
      <a-table
        :columns="opLogColumns"
        :data-source="operationLogs"
        :pagination="{ pageSize: 5, size: 'small', showTotal: (total: number) => `共 ${total} 条` }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'result'">
            <a-tag color="green" size="small">{{ record.result }}</a-tag>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { Modal } from 'ant-design-vue'
import { useSystemStore } from '@/stores/system.store'

const systemStore = useSystemStore()
const systemStatus = computed(() => systemStore.systemStatus)
const liveEnabled = ref(false)

const defaultParams = reactive({ tradeAmountPct: 50, stopLossPct: 2, takeProfitPct: 5 })
const notifySettings = reactive({ logLevel: 'info', errorNotify: true, emailNotify: true })

const opLogColumns = [
  { title: '时间', dataIndex: 'time', key: 'time', width: 160 },
  { title: '操作人', dataIndex: 'user', key: 'user', width: 80 },
  { title: '操作类型', dataIndex: 'type', key: 'type', width: 100 },
  { title: '操作内容', dataIndex: 'content', key: 'content' },
  { title: '结果', key: 'result', width: 60 },
  { title: 'IP地址', dataIndex: 'ip', key: 'ip', width: 120 }
]

const operationLogs = ref([
  { time: '2024-12-04 10:30:00', user: 'admin', type: '修改设置', content: '更新默认买入比例：30% → 50%', result: '成功', ip: '192.168.1.10' },
  { time: '2024-12-04 10:29:15', user: 'admin', type: '修改设置', content: '更新默认止损：1.5% → 2%', result: '成功', ip: '192.168.1.10' },
  { time: '2024-12-04 10:25:42', user: 'admin', type: '系统操作', content: '一键停止系统运行', result: '成功', ip: '192.168.1.10' },
  { time: '2024-12-04 09:58:33', user: 'admin', type: '登录系统', content: '用户登录', result: '成功', ip: '192.168.1.10' }
])

function handleStop() {
  Modal.confirm({
    title: '⚠️ 确认停止系统', content: '停止后系统将不再执行新的下单操作。',
    okText: '确认停止', okType: 'danger', cancelText: '取消',
    onOk: () => systemStore.stopSystem()
  })
}

function handleResume() {
  Modal.confirm({
    title: '确认恢复运行', content: '恢复后系统将继续执行已启用策略的交易判断。',
    okText: '确认恢复', cancelText: '取消',
    onOk: () => systemStore.resumeSystem()
  })
}

function toggleLive(checked: boolean) {
  if (checked) {
    Modal.confirm({
      title: '⚠️ 开启实盘交易', content: '开启后将允许使用真实资金进行交易，请确认风险。',
      okText: '确认开启', okType: 'danger', cancelText: '取消',
      onOk: () => { liveEnabled.value = true },
      onCancel: () => { liveEnabled.value = false }
    })
  } else {
    liveEnabled.value = false
  }
}
</script>

<style lang="scss" scoped>
.system-settings-page {
  .page-header-row { margin-bottom: 20px; }
  .card-title { font-size: 15px; font-weight: 600; color: var(--text-primary); margin-bottom: 16px; }
  .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }

  .setting-row {
    display: flex; justify-content: space-between; align-items: center;
    padding: 12px 0; border-bottom: 1px solid #F3F4F6;

    &:last-child { border-bottom: none; }

    .setting-label { font-size: 14px; color: var(--text-primary); font-weight: 500; }
    .setting-desc { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
  }

  .security-grid {
    max-width: 500px;
  }
}
</style>
