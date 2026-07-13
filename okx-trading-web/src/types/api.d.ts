export interface Strategy {
  id: number
  strategyName: string
  symbol: string
  timeframe: string
  fastPeriod: number
  slowPeriod: number
  tradeAmountPct: number
  stopLossPct: number
  takeProfitPct: number
  enabled: number
  runMode: string
  lastRunCandleTime: number | null
  createdAt: string
  updatedAt: string
}

export interface TradeOrder {
  id: number
  strategyId: number
  symbol: string
  side: string
  orderType: string
  price: string | null
  quantity: string | null
  notional: string | null
  clientOrderId: string
  okxOrderId: string | null
  status: string
  rawRequest: string | null
  rawResponse: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface Position {
  id: number
  strategyId: number
  symbol: string
  quantity: string
  avgPrice: string
  currentPrice: string
  realizedPnl: string
  unrealizedPnl: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface StrategyRunLog {
  id: number
  strategyId: number
  symbol: string
  timeframe: string
  candleTime: number
  closePrice: string | null
  fastMa: string | null
  slowMa: string | null
  signal: string
  action: string
  message: string
  createdAt: string
}

export interface OkxConfig {
  apiKeyMasked: string
  simulated: number
  status: string
  lastCheckAt: string | null
  lastError: string | null
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: string
}

export interface ChatConversation {
  id: string
  title: string
  provider: string
  model: string
  createdAt: string
  updatedAt: string
}

export interface ChatRequest {
  message: string
  conversationId?: string
  provider?: string
  model?: string
}

export interface ChatResponse {
  conversationId: string
  reply: ChatMessage
}

export interface AiModel {
  id: string
  name: string
}

export interface AiProvider {
  key: string
  name: string
  models: AiModel[]
}

// ---------- 视频核心内容提取 ----------

export interface VideoProcessOptions {
  extractMindMap?: boolean
  generateRepurposeScript?: boolean
  language?: string
  /** LLM 供应商 key，如 nvidia */
  llmProvider?: string
  /** LLM 模型 ID */
  llmModel?: string
}

export interface VideoProcessRequest {
  url: string
  options?: VideoProcessOptions
}

export interface KeyPointItem {
  timestamp: string
  point: string
}

export interface ChapterItem {
  timestamp: string
  title: string
  summary: string
}

export interface VideoSummaryPart {
  keyPoints: KeyPointItem[]
  chapters: ChapterItem[]
  mindMapMarkdown?: string | null
  repurposeScript?: string | null
}

export interface TranscriptionSegment {
  id: number
  start: number
  end: number
  text: string
}

export interface TranscriptionResult {
  text: string
  language?: string | null
  durationSeconds?: number | null
  segments: TranscriptionSegment[]
}

export interface VideoSummaryResult {
  videoId: string
  title: string
  duration?: number | null
  sourceUrl: string
  summary: VideoSummaryPart
  transcription: TranscriptionResult
}

export type VideoTaskStatus =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'TRANSCRIBING'
  | 'SUMMARIZING'
  | 'SUCCESS'
  | 'FAILED'
  | 'PAUSED'

export interface VideoTaskItem {
  taskId: string
  status: VideoTaskStatus | string
  url: string
  title?: string | null
  platform?: string | null
  llmProvider?: string | null
  llmModel?: string | null
  currentStep?: string | null
  errorMessage?: string | null
  durationSeconds?: number | null
  videoAvailable?: boolean | null
  videoPath?: string | null
  audioPath?: string | null
  createdAt?: string | null
  finishedAt?: string | null
  startedAt?: string | null
  /** 下载步骤耗时 ms */
  downloadDurationMs?: number | null
  /** 转录步骤耗时 ms */
  transcribeDurationMs?: number | null
  /** 总结步骤耗时 ms */
  summarizeDurationMs?: number | null
  /** 全流程总耗时 ms */
  totalDurationMs?: number | null
  result?: VideoSummaryResult | null
}

export interface VideoTaskPage {
  items: VideoTaskItem[]
  total: number
  page: number
  size: number
}

export interface LlmModelTestRequest {
  provider: string
  model: string
}

export interface LlmModelTestResult {
  available: boolean
  provider: string
  model: string
  reply?: string | null
  latencyMs?: number | null
  errorMessage?: string | null
}

/** 数据库中的 LLM 模型配置（管理用） */
export interface AiModelConfig {
  id: string
  provider: string
  providerName?: string
  modelId: string
  modelName: string
  enabled: boolean
  sortOrder: number
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AiModelConfigRequest {
  provider: string
  modelId: string
  modelName: string
  enabled?: boolean
  sortOrder?: number
  remark?: string
}

export interface AiProviderOption {
  key: string
  name: string
}

// ---------- AI 视频生成 (aigen) ----------

export type AigenTaskStatus =
  | 'PENDING'
  | 'PLANNING'
  | 'ASSET_GENERATING'
  | 'RENDERING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'PAUSED'

export interface AigenCreateOptions {
  language?: string
  aspectRatio?: string
  targetDurationSec?: number
  voiceId?: string
  bgmId?: string
  llmProvider?: string
  llmModel?: string
  negativePrompt?: string
  styleJson?: string
}

export interface AigenCreateRequest {
  prompt: string
  templateId?: string
  options?: AigenCreateOptions
}

export interface AigenTaskItem {
  id: string
  title?: string | null
  prompt: string
  templateId: string
  status: AigenTaskStatus | string
  currentStep?: string | null
  progress?: number | null
  language?: string | null
  aspectRatio?: string | null
  targetDurationSec?: number | null
  voiceId?: string | null
  bgmId?: string | null
  llmProvider?: string | null
  llmModel?: string | null
  errorMessage?: string | null
  durationSeconds?: number | null
  outputAvailable?: boolean | null
  planDurationMs?: number | null
  assetDurationMs?: number | null
  renderDurationMs?: number | null
  totalDurationMs?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AigenTaskPage {
  items: AigenTaskItem[]
  total: number
  page: number
  size: number
}

export interface AigenTemplate {
  id: string
  name: string
  description?: string
  compositionId?: string
  aspectRatios?: string[]
  defaultDurationSec?: number
  minDurationSec?: number
  maxDurationSec?: number
}
