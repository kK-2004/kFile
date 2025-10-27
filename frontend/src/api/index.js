import axios from 'axios'

// 1) 由 Vite 的 BASE_URL 推导出子路径（vite.config.js 已设 base: '/kfile/'）
const baseFromVite = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')

// 2) 允许通过 VITE_API_BASE 覆盖；
//    dev 环境默认使用 ''（让 Vite 代理 '/api' 生效），prod 回退到 baseFromVite('/kfile')
const apiBase = (typeof import.meta.env.VITE_API_BASE !== 'undefined')
  ? import.meta.env.VITE_API_BASE
  : (import.meta.env.DEV ? '' : baseFromVite)


const instance = axios.create({
  baseURL: apiBase,
  timeout: 20000,
  withCredentials: true
})

// Attach Bearer token from localStorage when present
instance.interceptors.request.use((config) => {
  try {
    const token = localStorage.getItem('KSITE_ACCESS_TOKEN') || localStorage.getItem('accessToken')
    if (token) {
      config.headers = config.headers || {}
      config.headers['Authorization'] = `Bearer ${token}`
      // 当使用 Bearer 时，不需要发送 Cookie，避免混淆（后端也允许）
      config.withCredentials = false
    }
  } catch {}
  return config
})

// Global response interceptor: redirect on 401 with X-Redirect
instance.interceptors.response.use(
  (res) => res,
  (error) => {
    try {
      const status = error?.response?.status
      let redirect = error?.response?.headers?.['x-redirect']
      if (status === 401 && redirect) {
        // 兼容后端未带子路径前缀的场景：自动补上 BASE_URL
        if (!/^https?:/i.test(redirect)) {
          const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
          if (redirect.startsWith('/')) {
            if (!redirect.startsWith(base + '/')) {
              redirect = base + redirect
            }
          } else {
            redirect = base + '/' + redirect
          }
        }
        const to = redirect + (redirect.includes('?') ? '&' : '?') + 'redirect=' + encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = to
        return
      }
    } catch {}
    return Promise.reject(error)
  }
)

