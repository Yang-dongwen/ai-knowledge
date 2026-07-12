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
