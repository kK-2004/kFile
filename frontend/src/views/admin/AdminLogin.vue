<template>
  <div :class="['login-wrap', { 'login-dark': isDarkTheme }]">
    <el-card class="login-card">
      <template #header>
        <h2 class="login-title">{{ local ? '管理员登录' : '统一认证登录' }}</h2>
      </template>
      <!-- /admin/login：统一认证网关登录；/admin/local-login：kFile 原有账号密码登录（与网关登录并存） -->
      <div v-if="!local" class="gateway-login">
        <template v-if="store.accountError">
          <el-alert :title="store.accountError.message || '当前账号无法使用 kFile'" type="error" :closable="false" show-icon />
          <el-button size="large" class="login-button" type="primary" @click="switchAccount">切换账号</el-button>
          <el-button size="large" class="login-secondary" text @click="router.replace('/')">返回首页</el-button>
        </template>
        <template v-else>
          <p class="gateway-hint">正在跳转统一认证中心…</p>
          <el-button size="large" class="login-button" type="primary" @click="goGateway">前往登录</el-button>
        </template>
      </div>
      <el-form v-else :model="form" @keyup.enter="onSubmit" label-position="top">
        <el-form-item label="用户名" class="login-form-item">
          <el-input
            v-model="form.username"
            autocomplete="username"
            placeholder="请输入用户名"
            size="large"
          />
        </el-form-item>
        <el-form-item label="密码" class="login-form-item">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            size="large"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="onSubmit"
            size="large"
            class="login-button"
          >
            登录
          </el-button>
        </el-form-item>
        <el-button v-if="gateway" class="login-secondary" text @click="goGateway">使用统一认证登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { ElMessage } from 'element-plus'
import { useThemeStore } from '../../stores/theme'
import { gatewayEnabled, login, logoutGateway } from '../../auth/oidc'

const route = useRoute()
const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const form = ref({ username: '', password: '' })
const loading = ref(false)
const isDarkTheme = computed(() => theme.effectiveDark)
const gateway = gatewayEnabled()
// 未配置网关时 /admin/login 也显示账号密码表单
const local = computed(() => route.path === '/admin/local-login' || !gateway)

const returnTo = () => {
  const raw = String(route.query.redirect || '/admin/projects')
  // 只接受站内路径，防止开放重定向
  return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/admin/projects'
}

const goGateway = () => login(returnTo())

// 账号未开通等：结束网关会话后可换另一个网关账号登录（logoutGateway 内部清本地 token，并带 id_token_hint）
const switchAccount = () => logoutGateway()

onMounted(() => {
  if (!local.value && !store.accountError) goGateway()
})

const onSubmit = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.error('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form.value.username, form.value.password)
    const raw = String(route.query.redirect || '/admin/projects')
    // 完整外链：直接整页跳转，避免 router 仅处理同源路径
    if (raw.startsWith('http://') || raw.startsWith('https://')) {
      window.location.href = raw
      return
    }
    // 相对路径：拆分 path + query（如 /admin/mcp/authorize?client_id=x&state=y），
    // 避免 router.replace 把带 query 的整串当 path 导致参数丢失
    const qIdx = raw.indexOf('?')
    if (qIdx >= 0) {
      const path = raw.substring(0, qIdx)
      const query = {}
      new URLSearchParams(raw.substring(qIdx + 1)).forEach((v, k) => { query[k] = v })
      router.replace({ path, query })
    } else {
      router.replace(raw)
    }
  } catch (e) {
    const msg = e?.response?.data?.message || '登录失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  --login-bg: #f7f9fc;
  --login-card-bg: #ffffff;
  --login-card-border: #e5eaf0;
  --login-text: #1f2937;
  --login-muted: #64748b;
  --login-soft: #94a3b8;
  --login-input-bg: #ffffff;
  --login-input-border: #d9e2ec;
  --login-accent: #2563eb;
  --login-accent-strong: #1d4ed8;
  --login-accent-soft: rgba(37, 99, 235, 0.12);
  --login-shadow: 0 20px 54px rgba(15, 23, 42, 0.09);
  display: flex;
  align-items: center;
  justify-content: center;
  height: calc(100vh - 64px);
  min-height: calc(100vh - 64px);
  padding: 24px;
  box-sizing: border-box;
  overflow: hidden;
  position: relative;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.9), rgba(241, 245, 249, 0.96));
  color: var(--login-text);
}

