export const shareTypeOptions = [
  { value: 'ALL', label: '全部' },
  { value: 'FOLDER_SYNC', label: '文件夹' },
  { value: 'FILE_SET', label: '文件集' },
  { value: 'SUBMISSION_SYNC', label: '提交' },
  { value: 'CDN', label: 'CDN' },
  { value: 'HISTORY', label: '历史' }
]

export const shareTypeLabel = (type) =>
  shareTypeOptions.find(option => option.value === type)?.label || '历史'

export const buildShareLinkUrl = (row, origin = window.location.origin) => {
  const base = String(origin).replace(/\/+$/, '')
  return row?.shareType === 'CDN'
    ? `${base}/file/cdn/${encodeURIComponent(row.code)}`
    : `${base}/share?s=${encodeURIComponent(row.code)}`
}
