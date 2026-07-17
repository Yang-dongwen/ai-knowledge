import request from './request'
import type {
  ChatConversation,
  ChatRequest,
  AiProvider,
  UpdateConversationRequest,
  EditResendRequest
} from '@/types/api'

/**
 * 流式空闲超时：与后端 ai.response-timeout-seconds 对齐。
 * 仅当「连续无 delta 输出」达到该时长才 abort；有 token 持续到达时会不断续期，不限制总时长。
 */
const CHAT_IDLE_TIMEOUT_MS = 20_000

/**
 * SSE 流式响应回调接口
 */
export interface StreamCallbacks {
  /** 收到会话元信息（conversationId / streamId / provider·model） */
  onMeta?: (data: {
    conversationId: string
    streamId?: string
    provider?: string
    model?: string
    regenerate?: boolean
  }) => void
  /** 收到 AI 回复增量内容 */
  onDelta?: (data: { content: string }) => void
  /** 流式结束 */
  onDone?: (data: { messageId: string; cancelled?: boolean }) => void
  /** 错误 */
  onError?: (data: { message: string }) => void
  /** 用户主动停止（本地 abort） */
  onAbort?: () => void
}

function streamFetch(
  path: string,
  data: ChatRequest,
  callbacks: StreamCallbacks
): AbortController {
  const controller = new AbortController()

  const baseURL = request.defaults.baseURL || '/api'
  const url = `${baseURL}${path}`

  const token = localStorage.getItem('okx_auth_token')
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let idleTimedOut = false
  let userAborted = false
  let finished = false
  let idleTimer: ReturnType<typeof setTimeout> | null = null

  const clearIdleTimer = () => {
    if (idleTimer != null) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  const resetIdleTimer = () => {
    clearIdleTimer()
    idleTimer = setTimeout(() => {
      if (finished) return
      idleTimedOut = true
      finished = true
      clearIdleTimer()
      controller.abort()
      callbacks.onError?.({ message: '模型 20 秒未响应，已强制停止。' })
    }, CHAT_IDLE_TIMEOUT_MS)
  }

  resetIdleTimer()

  fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(data),
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok) {
        finished = true
        clearIdleTimer()
        callbacks.onError?.({ message: `请求失败: ${response.status}` })
        return
      }

      const reader = response.body?.getReader()
      if (!reader) {
        finished = true
        clearIdleTimer()
        callbacks.onError?.({ message: '浏览器不支持流式读取' })
        return
      }

      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let currentEvent = ''
      let doneReceived = false

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim()
            if (!dataStr) continue
            try {
              const parsed = JSON.parse(dataStr)
              switch (currentEvent) {
                case 'meta':
                  callbacks.onMeta?.(parsed)
                  break
                case 'delta':
                  resetIdleTimer()
                  callbacks.onDelta?.(parsed)
                  break
                case 'done':
                  doneReceived = true
                  finished = true
                  clearIdleTimer()
                  callbacks.onDone?.(parsed)
                  break
                case 'error':
                  finished = true
                  clearIdleTimer()
                  callbacks.onError?.(parsed)
                  break
              }
            } catch {
              // 非 JSON 数据，忽略
            }
            currentEvent = ''
          }
        }
      }

      if (!doneReceived && !finished) {
        finished = true
        clearIdleTimer()
        callbacks.onDone?.({ messageId: '' })
      }
    })
    .catch((err) => {
      clearIdleTimer()
      if (err?.name === 'AbortError') {
        if (idleTimedOut) return
        if (!finished) {
          finished = true
          userAborted = true
          callbacks.onAbort?.()
        }
        return
      }
      if (!finished) {
        finished = true
        callbacks.onError?.({ message: '网络连接异常，请检查网络' })
      }
    })

  // 包装 abort：标记用户主动停止
  const originalAbort = controller.abort.bind(controller)
  controller.abort = (reason?: any) => {
    if (!finished && !idleTimedOut) {
      userAborted = true
    }
    originalAbort(reason)
  }

  // silence unused for lint
  void userAborted

  return controller
}

export const chatApi = {
  getModels(): Promise<{ data: AiProvider[] }> {
    return request.get('/chat/models')
  },

  getConversations(keyword?: string): Promise<{ data: ChatConversation[] }> {
    return request.get('/chat/conversations', {
      params: keyword ? { keyword } : undefined
    })
  },

  getMessages(conversationId: string): Promise<{ data: any }> {
    return request.get(`/chat/conversations/${conversationId}/messages`)
  },

  renameConversation(conversationId: string, title: string): Promise<{ data: ChatConversation }> {
    return request.patch(`/chat/conversations/${conversationId}`, { title })
  },

  updateConversation(
    conversationId: string,
    data: UpdateConversationRequest
  ): Promise<{ data: ChatConversation }> {
    return request.put(`/chat/conversations/${conversationId}`, data)
  },

  /**
   * 发送消息（SSE 流式响应）。
   */
  sendMessageStream(data: ChatRequest, callbacks: StreamCallbacks): AbortController {
    return streamFetch('/chat/send', data, callbacks)
  },

  /**
   * 重新生成最后一条 AI 回复（SSE）。
   */
  regenerateStream(
    data: Pick<ChatRequest, 'conversationId' | 'provider' | 'model'>,
    callbacks: StreamCallbacks
  ): AbortController {
    return streamFetch('/chat/regenerate', data as ChatRequest, callbacks)
  },

  /**
   * 真取消后端流式生成（stop 优先，再本地 abort）。
   */
  stopStream(data: { streamId?: string; conversationId?: string }): Promise<{ data: { stopped: boolean } }> {
    return request.post('/chat/stop', data)
  },

  /**
   * 编辑用户消息并从此重发（SSE）。
   */
  editResendStream(data: EditResendRequest, callbacks: StreamCallbacks): AbortController {
    return streamFetch('/chat/edit-resend', data as any, callbacks)
  },

  deleteConversation(conversationId: string): Promise<any> {
    return request.delete(`/chat/conversations/${conversationId}`)
  }
}
