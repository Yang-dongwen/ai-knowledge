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
