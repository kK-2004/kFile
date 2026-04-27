import axios from 'axios'

const apiBase = (typeof import.meta.env.VITE_API_BASE !== 'undefined')
  ? import.meta.env.VITE_API_BASE
  : (import.meta.env.DEV ? '' : '')

const instance = axios.create({
  baseURL: apiBase,
  timeout: 20000,
  withCredentials: true
})

instance.interceptors.response.use(
  (res) => res,
  (error) => {
    try {
      const status = error?.response?.status
      let redirect = error?.response?.headers?.['x-redirect']
      if (status === 401 && redirect) {
        let path = ''
        try { path = new URL(error?.config?.url || '', window.location.origin).pathname } catch { path = String(error?.config?.url || '') }

        const method = String(error?.config?.method || 'get').toLowerCase()
        const isAdminAuth = path.startsWith('/api/admin/auth/')
        const isAdminApi = path.startsWith('/api/admin/')
        const isAdminMe = path === '/api/admin/auth/me'
        const reProjectId = '[^/]+'
        const isSubmissionList = new RegExp(`^/api/projects/${reProjectId}/submissions$`).test(path) && method === 'get'
        const isPublicSubmissions = (
          (method === 'post' && new RegExp(`^/api/projects/${reProjectId}/submissions$`).test(path)) ||
          (method === 'post' && new RegExp(`^/api/projects/${reProjectId}/submissions/(direct-init|direct-complete|direct-multipart-(sign|complete))$`).test(path)) ||
          (method === 'get'  && new RegExp(`^/api/projects/${reProjectId}/submissions/status$`).test(path)) ||
          (method === 'post' && new RegExp(`^/api/projects/${reProjectId}/submissions/validate$`).test(path))
        )
        const isProjectAdminOps = (method === 'get' && new RegExp(`^/api/projects/${reProjectId}/submissions/(export|archive|archive-prepared)$`).test(path))
        const isProjectPublicGet = /^\/api\/projects/.test(path) && method === 'get' && path !== '/api/projects/quota' && !isSubmissionList && !isProjectAdminOps
        if (isPublicSubmissions || isProjectPublicGet) {
          return Promise.reject(error)
        }

        let currentPath = ''
        try { currentPath = new URL(window.location.href).pathname } catch { currentPath = window.location.pathname || '' }
        const onUserOrHome = currentPath === '/' || currentPath.startsWith('/user')
        if (onUserOrHome) {
          return Promise.reject(error)
        }

        if ((isAdminAuth || isAdminApi) && !isAdminMe) {
          if (!/^https?:/i.test(redirect)) {
            let r = redirect
            if (!r.startsWith('/')) r = '/' + r
            redirect = r
          }
          const to = redirect + (redirect.includes('?') ? '&' : '?') + 'redirect=' + encodeURIComponent(window.location.pathname + window.location.search)
          window.location.href = to
          return
        }
      }
    } catch {}
    return Promise.reject(error)
  }
)

