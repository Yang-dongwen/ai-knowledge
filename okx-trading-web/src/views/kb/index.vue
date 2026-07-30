<template>
  <div class="kb-workspace">
    <!-- 左：分类 -->
    <aside class="kb-sidebar">
      <div class="side-head">
        <span class="side-title">分类</span>
        <a-button type="text" size="small" class="icon-action" @click="openCreateCategory()">
          <PlusOutlined />
        </a-button>
      </div>
      <button
        type="button"
        class="cat-item"
        :class="{ active: !filterCategoryId && !filterTagId }"
        @click="selectAll"
      >
        <FolderOpenOutlined />
        <span>全部笔记</span>
      </button>
      <button
        type="button"
        class="cat-item"
        :class="{ active: filterCategoryId === '__none__' }"
        @click="selectUncategorized"
      >
        <InboxOutlined />
        <span>未分类</span>
      </button>
      <div class="cat-tree">
        <template v-for="node in flatCategories" :key="node.id">
          <div
            class="cat-row"
            :style="{ paddingLeft: `${12 + node.depth * 14}px` }"
          >
            <button
              type="button"
              class="cat-item flex-1"
              :class="{ active: filterCategoryId === node.id }"
              @click="selectCategory(node.id)"
            >
              <FolderOutlined />
              <span class="cat-name">{{ node.name }}</span>
            </button>
            <a-dropdown :trigger="['click']">
              <button type="button" class="cat-more" @click.stop>
                <MoreOutlined />
              </button>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="openCreateCategory(node.id)">添加子分类</a-menu-item>
                  <a-menu-item @click="openRenameCategory(node)">重命名</a-menu-item>
                  <a-menu-item danger @click="confirmDeleteCategory(node)">删除</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </template>
      </div>

      <div class="side-head tags-head">
        <span class="side-title">标签</span>
        <a-button type="text" size="small" class="icon-action" @click="openCreateTag">
          <PlusOutlined />
        </a-button>
      </div>
      <div class="tag-list">
        <button
          v-for="tag in tags"
          :key="tag.id"
          type="button"
          class="tag-chip"
          :class="{ active: filterTagId === tag.id }"
          @click="toggleTagFilter(tag.id)"
        >
          <span>#{{ tag.name }}</span>
          <span class="tag-count">{{ tag.noteCount ?? 0 }}</span>
        </button>
        <div v-if="!tags.length" class="side-empty">暂无标签</div>
      </div>

      <!-- 左下角：回收站 -->
      <div class="trash-footer">
        <button
          type="button"
          class="trash-entry"
          :class="{ active: trashMode }"
          @click="toggleTrashMode"
        >
          <DeleteOutlined />
          <span>回收站</span>
          <span v-if="trashCount > 0" class="trash-badge">{{ trashCount > 99 ? '99+' : trashCount }}</span>
        </button>
      </div>
    </aside>

    <!-- 中：列表 -->
    <section class="kb-list-pane">
      <div class="list-toolbar">
        <a-input-search
          v-model:value="keyword"
          allow-clear
          :placeholder="trashMode ? '搜索回收站…' : '搜索标题或正文'"
          class="search-input"
          @search="reloadNotes"
        />
        <template v-if="trashMode">
          <a-popconfirm
            title="清空回收站？将永久删除全部笔记及附件（含 R2 对象），不可恢复。"
            ok-text="清空"
            ok-type="danger"
            cancel-text="取消"
            :disabled="!total"
            @confirm="emptyTrash"
          >
            <a-button danger :disabled="!total || emptying">清空回收站</a-button>
          </a-popconfirm>
        </template>
        <a-button v-else type="primary" @click="openCreateNote">
          <template #icon><PlusOutlined /></template>
          新建
        </a-button>
      </div>
      <div class="list-meta">
        <span v-if="trashMode" class="trash-mode-label">回收站 · {{ total }} 条</span>
        <span v-else>共 {{ total }} 条</span>
      </div>
      <div v-if="listLoading" class="list-loading">
        <a-spin />
      </div>
      <div v-else-if="!notes.length" class="list-empty">
        <EmptyState
          v-if="trashMode"
          title="回收站为空"
          description="删除的笔记会出现在这里"
        />
        <EmptyState v-else title="还没有笔记" description="点击「新建」开始记录" />
      </div>
      <div v-else class="note-list">
        <button
          v-for="n in notes"
          :key="n.id"
          type="button"
          class="note-card"
          :class="{ active: selectedId === n.id, deleted: n.deleted || trashMode }"
          @click="selectNote(n.id)"
        >
          <div class="note-card-title">
            <PushpinOutlined v-if="n.pinned" class="pin-icon" />
            <span>{{ n.title || '未命名笔记' }}</span>
            <a-tag class="fmt-tag" :color="n.contentFormat === 'markdown' ? 'blue' : 'green'">
              {{ n.contentFormat === 'markdown' ? 'MD' : '富文本' }}
            </a-tag>
          </div>
          <div class="note-card-snippet">{{ n.snippet || '暂无正文' }}</div>
          <div class="note-card-foot">
            <span v-if="n.categoryName" class="meta-cat">{{ n.categoryName }}</span>
            <span
              v-for="t in n.tags?.slice(0, 3) || []"
              :key="t.id"
              class="meta-tag"
            >#{{ t.name }}</span>
            <span class="meta-time">{{ formatTime(n.updatedAt) }}</span>
          </div>
        </button>
      </div>
      <div v-if="total > pageSize" class="list-pager">
        <a-pagination
          size="small"
          :current="page + 1"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          @change="onPageChange"
        />
      </div>
    </section>

    <!-- 右：编辑 -->
    <section class="kb-editor-pane">
      <template v-if="!selectedId && !isCreating">
        <div class="editor-empty">
          <BookOutlined class="editor-empty-icon" />
          <p>选择一条笔记，或点击「新建」并选择编辑格式</p>
          <div class="empty-create-actions">
            <a-button type="primary" @click="openCreateNoteWith('html')">富文本新建</a-button>
            <a-button @click="openCreateNoteWith('markdown')">Markdown 新建</a-button>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="editor-toolbar">
          <div class="toolbar-left">
            <span class="doc-title-hint muted" :title="editTitle">{{ editTitle || '未命名笔记' }}</span>
            <a-tag :color="editFormat === 'markdown' ? 'blue' : 'green'" class="format-badge">
              {{ editFormat === 'markdown' ? 'Markdown' : '富文本' }}
            </a-tag>
          </div>
          <div class="editor-actions">
            <a-tooltip :title="editPinned ? '取消置顶' : '置顶'">
              <a-button
                type="text"
                :disabled="editDeleted"
                @click="togglePin"
              >
                <PushpinOutlined :class="{ 'pin-on': editPinned }" />
              </a-button>
            </a-tooltip>
            <template v-if="editDeleted || trashMode">
              <a-button type="primary" ghost :loading="saving" @click="restoreCurrent">
                恢复
              </a-button>
              <a-popconfirm
                title="永久删除？将删除笔记、附件及 R2/本地存储对象，不可恢复。"
                ok-text="永久删除"
                ok-type="danger"
                cancel-text="取消"
                @confirm="permanentDeleteCurrent"
              >
                <a-button danger :loading="saving">永久删除</a-button>
              </a-popconfirm>
            </template>
            <template v-else>
              <a-button @click="confirmConvertFormat">
                转为{{ editFormat === 'html' ? 'Markdown' : '富文本' }}
              </a-button>
              <a-button type="primary" :loading="saving" @click="saveNote">保存</a-button>
              <a-popconfirm
                v-if="selectedId"
                title="移入回收站？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="deleteCurrent"
              >
                <a-button danger>删除</a-button>
              </a-popconfirm>
            </template>
          </div>
        </div>
        <div class="editor-meta-row">
          <a-select
            v-model:value="editCategoryId"
            allow-clear
            placeholder="分类"
            class="meta-select"
            :disabled="editDeleted"
            :options="categoryOptions"
            @change="autoSave"
          />
          <a-select
            v-model:value="editTagIds"
            mode="multiple"
            allow-clear
            placeholder="标签"
            class="meta-select tags-select"
            :disabled="editDeleted"
            :options="tagOptions"
            @change="autoSave"
          />
          <a-radio-group
            v-if="editFormat === 'markdown'"
            v-model:value="viewMode"
            size="small"
            button-style="solid"
          >
            <a-radio-button value="edit">编辑</a-radio-button>
            <a-radio-button value="split">分栏</a-radio-button>
            <a-radio-button value="preview">预览</a-radio-button>
          </a-radio-group>
        </div>
        <div
          class="editor-body"
          :class="editFormat === 'html' ? 'mode-html' : `mode-${viewMode}`"
        >
          <!-- 富文本：先快速预览壳，再挂编辑器，避免 setHtml 卡死首帧 -->
          <div v-if="editFormat === 'html'" class="html-stack">
            <div
              v-show="htmlShellVisible"
              class="html-fast-shell"
              :class="{ alone: !richEditorActive }"
            >
              <div v-if="contentLoading" class="shell-loading">加载正文中…</div>
              <div
                v-else
                class="shell-body"
                v-html="fastShellHtml || '<p class=&quot;muted&quot;>（无正文）</p>'"
              />
              <div v-if="richEditorActive && !editDeleted" class="shell-tip muted">
                编辑器准备中…
              </div>
            </div>
            <!-- 回收站只读：不挂重型编辑器 -->
            <RichEditor
              v-if="richEditorActive && !editDeleted"
              ref="richEditorRef"
              v-model="editContent"
              :note-id="selectedId"
              :disabled="false"
              @update:model-value="onContentTyped"
              @uploaded="onPendingFile"
              @ready="onRichEditorReady"
            />
          </div>
          <template v-else>
            <div
              v-if="viewMode !== 'preview'"
              class="md-doc-edit"
              :class="{ 'md-drag-over': mdDragOver }"
              @dragenter.prevent="onMdDragEnter"
              @dragover.prevent="onMdDragOver"
              @dragleave.prevent="onMdDragLeave"
              @drop.prevent="onMdDrop"
            >
              <input
                v-model="mdTitleLine"
                class="md-doc-title"
                type="text"
                maxlength="200"
                placeholder="标题"
                :disabled="editDeleted"
                @input="onMarkdownTitleInput"
                @blur="onMarkdownBlur"
              />
              <div class="doc-title-rule" aria-hidden="true" />
              <div v-if="!editDeleted" class="md-toolbar">
                <input
                  ref="mdImageInputRef"
                  type="file"
                  accept="image/*"
                  multiple
                  class="md-file-input"
                  @change="onMdImageInputChange"
                />
                <a-button
                  size="small"
                  type="primary"
                  ghost
                  :loading="mdImageUploading"
                  @click="triggerMdImagePick"
                >
                  插入图片
                </a-button>
                <span class="md-toolbar-tip muted">
                  支持按钮上传 / 粘贴 / 拖入图片，语法：![说明](/api/v1/kb/files/…/content)
                </span>
              </div>
              <a-textarea
                ref="mdBodyAreaRef"
                v-model:value="mdBodyText"
                class="md-input md-body-input"
                placeholder="正文从这里开始… 可粘贴或拖入图片"
                :disabled="editDeleted"
                :auto-size="false"
                @blur="onMarkdownBlur"
                @input="onMarkdownBodyInput"
                @paste="onMdPaste"
              />
            </div>
            <div
              v-if="viewMode !== 'edit'"
              class="md-preview doc-preview"
              v-html="previewHtml"
            />
          </template>
        </div>
        <!-- 附件面板略延迟，不与正文首屏抢主线程 -->
        <FilePanel
          v-if="filePanelActive"
          ref="filePanelRef"
          :note-id="selectedId"
          :disabled="editDeleted"
          @pending-uploaded="onPendingFile"
        />
        <div class="editor-status">
          <span v-if="saveHint">{{ saveHint }}</span>
          <span v-else-if="editUpdatedAt">更新于 {{ formatTime(editUpdatedAt) }}</span>
        </div>
      </template>
    </section>

    <!-- 新建分类 -->
    <a-modal
      v-model:open="catModalOpen"
      :title="catModalParentId ? '添加子分类' : '新建分类'"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="catModalLoading"
      @ok="submitCategory"
    >
      <a-input v-model:value="catModalName" placeholder="分类名称" allow-clear @press-enter="submitCategory" />
    </a-modal>

    <!-- 重命名分类 -->
    <a-modal
      v-model:open="renameModalOpen"
      title="重命名分类"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="renameModalLoading"
      @ok="submitRenameCategory"
    >
      <a-input v-model:value="renameModalName" allow-clear @press-enter="submitRenameCategory" />
    </a-modal>

    <!-- 新建标签 -->
    <a-modal
      v-model:open="tagModalOpen"
      title="新建标签"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="tagModalLoading"
      @ok="submitTag"
    >
      <a-input v-model:value="tagModalName" placeholder="标签名称" allow-clear @press-enter="submitTag" />
    </a-modal>

    <!-- 新建：先选格式 -->
    <a-modal
      v-model:open="createFormatOpen"
      title="新建笔记"
      :footer="null"
      width="480px"
      destroy-on-close
    >
      <p class="create-format-tip">请先选择编辑格式，选定后编辑过程中格式固定；如需更换可用工具栏「转为…」并转换内容。</p>
      <div class="create-format-cards">
        <button type="button" class="format-card" @click="openCreateNoteWith('html')">
          <div class="format-card-title">富文本</div>
          <div class="format-card-desc">适合大多数人：所见即所得，可插入图片/视频、排版简单直观</div>
          <div class="format-card-tag recommended">推荐</div>
        </button>
        <button type="button" class="format-card" @click="openCreateNoteWith('markdown')">
          <div class="format-card-title">Markdown</div>
          <div class="format-card-desc">适合熟悉 MD 语法的人：纯文本书写，适合技术笔记与代码块</div>
        </button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  BookOutlined,
  DeleteOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MoreOutlined,
  PlusOutlined,
  PushpinOutlined
} from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import EmptyState from '@/components/EmptyState.vue'
import {
  kbApi,
  injectKbMediaTokens,
  mdImageSyntax,
  stripKbMediaTokens,
  stripKbMediaTokensAll,
  type KbCategory,
  type KbContentFormat,
  type KbNoteItem,
  type KbTag
} from '@/api/kb.api'
import {
  emptyHtmlDoc,
  emptyMarkdownDoc,
  ensureHtmlHasTitle,
  ensureMarkdownHasTitle,
  extractTitle,
  joinMarkdownDoc,
  splitMarkdownDoc
} from './kbDocTitle'

