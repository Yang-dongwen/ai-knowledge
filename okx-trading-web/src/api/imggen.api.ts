import request from './request'
import type {
  ImgGenCreateRequest,
  ImgGenImageModel,
  ImgGenTaskItem,
  ImgGenTaskPage
} from '@/types/api'

/**
 * AI 文生图 API（/api/v1/imggen/*）
 */
export const imggenApi = {
  /** 生图模型目录（FLUX 等，yml 配置） */
  listImageModels(): Promise<{ data: ImgGenImageModel[] }> {
    return request.get('/v1/imggen/models')
  },

  createTask(data: ImgGenCreateRequest): Promise<{ data: ImgGenTaskItem }> {
    return request.post('/v1/imggen/tasks', data)
  },

  listTasks(page = 0, size = 20, status?: string): Promise<{ data: ImgGenTaskPage }> {
    return request.get('/v1/imggen/tasks', {
      params: { page, size, ...(status ? { status } : {}) }
    })
  },

  getTask(taskId: string): Promise<{ data: ImgGenTaskItem }> {
    return request.get(`/v1/imggen/tasks/${taskId}`)
  },

  cancelTask(taskId: string): Promise<{ data: ImgGenTaskItem }> {
    return request.post(`/v1/imggen/tasks/${taskId}/cancel`)
  },

  pauseTask(taskId: string): Promise<{ data: ImgGenTaskItem }> {
    return request.post(`/v1/imggen/tasks/${taskId}/pause`)
  },

  retryTask(
    taskId: string,
    body?: {
      imageModel?: string
      imageProvider?: string
      llmProvider?: string
      llmModel?: string
      enhancePrompt?: boolean
      seed?: number | null
    }
  ): Promise<{ data: ImgGenTaskItem }> {
    return request.post(`/v1/imggen/tasks/${taskId}/retry`, body || {})
  },

  deleteTask(taskId: string): Promise<{ data: null }> {
    return request.delete(`/v1/imggen/tasks/${taskId}`)
  },

  /**
   * 拉取图片 Blob（带 Authorization）。
   */
  async fetchImageBlob(taskId: string, fileName: string): Promise<Blob> {
    const token = localStorage.getItem('okx_auth_token') || ''
    const res = await fetch(`/api/v1/imggen/tasks/${taskId}/media/${encodeURIComponent(fileName)}`, {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      }
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(text || `下载失败 HTTP ${res.status}`)
    }
    return await res.blob()
  }
}
