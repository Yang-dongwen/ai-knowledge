<template>
  <div class="news-page">
    <header class="news-hero">
      <div class="news-kicker">Horizon · 每日速递</div>
      <h1>今日资讯</h1>
      <p class="news-lead">
        全站一份公共时讯。线上读数据库；Halo 由线上入库后更新同一篇。
      </p>
      <div v-if="auth.isSuperAdmin" class="news-actions">
        <a-button :loading="refreshing" @click="triggerRefresh">重新生成</a-button>
        <a-button
          type="primary"
          :disabled="!latest"
          :loading="publishing"
          @click="triggerPublish"
        >
          {{ latest?.haloPermalink ? '更新到博客' : '发布到博客' }}
        </a-button>
      </div>
    </header>

    <div v-if="loading" class="news-loading">加载中…</div>

    <EmptyState
      v-else-if="!latest"
      scene="detail"
      :title="generating ? '正在生成今日资讯' : '还没有今日资讯'"
      :description="generating
        ? '启动时未发现当天稿，正在跑 Horizon（通常几分钟）。完成后会自动出现。'
        : '今天还没有稿。重启后端会自动生成；超管也可点「立即刷新」。'"
    />

    <div v-else class="news-layout">
      <article class="news-main">
        <div class="news-meta">
          <time v-if="latest.date">{{ latest.date }}</time>
          <span v-if="latest.updatedAt">更新于 {{ formatUpdated(latest.updatedAt) }}</span>
          <a v-if="latest.haloPermalink" :href="latest.haloPermalink" target="_blank" rel="noopener">
            在博客阅读
          </a>
          <span v-else-if="auth.isSuperAdmin" class="news-unpublished">尚未发到博客</span>
        </div>
        <div class="news-body" v-html="html" />
      </article>

      <aside v-if="recent.length" class="news-aside">
        <div class="aside-title">近日</div>
        <button
          v-for="item in recent"
          :key="item.date || item.title"
          type="button"
          class="aside-item"
          :class="{ current: item.date === latest?.date }"
          @click="openRecent(item)"
        >
          <span class="aside-date">{{ item.date || item.title }}</span>
          <span class="aside-snip">{{ decodeMarkdownQuoteEntities(item.snippet || item.title || '') }}</span>
        </button>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import MarkdownIt from 'markdown-it'
import EmptyState from '@/components/EmptyState.vue'
import { horizonApi, type HorizonDigestBrief, type HorizonDigestView } from '@/api/horizon.api'
import { decodeMarkdownQuoteEntities, sanitizeHtml } from '@/utils/sanitizeHtml'
import { useAuthStore } from '@/stores/auth.store'
import dayjs from 'dayjs'

const md = new MarkdownIt({ html: true, linkify: true, breaks: true })

const auth = useAuthStore()
const loading = ref(true)
const refreshing = ref(false)
const publishing = ref(false)
const generating = ref(false)
const latest = ref<HorizonDigestView | null>(null)
const recent = ref<HorizonDigestBrief[]>([])
const html = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

function stripLeadHeading(src: string, title?: string) {
  let s = src || ''
  s = s.replace(/^#\s+Horizon[^\n]*\n+/, '')
  if (title) {
    const t = title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    s = s.replace(new RegExp('^#+\\s+' + t + '\\s*\\n+', 'i'), '')
  }
  return s
}

function render(src: string) {
  html.value = sanitizeHtml(md.render(stripLeadHeading(decodeMarkdownQuoteEntities(src), latest.value?.title)))
}

async function load() {
  loading.value = true
  try {
    const [a, b, s] = await Promise.all([
      horizonApi.latest('zh'),
      horizonApi.recent('zh', 14),
      horizonApi.refreshStatus().catch(() => ({ data: { running: false, enabled: false } }))
    ])
    latest.value = a.data || null
    recent.value = b.data || []
    generating.value = !latest.value && !!(s.data && s.data.running)
    render(latest.value?.markdown || '')
    if (!latest.value && s.data?.running) {
      startPoll()
    } else {
      stopPoll()
    }
  } catch {
    latest.value = null
    recent.value = []
  } finally {
    loading.value = false
  }
}

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    void load()
  }, 8000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function openRecent(item: HorizonDigestBrief) {
  const date = item.date
  if (!date || date === latest.value?.date) return
  try {
    const { data } = await horizonApi.latest('zh', date)
    if (!data) {
      message.warning('没有这一天的正文')
      return
    }
    latest.value = data
    render(data.markdown || '')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '切换失败')
  }
}

