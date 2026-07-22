import request from './request'
import type {
  AiProvider,
  ArticleCreateRequest,
  ArticlePlatformDetectResult,
  ArticleTaskItem,
  ArticleTaskPage
} from '@/types/api'

/**
 * 文章/新闻提取 API（/api/v1/article/*）
 */
export const articleApi = {
  createTask(data: ArticleCreateRequest): Promise<{ data: ArticleTaskItem }> {
    return request.post('/v1/article/tasks', data, { timeout: 30000 })
  },

  listTasks(page = 0, size = 20, status?: string): Promise<{ data: ArticleTaskPage }> {
    return request.get('/v1/article/tasks', {
      params: { page, size, ...(status ? { status } : {}) }
    })
  },

  getTask(taskId: string): Promise<{ data: ArticleTaskItem }> {
    return request.get(`/v1/article/tasks/${taskId}`)
  },

  getCore(taskId: string): Promise<{ data: unknown }> {
    return request.get(`/v1/article/tasks/${taskId}/core`)
  },

  getRewrite(taskId: string): Promise<{ data: unknown }> {
    return request.get(`/v1/article/tasks/${taskId}/rewrite`)
  },

  getMainText(taskId: string): Promise<{ data: { id: string; mainText?: string; mainTextChars?: number } }> {
    return request.get(`/v1/article/tasks/${taskId}/main-text`)
  },

  paste(taskId: string, pasteText: string): Promise<{ data: ArticleTaskItem }> {
    return request.post(`/v1/article/tasks/${taskId}/paste`, { pasteText }, { timeout: 30000 })
  },

  cancelTask(taskId: string): Promise<{ data: ArticleTaskItem }> {
    return request.post(`/v1/article/tasks/${taskId}/cancel`)
  },

  pauseTask(taskId: string): Promise<{ data: ArticleTaskItem }> {
    return request.post(`/v1/article/tasks/${taskId}/pause`)
  },

  retryTask(
    taskId: string,
    body?: { llmProvider?: string; llmModel?: string; clearPaste?: boolean }
  ): Promise<{ data: ArticleTaskItem }> {
    return request.post(`/v1/article/tasks/${taskId}/retry`, body || {})
  },

  deleteTask(taskId: string): Promise<{ data: null }> {
    return request.delete(`/v1/article/tasks/${taskId}`)
  },

  detectPlatform(url: string): Promise<{ data: ArticlePlatformDetectResult }> {
    return request.post('/v1/article/platforms/detect', { url })
  },

  /** chat 模型列表（capability=chat） */
  listModels(): Promise<{ data: AiProvider[] }> {
    return request.get('/v1/article/models')
  },

  disclaimer(): Promise<{ data: { submit?: string } }> {
    return request.get('/v1/article/disclaimer')
  }
}
