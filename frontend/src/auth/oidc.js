// 统一认证网关登录（OIDC 授权码 + PKCE，公共客户端）。
// 生产：VITE_GATEWAY_ISSUER 指向网关（如 https://gw.ksite.xin），接口经 VITE_GATEWAY_API_BASE（如 https://gw.ksite.xin/kfile）
// 由网关代理到 kFile，kFile 用网关 JWKS 自行验签。未配置 issuer 时退回旧的本地会话模式（本地开发）。
// 应急：网关不可用时在 kFile 域名下调用 POST /api/admin/auth/local-login 获得本地会话，前端检测到会话即以本地模式工作。

const env = import.meta.env || {}

export const gatewayConfig = {
  issuer: trimSlash(env.VITE_GATEWAY_ISSUER || ''),
  clientId: env.VITE_GATEWAY_CLIENT_ID || 'kfile',
  apiBase: trimSlash(env.VITE_GATEWAY_API_BASE || '')
}

export const gatewayEnabled = () => !!gatewayConfig.issuer

const TOKENS_KEY = 'kfile-gw-tokens'
const PENDING_KEY = 'kfile-oidc-pending'
// access token 剩余有效期低于该值时先续期
const REFRESH_LEEWAY_MS = 60 * 1000

let localMode = false
let refreshing = null

function trimSlash(s) {
  return String(s).replace(/\/+$/, '')
}

function storage() {
  return globalThis.localStorage
}

/** 本地会话模式（应急登录）：接口直连 kFile 并携带 Cookie。 */
export function setLocalMode(on) {
  localMode = !!on
}

export function isLocalMode() {
  return localMode || !gatewayEnabled()
}

export function redirectUri() {
  return `${window.location.origin}/auth/callback`
}

export function readTokens() {
  try {
    const raw = storage()?.getItem(TOKENS_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function hasTokens() {
  return !!readTokens()?.accessToken
}

export function clearTokens() {
  storage()?.removeItem(TOKENS_KEY)
}

/** 解析 token 响应为本地存储结构；refresh 轮换时旧 id_token 保留供登出使用。 */
export function toStoredTokens(resp, previous, now = Date.now()) {
  return {
    accessToken: resp.access_token,
    refreshToken: resp.refresh_token || previous?.refreshToken || null,
    idToken: resp.id_token || previous?.idToken || null,
    expiresAt: now + Number(resp.expires_in || 0) * 1000
  }
}

export function needsRefresh(tokens, now = Date.now()) {
  return !!tokens && tokens.expiresAt - now < REFRESH_LEEWAY_MS
}

function base64Url(bytes) {
  let s = ''
  bytes.forEach(b => { s += String.fromCharCode(b) })
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomString(len = 32) {
  const bytes = new Uint8Array(len)
  globalThis.crypto.getRandomValues(bytes)
  return base64Url(bytes)
}

export async function pkceChallenge(verifier) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

export function buildAuthorizeUrl({ state, nonce, challenge, prompt }) {
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: gatewayConfig.clientId,
    redirect_uri: redirectUri(),
    scope: 'openid',
    state,
    nonce,
    code_challenge: challenge,
    code_challenge_method: 'S256'
  })
  if (prompt) params.set('prompt', prompt)
  return `${gatewayConfig.issuer}/oidc/authorize?${params}`
}

/** 跳转网关登录；returnTo 为登录后回到的站内路径。 */
export async function login(returnTo = '/admin/projects', { prompt } = {}) {
  const state = randomString()
  const nonce = randomString()
  const verifier = randomString(48)
  sessionStorage.setItem(PENDING_KEY, JSON.stringify({ state, nonce, verifier, returnTo }))
  window.location.assign(buildAuthorizeUrl({ state, nonce, challenge: await pkceChallenge(verifier), prompt }))
  return new Promise(() => {})
}

function decodePayload(jwt) {
  const part = String(jwt || '').split('.')[1] || ''
  const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'))
  return JSON.parse(decodeURIComponent(escape(json)))
}

async function postToken(form) {
  const resp = await fetch(`${gatewayConfig.issuer}/oidc/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form)
  })
  const data = await resp.json().catch(() => ({}))
  if (!resp.ok) {
    const err = new Error(data.error_description || data.error || '登录失败')
    err.code = data.error
    throw err
  }
  return data
}

/**
 * 处理 /auth/callback：
 * - 带 code：校验 state、换取 token、校验 id_token nonce，返回 { returnTo }；
 * - 带 error：返回 { error, description }；
 * - 都没有（网关登出后回到这里）：返回 { returnTo: '/' }。
 */
export async function handleCallback(search) {
  const params = new URLSearchParams(search)
  const pending = JSON.parse(sessionStorage.getItem(PENDING_KEY) || 'null')
  sessionStorage.removeItem(PENDING_KEY)
  if (params.get('error')) {
    return { error: params.get('error'), description: params.get('error_description') || '' }
  }
  const code = params.get('code')
  if (!code) return { returnTo: '/' }
  if (!pending || pending.state !== params.get('state')) {
    return { error: 'invalid_state', description: '登录状态已失效，请重新登录' }
  }
  const data = await postToken({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri(),
    client_id: gatewayConfig.clientId,
    code_verifier: pending.verifier
  })
  if (data.id_token && decodePayload(data.id_token).nonce !== pending.nonce) {
    return { error: 'invalid_nonce', description: '登录校验失败，请重新登录' }
  }
  storage().setItem(TOKENS_KEY, JSON.stringify(toStoredTokens(data, null)))
  return { returnTo: pending.returnTo || '/admin/projects' }
}

/** refresh 续期（并发只发一次）；失败清空本地 token 并返回 null。 */
export function refresh() {
  if (refreshing) return refreshing
  const tokens = readTokens()
  if (!tokens?.refreshToken) return Promise.resolve(null)
  refreshing = postToken({
    grant_type: 'refresh_token',
    refresh_token: tokens.refreshToken,
    client_id: gatewayConfig.clientId
  })
    .then(data => {
      const next = toStoredTokens(data, tokens)
      storage().setItem(TOKENS_KEY, JSON.stringify(next))
      return next.accessToken
    })
    .catch(() => {
      clearTokens()
      return null
    })
    .finally(() => { refreshing = null })
  return refreshing
}

/** 取可用的 access token：快过期先续期；没有登录返回 null。 */
export async function getAccessToken() {
  const tokens = readTokens()
  if (!tokens?.accessToken) return null
  if (needsRefresh(tokens)) return refresh()
  return tokens.accessToken
}

/** 清本地 token 并跳网关登出（结束网关 SSO 会话），登出后回到首页。 */
export function logoutGateway() {
  const tokens = readTokens()
  clearTokens()
  const params = new URLSearchParams({ post_logout_redirect_uri: redirectUri() })
  if (tokens?.idToken) params.set('id_token_hint', tokens.idToken)
  window.location.assign(`${gatewayConfig.issuer}/oidc/logout?${params}`)
  return new Promise(() => {})
}
