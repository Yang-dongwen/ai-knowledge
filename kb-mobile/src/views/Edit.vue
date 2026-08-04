<template>
  <div class="page no-tab">
    <header class="top">
      <button class="back" type="button" @click="$router.back()">← 返回</button>
      <h1>{{ id ? '编辑' : '新建' }}</h1>
      <button class="btn btn-primary sm" type="button" :disabled="saving" @click="onSave">
        {{ saving ? '…' : '保存' }}
      </button>
    </header>

    <!-- 新建时选格式 -->
    <div v-if="!id && !formatLocked" class="format-pick card">
      <p class="muted">选择编辑格式（创建后固定，PC 可转换）</p>
      <div class="format-btns">
        <button type="button" class="fmt-btn" :class="{ on: format === 'html' }" @click="pickFormat('html')">
          富文本
        </button>
        <button
          type="button"
          class="fmt-btn"
          :class="{ on: format === 'markdown' }"
          @click="pickFormat('markdown')"
        >
          Markdown
        </button>
      </div>
    </div>

    <div class="card form">
      <input v-model="title" class="title" maxlength="200" placeholder="标题" @input="onTitleInput" />
      <div class="rule" />

      <div class="html-tools">
        <label class="tool-btn">
          插图
          <input type="file" accept="image/*" hidden @change="onPickImage" />
        </label>
        <template v-if="format === 'html'">
          <button type="button" class="tool-btn" @click="applyFmt('bold')">粗体</button>
          <button type="button" class="tool-btn" @click="applyFmt('ul')">列表</button>
          <button type="button" class="tool-btn" @click="applyFmt('quote')">引用</button>
          <button type="button" class="tool-btn" @click="applyFmt('h2')">小标题</button>
        </template>
        <template v-else>
          <button type="button" class="tool-btn" @click="applyFmt('md-bold')">**粗**</button>
          <button type="button" class="tool-btn" @click="applyFmt('md-ul')">列表</button>
          <button type="button" class="tool-btn" @click="applyFmt('md-quote')">引用</button>
        </template>
      </div>

      <!-- 富文本：contenteditable 简易编辑 -->
      <div
        v-if="format === 'html'"
        ref="htmlBox"
        class="html-edit"
        contenteditable="true"
        data-placeholder="正文从这里开始…"
        @input="onHtmlInput"
        @paste="onHtmlPaste"
      />

      <!-- Markdown -->
      <textarea
        v-else
        v-model="mdBody"
        class="md-body"
        placeholder="正文（Markdown）…"
        @input="onMdInput"
      />
    </div>

    <div class="meta card">
      <label>
        分类
        <select v-model="categoryId">
          <option value="">未分类</option>
          <option v-for="c in flatCats" :key="c.id" :value="c.id">{{ c.label }}</option>
        </select>
      </label>
      <div class="tag-block">
        <div class="tag-head">
          <span class="tag-label">标签</span>
          <button type="button" class="link-add" @click="createTag">+ 新建</button>
        </div>
        <div class="tag-chips">
          <button
            v-for="t in allTags"
            :key="t.id"
            type="button"
            class="chip"
            :class="{ on: selectedTagIds.includes(String(t.id)) }"
            @click="toggleTag(t.id)"
          >
            #{{ t.name }}
          </button>
          <span v-if="!allTags.length" class="muted tip">暂无标签，点右上角新建</span>
        </div>
      </div>
      <label class="pin">
        <input v-model="pinned" type="checkbox" />
        置顶
      </label>
    </div>

    <div v-if="id" class="files card">
      <div class="files-head">
        <span>附件</span>
        <label class="tool-btn">
          上传
          <input type="file" hidden multiple @change="onPickFile" />
        </label>
      </div>
      <p v-if="!files.length" class="muted tip">暂无附件</p>
      <div v-for="f in files" :key="f.id" class="file-row">
        <span>{{ f.originalName }}</span>
        <button type="button" class="link" @click="removeFile(f)">删</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  clearSession,
  injectMediaInHtml,
  kbMediaUrl,
  mdImageSyntax,
  stripKbMediaTokens,
  stripKbMediaTokensAll
} from '../api'
import {
  emptyHtmlDoc,
  emptyMarkdownDoc,
  ensureHtmlHasTitle,
  ensureMarkdownHasTitle,
  extractTitle,
  joinMarkdownDoc,
  splitMarkdownDoc
} from '../utils/title'

const route = useRoute()
const router = useRouter()

const id = ref('')
const format = ref('html')
const formatLocked = ref(false)
const title = ref('未命名笔记')
const mdBody = ref('')
const htmlBox = ref(null)
const categoryId = ref('')
const pinned = ref(false)
const saving = ref(false)
const categories = ref([])
const allTags = ref([])
const selectedTagIds = ref([])
const files = ref([])
const pendingFileIds = ref([])

