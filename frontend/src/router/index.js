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
  history: createWebHistory(),
  routes
})

export default router

// Route guard: require auth for /admin except /admin/login
router.beforeEach(async (to) => {
  if (!to.path.startsWith('/admin') || to.path === '/admin/login') return true
  const store = useAuthStore()
  if (!store.loaded) await store.loadMe()
  if (store.user) return true
  return { path: '/admin/login', query: { redirect: to.fullPath } }
})
