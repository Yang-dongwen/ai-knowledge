<template>
  <a-modal
    :open="open"
    :title="modalTitle"
    width="920px"
    :footer="null"
    destroy-on-close
    @cancel="emit('update:open', false)"
  >
    <div class="modal-toolbar">
      <span class="hint">{{ modalHint }}</span>
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
      :scroll="{ x: 880 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'provider'">
          <a-tag>{{ record.providerName || record.provider }}</a-tag>
          <span class="mono muted">{{ record.provider }}</span>
        </template>
        <template v-else-if="column.key === 'capability'">
          <a-tag :color="record.capability === 'image' ? 'green' : 'blue'">
            {{ record.capability === 'image' ? '文生图' : 'Chat' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'modelId'">
          <span class="mono">{{ record.modelId }}</span>
        </template>
        <template v-else-if="column.key === 'invokeUrl'">
          <span class="mono muted" :title="record.invokeUrl || ''">
            {{ shortUrl(record.invokeUrl) }}
          </span>
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

    <a-modal
      v-model:open="formOpen"
      :title="editingId ? '编辑模型' : '添加模型'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      @ok="submitForm"
    >
      <a-form layout="vertical" class="cfg-form">
        <a-form-item label="能力类型" required>
          <a-radio-group v-model:value="form.capability" button-style="solid" :disabled="!!lockedCapability">
            <a-radio-button value="chat">Chat（对话/润色/分镜）</a-radio-button>
            <a-radio-button value="image">文生图 Image</a-radio-button>
          </a-radio-group>
        </a-form-item>
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
            :placeholder="
              form.capability === 'image'
                ? '例如 black-forest-labs/flux.1-schnell'
                : '例如 deepseek-ai/deepseek-v4-flash'
            "
          />
        </a-form-item>
        <a-form-item label="显示名称" required>
          <a-input
            v-model:value="form.modelName"
            :placeholder="form.capability === 'image' ? '例如 FLUX.1-schnell（快速）' : '例如 DeepSeek V4 Flash'"
          />
        </a-form-item>
        <template v-if="form.capability === 'image'">
          <a-form-item label="协议" required>
            <a-select
              v-model:value="form.protocol"
              :options="protocolOptions"
              placeholder="选择生图协议"
            />
          </a-form-item>
          <a-form-item label="Invoke URL" required>
            <a-input
              v-model:value="form.invokeUrl"
              :placeholder="invokePlaceholder"
            />
          </a-form-item>
          <a-form-item label="默认步数">
            <a-input-number v-model:value="form.defaultSteps" :min="1" :max="100" style="width: 100%" />
          </a-form-item>
          <a-form-item label="最大步数">
            <a-input-number v-model:value="form.maxSteps" :min="1" :max="100" style="width: 100%" />
          </a-form-item>
        </template>
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
import { ref, watch, reactive, computed } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { videoApi } from '@/api/video.api'
import type { AiModelConfig, AiProviderOption } from '@/types/api'

const props = withDefaults(
  defineProps<{
    open: boolean
    /**
     * 锁定能力：打开时只管理该类型（image-generate 页传 image，视频页传 chat）。
     * 不传则展示全部，表单可切换类型。
     */
    capability?: 'chat' | 'image'
  }>(),
  { capability: undefined }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'changed'): void
}>()

const lockedCapability = computed(() => props.capability || null)

const modalTitle = computed(() => {
  if (props.capability === 'image') return '文生图模型管理'
  if (props.capability === 'chat') return 'Chat 模型管理'
  return 'AI 模型管理'
})

const modalHint = computed(() => {
  if (props.capability === 'image') {
    return '生图模型存库（capability=image）。须填 Invoke URL；供应商 API Key 在 yml ai.providers。'
  }
  return '模型保存在数据库，供应商 API Key 仍在后端 yml 的 ai.providers 中配置。'
})

const loading = ref(false)
const list = ref<AiModelConfig[]>([])
const providerOptions = ref<AiProviderOption[]>([])
const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const togglingId = ref('')

const form = reactive({
  capability: 'chat' as 'chat' | 'image',
  provider: 'nvidia',
  modelId: '',
  modelName: '',
  invokeUrl: '',
  defaultSteps: 4,
  maxSteps: 50,
  protocol: 'nvidia-flux' as string,
  enabled: true,
  sortOrder: 0,
  remark: ''
})

const protocolOptions = [
  { value: 'nvidia-flux', label: 'nvidia-flux（FLUX 云端 GenAI）' },
  {
    value: 'nvidia-qwen',
    label: 'nvidia-qwen（Qwen → /v1/images/generations）'
  },
  {
    value: 'nvidia-openai-images',
    label: 'nvidia-openai-images（OpenAI Images 兼容）'
  },
  {
    value: 'nvidia-qwen-infer',
    label: 'nvidia-qwen-infer（自托管 /v1/infer）'
  }
]

const invokePlaceholder = computed(() => {
  if (form.protocol === 'nvidia-qwen' || form.protocol === 'nvidia-openai-images') {
    return 'http://127.0.0.1:8000/v1/images/generations（自托管 NIM；勿用 /v1/genai/qwen/...）'
  }
  if (form.protocol === 'nvidia-qwen-infer') {
    return 'http://127.0.0.1:8000/v1/infer'
  }
  return 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell'
})

const columns = computed(() => {
  const cols: any[] = [
    { title: '供应商', key: 'provider', width: 140 },
    { title: '类型', key: 'capability', width: 80 },
    { title: '显示名称', dataIndex: 'modelName', key: 'modelName', width: 140 },
    { title: '模型 ID', key: 'modelId', width: 200 }
  ]
  if (props.capability === 'image' || !props.capability) {
    cols.push({ title: 'Invoke URL', key: 'invokeUrl', width: 160 })
    cols.push({ title: '步数', key: 'steps', width: 80, customRender: ({ record }: any) => {
      if (record.capability !== 'image') return '—'
      return `${record.defaultSteps ?? '—'} / ${record.maxSteps ?? '—'}`
    }})
  }
  cols.push(
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 60 },
    { title: '启用', key: 'enabled', width: 70 },
    { title: '操作', key: 'action', width: 120, fixed: 'right' }
  )
  return cols
})

