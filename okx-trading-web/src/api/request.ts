import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'
import { message } from 'ant-design-vue'

export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
  success: boolean
  timestamp: string
}

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截
request.interceptors.request.use(
  (config) => {
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    if (res.success) {
      return res as any
    }
    // 业务错误
    message.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      switch (status) {
        case 401:
          message.error('未登录或登录已过期')
          break
        case 403:
          message.error('无权限')
          break
        case 500:
          message.error('服务器内部错误')
          break
        default:
          message.error(error.response.data?.message || '请求失败')
      }
    } else {
      message.error('网络连接异常，请检查网络')
    }
    return Promise.reject(error)
  }
)

export default request
