<template>
  <div class="page no-tab">
    <header class="nav">
      <button class="back" type="button" @click="$router.back()">← 返回</button>
      <div class="nav-actions" v-if="note">
        <template v-if="note.deleted">
          <button class="btn sm" type="button" @click="onRestore">恢复</button>
          <button class="btn btn-danger sm" type="button" @click="onPermanent">永久删除</button>
        </template>
        <template v-else>
          <button class="btn sm" type="button" @click="shareOpen = !shareOpen">分享</button>
          <button class="btn sm" type="button" @click="onEdit">编辑</button>
          <button class="btn btn-danger sm" type="button" @click="onDelete">删除</button>
        </template>
      </div>
    </header>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!note" class="empty">笔记不存在</div>
    <template v-else>
      <div v-if="shareOpen && !note.deleted" class="card share-panel">
        <div class="share-head">
          <span>公开分享（只读）</span>
          <label class="switch">
            <input type="checkbox" :checked="shareEnabled" :disabled="shareLoading" @change="onShareToggle" />
            <span>{{ shareEnabled ? '已开启' : '关闭' }}</span>
          </label>
        </div>
        <p class="muted tip">开启后生成链接，任何人无需登录即可阅读。</p>
        <template v-if="shareEnabled">
          <div class="share-url">{{ shareUrl || '（已开启）' }}</div>
          <div class="share-actions">
            <button class="btn sm btn-primary" type="button" @click="copyShare">复制链接</button>
            <button class="btn sm" type="button" @click="openShare">预览</button>
            <button class="btn sm" type="button" :disabled="shareLoading" @click="onRotateShare">重置链接</button>
          </div>
        </template>
      </div>

      <div class="card body">
        <div class="meta muted">
          <span class="fmt">{{ note.contentFormat === 'markdown' ? 'Markdown' : '富文本' }}</span>
          <span v-if="note.categoryName"> · {{ note.categoryName }}</span>
          · {{ formatTime(note.updatedAt) }}
          <span v-if="note.deleted"> · 回收站</span>
        </div>
        <div v-if="note.tags?.length" class="tags">
          <span v-for="t in note.tags" :key="t.id" class="tag">#{{ t.name }}</span>
        </div>
        <div class="doc-content" v-html="contentHtml" />
      </div>

      <div class="card files" v-if="files.length">
        <div class="files-title">附件 ({{ files.length }})</div>
        <button
          v-for="f in files"
          :key="f.id"
          type="button"
          class="file-row"
          @click="openFile(f)"
        >
          <span class="kind">{{ kindLabel(f.kind) }}</span>
          <span class="name">{{ f.originalName }}</span>
          <span class="size muted">{{ formatSize(f.sizeBytes) }}</span>
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, clearSession, formatTime, kbMediaUrl } from '../api'
import { renderNoteContent } from '../markdown'

const route = useRoute()
const router = useRouter()
const note = ref(null)
const files = ref([])
const loading = ref(true)
const shareOpen = ref(false)
const shareLoading = ref(false)
const shareStatus = ref(null)

const contentHtml = computed(() =>
  renderNoteContent(note.value?.content || '', note.value?.contentFormat || 'markdown')
)

const shareEnabled = computed(() => !!shareStatus.value?.enabled)
const shareUrl = computed(() => {
  const path = shareStatus.value?.sharePath
  if (!path) return ''
  return `${window.location.origin}${path}`
})

function kindLabel(k) {
  return { image: '图', video: '视', pdf: 'PDF', office: 'Office', audio: '音' }[k] || '文件'
}

