<template>
  <div class="article-page">
    <div class="layout">
      <!-- 左侧：提交 + 历史 -->
      <aside class="left-panel page-card">
        <div class="panel-head">
          <h2 class="page-title">文章提取</h2>
          <a-tooltip :title="liveTip">
            <span class="live-badge" :class="liveChannel">
              <span class="live-dot" />
              {{ liveLabel }}
            </span>
          </a-tooltip>
        </div>
        <p class="page-subtitle">链接或粘贴正文 → 抓取/清洗 → 核心提取 → 二次创作</p>

        <div class="field">
          <label class="field-label">文章链接</label>
          <a-input
            v-model:value="url"
            size="large"
            allow-clear
            placeholder="https://… 头条 / 知乎 / 通用网页等"
            @blur="onUrlBlur"
            @pressEnter="handleSubmit"
          />
          <div v-if="platformInfo" class="platform-row">
            <a-tag :color="supportColor(platformInfo.supportLevel)">
              {{ platformInfo.platform || 'unknown' }} · {{ platformInfo.supportLevel || '-' }}
            </a-tag>
            <span class="platform-msg">{{ platformInfo.message }}</span>
          </div>
        </div>

        <a-collapse ghost class="paste-collapse">
          <a-collapse-panel key="paste" header="粘贴正文（可选 / 强反爬平台必填）">
            <a-textarea
              v-model:value="pasteText"
              :rows="6"
              :maxlength="100000"
              show-count
              placeholder="抓取失败或 PASTE_ONLY 平台时，直接粘贴正文…"
            />
            <a-checkbox v-model:checked="forcePasteOnly" style="margin-top: 8px">
              强制仅用粘贴（跳过抓取）
            </a-checkbox>
          </a-collapse-panel>
        </a-collapse>

        <div class="field">
          <label class="field-label">Chat 模型</label>
          <a-select
            v-model:value="selectedLlmKey"
            size="large"
            show-search
            :options="llmOptions"
            :filter-option="filterOption"
            :loading="modelsLoading"
            placeholder="选择用于提取/二创的模型"
            style="width: 100%"
          />
        </div>

        <div class="field">
          <label class="field-label">二创形态</label>
          <a-checkbox-group v-model:value="variants" :options="variantOptions" />
        </div>

        <div class="field toggles">
          <a-checkbox v-model:checked="generateRewrite">生成二次创作</a-checkbox>
          <a-checkbox v-model:checked="extractMindMap">思维导图</a-checkbox>
          <a-checkbox v-model:checked="allowPasteFallback">抓取失败可粘贴续跑</a-checkbox>
        </div>

        <a-alert
          type="info"
          show-icon
          class="disclaimer"
          :message="submitDisclaimer"
        />

        <a-button
          type="primary"
          size="large"
          block
          class="submit-btn"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          开始提取
        </a-button>

        <div class="history-block">
          <div class="history-head">
            <span>历史任务</span>
            <a-button type="link" size="small" :loading="listLoading" @click="loadList">刷新</a-button>
          </div>
          <div v-if="!tasks.length && !listLoading" class="empty-hint">暂无任务</div>
          <div
            v-for="t in tasks"
            :key="t.id"
            class="task-item"
            :class="{ active: selectedId === t.id }"
            @click="selectTask(t.id)"
          >
            <div class="task-title">{{ t.title || t.sourceUrl || '未命名' }}</div>
            <div class="task-meta">
              <a-tag :color="statusColor(t.status)" size="small">{{ t.status }}</a-tag>
              <span class="prog">{{ t.progress ?? 0 }}%</span>
            </div>
            <div class="task-step">{{ t.currentStep || t.errorMessage || '' }}</div>
          </div>
        </div>
      </aside>

      <!-- 右侧：结果 -->
      <main class="right-panel page-card">
        <template v-if="!detail">
          <div class="empty-main">
            <EmptyState title="选择或提交任务" description="左侧提交链接或从历史中点选任务查看结果" />
          </div>
        </template>

        <template v-else>
          <div class="detail-head">
            <div>
              <h3 class="detail-title">{{ detail.title || '处理中…' }}</h3>
              <div class="detail-meta">
                <a-tag :color="statusColor(detail.status)">{{ detail.status }}</a-tag>
                <a-tag v-if="detail.supportLevel" :color="supportColor(detail.supportLevel)">
                  {{ detail.supportLevel }}
                </a-tag>
                <a-tag v-if="detail.degraded" color="orange">降级</a-tag>
                <span class="muted">{{ detail.platform }} · {{ detail.progress }}%</span>
              </div>
              <div v-if="detail.currentStep" class="detail-step">{{ detail.currentStep }}</div>
            </div>
            <div class="detail-actions">
              <a-button
                v-if="detail.status === 'SUCCESS'"
                size="small"
                type="primary"
                ghost
                :loading="savingToKb"
                @click="saveToKnowledgeBase"
              >存入知识库</a-button>
              <a-button
                v-if="canPause(detail)"
                size="small"
                @click="doPause"
              >暂停</a-button>
              <a-button
                v-if="canCancel(detail)"
                size="small"
                danger
                @click="doCancel"
              >取消</a-button>
              <a-button
                v-if="canRetry(detail)"
                size="small"
                @click="doRetry"
              >重试</a-button>
              <a-button size="small" danger type="text" @click="doDelete">删除</a-button>
            </div>
          </div>

          <!-- NEEDS_PASTE 面板 -->
          <div v-if="detail.status === 'NEEDS_PASTE'" class="needs-paste-panel">
            <a-alert
              type="warning"
              show-icon
              :message="detail.errorCode || '需要粘贴正文'"
              :description="detail.errorMessage || '该平台无法自动抓取，请粘贴正文后继续'"
            />
            <a-textarea
              v-model:value="resumePaste"
              :rows="8"
              :maxlength="100000"
              show-count
              placeholder="在此粘贴文章全文…"
              style="margin-top: 12px"
            />
            <a-button
              type="primary"
              :loading="pasting"
              :disabled="!resumePaste.trim()"
              style="margin-top: 12px"
              @click="doPaste"
            >
              粘贴并继续
            </a-button>
          </div>

          <a-progress
            v-if="isRunning(detail) || detail.status === 'PENDING'"
            :percent="detail.progress || 0"
            size="small"
            style="margin: 12px 0"
          />

          <a-tabs v-model:activeKey="activeTab" class="result-tabs">
            <a-tab-pane key="summary" tab="摘要">
              <div class="tab-toolbar">
                <a-button size="small" @click="copyText(coreSummary)">复制摘要</a-button>
              </div>
              <p class="body-text">{{ coreSummary || '—' }}</p>
              <a-alert
                v-if="detail.disclaimer || resultDisclaimer"
                type="info"
                show-icon
                class="result-disclaimer"
                :message="detail.disclaimer || resultDisclaimer"
              />
            </a-tab-pane>

            <a-tab-pane key="points" tab="要点">
              <div class="tab-toolbar">
                <a-button size="small" @click="copyText(keyPointsText)">复制要点</a-button>
              </div>
              <ul v-if="keyPoints.length" class="kp-list">
                <li v-for="(kp, i) in keyPoints" :key="i">
                  <a-tag v-if="kp.importance" size="small">{{ kp.importance }}</a-tag>
                  {{ kp.point }}
                </li>
              </ul>
              <p v-else class="muted">暂无要点</p>
            </a-tab-pane>

            <a-tab-pane key="timeline" tab="时间线">
              <ul v-if="timeline.length" class="tl-list">
                <li v-for="(ev, i) in timeline" :key="i">
                  <strong>{{ ev.time || '—' }}</strong>
                  <span>{{ ev.event }}</span>
                </li>
              </ul>
              <p v-else class="muted">暂无时间线</p>
            </a-tab-pane>

            <a-tab-pane key="entities" tab="实体">
              <div v-if="hasEntities" class="entities">
                <div v-for="(list, key) in entityGroups" :key="key" class="ent-group">
                  <div class="ent-label">{{ entityLabel(String(key)) }}</div>
                  <a-tag v-for="n in list" :key="n">{{ n }}</a-tag>
                  <span v-if="!list?.length" class="muted">—</span>
                </div>
              </div>
              <p v-else class="muted">暂无实体</p>
            </a-tab-pane>

            <a-tab-pane key="rewrite" tab="二创">
              <div v-if="rewriteVariants.length">
                <div v-for="(v, i) in rewriteVariants" :key="i" class="rewrite-card">
                  <div class="rewrite-head">
                    <strong>{{ v.label || v.id || `形态 ${i + 1}` }}</strong>
                    <a-button size="small" type="link" @click="copyText(v.content || '')">复制</a-button>
                  </div>
                  <p v-if="v.hook" class="hook">Hook：{{ v.hook }}</p>
                  <pre class="rewrite-body">{{ v.content }}</pre>
                  <p v-if="v.cta" class="cta">CTA：{{ v.cta }}</p>
                </div>
              </div>
              <p v-else class="muted">
                {{ detail.status === 'SUCCESS' ? '未生成二创或已降级' : '完成后显示二创' }}
              </p>
            </a-tab-pane>

            <a-tab-pane key="mind" tab="导图">
              <pre v-if="mindMap" class="mind-md">{{ mindMap }}</pre>
              <p v-else class="muted">未开启或未生成思维导图</p>
            </a-tab-pane>
          </a-tabs>

          <div v-if="detail.sourceUrl" class="source-line">
            来源：
            <a :href="detail.sourceUrl" target="_blank" rel="noopener">{{ detail.sourceUrl }}</a>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import EmptyState from '@/components/EmptyState.vue'