export default {
  // Site auth
  authMe() { return instance.get('/api/auth/me') },
  // Projects
  listProjects() { return instance.get('/api/projects') },
  getProject(id) { return instance.get(`/api/projects/${id}`) },
  createProject(data) { return instance.post('/api/projects', data) },
  updateProject(id, data) { return instance.put(`/api/projects/${id}`, data) },

  // Submissions
  pageSubmissions(projectId, page = 0, size = 20) {
    return instance.get(`/api/projects/${projectId}/submissions`, { params: { page, size } })
  },
  exportSubmissions(projectId) {
    return instance.get(`/api/projects/${projectId}/submissions/export`, { responseType: 'blob' })
  },
  exportZip(projectId, fieldKey, fieldValue) {
    const params = {}
    if (fieldKey) params.fieldKey = fieldKey
    if (fieldValue) params.fieldValue = fieldValue
    return instance.get(`/api/projects/${projectId}/submissions/archive`, { params, responseType: 'blob' })
  },
  latestStatus(projectId, params) {
    // params can be: { submitter: string } or { fieldValue: string }
    const p = typeof params === 'string' ? { submitter: params } : (params || {})
    return instance.get(`/api/projects/${projectId}/submissions/status`, { params: p, responseType: 'json' })
  },
  submit(projectId, submitter, files, config = {}) {
    const totalSize = (files || []).reduce((s, f) => s + (f?.size || 0), 0)
    const threshold = 50 * 1024 * 1024 // 50MB

    // 若总大小超过阈值，自动走直传 OSS 流程
    if (totalSize > threshold) {
      const filesMeta = Array.from(files || []).map(f => ({ name: f.name, contentType: f.type || 'application/octet-stream', size: f.size || 0 }))
      return this.directInit(projectId, submitter, filesMeta).then(res => {
        const entries = res?.data?.entries || res?.entries || []
        if (!entries.length) throw new Error('直传初始化失败：未返回任何条目')
        // 顺序上传每个文件（如需并发可后续优化）
        return entries.reduce((p, entry, idx) => {
          const file = files[idx]
          return p.then(() => this.directPut(entry.putUrl, file, config.onUploadProgress))
        }, Promise.resolve()).then(() => {
          const keys = entries.map(e => e.key)
          return this.directComplete(projectId, submitter, keys)
        })
      })
    }

    // 否则继续走表单上传，并将超时设为 0（不限时）
    const fd = new FormData()
    fd.append('submitter', JSON.stringify(submitter || {}))
    for (const f of files) fd.append('files', f)
    return instance.post(`/api/projects/${projectId}/submissions`, fd, {
      timeout: 0,
      ...config
    })
  },

  // 直传初始化/完成
  directInit(projectId, submitter, filesMeta) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-init`, {
      submitter,
      files: filesMeta.map(f => ({ name: f.name, contentType: f.type || 'application/octet-stream' }))
    })
  },
  directComplete(projectId, submitter, keys) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-complete`, { submitter, keys })
  },
  // 直传 PUT（签名 URL 为绝对地址）
  directPut(putUrl, file, onUploadProgress) {
    return axios.put(putUrl, file, {
      headers: { 'Content-Type': file.type || 'application/octet-stream' },
      // 大文件上传时间不可预估，禁用超时由浏览器网络栈控制
      timeout: 0,
      onUploadProgress
    })
  },

  // 分片直传：初始化、签名分片、完成合并
  directMultipartInit(projectId, submitter, filesMeta) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-multipart-init`, {
      submitter,
      files: filesMeta.map(f => ({ name: f.name, contentType: f.type || 'application/octet-stream', size: f.size || 0 }))
    })
  },
  directMultipartSign(projectId, key, uploadId, partNumber, size, contentType) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-multipart-sign`, {
      key, uploadId, partNumber, size, contentType
    })
  },
  directMultipartComplete(projectId, key, uploadId, parts) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-multipart-complete`, {
      key, uploadId, parts
    })
  },

  // Admin auth
  adminLogin(username, password) { return instance.post('/api/admin/auth/login', { username, password }) },
  adminMe() { return instance.get('/api/admin/auth/me') }
  ,adminListUsers() { return instance.get('/api/admin/users') }
  ,adminCreateUser(payload) { return instance.post('/api/admin/users', payload) }
  ,adminListUserProjects(userId) { return instance.get(`/api/admin/users/${userId}/projects`) }
  ,adminGrantProject(userId, projectId) { return instance.post(`/api/admin/users/${userId}/projects/${projectId}`) }
  ,adminRevokeProject(userId, projectId) { return instance.delete(`/api/admin/users/${userId}/projects/${projectId}`) }
  ,adminListProjects() { return instance.get('/api/admin/projects') }
  ,adminChangePassword(currentPassword, newPassword) { return instance.post('/api/admin/auth/change-password', { currentPassword, newPassword }) }
  ,adminResetPassword(userId) { return instance.post(`/api/admin/users/${userId}/reset-password`) }
  ,adminDeleteUser(userId) { return instance.delete(`/api/admin/users/${userId}`) }
  ,deleteProject(id) { return instance.delete(`/api/projects/${id}`) }
  ,adminStartDeleteProject(id) { return instance.post(`/api/admin/projects/${id}/delete-task`) }
  ,adminGetTask(taskId) { return instance.get(`/api/admin/tasks/${taskId}`) }
  ,adminStartArchiveTask(id, fieldKey, fieldValue) { return instance.post(`/api/admin/projects/${id}/archive-task`, { fieldKey, fieldValue }) }
  ,adminDownloadTask(taskId) { return instance.get(`/api/admin/tasks/${taskId}/download`, { responseType: 'blob' }) }

  // Prepared ZIP（带 Content-Length）
  
}
