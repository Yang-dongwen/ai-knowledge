<template>
  <div class="video-extract-page">
    <!-- 顶部：提交区 -->
    <div class="submit-hero page-card">
      <div class="hero-text">
        <h2 class="page-title">视频核心内容提取</h2>
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
          :disabled="!urlInput.trim()"
          @click="handleSubmit"
        >
          <template #icon><ThunderboltOutlined /></template>
          开始提取
        </a-button>
      </div>

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
              <a-tag :color="statusMeta(task.status).color" class="status-tag">
                {{ statusMeta(task.status).label }}
              </a-tag>
              <div class="task-item-actions">
                <span class="task-time">{{ shortTime(task.createdAt) }}</span>
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
              <span class="task-step" v-if="isRunning(task.status)">{{ task.currentStep }}</span>
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
              <a-tag :color="statusMeta(detail.status).color">{{ statusMeta(detail.status).label }}</a-tag>
              <h3 class="detail-title">{{ detail.title || '未命名视频' }}</h3>
              <div class="detail-sub">
                <span v-if="detail.platform" class="platform-badge">{{ platformLabel(detail.platform) }}</span>
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
              :items="pipelineSteps.map((s) => ({ title: s.name }))"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ detail.currentStep || '处理中…' }} · 下载与转录可能需要几分钟，请稍候</span>
            </div>
          </div>

          <!-- 失败 -->
          <a-alert
            v-if="detail.status === 'FAILED'"
            type="error"
            show-icon
            class="fail-alert"
            :message="detail.errorMessage || '任务失败'"
            description="可检查链接有效性、Whisper 服务与 AI Key 后重新提交"
          />

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
  ExclamationCircleOutlined
} from '@ant-design/icons-vue'
import { createVNode } from 'vue'
import MarkdownIt from 'markdown-it'
import { videoApi } from '@/api/video.api'
import type { VideoTaskItem, TranscriptionSegment } from '@/types/api'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const urlInput = ref('')
const submitting = ref(false)
const options = reactive({
  language: 'zh',
  extractMindMap: true,
  generateRepurposeScript: true
})

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

let pollTimer: ReturnType<typeof setInterval> | null = null

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
  FAILED: { label: '失败', color: 'error' }
}

function statusMeta(status?: string) {
  return STATUS_MAP[status || ''] || { label: status || '未知', color: 'default' }
}

function isRunning(status?: string) {
  return ['PENDING', 'DOWNLOADING', 'TRANSCRIBING', 'SUMMARIZING'].includes(status || '')
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
  submitting.value = true
  try {
    const res = await videoApi.process({
      url,
      options: {
        language: options.language,
        extractMindMap: options.extractMindMap,
        generateRepurposeScript: options.generateRepurposeScript
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
    // 同步列表中的状态
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
        finishedAt: res.data.finishedAt
      }
    }
  } catch {
    // ignore
  } finally {
    detailLoading.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    const hasRunning = tasks.value.some((t) => isRunning(t.status))
    if (!hasRunning && !(detail.value && isRunning(detail.value.status))) return
    // 轻量刷新列表
    try {
      const res = await videoApi.listTasks(0, 20)
      const map = new Map((res.data.items || []).map((t) => [t.taskId, t]))
      tasks.value = tasks.value.map((t) => map.get(t.taskId) || t)
      // 合并新任务到顶部
      for (const item of res.data.items || []) {
        if (!tasks.value.find((t) => t.taskId === item.taskId)) {
          tasks.value.unshift(item)
        }
      }
    } catch { /* ignore */ }

    if (detail.value && isRunning(detail.value.status)) {
      await refreshDetail()
    }
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(selectedId, () => {
  activeSegId.value = null
})

onMounted(async () => {
  await loadTasks(true)
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style lang="scss" scoped>
.video-extract-page {
  max-width: 1400px;
  margin: 0 auto;
}

.submit-hero {
  margin-bottom: 16px;
  background: linear-gradient(135deg, #ffffff 0%, #f0f7ff 55%, #eef9f4 100%);
  border: 1px solid var(--border-color);
  position: relative;
  overflow: hidden;

  &::after {
    content: '';
    position: absolute;
    right: -40px;
    top: -40px;
    width: 180px;
    height: 180px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(22, 119, 255, 0.12), transparent 70%);
    pointer-events: none;
  }

  .hero-text {
    margin-bottom: 16px;
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
      height: 40px;
      border-radius: 10px;
      padding: 0 22px;
      font-weight: 500;
      box-shadow: 0 4px 12px rgba(22, 119, 255, 0.25);
    }

    .input-prefix-icon {
      color: var(--text-muted);
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

    .task-del-btn {
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
  }

  &:hover .task-del-btn,
  &.active .task-del-btn {
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
}

.fail-alert {
  margin-bottom: 16px;
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
