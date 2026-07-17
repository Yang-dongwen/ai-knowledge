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
            <div class="agent-switch" title="开启后可查任务、创建任务（需确认）">
              <span class="agent-switch-label">Agent</span>
              <a-switch v-model:checked="agentMode" size="small" :disabled="isLoading" />
            </div>
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
                v-for="prompt in displayPrompts"
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
                <!-- Agent 工具卡 / 确认卡 -->
                <div
                  v-if="msg.toolResult"
                  class="agent-card"
                  :class="agentCardClass(msg)"
                >
                  <div class="agent-card-accent" aria-hidden="true" />
                  <div class="agent-card-body">
                    <div class="agent-card-top">
                      <div class="agent-card-icon" aria-hidden="true">{{ agentCardIcon(msg) }}</div>
                      <div class="agent-card-titles">
                        <div class="agent-card-title-row">
                          <span class="agent-card-badge">{{ agentCardBadge(msg) }}</span>
                          <span class="agent-card-tool">{{ humanToolName(msg.toolResult.tool) }}</span>
                        </div>
                        <div v-if="agentCardSubtitle(msg)" class="agent-card-sub">
                          {{ agentCardSubtitle(msg) }}
                        </div>
                      </div>
                    </div>

                    <!-- 确认参数（可编辑） -->
                    <div v-if="isConfirmCard(msg) && confirmArgs(msg)" class="agent-param-list">
                      <template v-if="!isConfirmSettledFinal(msg)">
                        <!-- 提示词 -->
                        <div v-if="'prompt' in ensureDraftArgs(msg)" class="agent-param-field">
                          <label class="agent-param-k">提示词</label>
                          <a-textarea
                            v-model:value="ensureDraftArgs(msg).prompt"
                            :rows="3"
                            :maxlength="confirmToolOf(msg) === 'draft_aigen' ? 4000 : 2000"
                            show-count
                            :disabled="isConfirmLocked(msg)"
                            class="agent-param-input"
                            placeholder="描述你想生成的内容"
                          />
                        </div>
                        <!-- 视频链接 -->
                        <div v-if="'url' in ensureDraftArgs(msg)" class="agent-param-field">
                          <label class="agent-param-k">链接</label>
                          <a-input
                            v-model:value="ensureDraftArgs(msg).url"
                            :disabled="isConfirmLocked(msg)"
                            class="agent-param-input"
                            placeholder="https://..."
                            allow-clear
                          />
                        </div>
                        <!-- 画幅 -->
                        <div v-if="'aspectRatio' in ensureDraftArgs(msg)" class="agent-param-field agent-param-inline">
                          <label class="agent-param-k">画幅</label>
                          <a-radio-group
                            v-model:value="ensureDraftArgs(msg).aspectRatio"
                            :disabled="isConfirmLocked(msg)"
                            button-style="solid"
                            size="small"
                            class="agent-param-radios"
                          >
                            <a-radio-button
                              v-for="opt in aspectOptionsFor(msg)"
                              :key="opt"
                              :value="opt"
                            >{{ opt }}</a-radio-button>
                          </a-radio-group>
                        </div>
                        <!-- 张数 -->
                        <div v-if="'n' in ensureDraftArgs(msg)" class="agent-param-field agent-param-inline">
                          <label class="agent-param-k">数量</label>
                          <a-radio-group
                            v-model:value="ensureDraftArgs(msg).n"
                            :disabled="isConfirmLocked(msg)"
                            button-style="solid"
                            size="small"
                            class="agent-param-radios"
                          >
                            <a-radio-button v-for="n in [1, 2, 3, 4]" :key="n" :value="n">
                              {{ n }} 张
                            </a-radio-button>
                          </a-radio-group>
                        </div>
                        <!-- 视频时长 -->
                        <div v-if="'targetDurationSec' in ensureDraftArgs(msg)" class="agent-param-field agent-param-inline">
                          <label class="agent-param-k">时长</label>
                          <a-select
                            v-model:value="ensureDraftArgs(msg).targetDurationSec"
                            :disabled="isConfirmLocked(msg)"
                            class="agent-param-select"
                            :options="durationOptions"
                            size="middle"
                          />
                        </div>
                        <!-- 生图模型：文生图 / AI 视频 -->
                        <div
                          v-if="needsImageModel(msg)"
                          class="agent-param-field"
                        >
                          <label class="agent-param-k">生图模型</label>
                          <a-select
                            v-model:value="ensureDraftArgs(msg).imageModelKey"
                            :disabled="isConfirmLocked(msg)"
                            :loading="agentModelsLoading"
                            :options="imageModelSelectOptions"
                            class="agent-param-input agent-model-select"
                            placeholder="选择生图模型"
                            show-search
                            option-filter-prop="label"
                            allow-clear
                          />
                        </div>
                        <!-- LLM 模型：AI 视频 / 视频提取 -->
                        <div
                          v-if="needsLlmModel(msg)"
                          class="agent-param-field"
                        >
                          <label class="agent-param-k">{{ llmModelLabel(msg) }}</label>
                          <a-select
                            v-model:value="ensureDraftArgs(msg).llmModelKey"
                            :disabled="isConfirmLocked(msg)"
                            :loading="agentModelsLoading"
                            :options="llmModelSelectOptions"
                            class="agent-param-input agent-model-select"
                            placeholder="选择模型"
                            show-search
                            option-filter-prop="label"
                            allow-clear
                          />
                        </div>
                        <div
                          v-if="needsImageModel(msg) && !agentModelsLoading && !imageModelSelectOptions.length"
                          class="agent-param-hint"
                        >暂无生图模型，请先在文生图页配置</div>
                        <div
                          v-if="needsLlmModel(msg) && !agentModelsLoading && !llmModelSelectOptions.length"
                          class="agent-param-hint"
                        >暂无可用 LLM，请先在模型管理中配置</div>
                      </template>
                      <!-- 终态只读展示 -->
                      <template v-else>
                        <div
                          v-for="(v, k) in confirmArgs(msg)"
                          :key="String(k)"
                          class="agent-param-row"
                        >
                          <span class="agent-param-k">{{ humanArgKey(String(k)) }}</span>
                          <span class="agent-param-v">{{ formatArgValue(v) }}</span>
                        </div>
                      </template>
                    </div>

                    <!-- 确认操作：未结束才显示按钮；结束后只显示状态 -->
                    <div v-if="isConfirmCard(msg)" class="agent-card-footer">
                      <template v-if="!isConfirmSettledFinal(msg)">
                        <p class="agent-card-tip">可先调整下方参数，确认后创建任务并执行（不可撤销）</p>
                        <div class="agent-card-btns">
                          <a-button
                            size="middle"
                            class="agent-btn-ghost"
                            :disabled="isConfirmLocked(msg)"
                            @click="handleRejectConfirm(msg)"
                          >取消</a-button>
                          <a-button
                            type="primary"
                            size="middle"
                            class="agent-btn-primary"
                            :loading="isConfirmPending(msg)"
                            :disabled="isConfirmLocked(msg)"
                            @click="handleAcceptConfirm(msg)"
                          >
                            {{ isConfirmPending(msg) ? '提交中…' : '确认执行' }}
                          </a-button>
                        </div>
                      </template>
                      <div v-else class="agent-card-done" :class="'done-' + msg.confirmSettled">
                        <span class="agent-card-done-dot" />
                        <span>{{
                          msg.confirmSettled === 'ok'
                            ? '已确认并创建'
                            : msg.confirmSettled === 'rejected'
                              ? '已取消，未执行'
                              : '已处理'
                        }}</span>
                      </div>
                    </div>

                    <!-- 创建成功 -->
                    <div v-if="isCreatedCard(msg)" class="agent-created">
                      <div class="agent-created-row">
                        <a-tag color="processing">{{ typeLabel(createdPayload(msg)?.type) }}</a-tag>
                        <span class="agent-created-id">#{{ createdPayload(msg)?.taskId }}</span>
                        <a-tag :color="statusColor(createdPayload(msg)?.status)">
                          {{ createdPayload(msg)?.status || '-' }}
                        </a-tag>
                      </div>
                      <div
                        v-if="createdModelText(msg)"
                        class="agent-created-model"
                      >模型：{{ createdModelText(msg) }}</div>
                      <div class="agent-created-title">
                        {{ createdPayload(msg)?.title || createdPayload(msg)?.prompt || '任务已创建' }}
                      </div>
                      <a-button
                        v-if="createdPayload(msg)?.openPath"
                        type="link"
                        size="small"
                        class="agent-created-link"
                        @click="goPath(createdPayload(msg)!.openPath!)"
                      >前往任务页 →</a-button>
                    </div>

                    <!-- 任务列表 -->
                    <div v-if="toolListItems(msg).length" class="agent-task-list">
                      <div
                        v-for="(item, idx) in toolListItems(msg)"
                        :key="(item.taskId || '') + '-' + idx"
                        class="agent-task-row"
                      >
                        <div class="agent-task-left">
                          <a-tag>{{ typeLabel(item.type) }}</a-tag>
                          <span class="agent-task-name">{{ item.title || item.prompt || item.taskId }}</span>
                        </div>
                        <div class="agent-task-right">
                          <a-tag :color="statusColor(item.status)">{{ item.status || '-' }}</a-tag>
                          <a
                            v-if="item.openPath"
                            class="agent-task-link"
                            href="javascript:;"
                            @click="goPath(item.openPath)"
                          >打开</a>
                        </div>
                      </div>
                    </div>

                    <!-- 单任务 -->
                    <div v-if="toolSingleTask(msg)" class="agent-task-list">
                      <div class="agent-task-row">
                        <div class="agent-task-left">
                          <a-tag>{{ typeLabel(toolSingleTask(msg)!.type) }}</a-tag>
                          <span class="agent-task-name">
                            {{ toolSingleTask(msg)!.title || toolSingleTask(msg)!.taskId }}
                          </span>
                        </div>
                        <div class="agent-task-right">
                          <a-tag :color="statusColor(toolSingleTask(msg)!.status)">
                            {{ toolSingleTask(msg)!.status }}
                          </a-tag>
                          <a
                            v-if="toolSingleTask(msg)!.openPath"
                            class="agent-task-link"
                            href="javascript:;"
                            @click="goPath(toolSingleTask(msg)!.openPath!)"
                          >打开</a>
                        </div>
                      </div>
                    </div>

                    <!-- 模型列表 -->
                    <div v-if="toolModels(msg).length" class="agent-model-list">
                      <div v-for="p in toolModels(msg)" :key="p.key" class="agent-model-group">
                        <div class="agent-model-provider">{{ p.name || p.key }}</div>
                        <div class="agent-model-tags">
                          <a-tag v-for="m in (p.models || [])" :key="m.id">{{ m.name || m.id }}</a-tag>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                <div
                  v-if="msg.content"
                  class="message-content"
                  v-html="renderContent(msg.content, msg.role)"
                ></div>
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
              v-if="isLoading && messages.length > 0 && messages[messages.length - 1].role === 'assistant' && !messages[messages.length - 1].content && !messages[messages.length - 1].toolResult"
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
                <div v-if="agentPhaseLabel" class="agent-phase-label">{{ agentPhaseLabel }}</div>
              </div>
            </div>
          </template>
        </div>

        <!-- 输入区域：无会话时也可直接发送（自动建会话） -->
        <div class="chat-input-area">
          <div class="input-wrapper">
            <a-textarea
              v-model:value="inputMessage"
              :placeholder="isLoading ? (agentPhaseLabel || 'AI 正在生成… 可点击停止') : (agentMode ? 'Agent 模式：可查任务、生成图片/视频（写操作需确认）' : '输入消息，Enter 发送 · Shift+Enter 换行')"
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
import { ref, reactive, onMounted, computed, nextTick } from 'vue'
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
import { imggenApi } from '@/api/imggen.api'
import { useAuthStore } from '@/stores/auth.store'
import ModelManageModal from '@/views/video-extract/ModelManageModal.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useRouter } from 'vue-router'
import type {
  ChatMessage,
  ChatConversation,
  AiProvider,
  AgentTaskSummary,
  AgentToolResultEvent,
  ImgGenImageModel
} from '@/types/api'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const auth = useAuthStore()
const router = useRouter()

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
/** Agent 模式 */
const agentMode = ref(false)
const confirmBusyId = ref('')
/** Agent 阶段文案：分析意图 / 调用工具 / 整理结果 */
const agentPhaseLabel = ref('')
type UiChatMessage = ChatMessage & {
  toolResult?: AgentToolResultEvent
  /** pending=提交中锁定；ok/rejected=终态，按钮消失 */
  confirmSettled?: 'ok' | 'rejected' | 'pending'
  /** 确认卡上可编辑的参数草稿（用户可改） */
  confirmDraftArgs?: Record<string, any>
}

