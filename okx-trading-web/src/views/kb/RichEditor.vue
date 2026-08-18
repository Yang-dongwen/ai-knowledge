<template>
  <div class="rich-wrap" :class="{ disabled, booting }">
    <Toolbar
      v-if="editorRef"
      class="rich-toolbar"
      :editor="editorRef"
      :default-config="toolbarConfig"
      mode="default"
    />
    <Editor
      class="rich-editor"
      v-model="html"
      :default-config="editorConfig"
      mode="default"
      @onCreated="onCreated"
      @onChange="onChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import {
  kbApi,
  kbMediaUrl,
  injectKbMediaTokens,
  stripKbMediaTokens
} from '@/api/kb.api'

const props = defineProps<{
  modelValue: string
  noteId?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [v: string]
  uploaded: [fileId: string]
  /** setHtml 完成，父级可关掉快速预览壳 */
  ready: []
}>()

const editorRef = shallowRef<IDomEditor | null>(null)
const html = shallowRef('')
/** 首次灌入正文中 */
const booting = shallowRef(true)

let syncing = false
let lastEmittedClean = ''
let emitTimer: ReturnType<typeof setTimeout> | null = null
let setHtmlToken = 0
const EMIT_DEBOUNCE_MS = 280
/** 超过此长度则让出主线程再 setHtml，先保证快速预览已绘制 */
const DEFER_SET_HTML_CHARS = 8_000

const toolbarConfig: Partial<IToolbarConfig> = {
  excludeKeys: ['fullScreen']
}

const editorConfig: Partial<IEditorConfig> = {
  placeholder: '第一行为标题，下方写正文… 可拖入图片/视频',
  readOnly: !!props.disabled,
  hoverbarKeys: {
    text: { menuKeys: ['bold', 'through', 'color', 'bgColor', 'clearStyle'] },
    link: { menuKeys: ['editLink', 'unLink', 'viewLink'] },
    image: { menuKeys: ['imageWidth30', 'imageWidth50', 'imageWidth100', 'deleteImage'] },
    video: { menuKeys: ['editVideoSize', 'deleteVideo'] }
  },
  MENU_CONF: {
    uploadImage: {
      fieldName: 'file',
      maxFileSize: 10 * 1024 * 1024,
      allowedFileTypes: ['image/*'],
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          const res = await kbApi.uploadFile(file, props.noteId || undefined)
          const url = kbMediaUrl(res.data.contentPath)
          insertFn(url, res.data.originalName || 'image', url)
          emit('uploaded', res.data.id)
          flushEmit()
        } catch (e: any) {
          message.error(e?.message || '图片上传失败')
        }
      }
    },
    uploadVideo: {
      fieldName: 'file',
      maxFileSize: 100 * 1024 * 1024,
      allowedFileTypes: ['video/*'],
      async customUpload(file: File, insertFn: (url: string, poster?: string) => void) {
        try {
          const res = await kbApi.uploadFile(file, props.noteId || undefined)
          const url = kbMediaUrl(res.data.contentPath)
          insertFn(url)
          emit('uploaded', res.data.id)
          flushEmit()
        } catch (e: any) {
          message.error(e?.message || '视频上传失败')
        }
      }
    }
  }
}

function scheduleEmit(raw: string) {
  if (emitTimer) clearTimeout(emitTimer)
  emitTimer = setTimeout(() => {
    emitTimer = null
    const clean = stripKbMediaTokens(raw || '')
    if (clean === lastEmittedClean) return
    lastEmittedClean = clean
    emit('update:modelValue', clean)
  }, EMIT_DEBOUNCE_MS)
}

function flushEmit() {
  if (emitTimer) {
    clearTimeout(emitTimer)
    emitTimer = null
  }
  const ed = editorRef.value
  if (!ed) return
  const raw = ed.getHtml()
  html.value = raw
  const clean = stripKbMediaTokens(raw || '')
  lastEmittedClean = clean
  emit('update:modelValue', clean)
}

/** 让浏览器先绘制快速预览层，再执行昂贵的 setHtml */
function runAfterPaint(fn: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(fn)
  })
}

function applyDisplayHtml(display: string, opts?: { forceDefer?: boolean }) {
  const ed = editorRef.value
  if (!ed) return
  const token = ++setHtmlToken
  const len = display?.length || 0
  const defer = opts?.forceDefer || len >= DEFER_SET_HTML_CHARS

  const run = () => {
    if (token !== setHtmlToken || !editorRef.value) return
    syncing = true
    booting.value = true
    const t0 = performance.now()
    try {
      editorRef.value.setHtml(display || '<p><br></p>')
      html.value = editorRef.value.getHtml()
    } finally {
      const cost = Math.round(performance.now() - t0)
      requestAnimationFrame(() => {
        if (token !== setHtmlToken) return
        syncing = false
        booting.value = false
        if (cost >= 50) {
          console.info(`[kb] RichEditor setHtml ${cost}ms chars=${len}`)
        }
        emit('ready')
      })
    }
  }

  if (defer) {
    // 空闲或超时后再灌入，优先保证壳层可见
    const ric = (window as any).requestIdleCallback as
      | undefined
      | ((cb: () => void, opts?: { timeout: number }) => number)
    if (typeof ric === 'function') {
      ric(() => runAfterPaint(run), { timeout: 120 })
    } else {
      setTimeout(() => runAfterPaint(run), 32)
    }
  } else {
    runAfterPaint(run)
  }
}

