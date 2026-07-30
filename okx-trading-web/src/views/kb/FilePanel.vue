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
        <a-upload
          :show-upload-list="false"
          :disabled="disabled || uploading"
          :before-upload="beforeUpload"
          multiple
        >
          <a-button size="small" type="primary" ghost :loading="uploading" :disabled="disabled">
            选择文件
          </a-button>
        </a-upload>
      </div>
    </div>
    <div v-if="!noteId" class="hint muted">
      尚未保存笔记：拖入的文件会先上传，保存笔记后自动关联。
    </div>
    <div v-else-if="loading" class="hint muted">加载中…</div>
    <div v-else-if="!files.length" class="drop-zone-empty muted">
      拖拽 Word / Excel / PDF / 图片等到此处，或点「选择文件」
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
        <!-- PDF：用 blob: URL，避免 iframe 直接打 API 被拒 / 鉴权失败 -->
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
import { kbApi, kbMediaUrl, type KbFileItem } from '@/api/kb.api'

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
const uploading = ref(false)
const dragOver = ref(false)
let dragDepth = 0

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
  () => {
    reload()
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
  const arr = Array.from(list)
  for (const file of arr) {
    await doUpload(file)
  }
}

function beforeUpload(file: File) {
  void doUpload(file)
  return false
}

async function doUpload(file: File) {
  uploading.value = true
  try {
    const res = await kbApi.uploadFile(file, props.noteId || undefined)
    if (!props.noteId) {
      emit('pendingUploaded', res.data.id)
      message.success(`已接收「${file.name}」，保存笔记后自动关联`)
    } else {
      message.success(`已添加「${file.name}」`)
      await reload()
    }
  } catch (e: any) {
    message.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

function download(f: KbFileItem) {
  const base = kbMediaUrl(f.contentPath)
  const sep = base.includes('?') ? '&' : '?'
  window.open(`${base}${sep}download=true`, '_blank')
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
      // 图片也走 blob，避免 token 链接偶发问题
      const buf = await fetchArrayBuffer(url)
      const blob = new Blob([buf], { type: f.contentType || 'image/png' })
      objectUrl = URL.createObjectURL(blob)
      previewMode.value = 'image'
      previewSrc.value = objectUrl
      return
    }
    if (f.kind === 'video' || /\.(mp4|webm|mov)$/i.test(name)) {
      const buf = await fetchArrayBuffer(url)
      const blob = new Blob([buf], { type: f.contentType || 'video/mp4' })
      objectUrl = URL.createObjectURL(blob)
      previewMode.value = 'video'
      previewSrc.value = objectUrl
      return
    }
    if (f.kind === 'pdf' || name.endsWith('.pdf')) {
      const buf = await fetchArrayBuffer(url)
      const blob = new Blob([buf], { type: 'application/pdf' })
      objectUrl = URL.createObjectURL(blob)
      // Chrome PDF 查看器
      previewMode.value = 'pdf'
      previewSrc.value = objectUrl
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
})

defineExpose({ reload })
</script>

<style scoped lang="scss">
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
