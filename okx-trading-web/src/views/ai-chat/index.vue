<template>
  <div class="ai-chat-page">
    <div class="chat-container">
      <!-- 左侧会话列表 -->
      <div class="chat-sidebar">
        <div class="sidebar-header">
          <span class="sidebar-title">会话列表</span>
          <a-button type="primary" size="small" @click="createConversation">
            <template #icon><PlusOutlined /></template>
            新对话
          </a-button>
        </div>
        <div class="sidebar-search">
          <a-input
            v-model:value="searchKeyword"
            allow-clear
            size="small"
            placeholder="搜索会话标题"
            @pressEnter="loadConversations"
            @change="onSearchChange"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
        </div>
        <div class="conversation-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            class="conversation-item"
            :class="{ active: activeConversationId === conv.id }"
            @click="switchConversation(conv.id)"
          >
            <div class="conv-info">
              <MessageOutlined class="conv-icon" />
              <div class="conv-text">
                <template v-if="renamingId === conv.id">
                  <a-input
                    v-model:value="renamingTitle"
                    size="small"
                    class="conv-rename-input"
                    maxlength="100"
                    @click.stop
                    @pressEnter="confirmRename(conv)"
                    @blur="confirmRename(conv)"
                    @keydown.esc.stop="cancelRename"
                  />
                </template>
                <template v-else>
                  <span class="conv-title" :title="conv.title || '新对话'">{{ conv.title || '新对话' }}</span>
                  <span class="conv-model" v-if="conv.model">{{ getModelDisplayName(conv.provider, conv.model) }}</span>
                </template>
              </div>
            </div>
            <div class="conv-actions" @click.stop>
              <EditOutlined
                v-if="renamingId !== conv.id && !String(conv.id).startsWith('local_')"
                class="conv-action"
                title="重命名"
                @click="startRename(conv)"
              />
              <DeleteOutlined
                class="conv-action conv-delete"
                title="删除"
                @click="handleDeleteConversation(conv.id)"
              />
            </div>
          </div>
          <EmptyState
            v-if="conversations.length === 0"
            scene="chat"
            compact
            tone="soft"
            title="暂无会话"
            description="点上方按钮开始新对话"
          />
        </div>
      </div>

      <!-- 右侧聊天区域 -->
      <div class="chat-main">
        <div class="chat-header">
          <div class="chat-header-left">
            <span class="chat-title">AI 对话</span>
            <a-select
              v-model:value="selectedModelKey"
              class="model-select"
              size="small"
              placeholder="选择模型"
              :disabled="isLoading || availableProviders.length === 0"
              @change="handleModelChange"
            >
              <a-select-opt-group
                v-for="provider in availableProviders"
                :key="provider.key"
                :label="provider.name"
              >
                <a-select-option
                  v-for="model in provider.models"
                  :key="`${provider.key}::${model.id}`"
                  :value="`${provider.key}::${model.id}`"
                >
                  {{ model.name }}
                </a-select-option>
              </a-select-opt-group>
            </a-select>
            <a-button
              size="small"
              class="header-action-btn"
              :loading="testingModel"
              :disabled="!selectedModelKey || isLoading"
              @click="handleTestModel"
            >
              <template #icon><ExperimentOutlined /></template>
              测试
            </a-button>
            <a-button
              size="small"
              class="header-action-btn"
              :disabled="!activeConversationId || String(activeConversationId).startsWith('local_')"
              @click="settingsOpen = true"
            >
              <template #icon><SlidersOutlined /></template>
              参数
            </a-button>
            <a-button
              v-if="auth.isSuperAdmin"
              size="small"
              class="header-action-btn"
              @click="modelManageOpen = true"
            >
              <template #icon><SettingOutlined /></template>
              模型管理
            </a-button>
          </div>
          <div class="chat-header-right">
            <a-tag v-if="testingModel" color="processing">测试中…</a-tag>
            <a-tag v-else-if="modelTestStatus === 'ok'" color="success">
              ✓ 可用{{ modelTestLatency != null ? ` · ${modelTestLatency}ms` : '' }}
            </a-tag>
            <a-tag v-else-if="modelTestStatus === 'fail'" color="error" :title="modelTestError || undefined">
              不可用
            </a-tag>
            <span class="chat-hint">T={{ sessionTemperature.toFixed(1) }} · {{ sessionMaxTokens }} tokens</span>
          </div>
        </div>

        <!-- 会话参数抽屉 -->
        <a-drawer
          v-model:open="settingsOpen"
          title="会话参数"
          placement="right"
          :width="360"
          @close="settingsOpen = false"
        >
          <div class="settings-form">
            <div class="settings-item">
              <div class="settings-label">温度 temperature</div>
              <a-slider v-model:value="sessionTemperature" :min="0" :max="2" :step="0.1" />
              <div class="settings-hint">越低越严谨，越高越发散（当前 {{ sessionTemperature.toFixed(1) }}）</div>
            </div>
            <div class="settings-item">
              <div class="settings-label">最大长度 max_tokens</div>
              <a-input-number
                v-model:value="sessionMaxTokens"
                :min="64"
                :max="16000"
                :step="64"
                style="width: 100%"
              />
            </div>
            <div class="settings-item">
              <div class="settings-label">系统提示 System Prompt</div>
              <a-textarea
                v-model:value="sessionSystemPrompt"
                :rows="8"
                placeholder="留空则使用默认通用助手提示"
                :maxlength="8000"
                show-count
              />
            </div>
            <a-space>
              <a-button type="primary" :loading="settingsSaving" @click="saveSessionSettings">保存到会话</a-button>
              <a-button @click="resetSessionSettingsDraft">重置草稿</a-button>
            </a-space>
          </div>
        </a-drawer>

        <ModelManageModal
          v-if="auth.isSuperAdmin"
          v-model:open="modelManageOpen"
          capability="chat"
          @changed="onModelsChanged"
        />

        <!-- 消息列表 -->
        <div class="chat-messages" ref="messagesRef">
          <div v-if="!activeConversationId" class="chat-welcome">
            <RobotOutlined class="welcome-icon" />
            <div class="welcome-title">AI 对话</div>
            <div class="welcome-desc">通用聊天助手，不绑定交易或账户数据</div>
            <div class="welcome-notice" v-if="availableProviders.length === 0">
              <template v-if="auth.isSuperAdmin">
                ⚠️ 暂无可用 Chat 模型 — 请点击右上角「模型管理」添加，并确认 yml 中配置了 api-key
              </template>
              <template v-else>
                ⚠️ 暂无可用 Chat 模型 — 请联系超级管理员配置
              </template>
            </div>
            <div class="welcome-prompts">
              <div
                v-for="prompt in defaultPrompts"
                :key="prompt.text"
                class="prompt-card"
                @click="handleQuickPrompt(prompt.text)"
              >
                <div class="prompt-icon">{{ prompt.icon }}</div>
                <div class="prompt-text">{{ prompt.text }}</div>
              </div>
            </div>
          </div>
          <template v-else>
            <div
              v-for="msg in displayMessages"
              :key="msg.id"
              class="message-item"
              :class="msg.role"
            >
              <div class="message-avatar">
                <div v-if="msg.role === 'assistant'" class="avatar-ai">
                  <RobotOutlined />
                </div>
                <div v-else class="avatar-user">
                  <UserOutlined />
                </div>
              </div>
              <div class="message-body">
                <div class="message-role">{{ msg.role === 'assistant' ? 'AI' : '我' }}</div>
                <div class="message-content" v-html="renderContent(msg.content, msg.role)"></div>
                <div class="message-footer">
                  <span class="message-time">{{ formatChatTime(msg.timestamp) }}</span>
                  <button
                    v-if="msg.content"
                    type="button"
                    class="msg-copy-btn"
                    :class="{ copied: copiedId === msg.id }"
                    @click="copyMessage(msg)"
                    :title="copiedId === msg.id ? '已复制' : '复制消息'"
                  >
                    <CheckOutlined v-if="copiedId === msg.id" />
                    <CopyOutlined v-else />
                    <span class="msg-copy-label">{{ copiedId === msg.id ? '已复制' : '复制' }}</span>
                  </button>
                  <button
                    v-if="canEditUser(msg)"
                    type="button"
                    class="msg-copy-btn"
                    title="编辑并重发"
                    :disabled="isLoading"
                    @click="startEditMessage(msg)"
                  >
                    <EditOutlined />
                    <span class="msg-copy-label">编辑</span>
                  </button>
                  <button
                    v-if="canRegenerate(msg)"
                    type="button"
                    class="msg-copy-btn"
                    title="重新生成"
                    :disabled="isLoading"
                    @click="handleRegenerate"
                  >
                    <ReloadOutlined />
                    <span class="msg-copy-label">重新生成</span>
                  </button>
                </div>
                <!-- 编辑用户消息 -->
                <div v-if="editingMessageId === msg.id" class="msg-edit-box" @click.stop>
                  <a-textarea v-model:value="editingContent" :rows="3" :maxlength="20000" />
                  <div class="msg-edit-actions">
                    <a-button size="small" @click="cancelEditMessage">取消</a-button>
                    <a-button size="small" type="primary" :disabled="!editingContent.trim()" @click="confirmEditResend">
                      保存并重发
                    </a-button>
                  </div>
                </div>
              </div>
            </div>
            <div
              v-if="isLoading && messages.length > 0 && messages[messages.length - 1].role === 'assistant' && !messages[messages.length - 1].content"
              class="message-item assistant typing-wrapper"
            >
              <div class="message-avatar">
                <div class="avatar-ai"><RobotOutlined /></div>
              </div>
              <div class="message-body">
                <div class="message-role">AI</div>
                <div class="message-content typing-indicator">
                  <span></span><span></span><span></span>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- 输入区域：无会话时也可直接发送（自动建会话） -->
        <div class="chat-input-area">
          <div class="input-wrapper">
            <a-textarea
              v-model:value="inputMessage"
              :placeholder="isLoading ? 'AI 正在生成… 可点击停止' : '输入消息，Enter 发送 · Shift+Enter 换行'"
              :auto-size="{ minRows: 1, maxRows: 4 }"
              :disabled="isLoading || availableProviders.length === 0"
              @keydown.enter="onEnterKey"
              class="chat-input"
            />
            <a-button
              v-if="isLoading"
              danger
              class="send-btn stop-btn"
              title="停止生成"
              @click="handleStop"
            >
              <StopOutlined />
            </a-button>
            <a-button
              v-else
              type="primary"
              :disabled="!inputMessage.trim() || availableProviders.length === 0"
              @click="() => handleSend()"
              class="send-btn"
            >
              <SendOutlined />
            </a-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, nextTick } from 'vue'
