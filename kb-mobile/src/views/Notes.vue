<template>
  <div class="page">
    <header class="top">
      <h1>{{ trashMode ? '回收站' : '笔记' }}</h1>
      <div class="top-actions">
        <button v-if="!trashMode" class="btn btn-primary sm" type="button" @click="goCreate">新建</button>
        <button
          v-else
          class="btn btn-danger sm"
          type="button"
          :disabled="!total || emptying"
          @click="onEmptyTrash"
        >
          清空
        </button>
      </div>
    </header>

    <div class="search">
      <input
        v-model="keyword"
        :placeholder="trashMode ? '搜索回收站…' : '搜索标题/摘要'"
        @keyup.enter="reload"
      />
      <button class="btn btn-primary sm" type="button" @click="reload">搜索</button>
    </div>

    <div v-if="!trashMode" class="tool-links">
      <button type="button" class="link" @click="$router.push('/folders')">文件夹</button>
      <button type="button" class="link" @click="$router.push('/tags')">标签</button>
    </div>

    <div class="filter-row">
      <button type="button" class="chip" :class="{ on: isAll }" @click="clearFilters">全部</button>
      <button
        v-if="!trashMode"
        type="button"
        class="chip"
        :class="{ on: filterUncategorized }"
        @click="filterUncat"
      >
        未分类
      </button>
      <button
        v-for="c in flatCats"
        :key="c.id"
        type="button"
        class="chip"
        :class="{ on: !trashMode && filterCategoryId === c.id }"
        @click="filterByCat(c.id)"
      >
        {{ c.label }}
      </button>
      <button
        v-for="t in tags"
        :key="'t' + t.id"
        type="button"
        class="chip tag-chip"
        :class="{ on: !trashMode && filterTagId === t.id }"
        @click="filterByTag(t.id)"
      >
        #{{ t.name }}
      </button>
      <button type="button" class="chip trash" :class="{ on: trashMode }" @click="toggleTrash">
        回收站{{ trashCount ? ` ${trashCount}` : '' }}
      </button>
    </div>

    <p class="meta muted">{{ filterLabel }} · {{ total }} 条</p>

    <div v-if="loadError" class="empty error-box">
      <p>{{ loadError }}</p>
      <button class="btn btn-primary" type="button" @click="reload">重试</button>
    </div>
    <div v-else-if="loading && !notes.length" class="empty">加载中…</div>
    <div v-else-if="!notes.length" class="empty">
      <p>{{ trashMode ? '回收站为空' : '还没有笔记' }}</p>
      <button v-if="!trashMode" class="btn btn-primary" type="button" @click="goCreate">写一条</button>
    </div>
    <div v-else class="list">
      <article
        v-for="n in notes"
        :key="n.id"
        class="card note"
        :class="{ deleted: n.deleted || trashMode, open: openId === n.id }"
        @click="goDetail(n.id)"
        @touchstart.passive="onTouchStart($event, n.id)"
        @touchend.passive="onTouchEnd($event, n.id)"
        @contextmenu.prevent="onLongPress(n)"
      >
        <div class="swipe-hint" v-if="!trashMode && openId === n.id">
          <button type="button" class="act pin" @click.stop="togglePin(n)">
            {{ n.pinned ? '取消置顶' : '置顶' }}
          </button>
          <button type="button" class="act del" @click.stop="softDelete(n.id)">删除</button>
        </div>
        <div class="note-body">
          <div class="title">
            <span v-if="n.pinned">📌 </span>{{ n.title || '未命名笔记' }}
            <span class="fmt" :class="n.contentFormat === 'markdown' ? 'md' : 'html'">
              {{ n.contentFormat === 'markdown' ? 'MD' : '富文本' }}
            </span>
          </div>
          <div class="snippet muted">{{ n.snippet || '暂无摘要' }}</div>
          <div class="foot">
            <span v-if="n.categoryName" class="cat">{{ n.categoryName }}</span>
            <span v-for="t in n.tags || []" :key="t.id" class="tag">#{{ t.name }}</span>
            <span class="time muted">{{ formatTime(n.updatedAt) }}</span>
          </div>
        </div>
      </article>
    </div>

    <button
      v-if="hasMore"
      class="btn btn-ghost btn-block more"
      type="button"
      :disabled="loadingMore"
      @click="loadMore"
    >
      {{ loadingMore ? '加载中…' : '加载更多' }}
    </button>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, clearSession, formatTime } from '../api'

