import request from './request'
import type {
  VideoProcessRequest,
  VideoTaskPage,
  VideoTaskItem,
  TranscriptionResult,
  VideoSummaryPart,
  AiProvider,
  LlmModelTestRequest,
  LlmModelTestResult
} from '@/types/api'

/**
 * 视频核心内容提取 API（对接 /api/v1/video/*）。
 */
export const videoApi = {
  /** 提交处理任务 */
  process(data: VideoProcessRequest): Promise<{ data: VideoTaskItem }> {
    return request.post('/v1/video/process', data)
  },

  /** 可用 LLM 模型列表 */
  listModels(): Promise<{ data: AiProvider[] }> {
    return request.get('/v1/video/models')
  },

  /** 测试模型是否可用（冷启动可能较慢） */
  testModel(data: LlmModelTestRequest): Promise<{ data: LlmModelTestResult }> {
    return request.post('/v1/video/models/test', data, { timeout: 90000 })
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

  /** 视频流 URL（给 <video> 使用，走 Vite 代理） */
  videoStreamUrl(taskId: string): string {
    return `/api/v1/video/tasks/${taskId}/video`
  }
}
