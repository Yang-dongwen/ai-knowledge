import request from './request'
import type { AuthUser } from './auth.api'

export interface AdminUserPage {
  items: AuthUser[]
  total: number
  page: number
  size: number
}

export interface ListUsersParams {
  page?: number
  size?: number
  keyword?: string
  role?: string
  status?: number | null
}

export const adminApi = {
  listUsers(params: ListUsersParams = {}) {
    return request.get('/admin/users', { params }) as Promise<{ data: AdminUserPage }>
  },
  /** status: 1 启用 0 禁用 */
  updateUserStatus(id: string, status: 0 | 1) {
    return request.put(`/admin/users/${id}/status`, { status }) as Promise<{ data: AuthUser }>
  }
}
