import { defineStore } from 'pinia'
import { ref } from 'vue'
import { systemApi } from '@/api/system.api'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'

export const useSystemStore = defineStore('system', () => {
  const systemStatus = ref<'RUNNING' | 'STOPPED'>('RUNNING')
  const runMode = ref<'PAPER' | 'PROD'>('PAPER')

  /** 系统状态接口仅超级管理员可访问，普通用户勿请求（会 403 弹「无权限」） */
  function canAccessSystemApi(): boolean {
    const auth = useAuthStore()
    return auth.isLoggedIn && auth.isSuperAdmin
  }

  async function fetchSystemStatus() {
    if (!canAccessSystemApi()) return
    try {
      const res = await systemApi.getStatus()
      systemStatus.value = res.data.status as 'RUNNING' | 'STOPPED'
    } catch {
      // ignore
    }
  }

  async function stopSystem() {
    if (!canAccessSystemApi()) return
    try {
      await systemApi.stop()
      systemStatus.value = 'STOPPED'
      message.success('系统已停止')
    } catch {
      message.error('停止失败')
    }
  }

  async function resumeSystem() {
    if (!canAccessSystemApi()) return
    try {
      await systemApi.resume()
      systemStatus.value = 'RUNNING'
      message.success('系统已恢复运行')
    } catch {
      message.error('恢复失败')
    }
  }

  return {
    systemStatus,
    runMode,
    fetchSystemStatus,
    stopSystem,
    resumeSystem
  }
})
