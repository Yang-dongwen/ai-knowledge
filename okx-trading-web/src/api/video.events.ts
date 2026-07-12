/**
 * 视频任务 SSE（fetch 读流，支持 Authorization）。
 * 原生 EventSource 无法自定义 Header，故不用。
 */

export type VideoTaskEventType =
  | 'connected'
  | 'ping'
  | 'task.created'
  | 'task.status'
  | 'task.deleted'
  | string

export interface VideoTaskSseEvent {
  type: VideoTaskEventType
  ts?: number
  taskId?: string
  data?: Record<string, unknown>
}

export interface VideoEventsHandlers {
  onEvent?: (ev: VideoTaskSseEvent) => void
  onOpen?: () => void
  onError?: (err: unknown) => void
  onClose?: () => void
}

const TOKEN_KEY = 'okx_auth_token'

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

/**
 * 解析 SSE 文本缓冲，返回完整事件并留下残余缓冲。
 */
function parseSseChunk(buffer: string): { events: VideoTaskSseEvent[]; rest: string } {
  const events: VideoTaskSseEvent[] = []
  // 事件以空行分隔
  const parts = buffer.split(/\n\n/)
  const rest = parts.pop() ?? ''
  for (const block of parts) {
    if (!block.trim() || block.startsWith(':')) continue
    let eventName = 'message'
    const dataLines: string[] = []
    for (const line of block.split(/\n/)) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
    if (!dataLines.length) continue
    const raw = dataLines.join('\n')
    try {
      const parsed = JSON.parse(raw) as VideoTaskSseEvent
      if (!parsed.type) parsed.type = eventName
      events.push(parsed)
    } catch {
      events.push({ type: eventName, data: { raw } })
    }
  }
  return { events, rest }
}

/**
 * 连接用户级任务事件流；自动断线重连（指数退避）。
 * @returns close() 关闭并停止重连
 */
export function connectVideoTaskEvents(handlers: VideoEventsHandlers = {}): { close: () => void } {
  let closed = false
  let attempt = 0
  let abort: AbortController | null = null

  const close = () => {
    closed = true
    abort?.abort()
    abort = null
    handlers.onClose?.()
  }

  const run = async () => {
    while (!closed) {
      abort = new AbortController()
      try {
        const token = localStorage.getItem(TOKEN_KEY) || ''
        const res = await fetch('/api/v1/video/events', {
          method: 'GET',
          headers: {
            Accept: 'text/event-stream',
            ...(token ? { Authorization: `Bearer ${token}` } : {})
          },
          signal: abort.signal,
          cache: 'no-store'
        })
        if (!res.ok || !res.body) {
          throw new Error(`SSE HTTP ${res.status}`)
        }
        attempt = 0
        handlers.onOpen?.()

        const reader = res.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buf = ''
        while (!closed) {
          const { done, value } = await reader.read()
          if (done) break
          buf += decoder.decode(value, { stream: true })
          const { events, rest } = parseSseChunk(buf)
          buf = rest
          for (const ev of events) {
            if (ev.type === 'ping') continue
            handlers.onEvent?.(ev)
          }
        }
        if (!closed) {
          handlers.onError?.(new Error('SSE stream ended'))
        }
      } catch (e: any) {
        if (closed || e?.name === 'AbortError') break
        handlers.onError?.(e)
      }
      if (closed) break
      attempt += 1
      const delay = Math.min(30_000, 1000 * Math.pow(2, Math.min(attempt, 5)))
      await sleep(delay)
    }
  }

  void run()
  return { close }
}
