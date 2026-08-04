<template>
  <div class="page no-tab">
    <header class="top">
      <button class="back" type="button" @click="$router.back()">← 返回</button>
      <h1>文件夹</h1>
      <button class="btn btn-primary sm" type="button" @click="openCreate('')">新建</button>
    </header>

    <p class="meta muted">{{ folderCount }} 文件夹 · {{ noteCount }} 文档</p>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!rows.length" class="empty">
      <p>还没有内容</p>
      <button class="btn btn-primary" type="button" @click="openCreate('')">创建文件夹</button>
    </div>
    <div v-else class="tree card">
      <div
        v-for="row in rows"
        :key="row.typeId"
        class="row"
        :style="{ paddingLeft: row.pad + 'px' }"
      >
        <template v-if="row.type === 'folder'">
          <button type="button" class="twist" @click="toggle(row.id)">
            {{ row.hasChildren ? (row.expanded ? '▾' : '▸') : '·' }}
          </button>
          <span class="icon">📁</span>
          <button type="button" class="label" @click="toggle(row.id)">{{ row.name }}</button>
          <button type="button" class="more" @click="folderMenu(row)">···</button>
        </template>
        <template v-else>
          <span class="twist spacer">·</span>
          <span class="icon">{{ row.pinned ? '📌' : '📄' }}</span>
          <button type="button" class="label note" @click="openNote(row.id)">{{ row.name }}</button>
          <span v-if="row.formatLabel" class="fmt">{{ row.formatLabel }}</span>
          <button type="button" class="more" @click="noteMenu(row)">···</button>
        </template>
      </div>
    </div>

    <!-- 简易对话框 -->
    <div v-if="sheet" class="mask" @click="closeSheet" />
    <div v-if="sheet" class="sheet card">
      <h3>{{ sheet.title }}</h3>
      <input
        v-if="sheet.mode === 'create' || sheet.mode === 'rename'"
        v-model="sheet.name"
        class="inp"
        placeholder="文件夹名称"
        maxlength="64"
      />
      <label v-if="sheet.mode === 'create' || sheet.mode === 'moveNote' || sheet.mode === 'moveFolder'">
        {{ sheet.mode === 'create' ? '上级' : '目标' }}
        <select v-model="sheet.parentId">
          <option v-for="f in parentOptions" :key="f.id || 'root'" :value="f.id">{{ f.name }}</option>
        </select>
      </label>
      <label v-if="sheet.mode === 'delete'">
        删除方式
        <select v-model="sheet.deleteMode">
          <option value="reject">仅空文件夹可删</option>
          <option value="orphan">子项移到上级/未分类</option>
          <option value="trash">文档进回收站</option>
        </select>
      </label>
      <div class="sheet-actions">
        <button class="btn btn-ghost" type="button" :disabled="busy" @click="closeSheet">取消</button>
        <button class="btn btn-primary" type="button" :disabled="busy" @click="submitSheet">
          {{ sheet.mode === 'delete' ? '确认删除' : '确定' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, clearSession } from '../api'

const router = useRouter()
const loading = ref(true)
const folderCount = ref(0)
const noteCount = ref(0)
const rows = ref([])
const expandedIds = ref({})
const flatFolders = ref([{ id: '', name: '根目录' }])
const parentOptions = ref([])
const sheet = ref(null)
const busy = ref(false)
let tree = []

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

function normalize(nodes, depth) {
  return (nodes || []).map((n) => {
    const type = n.type === 'folder' ? 'folder' : 'note'
    const children = type === 'folder' ? normalize(n.children || [], depth + 1) : []
    return {
      type,
      id: String(n.id),
      name: n.name || (type === 'folder' ? '未命名文件夹' : '未命名笔记'),
      pinned: !!n.pinned,
      formatLabel: type === 'note' ? (n.contentFormat === 'markdown' ? 'MD' : '富文本') : '',
      depth,
      children
    }
  })
}

function rebuildRows() {
  const out = []
  const walk = (nodes) => {
    for (const n of nodes || []) {
      const hasChildren = n.type === 'folder' && n.children?.length > 0
      const expanded = !!expandedIds.value[n.id]
      out.push({
        type: n.type,
        id: n.id,
        typeId: `${n.type}-${n.id}`,
        name: n.name,
        pinned: n.pinned,
        formatLabel: n.formatLabel,
        depth: n.depth,
        hasChildren,
        expanded,
        pad: n.depth * 14 + 4
      })
      if (n.type === 'folder' && expanded && hasChildren) walk(n.children)
    }
  }
  walk(tree)
  rows.value = out
}

async function load() {
  loading.value = true
  await wrap(async () => {
    const data = await api.getExplorerTree()
    tree = normalize(data.roots || [], 0)
    folderCount.value = data.folderCount || 0
    noteCount.value = data.noteCount || 0
    const flats = [{ id: '', name: '根目录' }]
    const walk = (nodes, depth) => {
      for (const n of nodes || []) {
        if (n.type === 'folder') {
          flats.push({ id: n.id, name: `${'— '.repeat(depth)}${n.name}` })
          if (n.children?.length) walk(n.children, depth + 1)
        }
      }
    }
    walk(tree, 0)
    flatFolders.value = flats
    const exp = { ...expandedIds.value }
    tree.forEach((n) => {
      if (n.type === 'folder' && exp[n.id] === undefined) exp[n.id] = true
    })
    expandedIds.value = exp
    rebuildRows()
  })
  loading.value = false
}

function toggle(id) {
  expandedIds.value = { ...expandedIds.value, [id]: !expandedIds.value[id] }
  rebuildRows()
}

function openNote(id) {
  router.push(`/detail/${id}`)
}

function openCreate(parentId) {
  parentOptions.value = flatFolders.value
  sheet.value = {
    mode: 'create',
    title: '新建文件夹',
    name: '',
    parentId: parentId || '',
    targetId: ''
  }
}

function closeSheet() {
  if (busy.value) return
  sheet.value = null
}

async function submitSheet() {
  if (!sheet.value || busy.value) return
  busy.value = true
  await wrap(async () => {
    const s = sheet.value
    if (s.mode === 'create') {
      const name = (s.name || '').trim()
      if (!name) throw new Error('请输入名称')
      await api.createCategory({ name, parentId: s.parentId || null })
    } else if (s.mode === 'rename') {
      const name = (s.name || '').trim()
      if (!name) throw new Error('请输入名称')
      await api.updateCategory(s.targetId, { name })
    } else if (s.mode === 'delete') {
      await api.deleteCategory(s.targetId, s.deleteMode || 'orphan')
    } else if (s.mode === 'moveNote' || s.mode === 'moveFolder') {
      const targetFolderId = s.parentId || null
      await api.treeMove({
        type: s.mode === 'moveNote' ? 'note' : 'folder',
        id: s.targetId,
        ...(targetFolderId ? { targetFolderId } : { clearToRoot: true })
      })
    }
    sheet.value = null
    await load()
  })
  busy.value = false
}

function folderMenu(row) {
  const act = prompt(
    `文件夹「${row.name}」\n输入：1新建子 2重命名 3上移 4下移 5移动 6删除`,
    '2'
  )
  if (act === '1') openCreate(row.id)
  else if (act === '2') {
    parentOptions.value = flatFolders.value
    sheet.value = { mode: 'rename', title: '重命名', name: row.name, targetId: row.id }
  } else if (act === '3') reorder('folder', row.id, -1)
  else if (act === '4') reorder('folder', row.id, 1)
  else if (act === '5') {
    parentOptions.value = flatFolders.value.filter((f) => f.id !== row.id)
    sheet.value = {
      mode: 'moveFolder',
      title: '移动文件夹',
      targetId: row.id,
      parentId: ''
    }
  } else if (act === '6') {
    sheet.value = {
      mode: 'delete',
      title: `删除「${row.name}」`,
      targetId: row.id,
      deleteMode: 'orphan'
    }
  }
}

function noteMenu(row) {
  const act = prompt(`笔记「${row.name}」\n输入：1移动 2上移 3下移 4打开`, '4')
  if (act === '1') {
    parentOptions.value = flatFolders.value
    sheet.value = { mode: 'moveNote', title: '移动笔记', targetId: row.id, parentId: '' }
  } else if (act === '2') reorder('note', row.id, -1)
  else if (act === '3') reorder('note', row.id, 1)
  else if (act === '4') openNote(row.id)
}

function findParentContext(type, id) {
  let found = null
  const walk = (nodes, parentId) => {
    const list = nodes || []
    const same = list.filter((n) => n.type === type)
    if (same.some((n) => n.id === id)) {
      found = { parentId: parentId || '', siblings: same }
      return
    }
    list.forEach((n) => {
      if (n.type === 'folder' && n.children) walk(n.children, n.id)
    })
  }
  walk(tree, '')
  return found
}

async function reorder(type, id, delta) {
  const ctx = findParentContext(type, String(id))
  if (!ctx?.siblings || ctx.siblings.length < 2) {
    alert('无法调整顺序')
    return
  }
  const ids = ctx.siblings.map((s) => s.id)
  const idx = ids.indexOf(String(id))
  const j = idx + delta
  if (idx < 0 || j < 0 || j >= ids.length) {
    alert(delta < 0 ? '已在最上' : '已在最下')
    return
  }
  const next = ids.slice()
  ;[next[idx], next[j]] = [next[j], next[idx]]
  await wrap(async () => {
    await api.treeReorder({
      type,
      orderedIds: next,
      ...(ctx.parentId ? { parentFolderId: ctx.parentId } : { clearParent: true })
    })
    await load()
  })
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
  padding: 0;
}
.sm {
  padding: 8px 12px;
  border-radius: 10px;
}
.meta {
  font-size: 12px;
  margin: 0 0 10px;
}
.tree {
  overflow: hidden;
}
.row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  border-bottom: 1px solid #f1f5f9;
  padding-right: 8px;
}
.twist {
  width: 22px;
  border: none;
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
}
.spacer {
  visibility: hidden;
}
.icon {
  font-size: 14px;
}
.label {
  flex: 1;
  text-align: left;
  border: none;
  background: transparent;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label.note {
  font-weight: 400;
  color: #334155;
}
.fmt {
  font-size: 10px;
  background: #dbeafe;
  color: #1d4ed8;
  padding: 1px 5px;
  border-radius: 4px;
}
.more {
  border: none;
  background: transparent;
  color: #94a3b8;
  font-weight: 700;
  padding: 8px;
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
  font-size: 16px;
}
.inp,
.sheet select {
  width: 100%;
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #f8fafc;
}
.sheet label {
  display: block;
  font-size: 13px;
  margin-top: 8px;
}
.sheet-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 14px;
}
</style>
