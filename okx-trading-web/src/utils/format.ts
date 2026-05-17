import dayjs from 'dayjs'

/**
 * 格式化金额，保留指定小数位。
 */
export function formatAmount(value: number | string | null | undefined, decimals = 2): string {
  if (value === null || value === undefined || value === '') return '--'
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '--'
  return num.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
}

/**
 * 格式化百分比。
 */
export function formatPercent(value: number | string | null | undefined, decimals = 2): string {
  if (value === null || value === undefined || value === '') return '--'
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '--'
  return (num * 100).toFixed(decimals) + '%'
}

/**
 * 格式化时间。
 */
export function formatTime(value: string | number | null | undefined, format = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!value) return '--'
  return dayjs(value).format(format)
}

/**
 * 格式化时间戳（毫秒）。
 */
export function formatTimestamp(ts: number | null | undefined, format = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!ts) return '--'
  return dayjs(ts).format(format)
}

/**
 * 获取盈亏颜色 class。
 */
export function getPnlClass(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return ''
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (num > 0) return 'profit'
  if (num < 0) return 'loss'
  return ''
}