import { articleApi } from '@/api/article.api'
import { connectArticleTaskEvents } from '@/api/article.events'
import { kbApi } from '@/api/kb.api'
import type {
  AiProvider,
  ArticleCore,
  ArticlePlatformDetectResult,
  ArticleRewriteVariant,
  ArticleTaskItem
} from '@/types/api'

const DEFAULT_SUBMIT_DISCLAIMER =
  '请仅提交您有权访问的内容。系统可能抓取公开网页；二次创作结果仅供学习研究，请遵守版权与平台规范，勿用于未授权商用传播。'
const RESULT_DISCLAIMER =
  '内容来源于公开网页或用户粘贴，AI 提取与改写可能有误，请自行核验事实与版权。'

const VARIANT_OPTIONS = [
  { label: '短视频口播', value: 'short_video_script' },
  { label: '公众号文', value: 'wechat_article' },
  { label: 'X 线程', value: 'x_thread' }
]

const url = ref('')
const pasteText = ref('')
const forcePasteOnly = ref(false)
const allowPasteFallback = ref(true)
const generateRewrite = ref(true)
const extractMindMap = ref(false)
const variants = ref<string[]>(['short_video_script', 'wechat_article'])
const variantOptions = VARIANT_OPTIONS

const modelsLoading = ref(false)
const llmProviders = ref<AiProvider[]>([])
const selectedLlmKey = ref<string>()
const submitting = ref(false)
const listLoading = ref(false)
const pasting = ref(false)
const savingToKb = ref(false)
const tasks = ref<ArticleTaskItem[]>([])
const selectedId = ref<string>()
const detail = ref<ArticleTaskItem | null>(null)
const resumePaste = ref('')
const activeTab = ref('summary')
const platformInfo = ref<ArticlePlatformDetectResult | null>(null)
const submitDisclaimer = ref(DEFAULT_SUBMIT_DISCLAIMER)
const resultDisclaimer = RESULT_DISCLAIMER