watch(
  () => props.open,
  (v) => {
    if (v) {
      loadAll()
    }
  }
)

function shortUrl(u?: string | null) {
  if (!u) return '—'
  if (u.length <= 36) return u
  return u.slice(0, 18) + '…' + u.slice(-14)
}

function inferProtocol(modelId?: string | null, invokeUrl?: string | null) {
  const m = (modelId || '').toLowerCase()
  const u = (invokeUrl || '').toLowerCase()
  if (u.includes('images/generations') || u.includes('/v1/images')) return 'nvidia-openai-images'
  if (m.includes('qwen') || u.includes('qwen')) return 'nvidia-qwen'
  return 'nvidia-flux'
}

async function loadAll() {
  loading.value = true
  try {
    const [cfgRes, provRes] = await Promise.all([
      videoApi.listModelConfigs(props.capability),
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
    form.capability = (row.capability === 'image' ? 'image' : 'chat')
    form.provider = row.provider
    form.modelId = row.modelId
    form.modelName = row.modelName
    form.invokeUrl = row.invokeUrl || ''
    form.defaultSteps = row.defaultSteps ?? 4
    form.maxSteps = row.maxSteps ?? 50
    form.protocol = row.protocol || inferProtocol(row.modelId, row.invokeUrl)
    form.enabled = row.enabled
    form.sortOrder = row.sortOrder ?? 0
    form.remark = row.remark || ''
  } else {
    editingId.value = null
    form.capability = props.capability || 'chat'
    form.provider = providerOptions.value[0]?.key || 'nvidia'
    form.modelId = ''
    form.modelName = ''
    form.protocol = form.capability === 'image' ? 'nvidia-flux' : 'nvidia-flux'
    form.invokeUrl =
      form.capability === 'image'
        ? 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell'
        : ''
    form.defaultSteps = form.capability === 'image' ? 4 : 4
    form.maxSteps = form.capability === 'image' ? 50 : 50
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
  if (form.capability === 'image' && !form.invokeUrl?.trim()) {
    message.warning('文生图模型必须填写 Invoke URL')
    return Promise.reject()
  }
  saving.value = true
  try {
    const body: any = {
      provider: form.provider.trim(),
      modelId: form.modelId.trim(),
      modelName: form.modelName.trim(),
      capability: form.capability,
      enabled: form.enabled,
      sortOrder: form.sortOrder ?? 0,
      remark: form.remark?.trim() || undefined
    }
    if (form.capability === 'image') {
      body.invokeUrl = form.invokeUrl.trim()
      body.defaultSteps = form.defaultSteps ?? 4
      body.maxSteps = form.maxSteps ?? 50
      body.protocol = form.protocol || inferProtocol(form.modelId, form.invokeUrl)
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
  } catch (e: any) {
    message.error(e?.message || '保存失败')
    return Promise.reject()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(record: AiModelConfig, enabled: boolean) {
  togglingId.value = record.id
  try {
    await videoApi.updateModelConfig(record.id, {
      provider: record.provider,
      modelId: record.modelId,
      modelName: record.modelName,
      capability: record.capability || 'chat',
      invokeUrl: record.invokeUrl || undefined,
      defaultSteps: record.defaultSteps ?? undefined,
      maxSteps: record.maxSteps ?? undefined,
      protocol: record.protocol || undefined,
      enabled,
      sortOrder: record.sortOrder ?? 0,
      remark: record.remark || undefined
    })
    record.enabled = enabled
    emit('changed')
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    togglingId.value = ''
  }
}

function onDelete(record: AiModelConfig) {
  Modal.confirm({
    title: '删除模型？',
    content: `${record.modelName}（${record.modelId}）`,
    okType: 'danger',
    async onOk() {
      await videoApi.deleteModelConfig(record.id)
      message.success('已删除')
      await loadAll()
      emit('changed')
    }
  })
}
</script>

<style scoped lang="scss">
.modal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.hint {
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.muted {
  color: #94a3b8;
  margin-left: 6px;
}
.cfg-form {
  max-height: 60vh;
  overflow-y: auto;
}
</style>
