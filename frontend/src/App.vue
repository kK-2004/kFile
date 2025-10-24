<template>
  <el-config-provider namespace="el">
    <!-- 顶部公告条（用户提交页显示），采用 Tailwind 组件写法 -->
    <div
      v-if="isHome && showAnnouncement"
      class="relative isolate flex items-center gap-x-6 overflow-hidden bg-gray-50 px-6 py-0 sm:px-3.5 sm:before:flex-1 dark:bg-gray-800/50 dark:after:pointer-events-none dark:after:absolute dark:after:inset-x-0 dark:after:bottom-0 dark:after:h-px dark:after:bg-white/10"
    >
      <div class="absolute left-[max(-7rem,calc(50%-52rem))] top-1/2 -z-10 -translate-y-1/2 transform-gpu blur-2xl" aria-hidden="true">
        <div class="aspect-[577/310] w-[36.0625rem] bg-gradient-to-r from-[#ff80b5] to-[#9089fc] opacity-30 dark:opacity-40" style="clip-path: polygon(74.8% 41.9%, 97.2% 73.2%, 100% 34.9%, 92.5% 0.4%, 87.5% 0%, 75% 28.6%, 58.5% 54.6%, 50.1% 56.8%, 46.9% 44%, 48.3% 17.4%, 24.7% 53.9%, 0% 27.9%, 11.9% 74.2%, 24.9% 54.1%, 68.6% 100%, 74.8% 41.9%)" />
      </div>
      <div class="absolute left-[max(45rem,calc(50%+8rem))] top-1/2 -z-10 -translate-y-1/2 transform-gpu blur-2xl" aria-hidden="true">
        <div class="aspect-[577/310] w-[36.0625rem] bg-gradient-to-r from-[#ff80b5] to-[#9089fc] opacity-30 dark:opacity-40" style="clip-path: polygon(74.8% 41.9%, 97.2% 73.2%, 100% 34.9%, 92.5% 0.4%, 87.5% 0%, 75% 28.6%, 58.5% 54.6%, 50.1% 56.8%, 46.9% 44%, 48.3% 17.4%, 24.7% 53.9%, 0% 27.9%, 11.9% 74.2%, 24.9% 54.1%, 68.6% 100%, 74.8% 41.9%)" />
      </div>
      <div class="flex flex-wrap items-center gap-x-4 gap-y-1.5">
        <p class="text-base text-gray-900 dark:text-gray-100">
          <strong class="font-semibold">k-Site 2025</strong>
          <svg viewBox="0 0 2 2" class="mx-2 inline size-0.5 fill-current" aria-hidden="true"><circle cx="1" cy="1" r="1" /></svg>
          Join the beta now!
        </p>
        <a href="https://ksite.xin" target="_blank" rel="noopener noreferrer" class="flex-none rounded-full bg-gray-900 px-3.5 py-1 text-sm font-semibold text-white shadow-sm hover:bg-gray-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900 dark:bg-white/10 dark:ring-white/20 dark:hover:bg-white/15 dark:focus-visible:outline-white">
          Have a Look <span aria-hidden="true">&rarr;</span>
        </a>
      </div>
      <div class="flex flex-1 justify-end">
        <button
          type="button"
          class="group -m-2 p-2 rounded-full appearance-none bg-transparent border-none transition-colors duration-200 hover:bg-gray-200/80 dark:hover:bg-white/20 ring-1 ring-transparent hover:ring-gray-300 dark:hover:ring-white/30 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900"
          @click="showAnnouncement = false"
        >
          <span class="sr-only">Dismiss</span>
          <svg class="size-5 text-gray-900 dark:text-gray-100 transition-colors duration-200 group-hover:text-gray-700 dark:group-hover:text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
    </div>

    <el-container style="min-height: 100vh;">
      <el-header class="app-header">
        <div class="header-content">
          <div class="header-left">
            <div class="logo-wrapper">
              <a href="https://ksite.xin/" target="_blank">
                <img src="./assets/static/img/logo.png" alt="K Logo" class="logo-icon" />
              </a>
              <h1 class="app-title">-File</h1>
            </div>
          </div>

          <div class="header-right">
            <el-space :size="8" class="nav-space" v-if="auth && auth.user">
              <el-button
                  v-if="!isUserSubmit && !(isAdminLogin && !auth.user)"
                  class="nav-btn"
                  text
                  @click="$router.push('/user/projects')"
              >
                用户端
              </el-button>
              <el-button
                  v-if="!isUserSubmit"
                  class="nav-btn"
                  text
                  @click="$router.push('/admin')"
              >
                管理端
              </el-button>

              <template v-if="isAdmin">
                <el-divider direction="vertical" class="nav-divider" />
                <el-button
                    class="nav-btn"
                    text
                    @click="$router.push('/admin/projects')"
                >
                  项目
                </el-button>
                <el-button
                    class="nav-btn"
                    text
                    v-if="isSuper"
                    @click="$router.push('/admin/users')"
                >
                  管理员与权限
                </el-button>

                <el-divider direction="vertical" class="nav-divider" />

                <div class="user-info">
                  <span class="username">{{ auth.user.username }}</span>
                  <span class="user-role">{{ (auth.user.role||'').toUpperCase() }}</span>
                </div>

                <el-button
                    class="action-btn"
                    size="small"
                    @click="openChangePwd"
                >
                  修改密码
                </el-button>
                <el-button
                    class="action-btn logout-btn"
                    size="small"
                    @click="logout"
                >
                  退出
                </el-button>
              </template>
            </el-space>
          </div>
        </div>
      </el-header>

      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px" class="pwd-dialog">
      <el-form :model="pwdForm" label-width="100px">
        <el-form-item label="当前密码">
          <el-input
            v-model="pwdForm.currentPassword"
            type="password"
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            autocomplete="new-password"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible=false">取消</el-button>
        <el-button type="primary" @click="changePwd">提交</el-button>
      </template>
    </el-dialog>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import api from './api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isAdmin = computed(() => route.path.startsWith('/admin'))
