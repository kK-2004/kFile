<template>
  <div :class="['login-wrap', { 'login-dark': isDarkTheme }]">
    <el-card class="login-card">
      <template #header>
        <h2 class="login-title">管理员登录</h2>
      </template>
      <el-form :model="form" @keyup.enter="onSubmit" label-position="top">
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
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { ElMessage } from 'element-plus'
import { useThemeStore } from '../../stores/theme'

const route = useRoute()
const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const form = ref({ username: '', password: '' })
const loading = ref(false)
const isDarkTheme = computed(() => theme.effectiveDark)

const onSubmit = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.error('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form.value.username, form.value.password)
    const raw = route.query.redirect || '/admin/projects'
    let target = String(raw)
    // 完整外链（如 MCP 授权页 redirect）：直接整页跳转，避免 router 仅处理同源路径
    if (target.startsWith('http://') || target.startsWith('https://')) {
      window.location.href = target
      return
    }
    router.replace(target)
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
  height: calc(100vh - 112px);
  min-height: calc(100vh - 112px);
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
  margin-top: -100px;
}

.login-card:hover {
  transform: translateY(-2px);
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

.login-dark .login-button {
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
    margin-top: -64px;
  }

  .login-title {
    font-size: 22px;
  }

  :deep(.el-card__body) {
    padding: 0 16px 20px;
  }
}
</style>
