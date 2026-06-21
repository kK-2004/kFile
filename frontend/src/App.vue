<template>
  <el-config-provider namespace="el">
    <!-- 顶部公告条（用户提交页显示），采用 Tailwind 组件写法 -->
<!--    <div-->
<!--      v-if="isHome && showAnnouncement"-->
<!--      class="relative isolate flex items-center gap-x-6 overflow-hidden bg-gray-50 px-6 py-0 sm:px-3.5 sm:before:flex-1 dark:bg-gray-800/50 dark:after:pointer-events-none dark:after:absolute dark:after:inset-x-0 dark:after:bottom-0 dark:after:h-px dark:after:bg-white/10"-->
<!--    >-->
<!--      <div class="absolute left-[max(-7rem,calc(50%-52rem))] top-1/2 -z-10 -translate-y-1/2 transform-gpu blur-2xl" aria-hidden="true">-->
<!--        <div class="aspect-[577/310] w-[36.0625rem] bg-gradient-to-r from-[#ff80b5] to-[#9089fc] opacity-30 dark:opacity-40" style="clip-path: polygon(74.8% 41.9%, 97.2% 73.2%, 100% 34.9%, 92.5% 0.4%, 87.5% 0%, 75% 28.6%, 58.5% 54.6%, 50.1% 56.8%, 46.9% 44%, 48.3% 17.4%, 24.7% 53.9%, 0% 27.9%, 11.9% 74.2%, 24.9% 54.1%, 68.6% 100%, 74.8% 41.9%)" />-->
<!--      </div>-->
<!--      <div class="absolute left-[max(45rem,calc(50%+8rem))] top-1/2 -z-10 -translate-y-1/2 transform-gpu blur-2xl" aria-hidden="true">-->
<!--        <div class="aspect-[577/310] w-[36.0625rem] bg-gradient-to-r from-[#ff80b5] to-[#9089fc] opacity-30 dark:opacity-40" style="clip-path: polygon(74.8% 41.9%, 97.2% 73.2%, 100% 34.9%, 92.5% 0.4%, 87.5% 0%, 75% 28.6%, 58.5% 54.6%, 50.1% 56.8%, 46.9% 44%, 48.3% 17.4%, 24.7% 53.9%, 0% 27.9%, 11.9% 74.2%, 24.9% 54.1%, 68.6% 100%, 74.8% 41.9%)" />-->
<!--      </div>-->
<!--      <div class="flex flex-wrap items-center gap-x-4 gap-y-1.5">-->
<!--        <p class="text-base text-gray-900 dark:text-gray-100">-->
<!--          <strong class="font-semibold">k-Site 2025</strong>-->
<!--          <svg viewBox="0 0 2 2" class="mx-2 inline size-0.5 fill-current" aria-hidden="true"><circle cx="1" cy="1" r="1" /></svg>-->
<!--          Join the beta now!-->
<!--        </p>-->
<!--        <a href="https://ksite.xin" target="_blank" rel="noopener noreferrer" class="flex-none rounded-full bg-gray-900 px-3.5 py-1 text-sm font-semibold text-white shadow-sm hover:bg-gray-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900 dark:bg-white/10 dark:ring-white/20 dark:hover:bg-white/15 dark:focus-visible:outline-white">-->
<!--          Have a Look <span aria-hidden="true">&rarr;</span>-->
<!--        </a>-->
<!--      </div>-->
<!--      <div class="flex flex-1 justify-end">-->
<!--        <button-->
<!--          type="button"-->
<!--          class="group -m-2 p-2 rounded-full appearance-none bg-transparent border-none transition-colors duration-200 hover:bg-gray-200/80 dark:hover:bg-white/20 ring-1 ring-transparent hover:ring-gray-300 dark:hover:ring-white/30 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900"-->
<!--          @click="showAnnouncement = false"-->
<!--        >-->
<!--          <span class="sr-only">Dismiss</span>-->
<!--          <svg class="size-5 text-gray-900 dark:text-gray-100 transition-colors duration-200 group-hover:text-gray-700 dark:group-hover:text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">-->
<!--            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />-->
<!--          </svg>-->
<!--        </button>-->
<!--      </div>-->
<!--    </div>-->

    <NewYearElements v-if="showNewYearElements" />

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
            <!-- 桌面端：完整导航 -->
            <el-space :size="8" class="nav-space" v-show="!isMobile">
              <template v-if="auth && auth.user">
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
              </template>

              <template v-if="isAdmin && auth && auth.user">
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
                    @click="$router.push('/admin/templates')"
                >
                  模板管理
                </el-button>
                <el-button
                    class="nav-btn"
                    text
                    v-if="isSuper"
                    @click="$router.push('/admin/users')"
                >
                  管理员与权限
                </el-button>
                <el-button
                    class="nav-btn"
                    text
                    v-if="isSuper"
                    @click="$router.push('/admin/settings')"
                >
                  系统设置
                </el-button>
                <el-button
                    class="nav-btn"
                    text
                    v-if="isAdmin"
                    @click="$router.push('/admin/files')"
                >
                  文件管理
                </el-button>
                <el-button
                    class="nav-btn"
                    text
                    v-if="isAdmin"
                    @click="$router.push('/admin/shares')"
                >
                  分享管理
                </el-button>

                <el-divider direction="vertical" class="nav-divider" />
              </template>

              <el-button class="theme-btn" text @click="cycleTheme" :title="themeTooltip">
                <el-icon size="16"><component :is="themeIconComp" /></el-icon>
              </el-button>

              <!-- 移动端汉堡菜单 -->
              <el-button v-show="isMobile" class="theme-btn" text @click="drawerVisible = true">
                <el-icon size="18"><Menu /></el-icon>
              </el-button>

              <template v-if="isAdmin && auth && auth.user">
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

      <el-main class="app-main" style="padding: 0">
        <router-view />
      </el-main>
    </el-container>

    <!-- 移动端导航抽屉 -->
    <el-drawer v-model="drawerVisible" direction="ltr" size="70%" :with-header="false">
      <div class="drawer-nav">
        <div class="drawer-section">
          <el-button text class="drawer-btn" @click="navigate('/user/projects'); drawerVisible=false">用户端</el-button>
          <el-button text class="drawer-btn" @click="navigate('/admin'); drawerVisible=false">管理端</el-button>
        </div>
        <template v-if="isAdmin && auth && auth.user">
          <el-divider />
          <div class="drawer-section">
            <el-button text class="drawer-btn" @click="navigate('/admin/projects'); drawerVisible=false">项目</el-button>
            <el-button v-if="isSuper" text class="drawer-btn" @click="navigate('/admin/templates'); drawerVisible=false">模板管理</el-button>
            <el-button v-if="isSuper" text class="drawer-btn" @click="navigate('/admin/users'); drawerVisible=false">管理员与权限</el-button>
            <el-button v-if="isSuper" text class="drawer-btn" @click="navigate('/admin/settings'); drawerVisible=false">系统设置</el-button>
            <el-button text class="drawer-btn" @click="navigate('/admin/files'); drawerVisible=false">文件管理</el-button>
            <el-button text class="drawer-btn" @click="navigate('/admin/shares'); drawerVisible=false">分享管理</el-button>
          </div>
          <el-divider />
          <div class="drawer-section">
            <el-button text class="drawer-btn" @click="cycleTheme">主题：{{ themeModeLabel }}</el-button>
            <el-button text class="drawer-btn" @click="openChangePwd; drawerVisible=false">修改密码</el-button>
            <el-button text class="drawer-btn logout" @click="logout; drawerVisible=false">退出</el-button>
          </div>
          <div class="drawer-user">
            <span class="username">{{ auth.user.username }}</span>
            <span class="user-role">{{ (auth.user.role||'').toUpperCase() }}</span>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px" class="pwd-dialog">
      <el-form :model="pwdForm" label-width="100px">
        <el-form-item label="当前密码">
          <el-input
            v-model="pwdForm.currentPassword"
            type="password"
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码" :error="newPwdError">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            autocomplete="new-password"
            @input="onNewPwdInput"
          >
            <template #suffix>
              <el-icon v-if="newPwdStatus === 'ok'" style="color:#67c23a"><CircleCheckFilled /></el-icon>
              <el-icon v-else-if="newPwdStatus === 'fail'" style="color:#f56c6c"><CircleCloseFilled /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="确认新密码" :error="confirmError">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            autocomplete="new-password"
            @input="onConfirmInput"
          >
            <template #suffix>
              <el-icon v-if="confirmStatus === 'ok'" style="color:#67c23a"><CircleCheckFilled /></el-icon>
              <el-icon v-else-if="confirmStatus === 'fail'" style="color:#f56c6c"><CircleCloseFilled /></el-icon>
            </template>
          </el-input>
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
import { useThemeStore } from './stores/theme'
import api from './api'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, CircleCloseFilled, Menu } from '@element-plus/icons-vue'
import { Sunny, Moon, Monitor } from '@element-plus/icons-vue'
import NewYearElements from './components/NewYearElements.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isAdmin = computed(() => route.path.startsWith('/admin'))
const isAdminLogin = computed(() => route.path === '/admin/login')
const isUserSubmit = computed(() => route.path.startsWith('/user/projects/') && route.params.id)
const isHome = computed(() => isUserSubmit.value)
const showAnnouncement = ref(true)
const isSuper = computed(() => auth.user && (auth.user.role||'').toUpperCase()==='SUPER')