export default {
  listProjects() { return instance.get('/api/projects') },
  getProject(id) { return instance.get(`/api/projects/${id}`) },
  adminGetProject(id) { return instance.get(`/api/admin/projects/${id}`) },
  creationQuota() { return instance.get('/api/projects/quota') },
  createProject(data) { return instance.post('/api/projects', data) },
  updateProject(id, data) { return instance.put(`/api/projects/${id}`, data) },

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
    const p = typeof params === 'string' ? { submitter: params } : (params || {})
    return instance.get(`/api/projects/${projectId}/submissions/status`, { params: p, responseType: 'json' })
  },
  validateSubmitter(projectId, submitter) {
    return instance.post(`/api/projects/${projectId}/submissions/validate`, { submitter })
  },
  submit(projectId, submitter, files, config = {}) {
    const totalSize = (files || []).reduce((s, f) => s + (f?.size || 0), 0)
    const threshold = 50 * 1024 * 1024

    if (totalSize > threshold) {
      const filesMeta = Array.from(files || []).map(f => ({ name: f.name, contentType: f.type || 'application/octet-stream', size: f.size || 0 }))
      return this.directInit(projectId, submitter, filesMeta).then(res => {
        const entries = res?.data?.entries || res?.entries || []
        if (!entries.length) throw new Error('直传初始化失败：未返回任何条目')
        return entries.reduce((p, entry, idx) => {
          const file = files[idx]
          return p.then(() => this.directPut(entry.putUrl, file, config.onUploadProgress))
        }, Promise.resolve()).then(() => {
          const keys = entries.map(e => e.key)
          return this.directComplete(projectId, submitter, keys)
        })
      })
    }

    const fd = new FormData()
    fd.append('submitter', JSON.stringify(submitter || {}))
    for (const f of files) fd.append('files', f)
    return instance.post(`/api/projects/${projectId}/submissions`, fd, {
      timeout: 0,
      ...config
    })
  },

  directInit(projectId, submitter, filesMeta) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-init`, {
      submitter,
      files: filesMeta.map(f => ({ name: f.name, contentType: f.type || 'application/octet-stream' }))
    })
  },
  directComplete(projectId, submitter, keys) {
    return instance.post(`/api/projects/${projectId}/submissions/direct-complete`, { submitter, keys })
  },
  directPut(putUrl, file, onUploadProgress) {
    return axios.put(putUrl, file, {
      headers: { 'Content-Type': file.type || 'application/octet-stream' },
      timeout: 0,
      onUploadProgress
    })
  },

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

  adminLogin(username, password) { return instance.post('/api/admin/auth/login', { username, password }) },
  adminMe() { return instance.get('/api/admin/auth/me') }
  ,adminCreateShare(projectId, payload) { return instance.post(`/api/admin/projects/${projectId}/share`, payload) }
  ,getShare(code) { return instance.get(`/api/share/${code}`) }
  ,adminListUsers() { return instance.get('/api/admin/users') }
  ,adminCreateUser(payload) { return instance.post('/api/admin/users', payload) }
  ,adminListUserProjects(userId) { return instance.get(`/api/admin/users/${userId}/projects`) }
  ,adminGrantProject(userId, projectId) { return instance.post(`/api/admin/users/${userId}/projects/${projectId}`) }
  ,adminRevokeProject(userId, projectId) { return instance.delete(`/api/admin/users/${userId}/projects/${projectId}`) }
  ,adminListProjects() { return instance.get('/api/admin/projects') }
  ,adminMissingAllowed(projectId) { return instance.get(`/api/admin/projects/${projectId}/missing-allowed`) }
  ,adminDownloadMissingAllowedCsv(projectId) { return instance.get(`/api/admin/projects/${projectId}/missing-allowed.csv`, { responseType: 'blob' }) }
  ,adminChangePassword(currentPassword, newPassword) { return instance.post('/api/admin/auth/change-password', { currentPassword, newPassword }) }
  ,adminResetPassword(userId) { return instance.post(`/api/admin/users/${userId}/reset-password`) }
  ,adminDeleteUser(userId) { return instance.delete(`/api/admin/users/${userId}`) }
  ,deleteProject(id) { return instance.delete(`/api/projects/${id}`) }
  ,adminStartDeleteProject(id) { return instance.post(`/api/admin/projects/${id}/delete-task`) }
  ,adminGetTask(taskId) { return instance.get(`/api/admin/tasks/${taskId}`) }
  ,adminStartArchiveTask(id, fieldKey, fieldValue) { return instance.post(`/api/admin/projects/${id}/archive-task`, { fieldKey, fieldValue }) }
  ,adminDownloadTask(taskId) { return instance.get(`/api/admin/tasks/${taskId}/download`, { responseType: 'blob', timeout: 0 }) }
  ,adminArchiveManifest(id, fieldKey, fieldValue, expireSeconds) {
    const params = {}
    if (fieldKey) params.fieldKey = fieldKey
    if (fieldValue) params.fieldValue = fieldValue
    if (expireSeconds) params.expireSeconds = expireSeconds
    return instance.get(`/api/admin/projects/${id}/archive-manifest`, { params })
  }
  ,adminGetConfig() { return instance.get('/api/admin/config') }
  ,adminUpdateConfig(payload) { return instance.put('/api/admin/config', payload) }

  ,adminManualUpload(projectId, submitter, files, config = {}) {
    const fd = new FormData()
    fd.append('projectId', projectId)
    fd.append('submitter', typeof submitter === 'string' ? submitter : JSON.stringify(submitter || {}))
    for (const f of files || []) fd.append('files', f)
    return instance.post('/api/admin/submissions/manual-upload', fd, { timeout: 0, ...config })
  }
  ,adminDeleteSubmissionsByField({ fieldKey, fieldValue, projectId }) {
    return instance.post('/api/admin/submissions/delete-by-field', { fieldKey, fieldValue, projectId })
  }
  ,adminPresignedUrl(projectId, fileUrlOrKey, expireSeconds, download = true) {
    const params = { projectId, fileUrlOrKey, download }
    if (expireSeconds) params.expireSeconds = expireSeconds
    return instance.get('/api/admin/submissions/presigned-url', { params })
  }
}