const liveChannel = ref<'live' | 'offline' | 'connecting'>('connecting')
const liveLabel = computed(() =>
  liveChannel.value === 'live' ? '实时' : liveChannel.value === 'connecting' ? '连接中' : '离线'
)
const liveTip = computed(() =>
  liveChannel.value === 'live'
    ? 'SSE 已连接，任务状态实时刷新'
    : 'SSE 未连接，可手动刷新列表'
)

let sseClose: (() => void) | null = null

const llmOptions = computed(() => {
  const opts: { label: string; value: string }[] = []
  for (const p of llmProviders.value) {
    for (const m of p.models || []) {
      opts.push({
        label: `${p.name || p.key} / ${m.name || m.id}`,
        value: `${p.key}::${m.id}`
      })
    }
  }
  return opts
})

const canSubmit = computed(() => {
  return !!(url.value.trim() || pasteText.value.trim()) && !submitting.value
})

const core = computed<ArticleCore | null>(() => {
  const c = detail.value?.core
  return c && typeof c === 'object' ? c : null
})

const coreSummary = computed(() => core.value?.summary || '')
const keyPoints = computed(() => core.value?.keyPoints || [])
const keyPointsText = computed(() =>
  keyPoints.value.map((k, i) => `${i + 1}. ${k.point || ''}`).join('\n')
)
const timeline = computed(() => core.value?.timeline || [])
const entityGroups = computed(() => {
  const e = core.value?.entities
  if (!e) return {} as Record<string, string[]>
  return {
    people: e.people || [],
    orgs: e.orgs || [],
    places: e.places || [],
    products: e.products || []
  }
})
const hasEntities = computed(() =>
  Object.values(entityGroups.value).some((l) => l && l.length > 0)
)
const mindMap = computed(() => core.value?.mindMapMarkdown || '')