// 主题切换
const theme = useThemeStore()
const themeIconComp = computed(() => {
  if (theme.mode === 'light') return Sunny
  if (theme.mode === 'dark') return Moon
  return Monitor
})
const themeTooltip = computed(() => {
  if (theme.mode === 'light') return '亮色模式（点击切换）'
  if (theme.mode === 'dark') return '暗色模式（点击切换）'
  return '跟随系统（点击切换）'
})
const cycleTheme = () => theme.cycleMode()
const themeModeLabel = computed(() => {
  if (theme.mode === 'light') return '亮色'
  if (theme.mode === 'dark') return '暗色'
  return '跟随系统'
})

// 移动端导航抽屉
const isMobile = ref(false)
const drawerVisible = ref(false)
const updateMobile = () => { isMobile.value = window.matchMedia('(max-width: 768px)').matches }
if (typeof window !== 'undefined') {
  updateMobile()
  window.matchMedia('(max-width: 768px)').addEventListener('change', updateMobile)
}
const navigate = (path) => { router.push(path) }
const showNewYearElements = computed(() => {
  const now = new Date()

  const startYear = now.getMonth() === 0 ? now.getFullYear() - 1 : now.getFullYear()

  const start = new Date(startYear, 11, 31, 0, 0, 0)      // 12/31 00:00:00
  const end   = new Date(startYear + 1, 0, 3, 23, 59, 59) // 次年 1/3 23:59:59

  return now >= start && now <= end
})

