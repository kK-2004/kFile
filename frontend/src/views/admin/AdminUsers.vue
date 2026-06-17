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

        <!-- 可用模板分配（仅 SUPER 可见） -->
        <div v-if="isSuper" style="margin-top: 20px;">
          <div style="font-weight: 600; margin-bottom: 8px; color: var(--kf-text-primary);">可用模板</div>
          <el-table :data="templates">
            <el-table-column prop="id" label="ID" width="80"/>
            <el-table-column prop="name" label="模板名称"/>
            <el-table-column label="已授权" width="100">
              <template #default="{row}">
                <el-switch v-model="row._allowed"/>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div style="margin-top: 12px; text-align: right;">
          <el-button type="primary" @click="savePerms">保存</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- MCP 访问令牌管理 -->
    <el-card style="margin-top: 20px;">
      <template #header>
        <div class="card-header">
          <span>MCP 访问令牌</span>
        </div>
      </template>

      <div style="margin-bottom: 12px; font-size:13px; color:var(--kf-text-sub,#888);">
        令牌由 Agent 引导用户在浏览器授权后签发（回调地址 redirect_uri 由 Agent 提供，须命中系统设置里的回调白名单）。此处仅查看与吊销已签发的令牌。
      </div>

      <el-table :data="tokens" v-loading="tokensLoading" empty-text="暂无令牌">
        <el-table-column prop="id" label="ID" width="80"/>
        <el-table-column prop="username" label="用户"/>
        <el-table-column label="创建时间" width="180">
          <template #default="{row}">{{ formatTs(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="180">
          <template #default="{row}">{{ formatTs(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="最近使用" width="180">
          <template #default="{row}">{{ formatTs(row.lastUsedAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{row}">
            <el-tag :type="row.revoked ? 'info' : 'success'">{{ row.revoked ? '已吊销' : '有效' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{row}">
            <el-popconfirm title="确定吊销该令牌？" @confirm="revokeToken(row)">
              <template #reference>
                <el-button size="small" type="danger" :disabled="row.revoked">吊销</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { copyText } from '../../utils/clipboard'

const auth = useAuthStore()
const isSuper = computed(() => {
  const r = (auth.user?.role || '').toUpperCase()
  return r === 'SUPER'
})

const users = ref([])
const projects = ref([])
const templates = ref([])
const loading = ref(false)

const showCreate = ref(false)
const form = ref({ username: '', password: '', role: 'ADMIN' })

const showPerm = ref(false)
const currentUser = ref(null)
let original = new Set()
let originalTemplates = new Set()

// MCP 令牌
const tokens = ref([])
const tokensLoading = ref(false)

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

const loadTemplates = async () => {
  // 仅 SUPER 能看到全部模板用于分配；列表接口本身限 SUPER
  if (!isSuper.value) return
  try {
    const { data } = await api.adminListTemplates()
    templates.value = (data || []).map(t => ({ ...t, _allowed: false }))
  } catch {}
}

const loadTokens = async () => {
  tokensLoading.value = true
  try {
    const { data } = await api.mcpListTokens()
    tokens.value = data || []
  } catch { tokens.value = [] }
  finally { tokensLoading.value = false }
}

const formatTs = (ts) => ts ? new Date(ts).toLocaleString() : '-'

const revokeToken = async (row) => {
  try {
    await api.mcpRevokeToken(row.id)
    ElMessage.success('已吊销')
    await loadTokens()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '吊销失败')
  }
}

onMounted(async () => {
  if (!auth.loaded) await auth.loadMe()
  loadUsers()
  loadProjects()
  loadTokens()
  await loadTemplates()
})

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
  // 模板分配：SUPER 才能看到/操作
  if (isSuper.value) {
    try {
      const { data: tids } = await api.adminListUserTemplates(row.id)
      originalTemplates = new Set(tids || [])
      templates.value = templates.value.map(t => ({ ...t, _allowed: originalTemplates.has(t.id) }))
    } catch { originalTemplates = new Set() }
  }
  showPerm.value = true
}