import {
  PlusOutlined,
  DeleteOutlined,
  MessageOutlined,
  RobotOutlined,
  UserOutlined,
  SendOutlined,
  CopyOutlined,
  CheckOutlined,
  SettingOutlined,
  ExperimentOutlined,
  EditOutlined,
  ReloadOutlined,
  StopOutlined,
  SearchOutlined,
  SlidersOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { chatApi } from '@/api/chat.api'
import { videoApi } from '@/api/video.api'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { ChatMessage, ChatConversation, AiProvider } from '@/types/api'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const auth = useAuthStore()

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  highlight(str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`
      } catch { /* ignore */ }
    }
    const escaped = str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
    return `<pre class="hljs"><code>${escaped}</code></pre>`
  }
})

const availableProviders = ref<AiProvider[]>([])
const selectedModelKey = ref<string>('')
const conversations = ref<ChatConversation[]>([])
const activeConversationId = ref<string>('')
const messages = ref<ChatMessage[]>([])
const inputMessage = ref('')
const isLoading = ref(false)
const messagesRef = ref<HTMLElement | null>(null)
/** Chat 模型管理（与视频提取等同 capability=chat） */
const modelManageOpen = ref(false)
const testingModel = ref(false)
const modelTestStatus = ref<'idle' | 'ok' | 'fail'>('idle')
const modelTestLatency = ref<number | null>(null)
const modelTestError = ref('')
/** 最近复制成功的消息 id，用于短暂切换图标 */
const copiedId = ref<string>('')
let copiedTimer: ReturnType<typeof setTimeout> | null = null
let currentAbortController: AbortController | null = null
/** 当前流式 streamId（meta 下发，用于后端真取消） */
const currentStreamId = ref<string>('')
/** 会话重命名 */
const renamingId = ref<string>('')
const renamingTitle = ref('')
let renameSaving = false
/** 会话搜索 */
const searchKeyword = ref('')
let searchTimer: ReturnType<typeof setTimeout> | null = null
/** 会话参数 */
const settingsOpen = ref(false)
const settingsSaving = ref(false)
const sessionTemperature = ref(0.7)
const sessionMaxTokens = ref(2000)
const sessionSystemPrompt = ref('')
/** 编辑用户消息 */
const editingMessageId = ref<string>('')
const editingContent = ref('')

const displayMessages = computed(() => {
  if (isLoading.value && messages.value.length > 0) {
    const last = messages.value[messages.value.length - 1]
    if (last.role === 'assistant' && !last.content) {
      return messages.value.slice(0, -1)
    }
  }
  return messages.value
})

/** 仅最后一条完整 assistant 且非加载中可重新生成 */
function canRegenerate(msg: ChatMessage): boolean {
  if (isLoading.value) return false
  if (msg.role !== 'assistant' || !msg.content) return false
  if (!activeConversationId.value || String(activeConversationId.value).startsWith('local_')) return false
  const list = displayMessages.value
  if (!list.length) return false
  return list[list.length - 1]?.id === msg.id
}

function canEditUser(msg: ChatMessage): boolean {
  if (isLoading.value) return false
  if (msg.role !== 'user' || !msg.content) return false
  if (!activeConversationId.value || String(activeConversationId.value).startsWith('local_')) return false
  // 临时前端 id 不可编辑
  if (String(msg.id).startsWith('msg_')) return false
  return true
}

function loadSettingsFromConversation(conv?: ChatConversation) {
  sessionTemperature.value = conv?.temperature != null ? Number(conv.temperature) : 0.7
  sessionMaxTokens.value = conv?.maxTokens != null ? Number(conv.maxTokens) : 2000
  sessionSystemPrompt.value = conv?.systemPrompt || ''
}

function resetSessionSettingsDraft() {
  const conv = conversations.value.find(c => c.id === activeConversationId.value)
  loadSettingsFromConversation(conv)
}

async function saveSessionSettings() {
  if (!activeConversationId.value || String(activeConversationId.value).startsWith('local_')) {
    message.warning('请先发送一条消息创建会话后再保存参数')
    return
  }
  settingsSaving.value = true
  try {
    const clearSp = !sessionSystemPrompt.value.trim()
    const res = await chatApi.updateConversation(activeConversationId.value, {
      temperature: sessionTemperature.value,
      maxTokens: sessionMaxTokens.value,
      systemPrompt: clearSp ? undefined : sessionSystemPrompt.value,
      clearSystemPrompt: clearSp
    })
    const data = (res as any).data as ChatConversation
    const conv = conversations.value.find(c => c.id === activeConversationId.value)
    if (conv && data) {
      conv.temperature = data.temperature
      conv.maxTokens = data.maxTokens
      conv.systemPrompt = data.systemPrompt
    }
    message.success('会话参数已保存')
    settingsOpen.value = false
  } catch {
    message.error('保存失败')
  } finally {
    settingsSaving.value = false
  }
}

function onSearchChange() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void loadConversations()
  }, 300)
}

const defaultPrompts = [
  { icon: '💬', text: '用三句话介绍你自己' },
  { icon: '✍️', text: '帮我润色一段工作邮件' },
  { icon: '🧠', text: '解释一下什么是大语言模型' },
  { icon: '📋', text: '给我一份每日学习计划模板' }
]

function parseModelKey(key: string): { provider: string; model: string } {
  const parts = key.split('::')
  return { provider: parts[0] || '', model: parts[1] || '' }
}

function getModelDisplayName(providerKey: string, modelId: string): string {
  const provider = availableProviders.value.find(p => p.key === providerKey)
  if (!provider) return modelId
  const model = provider.models.find(m => m.id === modelId)
  return model ? model.name : modelId
}

function formatChatTime(timestamp: string): string {
  if (!timestamp) return ''
  const d = new Date(timestamp)
  const now = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  const timeStr = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.toDateString() === now.toDateString()) return timeStr
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${timeStr}`
}

/** 复制消息原文（非渲染后的 HTML） */
async function copyMessage(msg: ChatMessage) {
  const text = (msg.content || '').trim()
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copiedId.value = msg.id
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => {
      if (copiedId.value === msg.id) copiedId.value = ''
    }, 1500)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}