/** 异步加载，避免 turndown/wangeditor/docx 阻塞首屏路由 */
const RichEditor = defineAsyncComponent(() => import('./RichEditor.vue'))
const FilePanel = defineAsyncComponent(() => import('./FilePanel.vue'))

/** 轻量 MD 渲染（仅预览用）；按需加载 markdown-it */
let mdRender: ((src: string) => string) | null = null
async function ensureMd() {
  if (mdRender) return mdRender
  const mod = await import('markdown-it')
  const MarkdownIt = mod.default
  const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
  mdRender = (src: string) => md.render(src || '')
  return mdRender
}

/** 简易 HTML → 纯文本/近似 Markdown，避免 turndown 依赖预构建失败拖垮整页 */
function htmlToMarkdownLite(html: string): string {
  if (!html) return ''
  let s = html
  s = s.replace(/<br\s*\/?>/gi, '\n')
  s = s.replace(/<\/p>/gi, '\n\n')
  s = s.replace(/<\/div>/gi, '\n')
  s = s.replace(/<\/h([1-6])>/gi, '\n\n')
  s = s.replace(/<h([1-6])[^>]*>/gi, (_, n) => '#'.repeat(Number(n)) + ' ')
  s = s.replace(/<li[^>]*>/gi, '- ')
  s = s.replace(/<\/li>/gi, '\n')
  s = s.replace(/<strong[^>]*>([\s\S]*?)<\/strong>/gi, '**$1**')
  s = s.replace(/<b[^>]*>([\s\S]*?)<\/b>/gi, '**$1**')
  s = s.replace(/<em[^>]*>([\s\S]*?)<\/em>/gi, '*$1*')
  s = s.replace(/<code[^>]*>([\s\S]*?)<\/code>/gi, '`$1`')
  s = s.replace(/<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi, '[$2]($1)')
  s = s.replace(/<img[^>]*src="([^"]*)"[^>]*\/?>/gi, '![]($1)')
  s = s.replace(/<[^>]+>/g, '')
  s = s
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
  return s.replace(/\n{3,}/g, '\n\n').trim()
}

