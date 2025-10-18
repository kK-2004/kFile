<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <template #header><span>管理员登录</span></template>
      <el-form :model="form" @keyup.enter="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit">登录</el-button>
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
  if (!form.value.username || !form.value.password) { ElMessage.error('请输入用户名和密码'); return }
  loading.value = true
  try {
    await store.login(form.value.username, form.value.password)
    const redirect = route.query.redirect || '/admin/projects'
    router.replace(redirect)
  } catch (e) {
    const msg = e?.response?.data?.message || '登录失败'
    ElMessage.error(msg)
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-wrap { display:flex; align-items:center; justify-content:center; min-height: 70vh; }
.login-card { width: 360px; }
</style>