function formatSize(n) {
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`
  return `${(n / 1024 / 1024).toFixed(1)}MB`
}

async function loadShare() {
  try {
    shareStatus.value = await api.getShareStatus(route.params.id)
  } catch {
    shareStatus.value = null
  }
}

async function load() {
  loading.value = true
  try {
    note.value = await api.getNote(route.params.id)
    try {
      files.value = (await api.listFiles(route.params.id)) || []
    } catch {
      files.value = []
    }
    if (!note.value?.deleted) await loadShare()
  } catch (e) {
    note.value = null
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
    } else {
      alert(e.message || '加载失败')
    }
  } finally {
    loading.value = false
  }
}

async function onShareToggle(e) {
  const on = !!e.target.checked
  shareLoading.value = true
  try {
    shareStatus.value = on
      ? await api.enableShare(route.params.id)
      : await api.disableShare(route.params.id)
    if (on && shareUrl.value) await copyShare()
  } catch (err) {
    alert(err.message || '操作失败')
    await loadShare()
  } finally {
    shareLoading.value = false
  }
}

async function copyShare() {
  const url = shareUrl.value
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    alert('链接已复制')
  } catch {
    prompt('复制链接', url)
  }
}

function openShare() {
  const path = shareStatus.value?.sharePath
  if (!path) {
    alert('请先开启分享')
    return
  }
  const token = shareStatus.value?.shareToken
  if (token) {
    window.open(`/s/${encodeURIComponent(token)}`, '_blank')
    return
  }
  window.open(path, '_blank')
}

async function onRotateShare() {
  if (!confirm('重置后旧链接立即失效，确定？')) return
  shareLoading.value = true
  try {
    shareStatus.value = await api.rotateShare(route.params.id)
    await copyShare()
  } catch (err) {
    alert(err.message || '重置失败')
  } finally {
    shareLoading.value = false
  }
}

function onEdit() {
  router.push({ path: '/edit', query: { id: route.params.id } })
}

async function onDelete() {
  if (!confirm('移入回收站？')) return
  try {
    await api.deleteNote(route.params.id)
    router.replace('/notes')
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
    } else alert(e.message || '删除失败')
  }
}

async function onRestore() {
  try {
    await api.restoreNote(route.params.id)
    alert('已恢复')
    await load()
  } catch (e) {
    alert(e.message || '恢复失败')
  }
}

async function onPermanent() {
  if (!confirm('永久删除？含附件与存储对象，不可恢复。')) return
  try {
    await api.permanentDeleteNote(route.params.id)
    router.replace('/notes')
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

function openFile(f) {
  const url = kbMediaUrl(f.contentPath)
  if (f.kind === 'image' || f.kind === 'pdf' || f.kind === 'video') {
    window.open(url, '_blank')
  } else {
    window.open(url + (url.includes('?') ? '&' : '?') + 'download=true', '_blank')
  }
}

onMounted(load)
</script>

<style scoped>
.nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 8px;
}
.back {
  border: none;
  background: transparent;
  color: var(--primary);
  font-weight: 600;
  padding: 8px 0;
  cursor: pointer;
}
.nav-actions {
  display: flex;
  gap: 6px;
}
.sm {
  padding: 8px 10px;
  font-size: 13px;
  border-radius: 10px;
}
.body {
  padding: 16px;
  margin-bottom: 12px;
}
.meta {
  font-size: 12px;
  margin-bottom: 8px;
}
.fmt {
  font-weight: 600;
}
.tags {
  margin-bottom: 8px;
}
/* 正文样式见全局 .doc-content */
.files {
  padding: 12px 14px;
}
.files-title {
  font-weight: 650;
  margin-bottom: 8px;
  font-size: 13px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  background: transparent;
  padding: 8px 0;
  border-top: 1px solid #f1f5f9;
  text-align: left;
  cursor: pointer;
  color: inherit;
}
.kind {
  font-size: 11px;
  background: #e2e8f0;
  padding: 1px 6px;
  border-radius: 4px;
}
.name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.size {
  font-size: 11px;
}
.btn-danger {
  background: #ef4444;
  color: #fff;
  border: none;
}
.share-panel {
  padding: 14px;
  margin-bottom: 12px;
}
.share-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 650;
  margin-bottom: 6px;
}
.switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
}
.tip {
  font-size: 12px;
  margin: 0 0 8px;
}
.share-url {
  font-size: 12px;
  word-break: break-all;
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.share-actions {
  display: flex;
  gap: 8px;
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
</style>