function renderContent(content: string, role: string = 'assistant'): string {
  if (!content) return ''
  if (role === 'user') {
    return content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
  return md.render(content)
}

async function scrollToBottom() {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

function isModelKeyAvailable(key: string): boolean {
  if (!key) return false
  const { provider, model } = parseModelKey(key)
  const p = availableProviders.value.find((x) => x.key === provider)
  return !!(p && p.models.some((m) => m.id === model))
}

async function loadModels() {
  try {
    const res = await chatApi.getModels()
    availableProviders.value = (res as any).data || []
    const prev = selectedModelKey.value
    if (prev && isModelKeyAvailable(prev)) {
      // 保留当前选择
    } else if (availableProviders.value.length > 0) {
      const firstProvider = availableProviders.value[0]
      const firstModel = firstProvider.models[0]
      if (firstModel) {
        selectedModelKey.value = `${firstProvider.key}::${firstModel.id}`
      } else {
        selectedModelKey.value = ''
      }
    } else {
      selectedModelKey.value = ''
    }
    // 模型列表变化后重置测试状态
    modelTestStatus.value = 'idle'
    modelTestLatency.value = null
    modelTestError.value = ''
  } catch {
    availableProviders.value = []
  }
}

async function onModelsChanged() {
  await loadModels()
  message.success('模型列表已刷新')
}

async function handleTestModel() {
  if (!selectedModelKey.value) {
    message.warning('请先选择模型')
    return
  }
  const { provider, model } = parseModelKey(selectedModelKey.value)
  if (!provider || !model) {
    message.warning('模型无效')
    return
  }
  testingModel.value = true
  modelTestStatus.value = 'idle'
  modelTestError.value = ''
  modelTestLatency.value = null
  try {
    const res = await videoApi.testModel({ provider, model })
    const data = (res as any).data
    if (data?.available) {
      modelTestStatus.value = 'ok'
      modelTestLatency.value = data.latencyMs ?? null
      message.success(`模型可用${data.latencyMs != null ? `（${data.latencyMs}ms）` : ''}`)
    } else {
      modelTestStatus.value = 'fail'
      modelTestError.value = data?.errorMessage || '不可用'
      message.error(modelTestError.value || '模型不可用')
    }
  } catch (e: any) {
    modelTestStatus.value = 'fail'
    modelTestError.value = e?.message || '测试失败'
    message.error(modelTestError.value)
  } finally {
    testingModel.value = false
  }
}

async function loadConversations() {
  try {
    const res = await chatApi.getConversations(searchKeyword.value.trim() || undefined)
    conversations.value = (res as any).data || []
  } catch {
    conversations.value = []
  }
}

async function loadMessages(conversationId: string) {
  try {
    const res = await chatApi.getMessages(conversationId)
    messages.value = (res as any).data || []
    scrollToBottom()
  } catch {
    messages.value = []
  }
}

async function switchConversation(id: string) {
  if (isLoading.value) {
    message.warning('请先停止当前生成')
    return
  }
  cancelEditMessage()
  activeConversationId.value = id
  const conv = conversations.value.find(c => c.id === id)
  if (conv && conv.provider && conv.model) {
    selectedModelKey.value = `${conv.provider}::${conv.model}`
  }
  loadSettingsFromConversation(conv)
  await loadMessages(id)
}

function createConversation() {
  const id = 'local_' + Date.now()
  const { provider, model } = parseModelKey(selectedModelKey.value)
  const conv: ChatConversation = {
    id,
    title: '新对话',
    provider,
    model,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
  conversations.value.unshift(conv)
  activeConversationId.value = id
  messages.value = []
}

function handleModelChange() {
  modelTestStatus.value = 'idle'
  modelTestLatency.value = null
  modelTestError.value = ''
  const { provider, model } = parseModelKey(selectedModelKey.value)
  if (!activeConversationId.value) return
  const conv = conversations.value.find(c => c.id === activeConversationId.value)
  if (conv) {
    conv.provider = provider
    conv.model = model
  }
}

function startRename(conv: ChatConversation) {
  renamingId.value = conv.id
  renamingTitle.value = conv.title || '新对话'
  nextTick(() => {
    const el = document.querySelector('.conv-rename-input input') as HTMLInputElement | null
    el?.focus()
    el?.select()
  })
}

function cancelRename() {
  renamingId.value = ''
  renamingTitle.value = ''
}

async function confirmRename(conv: ChatConversation) {
  if (renameSaving) return
  if (renamingId.value !== conv.id) return
  const title = renamingTitle.value.trim()
  if (!title) {
    message.warning('标题不能为空')
    return
  }
  if (title === (conv.title || '新对话')) {
    cancelRename()
    return
  }
  if (String(conv.id).startsWith('local_')) {
    conv.title = title
    cancelRename()
    return
  }
  renameSaving = true
  try {
    const res = await chatApi.renameConversation(conv.id, title)
    const data = (res as any).data
    conv.title = data?.title || title
    message.success('已重命名')
  } catch {
    message.error('重命名失败')
  } finally {
    renameSaving = false
    cancelRename()
  }
}

/** Enter 发送；Shift+Enter 换行；输入法组字中不拦截 */
function onEnterKey(e: KeyboardEvent) {
  if (e.shiftKey) return
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  e.stopPropagation()
  void handleSend()
}

async function handleStop() {
  const streamId = currentStreamId.value
  const convId =
    activeConversationId.value && !String(activeConversationId.value).startsWith('local_')
      ? activeConversationId.value
      : undefined
  // 先通知后端真取消，再断开本地 SSE
  try {
    await chatApi.stopStream({
      streamId: streamId || undefined,
      conversationId: convId
    })
  } catch {
    // 后端 stop 失败仍继续本地 abort
  }
  if (currentAbortController) {
    currentAbortController.abort()
    currentAbortController = null
  }
}

function bindStreamHandlers(assistantMsgIndex: number, opts?: { userText?: string }) {
  currentStreamId.value = ''
  return {
    onMeta(data: {
      conversationId: string
      streamId?: string
      provider?: string
      model?: string
    }) {
      if (data.streamId) {
        currentStreamId.value = data.streamId
      }
      if (data.conversationId && activeConversationId.value.startsWith('local_')) {
        activeConversationId.value = data.conversationId
        const conv = conversations.value.find(c => String(c.id).startsWith('local_'))
        if (conv) {
          conv.id = data.conversationId
          if (opts?.userText) conv.title = opts.userText.slice(0, 20) || '新对话'
          if (data.provider) conv.provider = data.provider
          if (data.model) conv.model = data.model
          conv.temperature = sessionTemperature.value
          conv.maxTokens = sessionMaxTokens.value
          conv.systemPrompt = sessionSystemPrompt.value || null
        }
        // 本地会话转正后，把草稿 system prompt 写回服务端
        if (sessionSystemPrompt.value.trim()) {
          void chatApi.updateConversation(data.conversationId, {
            systemPrompt: sessionSystemPrompt.value.trim()
          }).catch(() => { /* ignore */ })
        }
      }
      const { provider, model } = parseModelKey(selectedModelKey.value)
      const conv = conversations.value.find(c => c.id === activeConversationId.value)
      if (conv) {
        if (provider) conv.provider = provider
        if (model) conv.model = model
      }
    },
    onDelta(data: { content: string }) {
      messages.value[assistantMsgIndex] = {
        ...messages.value[assistantMsgIndex],
        content: messages.value[assistantMsgIndex].content + data.content
      }
      scrollToBottom()
    },
    onDone(data: { messageId: string; cancelled?: boolean }) {
      isLoading.value = false
      currentAbortController = null
      currentStreamId.value = ''
      if (data?.cancelled) {
        message.info('已停止生成')
      }
      // 刷新列表：拿到服务端最终消息（含取消时的部分内容）
      if (activeConversationId.value && !String(activeConversationId.value).startsWith('local_')) {
        void loadMessages(activeConversationId.value)
      } else {
        scrollToBottom()
      }
    },
    onError(data: { message: string }) {
      const errText = data.message || '抱歉，请求失败，请稍后重试。'
      const prev = messages.value[assistantMsgIndex]?.content || ''
      const next = prev
        ? (prev.includes(errText) ? prev : `${prev}\n\n${errText}`)
        : errText
      if (messages.value[assistantMsgIndex]) {
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          content: next
        }
      }
      isLoading.value = false
      currentAbortController = null
      currentStreamId.value = ''
      scrollToBottom()
    },
    onAbort() {
      // 后端 stop 已触发时通常会走 done(cancelled)；此处兜底本地断开
      const prev = messages.value[assistantMsgIndex]?.content || ''
      if (!prev) {
        if (messages.value[assistantMsgIndex]?.role === 'assistant') {
          messages.value.splice(assistantMsgIndex, 1)
        }
      } else if (!prev.includes('已停止生成')) {
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          content: prev + '\n\n*（已停止生成）*'
        }
      }
      isLoading.value = false
      currentAbortController = null
      currentStreamId.value = ''
      message.info('已停止生成')
      scrollToBottom()
      // 稍后再刷一次，对齐服务端落库的部分内容
      if (activeConversationId.value && !String(activeConversationId.value).startsWith('local_')) {
        setTimeout(() => {
          void loadMessages(activeConversationId.value)
        }, 400)
      }
    }
  }
}

async function handleSend() {
  const text = inputMessage.value.trim()
  if (!text || isLoading.value) return
  if (!selectedModelKey.value) return

  inputMessage.value = ''
  await nextTick()
  inputMessage.value = ''

  if (!activeConversationId.value) {
    createConversation()
  }

  const { provider, model } = parseModelKey(selectedModelKey.value)

  const userMsg: ChatMessage = {
    id: 'msg_' + Date.now(),
    role: 'user',
    content: text,
    timestamp: new Date().toISOString()
  }
  messages.value.push(userMsg)
  isLoading.value = true
  scrollToBottom()

  const assistantMsgData: ChatMessage = {
    id: 'msg_ai_' + Date.now(),
    role: 'assistant',
    content: '',
    timestamp: new Date().toISOString()
  }
  messages.value.push(assistantMsgData)
  const assistantMsgIndex = messages.value.length - 1
  scrollToBottom()

  currentAbortController = chatApi.sendMessageStream(
    {
      message: text,
      conversationId: activeConversationId.value.startsWith('local_')
        ? undefined
        : activeConversationId.value,
      provider: provider || undefined,
      model: model || undefined,
      temperature: sessionTemperature.value,
      maxTokens: sessionMaxTokens.value
    },
    bindStreamHandlers(assistantMsgIndex, { userText: text })
  )
}

async function handleRegenerate() {
  if (isLoading.value) return
  if (!activeConversationId.value || String(activeConversationId.value).startsWith('local_')) {
    message.warning('请先发送消息后再重新生成')
    return
  }
  if (!selectedModelKey.value) {
    message.warning('请先选择模型')
    return
  }

  // 去掉末尾 assistant（本地），后端也会删库
  while (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'assistant') {
    messages.value.pop()
  }

  const { provider, model } = parseModelKey(selectedModelKey.value)
  isLoading.value = true

  const assistantMsgData: ChatMessage = {
    id: 'msg_ai_' + Date.now(),
    role: 'assistant',
    content: '',
    timestamp: new Date().toISOString()
  }
  messages.value.push(assistantMsgData)
  const assistantMsgIndex = messages.value.length - 1
  scrollToBottom()

  currentAbortController = chatApi.regenerateStream(
    {
      conversationId: activeConversationId.value,
      provider: provider || undefined,
      model: model || undefined,
      temperature: sessionTemperature.value,
      maxTokens: sessionMaxTokens.value
    } as any,
    bindStreamHandlers(assistantMsgIndex)
  )
}

function startEditMessage(msg: ChatMessage) {
  editingMessageId.value = msg.id
  editingContent.value = msg.content || ''
}

function cancelEditMessage() {
  editingMessageId.value = ''
  editingContent.value = ''
}

async function confirmEditResend() {
  const text = editingContent.value.trim()
  if (!text || isLoading.value) return
  if (!activeConversationId.value || String(activeConversationId.value).startsWith('local_')) return
  if (!editingMessageId.value) return

  const msgId = editingMessageId.value
  cancelEditMessage()

  // 本地截断：保留该消息及之前，并更新内容
  const idx = messages.value.findIndex(m => m.id === msgId)
  if (idx < 0) return
  messages.value = messages.value.slice(0, idx + 1)
  messages.value[idx] = { ...messages.value[idx], content: text }

  const { provider, model } = parseModelKey(selectedModelKey.value)
  isLoading.value = true

  const assistantMsgData: ChatMessage = {
    id: 'msg_ai_' + Date.now(),
    role: 'assistant',
    content: '',
    timestamp: new Date().toISOString()
  }
  messages.value.push(assistantMsgData)
  const assistantMsgIndex = messages.value.length - 1
  scrollToBottom()

  currentAbortController = chatApi.editResendStream(
    {
      conversationId: activeConversationId.value,
      messageId: msgId,
      message: text,
      provider: provider || undefined,
      model: model || undefined,
      temperature: sessionTemperature.value,
      maxTokens: sessionMaxTokens.value
    },
    bindStreamHandlers(assistantMsgIndex, { userText: text })
  )
}

async function handleDeleteConversation(id: string) {
  conversations.value = conversations.value.filter(c => c.id !== id)
  if (activeConversationId.value === id) {
    activeConversationId.value = ''
    messages.value = []
  }
  if (!id.startsWith('local_')) {
    try {
      await chatApi.deleteConversation(id)
    } catch { /* ignore */ }
  }
}

function handleQuickPrompt(text: string) {
  if (!activeConversationId.value) {
    createConversation()
  }
  inputMessage.value = text
  void nextTick(() => handleSend())
}

onMounted(() => {
  loadModels()
  loadConversations()
})
</script>

<style lang="scss" scoped>
/* 占满 layout-content-body，整页不出现外层滑轮 */
.ai-chat-page {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  max-height: 100%;
  padding: 0;
  margin: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.chat-container {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  background: linear-gradient(165deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.88));
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 22px;
  overflow: hidden;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 16px 40px rgba(99, 102, 241, 0.08);
  backdrop-filter: blur(12px);
}

.chat-sidebar {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.95), rgba(241, 245, 249, 0.9));

  .sidebar-header {
    padding: 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid var(--border-color);

    .sidebar-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary);
    }
  }

  .sidebar-search {
    padding: 8px 12px;
    border-bottom: 1px solid var(--border-color);
  }

  .conversation-list {
    flex: 1 1 auto;
    min-height: 0;
    overflow-x: hidden;
    overflow-y: auto;
    padding: 8px;
    overscroll-behavior: contain;
  }

  .conversation-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 11px 12px;
    border-radius: 14px;
    cursor: pointer;
    margin-bottom: 4px;
    border: 1px solid transparent;
    transition: all 0.18s ease;

    &:hover {
      background: rgba(238, 242, 255, 0.85);
      border-color: rgba(199, 210, 254, 0.6);
    }

    &.active {
      background: linear-gradient(135deg, rgba(238, 242, 255, 0.98), rgba(245, 243, 255, 0.95));
      border-color: rgba(199, 210, 254, 0.95);
      box-shadow: 0 4px 14px rgba(99, 102, 241, 0.1);
      .conv-title { color: #4f46e5; font-weight: 600; }
    }

    .conv-info {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      overflow: hidden;
      flex: 1;
    }

    .conv-icon {
      font-size: 14px;
      color: var(--text-muted);
      flex-shrink: 0;
      margin-top: 2px;
    }

    .conv-text {
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .conv-title {
      font-size: 13px;
      color: var(--text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .conv-model {
      font-size: 11px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .conv-rename-input {
      width: 100%;
      :deep(.ant-input) {
        font-size: 12px;
        padding: 2px 6px;
      }
    }

    .conv-actions {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
      margin-left: 4px;
      opacity: 0;
      transition: opacity 0.2s;
    }

    .conv-action {
      font-size: 12px;
      color: var(--text-muted);
      cursor: pointer;

      &:hover {
        color: var(--primary-color);
      }

      &.conv-delete:hover {
        color: var(--danger-color);
      }
    }

    &:hover .conv-actions,
    &.active .conv-actions {
      opacity: 1;
    }
  }
}

.chat-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  flex-shrink: 0;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  .chat-header-left {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    min-width: 0;
  }

  .chat-header-right {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }

  .chat-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--text-primary);
  }

  .model-select {
    min-width: 180px;
    max-width: 260px;

    :deep(.ant-select-selector) {
      border-radius: 6px;
      font-size: 12px;
      height: 28px;
    }

    :deep(.ant-select-selection-item) {
      line-height: 26px;
      font-size: 12px;
    }
  }

  .header-action-btn {
    border-radius: 6px;
    font-size: 12px;
    height: 28px;
    padding: 0 10px;
  }

  .chat-hint {
    font-size: 12px;
    color: var(--text-muted);
    white-space: nowrap;
  }
}

