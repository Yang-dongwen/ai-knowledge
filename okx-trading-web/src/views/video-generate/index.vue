<template>
  <div class="aigen-page">
    <div class="submit-hero page-card">
      <div class="hero-text">
        <div class="hero-title-row">
          <h2 class="page-title">AI 视频生成</h2>
          <a-tooltip :title="liveChannelTip">
            <span class="live-badge" :class="liveChannel">
              <span class="live-dot" />
              <span class="live-label">{{ liveChannelLabel }}</span>
            </span>
          </a-tooltip>
        </div>
        <p class="page-subtitle">
          一句话生成分镜 · 配音 · 成片，适合短视频知识科普与口播脚本
        </p>
        <div class="pipeline-row">
          <span class="pipe-chip"><i>1</i>规划分镜</span>
          <span class="pipe-sep" />
          <span class="pipe-chip"><i>2</i>配音素材</span>
          <span class="pipe-sep" />
          <span class="pipe-chip"><i>3</i>渲染成片</span>
        </div>
      </div>

      <div class="form-block">
        <div class="field-label-row">
          <label class="field-label">创作提示词</label>
          <span class="char-meter" :class="{ warn: prompt.length > 3600 }">
            <i :style="{ width: `${Math.min(100, (prompt.length / 4000) * 100)}%` }" />
            <em>{{ prompt.length }} / 4000</em>
          </span>
        </div>
        <a-textarea
          v-model:value="prompt"
          :rows="4"
          :maxlength="4000"
          :show-count="false"
          class="prompt-textarea"
          placeholder="例如：用通俗语言讲解比特币减半，适合竖屏短视频，语气轻松"
          @pressEnter.ctrl="handleSubmit"
        />
      </div>

      <!-- LLM 模型选择（复用视频提取同一套模型接口） -->
      <div class="model-row">
        <div class="model-pick">
          <span class="opt-label">LLM 模型</span>
          <a-select
            v-model:value="selectedModelKey"
            class="model-select"
            size="large"
            show-search
            :options="modelSelectOptions"
            :filter-option="filterModelOption"
            placeholder="选择用于分镜规划的模型"
            :loading="modelsLoading"
            @change="onModelChange"
          />
          <a-button
            size="large"
            class="test-btn"
            :loading="testingModel"
            :disabled="!selectedModelKey"
            @click="handleTestModel"
          >
            <template #icon><ExperimentOutlined /></template>
            测试可用性
          </a-button>
          <a-button
            v-if="auth.isSuperAdmin"
            size="large"
            class="manage-btn"
            @click="modelManageOpen = true"
          >
            <template #icon><SettingOutlined /></template>
            模型管理
          </a-button>
        </div>
        <div class="model-status" v-if="selectedModelKey">
          <a-tag v-if="testingModel" color="processing">测试中…</a-tag>
          <a-tag v-else-if="currentModelTestStatus === 'ok'" color="success">
            ✓ 可用{{ lastTestLatency != null ? ` · ${lastTestLatency}ms` : '' }}
          </a-tag>
          <a-tag v-else-if="currentModelTestStatus === 'fail'" color="error">不可用</a-tag>
          <a-tag v-else color="default">未测试（可选，可直接生成）</a-tag>
          <span v-if="testErrorMsg && currentModelTestStatus === 'fail'" class="test-err" :title="testErrorMsg">
            {{ testErrorMsg }}
          </span>
        </div>
        <div class="model-status muted" v-else-if="!modelsLoading && availableProviders.length === 0">
          <a-tag color="warning">
            暂无可用模型
            <template v-if="auth.isSuperAdmin"> — 请打开「模型管理」添加，并确认 yml 中配置了 api-key</template>
            <template v-else> — 请联系超级管理员配置模型</template>
          </a-tag>
        </div>
      </div>

      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="modelManageOpen"
        @changed="onModelsChanged"
      />

      <div class="options-grid">
        <div class="opt">
          <span class="field-label">模板</span>
          <a-select
            v-model:value="templateId"
            class="full"
            :options="templateOptions"
            :loading="templatesLoading"
            placeholder="选择模板"
          />
        </div>
        <div class="opt">
          <span class="field-label">画幅</span>
          <a-radio-group v-model:value="aspectRatio" button-style="solid" size="middle">
            <a-radio-button value="9:16">竖屏 9:16</a-radio-button>
            <a-radio-button value="16:9">横屏 16:9</a-radio-button>
            <a-radio-button value="1:1">方形 1:1</a-radio-button>
          </a-radio-group>
        </div>
        <div class="opt duration-opt" @wheel.prevent="onDurationWheel">
          <div class="duration-head">
            <span class="field-label">目标时长</span>
            <span class="duration-badge">
              <strong>{{ targetDurationSec }}</strong>
              <em>秒</em>
            </span>
          </div>
          <a-slider
            v-model:value="targetDurationSec"
            :min="5"
            :max="90"
            :step="1"
            :tooltip-open="false"
            class="duration-slider"
          />
          <div class="duration-marks">
            <span>5s</span>
            <span class="hint-wheel">滚轮调节</span>
            <span>90s</span>
          </div>
        </div>
        <div class="opt">
          <span class="field-label">语言</span>
          <a-radio-group v-model:value="language" button-style="solid" size="middle">
            <a-radio-button value="zh">中文</a-radio-button>
            <a-radio-button value="en">English</a-radio-button>
          </a-radio-group>
        </div>
        <div class="opt">
          <span class="field-label">配音音色</span>
          <a-select
            v-model:value="voiceId"
            class="full"
            :options="voiceOptions"
            :loading="voicesLoading"
            placeholder="选择音色（Edge 推荐）"
            show-search
            :filter-option="filterModelOption"
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
          生成视频
        </a-button>
        <span class="hint">Ctrl + Enter 提交 · 5–90 秒 · 成片可在线预览下载</span>
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
              <a-tag :color="statusMeta(task.status, task).color" class="status-tag">
                {{ statusMeta(task.status, task).label }}
              </a-tag>
              <div class="task-item-actions">
                <span class="task-time">{{ shortTime(task.createdAt) }}</span>
                <a-tooltip :title="pauseButtonTip(task)">
                  <a-button
                    v-if="canPause(task)"
                    type="text"
                    size="small"
                    class="task-pause-btn"
                    :loading="pausingId === task.id"
                    @click.stop="handlePause(task)"
                  >
                    <template #icon><PauseCircleOutlined /></template>
                  </a-button>
                </a-tooltip>
                <a-button
                  v-if="canRetry(task.status)"
                  type="text"
                  size="small"
                  class="task-retry-btn"
                  :loading="retryingId === task.id"
                  title="重试（可改模型）"
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
              <span>{{ task.templateId }}</span>
              <span>{{ task.aspectRatio || '9:16' }}</span>
              <span>{{ task.targetDurationSec || 30 }}s</span>
              <span v-if="task.llmModel" class="model-chip" :title="task.llmModel">
                {{ shortModelName(task.llmModel) }}
              </span>
              <span v-if="task.totalDurationMs" class="dur-chip">
                {{ formatMs(task.totalDurationMs) }}
              </span>
            </div>
            <a-progress
              v-if="isRunning(task.status) || isPauseDraining(task)"
              :percent="task.progress || 0"
              size="small"
              :show-info="true"
              status="active"
            />
            <div v-if="task.currentStep" class="task-step">{{ task.currentStep }}</div>
            <div v-if="listTimingLine(task)" class="task-timing-line">{{ listTimingLine(task) }}</div>
          </div>
        </div>
      </aside>

      <section class="detail-panel page-card">
        <template v-if="selected">
          <div class="detail-header">
            <div class="detail-header-main">
              <a-tag :color="statusMeta(selected.status, selected).color">
                {{ statusMeta(selected.status, selected).label }}
              </a-tag>
              <h3 class="detail-title">{{ selected.title || '未命名任务' }}</h3>
              <div class="detail-sub">
                <span v-if="selected.templateId">{{ selected.templateId }}</span>
                <span v-if="selected.aspectRatio">{{ selected.aspectRatio }}</span>
                <span v-if="selected.targetDurationSec">{{ selected.targetDurationSec }}s</span>
                <span v-if="selected.llmModel" class="model-chip" :title="selected.llmProvider || ''">
                  {{ shortModelName(selected.llmModel) }}
                </span>
                <span v-if="selected.createdAt">创建于 {{ selected.createdAt }}</span>
              </div>
            </div>
            <a-space>
              <a-button type="text" :loading="listLoading" @click="loadTasks">
                <template #icon><ReloadOutlined /></template>
              </a-button>
              <a-tooltip v-if="canPause(selected)" :title="pauseButtonTip(selected)">
                <a-button :loading="pausingId === selected.id" @click="handlePause(selected)">
                  <template #icon><PauseCircleOutlined /></template>
                  暂停
                </a-button>
              </a-tooltip>
              <a-button
                v-if="canCancel(selected.status)"
                @click="handleCancel"
              >
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
                重试
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

          <!-- 重试弹窗：可重新配置 LLM（含已成功任务） -->
          <a-modal
            v-model:open="retryModalOpen"
            :title="retryTarget?.status === 'SUCCESS' ? '重新生成' : '重试任务'"
            :ok-text="retryTarget?.status === 'SUCCESS' ? '开始重新生成' : '开始重试'"
            cancel-text="取消"
            :confirm-loading="retryingId === retryTarget?.id"
            @ok="submitRetry"
          >
            <p class="retry-hint">
              <template v-if="retryTarget?.status === 'SUCCESS'">
                将清空当前分镜与成片，重新执行：规划 → 素材 → 渲染。可更换 LLM 模型（测试可选）。
              </template>
              <template v-else>
                将重新执行：规划 → 素材 → 渲染。可更换 LLM 模型（测试可选）。
              </template>
            </p>
            <div class="retry-prompt" v-if="retryTarget">
              <span class="muted">提示词</span>
              <div class="prompt-text">{{ retryTarget.prompt }}</div>
            </div>
            <div class="retry-model-row">
              <span class="opt-label">LLM 模型</span>
              <a-select
                v-model:value="retryModelKey"
                class="model-select"
                show-search
                :options="modelSelectOptions"
                :filter-option="filterModelOption"
                placeholder="选择模型"
                style="width: 100%; margin-top: 6px"
              />
            </div>
            <div class="retry-actions">
              <a-button
                size="small"
                :loading="retryTesting"
                :disabled="!retryModelKey"
                @click="testRetryModel"
              >
                测试可用性
              </a-button>
              <a-tag v-if="retryTestOk" color="success">✓ 可用</a-tag>
              <a-tag v-else-if="retryTestFail" color="error">不可用</a-tag>
            </div>
          </a-modal>

          <!-- 进行中：步骤条 + 已完成步骤耗时 -->
          <div class="progress-block" v-if="isRunning(selected.status)">
            <a-steps
              size="small"
              :current="stepIndex(selected.status)"
              :status="stepsStatus(selected.status)"
              :items="stepItemsWithTiming(selected)"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ selected.currentStep || '处理中…' }}</span>
            </div>
            <a-alert
              type="info"
              show-icon
              class="pause-policy-hint"
              message="暂停说明"
              description="暂停仅在步骤边界生效（规划 / 素材 / 渲染之间）。当前步骤会跑完后再中断，不会立即强杀进行中的 LLM 或渲染。"
            />
            <div class="timing-strip" v-if="hasAnyStepTiming(selected)">
              <span class="timing-chip" v-if="selected.planDurationMs">
                规划 <b>{{ formatMs(selected.planDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="selected.assetDurationMs">
                素材 <b>{{ formatMs(selected.assetDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="selected.renderDurationMs">
                渲染 <b>{{ formatMs(selected.renderDurationMs) }}</b>
              </span>
            </div>
          </div>

          <div class="progress-block pause-draining" v-else-if="isPauseDraining(selected)">
            <a-alert
              type="warning"
              show-icon
              message="已请求暂停 · 等待当前步骤结束"
              description="暂停只在步骤边界生效，请稍候；完成后可「重试」继续。其它排队任务可先被调度。"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ selected.currentStep || '暂停中…' }}</span>
            </div>
          </div>

          <!-- 终态步骤耗时面板 -->
          <div
            class="timing-panel"
            v-if="!isRunning(selected.status) && !isPauseDraining(selected) && hasAnyStepTiming(selected)"
          >
            <div class="timing-panel-title">
              <span>执行耗时</span>
              <span class="timing-total" v-if="selected.totalDurationMs">
                总计 {{ formatMs(selected.totalDurationMs) }}
              </span>
            </div>
            <div class="timing-bars">
              <div class="timing-bar-row" v-for="row in timingRows(selected)" :key="row.key">
                <div class="timing-bar-label">
                  <span>{{ row.label }}</span>
                  <span class="timing-bar-ms">{{ formatMs(row.ms) }}</span>
                </div>
                <div class="timing-bar-track">
                  <div class="timing-bar-fill" :class="row.key" :style="{ width: timingBarWidth(row.ms, selected) }" />
                </div>
              </div>
            </div>
            <div class="timing-meta" v-if="selected.startedAt || selected.finishedAt">
              <span v-if="selected.startedAt">开始 {{ selected.startedAt }}</span>
              <span v-if="selected.finishedAt">结束 {{ selected.finishedAt }}</span>
            </div>
          </div>

          <a-alert
            v-if="selected.status === 'PAUSED' && !isPauseDraining(selected)"
            type="warning"
            show-icon
            class="fail-alert"
            message="任务已暂停"
            description="已在步骤边界中断。可点「重试」并可选换模型后重新排队。"
          />

          <div class="detail-grid">
            <div class="detail-row">
              <span class="k">状态</span>
              <span class="v">
                <a-tag :color="statusMeta(selected.status, selected).color">
                  {{ statusMeta(selected.status, selected).label }}
                </a-tag>
                {{ selected.progress ?? 0 }}%
              </span>
            </div>
            <div class="detail-row">
              <span class="k">当前步骤</span>
              <span class="v">{{ selected.currentStep || '—' }}</span>
            </div>
            <div class="detail-row prompt-row">
              <span class="k">提示词</span>
              <div class="v prompt-cell">
                <p
                  class="prompt-text"
                  :class="{ collapsed: promptCollapsed && promptNeedsCollapse }"
                >
                  {{ selected.prompt }}
                </p>
                <button
                  v-if="promptNeedsCollapse"
                  type="button"
                  class="prompt-toggle"
                  @click="promptCollapsed = !promptCollapsed"
                >
                  {{ promptCollapsed ? '展开全部' : '收起' }}
                </button>
              </div>
            </div>
            <div class="detail-row">
              <span class="k">模板 / 画幅</span>
              <span class="v">{{ selected.templateId }} · {{ selected.aspectRatio }} · {{ selected.targetDurationSec }}s</span>
            </div>
            <div class="detail-row" v-if="selected.llmProvider || selected.llmModel">
              <span class="k">LLM 模型</span>
              <span class="v">{{ selected.llmProvider || '—' }} / {{ selected.llmModel || '—' }}</span>
            </div>
            <div class="detail-row" v-if="selected.errorMessage">
              <span class="k">错误</span>
              <span class="v error">{{ selected.errorMessage }}</span>
            </div>
          </div>

          <div v-if="selected.outputAvailable" class="player-box">
            <div class="player-head">
              <div class="player-head-left">
                <span class="player-label">成片预览</span>
                <span class="player-state" v-if="videoLoading">加载中…</span>
                <span class="player-state ok" v-else-if="videoObjectUrl">已就绪</span>
                <span class="player-state warn" v-else>待加载</span>
              </div>
              <div class="player-tools">
                <button
                  type="button"
                  class="tool-btn"
                  :disabled="videoLoading"
                  :title="'重新加载'"
                  @click="() => loadVideo(true)"
                >
                  <ReloadOutlined />
                  <span>重新加载</span>
                </button>
                <button
                  type="button"
                  class="tool-btn primary"
                  :disabled="!videoObjectUrl"
                  title="下载 MP4"
                  @click="downloadVideo"
                >
                  <DownloadOutlined />
                  <span>下载</span>
                </button>
              </div>
            </div>
            <div class="player-frame">
              <div v-if="videoLoading" class="player-loading">
                <a-spin tip="正在加载成片…" />
              </div>
              <video
                v-else-if="videoObjectUrl"
                ref="videoRef"
                class="player"
                :src="videoObjectUrl"
                controls
                playsinline
                preload="auto"
                @loadedmetadata="onVideoMeta"
              />
              <div v-else class="player-loading muted">成片加载失败，请点击上方「重新加载」</div>
            </div>
          </div>
          <div v-else class="player-placeholder">
            <span v-if="selected.status === 'SUCCESS'">流程已完成但暂无成片文件</span>
            <span v-else>生成成功后将在此预览成片</span>
          </div>

          <div
            v-if="canShowStoryboard"
            class="storyboard-panel"
            :class="{ open: storyboardOpen }"
          >
            <button type="button" class="storyboard-toggle" @click="toggleStoryboard">
              <span class="sb-left">
                <CodeOutlined />
                <span>分镜数据</span>
                <em v-if="storyboardPreview">JSON</em>
              </span>
              <span class="sb-right">
                <span class="sb-action">{{ storyboardOpen ? '收起' : '展开' }}</span>
                <DownOutlined class="sb-chevron" :class="{ rotated: storyboardOpen }" />
              </span>
            </button>
            <div v-show="storyboardOpen" class="storyboard-body">
              <div class="storyboard-toolbar">
                <span class="sb-hint">任务规划与素材时间轴（只读）</span>
                <button type="button" class="tool-btn ghost" @click="loadStoryboard">
                  <ReloadOutlined />
                  <span>刷新</span>
                </button>
              </div>
              <pre v-if="storyboardPreview" class="json-pre">{{ storyboardPreview }}</pre>
              <div v-else class="storyboard-empty">
                <a-spin v-if="storyboardLoading" size="small" />
                <span v-else>暂无数据，点击刷新加载</span>
              </div>
            </div>
          </div>
        </template>
        <a-empty v-else description="选择左侧任务查看详情" />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick, createVNode } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ThunderboltOutlined,
  ReloadOutlined,
  ExperimentOutlined,
  SettingOutlined,
  PauseCircleOutlined,
  RedoOutlined,
  DeleteOutlined,
  StopOutlined,
  ExclamationCircleOutlined,
  DownloadOutlined,
  CodeOutlined,
  DownOutlined
} from '@ant-design/icons-vue'
import { aigenApi } from '@/api/aigen.api'
import { videoApi } from '@/api/video.api'
import { connectAigenTaskEvents } from '@/api/aigen.events'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
import type { AigenTaskItem, AigenTemplate, AiProvider } from '@/types/api'

const auth = useAuthStore()

const prompt = ref('')
const templateId = ref('knowledge-cards')
const aspectRatio = ref('9:16')
const targetDurationSec = ref(30)
const language = ref('zh')

/** 在时长控件上滚轮调节秒数 */
function onDurationWheel(e: WheelEvent) {
  const delta = e.deltaY > 0 ? -1 : 1
  const next = Math.min(90, Math.max(5, (targetDurationSec.value || 30) + delta))
  targetDurationSec.value = next
}
const voiceId = ref('zh-CN-XiaoxiaoNeural')
const voicesLoading = ref(false)
const voiceOptions = ref<Array<{ value: string; label: string }>>([])
const submitting = ref(false)
const listLoading = ref(false)
const templatesLoading = ref(false)
const templates = ref<AigenTemplate[]>([])
const tasks = ref<AigenTaskItem[]>([])
const selectedId = ref<string | null>(null)
const storyboardPreview = ref('')
const storyboardOpen = ref(false)
const storyboardLoading = ref(false)
/** 详情提示词默认折叠（超过阈值时） */
const promptCollapsed = ref(true)
const PROMPT_COLLAPSE_LEN = 96
const videoObjectUrl = ref('')
const videoLoading = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
/** 已自动加载过的 taskId，避免 SSE 重复拉流 */
const autoLoadedTaskId = ref<string | null>(null)
const pausingId = ref('')
const retryingId = ref('')
const deletingId = ref('')
const retryModalOpen = ref(false)
const retryTarget = ref<AigenTaskItem | null>(null)
const retryModelKey = ref('')
const retryTesting = ref(false)
const retryTestOk = ref(false)
const retryTestFail = ref(false)

// ----- 模型选择（与视频提取共用 videoApi） -----
const selectedModelKey = ref('')
const availableProviders = ref<AiProvider[]>([])
const modelsLoading = ref(false)
const modelManageOpen = ref(false)
const testingModel = ref(false)
const testedOkKeys = ref<Set<string>>(new Set())
const testedFailKeys = ref<Set<string>>(new Set())
const testErrorMsg = ref('')
const lastTestLatency = ref<number | null>(null)

const sseConnected = ref(false)
const sseConnecting = ref(true)
const pollingActive = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const liveChannel = computed(() => {
  if (sseConnected.value) return 'live' as const
  if (pollingActive.value) return 'poll' as const
  if (sseConnecting.value) return 'connecting' as const
  return 'offline' as const
})
const liveChannelLabel = computed(() => {
  if (liveChannel.value === 'live') return '实时 · SSE'
  if (liveChannel.value === 'poll') return '实时 · 轮询'
  if (liveChannel.value === 'connecting') return '连接中'
  return '离线'
})
const liveChannelTip = computed(() => {
  if (liveChannel.value === 'live') return 'SSE 已连接，任务状态实时更新'
  if (liveChannel.value === 'poll') return 'SSE 未连通，正在轮询任务列表兜底'
  if (liveChannel.value === 'connecting') return '正在连接任务推送…'
  return '推送断开，将自动重连；有进行中任务时用轮询兜底'
})

const selected = computed(() => tasks.value.find((t) => t.id === selectedId.value) || null)

const promptNeedsCollapse = computed(() => {
  const p = selected.value?.prompt || ''
  return p.length > PROMPT_COLLAPSE_LEN
})

const canShowStoryboard = computed(() => {
  const s = selected.value?.status
  return s === 'SUCCESS' || s === 'RENDERING' || s === 'ASSET_GENERATING' || s === 'PLANNING'
})

const templateOptions = computed(() =>
  templates.value.map((t) => ({
    value: t.id,
    label: `${t.name}（${t.id}）`
  }))
)

const modelSelectOptions = computed(() =>
  availableProviders.value.map((p) => ({
    label: p.name,
    options: (p.models || []).map((m) => ({
      label: m.name || m.id,
      value: `${p.key}::${m.id}`,
      title: m.id
    }))
  }))
)

const currentModelTestStatus = computed<'ok' | 'fail' | 'none'>(() => {
  if (!selectedModelKey.value) return 'none'
  if (testedOkKeys.value.has(selectedModelKey.value)) return 'ok'
  if (testedFailKeys.value.has(selectedModelKey.value)) return 'fail'
  return 'none'
})

const canSubmit = computed(
  () =>
    prompt.value.trim().length > 0 &&
    !!selectedModelKey.value &&
    !submitting.value &&
    !modelsLoading.value
)

const PAUSE_BOUNDARY_TIP =
  '暂停仅在步骤边界生效：当前「规划 / 素材 / 渲染」会先跑完，再中断并释放并发槽。'

function statusMeta(status: string, task?: AigenTaskItem | null) {
  if (task && isPauseDraining(task)) {
    return { label: '暂停中', color: 'warning' }
  }
  const map: Record<string, { label: string; color: string }> = {
    PENDING: { label: '排队中', color: 'default' },
    PLANNING: { label: '规划中', color: 'processing' },
    ASSET_GENERATING: { label: '素材中', color: 'processing' },
    RENDERING: { label: '渲染中', color: 'processing' },
    SUCCESS: { label: '成功', color: 'success' },
    FAILED: { label: '失败', color: 'error' },
    CANCELLED: { label: '已取消', color: 'default' },
    PAUSED: { label: '已暂停', color: 'warning' }
  }
  return map[status] || { label: status, color: 'default' }
}

function isRunning(status: string) {
  return status === 'PLANNING' || status === 'ASSET_GENERATING' || status === 'RENDERING'
}

function isPauseDraining(task?: AigenTaskItem | null) {
  if (!task || task.status !== 'PAUSED') return false
  const step = task.currentStep || ''
  return step.includes('暂停中') || step.includes('等待当前步骤')
}

function canPause(task?: AigenTaskItem | null) {
  if (!task) return false
  if (isPauseDraining(task)) return false
  return (
    task.status === 'PENDING' ||
    task.status === 'PLANNING' ||
    task.status === 'ASSET_GENERATING' ||
    task.status === 'RENDERING'
  )
}

function pauseButtonTip(task?: AigenTaskItem | null) {
  if (task?.status === 'PENDING') return '排队中暂停：立即取消排队，不会开始执行'
  return PAUSE_BOUNDARY_TIP
}

function canCancel(status: string) {
  return (
    status === 'PENDING' ||
    status === 'PLANNING' ||
    status === 'ASSET_GENERATING' ||
    status === 'RENDERING'
  )
}

function canRetry(status: string) {
  const s = (status || '').toUpperCase()
  return s === 'FAILED' || s === 'CANCELLED' || s === 'PAUSED' || s === 'SUCCESS'
}

function positiveMs(v?: number | null) {
  return v != null && v > 0 ? v : null
}

function hasAnyStepTiming(task?: AigenTaskItem | null) {
  if (!task) return false
  return !!(
    positiveMs(task.planDurationMs) ||
    positiveMs(task.assetDurationMs) ||
    positiveMs(task.renderDurationMs) ||
    positiveMs(task.totalDurationMs)
  )
}

function timingRows(task: AigenTaskItem) {
  const rows: { key: string; label: string; ms: number }[] = []
  if (positiveMs(task.planDurationMs)) rows.push({ key: 'plan', label: '规划', ms: task.planDurationMs! })
  if (positiveMs(task.assetDurationMs)) rows.push({ key: 'asset', label: '素材', ms: task.assetDurationMs! })
  if (positiveMs(task.renderDurationMs)) rows.push({ key: 'render', label: '渲染', ms: task.renderDurationMs! })
  return rows
}

function timingBarWidth(ms: number, task: AigenTaskItem) {
  const total =
    positiveMs(task.totalDurationMs) ||
    [task.planDurationMs, task.assetDurationMs, task.renderDurationMs]
      .filter((x): x is number => x != null && x > 0)
      .reduce((a, b) => a + b, 0) ||
    1
  const pct = Math.min(100, Math.max(4, Math.round((ms / total) * 100)))
  return `${pct}%`
}

function listTimingLine(task: AigenTaskItem) {
  const parts: string[] = []
  if (positiveMs(task.planDurationMs)) parts.push(`规划 ${formatMs(task.planDurationMs!)}`)
  if (positiveMs(task.assetDurationMs)) parts.push(`素材 ${formatMs(task.assetDurationMs!)}`)
  if (positiveMs(task.renderDurationMs)) parts.push(`渲染 ${formatMs(task.renderDurationMs!)}`)
  return parts.join(' · ')
}

function stepItemsWithTiming(task: AigenTaskItem) {
  const dur = (ms?: number | null) => (positiveMs(ms) ? ` ${formatMs(ms!)}` : '')
  return [
    { title: '排队' },
    { title: `规划${dur(task.planDurationMs)}` },
    { title: `素材${dur(task.assetDurationMs)}` },
    { title: `渲染${dur(task.renderDurationMs)}` },
    { title: '完成' }
  ]
}

function stepIndex(status: string) {
  switch (status) {
    case 'PENDING':
      return 0
    case 'PLANNING':
      return 1
    case 'ASSET_GENERATING':
      return 2
    case 'RENDERING':
      return 3
    case 'SUCCESS':
    case 'FAILED':
    case 'CANCELLED':
      return 4
    default:
      return 0
  }
}

function stepsStatus(status: string): 'wait' | 'process' | 'finish' | 'error' {
  if (status === 'FAILED' || status === 'CANCELLED') return 'error'
  if (status === 'SUCCESS') return 'finish'
  return 'process'
}

function formatMs(ms: number) {
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

function shortModelName(id?: string | null) {
  if (!id) return ''
  const parts = id.split('/')
  return parts[parts.length - 1] || id
}

function parseModelKey(key: string): { provider: string; model: string } | null {
  const i = key.indexOf('::')
  if (i <= 0) return null
  return { provider: key.slice(0, i), model: key.slice(i + 2) }
}

function filterModelOption(input: string, option: any) {
  const q = (input || '').toLowerCase()
  const label = String(option?.label || '').toLowerCase()
  const value = String(option?.value || '').toLowerCase()
  return label.includes(q) || value.includes(q)
}

function onModelChange() {
  testErrorMsg.value = ''
  lastTestLatency.value = null
}

async function loadModels() {
  modelsLoading.value = true
  try {
    const res = await videoApi.listModels()
    availableProviders.value = res.data || []
    if (selectedModelKey.value) {
      const still = availableProviders.value.some((p) =>
        (p.models || []).some((m) => `${p.key}::${m.id}` === selectedModelKey.value)
      )
      if (!still) {
        selectedModelKey.value = ''
        testedOkKeys.value = new Set()
        testedFailKeys.value = new Set()
      }
    }
    if (!selectedModelKey.value && availableProviders.value.length) {
      const p = availableProviders.value[0]
      if (p.models?.length) {
        selectedModelKey.value = `${p.key}::${p.models[0].id}`
      }
    }
  } catch {
    availableProviders.value = []
  } finally {
    modelsLoading.value = false
  }
}

async function onModelsChanged() {
  await loadModels()
}

async function handleTestModel() {
  const parsed = parseModelKey(selectedModelKey.value)
  if (!parsed) {
    message.warning('请先选择模型')
    return
  }
  testingModel.value = true
  testErrorMsg.value = ''
  lastTestLatency.value = null
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    const result = res.data
    lastTestLatency.value = result.latencyMs ?? null
    if (result.available) {
      testedOkKeys.value = new Set([...testedOkKeys.value, selectedModelKey.value])
      const nextFail = new Set(testedFailKeys.value)
      nextFail.delete(selectedModelKey.value)
      testedFailKeys.value = nextFail
      message.success(`模型可用${result.latencyMs != null ? `（${result.latencyMs}ms）` : ''}`)
    } else {
      testedFailKeys.value = new Set([...testedFailKeys.value, selectedModelKey.value])
      const nextOk = new Set(testedOkKeys.value)
      nextOk.delete(selectedModelKey.value)
      testedOkKeys.value = nextOk
      testErrorMsg.value = result.errorMessage || '模型不可用'
      message.error(testErrorMsg.value)
    }
  } catch (e: any) {
    testedFailKeys.value = new Set([...testedFailKeys.value, selectedModelKey.value])
    const nextOk = new Set(testedOkKeys.value)
    nextOk.delete(selectedModelKey.value)
    testedOkKeys.value = nextOk
    const isTimeout =
      e?.code === 'ECONNABORTED' || String(e?.message || '').toLowerCase().includes('timeout')
    testErrorMsg.value = isTimeout ? '超过 10 秒无响应，判定不可用' : e?.message || '测试请求失败'
    message.error(testErrorMsg.value)
  } finally {
    testingModel.value = false
  }
}

function upsertTask(item: AigenTaskItem) {
  const idx = tasks.value.findIndex((t) => t.id === item.id)
  if (idx >= 0) {
    const prev = tasks.value[idx]
    const merged: AigenTaskItem = { ...prev, ...item }
    // 非失败态：清空历史错误（修复重试成功后仍显示旧 errorMessage）
    if (merged.status && merged.status !== 'FAILED') {
      merged.errorMessage =
        typeof item.errorMessage === 'string' ? item.errorMessage : ''
    }
    // 0 表示尚未产生或已重置，勿用 0 盖掉 undefined 时的展示逻辑在 hasAnyStepTiming
    tasks.value[idx] = merged
  } else {
    if (item.status && item.status !== 'FAILED') {
      item.errorMessage = item.errorMessage ?? ''
    }
    tasks.value.unshift(item)
  }
}

function mergeFromEvent(data: Record<string, unknown>) {
  const id = String(data.id || data.taskId || '')
  if (!id) return
  const status = data.status as string | undefined

  const num = (k: string) => {
    const v = data[k]
    if (v == null || v === '') return undefined
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }

  // errorMessage：字段存在则用（含空串）；非 FAILED 且无字段则强制清空
  let errorMessage: string | undefined
  if (Object.prototype.hasOwnProperty.call(data, 'errorMessage')) {
    errorMessage = data.errorMessage == null ? '' : String(data.errorMessage)
  } else if (status && status !== 'FAILED') {
    errorMessage = ''
  }

  const patch: AigenTaskItem = { id } as AigenTaskItem
  if (data.title != null) patch.title = String(data.title)
  if (data.prompt != null && String(data.prompt)) patch.prompt = String(data.prompt)
  if (data.templateId != null && String(data.templateId)) patch.templateId = String(data.templateId)
  if (status) patch.status = status
  if (data.currentStep != null) patch.currentStep = String(data.currentStep)
  if (num('progress') != null) patch.progress = num('progress')
  if (data.language != null) patch.language = String(data.language)
  if (data.aspectRatio != null) patch.aspectRatio = String(data.aspectRatio)
  if (num('targetDurationSec') != null) patch.targetDurationSec = num('targetDurationSec')
  if (data.llmProvider != null) patch.llmProvider = String(data.llmProvider)
  if (data.llmModel != null) patch.llmModel = String(data.llmModel)
  if (errorMessage !== undefined) patch.errorMessage = errorMessage
  if (num('durationSeconds') != null) patch.durationSeconds = num('durationSeconds')
  if (data.outputAvailable != null) patch.outputAvailable = Boolean(data.outputAvailable)
  // 耗时：含 0 也更新（重试清零）
  if (Object.prototype.hasOwnProperty.call(data, 'planDurationMs')) {
    patch.planDurationMs = num('planDurationMs') ?? 0
  }
  if (Object.prototype.hasOwnProperty.call(data, 'assetDurationMs')) {
    patch.assetDurationMs = num('assetDurationMs') ?? 0
  }
  if (Object.prototype.hasOwnProperty.call(data, 'renderDurationMs')) {
    patch.renderDurationMs = num('renderDurationMs') ?? 0
  }
  if (Object.prototype.hasOwnProperty.call(data, 'totalDurationMs')) {
    patch.totalDurationMs = num('totalDurationMs') ?? 0
  }
  if (data.startedAt != null) patch.startedAt = String(data.startedAt)
  if (data.finishedAt != null) patch.finishedAt = String(data.finishedAt)
  if (data.createdAt != null) patch.createdAt = String(data.createdAt)
  if (data.updatedAt != null) patch.updatedAt = String(data.updatedAt)

  upsertTask(patch)
}

async function loadTemplates() {
  templatesLoading.value = true
  try {
    const res = await aigenApi.listTemplates()
    templates.value = res.data || []
    if (!templates.value.find((t) => t.id === templateId.value) && templates.value[0]) {
      templateId.value = templates.value[0].id
    }
  } catch {
    // interceptor
  } finally {
    templatesLoading.value = false
  }
}

async function loadVoices() {
  voicesLoading.value = true
  try {
    const res = await aigenApi.listVoices()
    const list = res.data || []
    voiceOptions.value = list.map((v) => ({
      value: v.id,
      label: v.name || v.id
    }))
    if (!voiceOptions.value.find((o) => o.value === voiceId.value) && voiceOptions.value[0]) {
      voiceId.value = voiceOptions.value[0].value
    }
  } catch {
    voiceOptions.value = [
      { value: 'zh-CN-XiaoxiaoNeural', label: '晓晓（女·推荐）' }
    ]
  } finally {
    voicesLoading.value = false
  }
}

async function loadTasks() {
  listLoading.value = true
  try {
    const res = await aigenApi.listTasks(0, 50)
    tasks.value = res.data?.items || []
    if (selectedId.value && !tasks.value.find((t) => t.id === selectedId.value)) {
      selectedId.value = tasks.value[0]?.id ?? null
    }
    if (!selectedId.value && tasks.value[0]) {
      selectedId.value = tasks.value[0].id
    }
  } catch {
    // ignore
  } finally {
    listLoading.value = false
  }
}

function revokeVideoUrl() {
  if (videoObjectUrl.value) {
    URL.revokeObjectURL(videoObjectUrl.value)
    videoObjectUrl.value = ''
  }
}

function selectTask(id: string) {
  if (selectedId.value === id) return
  selectedId.value = id
  storyboardPreview.value = ''
  storyboardOpen.value = false
  promptCollapsed.value = true
  revokeVideoUrl()
  autoLoadedTaskId.value = null
}

function onVideoMeta() {
  const el = videoRef.value
  if (!el) return
  el.muted = false
  el.volume = 1
}

async function toggleStoryboard() {
  storyboardOpen.value = !storyboardOpen.value
  if (storyboardOpen.value && !storyboardPreview.value) {
    await loadStoryboard()
  }
}

/**
 * 加载成片。force=true 时强制重新拉取。
 */
async function loadVideo(force = false) {
  if (!selectedId.value) return
  if (!force && autoLoadedTaskId.value === selectedId.value && videoObjectUrl.value) {
    return
  }
  videoLoading.value = true
  try {
    revokeVideoUrl()
    const blob = await aigenApi.fetchOutputBlob(selectedId.value)
    videoObjectUrl.value = URL.createObjectURL(blob)
    autoLoadedTaskId.value = selectedId.value
    await nextTick()
    onVideoMeta()
    try {
      await videoRef.value?.play()
    } catch {
      // 有声自动播放可能被拦截
    }
  } catch (e: any) {
    autoLoadedTaskId.value = null
    message.error(e?.message || '加载成片失败')
  } finally {
    videoLoading.value = false
  }
}

/** 当前选中任务成片可用时自动加载 */
async function maybeAutoLoadVideo() {
  const t = selected.value
  if (!t?.outputAvailable || t.id !== selectedId.value) return
  if (autoLoadedTaskId.value === t.id && videoObjectUrl.value) return
  if (videoLoading.value) return
  await loadVideo(false)
}

function downloadVideo() {
  if (!videoObjectUrl.value || !selected.value) return
  const a = document.createElement('a')
  a.href = videoObjectUrl.value
  a.download = `${selected.value.title || 'aigen'}.mp4`
  a.click()
}

async function handleSubmit() {
  if (!canSubmit.value) return
  const parsed = parseModelKey(selectedModelKey.value)
  if (!parsed) {
    message.warning('请先选择 LLM 模型')
    return
  }
  submitting.value = true
  try {
    const res = await aigenApi.createTask({
      prompt: prompt.value.trim(),
      templateId: templateId.value,
      options: {
        language: language.value,
        aspectRatio: aspectRatio.value,
        targetDurationSec: targetDurationSec.value,
        voiceId: voiceId.value,
        llmProvider: parsed.provider,
        llmModel: parsed.model
      }
    })
    const task = res.data
    upsertTask(task)
    selectedId.value = task.id
    revokeVideoUrl()
    autoLoadedTaskId.value = null
    message.success('任务已提交')
    // SSE 若未连通，立刻用轮询兜底，避免一直停在「排队中」
    ensurePolling()
  } catch {
    // interceptor
  } finally {
    submitting.value = false
  }
}

function shortTime(s?: string | null) {
  if (!s) return '—'
  // yyyy-MM-dd HH:mm:ss → MM-dd HH:mm
  const m = s.match(/(\d{2}-\d{2})\s+(\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : s
}

async function handlePause(task?: AigenTaskItem | null) {
  const t = task || selected.value
  if (!t || !canPause(t)) return
  pausingId.value = t.id
  try {
    const res = await aigenApi.pauseTask(t.id)
    upsertTask(res.data)
    message.info(
      res.data.status === 'PAUSED' && !isPauseDraining(res.data)
        ? '任务已暂停'
        : '已请求暂停，当前步骤结束后生效'
    )
  } catch {
    // interceptor
  } finally {
    pausingId.value = ''
  }
}

async function handleCancel() {
  if (!selectedId.value) return
  try {
    const res = await aigenApi.cancelTask(selectedId.value)
    upsertTask(res.data)
    message.info('已请求取消')
  } catch {
    // ignore
  }
}

function openRetryModal(task?: AigenTaskItem | null) {
  const t = task || selected.value
  if (!t || !canRetry(t.status)) {
    message.warning('当前状态不可重试')
    return
  }
  // 列表点开时同步选中
  if (selectedId.value !== t.id) {
    selectTask(t.id)
  }
  retryTarget.value = t
  if (t.llmProvider && t.llmModel) {
    retryModelKey.value = `${t.llmProvider}::${t.llmModel}`
  } else {
    retryModelKey.value = selectedModelKey.value || ''
  }
  retryTestOk.value = false
  retryTestFail.value = false
  retryModalOpen.value = true
  loadModels()
}

async function testRetryModel() {
  const parsed = parseModelKey(retryModelKey.value)
  if (!parsed) {
    message.warning('请先选择模型')
    return
  }
  retryTesting.value = true
  retryTestOk.value = false
  retryTestFail.value = false
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    if (res.data?.available) {
      retryTestOk.value = true
      message.success('模型可用')
    } else {
      retryTestFail.value = true
      message.error(res.data?.errorMessage || '模型不可用')
    }
  } catch (e: any) {
    retryTestFail.value = true
    const isTimeout =
      e?.code === 'ECONNABORTED' ||
      String(e?.message || '').toLowerCase().includes('timeout')
    message.error(isTimeout ? '超过 10 秒无响应，判定不可用' : e?.message || '测试失败')
  } finally {
    retryTesting.value = false
  }
}

async function submitRetry() {
  const task = retryTarget.value || selected.value
  if (!task) return Promise.reject()
  const parsed = parseModelKey(retryModelKey.value)
  if (!parsed) {
    message.warning('请选择 LLM 模型')
    return Promise.reject()
  }
  const wasSuccess = task.status === 'SUCCESS'
  retryingId.value = task.id
  try {
    const res = await aigenApi.retryTask(task.id, {
      llmProvider: parsed.provider,
      llmModel: parsed.model
    })
    upsertTask({
      ...res.data,
      errorMessage: res.data.errorMessage ?? '',
      llmProvider: parsed.provider,
      llmModel: parsed.model
    })
    if (selectedId.value === task.id) {
      storyboardPreview.value = ''
      revokeVideoUrl()
      autoLoadedTaskId.value = null
    }
    retryModalOpen.value = false
    retryTarget.value = null
    message.success(wasSuccess ? '已重新排队，开始重新生成' : '已重新排队，开始重试')
  } catch {
    return Promise.reject()
  } finally {
    retryingId.value = ''
  }
}

/**
 * 删除确认：风格对齐视频提取
 */
function confirmDelete(task: AigenTaskItem) {
  const title = task.title || task.prompt || '未命名任务'
  const runningHint =
    isRunning(task.status) || task.status === 'PENDING'
      ? '该任务仍在处理/排队中，删除后将无法查看进度与结果。'
      : '此操作不可恢复。'

  Modal.confirm({
    title: '确认删除该任务？',
    icon: createVNode(ExclamationCircleOutlined),
    content: createVNode('div', { class: 'aigen-delete-confirm' }, [
      createVNode('p', { style: 'margin:0 0 8px;color:#1f2937;font-weight:500' }, title),
      createVNode(
        'p',
        { style: 'margin:0;color:#6b7280;font-size:13px;line-height:1.6' },
        `${runningHint}将同时删除：数据库记录、任务目录中的分镜与成片文件。`
      )
    ]),
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    async onOk() {
      await doDelete(task.id)
    }
  })
}

async function doDelete(id: string) {
  deletingId.value = id
  try {
    await aigenApi.deleteTask(id)
    tasks.value = tasks.value.filter((t) => t.id !== id)
    if (selectedId.value === id) {
      selectedId.value = tasks.value[0]?.id ?? null
      storyboardPreview.value = ''
      revokeVideoUrl()
      autoLoadedTaskId.value = null
    }
    message.success('任务及相关文件已删除')
  } catch {
    // interceptor
  } finally {
    deletingId.value = ''
  }
}

async function loadStoryboard() {
  if (!selectedId.value) return
  storyboardLoading.value = true
  try {
    const res = await aigenApi.getStoryboard(selectedId.value)
    storyboardPreview.value = JSON.stringify(res.data, null, 2)
  } catch {
    storyboardPreview.value = ''
  } finally {
    storyboardLoading.value = false
  }
}

// 选中任务变化 / 成片就绪 → 自动加载
watch(
  () => [selectedId.value, selected.value?.outputAvailable, selected.value?.status] as const,
  async () => {
    await maybeAutoLoadVideo()
  }
)

let sseClose: (() => void) | null = null

function needsPoll(task?: AigenTaskItem | null) {
  if (!task?.status) return false
  return (
    task.status === 'PENDING' ||
    task.status === 'PLANNING' ||
    task.status === 'ASSET_GENERATING' ||
    task.status === 'RENDERING' ||
    isPauseDraining(task)
  )
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollingActive.value = false
}

function ensurePolling() {
  if (sseConnected.value) {
    stopPolling()
    return
  }
  if (!tasks.value.some((t) => needsPoll(t))) {
    stopPolling()
    return
  }
  if (!pollTimer) {
    startPolling()
  }
}

function startPolling() {
  stopPolling()
  pollingActive.value = true
  const tick = async () => {
    if (sseConnected.value) {
      stopPolling()
      return
    }
    if (!tasks.value.some((t) => needsPoll(t))) {
      stopPolling()
      return
    }
    try {
      const res = await aigenApi.listTasks(0, 50)
      const map = new Map((res.data?.items || []).map((t) => [t.id, t]))
      tasks.value = tasks.value.map((t) => map.get(t.id) || t)
      for (const item of res.data?.items || []) {
        if (!tasks.value.find((t) => t.id === item.id)) {
          tasks.value.unshift(item)
        }
      }
      void maybeAutoLoadVideo()
    } catch {
      /* ignore */
    }
    const active = tasks.value.some((t) => needsPoll(t))
    if (!active || sseConnected.value) {
      stopPolling()
      return
    }
    pollTimer = setTimeout(() => {
      pollTimer = null
      void tick()
    }, 3000)
  }
  void tick()
}

onMounted(async () => {
  await Promise.all([loadTemplates(), loadModels(), loadVoices(), loadTasks()])
  await maybeAutoLoadVideo()
  ensurePolling()
  sseClose = connectAigenTaskEvents({
    onOpen: () => {
      sseConnected.value = true
      sseConnecting.value = false
      stopPolling()
    },
    onError: () => {
      sseConnected.value = false
      sseConnecting.value = false
      ensurePolling()
    },
    onClose: () => {
      sseConnected.value = false
    },
    onEvent: (ev) => {
      sseConnected.value = true
      sseConnecting.value = false
      if (ev.type === 'connected' || ev.type === 'ping') return
      if (ev.type === 'task.deleted') {
        const id = String(ev.data?.id || ev.data?.taskId || ev.taskId || '')
        if (id) {
          tasks.value = tasks.value.filter((t) => t.id !== id)
          if (selectedId.value === id) {
            selectedId.value = tasks.value[0]?.id ?? null
            storyboardPreview.value = ''
            revokeVideoUrl()
            autoLoadedTaskId.value = null
          }
        }
        ensurePolling()
        return
      }
      if (ev.type === 'task.created' || ev.type === 'task.status') {
        if (ev.data) {
          mergeFromEvent(ev.data)
          void maybeAutoLoadVideo()
        }
        ensurePolling()
      }
    }
  }).close
})

watch(
  () => tasks.value.map((t) => `${t.id}:${t.status}`).join('|'),
  () => ensurePolling()
)

onUnmounted(() => {
  stopPolling()
  sseClose?.()
  revokeVideoUrl()
})
</script>

<style lang="scss" scoped src="./aigen-ui.scss"></style>
