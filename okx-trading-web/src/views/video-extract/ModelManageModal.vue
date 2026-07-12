<template>
  <a-modal
    :open="open"
    title="LLM 模型管理"
    width="860px"
    :footer="null"
    destroy-on-close
    @cancel="emit('update:open', false)"
  >
    <div class="modal-toolbar">
      <span class="hint">模型保存在数据库，供应商 API Key 仍在后端 yml 的 ai.providers 中配置。</span>
      <a-button type="primary" @click="openForm()">
        <template #icon><PlusOutlined /></template>
        添加模型
      </a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="list"
      :loading="loading"
      row-key="id"
      size="small"
      :pagination="{ pageSize: 8, showSizeChanger: false }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'provider'">
          <a-tag>{{ record.providerName || record.provider }}</a-tag>
          <span class="mono muted">{{ record.provider }}</span>
        </template>
        <template v-else-if="column.key === 'modelId'">
          <span class="mono">{{ record.modelId }}</span>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-switch
            :checked="record.enabled"
            size="small"
            :loading="togglingId === record.id"
            @change="(v: boolean) => toggleEnabled(record, v)"
          />
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="openForm(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="onDelete(record)">删除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑表单 -->
    <a-modal
      v-model:open="formOpen"
      :title="editingId ? '编辑模型' : '添加模型'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      @ok="submitForm"
    >
      <a-form layout="vertical" class="cfg-form">
        <a-form-item label="供应商" required>
          <a-select
            v-model:value="form.provider"
            placeholder="选择供应商"
            :options="providerOptions.map((p) => ({ value: p.key, label: `${p.name} (${p.key})` }))"
          />
        </a-form-item>
        <a-form-item label="模型 ID（调用 API 用）" required>
          <a-input
            v-model:value="form.modelId"
            placeholder="例如 deepseek-ai/deepseek-v4-flash 或 z-ai/glm-5.2"
          />
        </a-form-item>
        <a-form-item label="显示名称" required>
          <a-input v-model:value="form.modelName" placeholder="例如 DeepSeek V4 Flash" />
        </a-form-item>
        <a-form-item label="排序（越小越靠前）">
          <a-input-number v-model:value="form.sortOrder" :min="0" :max="9999" style="width: 100%" />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="form.remark" placeholder="可选" />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, watch, reactive } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { videoApi } from '@/api/video.api'
import type { AiModelConfig, AiProviderOption } from '@/types/api'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'changed'): void
}>()

const loading = ref(false)
const list = ref<AiModelConfig[]>([])
const providerOptions = ref<AiProviderOption[]>([])
const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const togglingId = ref('')

const form = reactive({
  provider: 'nvidia',
  modelId: '',
  modelName: '',
  enabled: true,
  sortOrder: 0,
  remark: ''
})

const columns = [
  { title: '供应商', key: 'provider', width: 160 },
  { title: '显示名称', dataIndex: 'modelName', key: 'modelName', width: 160 },
  { title: '模型 ID', key: 'modelId' },
  { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 70 },
  { title: '启用', key: 'enabled', width: 70 },
  { title: '操作', key: 'action', width: 120 }
]

watch(
  () => props.open,
  (v) => {
    if (v) {
      loadAll()
    }
  }
)

async function loadAll() {
  loading.value = true
  try {
    const [cfgRes, provRes] = await Promise.all([
      videoApi.listModelConfigs(),
      videoApi.listProviders()
    ])
    list.value = cfgRes.data || []
    providerOptions.value = provRes.data || []
    if (!form.provider && providerOptions.value.length) {
      form.provider = providerOptions.value[0].key
    }
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function openForm(row?: AiModelConfig) {
  if (row) {
    editingId.value = row.id
    form.provider = row.provider
    form.modelId = row.modelId
    form.modelName = row.modelName
    form.enabled = row.enabled
    form.sortOrder = row.sortOrder ?? 0
    form.remark = row.remark || ''
  } else {
    editingId.value = null
    form.provider = providerOptions.value[0]?.key || 'nvidia'
    form.modelId = ''
    form.modelName = ''
    form.enabled = true
    form.sortOrder = (list.value.length + 1) * 10
    form.remark = ''
  }
  formOpen.value = true
}

async function submitForm() {
  if (!form.provider?.trim() || !form.modelId?.trim() || !form.modelName?.trim()) {
    message.warning('请填写供应商、模型 ID 和显示名称')
    return Promise.reject()
  }
  saving.value = true
  try {
    const body = {
      provider: form.provider.trim(),
      modelId: form.modelId.trim(),
      modelName: form.modelName.trim(),
      enabled: form.enabled,
      sortOrder: form.sortOrder ?? 0,
      remark: form.remark?.trim() || undefined
    }
    if (editingId.value) {
      await videoApi.updateModelConfig(editingId.value, body)
      message.success('已更新')
    } else {
      await videoApi.createModelConfig(body)
      message.success('已添加')
    }
    formOpen.value = false
    await loadAll()
    emit('changed')
  } catch {
    return Promise.reject()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: AiModelConfig, enabled: boolean) {
  togglingId.value = row.id
  try {
    await videoApi.updateModelConfig(row.id, {
      provider: row.provider,
      modelId: row.modelId,
      modelName: row.modelName,
      enabled,
      sortOrder: row.sortOrder,
      remark: row.remark || undefined
    })
    row.enabled = enabled
    emit('changed')
  } catch {
    // revert handled by reload
    await loadAll()
  } finally {
    togglingId.value = ''
  }
}

function onDelete(row: AiModelConfig) {
  Modal.confirm({
    title: '删除模型配置？',
    content: `${row.modelName}（${row.modelId}）删除后任务选择列表中将不再显示。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await videoApi.deleteModelConfig(row.id)
      message.success('已删除')
      await loadAll()
      emit('changed')
    }
  })
}
</script>

<style lang="scss" scoped>
.modal-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;

  .hint {
    font-size: 12px;
    color: var(--text-secondary);
  }
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.muted {
  color: var(--text-muted);
  margin-left: 6px;
}

.cfg-form {
  margin-top: 8px;
}
</style>
