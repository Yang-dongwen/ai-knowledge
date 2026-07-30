<template>
  <div class="share-page" :class="{ dark: isDark }">
    <div class="ambient" aria-hidden="true">
      <span class="orb a" />
      <span class="orb b" />
    </div>

    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">KB</span>
        <span class="brand-text">知识库 · 分享阅读</span>
      </div>
      <button type="button" class="theme-btn" @click="toggleTheme" :title="isDark ? '浅色' : '深色'">
        {{ isDark ? '浅色' : '深色' }}
      </button>
    </header>

    <main class="main">
      <div v-if="loading" class="state-card">
        <div class="spinner" />
        <p>加载文档…</p>
      </div>

      <div v-else-if="error" class="state-card error">
        <div class="error-icon">∅</div>
        <h1>无法打开</h1>
        <p>{{ error }}</p>
        <p class="sub">链接可能已关闭，或文档已被删除。</p>
      </div>

      <article v-else-if="note" class="article">
        <div class="article-meta">
          <span v-if="note.tags?.length" class="tags">
            <span v-for="t in note.tags" :key="t" class="tag">#{{ t }}</span>
          </span>
          <span class="meta-line">
            <span v-if="note.authorName">{{ note.authorName }}</span>
            <span v-if="note.authorName && displayDate" class="dot">·</span>
            <time v-if="displayDate">{{ displayDate }}</time>
          </span>
        </div>

        <h1 class="article-title">{{ note.title || '未命名笔记' }}</h1>
        <div class="title-rule" />

        <div
          class="article-body"
          :class="note.contentFormat === 'markdown' ? 'is-md' : 'is-html'"
          v-html="bodyHtml"
        />

        <footer class="article-foot">
          <span>只读分享</span>
          <span class="sep">·</span>
          <span>由知识库生成</span>
        </footer>
      </article>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { fetchPublicNote, type KbPublicNote } from '@/api/kb.api'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const note = ref<KbPublicNote | null>(null)
const bodyHtml = ref('')
const shareToken = ref('')
const isDark = ref(
  window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
)

const displayDate = computed(() => {
  const t = note.value?.updatedAt || note.value?.publishedAt
  if (!t) return ''
  return dayjs(t).format('YYYY 年 M 月 D 日')
})

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('share-dark', isDark.value)
}

/**
 * 确保正文/渲染后 HTML 中的媒体指向公开路径（后端已改写时幂等）。
 * 兼容：相对路径、localhost 绝对路径、带 access_token 的旧链接。
 */