const durationOptions = [
  { label: '5 秒', value: 5 },
  { label: '10 秒', value: 10 },
  { label: '15 秒', value: 15 },
  { label: '20 秒', value: 20 },
  { label: '30 秒', value: 30 },
  { label: '45 秒', value: 45 },
  { label: '60 秒', value: 60 }
]
const messages = ref<UiChatMessage[]>([])

const displayMessages = computed(() => {
  if (isLoading.value && messages.value.length > 0) {
    const last = messages.value[messages.value.length - 1]
    if (last.role === 'assistant' && !last.content && !last.toolResult) {
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

const agentPrompts = [
  { icon: '🖼️', text: '查看我最近的文生图任务' },
  { icon: '📋', text: '查看我最近的视频提取任务' },
  { icon: '🎨', text: '帮我生成一张赛博朋克风格的猫' },
  { icon: '🎬', text: '做一个15秒的科技感短视频，主题是人工智能' },
  { icon: '🔗', text: '帮我提取这个视频的内容（请附上链接）' },
  { icon: '🧩', text: '当前有哪些可用的 Chat 模型？' }
]

const displayPrompts = computed(() => (agentMode.value ? agentPrompts : defaultPrompts))

function toolListItems(msg: UiChatMessage): AgentTaskSummary[] {
  const ui = msg.toolResult?.ui
  if (!ui || ui.type !== 'task_list') return []
  const items = ui.payload?.items
  return Array.isArray(items) ? items : []
}

function toolSingleTask(msg: UiChatMessage): AgentTaskSummary | null {
  const ui = msg.toolResult?.ui
  if (!ui || ui.type !== 'task_status') return null
  return (ui.payload?.task as AgentTaskSummary) || null
}

function toolModels(msg: UiChatMessage): Array<{ key: string; name?: string; models?: Array<{ id: string; name?: string }> }> {
  const ui = msg.toolResult?.ui
  if (!ui || ui.type !== 'model_list') return []
  const providers = ui.payload?.providers
  return Array.isArray(providers) ? providers : []
}

function statusColor(status?: string): string {
  const s = (status || '').toUpperCase()
  if (s.includes('SUCCESS') || s === 'DONE' || s === 'COMPLETED') return 'success'
  if (s.includes('FAIL') || s.includes('ERROR')) return 'error'
  if (s.includes('RUN') || s.includes('PEND') || s.includes('PROCESS')) return 'processing'
  if (s.includes('CANCEL')) return 'default'
  return 'blue'
}

function goPath(path: string) {
  if (!path) return
  router.push(path)
}

function isConfirmCard(msg: UiChatMessage): boolean {
  if (!msg.toolResult) return false
  const t = msg.toolResult.ui?.type
  // 仅「待确认」为确认卡；已拒绝/已失效走结果态，不展示操作按钮
  if (t === 'confirm_rejected' || t === 'confirm_expired' || t === 'task_created') {
    return false
  }
  if (t === 'confirm') return true
  // 兼容无 ui 的旧数据
  return !t && !!(msg.toolResult.data?.confirmId || msg.toolResult.ui?.payload?.confirmId)
}

function isCreatedCard(msg: UiChatMessage): boolean {
  return msg.toolResult?.ui?.type === 'task_created'
}

function confirmIdOf(msg: UiChatMessage): string {
  return String(msg.toolResult?.data?.confirmId || msg.toolResult?.ui?.payload?.confirmId || '')
}

/** 提交中 / 已终态 → 双按钮禁用，防重复点 */
function isConfirmLocked(msg: UiChatMessage): boolean {
  if (msg.confirmSettled) return true
  const id = confirmIdOf(msg)
  return !!id && confirmBusyId.value === id
}

/** 是否正在提交（含 busy 与 pending 态） */
function isConfirmPending(msg: UiChatMessage): boolean {
  if (msg.confirmSettled === 'pending') return true
  const id = confirmIdOf(msg)
  return !!id && confirmBusyId.value === id
}

/** 是否已到终态（ok / rejected），用于隐藏按钮只展示结果 */
function isConfirmSettledFinal(msg: UiChatMessage): boolean {
  return msg.confirmSettled === 'ok' || msg.confirmSettled === 'rejected'
}

function formatArgValue(v: unknown): string {
  if (v == null) return '-'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

function confirmArgs(msg: UiChatMessage): Record<string, any> | null {
  const args = msg.toolResult?.data?.args || msg.toolResult?.ui?.payload?.args
  if (!args || typeof args !== 'object') return null
  return args
}

/** 按 confirmId/消息 id 缓存可编辑参数（独立 reactive，保证 v-model 稳定） */
const confirmDrafts = reactive<Record<string, Record<string, any>>>({})

/** 确认卡可选的任务模型目录 */
const agentLlmProviders = ref<AiProvider[]>([])
const agentImageModels = ref<ImgGenImageModel[]>([])
const agentModelsLoading = ref(false)

const imageModelSelectOptions = computed(() =>
  agentImageModels.value.map((m) => ({
    value: `${m.provider || 'nvidia'}::${m.id}`,
    label: `${m.name || m.id}${m.provider ? ` · ${m.provider}` : ''}${m.defaultModel ? '（默认）' : ''}`
  }))
)

const llmModelSelectOptions = computed(() => {
  const opts: Array<{ value: string; label: string }> = []
  for (const p of agentLlmProviders.value) {
    for (const m of p.models || []) {
      opts.push({
        value: `${p.key}::${m.id}`,
        label: `${m.name || m.id} · ${p.name || p.key}`
      })
    }
  }
  return opts
})

function draftKeyOf(msg: UiChatMessage): string {
  return confirmIdOf(msg) || String(msg.id || '')
}

function defaultImageModelKey(): string {
  const def = agentImageModels.value.find((m) => m.defaultModel) || agentImageModels.value[0]
  if (!def) return ''
  return `${def.provider || 'nvidia'}::${def.id}`
}

function defaultLlmModelKey(): string {
  const p = agentLlmProviders.value[0]
  const m = p?.models?.[0]
  if (!p || !m) return ''
  return `${p.key}::${m.id}`
}

function parseCompositeModelKey(key: string): { provider: string; model: string } | null {
  if (!key || !key.includes('::')) return null
  const i = key.indexOf('::')
  const provider = key.slice(0, i).trim()
  const model = key.slice(i + 2).trim()
  if (!provider || !model) return null
  return { provider, model }
}

/** 确保有可编辑草稿（首次从 toolResult.args 拷贝） */
function ensureDraftArgs(msg: UiChatMessage): Record<string, any> {
  const key = draftKeyOf(msg)
  if (!key) return {}
  if (!confirmDrafts[key]) {
    const base = confirmArgs(msg) || {}
    const draft: Record<string, any> = { ...base }
    if ('n' in draft) draft.n = Number(draft.n) || 1
    if ('targetDurationSec' in draft) {
      draft.targetDurationSec = Number(draft.targetDurationSec) || 15
    }
    // 从后端 args 还原模型选择（若有）
    if (draft.imageProvider && draft.imageModel && !draft.imageModelKey) {
      draft.imageModelKey = `${draft.imageProvider}::${draft.imageModel}`
    }
    if (draft.llmProvider && draft.llmModel && !draft.llmModelKey) {
      draft.llmModelKey = `${draft.llmProvider}::${draft.llmModel}`
    }
    confirmDrafts[key] = draft
  }
  // 列表加载完成后补默认模型
  applyDefaultModels(confirmDrafts[key], confirmToolOf(msg))
  return confirmDrafts[key]
}

function applyDefaultModels(draft: Record<string, any>, tool: string) {
  if (!draft) return
  if (needsImageModelByTool(tool)) {
    if (!draft.imageModelKey) {
      draft.imageModelKey = defaultImageModelKey()
    }
  }
  if (needsLlmModelByTool(tool)) {
    if (!draft.llmModelKey) {
      draft.llmModelKey = defaultLlmModelKey()
    }
  }
}

function confirmToolOf(msg: UiChatMessage): string {
  return String(
    msg.toolResult?.tool
    || msg.toolResult?.data?.tool
    || msg.toolResult?.ui?.payload?.tool
    || ''
  )
}

function needsImageModelByTool(tool: string): boolean {
  return tool === 'draft_imggen' || tool === 'draft_aigen'
}

function needsLlmModelByTool(tool: string): boolean {
  return tool === 'draft_aigen' || tool === 'draft_video_extract'
}

function needsImageModel(msg: UiChatMessage): boolean {
  return needsImageModelByTool(confirmToolOf(msg))
}

function needsLlmModel(msg: UiChatMessage): boolean {
  return needsLlmModelByTool(confirmToolOf(msg))
}

function llmModelLabel(msg: UiChatMessage): string {
  const tool = confirmToolOf(msg)
  if (tool === 'draft_video_extract') return '分析模型'
  if (tool === 'draft_aigen') return '脚本模型'
  return '模型'
}

function aspectOptionsFor(msg: UiChatMessage): string[] {
  const tool = confirmToolOf(msg)
  if (tool === 'draft_aigen') return ['9:16', '16:9', '1:1']
  // imggen 默认
  return ['1:1', '16:9', '9:16']
}

function collectConfirmArgs(msg: UiChatMessage): Record<string, any> {
  const draft = ensureDraftArgs(msg)
  const out: Record<string, any> = {}
  for (const [k, v] of Object.entries(draft)) {
    if (v === undefined || v === null || v === '') continue
    // 前端复合键展开为后端字段，不直接传 *ModelKey
    if (k === 'imageModelKey' || k === 'llmModelKey') continue
    if (k === 'n' || k === 'targetDurationSec') {
      out[k] = Number(v)
    } else {
      out[k] = typeof v === 'string' ? v.trim() : v
    }
  }
  const img = parseCompositeModelKey(String(draft.imageModelKey || ''))
  if (img && needsImageModel(msg)) {
    out.imageProvider = img.provider
    out.imageModel = img.model
  }
  const llm = parseCompositeModelKey(String(draft.llmModelKey || ''))
  if (llm && needsLlmModel(msg)) {
    out.llmProvider = llm.provider
    out.llmModel = llm.model
  }
  return out
}

async function loadAgentTaskModels() {
  agentModelsLoading.value = true
  try {
    const [llmRes, imgRes] = await Promise.all([
      videoApi.listModels().catch(() => ({ data: [] as AiProvider[] })),
      imggenApi.listImageModels().catch(() => ({ data: [] as ImgGenImageModel[] }))
    ])
    agentLlmProviders.value = (llmRes as any).data || []
    agentImageModels.value = (imgRes as any).data || []
  } catch {
    agentLlmProviders.value = []
    agentImageModels.value = []
  } finally {
    agentModelsLoading.value = false
  }
}

function createdPayload(msg: UiChatMessage): {
  type?: string
  taskId?: string
  status?: string
  title?: string
  prompt?: string
  openPath?: string
  model?: string
  provider?: string
  llmModel?: string
  imageModel?: string
} | null {
  if (!isCreatedCard(msg)) return null
  return (msg.toolResult?.data || msg.toolResult?.ui?.payload || null) as any
}

function createdModelText(msg: UiChatMessage): string {
  const p = createdPayload(msg)
  if (!p) return ''
  const parts: string[] = []
  if (p.model) parts.push(p.model)
  if (p.imageModel && p.imageModel !== p.model) parts.push(`出图 ${p.imageModel}`)
  if (p.llmModel) parts.push(`LLM ${p.llmModel}`)
  if (!parts.length && p.provider) parts.push(p.provider)
  return parts.join(' · ')
}

function patchMessage(msg: UiChatMessage, patch: Partial<UiChatMessage>) {
  const idx = messages.value.findIndex(m => m.id === msg.id)
  if (idx < 0) {
    Object.assign(msg, patch)
    return
  }
  // 替换数组项触发视图更新，同时回写调用方引用，避免后续逻辑读到旧对象
  messages.value[idx] = { ...messages.value[idx], ...patch }
  Object.assign(msg, messages.value[idx])
}

function agentCardClass(msg: UiChatMessage): string {
  if (msg.confirmSettled === 'pending') return 'is-confirm is-pending'
  if (isConfirmCard(msg) && !msg.confirmSettled) return 'is-confirm'
  if (msg.confirmSettled === 'rejected' || msg.toolResult?.ui?.type === 'confirm_rejected') {
    return 'is-rejected'
  }
  if (msg.toolResult?.ui?.type === 'confirm_expired' || msg.toolResult?.ok === false) return 'is-fail'
  if (isCreatedCard(msg) || msg.confirmSettled === 'ok') return 'is-success'
  return 'is-info'
}

function agentCardIcon(msg: UiChatMessage): string {
  if (msg.confirmSettled === 'pending') return '…'
  if (isConfirmCard(msg) && !msg.confirmSettled) return '⚡'
  if (msg.confirmSettled === 'rejected' || msg.toolResult?.ui?.type === 'confirm_rejected') return '✕'
  if (msg.toolResult?.ui?.type === 'confirm_expired' || msg.toolResult?.ok === false) return '!'
  if (isCreatedCard(msg) || msg.confirmSettled === 'ok') return '✓'
  if (msg.toolResult?.ui?.type === 'model_list') return '🧩'
  return '◎'
}

function agentCardBadge(msg: UiChatMessage): string {
  if (msg.confirmSettled === 'pending') return '提交中'
  if (isConfirmCard(msg) && !msg.confirmSettled) return '待确认'
  if (msg.confirmSettled === 'rejected' || msg.toolResult?.ui?.type === 'confirm_rejected') return '已取消'
  if (msg.toolResult?.ui?.type === 'confirm_expired') return '已失效'
  if (msg.toolResult?.ok === false) return '失败'
  if (isCreatedCard(msg) || msg.confirmSettled === 'ok') return '已创建'
  if (msg.toolResult?.ui?.type === 'task_list') return '任务列表'
  if (msg.toolResult?.ui?.type === 'task_status') return '任务详情'
  if (msg.toolResult?.ui?.type === 'model_list') return '模型列表'
  return '工具结果'
}

function agentCardSubtitle(msg: UiChatMessage): string {
  if (isConfirmCard(msg) && (!msg.confirmSettled || msg.confirmSettled === 'pending')) {
    return msg.toolResult?.data?.summary || msg.toolResult?.message || ''
  }
  if (isCreatedCard(msg)) return ''
  return msg.toolResult?.message || ''
}

function humanToolName(tool?: string): string {
  const map: Record<string, string> = {
    draft_imggen: '文生图',
    draft_aigen: 'AI 视频生成',
    draft_video_extract: '视频提取',
    list_my_tasks: '查询任务',
    get_task: '任务详情',
    list_chat_models: 'Chat 模型'
  }
  if (!tool) return '工具'
  return map[tool] || tool
}

function humanArgKey(key: string): string {
  const map: Record<string, string> = {
    prompt: '提示词',
    aspectRatio: '画幅',
    n: '数量',
    url: '链接',
    targetDurationSec: '时长(秒)',
    type: '类型',
    limit: '条数',
    taskId: '任务ID',
    imageModel: '生图模型',
    imageProvider: '生图供应商',
    llmModel: 'LLM 模型',
    llmProvider: 'LLM 供应商',
    imageModelKey: '生图模型',
    llmModelKey: '模型'
  }
  return map[key] || key
}

function typeLabel(type?: string): string {
  const map: Record<string, string> = {
    imggen: '文生图',
    aigen: '视频生成',
    video: '视频提取',
    image: '文生图'
  }
  return (type && map[type]) || type || '-'
}

async function handleAcceptConfirm(msg: UiChatMessage) {
  const id = confirmIdOf(msg)
  // 已结束 / 正在请求中 → 直接忽略，防连点
  if (!id || msg.confirmSettled || confirmBusyId.value) return

  const args = collectConfirmArgs(msg)
  // 前端轻量校验
  if ('prompt' in args && !String(args.prompt || '').trim()) {
    message.warning('请填写提示词')
    return
  }
  if ('url' in args) {
    const url = String(args.url || '').trim()
    if (!/^https?:\/\/\S+/i.test(url)) {
      message.warning('请填写有效的视频链接（http/https）')
      return
    }
  }

  confirmBusyId.value = id
  // 同步写入 pending：立即禁用双按钮并展示 loading
  patchMessage(msg, { confirmSettled: 'pending', confirmDraftArgs: { ...args } })
  try {
    const res = await chatApi.agentConfirm(id, args)
    const result = (res as any).data
    const nextTool: AgentToolResultEvent = {
      tool: msg.toolResult?.tool,
      ok: result?.ok !== false,
      message: result?.message,
      data: result?.data,
      ui: result?.ui || (result?.data ? { type: 'task_created', payload: result.data } : undefined)
    }
    const nextContent = result?.message
      ? ((msg.content || '').replace(/\n\n\[\[AGENT_CONFIRM\]\][\s\S]*$/, '').trim()
        + (msg.content ? '\n\n' : '') + result.message)
      : msg.content
    patchMessage(msg, {
      confirmSettled: 'ok',
      toolResult: nextTool,
      content: nextContent
    })
    if (result?.ok === false) {
      message.error(result?.message || '执行失败')
    } else {
      message.success('已创建任务')
    }
    scrollToBottom()
  } catch (e: any) {
    // 仅网络/接口失败允许重试
    patchMessage(msg, { confirmSettled: undefined })
    message.error(e?.message || '确认失败，可能已过期')
  } finally {
    if (confirmBusyId.value === id) {
      confirmBusyId.value = ''
    }
  }
}

async function handleRejectConfirm(msg: UiChatMessage) {
  const id = confirmIdOf(msg)
  if (!id || msg.confirmSettled || confirmBusyId.value) return
  confirmBusyId.value = id
  // 立即锁定，防止连点
  patchMessage(msg, { confirmSettled: 'pending' })
  try {
    await chatApi.agentReject(id)
    patchMessage(msg, { confirmSettled: 'rejected' })
    message.info('已取消操作')
  } catch {
    patchMessage(msg, { confirmSettled: undefined })
    message.error('取消失败')
  } finally {
    if (confirmBusyId.value === id) {
      confirmBusyId.value = ''
    }
  }
}

/** 统一 Agent / 聊天错误文案，避免技术细节直出 */
function friendlyAgentError(raw: string): string {
  const msg = (raw || '').trim()
  if (!msg) return '请求失败，请稍后重试。'
  if (/timeout|超时|未响应/i.test(msg)) {
    return '模型响应超时，请稍后重试或切换模型。'
  }
  if (/confirm|确认已失效|过期/i.test(msg)) {
    return '确认已失效，请重新发起操作后再确认。'
  }
  if (/401|未登录|Unauthorized/i.test(msg)) {
    return '登录已过期，请重新登录后再试。'
  }
  if (/403|无权限|AccessDenied/i.test(msg)) {
    return '没有权限执行该操作。'
  }
  if (/UNKNOWN_TOOL|不支持的工具|暂不支持/i.test(msg)) {
    return msg.includes('暂不支持') ? msg : '暂不支持该操作，请换一种说法试试。'
  }
  return msg
}

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

/** 从消息正文剥离 Agent 隐藏标记，并解析工具卡 */
function stripAgentMarkers(content: string): {
  text: string
  toolResult?: AgentToolResultEvent
} {
  if (!content) return { text: '' }
  let toolResult: AgentToolResultEvent | undefined
  let text = content

  const confirmRe = /\[\[AGENT_CONFIRM\]\]([\s\S]*?)\[\[\/AGENT_CONFIRM\]\]/
  const resultRe = /\[\[AGENT_RESULT\]\]([\s\S]*?)\[\[\/AGENT_RESULT\]\]/

  const cm = text.match(confirmRe)
  if (cm) {
    try {
      toolResult = JSON.parse(cm[1].trim())
    } catch { /* ignore */ }
    text = text.replace(confirmRe, '').trimEnd()
  } else {
    const rm = text.match(resultRe)
    if (rm) {
      try {
        toolResult = JSON.parse(rm[1].trim())
      } catch { /* ignore */ }
      text = text.replace(resultRe, '').trimEnd()
    }
  }
  return { text, toolResult }
}

function hydrateMessagesFromServer(list: ChatMessage[]): UiChatMessage[] {
  return (list || []).map((m) => {
    if (m.role !== 'assistant' || !m.content) {
      return { ...m }
    }
    const { text, toolResult } = stripAgentMarkers(m.content)
    // 从落库标记还原终态，避免刷新后重新变成「待确认」
    let confirmSettled: UiChatMessage['confirmSettled']
    const uiType = toolResult?.ui?.type
    if (uiType === 'confirm_rejected') {
      confirmSettled = 'rejected'
    } else if (uiType === 'task_created') {
      confirmSettled = 'ok'
    } else if (uiType === 'confirm_expired') {
      confirmSettled = 'rejected'
    } else if (toolResult?.data?.settled === 'rejected' || toolResult?.data?.settled === 'expired') {
      confirmSettled = 'rejected'
    } else if (toolResult?.data?.settled === 'ok') {
      confirmSettled = 'ok'
    }
    return {
      ...m,
      content: text,
      toolResult,
      confirmSettled
    }
  })
}

function renderContent(content: string, role: string = 'assistant'): string {
  if (!content) return ''
  const { text } = stripAgentMarkers(content)
  if (role === 'user') {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
  return md.render(text)
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
    const raw = ((res as any).data || []) as ChatMessage[]
    messages.value = hydrateMessagesFromServer(raw)
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
  agentPhaseLabel.value = agentMode.value ? '正在分析意图…' : ''
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
    onAgentStatus(data: { phase?: string; label?: string }) {
      if (data?.phase === 'done' || data?.phase === 'fallback_chat') {
        agentPhaseLabel.value = data.label || ''
        return
      }
      if (data?.label) {
        agentPhaseLabel.value = data.label
      } else if (data?.phase === 'deciding') {
        agentPhaseLabel.value = '正在分析意图…'
      } else if (data?.phase === 'tool_running') {
        agentPhaseLabel.value = '正在调用工具…'
      } else if (data?.phase === 'summarizing') {
        agentPhaseLabel.value = '正在整理结果…'
      }
      scrollToBottom()
    },
    onToolResult(data: AgentToolResultEvent) {
      agentPhaseLabel.value = '正在整理结果…'
      if (messages.value[assistantMsgIndex]) {
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          toolResult: data
        }
      }
      scrollToBottom()
    },
    onToolConfirm(data: AgentToolResultEvent) {
      agentPhaseLabel.value = ''
      if (messages.value[assistantMsgIndex]) {
        messages.value[assistantMsgIndex] = {
          ...messages.value[assistantMsgIndex],
          toolResult: data,
          confirmSettled: undefined
        }
      }
      scrollToBottom()
    },
    onDelta(data: { content: string }) {
      // 已有正文后不再展示阶段条
      if (data?.content) {
        agentPhaseLabel.value = ''
      }
      const prev = messages.value[assistantMsgIndex]
      if (!prev) return
      const merged = (prev.content || '') + (data.content || '')
      const { text, toolResult } = stripAgentMarkers(merged)
      messages.value[assistantMsgIndex] = {
        ...prev,
        content: text,
        toolResult: toolResult || prev.toolResult
      }
      scrollToBottom()
    },
    onDone(data: { messageId: string; cancelled?: boolean }) {
      isLoading.value = false
      currentAbortController = null
      currentStreamId.value = ''
      agentPhaseLabel.value = ''
      if (data?.cancelled) {
        message.info('已停止生成')
      }
      const keepTool = messages.value[assistantMsgIndex]?.toolResult
      const keepSettled = messages.value[assistantMsgIndex]?.confirmSettled
      const finish = async () => {
        if (activeConversationId.value && !String(activeConversationId.value).startsWith('local_')) {
          await loadMessages(activeConversationId.value)
          if (keepTool) {
            for (let i = messages.value.length - 1; i >= 0; i--) {
              if (messages.value[i].role === 'assistant') {
                if (!messages.value[i].toolResult) {
                  messages.value[i] = {
                    ...messages.value[i],
                    toolResult: keepTool,
                    confirmSettled: keepSettled
                  }
                }
                break
              }
            }
          }
        }
        scrollToBottom()
      }
      void finish()
    },
    onError(data: { message: string }) {
      const errText = friendlyAgentError(data.message || '抱歉，请求失败，请稍后重试。')
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
      agentPhaseLabel.value = ''
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
      agentPhaseLabel.value = ''
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
      maxTokens: sessionMaxTokens.value,
      agentMode: agentMode.value
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
  if (agentPrompts.some(p => p.text === text)) {
    agentMode.value = true
  }
  if (!activeConversationId.value) {
    createConversation()
  }
  inputMessage.value = text
  void nextTick(() => handleSend())
}

onMounted(() => {
  loadModels()
  loadAgentTaskModels()
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
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  overflow: hidden;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 8px 24px rgba(15, 23, 42, 0.04);
}

.chat-sidebar {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fafafa;

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
    padding: 10px 12px;
    border-radius: 10px;
    cursor: pointer;
    margin-bottom: 4px;
    border: 1px solid transparent;
    transition: background 0.15s ease, border-color 0.15s ease;

    &:hover {
      background: #f3f4f6;
      border-color: #e5e7eb;
    }

    &.active {
      background: #f3f4f6;
      border-color: #d1d5db;
      box-shadow: none;
      .conv-title { color: #111827; font-weight: 600; }
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

  .agent-switch {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 0 10px;
    height: 28px;
    border: 1px solid var(--border-color);
    border-radius: 6px;
    background: #fff;
    flex-shrink: 0;
  }

  .agent-switch-label {
    font-size: 12px;
    color: var(--text-secondary);
    font-weight: 600;
  }

  .chat-hint {
    font-size: 12px;
    color: var(--text-muted);
    white-space: nowrap;
  }
}

/* —— Agent 卡片 —— */
.agent-card {
  position: relative;
  display: flex;
  margin-bottom: 10px;
  max-width: min(460px, 100%);
  border-radius: 12px;
  border: 1px solid #e5e7eb;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}

.agent-card-accent {
  width: 4px;
  flex-shrink: 0;
  background: #94a3b8;
}

.agent-card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.agent-card.is-confirm {
  border-color: #fcd34d;
  background: #fffbeb;

  .agent-card-accent {
    background: linear-gradient(180deg, #f59e0b 0%, #d97706 100%);
  }
}

.agent-card.is-confirm.is-pending {
  border-color: #fbbf24;
  opacity: 0.95;
}

.agent-card.is-success {
  border-color: #86efac;
  background: #f0fdf4;

  .agent-card-accent {
    background: linear-gradient(180deg, #22c55e 0%, #16a34a 100%);
  }
}

.agent-card.is-fail {
  border-color: #fca5a5;
  background: #fef2f2;

  .agent-card-accent {
    background: linear-gradient(180deg, #ef4444 0%, #dc2626 100%);
  }
}

.agent-card.is-rejected {
  border-color: #e5e7eb;
  background: #f9fafb;

  .agent-card-accent {
    background: #9ca3af;
  }
}

.agent-card.is-info {
  border-color: #bfdbfe;
  background: #f8fafc;

  .agent-card-accent {
    background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  }
}

.agent-card-top {
  display: flex;
  gap: 12px;
  padding: 14px 14px 8px;
}

.agent-card-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 700;
  flex-shrink: 0;
  background: #f1f5f9;
  color: #475569;
  line-height: 1;
}

.is-confirm .agent-card-icon {
  background: #fef3c7;
  color: #b45309;
}

.is-success .agent-card-icon {
  background: #dcfce7;
  color: #15803d;
}

.is-fail .agent-card-icon {
  background: #fee2e2;
  color: #b91c1c;
}

.is-rejected .agent-card-icon {
  background: #f3f4f6;
  color: #6b7280;
}

.agent-card-titles {
  min-width: 0;
  flex: 1;
}

.agent-card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.agent-card-badge {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  padding: 1px 8px;
  border-radius: 999px;
  line-height: 1.6;
  background: #e0e7ff;
  color: #4338ca;
}

.is-confirm .agent-card-badge {
  background: #fef3c7;
  color: #b45309;
}

.is-success .agent-card-badge {
  background: #dcfce7;
  color: #15803d;
}

.is-fail .agent-card-badge {
  background: #fee2e2;
  color: #b91c1c;
}

.is-rejected .agent-card-badge {
  background: #f3f4f6;
  color: #6b7280;
}

.agent-card-tool {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  letter-spacing: -0.01em;
}

.agent-card-sub {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.55;
  color: #4b5563;
  white-space: pre-wrap;
  word-break: break-word;
}

.agent-param-list {
  margin: 4px 14px 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(251, 191, 36, 0.35);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.agent-param-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.agent-param-field.agent-param-inline {
  flex-direction: row;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;

  .agent-param-k {
    min-width: 40px;
    padding-top: 0;
  }
}

.agent-param-row {
  display: grid;
  grid-template-columns: 78px 1fr;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.5;
  padding: 2px 0;

  & + & {
    border-top: 1px solid rgba(251, 191, 36, 0.22);
    padding-top: 8px;
  }
}

.agent-param-k {
  color: #92400e;
  font-weight: 600;
  flex-shrink: 0;
  font-size: 12px;
}

.agent-param-v {
  color: #1f2937;
  word-break: break-word;
  white-space: pre-wrap;
  font-size: 12.5px;
}

.agent-param-input {
  width: 100%;

  :deep(textarea.ant-input),
  :deep(.ant-input) {
    border-radius: 8px;
    font-size: 13px;
    background: #fff;
  }
}

.agent-param-radios {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 0;

  :deep(.ant-radio-button-wrapper) {
    border-radius: 0;
    font-size: 12px;
  }

  :deep(.ant-radio-button-wrapper:first-child) {
    border-radius: 6px 0 0 6px;
  }

  :deep(.ant-radio-button-wrapper:last-child) {
    border-radius: 0 6px 6px 0;
  }

  :deep(.ant-radio-button-wrapper-checked:not(.ant-radio-button-wrapper-disabled)) {
    background: #d97706;
    border-color: #d97706;
  }

  :deep(.ant-radio-button-wrapper-checked:not(.ant-radio-button-wrapper-disabled)::before) {
    background: #d97706;
  }
}

.agent-param-select {
  min-width: 120px;
  flex: 0 0 auto;
}

.agent-model-select {
  width: 100%;

  :deep(.ant-select-selector) {
    border-radius: 8px !important;
    font-size: 13px;
  }
}

.agent-param-hint {
  font-size: 11px;
  color: #b45309;
  line-height: 1.4;
  margin-top: -4px;
}

.agent-card-footer {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 10px 14px 14px;
  border-top: 1px solid rgba(251, 191, 36, 0.28);
  background: rgba(255, 255, 255, 0.55);
}

.agent-card-tip {
  margin: 0;
  font-size: 12px;
  color: #a16207;
  line-height: 1.45;
}

.agent-card-btns {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.agent-btn-ghost {
  border-radius: 8px !important;
  min-width: 76px;
  height: 34px !important;
}

.agent-btn-primary {
  border-radius: 8px !important;
  min-width: 108px;
  height: 34px !important;
  font-weight: 600 !important;
  background: #d97706 !important;
  border-color: #d97706 !important;

  &:hover:not(:disabled) {
    background: #b45309 !important;
    border-color: #b45309 !important;
  }

  &:disabled {
    opacity: 0.65;
  }
}

.agent-card-done {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #6b7280;
  padding: 2px 0;
}

.agent-card-done-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9ca3af;
  flex-shrink: 0;
}

.agent-card-done.done-ok {
  color: #15803d;

  .agent-card-done-dot {
    background: #22c55e;
  }
}

.agent-card-done.done-rejected {
  color: #6b7280;

  .agent-card-done-dot {
    background: #9ca3af;
  }
}

.agent-created {
  padding: 0 14px 14px;
}

.agent-created-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.agent-created-id {
  font-size: 12px;
  color: #6b7280;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.agent-created-model {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 4px;
  word-break: break-all;
}

.agent-created-title {
  font-size: 13px;
  color: #111827;
  line-height: 1.5;
  word-break: break-word;
}

.agent-created-link {
  padding-left: 0 !important;
  margin-top: 4px;
  font-weight: 600 !important;
}

.agent-task-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 14px 14px;
}

.agent-task-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fafbfc;
  border: 1px solid #eef0f3;
}

.agent-task-left,
.agent-task-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.agent-task-name {
  font-size: 13px;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 220px;
}

.agent-task-link {
  font-size: 12px;
  color: var(--primary-color);
  white-space: nowrap;
}

.agent-model-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0 14px 14px;
}

.agent-model-provider {
  font-size: 12px;
  font-weight: 600;
  color: #4b5563;
  margin-bottom: 6px;
}

.agent-model-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
      background: #f3f4f6;
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
      background: #f3f4f6;
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

.agent-phase-label {
  margin-top: 6px;
  padding-left: 2px;
  font-size: 12px;
  color: var(--text-muted, #6b7280);
  letter-spacing: 0.01em;
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
