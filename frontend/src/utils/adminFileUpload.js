export const MULTIPART_THRESHOLD = 50 * 1024 * 1024
export const CHUNK_SIZE = 5 * 1024 * 1024

export const shouldUseChunkedUpload = ({ source, size }) =>
  source === 'minio' && size > MULTIPART_THRESHOLD

export const uploadStatusText = (item) => {
  if (item.status === 'done') return '完成'
  if (item.status === 'error') return '失败'
  if (item.status === 'queued') return '排队中'
  return item.mode === 'chunk' ? '分片上传中' : '上传中'
}

export const createResumeUploadItem = (row, file) => ({
  uid: `resume-${Date.now()}`,
  name: file.name,
  percent: 0,
  status: 'uploading',
  mode: shouldUseChunkedUpload({ source: row.storageSource, size: file.size }) ? 'chunk' : 'single',
  error: '',
  _file: file,
  resumeFileId: row.id,
  parentId: row.parentId ?? null,
  source: row.storageSource
})

export const matchesUploadRow = (row, item) => {
  const boundId = item.fileId ?? item.resumeFileId
  if (boundId != null) return row.id === boundId
  return row.originalName === item.name || row.name === item.name
}
