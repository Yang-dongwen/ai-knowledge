import request from './request'
import type { AigenCreateRequest, AigenTaskItem, AigenTaskPage, AigenTemplate } from '@/types/api'

/**
 * AI 视频生成 API（/api/v1/aigen/*）
 */
export const aigenApi = {
  createTask(data: AigenCreateRequest): Promise<{ data: AigenTaskItem }> {
    return request.post('/v1/aigen/tasks', data)
  },

  listTasks(page = 0, size = 20, status?: string): Promise<{ data: AigenTaskPage }> {
    return request.get('/v1/aigen/tasks', {
      params: { page, size, ...(status ? { status } : {}) }
    })
  },

  getTask(taskId: string): Promise<{ data: AigenTaskItem }> {
    return request.get(`/v1/aigen/tasks/${taskId}`)
  },

  getStoryboard(taskId: string): Promise<{ data: Record<string, unknown> }> {
    return request.get(`/v1/aigen/tasks/${taskId}/storyboard`)
  },

  cancelTask(taskId: string): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/cancel`)
  },

  pauseTask(taskId: string): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/pause`)
  },

  /**
   * 失败/取消/暂停/成功任务重试（清空产物后整流水线重跑）。
   * 可传入 llmProvider / llmModel 重新指定模型。
   */
  retryTask(
    taskId: string,
    body?: { llmProvider?: string; llmModel?: string }
  ): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/retry`, body || {})
  },

  deleteTask(taskId: string): Promise<{ data: null }> {
    return request.delete(`/v1/aigen/tasks/${taskId}`)
  },

  listTemplates(): Promise<{ data: AigenTemplate[] }> {
    return request.get('/v1/aigen/templates')
  },

  listVoices(): Promise<{ data: Array<{ id: string; name: string; lang?: string }> }> {
    return request.get('/v1/aigen/voices')
  },

  /**
   * 拉取成片为 Blob（带 Authorization，供 video 标签播放）。
   */
  async fetchOutputBlob(taskId: string): Promise<Blob> {
    const token = localStorage.getItem('okx_auth_token') || ''
    const res = await fetch(`/api/v1/aigen/tasks/${taskId}/media/output`, {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      }
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(text || `下载失败 HTTP ${res.status}`)
    }
    return res.blob()
  }
}
