import { defineStore } from 'pinia'
import api from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    loaded: false
  }),
  actions: {
    async loadMe() {
      try {
        const { data } = await api.adminMe()
        if (data && data.username && data.username !== 'anonymousUser') this.user = data
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
      this.loaded = true
    }
  }
})
