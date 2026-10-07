import assert from 'node:assert/strict'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { buildAuthorizeUrl, gatewayConfig, needsRefresh, pkceChallenge, toStoredTokens } from '../src/auth/oidc.js'

globalThis.window = { location: { origin: 'https://file.ksite.xin' } }
gatewayConfig.issuer = 'https://gw.ksite.xin'

test('PKCE S256 challenge is base64url(sha256(verifier)) without padding', async () => {
  const verifier = 'dBjftJeZ4CVP-mJ92K9DwgOWtFcmLOiWnNvyWB-3d0k'
  const expected = createHash('sha256').update(verifier).digest('base64url')
  assert.equal(await pkceChallenge(verifier), expected)
})

test('authorize URL targets gateway with PKCE and kfile callback', () => {
  const url = new URL(buildAuthorizeUrl({ state: 's', nonce: 'n', challenge: 'c' }))
  assert.equal(url.origin + url.pathname, 'https://gw.ksite.xin/oidc/authorize')
  assert.equal(url.searchParams.get('client_id'), 'kfile')
  assert.equal(url.searchParams.get('redirect_uri'), 'https://file.ksite.xin/auth/callback')
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256')
  assert.equal(url.searchParams.get('scope'), 'openid')
  assert.equal(url.searchParams.get('prompt'), null)
})

test('refresh rotation keeps previous id_token for logout hint', () => {
  const first = toStoredTokens({ access_token: 'a1', refresh_token: 'r1', id_token: 'i1', expires_in: 900 }, null, 0)
  assert.deepEqual(first, { accessToken: 'a1', refreshToken: 'r1', idToken: 'i1', expiresAt: 900000 })
  const rotated = toStoredTokens({ access_token: 'a2', refresh_token: 'r2', expires_in: 900 }, first, 1000)
  assert.equal(rotated.idToken, 'i1')
  assert.equal(rotated.refreshToken, 'r2')
})

test('refreshes when less than 60s remain', () => {
  assert.equal(needsRefresh({ expiresAt: 100000 }, 30000), false)
  assert.equal(needsRefresh({ expiresAt: 100000 }, 50000), true)
})
