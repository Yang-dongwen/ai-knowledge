import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'ai-workspace-theme'

function readStoredMode(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch {
    /* ignore */
  }
  return 'system'
}

function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function applyDomTheme(resolved: ResolvedTheme) {
  const root = document.documentElement
  root.dataset.theme = resolved
  root.style.colorScheme = resolved
  // 兼容部分第三方/原生控件
  root.classList.toggle('theme-dark', resolved === 'dark')
  root.classList.toggle('theme-light', resolved === 'light')

  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) {
    meta.setAttribute('content', resolved === 'dark' ? '#0b1220' : '#f5f6f8')
  }
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStoredMode())
  const systemDark = ref(systemPrefersDark())

  const resolved = computed<ResolvedTheme>(() => {
    if (mode.value === 'system') return systemDark.value ? 'dark' : 'light'
    return mode.value
  })

  const isDark = computed(() => resolved.value === 'dark')

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* ignore */
    }
  }

  function toggle() {
    // system → 切到与当前相反的显式主题，便于一键切换
    if (mode.value === 'system') {
      setMode(isDark.value ? 'light' : 'dark')
      return
    }
    setMode(mode.value === 'dark' ? 'light' : 'dark')
  }

  function cycle() {
    const order: ThemeMode[] = ['light', 'dark', 'system']
    const i = order.indexOf(mode.value)
    setMode(order[(i + 1) % order.length])
  }

  let media: MediaQueryList | null = null
  let onMediaChange: ((e: MediaQueryListEvent) => void) | null = null
  let inited = false

  function init() {
    applyDomTheme(resolved.value)
    if (inited) return
    inited = true

    if (typeof window === 'undefined' || !window.matchMedia) return
    media = window.matchMedia('(prefers-color-scheme: dark)')
    onMediaChange = (e: MediaQueryListEvent) => {
      systemDark.value = e.matches
    }
    media.addEventListener('change', onMediaChange)
  }

  function dispose() {
    if (media && onMediaChange) {
      media.removeEventListener('change', onMediaChange)
    }
    media = null
    onMediaChange = null
    inited = false
  }

  watch(resolved, (v) => applyDomTheme(v), { immediate: false })

  return {
    mode,
    resolved,
    isDark,
    setMode,
    toggle,
    cycle,
    init,
    dispose
  }
})