function formatUpdated(raw: string) {
  const d = dayjs(raw)
  if (!d.isValid()) return raw
  // 库里的 LocalDateTime 不带时区；服务器已按上海时间写入。
  return d.format('MM-DD HH:mm')
}

async function triggerRefresh() {
  refreshing.value = true
  try {
    await horizonApi.refresh()
    message.success('已刷新今日资讯')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '刷新失败')
  } finally {
    refreshing.value = false
  }
}

async function triggerPublish() {
  if (!latest.value) return
  publishing.value = true
  try {
    const { data } = await horizonApi.publish(latest.value.date)
    message.success(data?.lastPermalink ? '已发布到博客' : '已提交发布')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

onMounted(load)
onUnmounted(stopPoll)
</script>

<style lang="scss" scoped>
.news-page {
  max-width: 1040px;
  margin: 0 auto;
  padding: 8px 0 40px;
}

.news-hero {
  margin-bottom: 20px;
}

.news-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--text-secondary);
  text-transform: uppercase;
}

.news-hero h1 {
  margin: 6px 0 8px;
  font-size: 28px;
  letter-spacing: -0.03em;
  color: var(--text-primary);
}

.news-lead {
  margin: 0;
  max-width: 520px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.news-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.news-unpublished {
  color: var(--warning-text, #b45309);
}

.news-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--text-tertiary);
}

.news-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 28px;

  @media (max-width: 800px) {
    grid-template-columns: 1fr;
  }
}

.news-main {
  padding: 22px 24px 28px;
  border: 1px solid var(--border-color);
  border-radius: 14px;
  background: var(--surface-1);
}

.news-meta {
  display: flex;
  gap: 14px;
  font-size: 13px;
  color: var(--text-tertiary);
  margin-bottom: 8px;

  a {
    color: var(--primary-color);
  }
}

.news-body {
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.75;
  word-break: break-word;

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 1.35em 0 0.5em;
    line-height: 1.35;
    font-weight: 700;
    color: var(--text-primary);
  }

  :deep(h1) {
    font-size: 1.35em;
  }

  :deep(h2) {
    font-size: 1.2em;
  }

  :deep(h3) {
    font-size: 1.08em;
  }

  :deep(p) {
    margin: 0.65em 0;
  }

  :deep(blockquote) {
    margin: 0.8em 0;
    padding: 0.2em 0 0.2em 12px;
    border-left: 3px solid var(--border-strong);
    color: var(--text-secondary);
  }

  :deep(ul),
  :deep(ol) {
    margin: 0.5em 0 0.8em;
    padding-left: 1.4em;
  }

  :deep(li) {
    margin: 0.25em 0;
  }

  :deep(hr) {
    border: 0;
    border-top: 1px solid var(--border-color);
    margin: 1.2em 0;
  }

  :deep(a) {
    color: var(--primary-color);
  }

  :deep(strong) {
    font-weight: 700;
  }

  :deep(details) {
    margin: 0.6em 0 1em;
    padding: 8px 12px;
    border-radius: 8px;
    background: var(--surface-3);
  }

  :deep(summary) {
    cursor: pointer;
    font-weight: 600;
  }

  :deep(pre) {
    overflow: auto;
    padding: 12px;
    border-radius: 8px;
    background: var(--surface-3);
  }
}

.news-aside {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.aside-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.aside-item {
  text-align: left;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: var(--surface-1);
  cursor: pointer;

  &:hover,
  &.current {
    border-color: var(--border-color);
    background: var(--surface-2);
  }
}

.aside-date {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.aside-snip {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