const categories = ref<KbCategory[]>([])
const tags = ref<KbTag[]>([])
const notes = ref<KbNoteItem[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = 20
const listLoading = ref(false)
const keyword = ref('')
const filterCategoryId = ref<string | null>(null)
const filterTagId = ref<string | null>(null)
/** 左侧回收站模式：只看软删笔记 */
const trashMode = ref(false)
const trashCount = ref(0)
const emptying = ref(false)

const selectedId = ref<string | null>(null)
const isCreating = ref(false)
const editTitle = ref('')
const editContent = ref('')
/** Markdown 编辑区：标题与正文视觉分离（仍合并进 editContent 存库） */
const mdTitleLine = ref('未命名笔记')
const mdBodyText = ref('')
const editFormat = ref<KbContentFormat>('html')
const editCategoryId = ref<string | undefined>(undefined)
const editTagIds = ref<string[]>([])
const editPinned = ref(false)
const editDeleted = ref(false)
const editUpdatedAt = ref<string | undefined>()
const saving = ref(false)
const saveHint = ref('')
const viewMode = ref<'edit' | 'split' | 'preview'>('split')
const dirty = ref(false)
const applying = ref(false)

const catModalOpen = ref(false)
const catModalName = ref('')
const catModalParentId = ref<string | null>(null)
const catModalLoading = ref(false)

const renameModalOpen = ref(false)
const renameModalName = ref('')
const renameModalId = ref<string | null>(null)
const renameModalLoading = ref(false)

const tagModalOpen = ref(false)
const tagModalName = ref('')
const tagModalLoading = ref(false)

/** 新建时先选格式 */
const createFormatOpen = ref(false)
/** 未关联笔记的附件 id（拖入时 note 尚未保存） */
const pendingFileIds = ref<string[]>([])
const filePanelRef = ref<{ reload: () => Promise<void> } | null>(null)
const richEditorRef = ref<{ flushEmit?: () => void } | null>(null)

/** 打开笔记：快速 HTML 壳 → 再挂富文本编辑器 */
const contentLoading = ref(false)
const htmlShellVisible = ref(false)
const richEditorActive = ref(false)
const filePanelActive = ref(false)
const fastShellHtml = ref('')
let editorBootTimer: ReturnType<typeof setTimeout> | null = null
let filePanelTimer: ReturnType<typeof setTimeout> | null = null
let openSeq = 0

/** Markdown 预览防抖 */
let mdPreviewTimer: ReturnType<typeof setTimeout> | null = null

function clearBootTimers() {
  if (editorBootTimer) {
    clearTimeout(editorBootTimer)
    editorBootTimer = null
  }
  if (filePanelTimer) {
    clearTimeout(filePanelTimer)
    filePanelTimer = null
  }
}

/** 先画快速预览，空闲后再挂 WangEditor（避免首帧卡死） */
function scheduleRichEditorBoot(seq: number) {
  richEditorActive.value = false
  htmlShellVisible.value = true
  if (editorBootTimer) clearTimeout(editorBootTimer)

  const boot = () => {
    if (seq !== openSeq) return
    // 只读（回收站）永不挂重型编辑器
    if (editDeleted.value) {
      richEditorActive.value = false
      htmlShellVisible.value = true
      return
    }
    richEditorActive.value = true
  }

  const ric = (window as any).requestIdleCallback as
    | undefined
    | ((cb: () => void, opts?: { timeout: number }) => number)
  if (typeof ric === 'function') {
    ric(boot, { timeout: 180 })
  } else {
    editorBootTimer = setTimeout(boot, 48)
  }

  // 附件再晚一点
  if (filePanelTimer) clearTimeout(filePanelTimer)
  filePanelTimer = setTimeout(() => {
    if (seq !== openSeq) return
    filePanelActive.value = true
  }, 120)
}

function onRichEditorReady() {
  // 编辑器灌入完成，撤掉快速壳
  htmlShellVisible.value = false
  if (saveHint.value === '加载正文中…' || saveHint.value === '编辑器准备中…') {
    saveHint.value = ''
  }
}

function updateFastShell(content: string, format: KbContentFormat) {
  if (format === 'html') {
    fastShellHtml.value = injectKbMediaTokens(content || '')
  } else {
    fastShellHtml.value = ''
  }
}

function onPendingFile(fileId: string) {
  if (!pendingFileIds.value.includes(fileId)) {
    pendingFileIds.value.push(fileId)
  }
}

async function bindPendingFiles(noteId: string) {
  if (!pendingFileIds.value.length) return
  const ids = [...pendingFileIds.value]
  pendingFileIds.value = []
  for (const fid of ids) {
    try {
      await kbApi.bindFile(fid, noteId)
    } catch {
      /* 忽略单条失败 */
    }
  }
  await filePanelRef.value?.reload?.()
}

interface FlatCat {
  id: string
  name: string
  depth: number
}

const flatCategories = computed(() => {
  const out: FlatCat[] = []
  const walk = (nodes: KbCategory[], depth: number) => {
    for (const n of nodes) {
      out.push({ id: n.id, name: n.name, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

const categoryOptions = computed(() =>
  flatCategories.value.map((c) => ({
    value: c.id,
    label: `${'— '.repeat(c.depth)}${c.name}`
  }))
)

const tagOptions = computed(() =>
  tags.value.map((t) => ({ value: t.id, label: t.name }))
)

const previewHtml = ref('')
const mdBodyAreaRef = ref<any>(null)
const mdImageInputRef = ref<HTMLInputElement | null>(null)
const mdImageUploading = ref(false)
const mdDragOver = ref(false)
let mdDragDepth = 0

function getMdTextareaEl(): HTMLTextAreaElement | null {
  const comp = mdBodyAreaRef.value
  if (!comp) return null
  const root = comp.$el as HTMLElement | undefined
  if (!root) return null
  if (root.tagName === 'TEXTAREA') return root as HTMLTextAreaElement
  return root.querySelector?.('textarea') || null
}

/** 在光标处插入 Markdown 片段 */
function insertMarkdownAtCursor(snippet: string) {
  const ta = getMdTextareaEl()
  const text = mdBodyText.value || ''
  if (!ta) {
    const pad = text && !text.endsWith('\n') ? '\n\n' : text ? '\n' : ''
    mdBodyText.value = text + pad + snippet + '\n'
  } else {
    const start = ta.selectionStart ?? text.length
    const end = ta.selectionEnd ?? text.length
    const before = text.slice(0, start)
    const after = text.slice(end)
    const needNlBefore = before.length > 0 && !before.endsWith('\n')
    const insert = (needNlBefore ? '\n' : '') + snippet + '\n'
    mdBodyText.value = before + insert + after
    const pos = before.length + insert.length
    requestAnimationFrame(() => {
      ta.focus()
      ta.setSelectionRange(pos, pos)
    })
  }
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
}

async function uploadImagesForMarkdown(files: File[]) {
  const images = files.filter((f) => f.type.startsWith('image/'))
  if (!images.length) {
    message.warning('请选择图片文件')
    return
  }
  mdImageUploading.value = true
  try {
    for (const file of images) {
      const res = await kbApi.uploadFile(file, selectedId.value || undefined)
      onPendingFile(res.data.id)
      const alt = (file.name || 'image').replace(/\.[^.]+$/, '')
      // 存库用干净路径；预览时 inject token
      insertMarkdownAtCursor(mdImageSyntax(res.data.contentPath, alt))
    }
    message.success(images.length > 1 ? `已插入 ${images.length} 张图片` : '已插入图片')
  } catch (e: any) {
    message.error(e?.message || '图片上传失败')
  } finally {
    mdImageUploading.value = false
  }
}

function triggerMdImagePick() {
  mdImageInputRef.value?.click()
}

function onMdImageInputChange(e: Event) {
  const input = e.target as HTMLInputElement
  const list = input.files
  if (list?.length) {
    void uploadImagesForMarkdown(Array.from(list))
  }
  input.value = ''
}

function onMdPaste(e: ClipboardEvent) {
  if (editDeleted.value) return
  const items = e.clipboardData?.items
  if (!items?.length) return
  const files: File[] = []
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    if (it.kind === 'file' && it.type.startsWith('image/')) {
      const f = it.getAsFile()
      if (f) files.push(f)
    }
  }
  if (!files.length) return
  e.preventDefault()
  void uploadImagesForMarkdown(files)
}

function onMdDragEnter() {
  if (editDeleted.value) return
  mdDragDepth++
  mdDragOver.value = true
}

function onMdDragOver() {
  if (editDeleted.value) return
  mdDragOver.value = true
}

function onMdDragLeave() {
  mdDragDepth = Math.max(0, mdDragDepth - 1)
  if (mdDragDepth === 0) mdDragOver.value = false
}

function onMdDrop(e: DragEvent) {
  mdDragDepth = 0
  mdDragOver.value = false
  if (editDeleted.value) return
  const list = e.dataTransfer?.files
  if (!list?.length) return
  void uploadImagesForMarkdown(Array.from(list))
}

async function renderMarkdownPreview(src: string) {
  try {
    const render = await ensureMd()
    // markdown-it 产出 <img src="/api/v1/kb/files/..."> 后再注入 JWT
    const raw = render(src || '')
    previewHtml.value = injectKbMediaTokens(raw)
  } catch {
    previewHtml.value = `<pre>${(src || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')}</pre>`
  }
}

function scheduleMarkdownPreview() {
  if (editFormat.value !== 'markdown') return
  // 仅编辑模式不渲染预览，避免无意义全量 markdown-it
  if (viewMode.value === 'edit') return
  if (mdPreviewTimer) clearTimeout(mdPreviewTimer)
  mdPreviewTimer = setTimeout(() => {
    mdPreviewTimer = null
    void renderMarkdownPreview(editContent.value)
  }, 320)
}

watch(editFormat, (f) => {
  if (f === 'html') {
    previewHtml.value = ''
    return
  }
  scheduleMarkdownPreview()
})

watch(viewMode, () => {
  if (editFormat.value === 'markdown') scheduleMarkdownPreview()
})

function syncTitleFromContent() {
  editTitle.value = extractTitle(editContent.value || '', editFormat.value)
}

function syncMarkdownFromParts() {
  editContent.value = joinMarkdownDoc(mdTitleLine.value, mdBodyText.value)
  editTitle.value = (mdTitleLine.value || '').trim() || '未命名笔记'
}

function loadMarkdownParts(md: string) {
  const { title, body } = splitMarkdownDoc(md || '')
  mdTitleLine.value = title
  mdBodyText.value = body
  editContent.value = joinMarkdownDoc(title, body)
  editTitle.value = title
}

function onContentTyped() {
  if (applying.value) return
  dirty.value = true
  saveHint.value = '未保存'
  // 首行/H1 同步为列表标题
  syncTitleFromContent()
}

function onMarkdownTitleInput() {
  if (applying.value) return
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
}

function onMarkdownBodyInput() {
  if (applying.value) return
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
}

function onMarkdownBlur() {
  syncMarkdownFromParts()
  if (mdPreviewTimer) {
    clearTimeout(mdPreviewTimer)
    mdPreviewTimer = null
    void renderMarkdownPreview(editContent.value)
  }
  autoSave()
}

function openCreateNote() {
  if (trashMode.value) {
    exitTrashMode()
    void reloadNotes()
  }
  createFormatOpen.value = true
}

function openCreateNoteWith(format: KbContentFormat) {
  createFormatOpen.value = false
  if (trashMode.value) {
    exitTrashMode()
  }
  void createNote(format)
}

/** Markdown ↔ HTML 内容转换（显式「转为…」时使用） */
async function convertContent(
  from: KbContentFormat,
  to: KbContentFormat,
  content: string
): Promise<string> {
  const src = content || ''
  if (from === to) return src
  if (from === 'markdown' && to === 'html') {
    const render = await ensureMd()
    return render(src)
  }
  if (from === 'html' && to === 'markdown') {
    return htmlToMarkdownLite(src)
  }
  return src
}

function confirmConvertFormat() {
  if (editDeleted.value) return
  const from = editFormat.value
  const to: KbContentFormat = from === 'html' ? 'markdown' : 'html'
  const labelFrom = from === 'html' ? '富文本' : 'Markdown'
  const labelTo = to === 'html' ? '富文本' : 'Markdown'
  Modal.confirm({
    title: `转为${labelTo}？`,
    content: `将把当前「${labelFrom}」内容转换为「${labelTo}」。复杂排版（表格/部分样式）可能略有损失，建议转换后检查再保存。`,
    okText: '转换',
    cancelText: '取消',
    async onOk() {
      applying.value = true
      try {
        // 先刷出编辑器未防抖内容
        richEditorRef.value?.flushEmit?.()
        // Markdown 编辑区先合并
        if (from === 'markdown') syncMarkdownFromParts()
        const converted = await convertContent(from, to, editContent.value)
        editFormat.value = to
        if (to === 'markdown') {
          loadMarkdownParts(
            ensureMarkdownHasTitle(converted, extractTitle(converted, 'html'))
          )
          viewMode.value = 'split'
          richEditorActive.value = false
          htmlShellVisible.value = false
          scheduleMarkdownPreview()
        } else {
          const html = ensureHtmlHasTitle(converted, mdTitleLine.value || editTitle.value)
          editContent.value = html
          editTitle.value = extractTitle(html, 'html')
          updateFastShell(html, 'html')
          htmlShellVisible.value = true
          scheduleRichEditorBoot(++openSeq)
        }
        dirty.value = true
        saveHint.value = '未保存（已转换格式）'
        message.success(`已转为${labelTo}，请检查内容后保存`)
      } catch (e: any) {
        message.error(e?.message || '转换失败')
      } finally {
        applying.value = false
      }
    }
  })
}

function formatTime(t?: string) {
  if (!t) return ''
  return dayjs(t).format('MM-DD HH:mm')
}

async function loadCategories() {
  const res = await kbApi.listCategories()
  categories.value = res.data || []
}

async function loadTags() {
  const res = await kbApi.listTags()
  tags.value = res.data || []
}

async function reloadTrashCount() {
  try {
    const res = await kbApi.trashCount()
    trashCount.value = Number(res.data?.count ?? 0)
  } catch {
    /* ignore */
  }
}

async function reloadNotes() {
  listLoading.value = true
  try {
    const res = await kbApi.listNotes({
      page: page.value,
      size: pageSize,
      categoryId:
        !trashMode.value && filterCategoryId.value && filterCategoryId.value !== '__none__'
          ? filterCategoryId.value
          : undefined,
      uncategorized: !trashMode.value && filterCategoryId.value === '__none__',
      tagId: !trashMode.value ? filterTagId.value || undefined : undefined,
      keyword: keyword.value || undefined,
      onlyDeleted: trashMode.value
    })
    notes.value = res.data?.items || []
    total.value = res.data?.total ?? 0
    if (trashMode.value) {
      trashCount.value = total.value
    } else {
      await reloadTrashCount()
    }
  } finally {
    listLoading.value = false
  }
}

function exitTrashMode() {
  if (!trashMode.value) return
  trashMode.value = false
  selectedId.value = null
  isCreating.value = false
  page.value = 0
}

function toggleTrashMode() {
  trashMode.value = !trashMode.value
  selectedId.value = null
  isCreating.value = false
  filterCategoryId.value = null
  filterTagId.value = null
  page.value = 0
  keyword.value = ''
  reloadNotes()
}

function selectAll() {
  exitTrashMode()
  filterCategoryId.value = null
  filterTagId.value = null
  page.value = 0
  reloadNotes()
}

function selectUncategorized() {
  exitTrashMode()
  filterCategoryId.value = '__none__'
  filterTagId.value = null
  page.value = 0
  reloadNotes()
}

function selectCategory(id: string) {
  exitTrashMode()
  filterCategoryId.value = id
  filterTagId.value = null
  page.value = 0
  reloadNotes()
}

function toggleTagFilter(id: string) {
  exitTrashMode()
  filterTagId.value = filterTagId.value === id ? null : id
  filterCategoryId.value = null
  page.value = 0
  reloadNotes()
}

function onPageChange(p: number) {
  page.value = p - 1
  reloadNotes()
}

async function selectNote(id: string) {
  if (dirty.value && !editDeleted.value) {
    try {
      await saveNote(true)
    } catch {
      /* keep going */
    }
  }
  const seq = ++openSeq
  clearBootTimers()
  isCreating.value = false
  selectedId.value = id
  pendingFileIds.value = []
  contentLoading.value = true
  richEditorActive.value = false
  filePanelActive.value = false
  htmlShellVisible.value = true
  fastShellHtml.value = ''

  // 先用列表缓存标题/摘要占位
  const cached = notes.value.find((n) => n.id === id)
  if (cached) {
    applying.value = true
    editTitle.value = cached.title || ''
    editContent.value = ''
    editFormat.value = cached.contentFormat === 'markdown' ? 'markdown' : 'html'
    editCategoryId.value = cached.categoryId || undefined
    editTagIds.value = (cached.tags || []).map((t) => t.id)
    editPinned.value = !!cached.pinned
    editDeleted.value = !!cached.deleted
    editUpdatedAt.value = cached.updatedAt
    saveHint.value = '加载正文中…'
    dirty.value = false
    Promise.resolve().then(() => {
      applying.value = false
    })
  }

  const t0 = performance.now()
  try {
    const res = (await kbApi.getNote(id)) as {
      data: import('@/api/kb.api').KbNoteItem
      headers?: Record<string, string>
    }
    if (seq !== openSeq) return
    const ms = Math.round(performance.now() - t0)
    const chars = res.data?.content?.length ?? 0
    const headers = res.headers || {}
    console.info(
      `[kb] GET /notes/${id} clientWait=${ms}ms contentChars=${chars}` +
        (headers['x-kb-query-ms'] != null ? ` serverQuery=${headers['x-kb-query-ms']}ms` : '') +
        (headers['x-kb-db-ms'] != null ? ` db=${headers['x-kb-db-ms']}ms` : '')
    )
    applyNote(res.data, seq)
    dirty.value = false
  } catch (e: any) {
    if (seq !== openSeq) return
    contentLoading.value = false
    message.error(e?.message || '加载笔记失败')
  }
}

function applyNote(n: KbNoteItem, seq = openSeq) {
  if (seq !== openSeq) return
  applying.value = true
  contentLoading.value = false
  editTitle.value = n.title || ''
  const format: KbContentFormat = n.contentFormat === 'markdown' ? 'markdown' : 'html'
  let cleanContent =
    format === 'markdown' ? n.content || '' : stripKbMediaTokens(n.content || '')
  // 旧数据：标题在独立字段时，补到正文首行 H1 / # 标题
  cleanContent =
    format === 'markdown'
      ? ensureMarkdownHasTitle(cleanContent, n.title)
      : ensureHtmlHasTitle(cleanContent, n.title)
  editFormat.value = format
  if (format === 'markdown') {
    loadMarkdownParts(cleanContent)
    const len = cleanContent.length
    viewMode.value = len > 8000 ? 'edit' : 'split'
  } else {
    editContent.value = cleanContent
    editTitle.value = extractTitle(cleanContent, format) || n.title || '未命名笔记'
    mdTitleLine.value = editTitle.value
    mdBodyText.value = ''
  }
  editCategoryId.value = n.categoryId || undefined
  editTagIds.value = (n.tags || []).map((t) => t.id)
  editPinned.value = !!n.pinned
  editDeleted.value = !!n.deleted
  editUpdatedAt.value = n.updatedAt
  saveHint.value = editFormat.value === 'html' && !n.deleted ? '编辑器准备中…' : ''
  dirty.value = false

  if (editFormat.value === 'html') {
    // ① 立刻填充快速预览（轻量 v-html）
    updateFastShell(cleanContent, 'html')
    htmlShellVisible.value = true
    // ② 空闲后再挂 WangEditor
    scheduleRichEditorBoot(seq)
  } else {
    richEditorActive.value = false
    htmlShellVisible.value = false
    fastShellHtml.value = ''
    filePanelActive.value = true
    if (viewMode.value !== 'edit') {
      scheduleMarkdownPreview()
    } else {
      previewHtml.value = ''
    }
  }

  Promise.resolve().then(() => {
    applying.value = false
  })
}

async function createNote(format: KbContentFormat = 'html') {
  if (dirty.value && !editDeleted.value) {
    try {
      // 保存前显式 flush，卸载编辑器时不再回写
      richEditorRef.value?.flushEmit?.()
      await saveNote(true)
    } catch {
      /* ignore */
    }
  } else {
    // 即使未标脏，也丢掉编辑器防抖缓冲，避免 unmount 残留
    richEditorRef.value?.flushEmit?.()
  }

  const seq = ++openSeq
  clearBootTimers()
  if (mdPreviewTimer) {
    clearTimeout(mdPreviewTimer)
    mdPreviewTimer = null
  }

  // 先卸掉富文本编辑器，再清空内容，避免 unmount 时序问题
  richEditorActive.value = false
  htmlShellVisible.value = false
  filePanelActive.value = false

  applying.value = true
  isCreating.value = true
  selectedId.value = null
  pendingFileIds.value = []
  contentLoading.value = false
  editTitle.value = '未命名笔记'
  if (format === 'html') {
    editContent.value = emptyHtmlDoc()
    mdTitleLine.value = '未命名笔记'
    mdBodyText.value = ''
  } else {
    loadMarkdownParts(emptyMarkdownDoc())
  }
  editFormat.value = format
  viewMode.value = format === 'markdown' ? 'split' : 'edit'
  editCategoryId.value =
    filterCategoryId.value && filterCategoryId.value !== '__none__'
      ? filterCategoryId.value
      : undefined
  editTagIds.value = filterTagId.value ? [filterTagId.value] : []
  editPinned.value = false
  editDeleted.value = false
  editUpdatedAt.value = undefined
  dirty.value = true
  saveHint.value = '未保存'
  // 必须清掉上一条的预览/壳层，否则 Markdown 分栏会显示旧 HTML
  previewHtml.value = ''
  fastShellHtml.value = ''

  await Promise.resolve() // 等 v-if 卸掉旧 RichEditor

  if (format === 'html') {
    richEditorActive.value = true
    htmlShellVisible.value = false
    filePanelActive.value = true
  } else {
    richEditorActive.value = false
    htmlShellVisible.value = false
    filePanelActive.value = true
    // 空文档预览
    if (viewMode.value !== 'edit') {
      previewHtml.value = ''
    }
  }

  Promise.resolve().then(() => {
    applying.value = false
  })
  void seq
}

async function saveNote(silent = false) {
  if (editDeleted.value) return
  // 保存前把编辑器防抖中的最新内容刷出来
  richEditorRef.value?.flushEmit?.()
  saving.value = true
  saveHint.value = '保存中…'
  try {
    // Markdown 先把标题区 + 正文区合并
    if (editFormat.value === 'markdown') {
      syncMarkdownFromParts()
    }
    // 正文只存干净媒体路径（HTML 属性 + Markdown ![]() 均去 token）
    let contentToSave = stripKbMediaTokensAll(editContent.value || '')
    // 保证存库时正文含首行标题
    contentToSave =
      editFormat.value === 'html'
        ? ensureHtmlHasTitle(contentToSave, editTitle.value)
        : ensureMarkdownHasTitle(contentToSave, editTitle.value)
    const titleFromDoc = extractTitle(contentToSave, editFormat.value)
    editTitle.value = titleFromDoc
    const body = {
      title: titleFromDoc,
      content: contentToSave,
      contentFormat: editFormat.value,
      categoryId: editCategoryId.value || null,
      clearCategory: !editCategoryId.value,
      tagIds: editTagIds.value,
      pinned: editPinned.value
    }
    let note: KbNoteItem
    if (isCreating.value || !selectedId.value) {
      const res = await kbApi.createNote({
        title: body.title,
        content: body.content,
        contentFormat: body.contentFormat,
        categoryId: body.categoryId,
        tagIds: body.tagIds,
        pinned: body.pinned
      })
      note = res.data
      isCreating.value = false
      selectedId.value = note.id
    } else {
      const res = await kbApi.updateNote(selectedId.value, body)
      note = res.data
    }
    if (note.id) {
      await bindPendingFiles(note.id)
    }
    // 保存后不重挂编辑器，只合并元数据，避免 setHtml 卡顿
    applying.value = true
    if (note.content != null) {
      const fmt: KbContentFormat =
        note.contentFormat === 'markdown' ? 'markdown' : 'html'
      let clean =
        fmt === 'markdown' ? note.content : stripKbMediaTokens(note.content)
      clean =
        fmt === 'markdown'
          ? ensureMarkdownHasTitle(clean, note.title)
          : ensureHtmlHasTitle(clean, note.title)
      // 与编辑器 lastEmitted 对齐：内容相同则 RichEditor 不会 setHtml
      editContent.value = clean
      editTitle.value = extractTitle(clean, fmt) || note.title || '未命名笔记'
      if (fmt === 'html') {
        updateFastShell(clean, 'html')
      }
    } else if (note.title) {
      editTitle.value = note.title
    }
    if (note.contentFormat) {
      editFormat.value = note.contentFormat === 'markdown' ? 'markdown' : 'html'
    }
    editCategoryId.value = note.categoryId || undefined
    editTagIds.value = (note.tags || []).map((t) => t.id)
    editPinned.value = !!note.pinned
    editDeleted.value = !!note.deleted
    editUpdatedAt.value = note.updatedAt || editUpdatedAt.value
    isCreating.value = false
    selectedId.value = note.id
    contentLoading.value = false
    // 保存后保持编辑器，不闪快速壳
    if (editFormat.value === 'html' && !editDeleted.value) {
      richEditorActive.value = true
      htmlShellVisible.value = false
      filePanelActive.value = true
    }
    dirty.value = false
    saveHint.value = '已保存'
    Promise.resolve().then(() => {
      applying.value = false
    })
    if (!silent) message.success('已保存')
    void Promise.all([reloadNotes(), loadTags(), filePanelRef.value?.reload?.() ?? Promise.resolve()])
  } finally {
    saving.value = false
  }
}

function autoSave() {
  if (applying.value || editDeleted.value) return
  if (!dirty.value && !isCreating.value) return
  if (isCreating.value || selectedId.value) {
    void saveNote(true)
  }
}

// 分类/标签/置顶变更标脏；标题由正文首行同步，不单独 watch
watch([editCategoryId, editTagIds, editPinned], () => {
  if (applying.value) return
  if (selectedId.value || isCreating.value) {
    dirty.value = true
    saveHint.value = '未保存'
  }
})

async function togglePin() {
  editPinned.value = !editPinned.value
  dirty.value = true
  await saveNote(true)
}

async function deleteCurrent() {
  const id = selectedId.value
  if (!id) return
  // 阻止编辑器 blur/防抖保存在软删之后把笔记「救回」正常列表
  applying.value = true
  dirty.value = false
  try {
    await kbApi.deleteNote(id)
    // 乐观更新：立即从当前列表移除
    notes.value = notes.value.filter((n) => n.id !== id)
    total.value = Math.max(0, total.value - (trashMode.value ? 0 : 1))
    if (!trashMode.value) {
      trashCount.value += 1
    }
    selectedId.value = null
    isCreating.value = false
    editContent.value = ''
    saveHint.value = ''
    message.success('已移入回收站')
    await Promise.all([reloadNotes(), loadTags(), reloadTrashCount()])
  } catch (e: any) {
    message.error(e?.message || '删除失败')
    await reloadNotes()
  } finally {
    applying.value = false
  }
}

async function restoreCurrent() {
  if (!selectedId.value) return
  saving.value = true
  try {
    const res = await kbApi.restoreNote(selectedId.value)
    message.success('已恢复')
    if (trashMode.value) {
      selectedId.value = null
      await reloadNotes()
    } else {
      applyNote(res.data)
      await Promise.all([reloadNotes(), loadTags()])
    }
    await reloadTrashCount()
  } finally {
    saving.value = false
  }
}

async function permanentDeleteCurrent() {
  if (!selectedId.value) return
  saving.value = true
  try {
    await kbApi.permanentDeleteNote(selectedId.value)
    message.success('已永久删除（含附件与存储对象）')
    selectedId.value = null
    isCreating.value = false
    dirty.value = false
    await Promise.all([reloadNotes(), loadTags(), reloadTrashCount()])
  } catch (e: any) {
    message.error(e?.message || '永久删除失败')
  } finally {
    saving.value = false
  }
}

async function emptyTrash() {
  emptying.value = true
  try {
    const res = await kbApi.emptyTrash()
    const n = res.data?.deleted ?? 0
    message.success(n ? `已清空 ${n} 条` : '回收站已空')
    selectedId.value = null
    await reloadNotes()
  } catch (e: any) {
    message.error(e?.message || '清空失败')
  } finally {
    emptying.value = false
  }
}

function openCreateCategory(parentId?: string) {
  catModalParentId.value = parentId || null
  catModalName.value = ''
  catModalOpen.value = true
}

async function submitCategory() {
  const name = catModalName.value.trim()
  if (!name) {
    message.warning('请输入分类名称')
    return
  }
  catModalLoading.value = true
  try {
    await kbApi.createCategory({
      name,
      parentId: catModalParentId.value || undefined
    })
    catModalOpen.value = false
    message.success('分类已创建')
    await loadCategories()
  } finally {
    catModalLoading.value = false
  }
}

function openRenameCategory(node: FlatCat) {
  renameModalId.value = node.id
  renameModalName.value = node.name
  renameModalOpen.value = true
}

async function submitRenameCategory() {
  if (!renameModalId.value) return
  const name = renameModalName.value.trim()
  if (!name) {
    message.warning('请输入分类名称')
    return
  }
  renameModalLoading.value = true
  try {
    await kbApi.updateCategory(renameModalId.value, { name })
    renameModalOpen.value = false
    message.success('已重命名')
    await loadCategories()
    await reloadNotes()
  } finally {
    renameModalLoading.value = false
  }
}

function confirmDeleteCategory(node: FlatCat) {
  Modal.confirm({
    title: `删除分类「${node.name}」？`,
    content: '仅当无子分类且无关联笔记时可删除。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await kbApi.deleteCategory(node.id)
      message.success('已删除')
      if (filterCategoryId.value === node.id) {
        filterCategoryId.value = null
      }
      await loadCategories()
      await reloadNotes()
    }
  })
}

function openCreateTag() {
  tagModalName.value = ''
  tagModalOpen.value = true
}

async function submitTag() {
  const name = tagModalName.value.trim()
  if (!name) {
    message.warning('请输入标签名称')
    return
  }
  tagModalLoading.value = true
  try {
    await kbApi.createTag(name)
    tagModalOpen.value = false
    message.success('标签已创建')
    await loadTags()
  } finally {
    tagModalLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadCategories(), loadTags(), reloadNotes(), reloadTrashCount()])
})
</script>

<style lang="scss" scoped>
.kb-workspace {
  display: grid;
  grid-template-columns: 220px minmax(260px, 340px) 1fr;
  gap: 12px;
  height: 100%;
  min-height: 0;
  flex: 1;
}

.kb-sidebar,
.kb-list-pane,
.kb-editor-pane {
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 14px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.kb-sidebar {
  position: relative;
}

.trash-footer {
  margin-top: auto;
  flex-shrink: 0;
  padding: 10px 8px 12px;
  border-top: 1px solid var(--border-color);
}

.trash-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  border-radius: 10px;
  padding: 10px 12px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  text-align: left;

  &:hover {
    background: var(--surface-2);
    color: var(--text-primary);
  }

  &.active {
    background: color-mix(in srgb, #ef4444 12%, transparent);
    color: #ef4444;
  }
}

.trash-badge {
  margin-left: auto;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
  font-weight: 700;
}

.trash-mode-label {
  color: #ef4444;
  font-weight: 600;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 12px 6px;
}

.tags-head {
  margin-top: 8px;
  border-top: 1px solid var(--border-color);
  padding-top: 12px;
}

.side-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.icon-action {
  color: var(--text-secondary);
}

.cat-tree {
  flex: 0 1 auto;
  overflow: auto;
  max-height: 42%;
}

.cat-row {
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 6px;
}

.cat-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: calc(100% - 8px);
  margin: 2px 4px;
  padding: 8px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  font-size: 13px;

  &.flex-1 {
    flex: 1;
    width: auto;
    margin-right: 0;
  }

  &:hover {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 14%, transparent);
    color: var(--primary-color);
    font-weight: 600;
  }
}

.cat-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-more {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  opacity: 0.5;

  &:hover {
    opacity: 1;
    background: var(--surface-2);
  }
}

.tag-list {
  flex: 1;
  overflow: auto;
  padding: 4px 10px 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: flex-start;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  color: var(--text-primary);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;

  &.active {
    border-color: var(--primary-color);
    color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 12%, transparent);
  }
}

.tag-count {
  opacity: 0.55;
  font-size: 11px;
}

.side-empty {
  font-size: 12px;
  color: var(--text-secondary);
  padding: 8px 4px;
}

.list-toolbar {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--border-color);
}