const router = useRouter()
const keyword = ref('')
const notes = ref([])
const page = ref(0)
const size = 20
const total = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const loadError = ref('')
const trashMode = ref(false)
const trashCount = ref(0)
const emptying = ref(false)
const categories = ref([])
const tags = ref([])
const filterCategoryId = ref('')
const filterTagId = ref('')
const filterUncategorized = ref(false)
const openId = ref('')
let touchX = 0

const flatCats = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes || []) {
      out.push({ id: String(n.id), label: `${'··'.repeat(depth)}${n.name}` })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

const isAll = computed(
  () => !trashMode.value && !filterCategoryId.value && !filterTagId.value && !filterUncategorized.value
)

const filterLabel = computed(() => {
  if (trashMode.value) return '回收站'
  if (filterTagId.value) {
    const t = tags.value.find((x) => x.id === filterTagId.value)
    return t ? `#${t.name}` : '标签'
  }
  if (filterUncategorized.value) return '未分类'
  if (filterCategoryId.value) {
    const c = flatCats.value.find((x) => x.id === filterCategoryId.value)
    return c ? c.label : '分类'
  }
  return '全部'
})

async function wrapAuth(fn) {
  try {
    return await fn()
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
      return
    }
    alert(e.message || '请求失败')
  }
}

function listQuery(p) {
  const q = {
    page: p,
    size,
    keyword: keyword.value.trim() || undefined,
    onlyDeleted: trashMode.value || undefined
  }
  if (!trashMode.value) {
    if (filterTagId.value) q.tagId = filterTagId.value
    else if (filterUncategorized.value) q.uncategorized = true
    else if (filterCategoryId.value) q.categoryId = filterCategoryId.value
  }
  return q
}

async function loadMeta() {
  await wrapAuth(async () => {
    categories.value = (await api.listCategories()) || []
    tags.value = ((await api.listTags()) || []).map((t) => ({
      id: String(t.id),
      name: t.name
    }))
    const tc = await api.trashCount()
    trashCount.value = Number(tc?.count || 0)
  })
}

async function reload() {
  loading.value = true
  loadError.value = ''
  page.value = 0
  openId.value = ''
  try {
    const data = await api.listNotes(listQuery(0))
    notes.value = data.items || []
    total.value = data.total || 0
    hasMore.value = notes.value.length < total.value
    if (trashMode.value) trashCount.value = total.value
    else {
      const tc = await api.trashCount()
      trashCount.value = Number(tc?.count || 0)
    }
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
      return
    }
    loadError.value = e.message || '加载失败'
    notes.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  const next = page.value + 1
  await wrapAuth(async () => {
    const data = await api.listNotes(listQuery(next))
    notes.value = notes.value.concat(data.items || [])
    page.value = next
    total.value = data.total || 0
    hasMore.value = notes.value.length < total.value
  })
  loadingMore.value = false
}

function clearFilters() {
  trashMode.value = false
  filterCategoryId.value = ''
  filterTagId.value = ''
  filterUncategorized.value = false
  page.value = 0
  reload()
}

function filterUncat() {
  trashMode.value = false
  filterCategoryId.value = ''
  filterTagId.value = ''
  filterUncategorized.value = true
  page.value = 0
  reload()
}

function filterByCat(id) {
  trashMode.value = false
  filterCategoryId.value = id
  filterTagId.value = ''
  filterUncategorized.value = false
  page.value = 0
  reload()
}

function filterByTag(id) {
  trashMode.value = false
  filterTagId.value = id
  filterCategoryId.value = ''
  filterUncategorized.value = false
  page.value = 0
  reload()
}

function toggleTrash() {
  const next = !trashMode.value
  trashMode.value = next
  filterCategoryId.value = ''
  filterTagId.value = ''
  filterUncategorized.value = false
  page.value = 0
  reload()
}