const rewriteVariants = computed<ArticleRewriteVariant[]>(() => {
  const r = detail.value?.rewrite
  if (!r) return []
  const arr = r.rewriteVariants || r.variants || []
  return Array.isArray(arr) ? arr : []
})

function filterOption(input: string, option: { label?: string }) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

function supportColor(level?: string) {
  switch (level) {
    case 'FULL':
      return 'success'
    case 'PARTIAL':
      return 'warning'
    case 'PASTE_ONLY':
      return 'orange'
    case 'UNSUPPORTED':
      return 'error'
    default:
      return 'default'
  }
}

function statusColor(s?: string) {
  switch (s) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
    case 'CANCELLED':
      return 'error'
    case 'NEEDS_PASTE':
    case 'PAUSED':
      return 'warning'
    case 'PENDING':
      return 'default'
    default:
      return 'processing'
  }
}

function isRunning(t: ArticleTaskItem) {
  return ['RESOLVING', 'FETCHING', 'EXTRACTING', 'LLM_CORE', 'LLM_REWRITE'].includes(t.status)
}

function canPause(t: ArticleTaskItem) {
  return t.status === 'PENDING' || isRunning(t)
}

function canCancel(t: ArticleTaskItem) {
  return t.status === 'PENDING' || isRunning(t) || t.status === 'NEEDS_PASTE' || t.status === 'PAUSED'
}

function canRetry(t: ArticleTaskItem) {
  return ['FAILED', 'CANCELLED', 'PAUSED', 'SUCCESS'].includes(t.status)
}

function entityLabel(key: string) {
  const map: Record<string, string> = {
    people: '人物',
    orgs: '组织',
    places: '地点',
    products: '产品'
  }
  return map[key] || key
}

function parseLlmKey(key?: string): { provider?: string; model?: string } {
  if (!key) return {}
  const i = key.indexOf('::')
  if (i < 0) return { model: key }
  return { provider: key.slice(0, i), model: key.slice(i + 2) }
}

async function loadModels() {
  modelsLoading.value = true
  try {
    const res = await articleApi.listModels()
    llmProviders.value = res.data || []
    if (!selectedLlmKey.value && llmOptions.value.length) {
      selectedLlmKey.value = llmOptions.value[0].value
    }
  } catch (e: any) {
    message.error(e?.message || '加载模型失败')
  } finally {
    modelsLoading.value = false
  }
}

async function loadDisclaimer() {
  try {
    const res = await articleApi.disclaimer()
    if (res.data?.submit) submitDisclaimer.value = res.data.submit
  } catch {
    /* 用默认文案 */
  }
}

