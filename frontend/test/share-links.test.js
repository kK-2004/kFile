import test from 'node:test'
import assert from 'node:assert/strict'
import * as shareLinks from '../src/utils/shareLinks.js'

const { buildShareLinkUrl, shareTypeLabel, shareTypeOptions } = shareLinks

test('builds a stable CDN URL for CDN rows', () => {
  assert.equal(
    buildShareLinkUrl({ shareType: 'CDN', code: 'media-token' }, 'https://file.example.com'),
    'https://file.example.com/file/cdn/media-token'
  )
})

test('keeps the existing share URL for ordinary share rows', () => {
  assert.equal(
    buildShareLinkUrl({ shareType: 'FILE_SET', code: 'share-code' }, 'https://file.example.com'),
    'https://file.example.com/share?s=share-code'
  )
})

test('exposes CDN as a selectable share type', () => {
  assert.equal(shareTypeLabel('CDN'), 'CDN')
  assert.ok(shareTypeOptions.some(option => option.value === 'CDN'))
})

test('uses the server-provided location text for the share owner column', () => {
  assert.equal(typeof shareLinks.shareLocationText, 'function')
  assert.equal(shareLinks.shareLocationText?.({ locationText: '图片、音频' }), '图片、音频')
  assert.equal(shareLinks.shareLocationText?.({ projectName: '文件管理' }), '文件管理')
})

test('only treats a location as overflowed when its rendered width is exceeded', () => {
  assert.equal(shareLinks.isTextOverflowing?.({ scrollWidth: 121, clientWidth: 120 }), true)
  assert.equal(shareLinks.isTextOverflowing?.({ scrollWidth: 120, clientWidth: 120 }), false)
})

test('exposes the shared expiry choices for CDN renewal', () => {
  assert.deepEqual(shareLinks.shareExpiryOptions?.at(-1), { value: 0, label: '永久' })
  assert.ok(shareLinks.shareExpiryOptions?.some(option => option.value === 3600))
})
