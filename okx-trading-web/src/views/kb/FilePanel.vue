<template>
  <div
    class="file-panel"
    :class="{ 'is-dragover': dragOver }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <div class="head">
      <span class="title">附件</span>
      <div class="head-actions">
        <span class="drop-hint muted">可直接拖入文件</span>
        <input
          ref="resumeInputRef"
          type="file"
          class="resume-file-input"
          @change="onResumeFilePicked"
        />
        <a-upload
          :show-upload-list="false"
          :disabled="disabled"
          :before-upload="beforeUpload"
          multiple
        >
          <a-button size="small" type="primary" ghost :disabled="disabled">
            选择文件
          </a-button>
        </a-upload>
      </div>
    </div>
    <ul v-if="inflight.length" class="list inflight-list">
      <li v-for="u in inflight" :key="u.id" class="item">
        <div class="meta">
          <span class="kind">{{ inflightKind(u.status) }}</span>
          <span class="name" :title="u.name">{{ u.name }}</span>
          <span class="size muted">{{ inflightHint(u) }}</span>
        </div>
        <a-progress
          v-if="u.status !== 'error'"
          :percent="u.percent"
          size="small"
          :show-info="false"
          class="up-progress"
        />
        <div v-if="u.status !== 'completing'" class="ops">
          <a-button
            v-if="u.status === 'uploading'"
            type="link"
            size="small"
            @click="pauseUpload(u.id)"
          >
            暂停
          </a-button>
          <a-button
            v-if="u.status === 'paused' || u.status === 'error'"
            type="link"
            size="small"
            @click="resumeUpload(u.id)"
          >
            {{ u.status === 'error' ? '重试' : u.file ? '继续' : '选择文件继续' }}
          </a-button>
          <a-button type="link" size="small" danger @click="cancelUpload(u.id)">取消</a-button>
        </div>
      </li>
    </ul>
    <div v-if="!noteId" class="hint muted">
      尚未保存笔记：拖入的文件会先上传，保存笔记后自动关联。
    </div>
    <div v-else-if="loading" class="hint muted">加载中…</div>
    <div v-else-if="!files.length" class="drop-zone-empty muted">
      拖拽任意文件到此处，或点「选择文件」（单文件最大 2GB）
    </div>
    <ul v-else class="list">
      <li v-for="f in files" :key="f.id" class="item">
        <div class="meta" @click="preview(f)">
          <span class="kind">{{ kindLabel(f.kind) }}</span>
          <span class="name" :title="f.originalName">{{ f.originalName }}</span>
          <span class="size muted">{{ formatSize(f.sizeBytes) }}</span>
        </div>
        <div class="ops">
          <a-button type="link" size="small" @click="preview(f)">预览</a-button>
          <a-button type="link" size="small" @click="download(f)">下载</a-button>
          <a-popconfirm v-if="!disabled" title="删除该附件？" @confirm="remove(f)">
            <a-button type="link" size="small" danger>删除</a-button>
          </a-popconfirm>
        </div>
      </li>
    </ul>

    <a-modal
      v-model:open="previewOpen"
      :title="previewFile?.originalName || '预览'"
      width="920px"
      :footer="null"
      destroy-on-close
      wrap-class-name="kb-file-preview-modal"
      @cancel="closePreview"
    >
      <div v-if="previewLoading" class="preview-box">加载预览…</div>
      <div v-else-if="previewError" class="preview-box error">
        <p>{{ previewError }}</p>
        <a-button v-if="previewFile" type="primary" @click="download(previewFile)">下载原文件</a-button>
      </div>
      <div v-else class="preview-box">
        <img v-if="previewMode === 'image'" :src="previewSrc" class="pv-img" alt="" />
        <video v-else-if="previewMode === 'video'" :src="previewSrc" controls class="pv-video" />
        <!-- PDF：同源 + access_token，浏览器边下边显 -->
        <iframe
          v-else-if="previewMode === 'pdf'"
          :src="previewSrc"
          class="pv-iframe"
          title="PDF 预览"
        />
        <div v-else-if="previewMode === 'docx'" ref="docxHost" class="pv-docx" />
        <div v-else-if="previewMode === 'html'" class="pv-html" v-html="previewHtml" />
        <div v-else-if="previewMode === 'table'" class="pv-table-wrap">
          <table class="pv-table">
            <tbody>
              <tr v-for="(row, ri) in previewRows" :key="ri">
                <td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="preview-box">
          <p>该格式暂不支持高保真在线预览，请下载后用本地 Office 打开。</p>
          <a-button type="primary" @click="previewFile && download(previewFile)">下载文件</a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  kbApi,
  kbMediaUrl,
  KB_MAX_FILE_BYTES,
  dropKbUploadFile,
  isKbUploadCanceled,
  isKbUploadPaused,
  takeKbUploadFile,
  type KbFileItem
} from '@/api/kb.api'
import { triggerNativeDownload } from '@/utils/download'