.search-input {
  flex: 1;
}

.list-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--text-secondary);
}

.list-loading,
.list-empty,
.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  padding: 24px;
}

.editor-empty-icon {
  font-size: 36px;
  margin-bottom: 12px;
  opacity: 0.45;
}

.note-list {
  flex: 1;
  overflow: auto;
  padding: 6px 8px 12px;
}

.note-card {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 12px;
  padding: 10px 12px;
  cursor: pointer;
  margin-bottom: 4px;
  color: inherit;

  &:hover {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 10%, transparent);
    border-color: color-mix(in srgb, var(--primary-color) 35%, transparent);
  }

  &.deleted {
    opacity: 0.65;
  }
}

.note-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
}

.pin-icon,
.pin-on {
  color: var(--primary-color);
}

.fmt-tag {
  font-size: 11px;
  line-height: 18px;
  margin-inline-end: 0;
  flex-shrink: 0;
}

.del-tag {
  margin-left: auto;
  font-size: 11px;
}

.format-badge {
  margin-inline-end: 0;
  user-select: none;
}

.empty-create-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.create-format-tip {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
}

.create-format-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.format-card {
  position: relative;
  text-align: left;
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  border-radius: 12px;
  padding: 14px 14px 16px;
  cursor: pointer;
  color: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: var(--primary-color);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--primary-color) 35%, transparent);
  }
}

