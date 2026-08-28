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

export const shareExpiryOptions = [
  { value: 300, label: '5 分钟' },
  { value: 600, label: '10 分钟' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
  { value: 86400, label: '1 天' },
  { value: 604800, label: '7 天' },
  { value: 2592000, label: '30 天' },
  { value: 0, label: '永久' }
]

export const shareLocationText = (row) =>
  row?.locationText || row?.projectName || '文件管理'

export const isTextOverflowing = (element) =>
  Boolean(element && element.scrollWidth > element.clientWidth)

export const buildShareLinkUrl = (row, origin = window.location.origin) => {
  const base = String(origin).replace(/\/+$/, '')
  return row?.shareType === 'CDN'
    ? `${base}/file/cdn/${encodeURIComponent(row.code)}`
    : `${base}/share?s=${encodeURIComponent(row.code)}`
}