type InflightStatus = 'uploading' | 'completing' | 'paused' | 'error'

type InflightItem = {
  id: string
  name: string
  percent: number
  status: InflightStatus
  error?: string
  file?: File
  sizeBytes?: number
  controller: AbortController
  uploadId?: string
}

const props = defineProps<{
  noteId?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 无 noteId 时上传成功，父级可在保存后 bind */
  pendingUploaded: [fileId: string]
  /** 请求父级先保存笔记再重试拖入 */
  needNote: []
}>()

const files = ref<KbFileItem[]>([])
const loading = ref(false)
const inflight = ref<InflightItem[]>([])
const dragOver = ref(false)
let dragDepth = 0
const running = new Map<string, Promise<void>>()
const FILE_CONCURRENCY = 2
let fileActive = 0
const fileWaiters: Array<() => void> = []
const resumeInputRef = ref<HTMLInputElement | null>(null)
let resumePickId: string | null = null

const previewOpen = ref(false)
const previewFile = ref<KbFileItem | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const previewMode = ref<'image' | 'video' | 'pdf' | 'docx' | 'html' | 'table' | 'none'>('none')
const previewSrc = ref('')
const previewHtml = ref('')
const previewRows = ref<string[][]>([])
const docxHost = ref<HTMLElement | null>(null)
/** 预览用 blob URL，关闭时 revoke */
let objectUrl: string | null = null

function kindLabel(k: string) {
  const map: Record<string, string> = {
    image: '图',
    video: '视',
    audio: '音',
    pdf: 'PDF',
    office: 'Office',
    other: '文件'
  }
  return map[k] || '文件'
}

function formatSize(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function inflightKind(status: InflightStatus) {
  if (status === 'paused') return '停'
  if (status === 'error') return '败'
  if (status === 'completing') return '合'
  return '传'
}

function inflightHint(u: InflightItem) {
  if (u.status === 'error') return u.error || '上传失败'
  if (u.status === 'paused') {
    return u.file ? `已暂停 ${u.percent}%` : `已暂停 ${u.percent}% · 请选择同一文件继续`
  }
  if (u.status === 'completing') return '正在写入存储…'
  return `${u.percent}%`
}

function revokeObjectUrl() {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = null
  }
}