.format-card-title {
  font-weight: 700;
  font-size: 15px;
  margin-bottom: 8px;
}

.format-card-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.format-card-tag {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary-color) 16%, transparent);
  color: var(--primary-color);
  font-weight: 600;

  &.recommended {
    /* same */
  }
}

.note-card-snippet {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.9em;
}

.note-card-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  align-items: center;
}

.meta-cat {
  background: var(--surface-3);
  padding: 1px 6px;
  border-radius: 4px;
}

.meta-tag {
  opacity: 0.85;
}

.meta-time {
  margin-left: auto;
}

.list-pager {
  padding: 8px 12px 12px;
  display: flex;
  justify-content: center;
  border-top: 1px solid var(--border-color);
}

.editor-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 8px;
  border-bottom: 1px solid var(--border-color);
}

.editor-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}

.editor-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 14px;
  align-items: center;
  border-bottom: 1px solid var(--border-color);
}

.meta-select {
  min-width: 140px;
}

.tags-select {
  min-width: 200px;
  flex: 1;
}

.editor-body {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 0;
  padding: 0 10px 8px;

  &.mode-html {
    grid-template-columns: 1fr;
    min-height: 280px;
  }

  &.mode-edit {
    grid-template-columns: 1fr;
  }

  &.mode-preview {
    grid-template-columns: 1fr;
  }

  &.mode-split {
    grid-template-columns: 1fr 1fr;
  }
}

