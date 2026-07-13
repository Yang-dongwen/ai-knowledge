<template>
  <div class="aigen-page">
    <div class="submit-hero page-card">
      <div class="hero-text">
        <div class="hero-title-row">
          <h2 class="page-title">AI 文生图</h2>
          <a-tooltip :title="liveChannelTip">
            <span class="live-badge" :class="liveChannel">
              <span class="live-dot" />
              <span class="live-label">{{ liveChannelLabel }}</span>
            </span>
          </a-tooltip>
        </div>
        <p class="page-subtitle">
          输入提示词，调用 NVIDIA FLUX 生成图片 · 支持多比例与再生成
        </p>
        <div class="pipeline-row">
          <span class="pipe-chip"><i>1</i>润色（可选）</span>
          <span class="pipe-sep" />
          <span class="pipe-chip"><i>2</i>FLUX 出图</span>
          <span class="pipe-sep" />
          <span class="pipe-chip"><i>3</i>预览下载</span>
        </div>
      </div>

      <div class="form-block">
        <div class="field-label-row">
          <label class="field-label">创作提示词</label>
          <span class="char-meter" :class="{ warn: prompt.length > 1800 }">
            <i :style="{ width: `${Math.min(100, (prompt.length / 2000) * 100)}%` }" />
            <em>{{ prompt.length }} / 2000</em>
          </span>
        </div>
        <a-textarea
          v-model:value="prompt"
          :rows="4"
          :maxlength="2000"
          :show-count="false"
          class="prompt-textarea"
          placeholder="例如：赛博朋克风格的东京夜景，霓虹倒映在雨后街道，电影感光影"
          @pressEnter.ctrl="handleSubmit"
        />
      </div>

      <div class="form-block" style="margin-top: 12px">
        <label class="field-label">负向提示词（可选）</label>
        <a-textarea
          v-model:value="negativePrompt"
          :rows="2"
          :maxlength="500"
          class="prompt-textarea"
          placeholder="blurry, low quality, text, watermark …"
        />
      </div>

      <!-- 生图模型（数据库 capability=image） -->
      <div class="model-row">
        <div class="model-pick">
          <span class="opt-label">生图模型</span>
          <a-select
            v-model:value="selectedImageKey"
            class="model-select"
            size="large"
            show-search
            :options="imageModelOptions"
            :filter-option="filterOption"
            :loading="imageModelsLoading"
            placeholder="选择文生图模型（库表维护）"
          />
          <a-button
            v-if="auth.isSuperAdmin"
            size="large"
            class="manage-btn"
            @click="modelManageOpen = true"
          >
            <template #icon><SettingOutlined /></template>
            生图模型管理
          </a-button>
        </div>
        <div class="model-status" v-if="selectedImageModel">
          <a-tag color="blue">{{ selectedImageModel.name }}</a-tag>
          <a-tag v-if="selectedImageModel.protocol" color="purple">{{ selectedImageModel.protocol }}</a-tag>
          <span class="hint" style="font-size: 12px; color: #94a3b8">
            {{ selectedImageModel.provider }} · 默认 {{ selectedImageModel.defaultSteps ?? 4 }} 步
            <template v-if="selectedImageModel.description"> · {{ selectedImageModel.description }}</template>
          </span>
        </div>
        <div class="model-status muted" v-else-if="!imageModelsLoading && !imageModels.length">
          <a-tag color="warning">
            暂无生图模型
            <template v-if="auth.isSuperAdmin"> — 请打开「生图模型管理」添加（capability=image + Invoke URL）</template>
            <template v-else> — 请联系超级管理员配置</template>
          </a-tag>
        </div>
      </div>

      <!-- Prompt 润色 + Chat 模型 -->
      <div class="model-row" style="margin-top: 10px">
        <div class="model-pick" style="flex-wrap: wrap; gap: 10px">
          <span class="opt-label">Prompt 润色</span>
          <a-switch v-model:checked="enhancePrompt" checked-children="开" un-checked-children="关" />
          <template v-if="enhancePrompt">
            <span class="opt-label">润色 Chat 模型</span>
            <a-select
              v-model:value="selectedLlmKey"
              class="model-select"
              size="large"
              show-search
              :options="llmSelectOptions"
              :filter-option="filterOption"
              :loading="llmModelsLoading"
              placeholder="选择用于润色的 Chat 模型"
            />
            <a-button
              size="large"
              class="test-btn"
              :loading="testingLlm"
              :disabled="!selectedLlmKey"
              @click="handleTestLlm"
            >
              <template #icon><ExperimentOutlined /></template>
              测试可用性
            </a-button>
            <a-button
              v-if="auth.isSuperAdmin"
              size="large"
              class="manage-btn"
              @click="chatModelManageOpen = true"
            >
              <template #icon><SettingOutlined /></template>
              Chat 模型
            </a-button>
          </template>
        </div>
        <div class="model-status" v-if="enhancePrompt && selectedLlmKey">
          <a-tag v-if="testingLlm" color="processing">测试中…</a-tag>
          <a-tag v-else-if="llmTestStatus === 'ok'" color="success">
            ✓ 可用{{ llmTestLatency != null ? ` · ${llmTestLatency}ms` : '' }}
          </a-tag>
          <a-tag v-else-if="llmTestStatus === 'fail'" color="error">不可用</a-tag>
          <a-tag v-else color="default">未测试（可选）</a-tag>
        </div>
        <div class="model-status muted" v-else-if="enhancePrompt && !llmModelsLoading && !llmSelectOptions.length">
          <a-tag color="warning">
            暂无 Chat 模型
            <template v-if="auth.isSuperAdmin"> — 请打开「模型管理」添加</template>
            <template v-else> — 请联系超级管理员配置</template>
          </a-tag>
        </div>
      </div>

      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="modelManageOpen"
        capability="image"
        @changed="onImageModelsChanged"
      />
      <!-- Chat 模型管理（润色用）单独入口：与视频页同一套 capability=chat -->
      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="chatModelManageOpen"
        capability="chat"
        @changed="onLlmModelsChanged"
      />

      <div class="options-grid">
        <div class="opt">
          <span class="field-label">画幅</span>
          <a-radio-group v-model:value="aspectRatio" button-style="solid" size="middle">
            <a-radio-button value="1:1">方形 1:1</a-radio-button>
            <a-radio-button value="16:9">横屏 16:9</a-radio-button>
            <a-radio-button value="9:16">竖屏 9:16</a-radio-button>
          </a-radio-group>
        </div>
        <div class="opt">
          <span class="field-label">张数</span>
          <a-radio-group v-model:value="imageCount" button-style="solid" size="middle">
            <a-radio-button :value="1">1 张</a-radio-button>
            <a-radio-button :value="2">2 张</a-radio-button>
            <a-radio-button :value="4">4 张</a-radio-button>
          </a-radio-group>
        </div>
        <div class="opt">
          <span class="field-label">Seed（可选）</span>
          <a-input-number
            v-model:value="seed"
            class="full"
            :min="0"
            :max="2147483647"
            placeholder="空=随机"
            style="width: 100%"
          />
        </div>
      </div>

      <div class="submit-actions">
        <a-button
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          <template #icon><ThunderboltOutlined /></template>
          生成图片
        </a-button>
        <span class="hint">
          Ctrl + Enter 提交
          <template v-if="selectedImageModel"> · {{ selectedImageModel.name }}</template>
        </span>
      </div>
    </div>

    <div class="workspace">
      <aside class="task-panel page-card">
        <div class="panel-header">
          <span class="panel-title">任务列表</span>
          <a-button type="text" size="small" :loading="listLoading" @click="loadTasks">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </div>

        <a-empty v-if="!listLoading && !tasks.length" description="暂无任务，输入提示词开始生成" />

        <div v-else class="task-list">
          <div
            v-for="task in tasks"
            :key="task.id"
            class="task-item"
            :class="{ active: selectedId === task.id }"
            @click="selectTask(task.id)"
          >
            <div class="task-item-top">
              <a-tag :color="statusMeta(task.status).color" class="status-tag">
                {{ statusMeta(task.status).label }}
              </a-tag>
              <div class="task-item-actions">
                <span class="task-time">{{ shortTime(task.createdAt) }}</span>
                <a-button
                  v-if="canRetry(task.status)"
                  type="text"
                  size="small"
                  class="task-retry-btn"
                  :loading="retryingId === task.id"
                  title="再生成（可改模型）"
                  @click.stop="openRetryModal(task)"
                >
                  <template #icon><RedoOutlined /></template>
                </a-button>
                <a-button
                  type="text"
                  size="small"
                  danger
                  class="task-del-btn"
                  :loading="deletingId === task.id"
                  @click.stop="confirmDelete(task)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </div>
            </div>
            <div class="task-title" :title="task.title || task.prompt">
              {{ task.title || task.prompt }}
            </div>
            <div class="task-meta">
              <span>{{ task.aspectRatio || '1:1' }}</span>
              <span>{{ task.n || 1 }} 张</span>
              <span v-if="task.model" class="model-chip" :title="task.model">
                {{ shortModelName(task.model) }}
              </span>
              <span v-if="task.enhanceEnabled && task.llmModel" class="model-chip" :title="task.llmModel">
                润色:{{ shortModelName(task.llmModel) }}
              </span>
              <span v-if="task.totalDurationMs" class="dur-chip">
                {{ formatMs(task.totalDurationMs) }}
              </span>
            </div>
            <a-progress
              v-if="isRunning(task.status)"
              :percent="task.progress || 0"
              size="small"
              :show-info="true"
              status="active"
            />
            <div v-if="task.currentStep" class="task-step">{{ task.currentStep }}</div>
          </div>
        </div>
      </aside>

      <section class="detail-panel page-card">
        <template v-if="selected">
          <div class="detail-header">
            <div class="detail-header-main">
              <a-tag :color="statusMeta(selected.status).color">
                {{ statusMeta(selected.status).label }}
              </a-tag>
              <h3 class="detail-title">{{ selected.title || '未命名任务' }}</h3>
              <div class="detail-sub">
                <span v-if="selected.aspectRatio">{{ selected.aspectRatio }}</span>
                <span v-if="selected.width && selected.height">
                  {{ selected.width }}×{{ selected.height }}
                </span>
                <span v-if="selected.n">{{ selected.n }} 张</span>
                <span v-if="selected.model" class="model-chip">{{ shortModelName(selected.model) }}</span>
                <span v-if="selected.createdAt">创建于 {{ selected.createdAt }}</span>
              </div>
            </div>
            <a-space>
              <a-button type="text" :loading="listLoading" @click="loadTasks">
                <template #icon><ReloadOutlined /></template>
              </a-button>
              <a-button v-if="canCancel(selected.status)" @click="handleCancel">
                <template #icon><StopOutlined /></template>
                取消
              </a-button>
              <a-button
                v-if="canRetry(selected.status)"
                type="primary"
                ghost
                :loading="retryingId === selected.id"
                @click="openRetryModal(selected)"
              >
                <template #icon><RedoOutlined /></template>
                再生成
              </a-button>
              <a-button
                type="text"
                danger
                :loading="deletingId === selected.id"
                @click="confirmDelete(selected)"
              >
                <template #icon><DeleteOutlined /></template>
                删除
              </a-button>
            </a-space>
          </div>

          <div class="progress-block" v-if="isRunning(selected.status)">
            <a-steps
              size="small"
              :current="stepIndex(selected.status)"
              status="process"
              :items="[
                { title: '排队' },
                { title: '润色' },
                { title: '出图' }
              ]"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ selected.currentStep || '处理中…' }}</span>
            </div>
          </div>

          <div
            class="timing-panel"
            v-if="!isRunning(selected.status) && (selected.generateDurationMs || selected.totalDurationMs)"
          >
            <div class="timing-panel-title">
              <span>执行耗时</span>
              <span class="timing-total" v-if="selected.totalDurationMs">
                总计 {{ formatMs(selected.totalDurationMs) }}
              </span>
            </div>
            <div class="timing-strip">
              <span class="timing-chip" v-if="selected.enhanceDurationMs">
                润色 <b>{{ formatMs(selected.enhanceDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="selected.generateDurationMs">
                出图 <b>{{ formatMs(selected.generateDurationMs) }}</b>
              </span>
            </div>
          </div>

          <!-- 再生成：可换生图模型 / 润色模型 -->
          <a-modal
            v-model:open="retryModalOpen"
            :title="retryTarget?.status === 'SUCCESS' ? '重新生成' : '重试任务'"
            :ok-text="retryTarget?.status === 'SUCCESS' ? '开始重新生成' : '开始重试'"
            cancel-text="取消"
            :confirm-loading="retryingId === retryTarget?.id"
            @ok="submitRetry"
          >
            <p class="retry-hint">可更换生图模型；若开启润色，可更换 Chat 模型。</p>
            <div class="retry-model-row" style="margin-bottom: 12px">
              <span class="opt-label">生图模型</span>
              <a-select
                v-model:value="retryImageKey"
                class="model-select"
                show-search
                :options="imageModelOptions"
                :filter-option="filterOption"
                style="width: 100%; margin-top: 6px"
              />
            </div>
            <div class="retry-model-row" style="margin-bottom: 12px">
              <span class="opt-label">Prompt 润色</span>
              <a-switch v-model:checked="retryEnhance" style="margin-left: 8px" />
            </div>
            <div class="retry-model-row" v-if="retryEnhance">
              <span class="opt-label">润色 Chat 模型</span>
              <a-select
                v-model:value="retryLlmKey"
                class="model-select"
                show-search
                :options="llmSelectOptions"
                :filter-option="filterOption"
                style="width: 100%; margin-top: 6px"
              />
            </div>
          </a-modal>

          <a-alert
            v-if="selected.status === 'FAILED' && selected.errorMessage"
            type="error"
            show-icon
            class="fail-alert"
            message="生成失败"
            :description="selected.errorMessage"
          />

          <div class="detail-grid">
            <div class="detail-row prompt-row">
              <span class="k">提示词</span>
              <div class="v prompt-cell">
                <p class="prompt-text">{{ selected.prompt }}</p>
              </div>
            </div>
            <div class="detail-row prompt-row" v-if="selected.enhancedPrompt">
              <span class="k">润色后</span>
              <div class="v prompt-cell">
                <p class="prompt-text">{{ selected.enhancedPrompt }}</p>
              </div>
            </div>
            <div class="detail-row" v-if="selected.negativePrompt">
              <span class="k">负向词</span>
              <span class="v">{{ selected.negativePrompt }}</span>
            </div>
            <div class="detail-row">
              <span class="k">生图模型</span>
              <span class="v">{{ selected.provider || '—' }} / {{ selected.model || '—' }}</span>
            </div>
            <div class="detail-row">
              <span class="k">润色模型</span>
              <span class="v" v-if="selected.enhanceEnabled">
                {{ selected.llmProvider || '—' }} / {{ selected.llmModel || '—' }}
              </span>
              <span class="v" v-else>未启用</span>
            </div>
          </div>

          <div v-if="selected.outputAvailable && displayImages.length" class="player-box">
            <div class="player-head">
              <div class="player-head-left">
                <span class="player-label">图片预览</span>
                <span class="player-state ok">{{ displayImages.length }} 张</span>
              </div>
            </div>
            <div class="img-grid">
              <div v-for="img in displayImages" :key="img.index" class="img-card">
                <div class="img-frame" @click="previewImage(img)">
                  <a-spin v-if="imageLoading[img.index]" />
                  <img v-else-if="imageUrls[img.index]" :src="imageUrls[img.index]" alt="" />
                  <span v-else class="img-fallback">加载失败</span>
                </div>
                <div class="img-actions">
                  <span class="img-meta">#{{ img.index }} · seed {{ img.seed ?? '—' }}</span>
                  <a-button
                    type="link"
                    size="small"
                    :disabled="!imageUrls[img.index]"
                    @click="downloadImage(img)"
                  >
                    <DownloadOutlined /> 下载
                  </a-button>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="player-placeholder">
            <span v-if="selected.status === 'SUCCESS'">流程已完成但暂无图片文件</span>
            <span v-else>生成成功后将在此预览图片</span>
          </div>
        </template>
        <a-empty v-else description="选择左侧任务查看详情" />
      </section>
    </div>

    <a-modal
      v-model:open="previewOpen"
      :footer="null"
      width="720px"
      centered
      destroy-on-close
    >
      <img v-if="previewUrl" :src="previewUrl" alt="" style="width: 100%; border-radius: 8px" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, createVNode } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ThunderboltOutlined,
  ReloadOutlined,
  DeleteOutlined,
  RedoOutlined,
  StopOutlined,
  DownloadOutlined,
  ExperimentOutlined,
  SettingOutlined,
  ExclamationCircleOutlined
} from '@ant-design/icons-vue'
import { imggenApi } from '@/api/imggen.api'
import { connectImgGenTaskEvents } from '@/api/imggen.events'
import { videoApi } from '@/api/video.api'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
import type { AiProvider, ImgGenImageFile, ImgGenImageModel, ImgGenTaskItem } from '@/types/api'

const auth = useAuthStore()

const prompt = ref('')
const negativePrompt = ref('')
const aspectRatio = ref('1:1')
const imageCount = ref(1)
const seed = ref<number | null>(null)
const enhancePrompt = ref(false)

/** 生图模型（库表 capability=image；key = provider::modelId） */
const imageModels = ref<ImgGenImageModel[]>([])
const imageModelsLoading = ref(false)
const selectedImageKey = ref('')

/** 润色 Chat 模型（复用 video 模型列表） */
const availableProviders = ref<AiProvider[]>([])
const llmModelsLoading = ref(false)
const selectedLlmKey = ref('')
const testingLlm = ref(false)
const testedOkKeys = ref<Set<string>>(new Set())
const testedFailKeys = ref<Set<string>>(new Set())
const llmTestLatency = ref<number | null>(null)
const modelManageOpen = ref(false)
const chatModelManageOpen = ref(false)

/** 再生成弹窗 */
const retryModalOpen = ref(false)
const retryTarget = ref<ImgGenTaskItem | null>(null)
const retryImageKey = ref('')
const retryEnhance = ref(false)
const retryLlmKey = ref('')

const submitting = ref(false)
const listLoading = ref(false)
const tasks = ref<ImgGenTaskItem[]>([])
const selectedId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const retryingId = ref<string | null>(null)

const liveChannel = ref<'live' | 'poll' | 'off'>('off')
const imageUrls = ref<Record<number, string>>({})
const imageLoading = ref<Record<number, boolean>>({})
const previewOpen = ref(false)
const previewUrl = ref('')

let sseCloser: { close: () => void } | null = null
let pollTimer: ReturnType<typeof setInterval> | null = null

function imageKey(m: { provider?: string | null; id: string }) {
  return `${m.provider || 'nvidia'}::${m.id}`
}

const imageModelOptions = computed(() =>
  imageModels.value.map((m) => ({
    value: imageKey(m),
    label: `${m.name || m.id}${m.provider ? ` · ${m.provider}` : ''}`,
    title: m.description || m.id
  }))
)

const selectedImageModel = computed(() => {
  const parsed = parseLlmKey(selectedImageKey.value)
  if (!parsed) return null
  return (
    imageModels.value.find((m) => m.id === parsed.model && (m.provider || 'nvidia') === parsed.provider) ||
    imageModels.value.find((m) => m.id === parsed.model) ||
    null
  )
})

const llmSelectOptions = computed(() =>
  availableProviders.value.map((p) => ({
    label: p.name,
    options: (p.models || []).map((m) => ({
      label: m.name || m.id,
      value: `${p.key}::${m.id}`,
      title: m.id
    }))
  }))
)

const llmTestStatus = computed<'ok' | 'fail' | 'none'>(() => {
  if (!selectedLlmKey.value) return 'none'
  if (testedOkKeys.value.has(selectedLlmKey.value)) return 'ok'
  if (testedFailKeys.value.has(selectedLlmKey.value)) return 'fail'
  return 'none'
})

const canSubmit = computed(() => {
  if (!prompt.value.trim() || submitting.value) return false
  if (!selectedImageKey.value) return false
  if (enhancePrompt.value && !selectedLlmKey.value) return false
  return true
})

const selected = computed(() => tasks.value.find((t) => t.id === selectedId.value) || null)

const displayImages = computed(() => {
  const s = selected.value
  if (!s?.images?.length) return [] as ImgGenImageFile[]
  return s.images
})

const liveChannelLabel = computed(() => {
  if (liveChannel.value === 'live') return '实时'
  if (liveChannel.value === 'poll') return '轮询'
  return '离线'
})

const liveChannelTip = computed(() => {
  if (liveChannel.value === 'live') return 'SSE 已连接，任务进度实时推送'
  if (liveChannel.value === 'poll') return 'SSE 不可用，已回退定时刷新'
  return '未建立实时通道'
})

function statusMeta(status: string) {
  const map: Record<string, { label: string; color: string }> = {
    PENDING: { label: '排队中', color: 'default' },
    PROMPT_ENHANCING: { label: '润色中', color: 'processing' },
    GENERATING: { label: '生成中', color: 'processing' },
    SUCCESS: { label: '已完成', color: 'success' },
    FAILED: { label: '失败', color: 'error' },
    CANCELLED: { label: '已取消', color: 'default' },
    PAUSED: { label: '已暂停', color: 'warning' }
  }
  return map[status] || { label: status || '未知', color: 'default' }
}

function isRunning(status: string) {
  return status === 'PENDING' || status === 'PROMPT_ENHANCING' || status === 'GENERATING'
}

function canCancel(status: string) {
  return isRunning(status)
}

function canRetry(status: string) {
  return status === 'FAILED' || status === 'CANCELLED' || status === 'PAUSED' || status === 'SUCCESS'
}

function stepIndex(status: string) {
  if (status === 'PENDING') return 0
  if (status === 'PROMPT_ENHANCING') return 1
  if (status === 'GENERATING') return 2
  if (status === 'SUCCESS') return 3
  return 0
}

function shortTime(t?: string | null) {
  if (!t) return ''
  return t.slice(5, 16)
}

function formatMs(ms?: number | null) {
  if (ms == null || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function shortModelName(model?: string | null) {
  if (!model) return ''
  const parts = model.split('/')
  return parts[parts.length - 1] || model
}

function filterOption(input: string, option: any) {
  const label = String(option?.label ?? option?.title ?? '')
  const value = String(option?.value ?? '')
  const q = input.toLowerCase()
  return label.toLowerCase().includes(q) || value.toLowerCase().includes(q)
}

function parseLlmKey(key: string): { provider: string; model: string } | null {
  if (!key || !key.includes('::')) return null
  const i = key.indexOf('::')
  return { provider: key.slice(0, i), model: key.slice(i + 2) }
}

function fileNameFromImage(img: ImgGenImageFile) {
  if (img.path && img.path.includes('/')) {
    return img.path.split('/').pop() || `img-${img.index}.png`
  }
  return img.path || `img-${String(img.index).padStart(2, '0')}.png`
}

async function loadImageModels() {
  imageModelsLoading.value = true
  try {
    const res = await imggenApi.listImageModels()
    imageModels.value = res.data || []
    if (!selectedImageKey.value && imageModels.value.length) {
      const def = imageModels.value.find((m) => m.defaultModel) || imageModels.value[0]
      selectedImageKey.value = imageKey(def)
    } else if (selectedImageKey.value) {
      const still = imageModels.value.some((m) => imageKey(m) === selectedImageKey.value)
      if (!still) {
        const def = imageModels.value[0]
        selectedImageKey.value = def ? imageKey(def) : ''
      }
    }
  } catch {
    imageModels.value = []
  } finally {
    imageModelsLoading.value = false
  }
}

async function onImageModelsChanged() {
  await loadImageModels()
}

async function loadLlmModels() {
  llmModelsLoading.value = true
  try {
    const res = await videoApi.listModels()
    availableProviders.value = res.data || []
    if (!selectedLlmKey.value && availableProviders.value.length) {
      const p = availableProviders.value[0]
      if (p.models?.length) {
        selectedLlmKey.value = `${p.key}::${p.models[0].id}`
      }
    }
  } catch {
    availableProviders.value = []
  } finally {
    llmModelsLoading.value = false
  }
}

async function onLlmModelsChanged() {
  await loadLlmModels()
}

async function handleTestLlm() {
  const parsed = parseLlmKey(selectedLlmKey.value)
  if (!parsed) {
    message.warning('请先选择润色 Chat 模型')
    return
  }
  testingLlm.value = true
  llmTestLatency.value = null
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    const result = res.data
    llmTestLatency.value = result.latencyMs ?? null
    if (result.available) {
      testedOkKeys.value = new Set([...testedOkKeys.value, selectedLlmKey.value])
      const nextFail = new Set(testedFailKeys.value)
      nextFail.delete(selectedLlmKey.value)
      testedFailKeys.value = nextFail
      message.success(`模型可用${result.latencyMs != null ? `（${result.latencyMs}ms）` : ''}`)
    } else {
      testedFailKeys.value = new Set([...testedFailKeys.value, selectedLlmKey.value])
      const nextOk = new Set(testedOkKeys.value)
      nextOk.delete(selectedLlmKey.value)
      testedOkKeys.value = nextOk
      message.error(result.errorMessage || '模型不可用')
    }
  } catch (e: any) {
    testedFailKeys.value = new Set([...testedFailKeys.value, selectedLlmKey.value])
    message.error(e?.message || '测试请求失败')
  } finally {
    testingLlm.value = false
  }
}

async function handleSubmit() {
  if (!canSubmit.value) return
  if (enhancePrompt.value && !selectedLlmKey.value) {
    message.warning('已开启润色，请选择 Chat 模型')
    return
  }
  submitting.value = true
  try {
    const llm = enhancePrompt.value ? parseLlmKey(selectedLlmKey.value) : null
    const img = parseLlmKey(selectedImageKey.value)
    const res = await imggenApi.createTask({
      prompt: prompt.value.trim(),
      negativePrompt: negativePrompt.value.trim() || undefined,
      options: {
        aspectRatio: aspectRatio.value,
        n: imageCount.value,
        seed: seed.value ?? null,
        imageModel: img?.model,
        imageProvider: img?.provider,
        enhancePrompt: enhancePrompt.value,
        llmProvider: llm?.provider,
        llmModel: llm?.model
      }
    })
    const task = res.data
    message.success('任务已提交')
    tasks.value = [task, ...tasks.value.filter((t) => t.id !== task.id)]
    selectedId.value = task.id
  } catch (e: any) {
    message.error(e?.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

async function loadTasks() {
  listLoading.value = true
  try {
    const res = await imggenApi.listTasks(0, 50)
    tasks.value = res.data?.items || []
    if (selectedId.value && !tasks.value.find((t) => t.id === selectedId.value)) {
      selectedId.value = tasks.value[0]?.id ?? null
    } else if (!selectedId.value && tasks.value.length) {
      selectedId.value = tasks.value[0].id
    }
  } catch (e: any) {
    message.error(e?.message || '加载任务失败')
  } finally {
    listLoading.value = false
  }
}

function selectTask(id: string) {
  selectedId.value = id
}

function mergeTask(partial: Partial<ImgGenTaskItem> & { id?: string; taskId?: string }) {
  const id = String(partial.id || partial.taskId || '')
  if (!id) return
  const idx = tasks.value.findIndex((t) => t.id === id)
  if (idx >= 0) {
    tasks.value[idx] = { ...tasks.value[idx], ...partial, id } as ImgGenTaskItem
  } else if (partial.prompt) {
    tasks.value.unshift(partial as ImgGenTaskItem)
  }
}

async function handleCancel() {
  if (!selected.value) return
  try {
    const res = await imggenApi.cancelTask(selected.value.id)
    mergeTask(res.data)
    message.info('已取消')
  } catch (e: any) {
    message.error(e?.message || '取消失败')
  }
}

function openRetryModal(task: ImgGenTaskItem) {
  retryTarget.value = task
  if (task.provider && task.model) {
    retryImageKey.value = `${task.provider}::${task.model}`
  } else {
    retryImageKey.value = selectedImageKey.value
  }
  retryEnhance.value = !!task.enhanceEnabled
  if (task.llmProvider && task.llmModel) {
    retryLlmKey.value = `${task.llmProvider}::${task.llmModel}`
  } else {
    retryLlmKey.value = selectedLlmKey.value
  }
  retryModalOpen.value = true
}

async function submitRetry() {
  const task = retryTarget.value
  if (!task) return
  if (retryEnhance.value && !retryLlmKey.value) {
    message.warning('已开启润色，请选择 Chat 模型')
    return
  }
  retryingId.value = task.id
  try {
    const llm = retryEnhance.value ? parseLlmKey(retryLlmKey.value) : null
    const img = parseLlmKey(retryImageKey.value)
    const res = await imggenApi.retryTask(task.id, {
      imageModel: img?.model,
      imageProvider: img?.provider,
      enhancePrompt: retryEnhance.value,
      llmProvider: llm?.provider,
      llmModel: llm?.model
    })
    mergeTask(res.data)
    selectedId.value = task.id
    retryModalOpen.value = false
    message.success('已重新排队')
  } catch (e: any) {
    message.error(e?.message || '重试失败')
  } finally {
    retryingId.value = null
  }
}

function confirmDelete(task: ImgGenTaskItem) {
  Modal.confirm({
    title: '删除任务？',
    icon: createVNode(ExclamationCircleOutlined),
    content: '将删除任务记录与本地图片，不可恢复。',
    okType: 'danger',
    async onOk() {
      deletingId.value = task.id
      try {
        await imggenApi.deleteTask(task.id)
        tasks.value = tasks.value.filter((t) => t.id !== task.id)
        if (selectedId.value === task.id) {
          selectedId.value = tasks.value[0]?.id ?? null
        }
        message.success('已删除')
      } catch (e: any) {
        message.error(e?.message || '删除失败')
      } finally {
        deletingId.value = null
      }
    }
  })
}

function revokeAllImageUrls() {
  for (const url of Object.values(imageUrls.value)) {
    if (url) URL.revokeObjectURL(url)
  }
  imageUrls.value = {}
  imageLoading.value = {}
}

async function loadImagesForSelected() {
  revokeAllImageUrls()
  const s = selected.value
  if (!s?.outputAvailable || !s.images?.length) return
  for (const img of s.images) {
    const name = fileNameFromImage(img)
    imageLoading.value[img.index] = true
    try {
      const blob = await imggenApi.fetchImageBlob(s.id, name)
      imageUrls.value[img.index] = URL.createObjectURL(blob)
    } catch {
      // leave empty
    } finally {
      imageLoading.value[img.index] = false
    }
  }
}

function previewImage(img: ImgGenImageFile) {
  const url = imageUrls.value[img.index]
  if (!url) return
  previewUrl.value = url
  previewOpen.value = true
}

function downloadImage(img: ImgGenImageFile) {
  const url = imageUrls.value[img.index]
  if (!url) return
  const a = document.createElement('a')
  a.href = url
  a.download = fileNameFromImage(img)
  a.click()
}

function startSse() {
  sseCloser?.close()
  sseCloser = connectImgGenTaskEvents({
    onOpen: () => {
      liveChannel.value = 'live'
      stopPoll()
    },
    onError: () => {
      liveChannel.value = 'poll'
      startPoll()
    },
    onEvent: (ev) => {
      if (ev.type === 'task.deleted') {
        const id = String(ev.taskId || ev.id || ev.data?.id || '')
        if (id) {
          tasks.value = tasks.value.filter((t) => t.id !== id)
          if (selectedId.value === id) selectedId.value = tasks.value[0]?.id ?? null
        }
        return
      }
      if (ev.type === 'task.created' || ev.type === 'task.status') {
        const data = {
          ...(ev.data || {}),
          id: String(ev.data?.id || ev.taskId || ev.id || '')
        } as Partial<ImgGenTaskItem> & { id: string }
        mergeTask(data)
        // 成功时刷新完整详情（含 images）
        if (data.status === 'SUCCESS' && data.id) {
          void imggenApi.getTask(String(data.id)).then((r) => mergeTask(r.data)).catch(() => {})
        }
      }
    }
  })
}

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    void loadTasks()
  }, 5000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(selectedId, () => {
  void loadImagesForSelected()
})

watch(
  () => [selected.value?.status, selected.value?.images?.length, selected.value?.outputAvailable] as const,
  () => {
    if (selected.value?.status === 'SUCCESS' && selected.value?.outputAvailable) {
      void loadImagesForSelected()
    }
  }
)

onMounted(async () => {
  await Promise.all([loadImageModels(), loadLlmModels(), loadTasks()])
  startSse()
})

onUnmounted(() => {
  sseCloser?.close()
  stopPoll()
  revokeAllImageUrls()
})
</script>

<style lang="scss" scoped src="../video-generate/aigen-ui.scss"></style>
<style lang="scss" scoped>
.img-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
  padding: 4px 0 8px;
}

.img-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  background: #f8fafc;
}

.img-frame {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  background: #0f172a0a;
  cursor: zoom-in;
  min-height: 160px;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
}

.img-fallback {
  font-size: 12px;
  color: #94a3b8;
}

.img-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: #fff;
}

.img-meta {
  font-size: 11px;
  color: #64748b;
}
</style>