async function reload() {
  if (!props.noteId) {
    files.value = []
    return
  }
  loading.value = true
  try {
    const res = await kbApi.listFiles(props.noteId)
    files.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载附件失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.noteId,
  async () => {
    for (const u of inflight.value) {
      if (u.status === 'uploading') u.controller.abort('pause')
    }
    inflight.value = []
    await reload()
    await restorePending()
  },
  { immediate: true }
)

function onDragEnter() {
  if (props.disabled) return
  dragDepth++
  dragOver.value = true
}

function onDragOver() {
  if (props.disabled) return
  dragOver.value = true
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dragOver.value = false
}

async function onDrop(e: DragEvent) {
  dragDepth = 0
  dragOver.value = false
  if (props.disabled) return
  const list = e.dataTransfer?.files
  if (!list?.length) return
  await Promise.all(Array.from(list).map((file) => enqueueUpload(file)))
}

function beforeUpload(file: File) {
  void enqueueUpload(file)
  return false
}

function acquireFileSlot(): Promise<void> {
  if (fileActive < FILE_CONCURRENCY) {
    fileActive++
    return Promise.resolve()
  }
  return new Promise((resolve) => {
    fileWaiters.push(() => {
      fileActive++
      resolve()
    })
  })
}

function releaseFileSlot() {
  fileActive = Math.max(0, fileActive - 1)
  const next = fileWaiters.shift()
  if (next) next()
}

async function enqueueUpload(file: File, reuseId?: string) {
  if (file.size > KB_MAX_FILE_BYTES) {
    message.error(`「${file.name}」超过 2GB 上限`)
    return
  }
  const id = reuseId || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const controller = new AbortController()
  const existing = inflight.value.find((u) => u.id === id)
  if (existing) {
    patchInflight(id, { status: 'uploading', error: undefined, controller })
  } else {
    inflight.value = [
      ...inflight.value,
      { id, name: file.name, percent: 0, status: 'uploading', file, controller }
    ]
  }
  const job = (async () => {
    await acquireFileSlot()
    try {
      const row = inflight.value.find((u) => u.id === id)
      if (!row || row.status !== 'uploading') return
      await doUpload(id, file, row.controller)
    } finally {
      releaseFileSlot()
    }
  })()
  running.set(id, job)
  try {
    await job
  } finally {
    running.delete(id)
  }
}

function patchInflight(id: string, patch: Partial<InflightItem>) {
  inflight.value = inflight.value.map((u) => (u.id === id ? { ...u, ...patch } : u))
}

async function doUpload(id: string, file: File, controller: AbortController) {
  try {
    const current = inflight.value.find((u) => u.id === id)
    let sessionId = current?.uploadId
    const res = await kbApi.uploadFile(file, props.noteId || undefined, {
      resumeUploadId: sessionId,
      onProgress: (p) => {
        const row = inflight.value.find((u) => u.id === id)
        if (!row || row.status === 'error') return
        if (row.status === 'paused') {
          patchInflight(id, { percent: p.percent })
          return
        }
        if (p.phase === 'completing') {
          patchInflight(id, { percent: p.percent, status: 'completing' })
          return
        }
        if (row.status === 'completing' && p.phase !== 'done') return
        patchInflight(id, { percent: p.percent })
      },
      onSession: (uploadId) => {
        sessionId = uploadId
        patchInflight(id, { uploadId })
      },
      signal: controller.signal
    })
    inflight.value = inflight.value.filter((u) => u.id !== id)
    if (sessionId) dropKbUploadFile(sessionId)
    if (!props.noteId) {
      emit('pendingUploaded', res.data.id)
      message.success(`已接收「${file.name}」，保存笔记后自动关联`)
    } else {
      message.success(`已添加「${file.name}」`)
      await reload()
    }
  } catch (e: any) {
    const row = inflight.value.find((u) => u.id === id)
    if (!row || row.status === 'paused' || isKbUploadPaused(e) || controller.signal.reason === 'pause') {
      if (row) patchInflight(id, { status: 'paused' })
      return
    }
    if (isKbUploadCanceled(e) || controller.signal.reason === 'cancel') {
      inflight.value = inflight.value.filter((u) => u.id !== id)
      return
    }
    patchInflight(id, { status: 'error', error: e?.message || '上传失败' })
    message.error(e?.message || '上传失败')
  }
}

function pauseUpload(id: string) {
  const row = inflight.value.find((u) => u.id === id)
  if (!row || row.status !== 'uploading') return
  row.controller.abort('pause')
}

async function resumeUpload(id: string) {
  const row = inflight.value.find((u) => u.id === id)
  if (!row || (row.status !== 'paused' && row.status !== 'error')) return
  const prev = running.get(id)
  if (prev) await Promise.allSettled([prev])
  const latest = inflight.value.find((u) => u.id === id)
  if (!latest || latest.status === 'uploading') return
  if (!latest.file) {
    resumePickId = latest.id
    const input = resumeInputRef.value
    if (input) {
      input.value = ''
      input.click()
    }
    return
  }
  void enqueueUpload(latest.file, id)
}

function onResumeFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const id = resumePickId
  resumePickId = null
  if (!file || !id) return
  const row = inflight.value.find((u) => u.id === id)
  if (!row) return
  const expectSize = row.sizeBytes ?? row.file?.size
  if (file.name !== row.name || (expectSize != null && file.size !== expectSize)) {
    message.error(`请选择同一个文件「${row.name}」`)
    return
  }
  patchInflight(id, { file })
  void enqueueUpload(file, id)
}

