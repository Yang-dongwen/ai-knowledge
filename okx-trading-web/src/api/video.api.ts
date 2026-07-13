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

  /** 可用 LLM 模型列表 */
  listModels(): Promise<{ data: AiProvider[] }> {
    return request.get('/v1/video/models')
  },

  /**
   * 测试模型是否可用。
   * 超过 10s 无响应前端直接判不可用（与后端 test-timeout-seconds 对齐）。
   */
  testModel(data: LlmModelTestRequest): Promise<{ data: LlmModelTestResult }> {
    return request.post('/v1/video/models/test', data, { timeout: 10000 })
  },

  // ---------- 模型配置管理（数据库） ----------

  listModelConfigs(): Promise<{ data: AiModelConfig[] }> {
    return request.get('/v1/video/model-configs')
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
    body?: { llmProvider?: string; llmModel?: string }
  ): Promise<{ data: VideoTaskItem }> {
    return request.post(`/v1/video/tasks/${taskId}/retry`, body || {})
  },

  /** 视频流 URL（给 <video> 使用，走 Vite 代理） */
  videoStreamUrl(taskId: string): string {
    return `/api/v1/video/tasks/${taskId}/video`
  }
}
