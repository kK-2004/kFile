import { createRouter, createWebHistory } from 'vue-router'
import AdminProjects from '../views/admin/AdminProjects.vue'
import AdminProjectForm from '../views/admin/AdminProjectForm.vue'
import AdminSubmissions from '../views/admin/AdminSubmissions.vue'
import AdminLogin from '../views/admin/AdminLogin.vue'
import AdminUsers from '../views/admin/AdminUsers.vue'
import AdminSettings from '../views/admin/AdminSettings.vue'
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
]

const router = createRouter({
  history: createWebHistory('/kfile/'),
  routes
})

export default router

// Route guard: 捕获 accessToken 并保护 /admin 路由
router.beforeEach(async (to) => {
  const store = useAuthStore()
  // 无论进入哪个页面，都先尝试捕获一次 accessToken
  try {
    const url = new URL(window.location.href)
    const token = url.searchParams.get('accessToken')
    if (token) {
      store.setToken(token)
      url.searchParams.delete('accessToken')
      window.history.replaceState({}, '', url.pathname + url.search)
    }
  } catch {}

  // /share 页面不需要登录，跳过会话探测
  if (to.path === '/share') return true

  // 在非登录页正常探测会话；在登录页如果已携带站点 token 也应探测以便自动跳转
  if (to.path !== '/admin/login') {
    try { if (!store.loaded) await store.loadMe() } catch {}
  } else {
    const hasSiteToken = store.token || localStorage.getItem('KSITE_ACCESS_TOKEN') || localStorage.getItem('accessToken')
    if (!store.loaded && hasSiteToken) {
      try { await store.loadMe() } catch {}
    }
  }

  // 不再在首页自动将主站用户导向 /user/projects（该页为管理员调试页面）

  // 已登录且访问 /admin/login：
  // - 本地管理员：跳回目标或 /admin/projects
  // - 主站管理员（role 包含 ADMIN/SUPER）：直接进 /admin/projects
  // - 主站普通用户：同样直接进入 /admin/projects
  if (to.path === '/admin/login' && store.user) {
    const params = new URLSearchParams(window.location.search)
    const raw = params.get('redirect') || ''
    const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
    const normalize = (input) => {
      let t = input || ''
      try { if (/^https?:/i.test(t)) { const u = new URL(t); t = u.pathname + u.search + u.hash } } catch {}
      if (base && t.startsWith(base + '/')) t = t.slice(base.length)
      if (!t) t = '/'
      return t
    }
    const isAdminPath = (p) => p.startsWith('/admin')
    const role = String(store.user.role || '').toUpperCase()
    const isSiteAdmin = store.user.mode === 'site' && (role === 'ADMIN' || role === 'SUPER')
    if (store.user.mode === 'local') {
      const t = normalize(raw || '/admin/projects')
      return { path: t }
    } else if (store.user.mode === 'site') {
      // 无论是否管理员，统一进入项目管理页
      return { path: '/admin/projects' }
    } else {
      // 站点普通用户：留在登录页
      return true
    }
  }

  // 保护 /admin/** 除登录页：
  // - 本地管理员：放行
  // - 主站管理员（ADMIN/SUPER）：放行（通过 Bearer 访问）
  // - 主站普通用户：仅放行项目管理相关页面（/admin/projects...）
  // - 未登录：跳到管理员登录页
  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    if (store.user && store.user.mode === 'local') return true
    if (store.user && store.user.mode === 'site') {
      const role = String(store.user.role || '').toUpperCase()
      if (role === 'ADMIN' || role === 'SUPER') return true
      // 普通站点用户：仅允许进入项目管理相关页面
      const allowSiteUser = (
        to.path === '/admin/projects' ||
        to.path.startsWith('/admin/projects/')
      )
      if (allowSiteUser) return true
      return { path: '/admin/login', query: { redirect: to.fullPath } }
    }
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  return true
})
