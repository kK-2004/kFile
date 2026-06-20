import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const THEME_KEY = 'kfile.theme'

/**
 * 主题管理：light / dark / auto（跟随系统）
 * - mode: 用户选择的模式
 * - effectiveDark: 实际是否暗色（auto 时取决于系统）
 * 通过 html.dark class 驱动 Element Plus dark CSS + 自定义 CSS 变量覆盖
 */
export const useThemeStore = defineStore('theme', () => {
  const mode = ref(localStorage.getItem(THEME_KEY) || 'auto')
  const systemDark = ref(false)
  let mediaQuery = null

  const effectiveDark = computed(() => {
    if (mode.value === 'dark') return true
    if (mode.value === 'light') return false
    return systemDark.value // auto
  })

  /** 应用主题到 DOM（加/移 html.dark class） */
  function applyTheme() {
    const el = document.documentElement
    if (effectiveDark.value) {
      el.classList.add('dark')
    } else {
      el.classList.remove('dark')
    }
  }

  /** 初始化：读 localStorage + 监听系统主题变化 */
  function init() {
    // 读系统偏好
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    systemDark.value = mediaQuery.matches

    mediaQuery.addEventListener('change', (e) => {
      systemDark.value = e.matches
      if (mode.value === 'auto') applyTheme()
    })

    applyTheme()
  }

  /** 切换模式（light/dark/auto） */
  function setMode(m) {
    mode.value = m
    localStorage.setItem(THEME_KEY, m)
    applyTheme()
  }

  /** 循环切换 light → dark → auto */
  function cycleMode() {
    const order = ['light', 'dark', 'auto']
    const idx = order.indexOf(mode.value)
    setMode(order[(idx + 1) % order.length])
  }

  return { mode, effectiveDark, init, setMode, cycleMode }
})
