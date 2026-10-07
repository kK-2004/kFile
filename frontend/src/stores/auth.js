import { defineStore } from 'pinia'
import api from '../api'
import { clearTokens, gatewayEnabled, hasTokens, logoutGateway, setLocalMode } from '../auth/oidc'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    loaded: false,
    // 网关登录有效但 kFile 账号不可用：{ code, message }
    accountError: null
  }),
  actions: {
    async loadMe() {
      this.accountError = null
      try {
        if (gatewayEnabled() && hasTokens()) {
          setLocalMode(false)
          try {
            const { data } = await api.adminMe()
            if (data && data.username) this.user = { ...data }
            return
          } catch (e) {
            if (e?.response?.status === 403) {
              this.accountError = { code: e.response.data?.code, message: e.response.data?.message }
              return
            }
            // token 失效且续期失败：再看是否有本地会话
          }
        }
        // 无网关 token：检测本地会话（应急登录）
        setLocalMode(true)
        const { data } = await api.adminMe()
        if (data && data.username && data.username !== 'anonymousUser') {
          this.user = { ...data }
        } else if (gatewayEnabled()) {
          setLocalMode(false)
        }
      } catch {
        if (gatewayEnabled()) setLocalMode(false)
      } finally { this.loaded = true }
    },
    // kFile 账号密码登录（/admin/local-login）：切到本地会话模式，丢弃网关 token
    async login(username, password) {
      clearTokens()
      setLocalMode(true)
      await api.adminLogin(username, password)
      await this.loadMe()
    },
    async logout() {
      if (gatewayEnabled() && hasTokens()) {
        this.user = null
        return logoutGateway()
      }
      try { await api.instance.post('/api/admin/auth/logout') } catch {}
      if (gatewayEnabled()) setLocalMode(false)
      this.user = null
      this.loaded = true
    }
  }
})
