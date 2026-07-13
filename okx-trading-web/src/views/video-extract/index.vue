<template>
  <div class="video-extract-page">
    <!-- 顶部：提交区 -->
    <div class="submit-hero page-card">
      <div class="hero-text">
        <div class="hero-title-row">
          <h2 class="page-title">视频核心内容提取</h2>
          <a-tooltip :title="liveChannelTip">
            <span class="live-badge" :class="liveChannel">
              <span class="live-dot" />
              <span class="live-label">{{ liveChannelLabel }}</span>
              <span v-if="lastEventHint" class="live-hint">{{ lastEventHint }}</span>
            </span>
          </a-tooltip>
        </div>
        <p class="page-subtitle">
          粘贴抖音 / B站 / YouTube 链接，自动下载 · 转录 · AI 提炼要点与二创文案
        </p>
      </div>

      <div class="submit-row">
        <a-input
          v-model:value="urlInput"
          size="large"
          allow-clear
          class="url-input"
          placeholder="粘贴视频链接，例如 https://www.bilibili.com/video/BVxxxx"
          @pressEnter="handleSubmit"
        >
          <template #prefix>
            <LinkOutlined class="input-prefix-icon" />
          </template>
        </a-input>
        <a-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          <template #icon><ThunderboltOutlined /></template>
          开始提取
        </a-button>
      </div>

      <!-- LLM 模型选择 + 连通性测试 -->
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
            placeholder="选择用于总结的模型（数据库配置）"
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
          <a-tag v-else color="default">未测试（可选，可直接创建）</a-tag>
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
        capability="chat"
        @changed="onModelsChanged"
      />

      <div class="options-row">
        <a-space wrap :size="16">
          <span class="opt-label">语言</span>
          <a-radio-group v-model:value="options.language" size="small" button-style="solid">
            <a-radio-button value="zh">中文</a-radio-button>
            <a-radio-button value="en">English</a-radio-button>
          </a-radio-group>
          <a-divider type="vertical" />
          <a-checkbox v-model:checked="options.extractMindMap">思维导图</a-checkbox>
          <a-checkbox v-model:checked="options.generateRepurposeScript">二创脚本</a-checkbox>
        </a-space>
        <div class="platform-hints">
          <span class="hint-chip">抖音</span>
          <span class="hint-chip">B站</span>
          <span class="hint-chip">YouTube</span>
          <span class="hint-chip">小红书</span>
        </div>
      </div>
    </div>

    <!-- 主体：左列表 + 右详情 -->
    <div class="workspace">
      <!-- 左侧任务列表 -->
      <aside class="task-panel page-card">
        <div class="panel-header">
          <span class="panel-title">历史任务</span>
          <a-button type="text" size="small" :loading="listLoading" @click="loadTasks">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </div>

        <div class="task-list" v-if="tasks.length">
          <div
            v-for="task in tasks"
            :key="task.taskId"
            class="task-item"
            :class="{ active: selectedId === task.taskId }"
            @click="selectTask(task.taskId)"
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
                    :loading="pausingId === task.taskId"
                    @click.stop="handlePause(task)"
                  >
                    <template #icon><PauseCircleOutlined /></template>
                  </a-button>
                </a-tooltip>
                <a-button
                  v-if="canRetry(task)"
                  type="text"
                  size="small"
                  class="task-retry-btn"
                  :loading="retryingId === task.taskId"
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
                  :loading="deletingId === task.taskId"
                  @click.stop="confirmDelete(task)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </div>
            </div>
            <div class="task-title" :title="task.title || task.url">
              {{ task.title || '处理中…' }}
            </div>
            <div class="task-meta">
              <span class="platform-badge" v-if="task.platform">{{ platformLabel(task.platform) }}</span>
              <span class="platform-badge llm-badge" v-if="task.llmModel" :title="task.llmModel">
                {{ shortModelName(task.llmModel) }}
              </span>
              <span class="task-step" v-if="isRunning(task.status) || isPauseDraining(task)">
                {{ task.currentStep }}
              </span>
              <span class="task-cost" v-else-if="task.totalDurationMs != null" :title="timingTooltip(task)">
                耗时 {{ formatMs(task.totalDurationMs) }}
              </span>
              <span class="task-dur" v-else-if="task.durationSeconds">{{ formatDuration(task.durationSeconds) }}</span>
            </div>
          </div>
        </div>
        <a-empty v-else description="暂无任务，粘贴链接开始吧" :image-style="{ height: '48px' }" />

        <div class="list-footer" v-if="taskTotal > tasks.length">
          <a-button type="link" size="small" :loading="listLoading" @click="loadMore">加载更多</a-button>
        </div>
      </aside>

      <!-- 右侧详情 -->
      <main class="detail-panel page-card">
        <template v-if="!selectedId">
          <div class="empty-detail">
            <div class="empty-visual">
              <VideoCameraOutlined />
            </div>
            <h3>选择或创建一个任务</h3>
            <p>提交链接后，可在此查看进度、要点、转录时间轴与原始视频</p>
            <div class="pipeline-preview">
              <div class="pipe-step" v-for="s in pipelineSteps" :key="s.key">
                <div class="pipe-icon">{{ s.icon }}</div>
                <div class="pipe-name">{{ s.name }}</div>
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="detailLoading && !detail">
          <div class="detail-loading">
            <a-spin size="large" tip="加载任务详情…" />
          </div>
        </template>

        <template v-else-if="detail">
          <!-- 标题栏 -->
          <div class="detail-header">
            <div class="detail-header-main">
              <a-tag :color="statusMeta(detail.status, detail).color">
                {{ statusMeta(detail.status, detail).label }}
              </a-tag>
              <h3 class="detail-title">{{ detail.title || '未命名视频' }}</h3>
              <div class="detail-sub">
                <span v-if="detail.platform" class="platform-badge">{{ platformLabel(detail.platform) }}</span>
                <span v-if="detail.llmModel" class="platform-badge llm-badge" :title="detail.llmProvider || ''">
                  {{ shortModelName(detail.llmModel) }}
                </span>
                <span v-if="detail.durationSeconds">时长 {{ formatDuration(detail.durationSeconds) }}</span>
                <span v-if="detail.createdAt">创建于 {{ detail.createdAt }}</span>
                <a v-if="detail.url" :href="detail.url" target="_blank" rel="noopener" class="source-link">
                  打开源链接 <ExportOutlined />
                </a>
              </div>
            </div>
            <a-space>
              <a-button type="text" :loading="detailLoading" @click="refreshDetail">
                <template #icon><ReloadOutlined /></template>
              </a-button>
              <a-tooltip v-if="canPause(detail)" :title="pauseButtonTip(detail)">
                <a-button :loading="pausingId === detail.taskId" @click="handlePause(detail)">
                  <template #icon><PauseCircleOutlined /></template>
                  暂停
                </a-button>
              </a-tooltip>
              <a-button
                v-if="canRetry(detail)"
                type="primary"
                ghost
                :loading="retryingId === detail.taskId"
                @click="openRetryModal(detail)"
              >
                <template #icon><RedoOutlined /></template>
                重试
              </a-button>
              <a-button
                type="text"
                danger
                :loading="deletingId === detail.taskId"
                @click="confirmDelete(detail)"
              >
                <template #icon><DeleteOutlined /></template>
                删除
              </a-button>
            </a-space>
          </div>

          <!-- 进度条（处理中） -->
          <div class="progress-block" v-if="isRunning(detail.status)">
            <a-steps
              :current="statusStepIndex(detail.status)"
              size="small"
              :items="stepItemsWithTiming(detail)"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ detail.currentStep || '处理中…' }} · 下载与转录可能需要几分钟，请稍候</span>
            </div>
            <a-alert
              type="info"
              show-icon
              class="pause-policy-hint"
              message="暂停说明"
              description="暂停仅在步骤边界生效（下载 / 转录 / 总结之间）。当前步骤会跑完后再中断，不会立即强杀进行中的下载或模型调用。"
            />
            <div class="timing-strip" v-if="hasAnyStepTiming(detail)">
              <span class="timing-chip" v-if="detail.downloadDurationMs != null">
                下载 <b>{{ formatMs(detail.downloadDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="detail.transcribeDurationMs != null">
                转录 <b>{{ formatMs(detail.transcribeDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="detail.summarizeDurationMs != null">
                总结 <b>{{ formatMs(detail.summarizeDurationMs) }}</b>
              </span>
            </div>
          </div>

          <!-- 已点暂停、等待当前步骤跑完 -->
          <div class="progress-block pause-draining" v-else-if="isPauseDraining(detail)">
            <a-alert
              type="warning"
              show-icon
              class="pause-policy-hint"
              message="已请求暂停 · 等待当前步骤结束"
              :description="pauseDrainingDesc(detail)"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ detail.currentStep || '暂停中，等待当前步骤结束…' }}</span>
            </div>
          </div>

          <!-- 步骤耗时（成功 / 失败均可展示已完成步骤） -->
          <div
            class="timing-panel"
            v-if="!isRunning(detail.status) && hasAnyStepTiming(detail)"
          >
            <div class="timing-panel-title">
              <span>执行耗时</span>
              <span class="timing-total" v-if="detail.totalDurationMs != null">
                总计 {{ formatMs(detail.totalDurationMs) }}
              </span>
            </div>
            <div class="timing-bars">
              <div
                class="timing-bar-row"
                v-for="row in timingRows(detail)"
                :key="row.key"
              >
                <div class="timing-bar-label">
                  <span>{{ row.label }}</span>
                  <span class="timing-bar-ms">{{ formatMs(row.ms) }}</span>
                </div>
                <div class="timing-bar-track">
                  <div
                    class="timing-bar-fill"
                    :class="row.key"
                    :style="{ width: timingBarWidth(row.ms, detail) }"
                  />
                </div>
              </div>
            </div>
            <div class="timing-meta" v-if="detail.startedAt || detail.finishedAt">
              <span v-if="detail.startedAt">开始 {{ detail.startedAt }}</span>
              <span v-if="detail.finishedAt">结束 {{ detail.finishedAt }}</span>
            </div>
          </div>

          <!-- 暂停完成提示（非「等待步骤结束」中） -->
          <a-alert
            v-if="detail.status === 'PAUSED' && !isPauseDraining(detail)"
            type="warning"
            show-icon
            class="fail-alert"
            message="任务已暂停"
          >
            <template #description>
              <div class="fail-desc">
                <span>
                  已在步骤边界中断（暂停不会强制中断进行中的下载 / 转录 / 总结）。
                  可「重试」从流水线重新排队，或先处理其它任务。
                </span>
                <a-button
                  type="primary"
                  size="small"
                  :loading="retryingId === detail.taskId"
                  @click="openRetryModal(detail)"
                >
                  <template #icon><RedoOutlined /></template>
                  重试
                </a-button>
              </div>
            </template>
          </a-alert>

          <!-- 失败 -->
          <a-alert
            v-if="detail.status === 'FAILED'"
            type="error"
            show-icon
            class="fail-alert"
            :message="detail.errorMessage || '任务失败'"
          >
            <template #description>
              <div class="fail-desc">
                <span>可检查链接、Whisper 与 AI Key，并重新选择模型后重试完整流水线。</span>
                <a-button
                  type="primary"
                  size="small"
                  danger
                  :loading="retryingId === detail.taskId"
                  @click="openRetryModal(detail)"
                >
                  <template #icon><RedoOutlined /></template>
                  重试
                </a-button>
              </div>
            </template>
          </a-alert>

          <!-- 重试弹窗：可重新配置 LLM -->
          <a-modal
            v-model:open="retryModalOpen"
            :title="retryTarget?.status === 'SUCCESS' ? '重新提取' : '重试任务'"
            :ok-text="retryTarget?.status === 'SUCCESS' ? '开始重新提取' : '开始重试'"
            cancel-text="取消"
            :confirm-loading="retryingId === retryTarget?.taskId"
            @ok="submitRetry"
          >
            <p class="retry-hint">
              <template v-if="retryTarget?.status === 'SUCCESS'">
                将清空当前结果并重新执行：下载 → 转录 → 总结。可更换 LLM 模型（测试可用性可选）。
              </template>
              <template v-else>
                将重新执行：下载 → 转录 → 总结。可更换 LLM 模型（测试可用性可选）。
              </template>
            </p>
            <div class="retry-url" v-if="retryTarget">
              <span class="muted">链接</span>
              <div class="url-text">{{ retryTarget.url }}</div>
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

          <!-- 成功结果 -->
          <template v-if="detail.status === 'SUCCESS' && detail.result">
            <a-tabs v-model:activeKey="activeTab" class="result-tabs">
              <!-- 概览 -->
              <a-tab-pane key="overview" tab="概览">
                <div class="overview-grid">
                  <div class="video-box" v-if="detail.videoAvailable">
                    <video
                      ref="videoRef"
                      class="video-player"
                      controls
                      preload="metadata"
                      :src="videoApi.videoStreamUrl(detail.taskId)"
                    />
                  </div>
                  <div class="video-box video-placeholder" v-else>
                    <VideoCameraOutlined />
                    <span>视频文件不可用（可能已清理）</span>
                  </div>

                  <div class="overview-side">
                    <div class="mini-card">
                      <div class="mini-label">核心要点</div>
                      <div class="mini-value">{{ detail.result.summary?.keyPoints?.length || 0 }}</div>
                    </div>
                    <div class="mini-card">
                      <div class="mini-label">章节</div>
                      <div class="mini-value">{{ detail.result.summary?.chapters?.length || 0 }}</div>
                    </div>
                    <div class="mini-card">
                      <div class="mini-label">字幕段</div>
                      <div class="mini-value">{{ detail.result.transcription?.segments?.length || 0 }}</div>
                    </div>
                    <div class="quick-keypoints" v-if="detail.result.summary?.keyPoints?.length">
                      <div class="qk-title">速览要点</div>
                      <div
                        v-for="(kp, i) in detail.result.summary.keyPoints.slice(0, 5)"
                        :key="i"
                        class="qk-item"
                        @click="seekToTimestamp(kp.timestamp)"
                      >
                        <span class="ts">{{ kp.timestamp }}</span>
                        <span class="pt">{{ kp.point }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </a-tab-pane>

              <!-- 核心要点 -->
              <a-tab-pane key="keypoints" tab="核心要点">
                <div class="kp-list" v-if="detail.result.summary?.keyPoints?.length">
                  <div
                    v-for="(kp, i) in detail.result.summary.keyPoints"
                    :key="i"
                    class="kp-card"
                    @click="seekToTimestamp(kp.timestamp)"
                  >
                    <div class="kp-index">{{ i + 1 }}</div>
                    <div class="kp-body">
                      <a-tag color="blue" class="ts-tag">{{ kp.timestamp || '--' }}</a-tag>
                      <div class="kp-text">{{ kp.point }}</div>
                    </div>
                  </div>
                </div>
                <a-empty v-else description="暂无要点" />
              </a-tab-pane>

              <!-- 章节 -->
              <a-tab-pane key="chapters" tab="章节大纲">
                <a-timeline v-if="detail.result.summary?.chapters?.length">
                  <a-timeline-item v-for="(ch, i) in detail.result.summary.chapters" :key="i" color="blue">
                    <div class="chapter-item" @click="seekToTimestamp(ch.timestamp)">
                      <div class="ch-head">
                        <span class="ch-title">{{ ch.title }}</span>
                        <a-tag>{{ ch.timestamp || '--' }}</a-tag>
                      </div>
                      <div class="ch-summary">{{ ch.summary }}</div>
                    </div>
                  </a-timeline-item>
                </a-timeline>
                <a-empty v-else description="暂无章节" />
              </a-tab-pane>

              <!-- 思维导图 -->
              <a-tab-pane key="mindmap" tab="思维导图">
                <div class="md-toolbar" v-if="detail.result.summary?.mindMapMarkdown">
                  <a-button size="small" @click="copyText(detail.result.summary.mindMapMarkdown!)">
                    <template #icon><CopyOutlined /></template>
                    复制 Markdown
                  </a-button>
                </div>
                <div
                  v-if="detail.result.summary?.mindMapMarkdown"
                  class="md-body"
                  v-html="renderMarkdown(detail.result.summary.mindMapMarkdown)"
                />
                <a-empty v-else description="未生成思维导图（提交时可能关闭了该选项）" />
              </a-tab-pane>

              <!-- 二创脚本 -->
              <a-tab-pane key="repurpose" tab="二创脚本">
                <div class="md-toolbar" v-if="detail.result.summary?.repurposeScript">
                  <a-button type="primary" size="small" @click="copyText(detail.result.summary.repurposeScript!)">
                    <template #icon><CopyOutlined /></template>
                    复制文案
                  </a-button>
                </div>
                <div
                  v-if="detail.result.summary?.repurposeScript"
                  class="script-box"
                >{{ detail.result.summary.repurposeScript }}</div>
                <a-empty v-else description="未生成二创脚本" />
              </a-tab-pane>

              <!-- 转录 -->
              <a-tab-pane key="transcript" tab="全文转录">
                <div class="transcript-toolbar">
                  <a-input-search
                    v-model:value="transcriptQuery"
                    placeholder="搜索字幕…"
                    allow-clear
                    style="max-width: 280px"
                  />
                  <a-button
                    size="small"
                    :disabled="!detail.result.transcription?.text"
                    @click="copyText(detail.result.transcription?.text || '')"
                  >
                    <template #icon><CopyOutlined /></template>
                    复制全文
                  </a-button>
                </div>
                <div class="segment-list" v-if="filteredSegments.length">
                  <div
                    v-for="seg in filteredSegments"
                    :key="seg.id"
                    class="seg-row"
                    :class="{ active: activeSegId === seg.id }"
                    @click="seekToSeconds(seg.start)"
                  >
                    <span class="seg-time">{{ formatSeconds(seg.start) }}</span>
                    <span class="seg-text" v-html="highlightQuery(seg.text)" />
                  </div>
                </div>
                <a-empty v-else description="暂无转录内容" />
              </a-tab-pane>
            </a-tabs>
          </template>

          <!-- 处理中但尚无结果 -->
          <div v-else-if="isRunning(detail.status)" class="waiting-card">
            <div class="waiting-illustration">⏳</div>
            <p>流水线运行中：下载 → 转录 → AI 总结</p>
            <p class="muted">列表会自动刷新，完成后结果会出现在这里</p>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  LinkOutlined,
  ThunderboltOutlined,
  ReloadOutlined,
  VideoCameraOutlined,
  ExportOutlined,
  CopyOutlined,
  DeleteOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  SettingOutlined,
  RedoOutlined,
  PauseCircleOutlined
} from '@ant-design/icons-vue'
import { createVNode } from 'vue'
import MarkdownIt from 'markdown-it'
import { videoApi } from '@/api/video.api'
import { connectVideoTaskEvents, type VideoTaskSseEvent } from '@/api/video.events'
import type { VideoTaskItem, TranscriptionSegment, AiProvider } from '@/types/api'
import ModelManageModal from './ModelManageModal.vue'
import { useAuthStore } from '@/stores/auth.store'

const auth = useAuthStore()

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const urlInput = ref('')
const submitting = ref(false)
const options = reactive({
  language: 'zh',
  extractMindMap: true,
  generateRepurposeScript: true
})

/** provider::modelId */
const selectedModelKey = ref('')
const availableProviders = ref<AiProvider[]>([])
const modelsLoading = ref(false)
const modelManageOpen = ref(false)
const testingModel = ref(false)
/** 测试通过的 modelKey 集合 */
const testedOkKeys = ref<Set<string>>(new Set())
const testedFailKeys = ref<Set<string>>(new Set())
const testErrorMsg = ref('')
const lastTestLatency = ref<number | null>(null)

const tasks = ref<VideoTaskItem[]>([])
const taskTotal = ref(0)
const listPage = ref(0)
const listLoading = ref(false)
const selectedId = ref('')
const detail = ref<VideoTaskItem | null>(null)
const detailLoading = ref(false)
const activeTab = ref('overview')
const transcriptQuery = ref('')
const activeSegId = ref<number | null>(null)
const videoRef = ref<HTMLVideoElement | null>(null)
const deletingId = ref('')
const retryingId = ref('')
const pausingId = ref('')
const retryModalOpen = ref(false)
const retryTarget = ref<VideoTaskItem | null>(null)
const retryModelKey = ref('')
const retryTesting = ref(false)
const retryTestOk = ref(false)
const retryTestFail = ref(false)

let pollTimer: ReturnType<typeof setTimeout> | null = null

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
  () => !!urlInput.value.trim() && !!selectedModelKey.value && !submitting.value
)

function parseModelKey(key: string): { provider: string; model: string } | null {
  const i = key.indexOf('::')
  if (i <= 0) return null
  return { provider: key.slice(0, i), model: key.slice(i + 2) }
}

function shortModelName(id?: string | null) {
  if (!id) return ''
  const parts = id.split('/')
  return parts[parts.length - 1] || id
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
    // 若当前选中已不在列表中，清空
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
    // 默认选中第一个供应商的第一个模型
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
    // axios 超时 code 为 ECONNABORTED；统一提示 10s 不可用
    const isTimeout =
      e?.code === 'ECONNABORTED' ||
      String(e?.message || '').toLowerCase().includes('timeout')
    testErrorMsg.value = isTimeout
      ? '超过 10 秒无响应，判定不可用'
      : e?.message || '测试请求失败'
    message.error(testErrorMsg.value)
  } finally {
    testingModel.value = false
  }
}

const pipelineSteps = [
  { key: 'PENDING', name: '排队', icon: '①' },
  { key: 'DOWNLOADING', name: '下载', icon: '②' },
  { key: 'TRANSCRIBING', name: '转录', icon: '③' },
  { key: 'SUMMARIZING', name: '总结', icon: '④' },
  { key: 'SUCCESS', name: '完成', icon: '⑤' }
]

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  PENDING: { label: '排队中', color: 'default' },
  DOWNLOADING: { label: '下载中', color: 'processing' },
  TRANSCRIBING: { label: '转录中', color: 'purple' },
  SUMMARIZING: { label: '总结中', color: 'cyan' },
  SUCCESS: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  PAUSED: { label: '已暂停', color: 'warning' }
}

/** 暂停仅在下载/转录/总结步骤边界生效 */
const PAUSE_STEP_BOUNDARY_TIP =
  '暂停仅在步骤边界生效：当前「下载 / 转录 / 总结」会先跑完，再中断并释放并发槽，不会立即强制停止。'

function statusMeta(status?: string, task?: VideoTaskItem | null) {
  if (task && isPauseDraining(task)) {
    return { label: '暂停中', color: 'warning' }
  }
  return STATUS_MAP[status || ''] || { label: status || '未知', color: 'default' }
}

function isRunning(status?: string) {
  return ['PENDING', 'DOWNLOADING', 'TRANSCRIBING', 'SUMMARIZING'].includes(status || '')
}

/** 已点暂停，但当前步骤可能仍在跑（等待边界生效） */
function isPauseDraining(task?: VideoTaskItem | null) {
  if (!task || task.status !== 'PAUSED') return false
  const step = task.currentStep || ''
  return step.includes('暂停中') || step.includes('等待当前步骤')
}

function canPause(task?: VideoTaskItem | null) {
  if (!task) return false
  return isRunning(task.status)
}

function pauseButtonTip(task?: VideoTaskItem | null) {
  if (!task) return PAUSE_STEP_BOUNDARY_TIP
  if (task.status === 'PENDING') {
    return '排队中暂停：立即取消排队，不会开始执行'
  }
  return PAUSE_STEP_BOUNDARY_TIP
}

function pauseDrainingDesc(task?: VideoTaskItem | null) {
  return (
    (task?.currentStep ? `${task.currentStep}。` : '') +
    '暂停只在步骤边界生效，请稍候；完成后可「重试」继续。其它排队任务可先被调度。'
  )
}

/** 列表/详情需要继续轮询的任务（含暂停等待收尾） */
function needsPoll(task?: VideoTaskItem | null) {
  if (!task) return false
  return isRunning(task.status) || isPauseDraining(task)
}

function statusStepIndex(status?: string) {
  const order = ['PENDING', 'DOWNLOADING', 'TRANSCRIBING', 'SUMMARIZING', 'SUCCESS']
  const i = order.indexOf(status || '')
  return i >= 0 ? i : 0
}

function platformLabel(p?: string | null) {
  const map: Record<string, string> = {
    douyin: '抖音',
    bilibili: 'B站',
    youtube: 'YouTube',
    xiaohongshu: '小红书',
    other: '其他'
  }
  return map[p || ''] || p || ''
}

function formatDuration(sec?: number | null) {
  if (sec == null || isNaN(sec)) return '--'
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
  return `${m}:${String(r).padStart(2, '0')}`
}

/** 毫秒耗时可读化：1.2s / 1m 05s / 1h 02m */
function formatMs(ms?: number | null) {
  if (ms == null || isNaN(ms) || ms < 0) return '--'
  if (ms < 1000) return `${Math.round(ms)}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(sec < 10 ? 1 : 0)}s`
  const totalSec = Math.floor(sec)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  return `${m}m ${String(s).padStart(2, '0')}s`
}

function hasAnyStepTiming(task: VideoTaskItem) {
  return (
    task.downloadDurationMs != null ||
    task.transcribeDurationMs != null ||
    task.summarizeDurationMs != null ||
    task.totalDurationMs != null
  )
}

function timingRows(task: VideoTaskItem) {
  const rows: { key: string; label: string; ms: number }[] = []
  if (task.downloadDurationMs != null) {
    rows.push({ key: 'download', label: '下载', ms: task.downloadDurationMs })
  }
  if (task.transcribeDurationMs != null) {
    rows.push({ key: 'transcribe', label: '转录', ms: task.transcribeDurationMs })
  }
  if (task.summarizeDurationMs != null) {
    rows.push({ key: 'summarize', label: '总结', ms: task.summarizeDurationMs })
  }
  return rows
}

function timingBarWidth(ms: number, task: VideoTaskItem) {
  const rows = timingRows(task)
  const max = Math.max(...rows.map((r) => r.ms), 1)
  const pct = Math.max(6, Math.round((ms / max) * 100))
  return `${pct}%`
}

function timingTooltip(task: VideoTaskItem) {
  const parts: string[] = []
  if (task.downloadDurationMs != null) parts.push(`下载 ${formatMs(task.downloadDurationMs)}`)
  if (task.transcribeDurationMs != null) parts.push(`转录 ${formatMs(task.transcribeDurationMs)}`)
  if (task.summarizeDurationMs != null) parts.push(`总结 ${formatMs(task.summarizeDurationMs)}`)
  if (task.totalDurationMs != null) parts.push(`合计 ${formatMs(task.totalDurationMs)}`)
  return parts.join(' · ') || ''
}

function stepItemsWithTiming(task: VideoTaskItem) {
  const map: Record<string, number | null | undefined> = {
    DOWNLOADING: task.downloadDurationMs,
    TRANSCRIBING: task.transcribeDurationMs,
    SUMMARIZING: task.summarizeDurationMs
  }
  return pipelineSteps.map((s) => {
    const ms = map[s.key]
    const title = ms != null ? `${s.name} ${formatMs(ms)}` : s.name
    return { title }
  })
}

function formatSeconds(sec: number) {
  return formatDuration(sec)
}

function shortTime(t?: string | null) {
  if (!t) return ''
  // yyyy-MM-dd HH:mm:ss → MM-dd HH:mm
  const m = t.match(/(\d{2})-(\d{2})\s+(\d{2}:\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}` : t
}

function renderMarkdown(text: string) {
  return md.render(text || '')
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}

const filteredSegments = computed(() => {
  const segs = detail.value?.result?.transcription?.segments || []
  const q = transcriptQuery.value.trim().toLowerCase()
  if (!q) return segs
  return segs.filter((s) => (s.text || '').toLowerCase().includes(q))
})

function highlightQuery(text: string) {
  const q = transcriptQuery.value.trim()
  if (!q) return escapeHtml(text)
  const safe = escapeHtml(text)
  const re = new RegExp(`(${escapeRegExp(escapeHtml(q))})`, 'gi')
  return safe.replace(re, '<mark>$1</mark>')
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 解析 mm:ss / hh:mm:ss */
function parseTimestamp(ts?: string): number | null {
  if (!ts) return null
  const parts = ts.trim().split(':').map((x) => parseInt(x, 10))
  if (parts.some((n) => isNaN(n))) return null
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  return null
}

function seekToTimestamp(ts?: string) {
  const sec = parseTimestamp(ts)
  if (sec != null) seekToSeconds(sec)
}

function seekToSeconds(sec: number) {
  activeTab.value = 'overview'
  nextTick(() => {
    const el = videoRef.value
    if (el) {
      el.currentTime = Math.max(0, sec)
      el.play().catch(() => {/* autoplay may block */})
    } else {
      // 视频不可用时切到转录并高亮
      activeTab.value = 'transcript'
    }
    // 高亮最近 segment
    const segs = detail.value?.result?.transcription?.segments || []
    let best: TranscriptionSegment | null = null
    for (const s of segs) {
      if (s.start <= sec) best = s
      else break
    }
    activeSegId.value = best?.id ?? null
  })
}

async function handleSubmit() {
  const url = urlInput.value.trim()
  if (!url) {
    message.warning('请粘贴视频链接')
    return
  }
  const parsed = parseModelKey(selectedModelKey.value)
  if (!parsed) {
    message.warning('请选择 LLM 模型')
    return
  }
  submitting.value = true
  try {
    const res = await videoApi.process({
      url,
      options: {
        language: options.language,
        extractMindMap: options.extractMindMap,
        generateRepurposeScript: options.generateRepurposeScript,
        llmProvider: parsed.provider,
        llmModel: parsed.model
      }
    })
    message.success('任务已提交，后台处理中')
    urlInput.value = ''
    await loadTasks(true)
    if (res.data?.taskId) {
      await selectTask(res.data.taskId)
    }
  } catch {
    // 错误已由 request 拦截器提示
  } finally {
    submitting.value = false
  }
}

async function loadTasks(reset = false) {
  if (reset) listPage.value = 0
  listLoading.value = true
  try {
    const res = await videoApi.listTasks(listPage.value, 20)
    const page = res.data
    taskTotal.value = page.total
    if (reset || listPage.value === 0) {
      tasks.value = page.items || []
    } else {
      const ids = new Set(tasks.value.map((t) => t.taskId))
      for (const item of page.items || []) {
        if (!ids.has(item.taskId)) tasks.value.push(item)
      }
    }
  } catch {
    // ignore
  } finally {
    listLoading.value = false
  }
}

async function loadMore() {
  listPage.value += 1
  await loadTasks(false)
}

async function selectTask(taskId: string) {
  selectedId.value = taskId
  activeTab.value = 'overview'
  transcriptQuery.value = ''
  await refreshDetail()
}

/** 暂停进行中/排队中任务 */
async function handlePause(task: VideoTaskItem) {
  if (!canPause(task)) {
    message.warning('仅排队中或进行中的任务可暂停')
    return
  }

  const isPending = task.status === 'PENDING'
  Modal.confirm({
    title: isPending ? '暂停排队任务' : '暂停进行中的任务',
    content: isPending
      ? '任务尚未开始执行，将立即取消排队并标记为已暂停。'
      : PAUSE_STEP_BOUNDARY_TIP + '是否继续？',
    okText: isPending ? '立即暂停' : '请求暂停',
    cancelText: '取消',
    onOk: () => doPause(task)
  })
}

async function doPause(task: VideoTaskItem) {
  pausingId.value = task.taskId
  try {
    const res = await videoApi.pauseTask(task.taskId)
    message.success(
      task.status === 'PENDING'
        ? '已暂停排队任务'
        : '已请求暂停：当前步骤结束后中断，并调度其它排队任务'
    )
    const idx = tasks.value.findIndex((t) => t.taskId === task.taskId)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = { ...tasks.value[idx], ...res.data }
    }
    if (selectedId.value === task.taskId) {
      await refreshDetail()
    }
    await loadTasks(true)
  } catch {
    // ignore
  } finally {
    pausingId.value = ''
  }
}

/** 失败 / 暂停 / 成功 可重试（可改 LLM） */
function canRetry(task?: VideoTaskItem | null) {
  if (!task?.status) return false
  return ['FAILED', 'PAUSED', 'SUCCESS'].includes(String(task.status).toUpperCase())
}

/** 打开重试弹窗（可改 LLM） */
function openRetryModal(task: VideoTaskItem) {
  if (!canRetry(task)) {
    message.warning('仅失败、已暂停或已成功的任务可重试')
    return
  }
  retryTarget.value = task
  // 默认用原任务模型，否则用当前页选中模型
  if (task.llmProvider && task.llmModel) {
    retryModelKey.value = `${task.llmProvider}::${task.llmModel}`
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

/**
 * 确认重试：可带新 LLM 模型。
 */
async function submitRetry() {
  const task = retryTarget.value
  if (!task) return Promise.reject()
  const parsed = parseModelKey(retryModelKey.value)
  if (!parsed) {
    message.warning('请选择 LLM 模型')
    return Promise.reject()
  }
  retryingId.value = task.taskId
  try {
    const res = await videoApi.retryTask(task.taskId, {
      llmProvider: parsed.provider,
      llmModel: parsed.model
    })
    message.success(
      task.status === 'SUCCESS' ? '已重新排队，开始重新提取' : '已重新排队，开始重试'
    )
    retryModalOpen.value = false
    const idx = tasks.value.findIndex((t) => t.taskId === task.taskId)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = { ...tasks.value[idx], ...res.data, result: undefined }
    }
    if (selectedId.value === task.taskId) {
      detail.value = {
        ...(res.data || task),
        result: undefined,
        status: res.data?.status || 'PENDING',
        currentStep: res.data?.currentStep || '重试排队中',
        errorMessage: null,
        downloadDurationMs: null,
        transcribeDurationMs: null,
        summarizeDurationMs: null,
        totalDurationMs: null,
        finishedAt: null,
        llmProvider: parsed.provider,
        llmModel: parsed.model
      }
      await refreshDetail()
    }
    await loadTasks(true)
  } catch {
    return Promise.reject()
  } finally {
    retryingId.value = ''
  }
}

/**
 * 删除确认：清理数据库记录 + 本地视频/音频/JSON。
 */
function confirmDelete(task: VideoTaskItem) {
  const title = task.title || '未命名视频'
  const runningHint = isRunning(task.status)
    ? '该任务仍在处理中，删除后将无法查看进度与结果。'
    : '此操作不可恢复。'

  Modal.confirm({
    title: '确认删除该任务？',
    icon: createVNode(ExclamationCircleOutlined),
    content: createVNode('div', { class: 'video-delete-confirm' }, [
      createVNode('p', { style: 'margin:0 0 8px;color:#1f2937;font-weight:500' }, title),
      createVNode(
        'p',
        { style: 'margin:0;color:#6b7280;font-size:13px;line-height:1.6' },
        `${runningHint}将同时删除：数据库记录、本地视频/音频、转录与摘要文件。`
      )
    ]),
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    async onOk() {
      await doDelete(task.taskId)
    }
  })
}

async function doDelete(taskId: string) {
  deletingId.value = taskId
  try {
    await videoApi.deleteTask(taskId)
    message.success('任务及相关文件已删除')
    tasks.value = tasks.value.filter((t) => t.taskId !== taskId)
    taskTotal.value = Math.max(0, taskTotal.value - 1)
    if (selectedId.value === taskId) {
      selectedId.value = ''
      detail.value = null
    }
  } catch {
    // 错误已由 request 拦截器提示
  } finally {
    deletingId.value = ''
  }
}

async function refreshDetail() {
  if (!selectedId.value) return
  detailLoading.value = true
  try {
    const res = await videoApi.getTask(selectedId.value)
    detail.value = res.data
    // 同步列表中的状态与耗时
    const idx = tasks.value.findIndex((t) => t.taskId === selectedId.value)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = {
        ...tasks.value[idx],
        status: res.data.status,
        title: res.data.title,
        currentStep: res.data.currentStep,
        durationSeconds: res.data.durationSeconds,
        videoAvailable: res.data.videoAvailable,
        errorMessage: res.data.errorMessage,
        finishedAt: res.data.finishedAt,
        startedAt: res.data.startedAt,
        downloadDurationMs: res.data.downloadDurationMs,
        transcribeDurationMs: res.data.transcribeDurationMs,
        summarizeDurationMs: res.data.summarizeDurationMs,
        totalDurationMs: res.data.totalDurationMs
      }
    }
  } catch {
    // ignore
  } finally {
    detailLoading.value = false
  }
}

/** SSE 已连接时不轮询；断线则智能轮询兜底 */
const sseConnected = ref(false)
/** 是否正在发起/重连 SSE（尚未 onOpen） */
const sseConnecting = ref(false)
/** 智能轮询是否在跑（用于角标，需 ref 才能刷新 UI） */
const pollingActive = ref(false)
/** 最近一次有效业务事件（不含 ping） */
const lastSseEventAt = ref<number | null>(null)
const lastSseEventType = ref('')
/** 角标「xs前」每秒刷新 */
const liveTick = ref(0)
let liveHintTimer: ReturnType<typeof setInterval> | null = null
let sseHandle: { close: () => void } | null = null
let pollIntervalMs = 3000
let visibilityHandler: (() => void) | null = null

/** idle | connecting | sse | poll */
const liveChannel = computed(() => {
  if (sseConnected.value) return 'sse'
  if (pollingActive.value) return 'poll'
  if (sseConnecting.value) return 'connecting'
  return 'idle'
})

const liveChannelLabel = computed(() => {
  switch (liveChannel.value) {
    case 'sse':
      return '实时 · SSE'
    case 'poll':
      return '实时 · 轮询'
    case 'connecting':
      return '连接中…'
    default:
      return '待机'
  }
})

const lastEventHint = computed(() => {
  void liveTick.value
  if (!lastSseEventAt.value || !lastSseEventType.value) return ''
  const sec = Math.max(0, Math.round((Date.now() - lastSseEventAt.value) / 1000))
  const when = sec < 5 ? '刚刚' : sec < 60 ? `${sec}s前` : `${Math.floor(sec / 60)}m前`
  return `${lastSseEventType.value} · ${when}`
})

const liveChannelTip = computed(() => {
  const base =
    '状态通道：SSE=服务端推送长连接（Network 里叫 events，类型常是 fetch，不是 EventStream）；轮询=断线时兜底拉 tasks；待机=无活跃任务且未连上推送。'
  if (liveChannel.value === 'sse') {
    return `${base}\n当前：SSE 已连接，任务状态由推送更新。Edge 请在 Network → 筛选「全部」搜 events，看一条一直 Pending 的请求。`
  }
  if (liveChannel.value === 'poll') {
    return `${base}\n当前：SSE 未连通，正在智能轮询 /v1/video/tasks。`
  }
  if (liveChannel.value === 'connecting') {
    return `${base}\n当前：正在连接 /api/v1/video/events …`
  }
  return `${base}\n当前：待机（无进行中任务时也可能不轮询）。`
})

function startLiveHintClock() {
  if (liveHintTimer) return
  liveHintTimer = setInterval(() => {
    liveTick.value++
  }, 1000)
}
function stopLiveHintClock() {
  if (liveHintTimer) {
    clearInterval(liveHintTimer)
    liveHintTimer = null
  }
}

function mergeTaskPatch(taskId: string, patch: Partial<VideoTaskItem>) {
  const idx = tasks.value.findIndex((t) => t.taskId === taskId)
  if (idx >= 0) {
    tasks.value[idx] = { ...tasks.value[idx], ...patch }
  } else if (patch.status || patch.url) {
    // 新任务（created）插到顶部
    tasks.value.unshift({
      taskId,
      status: patch.status || 'PENDING',
      url: patch.url || '',
      ...patch
    } as VideoTaskItem)
    taskTotal.value += 1
  }
  if (selectedId.value === taskId && detail.value) {
    detail.value = { ...detail.value, ...patch }
  } else if (selectedId.value === taskId && !detail.value && patch) {
    detail.value = { taskId, status: patch.status || 'PENDING', url: patch.url || '', ...patch } as VideoTaskItem
  }
}

async function handleSseEvent(ev: VideoTaskSseEvent) {
  if (ev.type === 'ping') return
  if (ev.type === 'connected') {
    sseConnected.value = true
    sseConnecting.value = false
    lastSseEventType.value = 'connected'
    lastSseEventAt.value = Date.now()
    return
  }

  lastSseEventType.value = String(ev.type || 'event')
  lastSseEventAt.value = Date.now()

  if (ev.type === 'task.deleted') {
    const id = String(ev.taskId || ev.data?.taskId || '')
    if (!id) return
    tasks.value = tasks.value.filter((t) => t.taskId !== id)
    taskTotal.value = Math.max(0, taskTotal.value - 1)
    if (selectedId.value === id) {
      selectedId.value = ''
      detail.value = null
    }
    return
  }

  const data = (ev.data || {}) as Partial<VideoTaskItem> & { taskId?: string }
  const taskId = String(ev.taskId || data.taskId || '')
  if (!taskId) return

  const patch: Partial<VideoTaskItem> = {
    taskId,
    status: data.status as VideoTaskItem['status'],
    url: data.url as string | undefined,
    title: data.title as string | null | undefined,
    platform: data.platform as string | null | undefined,
    llmProvider: data.llmProvider as string | null | undefined,
    llmModel: data.llmModel as string | null | undefined,
    currentStep: data.currentStep as string | null | undefined,
    errorMessage: data.errorMessage as string | null | undefined,
    durationSeconds: data.durationSeconds as number | null | undefined,
    videoAvailable: data.videoAvailable as boolean | null | undefined,
    createdAt: data.createdAt as string | null | undefined,
    startedAt: data.startedAt as string | null | undefined,
    finishedAt: data.finishedAt as string | null | undefined,
    downloadDurationMs: data.downloadDurationMs as number | null | undefined,
    transcribeDurationMs: data.transcribeDurationMs as number | null | undefined,
    summarizeDurationMs: data.summarizeDurationMs as number | null | undefined,
    totalDurationMs: data.totalDurationMs as number | null | undefined
  }
  // 去掉 undefined，避免覆盖已有字段
  Object.keys(patch).forEach((k) => {
    if ((patch as any)[k] === undefined) delete (patch as any)[k]
  })

  mergeTaskPatch(taskId, patch)

  // 终态成功：拉完整 result（含摘要/转录）
  if (selectedId.value === taskId && data.status === 'SUCCESS') {
    await refreshDetail()
  }
}

function startSse() {
  stopSse()
  sseConnecting.value = true
  sseHandle = connectVideoTaskEvents({
    onOpen: () => {
      // HTTP 流已建立；真正业务以 connected 事件为准，这里先标为已连
      sseConnected.value = true
      sseConnecting.value = false
      stopPolling()
    },
    onEvent: (ev) => {
      void handleSseEvent(ev)
    },
    onError: () => {
      sseConnected.value = false
      sseConnecting.value = false
      ensurePolling()
    },
    onClose: () => {
      sseConnected.value = false
      sseConnecting.value = false
    }
  })
}

function stopSse() {
  sseHandle?.close()
  sseHandle = null
  sseConnected.value = false
  sseConnecting.value = false
}

function computePollInterval(): number {
  if (typeof document !== 'undefined' && document.hidden) return 15000
  const active = tasks.value.filter((t) => needsPoll(t))
  if (!active.length) return 8000
  if (active.some((t) => t.status === 'SUMMARIZING' || isPauseDraining(t))) return 2000
  if (active.some((t) => t.status === 'DOWNLOADING' || t.status === 'TRANSCRIBING')) return 3000
  return 5000 // PENDING 等
}

function ensurePolling() {
  if (sseConnected.value) {
    stopPolling()
    return
  }
  const hasActive =
    tasks.value.some((t) => needsPoll(t)) || !!(detail.value && needsPoll(detail.value))
  if (!hasActive) {
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
    const hasActive =
      tasks.value.some((t) => needsPoll(t)) || !!(detail.value && needsPoll(detail.value))
    if (!hasActive) {
      stopPolling()
      return
    }
    try {
      const res = await videoApi.listTasks(0, 20)
      const map = new Map((res.data.items || []).map((t) => [t.taskId, t]))
      tasks.value = tasks.value.map((t) => map.get(t.taskId) || t)
      for (const item of res.data.items || []) {
        if (!tasks.value.find((t) => t.taskId === item.taskId)) {
          tasks.value.unshift(item)
        }
      }
      lastSseEventType.value = 'poll.tasks'
      lastSseEventAt.value = Date.now()
    } catch {
      /* ignore */
    }
    if (detail.value && needsPoll(detail.value)) {
      await refreshDetail()
    }
    pollIntervalMs = computePollInterval()
    pollTimer = setTimeout(() => {
      pollTimer = null
      void tick()
    }, pollIntervalMs)
  }
  void tick()
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollingActive.value = false
}

watch(selectedId, () => {
  activeSegId.value = null
})

// 任务列表变化时：SSE 断线则维护轮询
watch(
  () => tasks.value.map((t) => `${t.taskId}:${t.status}:${t.currentStep}`).join('|'),
  () => ensurePolling()
)

onMounted(async () => {
  await Promise.all([loadModels(), loadTasks(true)])
  startSse()
  ensurePolling()
  startLiveHintClock()
  visibilityHandler = () => {
    if (!document.hidden && !sseConnected.value) {
      ensurePolling()
    }
  }
  document.addEventListener('visibilitychange', visibilityHandler)
})

onUnmounted(() => {
  stopPolling()
  stopSse()
  stopLiveHintClock()
  if (visibilityHandler) {
    document.removeEventListener('visibilitychange', visibilityHandler)
    visibilityHandler = null
  }
})
</script>

<style lang="scss" scoped>
.video-extract-page {
  max-width: 1400px;
  margin: 0 auto;
}

.submit-hero {
  margin-bottom: 18px;
  padding: 24px 26px 20px;
  border-radius: 20px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.98) 0%, rgba(239, 246, 255, 0.96) 50%, rgba(236, 253, 245, 0.92) 100%);
  border: 1px solid rgba(59, 130, 246, 0.12);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 12px 36px rgba(37, 99, 235, 0.07);
  position: relative;
  overflow: hidden;

  &::after {
    content: '';
    position: absolute;
    right: -40px;
    top: -40px;
    width: 200px;
    height: 200px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(37, 99, 235, 0.14), transparent 70%);
    pointer-events: none;
  }

  .hero-text {
    margin-bottom: 16px;
  }

  .hero-title-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px 14px;
    margin-bottom: 4px;

    .page-title {
      margin-bottom: 0;
    }
  }

  .live-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 3px 10px 3px 8px;
    border-radius: 999px;
    font-size: 12px;
    line-height: 1.4;
    border: 1px solid transparent;
    cursor: help;
    user-select: none;
    white-space: nowrap;

    .live-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      flex-shrink: 0;
      background: #9ca3af;
    }

    .live-label {
      font-weight: 600;
      color: #374151;
    }

    .live-hint {
      color: #9ca3af;
      font-weight: 400;
      max-width: 160px;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    &.sse {
      background: #ecfdf5;
      border-color: #a7f3d0;
      .live-dot {
        background: #10b981;
        box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.25);
        animation: live-pulse 1.6s ease-in-out infinite;
      }
      .live-label {
        color: #047857;
      }
    }

    &.poll {
      background: #fff7ed;
      border-color: #fed7aa;
      .live-dot {
        background: #f59e0b;
        animation: live-pulse 1.6s ease-in-out infinite;
      }
      .live-label {
        color: #c2410c;
      }
    }

    &.connecting {
      background: #eff6ff;
      border-color: #bfdbfe;
      .live-dot {
        background: #3b82f6;
        animation: live-pulse 0.9s ease-in-out infinite;
      }
      .live-label {
        color: #1d4ed8;
      }
    }

    &.idle {
      background: #f9fafb;
      border-color: #e5e7eb;
      .live-dot {
        background: #9ca3af;
      }
      .live-label {
        color: #6b7280;
      }
    }
  }

  @keyframes live-pulse {
    0%,
    100% {
      opacity: 1;
      transform: scale(1);
    }
    50% {
      opacity: 0.55;
      transform: scale(0.85);
    }
  }

  .submit-row {
    display: flex;
    gap: 12px;
    align-items: center;

    .url-input {
      flex: 1;

      :deep(.ant-input-affix-wrapper),
      :deep(input) {
        border-radius: 10px;
      }
    }

    .submit-btn {
      height: 44px;
      border-radius: 12px;
      padding: 0 24px;
      font-weight: 650;
      border: none;
      background: linear-gradient(135deg, #2563eb, #4f46e5 55%, #6366f1);
      box-shadow: 0 10px 22px rgba(37, 99, 235, 0.28);

      &:hover:not(:disabled) {
        filter: brightness(1.04);
      }
    }

    .input-prefix-icon {
      color: var(--text-muted);
    }
  }

  .model-row {
    margin-top: 14px;
    padding: 14px 16px;
    background: rgba(255, 255, 255, 0.8);
    border: 1px solid rgba(148, 163, 184, 0.22);
    border-radius: 16px;
    backdrop-filter: blur(8px);

    .model-pick {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;

      .opt-label {
        color: var(--text-secondary);
        font-size: 13px;
        flex-shrink: 0;
      }

      .model-select {
        flex: 1;
        min-width: 260px;
        max-width: 520px;
      }

      .test-btn,
      .manage-btn {
        border-radius: 10px;
      }
    }

    .model-status {
      margin-top: 10px;
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      font-size: 12px;

      &.muted {
        color: var(--text-muted);
      }

      .test-err {
        color: var(--danger-color);
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .options-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 14px;
    flex-wrap: wrap;
    gap: 10px;

    .opt-label {
      color: var(--text-secondary);
      font-size: 13px;
    }
  }

  .platform-hints {
    display: flex;
    gap: 6px;

    .hint-chip {
      font-size: 12px;
      color: var(--primary-color);
      background: rgba(22, 119, 255, 0.08);
      border-radius: 999px;
      padding: 2px 10px;
    }
  }
}

.workspace {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
  min-height: calc(100vh - 280px);
}

.task-panel {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  max-height: calc(100vh - 220px);

  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border-color);

    .panel-title {
      font-weight: 600;
      font-size: 14px;
    }
  }

  .task-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  .list-footer {
    text-align: center;
    padding: 4px 0 10px;
    border-top: 1px solid var(--border-color);
  }
}

.task-item {
  padding: 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
  margin-bottom: 4px;

  &:hover {
    background: #f5f7fa;
  }

  &.active {
    background: #ebf5ff;
    border-color: rgba(22, 119, 255, 0.25);
  }

  .task-item-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;

    .status-tag {
      margin: 0;
      font-size: 12px;
    }

    .task-item-actions {
      display: flex;
      align-items: center;
      gap: 2px;
    }

    .task-time {
      font-size: 11px;
      color: var(--text-muted);
    }

    .task-del-btn,
    .task-retry-btn,
    .task-pause-btn {
      opacity: 0;
      transition: opacity 0.15s;
      width: 24px;
      height: 24px;
      min-width: 24px;
      padding: 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    .task-retry-btn {
      color: var(--primary-color);
    }

    .task-pause-btn {
      color: var(--warning-color, #f59e0b);
    }
  }

  &:hover .task-del-btn,
  &:hover .task-retry-btn,
  &:hover .task-pause-btn,
  &.active .task-del-btn,
  &.active .task-retry-btn,
  &.active .task-pause-btn {
    opacity: 1;
  }

  .task-title {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-primary);
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin-bottom: 6px;
  }

  .task-meta {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 12px;
    color: var(--text-secondary);

    .task-step {
      color: var(--primary-color);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}

.platform-badge {
  display: inline-block;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
  background: #f3f4f6;
  color: var(--text-secondary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.llm-badge {
    background: #eef2ff;
    color: #4f46e5;
  }
}

.detail-panel {
  min-height: 520px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 20px 22px;
}

.empty-detail {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  text-align: center;
  color: var(--text-secondary);

  .empty-visual {
    width: 72px;
    height: 72px;
    border-radius: 20px;
    background: linear-gradient(135deg, #e6f4ff, #f0f5ff);
    color: var(--primary-color);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 32px;
    margin-bottom: 16px;
  }

  h3 {
    color: var(--text-primary);
    margin-bottom: 8px;
  }

  p {
    max-width: 360px;
    margin-bottom: 28px;
  }

  .pipeline-preview {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    justify-content: center;

    .pipe-step {
      width: 72px;
      padding: 12px 8px;
      background: #f9fafb;
      border: 1px solid var(--border-color);
      border-radius: 12px;

      .pipe-icon {
        font-size: 16px;
        margin-bottom: 4px;
        color: var(--primary-color);
      }

      .pipe-name {
        font-size: 12px;
        color: var(--text-secondary);
      }
    }
  }
}

.detail-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 320px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;

  .detail-title {
    margin: 8px 0 6px;
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    line-height: 1.35;
  }

  .detail-sub {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    font-size: 12px;
    color: var(--text-secondary);
    align-items: center;

    .source-link {
      color: var(--primary-color);
    }
  }
}

.pause-policy-hint {
  margin-top: 12px;
  border-radius: 10px;
}

.progress-block.pause-draining {
  .progress-hint {
    margin-top: 10px;
  }
}

.progress-block {
  background: #f8fafc;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;

  .progress-hint {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
    font-size: 13px;
    color: var(--text-secondary);
  }

  .timing-strip {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }
}

.timing-chip {
  font-size: 12px;
  color: var(--text-secondary);
  background: #fff;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  padding: 2px 10px;

  b {
    color: var(--primary-color);
    font-weight: 600;
    margin-left: 2px;
  }
}

.timing-panel {
  background: linear-gradient(135deg, #f8fafc 0%, #eef6ff 100%);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;

  .timing-panel-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 12px;

    .timing-total {
      font-weight: 600;
      color: var(--primary-color);
      font-size: 13px;
    }
  }

  .timing-bars {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .timing-bar-row {
    .timing-bar-label {
      display: flex;
      justify-content: space-between;
      font-size: 12px;
      color: var(--text-secondary);
      margin-bottom: 4px;

      .timing-bar-ms {
        font-variant-numeric: tabular-nums;
        color: var(--text-primary);
        font-weight: 500;
      }
    }

    .timing-bar-track {
      height: 8px;
      background: #e5e7eb;
      border-radius: 999px;
      overflow: hidden;
    }

    .timing-bar-fill {
      height: 100%;
      border-radius: 999px;
      transition: width 0.35s ease;

      &.download {
        background: linear-gradient(90deg, #60a5fa, #2563eb);
      }

      &.transcribe {
        background: linear-gradient(90deg, #a78bfa, #7c3aed);
      }

      &.summarize {
        background: linear-gradient(90deg, #34d399, #059669);
      }
    }
  }

  .timing-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-top: 12px;
    font-size: 11px;
    color: var(--text-muted);
  }
}

.task-cost {
  color: var(--primary-color);
  font-variant-numeric: tabular-nums;
}

.fail-alert {
  margin-bottom: 16px;

  .fail-desc {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 4px;
  }
}

.retry-hint {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.retry-url {
  margin-bottom: 12px;

  .muted {
    font-size: 12px;
    color: var(--text-muted);
  }

  .url-text {
    font-size: 12px;
    word-break: break-all;
    color: var(--text-primary);
    margin-top: 4px;
  }
}

.retry-model-row {
  margin-bottom: 10px;

  .opt-label {
    font-size: 13px;
    color: var(--text-secondary);
  }
}

.retry-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.waiting-card {
  text-align: center;
  padding: 40px 16px;
  color: var(--text-secondary);

  .waiting-illustration {
    font-size: 36px;
    margin-bottom: 12px;
  }

  .muted {
    font-size: 12px;
    color: var(--text-muted);
    margin-top: 6px;
  }
}

.result-tabs {
  :deep(.ant-tabs-nav) {
    margin-bottom: 16px;
  }
}

.overview-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 16px;

  @media (max-width: 1100px) {
    grid-template-columns: 1fr;
  }
}

.video-box {
  border-radius: 12px;
  overflow: hidden;
  background: #0f172a;
  aspect-ratio: 16 / 9;
  display: flex;
  align-items: center;
  justify-content: center;

  .video-player {
    width: 100%;
    height: 100%;
    background: #000;
  }

  &.video-placeholder {
    flex-direction: column;
    gap: 8px;
    color: #94a3b8;
    font-size: 13px;

    .anticon {
      font-size: 28px;
    }
  }
}

.overview-side {
  display: flex;
  flex-direction: column;
  gap: 10px;

  .mini-card {
    background: #f8fafc;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 12px 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;

    .mini-label {
      font-size: 13px;
      color: var(--text-secondary);
    }

    .mini-value {
      font-size: 20px;
      font-weight: 600;
      color: var(--primary-color);
    }
  }

  .quick-keypoints {
    flex: 1;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 12px;
    overflow: auto;
    max-height: 260px;

    .qk-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-secondary);
      margin-bottom: 8px;
    }

    .qk-item {
      display: flex;
      gap: 8px;
      padding: 8px;
      border-radius: 8px;
      cursor: pointer;
      font-size: 13px;
      transition: background 0.15s;

      &:hover {
        background: #ebf5ff;
      }

      .ts {
        flex-shrink: 0;
        color: var(--primary-color);
        font-variant-numeric: tabular-nums;
        font-size: 12px;
      }

      .pt {
        color: var(--text-primary);
        line-height: 1.4;
      }
    }
  }
}

.kp-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kp-card {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s;
  background: #fff;

  &:hover {
    border-color: rgba(22, 119, 255, 0.35);
    box-shadow: 0 4px 12px rgba(22, 119, 255, 0.08);
    transform: translateY(-1px);
  }

  .kp-index {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: #ebf5ff;
    color: var(--primary-color);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 600;
    font-size: 13px;
    flex-shrink: 0;
  }

  .kp-body {
    flex: 1;

    .ts-tag {
      margin-bottom: 6px;
    }
  }

  .kp-text {
    font-size: 14px;
    line-height: 1.55;
    color: var(--text-primary);
  }
}

.chapter-item {
  cursor: pointer;
  padding: 4px 0 8px;

  .ch-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;

    .ch-title {
      font-weight: 600;
      color: var(--text-primary);
    }
  }

  .ch-summary {
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.55;
  }

  &:hover .ch-title {
    color: var(--primary-color);
  }
}

.md-toolbar,
.transcript-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
}

.md-body {
  background: #fafbfc;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 16px 20px;
  line-height: 1.7;

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 12px 0 8px;
    font-weight: 600;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 20px;
  }

  :deep(code) {
    background: #f1f5f9;
    padding: 1px 6px;
    border-radius: 4px;
    font-size: 12px;
  }

  :deep(pre) {
    background: #0f172a;
    color: #e2e8f0;
    padding: 12px;
    border-radius: 8px;
    overflow: auto;
  }
}

.script-box {
  white-space: pre-wrap;
  background: linear-gradient(180deg, #fffbeb, #ffffff);
  border: 1px solid #fde68a;
  border-radius: 12px;
  padding: 18px 20px;
  line-height: 1.7;
  font-size: 14px;
  color: var(--text-primary);
}

.segment-list {
  max-height: 520px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: 12px;
}

.seg-row {
  display: flex;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  transition: background 0.12s;

  &:last-child {
    border-bottom: none;
  }

  &:hover,
  &.active {
    background: #ebf5ff;
  }

  .seg-time {
    flex-shrink: 0;
    width: 56px;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--primary-color);
    font-weight: 500;
    padding-top: 2px;
  }

  .seg-text {
    flex: 1;
    font-size: 13px;
    line-height: 1.55;
    color: var(--text-primary);

    :deep(mark) {
      background: #fef08a;
      padding: 0 2px;
      border-radius: 2px;
    }
  }
}

@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .task-panel {
    max-height: 280px;
  }

  .detail-panel {
    max-height: none;
  }
}
</style>
