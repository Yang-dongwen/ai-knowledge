import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, type AuthUser, type LoginResult } from '@/api/auth.api'

const TOKEN_KEY = 'okx_auth_token'
const USER_KEY = 'okx_auth_user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<AuthUser | null>(loadUser())

  const isLoggedIn = computed(() => !!token.value)
  const role = computed(() => (user.value?.role || 'USER').toUpperCase())
  const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')
  /** 与后端 MemberStatusService.isActive 对齐，勿仅看 role===MEMBER */
  const isMemberActive = computed(() => {
    if (role.value === 'SUPER_ADMIN') return true
    if (user.value?.memberActive != null) return !!user.value.memberActive
    if (role.value !== 'MEMBER') return false
    const exp = user.value?.memberExpireAt
    return !!exp && new Date(exp).getTime() > Date.now()
  })
  const isMember = isMemberActive

  function loadUser(): AuthUser | null {
    try {
      const raw = localStorage.getItem(USER_KEY)
      return raw ? (JSON.parse(raw) as AuthUser) : null
    } catch {
      return null
    }
  }

  function persist(result: LoginResult) {
    token.value = result.token
    user.value = result.user
    localStorage.setItem(TOKEN_KEY, result.token)
    localStorage.setItem(USER_KEY, JSON.stringify(result.user))
  }

  function clear() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  async function login(email: string, password: string) {
    const res = await authApi.login({ email, password })
    persist(res.data)
    return res.data
  }

  async function loginWithOAuthTicket(ticket: string) {
    const res = await authApi.exchangeOAuthTicket(ticket)
    persist(res.data)
    return res.data
  }

  async function register(payload: {
    email: string
    password: string
    code: string
    nickname?: string
  }) {
    const res = await authApi.register(payload)
    persist(res.data)
    return res.data
  }

  async function fetchMe() {
    if (!token.value) return null
    try {
      const res = await authApi.me()
      user.value = res.data
      localStorage.setItem(USER_KEY, JSON.stringify(res.data))
      return res.data
    } catch (e: any) {
      // 仅认证失败清会话；网络/5xx 保留本地缓存，避免抖动强制登出
      const status = e?.response?.status
      if (status === 401 || status === 403) {
        clear()
      }
      return null
    }
  }

  async function logout() {
    try {
      if (token.value) await authApi.logout()
    } catch {
      // ignore
    }
    clear()
  }

  return {
    token,
    user,
    isLoggedIn,
    role,
    isSuperAdmin,
    isMember,
    isMemberActive,
    login,
    loginWithOAuthTicket,
    register,
    fetchMe,
    logout,
    clear,
    persist
  }
})
