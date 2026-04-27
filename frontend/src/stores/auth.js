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
        if (data && data.username && data.username !== 'anonymousUser') this.user = { ...data }
      } catch (e) {
      } finally { this.loaded = true }
    },
    async login(username, password) {
      await api.adminLogin(username, password)
      await this.loadMe()
    },
    async logout() {
      try { await fetch('/api/admin/auth/logout', { method: 'POST', credentials: 'include' }) } catch {}
      this.user = null
      this.loaded = true
    }
  }
})
