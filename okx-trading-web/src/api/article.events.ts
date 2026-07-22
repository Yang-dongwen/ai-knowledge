/**
 * 文章提取任务 SSE。
 * 禁止原生 EventSource（无法带 Authorization），与 video/imggen 一致用 fetch + JWT。
 */

export type ArticleTaskEventType =
  | 'connected'
  | 'ping'
  | 'task.created'
  | 'task.status'
  | 'task.deleted'
  | string

export interface ArticleTaskSseEvent {
  type: ArticleTaskEventType
  ts?: number
  taskId?: string
  id?: string
  data?: Record<string, unknown>
}

export interface ArticleEventsHandlers {
  onEvent?: (ev: ArticleTaskSseEvent) => void
  onOpen?: () => void
  onError?: (err: unknown) => void
  onClose?: () => void
}

const TOKEN_KEY = 'okx_auth_token'

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

function parseSseChunk(buffer: string): { events: ArticleTaskSseEvent[]; rest: string } {
  const events: ArticleTaskSseEvent[] = []
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
      let parsed: unknown = JSON.parse(raw)
      if (typeof parsed === 'string') {
        parsed = JSON.parse(parsed)
      }
      const ev = parsed as ArticleTaskSseEvent
      if (!ev.type) ev.type = eventName
      events.push(ev)
    } catch {
      events.push({ type: eventName, data: { raw } })
    }
  }
  return { events, rest }
}

export function connectArticleTaskEvents(handlers: ArticleEventsHandlers = {}): { close: () => void } {
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
        const res = await fetch('/api/v1/article/events', {
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