.login-wrap.login-dark {
  --login-bg: #101615;
  --login-card-bg: #17201e;
  --login-card-border: rgba(151, 168, 164, 0.2);
  --login-text: #f1f7f5;
  --login-muted: #a6b7b3;
  --login-soft: #748681;
  --login-input-bg: #121a19;
  --login-input-border: rgba(151, 168, 164, 0.24);
  --login-accent: #5bd0c8;
  --login-accent-strong: #7dd7ff;
  --login-accent-soft: rgba(91, 208, 200, 0.14);
  --login-shadow: 0 24px 70px rgba(0, 0, 0, 0.42);
  background:
    linear-gradient(180deg, #101615 0%, #121817 52%, #0f1212 100%);
}

.login-card {
  width: 400px;
  border-radius: 16px;
  box-shadow: var(--login-shadow);
  border: 1px solid var(--login-card-border);
  overflow: hidden;
  background: var(--login-card-bg);
  transition: transform 0.3s ease, box-shadow 0.3s ease, border-color 0.3s ease;
  /* 视觉上略微上移，让卡片在视口中偏上居中 */
  margin-top: -40px;
}

.login-card :deep(.el-card__header),
.login-card :deep(.el-card__body) {
  background: transparent;
}

.login-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--login-text);
  text-align: center;
}

:deep(.el-card__header) {
  padding: 24px 24px 8px;
  border-bottom: none;
  background: transparent;
}

:deep(.el-card__body) {
  padding: 0 24px 24px;
}

.login-form-item :deep(.el-form-item__label) {
  font-size: 14px;
  font-weight: 500;
  color: var(--login-muted);
  margin-bottom: 8px;
}

.login-form-item :deep(.el-input__wrapper) {
  border-radius: 10px;
  border: 1px solid var(--login-input-border);
  box-shadow: none;
  background: var(--login-input-bg);
  transition: border-color 0.3s, box-shadow 0.3s, background-color 0.3s;
}

.login-form-item :deep(.el-input__wrapper:hover) {
  border-color: color-mix(in srgb, var(--login-accent) 42%, var(--login-input-border));
}

.login-form-item :deep(.el-input__wrapper:focus-within) {
  border-color: var(--login-accent);
  box-shadow: 0 0 0 3px var(--login-accent-soft);
}

.login-form-item :deep(.el-input__inner) {
  color: var(--login-text);
}

.login-form-item :deep(.el-input__inner::placeholder) {
  color: var(--login-soft);
}

.login-form-item :deep(.el-input__password),
.login-form-item :deep(.el-input__suffix) {
  color: var(--login-muted);
}

.gateway-login {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gateway-hint {
  margin: 0;
  text-align: center;
  color: var(--login-muted);
}

.login-secondary {
  width: 100%;
  margin-left: 0 !important;
}

.login-button {
  width: 100%;
  margin-top: 12px;
  border-radius: 10px;
  font-weight: 500;
  letter-spacing: 0.5px;
  background: linear-gradient(135deg, var(--login-accent), var(--login-accent-strong));
  border: 1px solid transparent;
  height: 48px;
  font-size: 16px;
  box-shadow: 0 12px 24px color-mix(in srgb, var(--login-accent) 24%, transparent);
  transition: transform 180ms ease, box-shadow 180ms ease, filter 180ms ease;
}

.login-button:hover {
  background: linear-gradient(135deg, var(--login-accent), var(--login-accent-strong));
  transform: translateY(-1px);
  filter: saturate(1.05);
  box-shadow: 0 16px 30px color-mix(in srgb, var(--login-accent) 30%, transparent);
}

.login-dark .gateway-login {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gateway-hint {
  margin: 0;
  text-align: center;
  color: var(--login-muted);
}

.login-secondary {
  width: 100%;
  margin-left: 0 !important;
}

.login-button {
  color: #061817;
}

.login-dark .login-card {
  background: linear-gradient(180deg, rgba(28, 39, 37, 0.96), rgba(21, 30, 29, 0.98));
}

.login-dark .login-form-item :deep(.el-input__wrapper) {
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.03);
}

.login-dark .login-form-item :deep(.el-input__inner) {
  caret-color: var(--login-accent);
}

@media (max-width: 480px) {
  .login-wrap {
    padding: 18px;
  }

  .login-card {
    width: 100%;
    border-radius: 12px;
    margin-top: -24px;
  }

  .login-title {
    font-size: 22px;
  }

  :deep(.el-card__body) {
    padding: 0 16px 20px;
  }
}
</style>
