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

    <div class="filter-row">
      <button
        type="button"
        class="chip"
        :class="{ on: !trashMode && !filterCategoryId }"
        @click="clearFilters"
      >
        全部
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
      <button type="button" class="chip trash" :class="{ on: trashMode }" @click="toggleTrash">
        回收站{{ trashCount ? ` ${trashCount}` : '' }}
      </button>
    </div>

    <p class="meta muted">共 {{ total }} 条</p>

    <div v-if="loading && !notes.length" class="empty">加载中…</div>
    <div v-else-if="!notes.length" class="empty">
      {{ trashMode ? '回收站为空' : '还没有笔记' }}
    </div>
    <div v-else class="list">
      <article
        v-for="n in notes"
        :key="n.id"
        class="card note"
        :class="{ deleted: n.deleted || trashMode }"
        @click="goDetail(n.id)"
      >
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
const trashMode = ref(false)
const trashCount = ref(0)
const emptying = ref(false)
const categories = ref([])
const filterCategoryId = ref('')

const flatCats = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes || []) {
      out.push({ id: n.id, label: `${'— '.repeat(depth)}${n.name}`.trim() })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out.slice(0, 12)
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

async function loadMeta() {
  await wrapAuth(async () => {
    categories.value = (await api.listCategories()) || []
    const tc = await api.trashCount()
    trashCount.value = Number(tc?.count || 0)
  })
}

async function reload() {
  loading.value = true
  page.value = 0
  await wrapAuth(async () => {
    const data = await api.listNotes({
      page: 0,
      size,
      keyword: keyword.value.trim() || undefined,
      onlyDeleted: trashMode.value || undefined,
      categoryId: !trashMode.value && filterCategoryId.value ? filterCategoryId.value : undefined
    })
    notes.value = data.items || []
    total.value = data.total || 0
    hasMore.value = notes.value.length < total.value
    if (trashMode.value) trashCount.value = total.value
    else {
      const tc = await api.trashCount()
      trashCount.value = Number(tc?.count || 0)
    }
  })
  loading.value = false
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  const next = page.value + 1
  await wrapAuth(async () => {
    const data = await api.listNotes({
      page: next,
      size,
      keyword: keyword.value.trim() || undefined,
      onlyDeleted: trashMode.value || undefined,
      categoryId: !trashMode.value && filterCategoryId.value ? filterCategoryId.value : undefined
    })
    notes.value = notes.value.concat(data.items || [])
    page.value = next
    total.value = data.total || 0
    hasMore.value = notes.value.length < total.value
  })
  loadingMore.value = false
}

function toggleTrash() {
  trashMode.value = !trashMode.value
  filterCategoryId.value = ''
  page.value = 0
  reload()
}

function clearFilters() {
  trashMode.value = false
  filterCategoryId.value = ''
  page.value = 0
  reload()
}

function filterByCat(id) {
  trashMode.value = false
  filterCategoryId.value = id
  page.value = 0
  reload()
}

function goDetail(id) {
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
  margin-bottom: 10px;
}
.search input {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
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
  padding: 14px;
  cursor: pointer;
}
.note.deleted {
  opacity: 0.75;
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
