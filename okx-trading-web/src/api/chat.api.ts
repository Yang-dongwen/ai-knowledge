import request from './request'
import type { ChatConversation, ChatRequest, AiProvider } from '@/types/api'

/**
 * SSE 流式响应回调接口
 */
export interface StreamCallbacks {
  /** 收到会话元信息（conversationId） */
  onMeta?: (data: { conversationId: string }) => void
  /** 收到 AI 回复增量内容 */
  onDelta?: (data: { content: string }) => void
  /** 流式结束 */
  onDone?: (data: { messageId: string }) => void
  /** 错误 */
  onError?: (data: { message: string }) => void
}

export const chatApi = {
  getModels(): Promise<{ data: AiProvider[] }> {
    return request.get('/chat/models')
  },

  getConversations(): Promise<{ data: ChatConversation[] }> {
    return request.get('/chat/conversations')
  },

  getMessages(conversationId: string): Promise<{ data: any }> {
    return request.get(`/chat/conversations/${conversationId}/messages`)
  },

  /**
   * 发送消息（SSE 流式响应）。
   * 使用 fetch + ReadableStream 消费 Server-Sent Events。
   * 返回 AbortController 用于取消请求。
   */
  sendMessageStream(data: ChatRequest, callbacks: StreamCallbacks): AbortController {
    const controller = new AbortController()

    const baseURL = request.defaults.baseURL || '/api'
    const url = `${baseURL}/chat/send`

    const token = localStorage.getItem('okx_auth_token')
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
      signal: controller.signal
    })
      .then(async (response) => {
        if (!response.ok) {
          callbacks.onError?.({ message: `请求失败: ${response.status}` })
          return
        }

        const reader = response.body?.getReader()
        if (!reader) {
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

          // 按行解析 SSE 事件
          const lines = buffer.split('\n')
          // 保留最后一个不完整的行
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
                    callbacks.onDelta?.(parsed)
                    break
                  case 'done':
                    doneReceived = true
                    callbacks.onDone?.(parsed)
                    break
                  case 'error':
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

        // 流结束后如果没收到 done 事件，兜底触发
        if (!doneReceived) {
          callbacks.onDone?.({ messageId: '' })
        }
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          callbacks.onError?.({ message: '网络连接异常，请检查网络' })
        }
      })

    return controller
  },

  deleteConversation(conversationId: string): Promise<any> {
    return request.delete(`/chat/conversations/${conversationId}`)
  }
}
