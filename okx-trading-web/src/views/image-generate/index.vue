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
          输入提示词，可先润色写回输入框确认后再生成 · 支持多比例与再生成
        </p>
        <div class="pipeline-row">
          <span class="pipe-chip"><i>1</i>编写 / 润色</span>
          <span class="pipe-sep" />
          <span class="pipe-chip"><i>2</i>确认后出图</span>
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
        <div class="prompt-box" :class="{ 'is-enhanced': !!promptBeforeEnhance }">
          <a-textarea
            v-model:value="prompt"
            :rows="5"
            :maxlength="2000"
            :show-count="false"
            class="prompt-textarea prompt-textarea-in-box"
            placeholder="例如：赛博朋克风格的东京夜景，霓虹倒映在雨后街道，电影感光影"
            @pressEnter.ctrl="handleSubmit"
          />
          <div class="prompt-box-actions">
            <button
              v-if="promptBeforeEnhance"
              type="button"
              class="prompt-corner-btn undo"
              :disabled="enhancing"
              @click="undoEnhance"
            >
              撤销
            </button>
            <a-tooltip :title="enhanceBtnTip">
              <button
                type="button"
                class="prompt-corner-btn enhance"
                :class="{ busy: enhancing }"
                :disabled="enhancing || submitting"
                @click="openEnhanceModal"
              >
                <HighlightOutlined />
                <span>{{ enhancing ? '润色中…' : '一键润色' }}</span>
              </button>
            </a-tooltip>
          </div>
        </div>
        <p v-if="promptBeforeEnhance" class="enhance-hint">
          已润色写回输入框{{ lastEnhanceLatency != null ? `（${lastEnhanceLatency}ms）` : '' }}，确认后点击生成
        </p>
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
          <span class="hint" style="font-size: 12px; color: var(--text-muted)">
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

      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="modelManageOpen"
        capability="image"
        @changed="onImageModelsChanged"
      />
      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="chatModelManageOpen"
        capability="chat"
        @changed="onLlmModelsChanged"
      />

      <!-- 一键润色：弹窗选择模型后再执行 -->
      <a-modal
        v-model:open="enhanceModalOpen"
        title="一键润色提示词"
        :ok-text="enhancing ? '润色中…' : '开始润色'"
        cancel-text="取消"
        :confirm-loading="enhancing"
        :ok-button-props="{ disabled: !selectedLlmKey || !prompt.trim() }"
        destroy-on-close
        @ok="confirmEnhanceFromModal"
      >
        <p class="enhance-modal-desc">
          选择 Chat 模型润色当前输入框内容，结果写回输入框，确认后再生成图片。
        </p>
        <div class="enhance-modal-field">
          <div class="enhance-modal-label">润色模型</div>
          <a-select
            v-model:value="selectedLlmKey"
            class="model-select"
            show-search
            :options="llmSelectOptions"
            :filter-option="filterOption"
            :loading="llmModelsLoading"
            placeholder="选择润色用 Chat 模型"
            style="width: 100%"
          />
        </div>
        <div class="enhance-modal-actions">
          <a-button
            size="small"
            :loading="testingLlm"
            :disabled="!selectedLlmKey"
            @click="handleTestLlm"
          >
            <template #icon><ExperimentOutlined /></template>
            测试可用性
          </a-button>
          <a-button
            v-if="auth.isSuperAdmin"
            size="small"
            @click="chatModelManageOpen = true"
          >
            <template #icon><SettingOutlined /></template>
            Chat 模型管理
          </a-button>
          <a-tag v-if="testingLlm" color="processing">测试中…</a-tag>
          <a-tag v-else-if="selectedLlmKey && llmTestStatus === 'ok'" color="success">
            ✓ 可用{{ llmTestLatency != null ? ` · ${llmTestLatency}ms` : '' }}
          </a-tag>
          <a-tag v-else-if="selectedLlmKey && llmTestStatus === 'fail'" color="error">不可用</a-tag>
        </div>
        <div v-if="!llmModelsLoading && !llmSelectOptions.length" style="margin-top: 10px">
          <a-tag color="warning">
            暂无 Chat 模型
            <template v-if="auth.isSuperAdmin"> — 请打开「Chat 模型管理」添加</template>
            <template v-else> — 请联系超级管理员配置</template>
          </a-tag>
        </div>
      </a-modal>

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
          输入框右下角可一键润色 · Ctrl + Enter 提交
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

        <EmptyState
          v-if="!listLoading && !tasks.length"
          scene="tasks"
          compact
          tone="soft"
          title="还没有生成任务"
          description="写好提示词，确认后即可出图"
        />

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

          <!-- 再生成：可换生图模型（提示词已在创建时确认，任务内不再二次润色） -->
          <a-modal
            v-model:open="retryModalOpen"
            :title="retryTarget?.status === 'SUCCESS' ? '重新生成' : '重试任务'"
            :ok-text="retryTarget?.status === 'SUCCESS' ? '开始重新生成' : '开始重试'"
            cancel-text="取消"
            :confirm-loading="retryingId === retryTarget?.id"
            @ok="submitRetry"
          >
            <p class="retry-hint">使用原任务提示词重新出图，可更换生图模型。</p>
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
              <span class="k">任务内润色</span>
              <div class="v prompt-cell">
                <p class="prompt-text">{{ selected.enhancedPrompt }}</p>
              </div>
            </div>
            <div class="detail-row">
              <span class="k">生图模型</span>
              <span class="v">{{ selected.provider || '—' }} / {{ selected.model || '—' }}</span>
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
        <EmptyState
          v-else
          scene="detail"
          title="选择左侧任务查看详情"
          description="生成完成后可在此预览与下载图片"
        />
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
  ExclamationCircleOutlined,
  HighlightOutlined
} from '@ant-design/icons-vue'
import { imggenApi } from '@/api/imggen.api'
import { connectImgGenTaskEvents } from '@/api/imggen.events'
import { videoApi } from '@/api/video.api'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { AiProvider, ImgGenImageFile, ImgGenImageModel, ImgGenTaskItem } from '@/types/api'

