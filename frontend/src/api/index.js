import axios from 'axios'

const apiBase = import.meta.env.VITE_API_BASE || '' // use vite proxy if empty

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
      const redirect = error?.response?.headers?.['x-redirect']
      if (status === 401 && redirect) {
        const to = redirect + '?redirect=' + encodeURIComponent(window.location.pathname + window.location.search)
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
  submit(projectId, submitter, files) {
    const fd = new FormData()
    fd.append('submitter', JSON.stringify(submitter || {}))
    for (const f of files) fd.append('files', f)
    return instance.post(`/api/projects/${projectId}/submissions`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
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