const isAdminLogin = computed(() => route.path === '/admin/login')
const isUserSubmit = computed(() => route.path.startsWith('/user/projects/') && route.params.id)
const isHome = computed(() => isUserSubmit.value)
const showAnnouncement = ref(true)
const isSuper = computed(() => auth.user && (auth.user.role||'').toUpperCase()==='SUPER')

onMounted(() => { if (isAdmin.value && !auth.loaded) auth.loadMe() })

const pwdVisible = ref(false)
const pwdForm = ref({ currentPassword: '', newPassword: '' })
const openChangePwd = () => { pwdForm.value = { currentPassword:'', newPassword:'' }; pwdVisible.value = true }
const changePwd = async () => {
  try {
    await api.adminChangePassword(pwdForm.value.currentPassword, pwdForm.value.newPassword)
    ElMessage.success('修改成功，请牢记新密码')
    pwdVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.message || '修改失败'
    ElMessage.error(msg)
  }
}

const logout = async () => { await auth.logout(); router.push('/admin/login') }
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Montserrat:wght@700;800&display=swap');

body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

/* Header 样式 */
.app-header {
  background: #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  padding: 0 32px;
  height: 64px !important;
  display: flex;
  align-items: center;
  position: sticky;
  top: 0;
  z-index: 1000;
  border-bottom: 1px solid #f0f0f0;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

/* Logo 区域 */
.header-left {
  display: flex;
  align-items: center;
}

.logo-wrapper {
  display: flex;
  align-items: center;
  gap: 4px;
}

.logo-icon {
  height: 32px;
  width: auto;
  object-fit: contain;
  transition: all 0.3s ease;
  margin-top: 1.61px;
}

.logo-icon:hover {
  transform: translateY(-2px);
  filter: drop-shadow(0 4px 8px rgba(239, 68, 68, 0.25));
}

.app-title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  font-family: 'Montserrat', -apple-system, BlinkMacSystemFont, sans-serif;
  color: #1f2937;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  position: relative;
  display: inline-block;
  padding-bottom: 2px;
}

.app-title::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  width: 40px;
  height: 3px;
  background: #ef4444;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.logo-wrapper:hover .app-title::after {
  width: 100%;
}

/* 右侧导航 */
.header-right {
  display: flex;
  align-items: center;
}

.nav-space {
  display: flex;
  align-items: center;
}

/* 导航按钮 - 确保文字颜色清晰 */
:deep(.nav-btn) {
  color: #4b5563 !important;
  font-weight: 500;
  padding: 8px 16px;
  border-radius: 8px;
  transition: all 0.3s ease;
}

:deep(.nav-btn:hover) {
  background: #fef2f2 !important;
  color: #ef4444 !important;
  transform: translateY(-1px);
}

/* 分隔线 */
:deep(.nav-divider) {
  height: 20px;
  margin: 0 8px;
  border-color: #e5e7eb;
}

/* 用户信息 */
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f9fafb;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.username {
  color: #1f2937;
  font-weight: 500;
  font-size: 14px;
}

.user-role {
  color: #ef4444;
  font-size: 12px;
  padding: 2px 8px;
  background: #fef2f2;
  border-radius: 4px;
  font-weight: 500;
}

/* 操作按钮 - 确保颜色清晰可见 */
:deep(.action-btn) {
  background: #ffffff !important;
  border: 1px solid #e5e7eb !important;
  color: #4b5563 !important;
  font-weight: 500;
  border-radius: 6px;
  padding: 6px 16px;
  transition: all 0.3s ease;
}

:deep(.action-btn:hover) {
  background: #fef2f2 !important;
  border-color: #fecaca !important;
  color: #ef4444 !important;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.15);
}

:deep(.logout-btn:hover) {
  background: #ef4444 !important;
  border-color: #ef4444 !important;
  color: #ffffff !important;
}

/* 主内容区域 */
.app-main {
  background: #f5f7fa;
  padding: 24px;
}

/* 对话框美化 */
:deep(.pwd-dialog .el-dialog) {
  border-radius: 12px;
  overflow: hidden;
}

:deep(.pwd-dialog .el-dialog__header) {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  color: #ffffff;
  padding: 20px;
  margin: 0;
}

:deep(.pwd-dialog .el-dialog__title) {
  color: #ffffff;
  font-weight: 600;
}

:deep(.pwd-dialog .el-dialog__body) {
  padding: 20px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .app-header {
    padding: 0 16px;
  }

  .app-title {
    font-size: 16px;
  }

  .logo-icon {
    width: 36px;
    height: 36px;
    font-size: 18px;
  }

  .user-info {
    display: none;
  }

  :deep(.nav-btn) {
    padding: 6px 12px;
    font-size: 13px;
  }
}
</style>
