<template>
  <div class="login-wrap">
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
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const store = useAuthStore()
const form = ref({ username: '', password: '' })
const loading = ref(false)

const onSubmit = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.error('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form.value.username, form.value.password)
    const redirect = route.query.redirect || '/admin/projects'
    router.replace(redirect)
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
  display: flex;
  align-items: center;
  justify-content: center;
  /* Prevent page scroll: viewport minus header(64) and main padding(24+24) */
  height: calc(100vh - 112px);
  min-height: calc(100vh - 112px);
  padding: 10px;
  box-sizing: border-box;
  overflow: hidden;
}

.login-card {
  width: 400px;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08);
  border: none;
  overflow: hidden;
  background: #ffffff;
  transition: transform 0.3s ease;
  margin-top: -100px;
}

.login-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--kf-text-primary, #333);
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
  color: var(--kf-text-primary, #444);
  margin-bottom: 8px;
}

.login-form-item :deep(.el-input__wrapper) {
  border-radius: 10px;
  border: 1px solid var(--kf-border-color, #dcdfe6);
  box-shadow: none;
  transition: border-color 0.3s, box-shadow 0.3s;
}

.login-form-item :deep(.el-input__wrapper:focus-within) {
  border-color: var(--kf-primary, #409eff);
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15);
}

.login-button {
  width: 100%;
  margin-top: 12px;
  border-radius: 10px;
  font-weight: 500;
  letter-spacing: 0.5px;
  background: var(--kf-primary, #409eff);
  border: none;
  height: 48px;
  font-size: 16px;
}

.login-button:hover {
  background: var(--kf-primary-hover, #66b1ff);
}

/* 响应式优化 */
@media (max-width: 480px) {
  .login-card {
    border-radius: 12px;
  }

  .login-title {
    font-size: 22px;
  }

  :deep(.el-card__body) {
    padding: 0 16px 20px;
  }
}
</style>
