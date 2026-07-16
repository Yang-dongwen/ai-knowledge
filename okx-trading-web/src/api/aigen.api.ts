import request from './request'
import type { AigenCreateRequest, AigenTaskItem, AigenTaskPage, AigenTemplate } from '@/types/api'

export interface AigenShotSummary {
  id: string
  order?: number
  durationSec?: number
  title?: string
  visualType?: string
  assetPath?: string
  imageAvailable?: boolean
  audioSrc?: string
  audioAvailable?: boolean
  motionType?: string
  layout?: string
  promptPreview?: string
}

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

  listShots(taskId: string): Promise<{ data: AigenShotSummary[] }> {
    return request.get(`/v1/aigen/tasks/${taskId}/shots`)
  },

  regenerateShot(
    taskId: string,
    shotId: string,
    params?: { enhance?: boolean; reRender?: boolean }
  ): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/shots/${encodeURIComponent(shotId)}/regenerate`, null, {
      params: {
        enhance: params?.enhance,
        reRender: params?.reRender ?? true
      }
    })
  },

  async uploadShotImage(
    taskId: string,
    shotId: string,
    file: File,
    reRender = true
  ): Promise<{ data: AigenShotSummary }> {
    const form = new FormData()
    form.append('file', file)
    return request.post(
      `/v1/aigen/tasks/${taskId}/shots/${encodeURIComponent(shotId)}/image`,
      form,
      {
        params: { reRender },
        headers: { 'Content-Type': 'multipart/form-data' }
      }
    )
  },

  cancelTask(taskId: string): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/cancel`)
  },

  pauseTask(taskId: string): Promise<{ data: AigenTaskItem }> {
    return request.post(`/v1/aigen/tasks/${taskId}/pause`)
  },

  retryTask(
    taskId: string,
    body?: {
      llmProvider?: string
      llmModel?: string
      imageProvider?: string
      imageModel?: string
    }
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
    const buf = await res.arrayBuffer()
    return new Blob([buf], { type: 'video/mp4' })
  },

  /** 镜头缩略图 URL（需前端带 Authorization 拉 blob 时另处理） */
  shotImageUrl(taskId: string, shotId: string): string {
    return `/api/v1/aigen/tasks/${taskId}/shots/${encodeURIComponent(shotId)}/image`
  },

  async fetchShotImageBlob(taskId: string, shotId: string): Promise<Blob> {
    const token = localStorage.getItem('okx_auth_token') || ''
    const res = await fetch(aigenApi.shotImageUrl(taskId, shotId), {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      }
    })
    if (!res.ok) {
      throw new Error(`镜头图加载失败 HTTP ${res.status}`)
    }
    return res.blob()
  }
}