const savePerms = async () => {
  try {
    // 项目权限
    const now = new Set(projects.value.filter(p => p._allowed).map(p => p.id))
    const grants = [...now].filter(id => !original.has(id))
    const revokes = [...original].filter(id => !now.has(id))
    for (const id of grants) await api.adminGrantProject(currentUser.value.id, id)
    for (const id of revokes) await api.adminRevokeProject(currentUser.value.id, id)
    // 模板权限（仅 SUPER）
    if (isSuper.value) {
      const nowT = new Set(templates.value.filter(t => t._allowed).map(t => t.id))
      const grantsT = [...nowT].filter(id => !originalTemplates.has(id))
      const revokesT = [...originalTemplates].filter(id => !nowT.has(id))
      for (const id of grantsT) await api.adminGrantTemplate(currentUser.value.id, id)
      for (const id of revokesT) await api.adminRevokeTemplate(currentUser.value.id, id)
    }
    ElMessage.success('已保存权限')
    showPerm.value = false
  } catch { ElMessage.error('保存失败') }
}
const resetPwd = async (row) => {
  let data
  try {
    ;({ data } = await api.adminResetPassword(row.id))
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '重置失败')
    return
  }
  try {
    await copyText(data.newPassword)
    ElMessage({ message: '已重置并复制新密码：' + data.newPassword, type: 'success', duration: 0, showClose: true })
  } catch {
    ElMessage({ message: '已重置，新密码：' + data.newPassword + '（请手动复制）', type: 'success', duration: 0, showClose: true })
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
const logout = async () => { await auth.logout(); location.href = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') + '/admin/login' }
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
  border-radius: 8px 8px 0 0;
}

.card-header span {
  color: var(--kf-text-primary);
  font-size: 18px;
  font-weight: bold;
}

/* 表格样式优化 */
:deep(.el-table) {
  border: 1px solid var(--kf-border-color);
  border-radius: 8px;
  overflow: hidden;
}

:deep(.el-table th) {
  background-color: var(--kf-header-bg);
  color: var(--kf-text-primary);
  font-weight: 500;
}

:deep(.el-table td) {
  padding: 8px 0;
}

/* 按钮样式统一 */
:deep(.el-button) {
  border-radius: 4px;
}

:deep(.el-button--primary) {
  background-color: var(--kf-primary);
  border-color: var(--kf-primary);
}

:deep(.el-button--primary:hover) {
  background-color: var(--kf-primary-hover);
  border-color: var(--kf-primary-hover);
}

:deep(.el-button--danger) {
  background-color: var(--kf-danger);
  border-color: var(--kf-danger);
}

:deep(.el-button--danger:hover) {
  background-color: var(--kf-danger-hover);
  border-color: var(--kf-danger-hover);
}

:deep(.el-button--warning) {
  background-color: var(--kf-warning);
  border-color: var(--kf-warning);
}

:deep(.el-button--warning:hover) {
  background-color: var(--kf-warning-hover);
  border-color: var(--kf-warning-hover);
}

:deep(.el-button--small) {
  padding: 8px 12px;
  font-size: 12px;
}

/* 标签样式优化 */
:deep(.el-tag) {
  border-radius: 4px;
}

:deep(.el-tag--success) {
  background-color: var(--kf-success-light);
  border-color: var(--kf-success-light);
  color: var(--kf-success);
}

:deep(.el-tag--info) {
  background-color: var(--kf-info-light);
  border-color: var(--kf-info-light);
  color: var(--kf-info);
}

/* 抽屉样式 */
:deep(.el-drawer) {
  border-radius: 8px 0 0 8px;
}

:deep(.el-drawer__header) {
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
  padding: 15px 20px;
  margin-bottom: 0;
}

:deep(.el-drawer__title) {
  color: var(--kf-text-primary);
  font-weight: bold;
  font-size: 16px;
}

:deep(.el-drawer__body) {
  padding: 20px;
}

/* 表单项样式 */
:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-form-item__label) {
  color: var(--kf-text-primary);
  font-weight: 500;
}

/* 选择器样式 */
:deep(.el-select) {
  width: 100%;
}
</style>