onMounted(() => {
  if (isAdmin.value && !auth.loaded) auth.loadMe()
})

const pwdVisible = ref(false)
const pwdForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const newPwdStatus = ref('')
const newPwdError = ref('')
const confirmStatus = ref('')
const confirmError = ref('')
let newPwdTimer = null
let confirmTimer = null

const onNewPwdInput = () => {
  newPwdStatus.value = ''
  newPwdError.value = ''
  clearTimeout(newPwdTimer)
  const val = pwdForm.value.newPassword
  if (!val) { onConfirmInput(); return }
  newPwdTimer = setTimeout(() => {
    if (val.length < 6) {
      newPwdStatus.value = 'fail'
      newPwdError.value = '密码长度至少6位'
    } else {
      newPwdStatus.value = 'ok'
      newPwdError.value = ''
    }
    onConfirmInput()
  }, 400)
}

const onConfirmInput = () => {
  confirmStatus.value = ''
  confirmError.value = ''
  clearTimeout(confirmTimer)
  const { newPassword, confirmPassword } = pwdForm.value
  if (!confirmPassword) return
  confirmTimer = setTimeout(() => {
    if (newPassword !== confirmPassword) {
      confirmStatus.value = 'fail'
      confirmError.value = '两次输入的密码不一致'
    } else {
      confirmStatus.value = 'ok'
      confirmError.value = ''
    }
  }, 400)
}