const flatCats = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes || []) {
      out.push({ id: String(n.id), label: `${'— '.repeat(depth)}${n.name}`.trim() })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

function pickFormat(f) {
  format.value = f
  formatLocked.value = true
  if (f === 'html') {
    setHtmlEditor(emptyHtmlDoc(title.value || '未命名笔记'))
  } else {
    const { body } = splitMarkdownDoc(emptyMarkdownDoc(title.value || '未命名笔记'))
    mdBody.value = body
  }
}

function onTitleInput() {
  // 标题同步进内容
  if (format.value === 'markdown') {
    // body 不变，join 时用 title
  } else if (htmlBox.value) {
    const h1 = htmlBox.value.querySelector('h1')
    if (h1) h1.textContent = title.value || '未命名笔记'
    else {
      htmlBox.value.innerHTML = ensureHtmlHasTitle(htmlBox.value.innerHTML, title.value)
    }
  }
}

function onMdInput() {
  /* title + body joined on save */
}

function onHtmlInput() {
  const t = extractTitle(htmlBox.value?.innerHTML || '', 'html')
  if (t && t !== title.value) title.value = t
}

function setHtmlEditor(html) {
  nextTick(() => {
    if (htmlBox.value) {
      htmlBox.value.innerHTML = injectMediaInHtml(html || emptyHtmlDoc())
    }
  })
}

function getHtmlContent() {
  if (!htmlBox.value) return emptyHtmlDoc(title.value)
  return stripKbMediaTokens(htmlBox.value.innerHTML || '')
}

function getContentToSave() {
  if (format.value === 'markdown') {
    return stripKbMediaTokensAll(
      ensureMarkdownHasTitle(joinMarkdownDoc(title.value, mdBody.value), title.value)
    )
  }
  let html = ensureHtmlHasTitle(getHtmlContent(), title.value)
  return stripKbMediaTokensAll(html)
}

function toggleTag(tid) {
  const s = String(tid)
  const set = new Set(selectedTagIds.value)
  if (set.has(s)) set.delete(s)
  else set.add(s)
  selectedTagIds.value = Array.from(set)
}

function applyFmt(cmd) {
  if (format.value === 'markdown') {
    let body = mdBody.value || ''
    const nl = body && !body.endsWith('\n') ? '\n' : ''
    if (cmd === 'md-bold') body += `${nl}**粗体文字**`
    else if (cmd === 'md-ul') body += `${nl}- 列表项\n`
    else if (cmd === 'md-quote') body += `${nl}> 引用\n`
    mdBody.value = body
    return
  }
  if (!htmlBox.value) return
  htmlBox.value.focus()
  if (cmd === 'bold') document.execCommand('bold')
  else if (cmd === 'ul') document.execCommand('insertUnorderedList')
  else if (cmd === 'quote') document.execCommand('formatBlock', false, 'blockquote')
  else if (cmd === 'h2') document.execCommand('formatBlock', false, 'h2')
  onHtmlInput()
}

async function createTag() {
  const name = (prompt('新标签名称') || '').trim()
  if (!name) return
  try {
    const tag = await api.createTag(name)
    const tid = String(tag.id)
    if (!selectedTagIds.value.includes(tid)) {
      selectedTagIds.value = selectedTagIds.value.concat([tid])
    }
    allTags.value = (await api.listTags()) || []
  } catch (e) {
    alert(e.message || '创建失败')
  }
}

async function uploadOneImage(file) {
  const res = await api.uploadFile(file, id.value || undefined)
  if (!id.value) pendingFileIds.value.push(res.id)
  const path = res.contentPath.startsWith('/') ? res.contentPath : `/${res.contentPath}`
  const url = kbMediaUrl(path)
  if (format.value === 'html' && htmlBox.value) {
    htmlBox.value.focus()
    document.execCommand(
      'insertHTML',
      false,
      `<p><img src="${url}" alt="" style="max-width:100%;height:auto"/></p>`
    )
    onHtmlInput()
  } else {
    mdBody.value = (mdBody.value || '') + `\n\n${mdImageSyntax(path)}\n`
  }
}

async function onPickImage(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  try {
    await uploadOneImage(file)
  } catch (err) {
    alert(err.message || '上传失败')
  }
}

function onHtmlPaste(e) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const it of items) {
    if (it.type.startsWith('image/')) {
      e.preventDefault()
      const file = it.getAsFile()
      if (file) void uploadOneImage(file).catch((err) => alert(err.message || '上传失败'))
      break
    }
  }
}

async function onPickFile(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (!id.value) {
    alert('请先保存笔记再上传附件')
    return
  }
  for (const file of list) {
    try {
      await api.uploadFile(file, id.value)
    } catch (err) {
      alert(err.message || '上传失败')
    }
  }
  await loadFiles()
}

async function loadFiles() {
  if (!id.value) return
  try {
    files.value = (await api.listFiles(id.value)) || []
  } catch {
    files.value = []
  }
}

