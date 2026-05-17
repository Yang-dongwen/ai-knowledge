import { defineStore } from 'pinia'
import { ref } from 'vue'
import { systemApi } from '@/api/system.api'
import { message } from 'ant-design-vue'

export const useSystemStore = defineStore('system', () => {
  const systemStatus = ref<'RUNNING' | 'STOPPED'>('RUNNING')
  const runMode = ref<'PAPER' | 'PROD'>('PAPER')

  async function fetchSystemStatus() {
    try {
      const res = await systemApi.getStatus()
      systemStatus.value = res.data.status as 'RUNNING' | 'STOPPED'
    } catch (e) {
      // ignore
    }
  }

  async function stopSystem() {
    try {
      await systemApi.stop()
      systemStatus.value = 'STOPPED'
      message.success('系统已停止')
    } catch (e) {
      message.error('停止失败')
    }
  }

  async function resumeSystem() {
    try {
      await systemApi.resume()
      systemStatus.value = 'RUNNING'
      message.success('系统已恢复运行')
    } catch (e) {
      message.error('恢复失败')
    }
  }

  // 初始化时获取状态
  fetchSystemStatus()

  return {
    systemStatus,
    runMode,
    fetchSystemStatus,
    stopSystem,
    resumeSystem
  }
})