async function restorePending() {
  try {
    const res = await kbApi.listUploads({
      noteId: props.noteId || undefined,
      unbound: !props.noteId
    })
    const seen = new Set(inflight.value.map((u) => u.uploadId).filter(Boolean) as string[])
    const extra: InflightItem[] = []
    for (const s of res.data || []) {
      const uid = String(s.uploadId)
      if (!uid || seen.has(uid)) continue
      seen.add(uid)
      const total = s.totalBytes || 0
      const uploaded = s.uploadedBytes || 0
      extra.push({
        id: `srv-${uid}`,
        name: s.originalName,
        percent: total ? Math.min(99, Math.floor((uploaded / total) * 100)) : 0,
        status: 'paused',
        file: takeKbUploadFile(uid),
        sizeBytes: total,
        controller: new AbortController(),
        uploadId: uid
      })
    }
    if (extra.length) {
      inflight.value = [...inflight.value, ...extra]
    }
  } catch {
    /* 未登录或接口不可用时忽略 */
  }
}

async function cancelUpload(id: string) {
  const row = inflight.value.find((u) => u.id === id)
  if (!row) return
  row.controller.abort('cancel')
  inflight.value = inflight.value.filter((u) => u.id !== id)
  if (row.uploadId) {
    dropKbUploadFile(row.uploadId)
    await kbApi.abortUpload(row.uploadId)
  }
  kbApi.clearUploadResume(row.file, props.noteId || undefined)
  message.success(`已取消「${row.name}」`)
}

async function flushUploads() {
  const jobs = [...running.values()]
  if (jobs.length) {
    await Promise.allSettled(jobs)
  }
}

function download(f: KbFileItem) {
  const base = kbMediaUrl(f.contentPath)
  const sep = base.includes('?') ? '&' : '?'
  triggerNativeDownload(`${base}${sep}download=true`, f.originalName || 'file')
}