.html-stack {
  position: relative;
  min-height: 280px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.html-fast-shell {
  position: absolute;
  inset: 0;
  z-index: 2;
  overflow: auto;
  padding: 14px 16px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 10px;

  &.alone {
    position: relative;
    inset: auto;
    flex: 1;
    min-height: 280px;
  }
}

.shell-loading {
  color: var(--text-secondary);
  padding: 24px 0;
  text-align: center;
}

.shell-body {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;

  :deep(h1) {
    font-size: 1.85em;
    font-weight: 700;
    line-height: 1.3;
    margin: 0.1em 0 0;
    padding: 0.15em 0 0.55em;
    border: none;
  }

  :deep(h1 + *) {
    margin-top: 0.85em !important;
    padding-top: 0.85em;
    border-top: 1px solid var(--border-color);
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
  }

  :deep(video) {
    max-width: 100%;
  }

  :deep(p) {
    margin: 0.5em 0;
  }
}

/* Markdown：标题区 + 横线 + 正文 */
.md-doc-edit {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 280px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  overflow: hidden;
  transition: box-shadow 0.15s, border-color 0.15s;

  &.md-drag-over {
    border-color: var(--primary-color);
    box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--primary-color) 35%, transparent);
  }
}

.md-doc-title {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 1.65em;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.02em;
  padding: 16px 16px 12px;
  color: var(--text-primary);
}