.chat-messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 20px;
}

.chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  user-select: none;

  .welcome-icon {
    font-size: 48px;
    color: var(--primary-color);
    margin-bottom: 16px;
  }

  .welcome-title {
    font-size: 20px;
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 8px;
  }

  .welcome-desc {
    font-size: 14px;
    color: var(--text-secondary);
    margin-bottom: 12px;
  }

  .welcome-notice {
    font-size: 13px;
    color: var(--warning-color);
    margin-bottom: 20px;
    padding: 8px 16px;
    background: #FFF7ED;
    border: 1px solid #FDBA74;
    border-radius: 8px;
  }

  .welcome-prompts {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    max-width: 440px;
  }

  .prompt-card {
    background: #FAFBFC;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 14px 16px;
    cursor: pointer;
    transition: all 0.2s;
    display: flex;
    align-items: center;
    gap: 10px;

    &:hover {
      border-color: var(--primary-color);
      background: #EBF5FF;
    }

    .prompt-icon {
      font-size: 18px;
      flex-shrink: 0;
    }

    .prompt-text {
      font-size: 13px;
      color: var(--text-secondary);
    }
  }
}

.message-item {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;

  &.assistant {
    .message-body {
      .message-content {
        background: #F5F7FA;
        border-radius: 2px 12px 12px 12px;
      }
    }
  }

  &.user {
    flex-direction: row-reverse;

    .message-body {
      align-items: flex-end;

      .message-role {
        text-align: right;
      }

      .message-footer {
        flex-direction: row-reverse;
      }

      .message-content {
        background: var(--primary-color);
        color: #fff;
        border-radius: 12px 2px 12px 12px;

        :deep(code) {
          background: rgba(255, 255, 255, 0.15);
          color: #fff;
        }
      }
    }
  }

  &:hover .msg-copy-btn {
    opacity: 1;
  }

  .message-avatar {
    flex-shrink: 0;
    margin-top: 2px;

    .avatar-ai, .avatar-user {
      width: 32px;
      height: 32px;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 16px;
    }

    .avatar-ai {
      background: #EBF5FF;
      color: var(--primary-color);
    }

    .avatar-user {
      background: var(--primary-color);
      color: #fff;
    }
  }

  .message-body {
    display: flex;
    flex-direction: column;
    max-width: 70%;

    .message-role {
      font-size: 12px;
      color: var(--text-muted);
      margin-bottom: 4px;
    }

    .message-footer {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 4px;
      min-height: 22px;
    }

    .message-time {
      font-size: 11px;
      color: var(--text-muted);
    }

    .message-content {
      padding: 10px 14px;
      font-size: 14px;
      line-height: 1.6;
      word-break: break-word;

      :deep(p) {
        margin: 0 0 8px 0;
        &:last-child { margin-bottom: 0; }
      }

      :deep(h1), :deep(h2), :deep(h3), :deep(h4), :deep(h5), :deep(h6) {
        margin: 12px 0 8px 0;
        font-weight: 600;
        line-height: 1.4;
        &:first-child { margin-top: 0; }
      }
      :deep(h1) { font-size: 1.3em; }
      :deep(h2) { font-size: 1.2em; }
      :deep(h3) { font-size: 1.1em; }

      :deep(ul), :deep(ol) {
        margin: 4px 0 8px 0;
        padding-left: 20px;
      }

      :deep(li) {
        margin-bottom: 4px;
      }

      :deep(code) {
        background: rgba(0, 0, 0, 0.06);
        padding: 2px 5px;
        border-radius: 3px;
        font-size: 0.9em;
        font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
      }

      :deep(pre.hljs) {
        background: #f6f8fa;
        border-radius: 6px;
        padding: 12px 16px;
        margin: 8px 0;
        overflow-x: auto;
        font-size: 13px;
        line-height: 1.5;

        code {
          background: none;
          padding: 0;
          border-radius: 0;
          font-size: inherit;
        }
      }

      :deep(blockquote) {
        margin: 8px 0;
        padding: 4px 12px;
        border-left: 3px solid #ddd;
        color: #666;
      }

      :deep(table) {
        border-collapse: collapse;
        margin: 8px 0;
        width: 100%;
        font-size: 13px;
      }

      :deep(th), :deep(td) {
        border: 1px solid #ddd;
        padding: 6px 10px;
        text-align: left;
      }

      :deep(th) {
        background: #f5f7fa;
        font-weight: 600;
      }

      :deep(a) {
        color: var(--primary-color);
        text-decoration: none;
        &:hover { text-decoration: underline; }
      }

      :deep(strong) {
        font-weight: 600;
      }

      :deep(hr) {
        border: none;
        border-top: 1px solid #eee;
        margin: 12px 0;
      }
    }
  }
}