const auth = useAuthStore()

const prompt = ref('')
const aspectRatio = ref('1:1')
const imageCount = ref(1)
const seed = ref<number | null>(null)
/** 润色前原文，非空表示当前输入框内容来自润色，可撤销 */
const promptBeforeEnhance = ref('')
const lastEnhanceLatency = ref<number | null>(null)

/** 生图模型（库表 capability=image；key = provider::modelId） */
const imageModels = ref<ImgGenImageModel[]>([])
const imageModelsLoading = ref(false)
const selectedImageKey = ref('')

/** 润色 Chat 模型（复用 video 模型列表） */
const availableProviders = ref<AiProvider[]>([])
const llmModelsLoading = ref(false)
const selectedLlmKey = ref('')
const testingLlm = ref(false)
const enhancing = ref(false)
const enhanceModalOpen = ref(false)
const testedOkKeys = ref<Set<string>>(new Set())
const testedFailKeys = ref<Set<string>>(new Set())
const llmTestLatency = ref<number | null>(null)
const modelManageOpen = ref(false)
const chatModelManageOpen = ref(false)

/** 再生成弹窗 */
const retryModalOpen = ref(false)
const retryTarget = ref<ImgGenTaskItem | null>(null)
const retryImageKey = ref('')

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
  if (!prompt.value.trim() || submitting.value || enhancing.value) return false
  if (!selectedImageKey.value) return false
  return true
})