async function onUrlBlur() {
  const u = url.value.trim()
  if (!u) {
    platformInfo.value = null
    return
  }
  try {
    const res = await articleApi.detectPlatform(u)
    platformInfo.value = res.data
  } catch {
    platformInfo.value = null
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const res = await articleApi.listTasks(0, 30)
    tasks.value = res.data?.items || []
  } catch (e: any) {
    message.error(e?.message || '加载列表失败')
  } finally {
    listLoading.value = false
  }
}

async function selectTask(id: string) {
  selectedId.value = id
  try {
    const res = await articleApi.getTask(id)
    detail.value = res.data
    if (detail.value?.status === 'NEEDS_PASTE') {
      resumePaste.value = ''
    }
  } catch (e: any) {
    message.error(e?.message || '加载详情失败')
  }
}

async function handleSubmit() {
  if (!canSubmit.value) return
  const u = url.value.trim()
  const paste = pasteText.value.trim()
  if (!u && !paste) {
    message.warning('请填写链接或粘贴正文')
    return
  }
  const { provider, model } = parseLlmKey(selectedLlmKey.value)
  submitting.value = true
  try {
    const res = await articleApi.createTask({
      url: u || undefined,
      pasteText: paste || undefined,
      options: {
        language: 'zh',
        llmProvider: provider,
        llmModel: model,
        extractMindMap: extractMindMap.value,
        generateRewrite: generateRewrite.value,
        rewriteVariants: variants.value,
        allowPasteFallback: allowPasteFallback.value,
        forcePasteOnly: forcePasteOnly.value
      }
    })
    message.success('任务已提交')
    await loadList()
    if (res.data?.id) {
      await selectTask(res.data.id)
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '提交失败'
    message.error(msg)
  } finally {
    submitting.value = false
  }
}

async function doPaste() {
  if (!detail.value?.id || !resumePaste.value.trim()) return
  pasting.value = true
  try {
    const res = await articleApi.paste(detail.value.id, resumePaste.value.trim())
    message.success('已提交粘贴，继续处理')
    detail.value = res.data
    await loadList()
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '粘贴失败')
  } finally {
    pasting.value = false
  }
}

async function doPause() {
  if (!detail.value?.id) return
  try {
    const res = await articleApi.pauseTask(detail.value.id)
    detail.value = res.data
    await loadList()
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '暂停失败')
  }
}

async function doCancel() {
  if (!detail.value?.id) return
  try {
    const res = await articleApi.cancelTask(detail.value.id)
    detail.value = res.data
    await loadList()
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '取消失败')
  }
}

async function doRetry() {
  if (!detail.value?.id) return
  const { provider, model } = parseLlmKey(selectedLlmKey.value)
  try {
    const res = await articleApi.retryTask(detail.value.id, {
      llmProvider: provider,
      llmModel: model
    })
    detail.value = res.data
    message.success('已重新排队')
    await loadList()
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '重试失败')
  }
}

