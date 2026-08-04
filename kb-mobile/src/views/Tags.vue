<template>
  <div class="page no-tab">
    <header class="top">
      <button class="back" type="button" @click="$router.back()">← 返回</button>
      <h1>标签</h1>
      <button class="btn btn-primary sm" type="button" @click="openCreate">新建</button>
    </header>

    <p class="meta muted">共 {{ tags.length }} 个标签</p>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!tags.length" class="empty">
      <p>还没有标签</p>
      <button class="btn btn-primary" type="button" @click="openCreate">创建标签</button>
    </div>
    <div v-else class="list">
      <button
        v-for="t in tags"
        :key="t.id"
        type="button"
        class="card tag-row"
        @click="onTag(t)"
      >
        <span class="name"><span class="hash">#</span>{{ t.name }}</span>
        <span class="muted">{{ t.noteCount || 0 }} 篇 ›</span>
      </button>
    </div>

    <div v-if="sheet" class="mask" @click="sheet = null" />
    <div v-if="sheet" class="sheet card">
      <h3>{{ sheet.mode === 'create' ? '新建标签' : '重命名' }}</h3>
      <input v-model="sheet.name" class="inp" maxlength="64" placeholder="标签名称" />
      <div class="sheet-actions">
        <button class="btn btn-ghost" type="button" @click="sheet = null">取消</button>
        <button class="btn btn-primary" type="button" :disabled="busy" @click="submit">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, clearSession } from '../api'

const router = useRouter()
const tags = ref([])
const loading = ref(true)
const sheet = ref(null)
const busy = ref(false)

async function wrap(fn) {
  try {
    await fn()
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
      return
    }
    alert(e.message || '失败')
  }
}

async function load() {
  loading.value = true
  await wrap(async () => {
    tags.value = ((await api.listTags()) || []).map((t) => ({
      id: String(t.id),
      name: t.name,
      noteCount: t.noteCount || 0
    }))
  })
  loading.value = false
}

function openCreate() {
  sheet.value = { mode: 'create', name: '', id: '' }
}

function onTag(t) {
  const act = prompt(`标签 #${t.name}\n1 筛选笔记  2 重命名  3 删除`, '1')
  if (act === '1') {
    sessionStorage.setItem('kb_list_filter', JSON.stringify({ tagId: t.id }))
    router.push('/notes')
  } else if (act === '2') {
    sheet.value = { mode: 'rename', name: t.name, id: t.id }
  } else if (act === '3') {
    if (!confirm(`删除标签「${t.name}」？笔记不会删除。`)) return
    wrap(async () => {
      await api.deleteTag(t.id)
      await load()
    })
  }
}

async function submit() {
  if (!sheet.value || busy.value) return
  const name = (sheet.value.name || '').trim()
  if (!name) {
    alert('请输入名称')
    return
  }
  busy.value = true
  await wrap(async () => {
    if (sheet.value.mode === 'create') await api.createTag(name)
    else await api.updateTag(sheet.value.id, name)
    sheet.value = null
    await load()
  })
  busy.value = false
}

onMounted(load)
</script>

<style scoped>
.top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.top h1 {
  flex: 1;
  margin: 0;
  font-size: 18px;
}
.back {
  border: none;
  background: transparent;
  color: var(--primary);
  font-weight: 600;
}
.sm {
  padding: 8px 12px;
  border-radius: 10px;
}
.meta {
  font-size: 12px;
  margin: 0 0 10px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tag-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  width: 100%;
  border: none;
  text-align: left;
  cursor: pointer;
}
.name {
  font-weight: 650;
  font-size: 15px;
}
.hash {
  color: var(--primary);
  margin-right: 2px;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  z-index: 80;
}
.sheet {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: 24px;
  z-index: 90;
  padding: 16px;
}
.sheet h3 {
  margin: 0 0 12px;
}
.inp {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #f8fafc;
}
.sheet-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 12px;
}
</style>
