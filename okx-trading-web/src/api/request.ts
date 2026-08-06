import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'
import { message, Modal } from 'ant-design-vue'

export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
  success: boolean
  timestamp: string
}

const TOKEN_KEY = 'okx_auth_token'
const USER_KEY = 'okx_auth_user'

let authRedirecting = false

/** 统一 401 处理：axios / fetch / SSE / 上传共用 */
export function handleAuthFailure(status?: number, msg?: string) {
  if (status != null && status !== 401) return false
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (authRedirecting) return true
  const path = location.pathname
  if (
    path.startsWith('/login')
    || path.startsWith('/register')
    || path.startsWith('/forgot-password')
    || path.startsWith('/s/')
  ) {
    return true
  }
  authRedirecting = true
  message.error(msg || '未登录或登录已过期')
  const redirect = encodeURIComponent(path + location.search)
  location.href = `/login?redirect=${redirect}`
  return true
}

/** 仅允许站内相对路径，防开放重定向 */
export function safeAppRedirect(raw: unknown, fallback = '/home'): string {
  if (typeof raw !== 'string' || !raw) return fallback
  const s = raw.trim()
  if (!s.startsWith('/') || s.startsWith('//') || s.includes('://')) return fallback
  if (s.includes('\\') || s.includes('\n') || s.includes('\r')) return fallback
  return s
}

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截：附加 JWT
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data as ApiResponse & { headers?: Record<string, string> }
    if (res.success) {
      // 保留响应头（知识库详情用 X-Kb-* 做耗时分析）；业务仍主要用 data
      const h = response.headers || {}
      const flat: Record<string, string> = {}
      Object.keys(h).forEach((k) => {
        const v = (h as any)[k]
        if (v != null && typeof v !== 'object') flat[k.toLowerCase()] = String(v)
      })
      res.headers = flat
      return res as any
    }
    message.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      const msg = error.response.data?.message
      switch (status) {
        case 401:
          handleAuthFailure(401, msg)
          break
        case 403: {
          const m = msg || '无权限'
          message.error(m)
          if (/会员/.test(m) && !location.pathname.startsWith('/member')) {
            Modal.confirm({
              title: '需要会员',
              content: m,
              okText: '去开通',
              cancelText: '稍后再说',
              onOk: () => {
                location.href = '/member/recharge'
              }
            })
          }
          break
        }
        case 500:
          message.error(msg || '服务器内部错误')
          break
        default:
          message.error(msg || '请求失败')
      }
    } else if (error.code === 'ECONNABORTED') {
      message.error('请求超时')
    } else {
      message.error('网络连接异常，请检查网络')
    }
    return Promise.reject(error)
  }
)

export default request