const enhanceBtnTip = computed(() => {
  if (!prompt.value.trim()) return '请先填写提示词'
  if (enhancing.value) return '正在润色…'
  return '选择模型并润色，结果写回输入框'
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

function openEnhanceModal() {
  if (!prompt.value.trim()) {
    message.warning('请先填写创作提示词')
    return
  }
  if (enhancing.value || submitting.value) return
  enhanceModalOpen.value = true
  if (!availableProviders.value.length) {
    void loadLlmModels()
  }
}

/**
 * 弹窗确认后润色：结果写回输入框，不创建任务；用户确认后再 handleSubmit。
 * ant-design-vue Modal @ok 返回 Promise 可阻止关闭失败时的自动关窗。
 */
async function confirmEnhanceFromModal() {
  if (!prompt.value.trim()) {
    message.warning('请先填写创作提示词')
    return Promise.reject()
  }
  const llm = parseLlmKey(selectedLlmKey.value)
  if (!llm) {
    message.warning('请先选择润色 Chat 模型')
    return Promise.reject()
  }
  const original = prompt.value.trim()
  enhancing.value = true
  lastEnhanceLatency.value = null
  try {
    const res = await imggenApi.enhancePrompt({
      prompt: original,
      llmProvider: llm.provider,
      llmModel: llm.model,
      languageHint: 'auto'
    })
    const enhanced = (res.data?.enhancedPrompt || '').trim()
    if (!enhanced) {
      message.error('润色结果为空')
      return Promise.reject()
    }
    // 仅首次润色保留更早的原文，便于多次润色后仍可一次撤销到最初
    if (!promptBeforeEnhance.value) {
      promptBeforeEnhance.value = original
    }
    prompt.value = enhanced.slice(0, 2000)
    lastEnhanceLatency.value = res.data?.latencyMs ?? null
    enhanceModalOpen.value = false
    message.success(
      `已润色并写入输入框${lastEnhanceLatency.value != null ? `（${lastEnhanceLatency.value}ms）` : ''}，请确认后生成`
    )
  } catch (e: any) {
    message.error(e?.message || '润色失败')
    return Promise.reject(e)
  } finally {
    enhancing.value = false
  }
}

function undoEnhance() {
  if (!promptBeforeEnhance.value) return
  prompt.value = promptBeforeEnhance.value
  promptBeforeEnhance.value = ''
  lastEnhanceLatency.value = null
  message.info('已恢复润色前原文')
}

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const img = parseLlmKey(selectedImageKey.value)
    // 润色已在输入框完成并由用户确认；任务内不再二次润色
    const res = await imggenApi.createTask({
      prompt: prompt.value.trim(),
      options: {
        aspectRatio: aspectRatio.value,
        n: imageCount.value,
        seed: seed.value ?? null,
        imageModel: img?.model,
        imageProvider: img?.provider,
        enhancePrompt: false
      }
    })
    const task = res.data
    message.success('任务已提交')
    promptBeforeEnhance.value = ''
    lastEnhanceLatency.value = null
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
  retryModalOpen.value = true
}

async function submitRetry() {
  const task = retryTarget.value
  if (!task) return
  retryingId.value = task.id
  try {
    const img = parseLlmKey(retryImageKey.value)
    const res = await imggenApi.retryTask(task.id, {
      imageModel: img?.model,
      imageProvider: img?.provider,
      enhancePrompt: false
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
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
  background: var(--surface-hover);
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
  color: var(--text-muted);
}

.img-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--surface-1);
}

.img-meta {
  font-size: 11px;
  color: var(--text-secondary);
}

/* 输入框 + 右下角一键润色（覆盖 aigen-ui 表单默认边框） */
.prompt-box {
  position: relative;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  background: var(--surface-hover);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;

  &:focus-within {
    background: var(--surface-1);
    border-color: var(--text-muted);
    box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.06);
  }

  &.is-enhanced {
    border-color: #a7f3d0;
    box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.1);
  }

  :deep(.prompt-textarea-in-box.ant-input),
  :deep(.prompt-textarea-in-box textarea.ant-input),
  :deep(.prompt-textarea-in-box textarea) {
    border: none !important;
    box-shadow: none !important;
    background: transparent !important;
    padding: 12px 14px 44px !important;
    border-radius: 12px !important;
    resize: vertical;
    min-height: 120px;

    &:hover,
    &:focus {
      border: none !important;
      box-shadow: none !important;
      background: transparent !important;
    }
  }
}

.prompt-box-actions {
  position: absolute;
  right: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  z-index: 2;
  pointer-events: none;

  > * {
    pointer-events: auto;
  }
}

.prompt-corner-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease, opacity 0.15s ease;
  user-select: none;

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }

  &.enhance {
    color: var(--text-primary);
    border-color: var(--border-color);
    background: var(--surface-1);

    &:hover:not(:disabled) {
      background: var(--surface-hover);
      border-color: var(--border-strong);
      color: var(--primary-strong);
    }

    &.busy {
      color: var(--text-secondary);
    }
  }

  &.undo {
    color: var(--text-secondary);

    &:hover:not(:disabled) {
      color: #334155;
      background: var(--surface-hover);
      border-color: #cbd5e1;
    }
  }
}

.enhance-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #16a34a;
  line-height: 1.4;
}

.enhance-modal-desc {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.enhance-modal-field {
  margin-bottom: 12px;
}

.enhance-modal-label {
  font-size: 13px;
  font-weight: 500;
  color: #334155;
  margin-bottom: 6px;
}

.enhance-modal-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