const openChangePwd = () => {
  pwdForm.value = { currentPassword:'', newPassword:'', confirmPassword:'' }
  newPwdStatus.value = ''; newPwdError.value = ''
  confirmStatus.value = ''; confirmError.value = ''
  pwdVisible.value = true
}
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

<style>
/* 全局重置，消除浏览器默认边距，避免四周留白 */
html, body, #app { margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; }
</style>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Montserrat:wght@700;800&display=swap');

/* 全局字体放到非 scoped 样式会更稳，这里仅保留局部组件样式 */

/* Header 样式 */
.app-header {
  background: var(--kf-header-bg);
  box-shadow: var(--kf-shadow);
  padding: 0 32px;
  height: 64px !important;
  display: flex;
  align-items: center;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 1000;
  border-bottom: 1px solid var(--kf-border-light);
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
  color: var(--kf-text);
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
  color: var(--kf-muted) !important;
  font-weight: 500;
  padding: 8px 16px;
  border-radius: 8px;
  transition: all 0.3s ease;
}

:deep(.nav-btn:hover) {
  background: var(--kf-hover-bg) !important;
  color: var(--kf-danger) !important;
  transform: translateY(-1px);
}

/* 分隔线 */
:deep(.nav-divider) {
  height: 20px;
  margin: 0 8px;
  border-color: var(--kf-border);
}

/* 主题切换按钮 */
.theme-btn {
  padding: 4px 8px !important;
  color: var(--kf-muted) !important;
}
.theme-btn:hover {
  background: var(--kf-hover-bg) !important;
}

/* 用户信息 */
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: var(--kf-user-info-bg);
  border-radius: 8px;
  border: 1px solid var(--kf-border);
}

.username {
  color: var(--kf-text);
  font-weight: 500;
  font-size: 14px;
}

.user-role {
  color: var(--kf-danger);
  font-size: 12px;
  padding: 2px 8px;
  background: var(--kf-hover-bg);
  border-radius: 4px;
  font-weight: 500;
}

/* 操作按钮 */
:deep(.action-btn) {
  background: var(--kf-bg) !important;
  border: 1px solid var(--kf-border) !important;
  color: var(--kf-muted) !important;
  font-weight: 500;
  border-radius: 6px;
  padding: 6px 16px;
  transition: all 0.3s ease;
}

:deep(.action-btn:hover) {
  background: var(--kf-hover-bg) !important;
  border-color: var(--kf-danger-hover) !important;
  color: var(--kf-danger) !important;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.15);
}

:deep(.logout-btn:hover) {
  background: var(--kf-danger) !important;
  border-color: var(--kf-danger) !important;
  color: #ffffff !important;
}

/* 主内容区域 */
.app-main {
  background: var(--kf-bg-dim);
  padding: 0;
  margin-top: 64px;
}
/* 强制覆盖 Element Plus 默认主区域内边距 */
:deep(.el-main.app-main) { padding: 0 !important; }

/* 移动端导航抽屉 */
.drawer-nav { display: flex; flex-direction: column; gap: 4px; padding: 16px 12px; }
.drawer-section { display: flex; flex-direction: column; gap: 2px; }
.drawer-btn { justify-content: flex-start !important; font-size: 15px !important; padding: 12px 16px !important; color: var(--kf-text) !important; }
.drawer-btn:hover { background: var(--kf-hover-bg) !important; }
.drawer-btn.logout { color: var(--kf-danger) !important; }
.drawer-user { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding: 12px; }

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
  background: var(--kf-bg);
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
