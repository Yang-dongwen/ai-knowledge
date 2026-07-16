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
                <span class="conv-title">{{ conv.title || '新对话' }}</span>
                <span class="conv-model" v-if="conv.model">{{ getModelDisplayName(conv.provider, conv.model) }}</span>
              </div>
            </div>
            <DeleteOutlined
              class="conv-delete"
              @click.stop="handleDeleteConversation(conv.id)"
            />
          </div>
          <a-empty v-if="conversations.length === 0" description="暂无会话" :image-style="{ height: '40px' }" />
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
            <span class="chat-hint">纯聊天 · 可切换模型</span>
          </div>
        </div>

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
              :placeholder="isLoading ? 'AI 正在思考中...' : '输入消息，Enter 发送 · Shift+Enter 换行'"
              :auto-size="{ minRows: 1, maxRows: 4 }"
              :disabled="isLoading || availableProviders.length === 0"
              @keydown.enter="onEnterKey"
              class="chat-input"
            />
            <a-button
              type="primary"
              :disabled="!inputMessage.trim() || isLoading || availableProviders.length === 0"
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
  ExperimentOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { chatApi } from '@/api/chat.api'
import { videoApi } from '@/api/video.api'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
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

const displayMessages = computed(() => {
  if (isLoading.value && messages.value.length > 0) {
    const last = messages.value[messages.value.length - 1]
    if (last.role === 'assistant' && !last.content) {
      return messages.value.slice(0, -1)
    }
  }
  return messages.value
})

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
    const res = await chatApi.getConversations()
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
  activeConversationId.value = id
  const conv = conversations.value.find(c => c.id === id)
  if (conv && conv.provider && conv.model) {
    selectedModelKey.value = `${conv.provider}::${conv.model}`
  }
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

/** Enter 发送；Shift+Enter 换行；输入法组字中不拦截 */
function onEnterKey(e: KeyboardEvent) {
  if (e.shiftKey) return
  // 中文等输入法确认选字时不要发送
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  e.stopPropagation()
  void handleSend()
}

async function handleSend() {
  const text = inputMessage.value.trim()
  if (!text || isLoading.value) return
  if (!selectedModelKey.value) return

  // 先清空输入框，避免 a-textarea 在 Enter 默认行为后把文本写回
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
      model: model || undefined
    },
    {
      onMeta(data) {
        if (data.conversationId && activeConversationId.value.startsWith('local_')) {
          activeConversationId.value = data.conversationId
          const conv = conversations.value.find(c => String(c.id).startsWith('local_'))
          if (conv) {
            conv.id = data.conversationId
            conv.title = text.slice(0, 20) || '新对话'
            if (data.provider) conv.provider = data.provider
            if (data.model) conv.model = data.model
          }
        }
        // 同步当前会话的模型展示
        const conv = conversations.value.find(c => c.id === activeConversationId.value)
        if (conv) {
          if (provider) conv.provider = provider
          if (model) conv.model = model
        }
      },
      onDelta(data) {
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          content: messages.value[assistantMsgIndex].content + data.content
        }
        scrollToBottom()
      },
      onDone(_data) {
        isLoading.value = false
        currentAbortController = null
        scrollToBottom()
      },
      onError(data) {
        const errText = data.message || '抱歉，请求失败，请稍后重试。'
        const prev = messages.value[assistantMsgIndex].content || ''
        // 无内容则直接展示错误；已有部分流式内容则追加超时/错误说明
        const next = prev
          ? (prev.includes(errText) ? prev : `${prev}\n\n${errText}`)
          : errText
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          content: next
        }
        isLoading.value = false
        currentAbortController = null
        scrollToBottom()
      }
    }
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
.ai-chat-page {
  height: calc(100vh - 56px - 56px);
  padding: 0;
  overflow: hidden;
}

.chat-container {
  display: flex;
  height: 100%;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--card-radius);
  overflow: hidden;
}

.chat-sidebar {
  width: 240px;
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  background: #FAFBFC;

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

  .conversation-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  .conversation-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 2px;
    transition: all 0.2s;

    &:hover {
      background: #EBF5FF;
    }

    &.active {
      background: #EBF5FF;
      .conv-title { color: var(--primary-color); font-weight: 500; }
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

    .conv-delete {
      font-size: 12px;
      color: var(--text-muted);
      opacity: 0;
      transition: opacity 0.2s;
      flex-shrink: 0;
      margin-left: 4px;

      &:hover {
        color: var(--danger-color);
      }
    }

    &:hover .conv-delete {
      opacity: 1;
    }
  }
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
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
  flex: 1;
  overflow-y: auto;
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
}
</style>