function onTouchStart(e, id) {
  touchX = e.changedTouches?.[0]?.clientX || 0
}

function onTouchEnd(e, id) {
  if (trashMode.value) return
  const x = e.changedTouches?.[0]?.clientX || 0
  const dx = x - touchX
  if (dx < -48) openId.value = id
  else if (dx > 40 && openId.value === id) openId.value = ''
}

function onLongPress(n) {
  if (trashMode.value) return
  const act = prompt(`「${n.title || '未命名'}」\n1 置顶切换  2 删除`, '1')
  if (act === '1') togglePin(n)
  else if (act === '2') softDelete(n.id)
}

async function togglePin(n) {
  await wrapAuth(async () => {
    await api.updateNote(n.id, { pinned: !n.pinned })
    openId.value = ''
    await reload()
  })
}

async function softDelete(id) {
  if (!confirm('移入回收站？')) return
  await wrapAuth(async () => {
    await api.deleteNote(id)
    openId.value = ''
    await reload()
  })
}

function goDetail(id) {
  if (openId.value === id) {
    openId.value = ''
    return
  }
  if (openId.value) openId.value = ''
  router.push(`/detail/${id}`)
}

function goCreate() {
  router.push('/edit')
}

async function onEmptyTrash() {
  if (!confirm('清空回收站？将永久删除全部笔记及附件，不可恢复。')) return
  emptying.value = true
  await wrapAuth(async () => {
    const r = await api.emptyTrash()
    alert(r?.deleted ? `已清空 ${r.deleted} 条` : '回收站已空')
    await reload()
  })
  emptying.value = false
}

onMounted(async () => {
  try {
    const raw = sessionStorage.getItem('kb_list_filter')
    if (raw) {
      sessionStorage.removeItem('kb_list_filter')
      const f = JSON.parse(raw)
      if (f.tagId) {
        filterTagId.value = String(f.tagId)
        filterCategoryId.value = ''
        filterUncategorized.value = false
        trashMode.value = false
      }
    }
  } catch {
    /* ignore */
  }
  await loadMeta()
  await reload()
})
</script>

<style scoped>
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.top h1 {
  margin: 0;
  font-size: 22px;
}
.top-actions {
  display: flex;
  gap: 8px;
}
.sm {
  padding: 8px 12px;
  border-radius: 10px;
}
.search {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.search input {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}
.tool-links {
  display: flex;
  gap: 14px;
  margin-bottom: 8px;
}
.link {
  border: none;
  background: transparent;
  color: var(--primary);
  font-weight: 650;
  font-size: 13px;
  padding: 0;
  cursor: pointer;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.chip {
  border: 1px solid var(--border);
  background: var(--surface-2, #f1f5f9);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}
.chip.on {
  border-color: var(--primary);
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 12%, #fff);
}
.chip.tag-chip.on {
  border-color: #6366f1;
  color: #3730a3;
  background: #e0e7ff;
}
.chip.trash.on {
  border-color: #ef4444;
  color: #ef4444;
  background: #fef2f2;
}
.meta {
  font-size: 12px;
  margin: 0 2px 10px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.note {
  padding: 0;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}
.note.deleted {
  opacity: 0.75;
}
.note-body {
  padding: 14px;
  background: #fff;
  position: relative;
  z-index: 1;
  transition: transform 0.2s ease;
}
.note.open .note-body {
  transform: translateX(-120px);
}
.swipe-hint {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  z-index: 0;
}
.act {
  width: 60px;
  border: none;
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.act.pin {
  background: #2563eb;
}
.act.del {
  background: #ef4444;
}
.title {
  font-weight: 700;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.fmt {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
}
.fmt.md {
  background: #dbeafe;
  color: #1d4ed8;
}
.fmt.html {
  background: #dcfce7;
  color: #15803d;
}
.snippet {
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.6em;
}
.foot {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 12px;
  align-items: center;
}
.cat {
  background: #e2e8f0;
  padding: 1px 8px;
  border-radius: 6px;
}
.time {
  margin-left: auto;
}
.more {
  margin-top: 14px;
}
.btn-danger {
  background: #ef4444;
  color: #fff;
  border: none;
  border-radius: 10px;
}
</style>
