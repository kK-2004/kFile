import { defineStore } from 'pinia'
import api from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,          // 当前登录主体（站点或本地）
    loaded: false,
    token: null          // k-Site accessToken（若存在）
  }),
  actions: {
    setToken(token) {
      this.token = token || null
      try {
        if (token) {
          localStorage.setItem('KSITE_ACCESS_TOKEN', token)
          // 兼容某些页面读取 accessToken 的场景
          localStorage.setItem('accessToken', token)
        } else {
          localStorage.removeItem('KSITE_ACCESS_TOKEN')
          localStorage.removeItem('accessToken')
        }
      } catch {}
    },
    async loadMe() {
      try {
        // 优先用 Bearer 校验站点登录
        const t = this.token || localStorage.getItem('KSITE_ACCESS_TOKEN') || localStorage.getItem('accessToken')
        if (t) {
          this.token = t
          try {
            const { data } = await api.authMe()
            // 新版后端仅返回 { username, role }
            if (data && typeof data.username === 'string' && typeof data.role === 'string') {
              this.user = { ...data, mode: 'site' }
              this.loaded = true
              return
            }
          } catch (e) {
            // token 失效则清除并继续尝试本地会话
            this.setToken(null)
          }
        }
        // 回退到本地管理员会话
        const { data } = await api.adminMe()
        if (data && data.username && data.username !== 'anonymousUser') this.user = { ...data, mode: 'local' }
      } catch (e) {
        // 未登录或跨域失败
      } finally { this.loaded = true }
    },
    async login(username, password) {
      // Perform login, then fetch full profile to ensure role is present
      await api.adminLogin(username, password)
      await this.loadMe()
    },
    async logout() {
      // backend logout URL configured in SecurityConfig; we can hit it via fetch
      try { await fetch('/api/admin/auth/logout', { method: 'POST', credentials: 'include' }) } catch {}
      this.user = null
      this.setToken(null)
      this.loaded = true
    }
  }
})