function ensurePublicMediaUrls(html: string, token: string): string {
  if (!html || !token) return html
  const pub = `/api/v1/kb/public/s/${token}/files/$1/content`
  let s = html
  s = s.replace(
    /https?:\/\/[^/"'\s)]+\/api\/v1\/kb\/files\/(\d+)\/content(?:\?[^"'\s)]*)?/gi,
    pub
  )
  s = s.replace(/\/api\/v1\/kb\/files\/(\d+)\/content(?:\?[^"'\s)]*)?/gi, pub)
  // 已是 public 路径但带多余 query 时去掉 token 参数
  s = s.replace(
    new RegExp(
      `(/api/v1/kb/public/s/${token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/files/\\d+/content)\\?[^"'\\s)]*`,
      'gi'
    ),
    '$1'
  )
  return s
}

async function buildMarkdownHtml(src: string, token: string) {
  const mod = await import('markdown-it')
  const multimd = await import('markdown-it-multimd-table')
  const MarkdownIt = mod.default
  const plugin = (multimd as any).default || multimd
  // html:true — 正文里可能含编辑器插入的 <img ... data-kb-md-img>，关闭会变成纯文本裂图
  const md = new MarkdownIt({
    html: true,
    linkify: true,
    breaks: true
  }).use(plugin, {
    multiline: true,
    rowspan: true,
    headerless: true
  })
  // 先改写 MD/HTML 源里的图片路径，再 render
  const rewritten = ensurePublicMediaUrls(src || '', token)
  return ensurePublicMediaUrls(md.render(rewritten), token)
}

onMounted(async () => {
  document.documentElement.classList.toggle('share-dark', isDark.value)
  const token = String(route.params.token || '')
  shareToken.value = token
  if (!token) {
    error.value = '无效的分享链接'
    loading.value = false
    return
  }
  try {
    const data = await fetchPublicNote(token)
    note.value = data
    document.title = `${data.title || '分享文档'} · 知识库`
    if (data.contentFormat === 'markdown') {
      bodyHtml.value = await buildMarkdownHtml(data.content || '', token)
    } else {
      bodyHtml.value = ensurePublicMediaUrls(data.content || '<p></p>', token)
    }
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped lang="scss">
.share-page {
  --bg: #f6f4ef;
  --paper: #fffcf7;
  --ink: #1c1917;
  --muted: #78716c;
  --line: #e7e5e4;
  --accent: #0f766e;
  --table-head: #f5f0e8;
  --table-border: #ddd6cb;
  --table-stripe: rgba(15, 118, 110, 0.04);
  --orb1: rgba(15, 118, 110, 0.12);
  --orb2: rgba(180, 83, 9, 0.08);
  min-height: 100vh;
  position: relative;
  color: var(--ink);
  background: var(--bg);
  font-family:
    'Segoe UI',
    'PingFang SC',
    'Hiragino Sans GB',
    'Microsoft YaHei',
    Georgia,
    'Times New Roman',
    serif;
}

.share-page.dark {
  --bg: #0c0f12;
  --paper: #141a1f;
  --ink: #e7e5e4;
  --muted: #a8a29e;
  --line: #292524;
  --accent: #2dd4bf;
  --table-head: #1c242c;
  --table-border: #3f4a55;
  --table-stripe: rgba(45, 212, 191, 0.06);
  --orb1: rgba(45, 212, 191, 0.08);
  --orb2: rgba(251, 191, 36, 0.05);
}

.ambient {
  pointer-events: none;
  position: fixed;
  inset: 0;
  overflow: hidden;
  z-index: 0;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(64px);
}

.orb.a {
  width: 420px;
  height: 420px;
  background: var(--orb1);
  top: -80px;
  right: -40px;
}

.orb.b {
  width: 360px;
  height: 360px;
  background: var(--orb2);
  bottom: 10%;
  left: -60px;
}

.topbar {
  position: relative;
  z-index: 2;
  max-width: min(1400px, 96vw);
  margin: 0 auto;
  padding: 20px 28px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 13px;
  font-family:
    system-ui,
    -apple-system,
    sans-serif;
}

.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font-weight: 800;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.02em;
}

.theme-btn {
  border: 1px solid var(--line);
  background: var(--paper);
  color: var(--muted);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
  font-family: system-ui, sans-serif;
}

.theme-btn:hover {
  color: var(--ink);
}

.main {
  position: relative;
  z-index: 1;
  /* 阅读区加宽，减少两侧留白 */
  max-width: min(1400px, 96vw);
  margin: 0 auto;
  padding: 28px 28px 80px;
}

.state-card {
  text-align: center;
  padding: 80px 24px;
  color: var(--muted);
  font-family: system-ui, sans-serif;
}

.state-card.error h1 {
  color: var(--ink);
  font-size: 22px;
  margin: 12px 0 8px;
  font-family: inherit;
}

.state-card .sub {
  font-size: 13px;
  margin-top: 8px;
}

.error-icon {
  font-size: 40px;
  opacity: 0.4;
}

.spinner {
  width: 28px;
  height: 28px;
  margin: 0 auto 12px;
  border: 2px solid var(--line);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.article {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 40px 40px 48px;
  box-shadow:
    0 1px 2px rgba(28, 25, 23, 0.04),
    0 24px 48px -24px rgba(28, 25, 23, 0.12);
}

@media (min-width: 1100px) {
  .article {
    padding: 48px 56px 56px;
  }
}

.article-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 18px;
  font-family: system-ui, sans-serif;
  font-size: 13px;
  color: var(--muted);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  padding: 2px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
}

.meta-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  opacity: 0.5;
}

.article-title {
  margin: 0;
  font-size: clamp(1.75rem, 4vw, 2.35rem);
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.025em;
  color: var(--ink);
}

.title-rule {
  margin: 22px 0 28px;
  height: 1px;
  background: linear-gradient(90deg, var(--accent), transparent 70%);
  opacity: 0.55;
}

.article-body {
  font-size: 1.06rem;
  line-height: 1.85;
  color: var(--ink);
  word-break: break-word;
}

.article-body :deep(h1),
.article-body :deep(h2),
.article-body :deep(h3) {
  font-weight: 700;
  line-height: 1.3;
  margin: 1.4em 0 0.55em;
  letter-spacing: -0.02em;
}

.article-body :deep(h1) {
  font-size: 1.55em;
}
.article-body :deep(h2) {
  font-size: 1.3em;
}
.article-body :deep(h3) {
  font-size: 1.12em;
}

.article-body :deep(p) {
  margin: 0.85em 0;
}

.article-body :deep(ul),
.article-body :deep(ol) {
  padding-left: 1.4em;
  margin: 0.75em 0;
}

.article-body :deep(li) {
  margin: 0.3em 0;
}

.article-body :deep(blockquote) {
  margin: 1em 0;
  padding: 0.2em 0 0.2em 1em;
  border-left: 3px solid var(--accent);
  color: var(--muted);
}

.article-body :deep(pre) {
  margin: 1em 0;
  padding: 14px 16px;
  border-radius: 12px;
  background: color-mix(in srgb, var(--ink) 6%, var(--paper));
  overflow-x: auto;
  font-size: 0.9em;
  line-height: 1.55;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.article-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
}

.article-body :deep(:not(pre) > code) {
  background: color-mix(in srgb, var(--ink) 7%, transparent);
  padding: 1px 6px;
  border-radius: 4px;
}

.article-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 12px;
  margin: 1em 0;
  box-shadow: 0 8px 24px -12px rgba(0, 0, 0, 0.25);
  display: block;
}

.article-body :deep(video) {
  max-width: 100%;
  border-radius: 12px;
  margin: 1em 0;
}

.article-body :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.article-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--line);
  margin: 2em 0;
}

/* —— Markdown 表格 —— */
.article-body :deep(.table-scroll),
.article-body {
  /* 宽表允许在文章卡片内横向滑 */
}

.article-body :deep(table) {
  width: 100%;
  min-width: 280px;
  border-collapse: separate;
  border-spacing: 0;
  margin: 1.25em 0 1.5em;
  font-size: 0.94em;
  line-height: 1.55;
  font-family:
    system-ui,
    -apple-system,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  border: 1px solid var(--table-border);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.03);
  display: table;
  table-layout: auto;
}

.article-body :deep(thead) {
  background: var(--table-head);
}

.article-body :deep(th) {
  font-weight: 650;
  text-align: left;
  padding: 12px 14px;
  border-bottom: 1px solid var(--table-border);
  color: var(--ink);
  white-space: nowrap;
  background: var(--table-head);
}

.article-body :deep(td) {
  padding: 11px 14px;
  border-bottom: 1px solid var(--table-border);
  vertical-align: top;
}

.article-body :deep(th + th),
.article-body :deep(td + td) {
  border-left: 1px solid var(--table-border);
}

.article-body :deep(tbody tr:last-child td) {
  border-bottom: none;
}

.article-body :deep(tbody tr:nth-child(even)) {
  background: var(--table-stripe);
}

.article-body :deep(tbody tr:hover) {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.article-foot {
  margin-top: 48px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  color: var(--muted);
  font-family: system-ui, sans-serif;
  display: flex;
  gap: 6px;
  justify-content: center;
}

.sep {
  opacity: 0.5;
}

@media (max-width: 640px) {
  .article {
    padding: 28px 20px 36px;
    border-radius: 16px;
  }

  .main {
    padding: 20px 14px 64px;
  }

  .article-body :deep(th),
  .article-body :deep(td) {
    padding: 9px 10px;
    font-size: 0.9em;
  }
}
</style>

<style>
html.share-dark body {
  background: #0c0f12;
}
</style>