function disableNativeChecks(root: ParentNode | null | undefined) {
  if (!root || !('querySelector' in root)) return
  const el = root.querySelector('[contenteditable="true"]') as HTMLElement | null
  if (!el) return
  el.setAttribute('spellcheck', 'false')
  el.setAttribute('autocorrect', 'off')
  el.setAttribute('autocapitalize', 'off')
}

function onCreated(editor: IDomEditor) {
  editorRef.value = editor
  requestAnimationFrame(() => {
    disableNativeChecks(document.querySelector('.rich-wrap'))
  })
  if (props.disabled) editor.disable()
  const clean = stripKbMediaTokens(props.modelValue || '')
  lastEmittedClean = clean
  const display = injectKbMediaTokens(clean)
  // 空文档立刻 ready；有内容则 defer setHtml
  if (!clean || clean === '<p><br></p>' || clean === '<p></p>') {
    syncing = true
    try {
      editor.setHtml('<p><br></p>')
      html.value = editor.getHtml()
    } finally {
      syncing = false
      booting.value = false
      emit('ready')
    }
    return
  }
  applyDisplayHtml(display, { forceDefer: true })
}

function onChange(editor: IDomEditor) {
  if (syncing) return
  const h = editor.getHtml()
  html.value = h
  scheduleEmit(h)
}

watch(
  () => props.modelValue,
  (v) => {
    const cleanIncoming = stripKbMediaTokens(v || '')
    if (cleanIncoming === lastEmittedClean) return
    lastEmittedClean = cleanIncoming
    const display = injectKbMediaTokens(cleanIncoming)
    html.value = display
    if (!editorRef.value) return
    applyDisplayHtml(display, { forceDefer: (cleanIncoming?.length || 0) >= DEFER_SET_HTML_CHARS })
  }
)

watch(
  () => props.disabled,
  (d) => {
    const ed = editorRef.value
    if (!ed) return
    if (d) ed.disable()
    else ed.enable()
  }
)

onBeforeUnmount(() => {
  setHtmlToken++
  if (emitTimer) {
    clearTimeout(emitTimer)
    emitTimer = null
  }
  // 禁止 unmount 时 flushEmit：
  // 父级「Markdown 新建」会先清空 editContent，再 v-if 卸载本组件；
  // 若此处 getHtml 回写，会把上一条正文重新塞进新建页。
  // 换笔记/保存前由父级显式调用 flushEmit。
  const ed = editorRef.value
  if (ed) {
    try {
      ed.destroy()
    } catch {
      /* ignore */
    }
    editorRef.value = null
  }
})

defineExpose({ flushEmit })
</script>

<style scoped lang="scss">
.rich-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  overflow: hidden;
  background: var(--surface-1);

  &.disabled {
    opacity: 0.85;
  }

  &.booting {
    /* setHtml 期间减少重绘干扰 */
    pointer-events: none;
  }
}

.rich-toolbar {
  border-bottom: 1px solid var(--border-color) !important;
}

.rich-editor {
  flex: 1;
  min-height: 0;
  overflow-y: auto;

  :deep(.w-e-text-container) {
    background: var(--surface-1) !important;
    color: var(--text-primary) !important;
  }

  :deep(.w-e-text-container [contenteditable='true']) {
    color: var(--text-primary);
  }

  :deep(.w-e-scroll) {
    min-height: 280px;
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
    content-visibility: auto;
  }

  :deep(video) {
    max-width: 100%;
  }

  /* 文档内标题：首行 H1 + 横线分隔正文 */
  :deep(h1) {
    font-size: 1.85em;
    font-weight: 700;
    line-height: 1.3;
    margin: 0.1em 0 0;
    padding: 0.15em 0 0.55em;
    border: none;
    letter-spacing: -0.02em;
    color: var(--text-primary, inherit);
  }

  /* 标题下方独立横线（与正文拉开） */
  :deep(h1 + *) {
    margin-top: 0.85em !important;
    padding-top: 0.85em;
    border-top: 1px solid var(--border-color, #e2e8f0);
  }

  :deep(h2) {
    font-size: 1.35em;
    font-weight: 650;
    margin: 1em 0 0.4em;
  }
}
</style>