/** 将摘要 + 要点 + 来源写入知识库（Markdown） */
async function saveToKnowledgeBase() {
  if (!detail.value || detail.value.status !== 'SUCCESS') return
  savingToKb.value = true
  try {
    const title = (detail.value.title || '文章提取').slice(0, 200)
    const parts: string[] = [`# ${title}`, '']
    if (detail.value.sourceUrl) {
      parts.push(`> 来源：${detail.value.sourceUrl}`, '')
    }
    if (coreSummary.value) {
      parts.push('## 摘要', '', coreSummary.value, '')
    }
    if (keyPoints.value.length) {
      parts.push('## 要点', '')
      keyPoints.value.forEach((kp, i) => {
        parts.push(`${i + 1}. ${kp.point || ''}`)
      })
      parts.push('')
    }
    if (timeline.value.length) {
      parts.push('## 时间线', '')
      timeline.value.forEach((ev) => {
        parts.push(`- **${ev.time || '—'}** ${ev.event || ''}`)
      })
      parts.push('')
    }
    if (rewriteVariants.value.length) {
      parts.push('## 二创', '')
      rewriteVariants.value.forEach((v, i) => {
        parts.push(`### ${v.label || v.id || `形态 ${i + 1}`}`, '')
        if (v.hook) parts.push(`Hook：${v.hook}`, '')
        if (v.content) parts.push(v.content, '')
        if (v.cta) parts.push(`CTA：${v.cta}`, '')
      })
    }
    if (mindMap.value) {
      parts.push('## 思维导图', '', mindMap.value, '')
    }
    const content = parts.join('\n').trim()
    // 标签：尽量复用「文章提取」
    let tagIds: string[] = []
    try {
      const tagsRes = await kbApi.listTags()
      const existing = (tagsRes.data || []).find((t) => t.name === '文章提取')
      if (existing) {
        tagIds = [existing.id]
      } else {
        const created = await kbApi.createTag('文章提取')
        if (created.data?.id) tagIds = [created.data.id]
      }
    } catch {
      /* 标签失败不阻塞入库 */
    }
    await kbApi.createNote({
      title,
      content,
      contentFormat: 'markdown',
      tagIds: tagIds.length ? tagIds : undefined
    })
    message.success('已存入知识库')
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '存入知识库失败')
  } finally {
    savingToKb.value = false
  }
}

function doDelete() {
  if (!detail.value?.id) return
  const id = detail.value.id
  Modal.confirm({
    title: '删除任务？',
    content: '将删除任务记录与相关文件，不可恢复。',
    okType: 'danger',
    async onOk() {
      try {
        await articleApi.deleteTask(id)
        message.success('已删除')
        if (selectedId.value === id) {
          selectedId.value = undefined
          detail.value = null
        }
        await loadList()
      } catch (e: any) {
        message.error(e?.response?.data?.message || e?.message || '删除失败')
      }
    }
  })
}

function copyText(text: string) {
  if (!text) {
    message.warning('无可复制内容')
    return
  }
  navigator.clipboard.writeText(text).then(
    () => message.success('已复制'),
    () => message.error('复制失败')
  )
}

function mergeTaskFromSse(data: Record<string, unknown>) {
  const id = String(data.id || data.taskId || '')
  if (!id) return
  const idx = tasks.value.findIndex((t) => t.id === id)
  const light: ArticleTaskItem = {
    id,
    title: data.title as string | undefined,
    sourceUrl: data.sourceUrl as string | undefined,
    platform: data.platform as string | undefined,
    supportLevel: data.supportLevel as string | undefined,
    status: String(data.status || 'PENDING'),
    currentStep: data.currentStep as string | undefined,
    progress: Number(data.progress ?? 0),
    errorCode: data.errorCode as string | undefined,
    errorMessage: data.errorMessage as string | undefined,
    degraded: Boolean(data.degraded),
    createdAt: data.createdAt as string | undefined,
    updatedAt: data.updatedAt as string | undefined
  }
  if (idx >= 0) {
    tasks.value[idx] = { ...tasks.value[idx], ...light }
  } else {
    tasks.value = [light, ...tasks.value]
  }
  if (selectedId.value === id) {
    // 轻量更新 + 终态时拉详情
    if (detail.value) {
      detail.value = { ...detail.value, ...light }
    }
    const terminal = ['SUCCESS', 'FAILED', 'CANCELLED', 'PAUSED', 'NEEDS_PASTE']
    if (terminal.includes(light.status)) {
      void selectTask(id)
    }
  }
}

function connectSse() {
  sseClose?.()
  liveChannel.value = 'connecting'
  const { close } = connectArticleTaskEvents({
    onOpen: () => {
      liveChannel.value = 'live'
    },
    onError: () => {
      liveChannel.value = 'offline'
    },
    onEvent: (ev) => {
      if (ev.type === 'task.created' || ev.type === 'task.status') {
        if (ev.data) mergeTaskFromSse(ev.data)
      } else if (ev.type === 'task.deleted') {
        const id = String(ev.data?.id || ev.taskId || '')
        tasks.value = tasks.value.filter((t) => t.id !== id)
        if (selectedId.value === id) {
          selectedId.value = undefined
          detail.value = null
        }
      }
    }
  })
  sseClose = close
}

