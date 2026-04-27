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
    const params = new URLSearchParams(window.location.search)
    const raw = params.get('redirect') || ''
    const t = raw || '/admin/projects'
    return { path: t }
  }

  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    if (store.user) return true
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  return true
})
