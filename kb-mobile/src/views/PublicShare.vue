<template>
  <div class="share-page">
    <header class="top">
      <span class="brand">知识库 · 分享阅读</span>
    </header>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error">
      <h1>无法打开</h1>
      <p>{{ error }}</p>
      <p class="muted">链接可能已关闭，或文档已被删除。</p>
    </div>
    <article v-else-if="note" class="card article">
      <div class="meta muted">
        <span v-if="note.authorName">{{ note.authorName }}</span>
        <span v-if="note.authorName && dateText"> · </span>
        <span v-if="dateText">{{ dateText }}</span>
        <span> · 只读分享</span>
      </div>
      <div v-if="note.tags?.length" class="tags">
        <span v-for="t in note.tags" :key="t" class="tag">#{{ t }}</span>
      </div>
      <h1 class="title">{{ note.title || '未命名笔记' }}</h1>
      <div class="rule" />
      <div class="doc-content" v-html="bodyHtml" />
      <footer class="foot muted">由知识库生成 · 仅供阅读</footer>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { renderNoteContent } from '../markdown'
import { sanitizeHtml } from '../utils/sanitizeHtml'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const note = ref(null)
const bodyHtml = ref('')

const dateText = computed(() => {
  const t = note.value?.updatedAt || note.value?.publishedAt
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
})

/** 与 PC 一致：兜底改写私有媒体为公开路径（后端已改写时幂等） */
function ensurePublicMediaUrls(html, token) {
  if (!html || !token) return html || ''
  const pub = `/api/v1/kb/public/s/${token}/files/$1/content`
  let s = html
  s = s.replace(
    /https?:\/\/[^/"'\s)]+\/api\/v1\/kb\/files\/(\d+)\/content(?:\?[^"'\s)]*)?/gi,
    pub
  )
  s = s.replace(/\/api\/v1\/kb\/files\/(\d+)\/content(?:\?[^"'\s)]*)?/gi, pub)
  return s
}

onMounted(async () => {
  const token = route.params.token
  if (!token) {
    error.value = '无效链接'
    loading.value = false
    return
  }
  try {
    const data = await api.fetchPublicNote(token)
    note.value = data
    // 与后端/PC 对齐：未声明时按 markdown 处理（HTML 笔记会显式 contentFormat=html）
    const fmt = data.contentFormat || 'markdown'
    const raw = renderNoteContent(data.content || '', fmt)
    bodyHtml.value = sanitizeHtml(ensurePublicMediaUrls(raw, token))
  } catch (e) {
    error.value = e.message || '分享不存在或已关闭'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  padding: 16px 14px 40px;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%);
}
.top {
  margin-bottom: 14px;
}
.brand {
  font-weight: 700;
  font-size: 14px;
  color: #334155;
}
.article {
  padding: 18px 16px 24px;
}
.meta {
  font-size: 12px;
  margin-bottom: 8px;
}
.tags {
  margin-bottom: 8px;
}
.tag {
  display: inline-block;
  background: #e0e7ff;
  color: #3730a3;
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 12px;
  margin-right: 4px;
}
.title {
  margin: 0;
  font-size: 1.55em;
  font-weight: 700;
  line-height: 1.3;
}
.rule {
  border-top: 1px solid var(--border);
  margin: 12px 0 16px;
}
/* 正文样式见全局 .doc-content */
.foot {
  margin-top: 28px;
  font-size: 12px;
  text-align: center;
}
.empty {
  text-align: center;
  padding: 48px 16px;
  color: #64748b;
}
.empty.error h1 {
  font-size: 18px;
  color: #0f172a;
}
</style>
