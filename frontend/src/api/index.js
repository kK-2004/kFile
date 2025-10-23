import axios from 'axios'

// 1) 由 Vite 的 BASE_URL 推导出子路径（vite.config.js 已设 base: '/kfile/'）
const baseFromVite = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')

// 2) 允许通过 VITE_API_BASE 覆盖；未提供时回退到 baseFromVite
//    注意用 ??（空值合并），这样 dev 环境可将其设为 '' 以使用代理
const apiBase = (import.meta.env.VITE_API_BASE ?? baseFromVite)


const instance = axios.create({
  baseURL: apiBase,
  timeout: 20000,
  withCredentials: true
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
    const fd = new FormData()
    fd.append('submitter', JSON.stringify(submitter || {}))
    for (const f of files) fd.append('files', f)
    // 取消手动设置 Content-Type，让浏览器自动带 boundary；延长超时以适配大文件
    return instance.post(`/api/projects/${projectId}/submissions`, fd, {
      timeout: 120000,
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
}
