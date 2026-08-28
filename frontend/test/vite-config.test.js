import test from 'node:test'
import assert from 'node:assert/strict'
import viteConfig from '../vite.config.js'

test('proxies stable CDN preview links through the local Vite server', () => {
  const fileProxy = viteConfig.server?.proxy?.['/file']
  assert.ok(fileProxy)
  assert.ok(fileProxy.target)
})
