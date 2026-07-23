import { createRouter, createWebHistory } from 'vue-router'
import AdminProjects from '../views/admin/AdminProjects.vue'
import AdminProjectForm from '../views/admin/AdminProjectForm.vue'
import AdminSubmissions from '../views/admin/AdminSubmissions.vue'
import AdminLogin from '../views/admin/AdminLogin.vue'
import AdminUsers from '../views/admin/AdminUsers.vue'
import AdminSettings from '../views/admin/AdminSettings.vue'
import AdminMcpAuthorize from '../views/admin/AdminMcpAuthorize.vue'
import AdminTemplates from '../views/admin/AdminTemplates.vue'
import AdminFiles from '../views/admin/AdminFiles.vue'
import AdminShares from '../views/admin/AdminShares.vue'
import UserProjects from '../views/user/UserProjects.vue'
import Hero from '../views/Hero.vue'
import UserSubmit from '../views/user/UserSubmit.vue'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', component: Hero },
  { path: '/share', component: () => import('../views/ShareDownload.vue') },
  { path: '/admin', redirect: '/admin/projects' },
  { path: '/user/projects', component: UserProjects },
  { path: '/user/projects/:id', component: UserSubmit, props: true },

  { path: '/admin/projects', component: AdminProjects },
  { path: '/admin/projects/new', component: AdminProjectForm },
  { path: '/admin/projects/:id/edit', component: AdminProjectForm, props: true },
  { path: '/admin/projects/:id/submissions', component: AdminSubmissions, props: true },
  { path: '/admin/login', component: AdminLogin }
  ,{ path: '/admin/users', component: AdminUsers }
  ,{ path: '/admin/settings', component: AdminSettings }
  ,{ path: '/admin/mcp/authorize', component: AdminMcpAuthorize }
  ,{ path: '/admin/templates', component: AdminTemplates }
  ,{ path: '/admin/files', component: AdminFiles }
  ,{ path: '/admin/shares', component: AdminShares }
]

const router = createRouter({
  history: createWebHistory('/'),
  routes
})

export default router

router.beforeEach(async (to) => {
  const store = useAuthStore()

  if (to.path === '/share') return true

  if (to.path !== '/admin/login') {
    try { if (!store.loaded) await store.loadMe() } catch {}
  }

  if (to.path === '/admin/login' && store.user) {
    // 已登录访问登录页：跳到 redirect 或默认页
    const raw = to.query.redirect || ''
    if (raw) return buildRedirectTarget(raw)
    return { path: '/admin/projects' }
  }

  // MCP 授权页：由页面自行处理登录检测与跳转，路由守卫不拦截
  // （页面会在未登录时主动跳到 /admin/login?redirect=<完整授权参数>）
  if (to.path === '/admin/mcp/authorize') {
    return true
  }

  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    if (store.user) return true
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  return true
})

/**
 * 把 redirect 字符串（可能是相对路径含 query，如 /admin/mcp/authorize?client_id=x&state=y）
 * 解析为 vue-router 的导航目标，避免 router.replace 把整串当 path 导致 query 丢失。
 */
function buildRedirectTarget(raw) {
  try {
    // 相对路径含 query：手工拆分 path + query
    const qIdx = raw.indexOf('?')
    if (qIdx >= 0) {
      const path = raw.substring(0, qIdx)
      const search = raw.substring(qIdx + 1)
      const query = {}
      new URLSearchParams(search).forEach((v, k) => { query[k] = v })
      return { path, query }
    }
    return { path: raw }
  } catch {
    return { path: raw }
  }
}
