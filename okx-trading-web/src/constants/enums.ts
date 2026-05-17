// 订单状态
export const ORDER_STATUS_MAP: Record<string, { label: string; color: string }> = {
  CREATED: { label: '已创建', color: 'default' },
  SUBMITTING: { label: '提交中', color: 'processing' },
  SUBMITTED: { label: '已提交', color: 'blue' },
  FILLED: { label: '已成交', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  CANCELED: { label: '已取消', color: 'default' },
  UNKNOWN: { label: '未知', color: 'warning' }
}

// 交易信号
export const SIGNAL_MAP: Record<string, { label: string; color: string }> = {
  BUY: { label: '买入', color: 'green' },
  SELL: { label: '卖出', color: 'red' },
  HOLD: { label: '持有', color: 'default' }
}

// 订单方向
export const SIDE_MAP: Record<string, { label: string; color: string }> = {
  BUY: { label: '买入', color: 'green' },
  SELL: { label: '卖出', color: 'red' }
}

// 运行模式
export const RUN_MODE_MAP: Record<string, { label: string; color: string }> = {
  PAPER: { label: '模拟盘', color: 'blue' },
  PROD: { label: '实盘', color: 'red' }
}

// K线周期选项
export const TIMEFRAME_OPTIONS = [
  { label: '1分钟', value: '1m' },
  { label: '5分钟', value: '5m' },
  { label: '15分钟', value: '15m' },
  { label: '1小时', value: '1H' }
]

// 交易对选项
export const SYMBOL_OPTIONS = [
  { label: 'BTC-USDT', value: 'BTC-USDT' },
  { label: 'ETH-USDT', value: 'ETH-USDT' }
]
