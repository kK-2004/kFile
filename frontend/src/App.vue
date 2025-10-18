<template>
  <el-config-provider namespace="el">
    <el-container style="min-height: 100vh;">
      <el-header>
        <el-row align="middle" justify="space-between">
          <el-col :span="12">
            <h2 style="margin: 0">K-File 文件收集系统</h2>
          </el-col>
          <el-col :span="12" style="text-align: right">
            <el-space>
              <el-button link @click="$router.push('/user/projects')">用户端</el-button>
              <el-button link @click="$router.push('/admin')">管理端</el-button>
              <el-divider direction="vertical" />
              <template v-if="isAdmin">
                <el-button link @click="$router.push('/admin/projects')">项目</el-button>
                <el-button link v-if="isSuper" @click="$router.push('/admin/users')">管理员与权限</el-button>
                <el-divider direction="vertical" />
                <span v-if="auth && auth.user">当前用户：{{ auth.user.username }}（{{ (auth.user.role||'').toUpperCase() }}）</span>
                <el-button size="small" @click="openChangePwd" v-if="auth && auth.user">修改密码</el-button>
                <el-button size="small" @click="logout" v-if="auth && auth.user">退出</el-button>
              </template>
            </el-space>
          </el-col>
        </el-row>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px">
      <el-form :model="pwdForm" label-width="100px">
        <el-form-item label="当前密码"><el-input v-model="pwdForm.currentPassword" type="password" autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwdForm.newPassword" type="password" autocomplete="new-password" /></el-form-item>
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

<style>
body { margin: 0; }
</style>
