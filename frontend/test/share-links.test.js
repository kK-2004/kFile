import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildShareLinkUrl,
  shareTypeLabel,
  shareTypeOptions
} from '../src/utils/shareLinks.js'

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