.md-doc-title::placeholder {
  color: var(--text-secondary);
  opacity: 0.55;
  font-weight: 600;
}

.doc-title-rule {
  height: 0;
  margin: 0 16px;
  border: none;
  border-top: 1px solid var(--border-color);
  flex-shrink: 0;
}

.md-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.md-file-input {
  display: none;
}

.md-toolbar-tip {
  font-size: 12px;
}

.md-body-input {
  flex: 1;
  min-height: 0 !important;
  border: none !important;
  border-radius: 0 !important;
}

/* Markdown 预览：首个 h1 与正文横线分隔 */
.md-preview.doc-preview,
.md-preview {
  :deep(h1) {
    font-size: 1.85em;
    font-weight: 700;
    line-height: 1.3;
    margin: 0.1em 0 0;
    padding: 0.15em 0 0.55em;
    border: none;
  }

  :deep(h1 + *) {
    margin-top: 0.85em !important;
    padding-top: 0.85em;
    border-top: 1px solid var(--border-color);
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
    margin: 0.5em 0;
  }
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.doc-title-hint {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42%;
}

.shell-tip {
  position: sticky;
  bottom: 0;
  padding: 8px 0 0;
  font-size: 12px;
  text-align: center;
  background: linear-gradient(transparent, var(--surface-1) 40%);
}

.html-stack > :deep(.rich-wrap) {
  flex: 1;
  min-height: 280px;
  position: relative;
  z-index: 1;
}

.md-input {
  height: 100% !important;
  resize: none;
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  background: var(--surface-1);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13.5px;
  line-height: 1.6;
  padding: 14px !important;

  :deep(textarea) {
    height: 100% !important;
    resize: none;
    border: none;
    box-shadow: none !important;
    background: transparent;
    font-family: inherit;
    font-size: inherit;
    line-height: inherit;
  }
}

.md-preview {
  height: 100%;
  overflow: auto;
  padding: 14px 18px;
  border-left: 1px solid var(--border-color);
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 0.8em 0 0.4em;
    line-height: 1.3;
  }

  :deep(p) {
    margin: 0.5em 0;
  }

  :deep(pre) {
    background: var(--surface-2);
    padding: 10px 12px;
    border-radius: 8px;
    overflow: auto;
  }

  :deep(code) {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.92em;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 1.4em;
  }

  :deep(blockquote) {
    margin: 0.6em 0;
    padding-left: 12px;
    border-left: 3px solid var(--border-color);
    color: var(--text-secondary);
  }
}

.mode-preview .md-preview {
  border-left: none;
}

.editor-status {
  padding: 6px 14px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  border-top: 1px solid var(--border-color);
  min-height: 28px;
}

@media (max-width: 1100px) {
  .kb-workspace {
    grid-template-columns: 200px 1fr;
    grid-template-rows: 1fr 1fr;
  }

  .kb-sidebar {
    grid-row: 1 / span 2;
  }

  .kb-list-pane {
    grid-column: 2;
  }

  .kb-editor-pane {
    grid-column: 2;
  }
}

@media (max-width: 768px) {
  .kb-workspace {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
    overflow: auto;
  }

  .kb-sidebar,
  .kb-list-pane,
  .kb-editor-pane {
    min-height: 280px;
  }
}
</style>