async function fetchArrayBuffer(url: string): Promise<ArrayBuffer> {
  const res = await fetch(url, { credentials: 'same-origin' })
  if (!res.ok) {
    let msg = `加载失败 (${res.status})`
    try {
      const j = await res.json()
      if (j?.message) msg = j.message
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }
  return res.arrayBuffer()
}

async function preview(f: KbFileItem) {
  previewFile.value = f
  previewOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  previewMode.value = 'none'
  previewSrc.value = ''
  previewHtml.value = ''
  previewRows.value = []
  revokeObjectUrl()

  const name = (f.originalName || '').toLowerCase()
  const url = kbMediaUrl(f.contentPath)

  try {
    if (f.kind === 'image' || /\.(png|jpe?g|gif|webp|bmp)$/i.test(name)) {
      previewMode.value = 'image'
      previewSrc.value = url
      return
    }
    if (f.kind === 'video' || /\.(mp4|webm|mov)$/i.test(name)) {
      previewMode.value = 'video'
      previewSrc.value = url
      return
    }
    if (f.kind === 'pdf' || name.endsWith('.pdf')) {
      previewMode.value = 'pdf'
      previewSrc.value = url
      return
    }
    if (name.endsWith('.docx')) {
      const buf = await fetchArrayBuffer(url)
      previewMode.value = 'docx'
      previewLoading.value = false
      await nextTick()
      if (!docxHost.value) {
        throw new Error('预览容器未就绪')
      }
      docxHost.value.innerHTML = ''
      // 按需加载，避免拖垮路由首包
      const { renderAsync } = await import('docx-preview')
      await renderAsync(buf, docxHost.value, undefined, {
        className: 'kb-docx',
        inWrapper: true,
        ignoreWidth: false,
        ignoreHeight: false,
        breakPages: true,
        useBase64URL: true,
        renderHeaders: true,
        renderFooters: true,
        renderFootnotes: true
      })
      return
    }
    if (name.endsWith('.xlsx') || name.endsWith('.xls')) {
      const buf = await fetchArrayBuffer(url)
      const XLSX = await import('xlsx')
      const wb = XLSX.read(buf, { type: 'array' })
      const sheet = wb.Sheets[wb.SheetNames[0]]
      const rows = XLSX.utils.sheet_to_json<(string | number)[]>(sheet, {
        header: 1
      }) as (string | number)[][]
      previewMode.value = 'table'
      previewRows.value = rows
        .slice(0, 300)
        .map((r) => (r || []).map((c) => (c == null ? '' : String(c))))
      return
    }
    previewMode.value = 'none'
  } catch (e: any) {
    previewError.value = e?.message || '预览失败'
    previewMode.value = 'none'
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  previewOpen.value = false
  previewFile.value = null
  revokeObjectUrl()
  if (docxHost.value) docxHost.value.innerHTML = ''
}

async function remove(f: KbFileItem) {
  try {
    await kbApi.deleteFile(f.id)
    message.success('已删除')
    await reload()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onBeforeUnmount(() => {
  revokeObjectUrl()
  for (const u of inflight.value) {
    if (u.status === 'uploading') {
      u.controller.abort('pause')
    }
  }
})

defineExpose({ reload, flushUploads })
</script>

<style scoped lang="scss">
.resume-file-input {
  display: none;
}

.file-panel {
  border-top: 1px solid var(--border-color);
  padding: 10px 12px 12px;
  background: var(--surface-1);
  transition: background 0.15s, box-shadow 0.15s;

  &.is-dragover {
    background: color-mix(in srgb, var(--primary-color) 8%, var(--surface-1));
    box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--primary-color) 45%, transparent);
  }
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  gap: 8px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.drop-hint {
  font-size: 12px;
}

.title {
  font-size: 12px;
  font-weight: 650;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.hint,
.drop-zone-empty {
  font-size: 12px;
  padding: 12px 8px;
  border: 1px dashed var(--border-color);
  border-radius: 10px;
  text-align: center;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 160px;
  overflow: auto;
}

.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid color-mix(in srgb, var(--border-color) 70%, transparent);
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  cursor: pointer;
}

.kind {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--surface-3);
  color: var(--text-secondary);
  flex-shrink: 0;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.size {
  font-size: 11px;
  flex-shrink: 0;
}

.up-progress {
  width: 120px;
  flex-shrink: 0;
}

.inflight-list .item {
  flex-wrap: wrap;
  gap: 6px;
}

.ops {
  flex-shrink: 0;
}

.preview-box {
  min-height: 360px;
  max-height: 72vh;
  overflow: auto;
}

.preview-box.error {
  color: var(--error-color, #ef4444);
}

.pv-img,
.pv-video {
  max-width: 100%;
  display: block;
  margin: 0 auto;
}

.pv-iframe {
  width: 100%;
  height: 72vh;
  border: none;
  background: #525659;
}

.pv-docx {
  min-height: 360px;
  background: #f0f0f0;
  padding: 8px;

  :deep(.kb-docx-wrapper) {
    background: #f0f0f0;
    padding: 12px 0;
  }

  :deep(.kb-docx) {
    background: #fff;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.12);
    margin: 0 auto 16px;
    padding: 24px 32px;
  }

  :deep(section.kb-docx) {
    min-height: 200px;
  }
}

.pv-html {
  font-size: 14px;
  line-height: 1.6;
}

.pv-table-wrap {
  overflow: auto;
}

.pv-table {
  border-collapse: collapse;
  width: 100%;
  font-size: 12px;
}

.pv-table td {
  border: 1px solid var(--border-color);
  padding: 4px 8px;
  white-space: nowrap;
}

.muted {
  color: var(--text-secondary);
}
</style>