.msg-copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1;
  color: var(--text-muted);
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s, color 0.15s, background 0.15s;

  .msg-copy-label {
    font-size: 11px;
  }

  &:hover {
    opacity: 1;
    color: var(--primary-color);
    background: rgba(22, 119, 255, 0.08);
  }

  &.copied {
    opacity: 1;
    color: #16a34a;
    background: rgba(22, 163, 74, 0.08);
  }
}

.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px !important;

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--text-muted);
    animation: typing 1.2s infinite;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

@keyframes typing {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

.chat-input-area {
  flex-shrink: 0;
  padding: 12px 20px 16px;
  border-top: 1px solid var(--border-color);

  .input-wrapper {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    background: #F5F7FA;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 8px 8px 8px 14px;
    transition: border-color 0.2s;

    &:focus-within {
      border-color: var(--primary-color);
    }
  }

  .chat-input {
    flex: 1;
    border: none;
    background: transparent;
    box-shadow: none !important;
    resize: none;
    font-size: 14px;

    :deep(.ant-input) {
      background: transparent;
    }

    &:focus {
      box-shadow: none;
    }
  }

  .send-btn {
    width: 36px;
    height: 36px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .stop-btn {
    background: #fff;
  }
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 20px;

  .settings-item {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .settings-label {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
  }

  .settings-hint {
    font-size: 12px;
    color: var(--text-muted);
  }
}

.msg-edit-box {
  margin-top: 8px;
  width: 100%;
  max-width: 100%;

  .msg-edit-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 8px;
  }
}
</style>