onMounted(async () => {
  await Promise.all([loadModels(), loadDisclaimer(), loadList()])
  connectSse()
})

onUnmounted(() => {
  sseClose?.()
})

watch(selectedId, (id) => {
  if (id && (!detail.value || detail.value.id !== id)) {
    void selectTask(id)
  }
})
</script>

<style lang="scss" scoped>
.article-page {
  max-width: 1280px;
  margin: 0 auto;
}

.layout {
  display: grid;
  grid-template-columns: minmax(320px, 400px) 1fr;
  gap: 16px;
  align-items: start;
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .left-panel {
    position: static;
    max-height: none;
  }
}

@media (max-width: 768px) {
  .article-page {
    max-width: 100%;
  }

  .page-card {
    padding: 14px !important;
  }
}

.page-card {
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 14px;
  padding: 18px 18px 20px;
  box-shadow: 0 1px 2px color-mix(in srgb, var(--text-primary) 4%, transparent);
}

.left-panel {
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 100px);
  overflow: auto;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  color: var(--text-primary);
}

.page-subtitle {
  margin: 6px 0 16px;
  font-size: 13px;
  color: var(--text-muted);
}

.live-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border-color);
  color: var(--text-secondary);

  .live-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--text-muted);
  }

  &.live .live-dot {
    background: #22c55e;
    box-shadow: 0 0 0 3px color-mix(in srgb, #22c55e 25%, transparent);
  }
  &.connecting .live-dot {
    background: #f59e0b;
  }
  &.offline .live-dot {
    background: #ef4444;
  }
}

.field {
  margin-bottom: 14px;
}

.field-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.platform-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.platform-msg {
  font-size: 12px;
  color: var(--text-muted);
}

.paste-collapse {
  margin-bottom: 12px;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  overflow: hidden;
}

.toggles {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.disclaimer {
  margin-bottom: 12px;
  font-size: 12px;
}

.submit-btn {
  margin-bottom: 18px;
}

.history-block {
  border-top: 1px solid var(--border-color);
  padding-top: 12px;
}

.history-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 12px;
  color: var(--text-muted);
  padding: 12px 0;
}

.task-item {
  padding: 10px 10px;
  border-radius: 10px;
  border: 1px solid transparent;
  cursor: pointer;
  margin-bottom: 6px;
  transition: background 0.15s, border-color 0.15s;

  &:hover {
    background: var(--surface-2);
  }
  &.active {
    border-color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 8%, transparent);
  }
}

.task-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}

.prog {
  color: var(--text-muted);
}

.task-step {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.right-panel {
  min-height: 520px;
}

.empty-main {
  padding: 48px 16px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.detail-title {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 650;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.detail-step {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: flex-start;
}

.muted {
  color: var(--text-muted);
  font-size: 13px;
}

.needs-paste-panel {
  margin: 12px 0 16px;
  padding: 12px;
  border-radius: 12px;
  border: 1px dashed color-mix(in srgb, #f59e0b 50%, var(--border-color));
  background: color-mix(in srgb, #f59e0b 8%, transparent);
}

.result-tabs {
  margin-top: 8px;
}

.tab-toolbar {
  margin-bottom: 8px;
}

.body-text {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 14px;
  color: var(--text-primary);
}

.result-disclaimer {
  margin-top: 16px;
}

.kp-list,
.tl-list {
  padding-left: 18px;
  margin: 0;
  line-height: 1.75;
  font-size: 14px;
}

.tl-list li {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px;
  margin-bottom: 8px;
}

.entities {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ent-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.rewrite-card {
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--surface-2);
}

.rewrite-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.rewrite-body {
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.65;
  margin: 0;
}

.hook,
.cta {
  font-size: 12px;
  color: var(--text-muted);
  margin: 4px 0;
}

.mind-md {
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  background: var(--surface-2);
  padding: 12px;
  border-radius: 8px;
}

.source-line {
  margin-top: 16px;
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
}
</style>
