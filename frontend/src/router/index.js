import { createRouter, createWebHistory } from 'vue-router'
import AdminProjects from '../views/admin/AdminProjects.vue'
import AdminProjectForm from '../views/admin/AdminProjectForm.vue'
import AdminSubmissions from '../views/admin/AdminSubmissions.vue'
import AdminLogin from '../views/admin/AdminLogin.vue'
import AdminUsers from '../views/admin/AdminUsers.vue'
import UserProjects from '../views/user/UserProjects.vue'
import UserSubmit from '../views/user/UserSubmit.vue'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', redirect: '/user/projects' },
  { path: '/admin', redirect: '/admin/projects' },
  { path: '/user/projects', component: UserProjects },
  { path: '/user/projects/:id', component: UserSubmit, props: true },

  { path: '/admin/projects', component: AdminProjects },
  { path: '/admin/projects/new', component: AdminProjectForm },
  { path: '/admin/projects/:id/edit', component: AdminProjectForm, props: true },
  { path: '/admin/projects/:id/submissions', component: AdminSubmissions, props: true },
  { path: '/admin/login', component: AdminLogin }
  ,{ path: '/admin/users', component: AdminUsers }
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

  if (!store.loaded) await store.loadMe()

  // 已登录且访问 /admin/login，则直接跳转到目标页
  if (to.path === '/admin/login' && store.user) {
    const params = new URLSearchParams(window.location.search)
    const redirect = params.get('redirect') || '/admin/projects'
    return { path: redirect }
  }

  // 保护 /admin/** 除登录页
  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    if (store.user) return true
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  return true
})
