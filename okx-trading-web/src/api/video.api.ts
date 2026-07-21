import request from './request'
import type {
  VideoProcessRequest,
  VideoTaskPage,
  VideoTaskItem,
  TranscriptionResult,
  VideoSummaryPart,
  AiProvider,
  LlmModelTestRequest,
  LlmModelTestResult,
  AiModelConfig,
  AiModelConfigRequest,
  AiProviderOption
} from '@/types/api'

/**
 * 视频核心内容提取 API（对接 /api/v1/video/*）。
 */
export const videoApi = {
  /** 提交处理任务 */
  process(data: VideoProcessRequest): Promise<{ data: VideoTaskItem }> {
    return request.post('/v1/video/process', data)
  },

  /** 可用模型列表（capability 默认 chat；可传 video_omni） */
  listModels(capability?: string): Promise<{ data: AiProvider[] }> {
    return request.get('/v1/video/models', {
      params: capability ? { capability } : {}
    })
  },

  /**
   * 测试模型是否可用。
   * 超过 10s 无响应前端直接判不可用（与后端 test-timeout-seconds 对齐）。
   */
  testModel(data: LlmModelTestRequest): Promise<{ data: LlmModelTestResult }> {
    return request.post('/v1/video/models/test', data, { timeout: 10000 })
  },

  // ---------- 模型配置管理（数据库） ----------

  listModelConfigs(capability?: string): Promise<{ data: AiModelConfig[] }> {
    return request.get('/v1/video/model-configs', {
      params: capability ? { capability } : {}
    })
  },

  listProviders(): Promise<{ data: AiProviderOption[] }> {
    return request.get('/v1/video/model-configs/providers')
  },

  createModelConfig(data: AiModelConfigRequest): Promise<{ data: AiModelConfig }> {
    return request.post('/v1/video/model-configs', data)
  },

  updateModelConfig(id: string, data: AiModelConfigRequest): Promise<{ data: AiModelConfig }> {
    return request.put(`/v1/video/model-configs/${id}`, data)
  },

  deleteModelConfig(id: string): Promise<{ data: null }> {
    return request.delete(`/v1/video/model-configs/${id}`)
  },

  /** 任务详情（含 SUCCESS 时的 result） */
  getTask(taskId: string): Promise<{ data: VideoTaskItem }> {
    return request.get(`/v1/video/tasks/${taskId}`)
  },

  /** 分页任务列表 */
  listTasks(page = 0, size = 20): Promise<{ data: VideoTaskPage }> {
    return request.get('/v1/video/tasks', { params: { page, size } })
  },

  /** 带时间戳转录 */
  getTranscription(taskId: string): Promise<{ data: TranscriptionResult }> {
    return request.get(`/v1/video/tasks/${taskId}/transcription`)
  },

  /** 核心内容摘要 */
  getSummary(taskId: string): Promise<{ data: VideoSummaryPart }> {
    return request.get(`/v1/video/tasks/${taskId}/summary`)
  },

  /** 删除任务（数据库 + 视频/音频/JSON 文件） */
  deleteTask(taskId: string): Promise<{ data: null }> {
    return request.delete(`/v1/video/tasks/${taskId}`)
  },

  /** 暂停进行中/排队中任务 */
  pauseTask(taskId: string): Promise<{ data: VideoTaskItem }> {
    return request.post(`/v1/video/tasks/${taskId}/pause`)
  },

  /**
   * 失败/暂停/成功任务重试（完整重跑流水线，成功会清空原产物）。
   * 可传入 llmProvider / llmModel 重新指定模型。
   */
  retryTask(
    taskId: string,
    body?: {
      llmProvider?: string
      llmModel?: string
      understandingMode?: string
      omniProvider?: string
      omniModel?: string
    }
  ): Promise<{ data: VideoTaskItem }> {
    return request.post(`/v1/video/tasks/${taskId}/retry`, body || {})
  },

  /**
   * 代理流地址（回退用；query access_token）。
   */
  videoStreamUrl(taskId: string): string {
    const token = localStorage.getItem('okx_auth_token') || ''
    const q = token ? `?access_token=${encodeURIComponent(token)}` : ''
    return `/api/v1/video/tasks/${taskId}/video${q}`
  },

  /**
   * PR5：取媒体 URL。presign 时为 R2 直链；proxy 时补 access_token。
   */
  async resolvePlayUrl(taskId: string): Promise<{ url: string; mode: string; expiresAtMs: number }> {
    const res = await request.get(`/v1/video/tasks/${taskId}/media-url`, {
      params: { disposition: 'inline' }
    })
    return attachAccessTokenIfProxy(res.data)
  },

  async resolveDownloadUrl(taskId: string): Promise<{ url: string; mode: string }> {
    const res = await request.get(`/v1/video/tasks/${taskId}/media-url`, {
      params: { disposition: 'attachment' }
    })
    return attachAccessTokenIfProxy(res.data)
  },

  /**
   * 整文件拉取。优先 R2 直链；失败回退代理。
   */
  async fetchVideoBlob(taskId: string): Promise<Blob> {
    try {
      const { url } = await this.resolveDownloadUrl(taskId)
      const token = localStorage.getItem('okx_auth_token') || ''
      const headers: Record<string, string> = { Accept: 'video/mp4,video/*,*/*' }
      // 同源 proxy 才需要 Bearer；R2 直链靠签名
      if (!url.startsWith('http') || url.startsWith(window.location.origin)) {
        if (token) headers.Authorization = `Bearer ${token}`
      }
      const res = await fetch(url, { headers })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const buf = await res.arrayBuffer()
      return new Blob([buf], { type: 'video/mp4' })
    } catch {
      const token = localStorage.getItem('okx_auth_token') || ''
      const res = await fetch(`/api/v1/video/tasks/${taskId}/video`, {
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          Accept: 'video/mp4,video/*,*/*'
        }
      })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        let msg = `视频加载失败 HTTP ${res.status}`
        try {
          const j = JSON.parse(text)
          if (j?.message) msg = j.message
        } catch {
          if (text) msg = text.slice(0, 200)
        }
        throw new Error(msg)
      }
      const buf = await res.arrayBuffer()
      return new Blob([buf], { type: 'video/mp4' })
    }
  },

  /** Cookie 文件状态（不含明文） */
  cookieStatus(platform = 'douyin'): Promise<{ data: VideoCookieStatus }> {
    return request.get('/v1/video/cookies', { params: { platform } })
  },

  /** 上传浏览器 Cookie 请求头字符串 */
  uploadCookie(body: {
    cookieHeader: string
    platform?: string
  }): Promise<{ data: VideoCookieStatus }> {
    return request.post('/v1/video/cookies', body)
  },

  /** 清除 Cookie 文件 */
  clearCookie(platform = 'douyin'): Promise<{ data: null }> {
    return request.delete('/v1/video/cookies', { params: { platform } })
  }
}

/** proxy 模式相对路径补 access_token，便于 &lt;video src&gt; 直链。 */
export function attachAccessTokenIfProxy(data: {
  url?: string
  mode?: string
  proxyPath?: string
  expiresAtMs?: number
}): { url: string; mode: string; expiresAtMs: number } {
  const mode = data?.mode || 'proxy'
  let url = data?.url || data?.proxyPath || ''
  if (mode === 'proxy' || (!url.startsWith('http://') && !url.startsWith('https://'))) {
    const token = localStorage.getItem('okx_auth_token') || ''
    const path = url.startsWith('/') ? url : data?.proxyPath || url
    const sep = path.includes('?') ? '&' : '?'
    url = token ? `${path}${sep}access_token=${encodeURIComponent(token)}` : path
  }
  return { url, mode, expiresAtMs: data?.expiresAtMs || 0 }
}

export interface VideoCookieStatus {
  platform: string
  configured: boolean
  fileExists: boolean
  filePath?: string | null
  cookieCount?: number | null
  fileSizeBytes?: number | null
  lastModifiedAt?: string | null
  hint?: string | null
}
