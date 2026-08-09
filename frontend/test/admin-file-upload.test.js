import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createResumeUploadItem,
  matchesUploadRow,
  shouldUseChunkedUpload,
  uploadStatusText
} from '../src/utils/adminFileUpload.js'

test('uses ordinary upload for a MinIO file at or below 50MB', () => {
  assert.equal(shouldUseChunkedUpload({ source: 'minio', size: 50 * 1024 * 1024 }), false)
})

test('uses chunked upload only for MinIO files above 50MB', () => {
  assert.equal(shouldUseChunkedUpload({ source: 'minio', size: 50 * 1024 * 1024 + 1 }), true)
})

test('never uses chunked upload for OSS', () => {
  assert.equal(shouldUseChunkedUpload({ source: 'oss', size: 200 * 1024 * 1024 }), false)
})

test('hides hash and init phases behind the ordinary uploading label', () => {
  assert.equal(uploadStatusText({ status: 'uploading', mode: 'hash' }), '上传中')
  assert.equal(uploadStatusText({ status: 'uploading', mode: 'init' }), '上传中')
})

test('shows chunked label only after actual chunk upload starts', () => {
  assert.equal(uploadStatusText({ status: 'uploading', mode: 'chunk' }), '分片上传中')
})

test('builds a resume item with the original record id and actual mode', () => {
  const item = createResumeUploadItem(
    { id: 42, name: 'report.bin', size: 10, storageSource: 'minio' },
    { name: 'report.bin', size: 10 }
  )
  assert.equal(item.resumeFileId, 42)
  assert.equal(item.mode, 'single')
})

test('matches an active upload by record id before falling back to name', () => {
  const row = { id: 42, name: 'report.bin', originalName: 'report.bin' }
  const item = { fileId: 42, name: 'report.bin', status: 'uploading' }
  assert.equal(matchesUploadRow(row, item), true)
  assert.equal(matchesUploadRow({ ...row, id: 43 }, item), false)
})
