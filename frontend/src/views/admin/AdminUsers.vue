<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>管理员与权限</span>
        <el-space>
          <el-button type="primary" @click="openCreate">新增管理员</el-button>
        </el-space>
      </div>
    </template>

    <el-table :data="users" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80"/>
      <el-table-column prop="username" label="用户名"/>
      <el-table-column prop="role" label="角色" width="120"/>
      <el-table-column prop="enabled" label="启用" width="100">
        <template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{ row.enabled?'是':'否' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="380">
        <template #default="{row}">
          <el-button size="small" @click="openPerm(row)">权限配置</el-button>
          <el-button size="small" type="warning" @click="resetPwd(row)">重置密码</el-button>
          <el-popconfirm title="确定删除该管理员？此操作不可恢复" @confirm="delUser(row)">
            <template #reference>
              <el-button size="small" type="danger" :disabled="(row.role||'').toUpperCase()==='SUPER'">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="showCreate" title="新增管理员" size="30%">
      <el-form :model="form" label-width="100px">
        <el-form-item label="用户名"><el-input v-model="form.username"/></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password"/></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role"><el-option value="ADMIN"/><el-option value="SUPER"/></el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="create">创建</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>

    <el-drawer v-model="showPerm" title="项目权限配置" size="40%">
      <div v-if="currentUser">
        <div style="margin-bottom: 12px;">用户：{{ currentUser.username }} ({{ currentUser.role }})</div>
        <el-table :data="projects">
          <el-table-column prop="id" label="项目ID" width="100"/>
          <el-table-column prop="name" label="项目名称"/>
          <el-table-column label="有权限" width="120">
            <template #default="{row}">
              <el-switch v-model="row._allowed" :disabled="currentUser.role==='SUPER'"/>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 12px; text-align: right;">
          <el-button type="primary" @click="savePerms">保存</el-button>
        </div>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const users = ref([])
const projects = ref([])
const loading = ref(false)

const showCreate = ref(false)
const form = ref({ username: '', password: '', role: 'ADMIN' })

const showPerm = ref(false)
const currentUser = ref(null)
let original = new Set()

const loadUsers = async () => {
  loading.value = true
  try {
    const { data } = await api.adminListUsers()
    users.value = data
  } finally { loading.value = false }
}

const loadProjects = async () => {
  const { data } = await api.listProjects()
  projects.value = data.map(p => ({ ...p, _allowed: false }))
}

onMounted(() => { loadUsers(); loadProjects() })

const openCreate = () => { form.value = { username:'', password:'', role:'ADMIN' }; showCreate.value = true }

const create = async () => {
  try {
    await api.adminCreateUser(form.value)
    ElMessage.success('已创建')
    showCreate.value = false
    loadUsers()
  } catch { ElMessage.error('创建失败') }
}

const openPerm = async (row) => {
  currentUser.value = row
  const { data } = await api.adminListUserProjects(row.id)
  original = new Set(data)
  projects.value = projects.value.map(p => ({ ...p, _allowed: original.has(p.id) }))
  showPerm.value = true
}

const savePerms = async () => {
  try {
    const now = new Set(projects.value.filter(p => p._allowed).map(p => p.id))
    const grants = [...now].filter(id => !original.has(id))
    const revokes = [...original].filter(id => !now.has(id))
    for (const id of grants) await api.adminGrantProject(currentUser.value.id, id)
    for (const id of revokes) await api.adminRevokeProject(currentUser.value.id, id)
    ElMessage.success('已保存权限')
    showPerm.value = false
  } catch { ElMessage.error('保存失败') }
}
const resetPwd = async (row) => {
  try {
    const { data } = await api.adminResetPassword(row.id)
    await navigator.clipboard.writeText(data.newPassword)
    ElMessage.success('已重置并复制新密码：' + data.newPassword)
  } catch (e) {
    const msg = e?.response?.data?.message || '重置失败'
    ElMessage.error(msg)
  }
}
const delUser = async (row) => {
  try {
    await api.adminDeleteUser(row.id)
    ElMessage.success('已删除')
    loadUsers()
  } catch (e) {
    const msg = e?.response?.data?.message || '删除失败'
    ElMessage.error(msg)
  }
}
const logout = async () => { await auth.logout(); location.href = '/admin/login' }
</script>

<style scoped>
.card-header{ display:flex; align-items:center; justify-content:space-between }
</style>
const auth = useAuthStore()
onMounted(()=>{ if (!auth.loaded) auth.loadMe() })
