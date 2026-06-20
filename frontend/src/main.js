import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 暗色模式 CSS 变量（配合 html.dark class 生效）
import 'element-plus/theme-chalk/dark/css-vars.css'
// Tailwind v4 (仅theme+utilities，无preflight，避免与Element冲突)
import './styles/tailwind.css'
import App from './App.vue'
import router from './router'
import './styles/admin.css'
import { useThemeStore } from './stores/theme'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)

// 在 mount 前初始化主题（避免闪烁）
// 直接操作 DOM，不依赖 Pinia active 状态
const savedTheme = localStorage.getItem('kfile.theme') || 'auto'
const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches
const shouldDark = savedTheme === 'dark' || (savedTheme === 'auto' && systemDark)
if (shouldDark) document.documentElement.classList.add('dark')

app.mount('#app')

// mount 后用 store 管理（监听系统变化等）
useThemeStore().init()
