import { defineStore } from 'pinia'
import api from '../api'
import { clearTokens, gatewayEnabled, hasTokens, logoutGateway, setLocalMode } from '../auth/oidc'

/** 网关模式下 /me 失败的可读原因 */
function describeMeError(e) {
  const status = e?.response?.status
  const body = e?.response?.data || {}
  if (status === 403) return { code: body.code, message: body.message || '当前账号无法使用 kFile' }
  if (status === 401) {
    return { code: body.code || 'UNAUTHORIZED', message: 'kFile 拒绝了统一认证的登录凭证，请检查 kFile 的 client-id / issuer 配置' }
  }
  return { code: 'NETWORK', message: `无法连接 kFile 服务（${status || '网络错误或跨域被拒'}），请稍后重试` }
}

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
          // 已在网关登录：kFile 不认这次登录时只展示原因，不再自动跳网关（否则网关 SSO 秒回 → 无限往返）
          try {
            const { data } = await api.adminMe()
            if (data && data.username) {
              this.user = { ...data }
            } else {
              this.accountError = {
                code: 'GATEWAY_NOT_RECOGNIZED',
                message: '已在统一认证登录，但 kFile 未识别该登录（服务端未启用网关验签，请检查 app.gateway 配置）'
              }
            }
          } catch (e) {
            this.accountError = describeMeError(e)
          }
          return
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
