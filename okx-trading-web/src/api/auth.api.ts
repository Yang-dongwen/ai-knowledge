import request from './request'

/** USER 普通用户 | MEMBER 会员 | SUPER_ADMIN 超级管理员 */
export type UserRole = 'USER' | 'MEMBER' | 'SUPER_ADMIN'

export interface AuthUser {
  id: string
  email: string
  nickname: string
  role?: UserRole | string
  roleLabel?: string
  emailVerified: boolean
  /** 1 正常 0 禁用 */
  status?: number
  lastLoginAt?: string | null
  createdAt?: string | null
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

export const ROLE_LABELS: Record<string, string> = {
  USER: '普通用户',
  MEMBER: '会员',
  SUPER_ADMIN: '超级管理员'
}

export function roleLabel(role?: string | null, fallback?: string | null): string {
  if (fallback) return fallback
  if (!role) return ROLE_LABELS.USER
  return ROLE_LABELS[role] || role
}

export const authApi = {
  sendRegisterCode(email: string) {
    return request.post('/auth/register/send-code', { email })
  },
  register(data: { email: string; password: string; code: string; nickname?: string }) {
    return request.post('/auth/register', data) as Promise<{ data: LoginResult }>
  },
  login(data: { email: string; password: string }) {
    return request.post('/auth/login', data) as Promise<{ data: LoginResult }>
  },
  sendResetCode(email: string) {
    return request.post('/auth/password/send-code', { email })
  },
  resetPassword(data: { email: string; code: string; newPassword: string }) {
    return request.post('/auth/password/reset', data)
  },
  /** 从后端查询当前用户资料 */
  me() {
    return request.get('/auth/me') as Promise<{ data: AuthUser }>
  },
  logout() {
    return request.post('/auth/logout')
  }
}