async function removeFile(f) {
  if (!confirm(`删除附件「${f.originalName}」？`)) return
  try {
    await api.deleteFile(f.id)
    await loadFiles()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

async function onSave() {
  saving.value = true
  try {
    const content = getContentToSave()
    const t = extractTitle(content, format.value)
    title.value = t
    const body = {
      title: t,
      content,
      contentFormat: format.value,
      categoryId: categoryId.value || null,
      clearCategory: !categoryId.value,
      tagIds: selectedTagIds.value,
      pinned: pinned.value
    }
    let note
    if (id.value) {
      note = await api.updateNote(id.value, body)
    } else {
      note = await api.createNote(body)
      id.value = note.id
      formatLocked.value = true
      for (const fid of pendingFileIds.value) {
        try {
          await api.bindFile(fid, note.id)
        } catch {
          /* ignore */
        }
      }
      pendingFileIds.value = []
    }
    await loadFiles()
    alert('已保存')
    router.replace(`/detail/${note.id}`)
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
    } else alert(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function loadNote(noteId) {
  try {
    const n = await api.getNote(noteId)
    id.value = String(n.id)
    format.value = n.contentFormat === 'markdown' ? 'markdown' : 'html'
    formatLocked.value = true
    title.value = n.title || '未命名笔记'
    categoryId.value = n.categoryId ? String(n.categoryId) : ''
    pinned.value = !!n.pinned
    selectedTagIds.value = (n.tags || []).map((t) => String(t.id))
    if (format.value === 'markdown') {
      const ensured = ensureMarkdownHasTitle(n.content || '', n.title)
      const parts = splitMarkdownDoc(ensured)
      title.value = parts.title
      mdBody.value = parts.body
    } else {
      const html = ensureHtmlHasTitle(stripKbMediaTokens(n.content || ''), n.title)
      title.value = extractTitle(html, 'html')
      setHtmlEditor(html)
    }
    await loadFiles()
  } catch (e) {
    if (e.code === 401) {
      clearSession()
      router.replace('/login')
    } else alert(e.message || '加载失败')
  }
}

onMounted(async () => {
  try {
    const [cats, tags] = await Promise.all([api.listCategories(), api.listTags()])
    categories.value = cats || []
    allTags.value = tags || []
  } catch {
    categories.value = []
    allTags.value = []
  }
  const qid = typeof route.query.id === 'string' ? route.query.id : ''
  if (qid) {
    await loadNote(qid)
  } else {
    formatLocked.value = false
    format.value = 'html'
    title.value = '未命名笔记'
    selectedTagIds.value = []
    setHtmlEditor(emptyHtmlDoc())
  }
})

watch(format, (f) => {
  if (id.value) return
  if (f === 'html') setHtmlEditor(emptyHtmlDoc(title.value))
  else {
    const p = splitMarkdownDoc(emptyMarkdownDoc(title.value))
    mdBody.value = p.body
  }
})
</script>

<style scoped>
.top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
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
  cursor: pointer;
}
.sm {
  padding: 8px 12px;
  border-radius: 10px;
}
.format-pick {
  padding: 14px;
  margin-bottom: 12px;
}
.format-btns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}
.fmt-btn {
  border: 1px solid var(--border);
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  font-weight: 650;
  cursor: pointer;
}
.fmt-btn.on {
  border-color: var(--primary);
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, #fff);
}
.form {
  padding: 8px 14px 14px;
  margin-bottom: 12px;
}
.title {
  width: 100%;
  border: none;
  outline: none;
  font-size: 1.55em;
  font-weight: 700;
  padding: 12px 0 10px;
  background: transparent !important;
  color: #0f172a;
}
.rule {
  border-top: 1px solid var(--border);
  margin-bottom: 10px;
}
.html-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.tool-btn {
  display: inline-block;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  background: var(--surface-2, #f1f5f9);
}
.tip {
  font-size: 12px;
}
.html-edit {
  min-height: 240px;
  outline: none;
  font-size: 15px;
  line-height: 1.65;
  word-break: break-word;
  color: #0f172a;
  background: #fff;
}
.html-edit:empty:before {
  content: attr(data-placeholder);
  color: #94a3b8;
}
.html-edit :deep(h1) {
  font-size: 1.2em;
  font-weight: 700;
  margin: 0 0 0.4em;
}
.html-edit :deep(img) {
  max-width: 100%;
}
.md-body {
  width: 100%;
  min-height: 240px;
  border: none !important;
  outline: none;
  resize: vertical;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 14px;
  line-height: 1.6;
  background: #ffffff !important;
  color: #0f172a;
  padding: 4px 0;
}
.meta {
  padding: 12px 14px;
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.meta label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.meta select {
  padding: 8px;
  border-radius: 8px;
  border: 1px solid var(--border);
}
.pin {
  flex-direction: row !important;
  align-items: center;
  gap: 8px !important;
}
.tag-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tag-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.link-add {
  border: none;
  background: transparent;
  color: var(--primary);
  font-weight: 650;
  font-size: 13px;
  cursor: pointer;
}
.tag-label {
  font-size: 13px;
}
.tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  border: 1px solid var(--border);
  background: #f8fafc;
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
.files {
  padding: 12px 14px;
}
.files-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 650;
  margin-bottom: 8px;
}
.file-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;
  border-top: 1px solid #f1f5f9;
}
.link {
  border: none;
  background: transparent;
  color: #ef4444;
  cursor: pointer;
}
</style>
