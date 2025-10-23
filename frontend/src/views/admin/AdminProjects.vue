<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>项目管理</span>
        <el-button type="primary" v-if="auth.user && (auth.user.role||'').toUpperCase()==='SUPER'" @click="$router.push('/admin/projects/new')">新建项目</el-button>
      </div>
    </template>

    <el-table :data="projects" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="项目名称" />
      <el-table-column prop="allowResubmit" label="可重复提交" width="120">
        <template #default="{row}">
          <el-tag :type="row.allowResubmit ? 'success' : 'info'">{{ row.allowResubmit ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="offline" label="下线" width="100">
        <template #default="{row}">
          <el-switch :model-value="row.offline" @change="(v)=>toggleOffline(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{row}">
          <el-tag :type="statusOf(row).type">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用户页链接" min-width="240">
        <template #default="{row}">
          <div style="display:flex; align-items:center; gap:8px; max-width: 100%;">
            <span class="nowrap-ellipsis" style="max-width:320px; display:inline-block;">{{ row._userLink }}</span>
            <el-button size="small" @click="copy(row._userLink)">复制</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="420">
        <template #default="{row}">
          <el-space>
            <el-button size="small" @click="edit(row)">编辑</el-button>
            <el-button size="small" @click="viewSubmissions(row)">提交记录</el-button>
            <el-button size="small" @click="exportCsv(row)">导出CSV</el-button>
            <el-button size="small" type="danger" v-if="auth.user && (auth.user.role||'').toUpperCase()==='SUPER'" @click="openDelete(row)">删除</el-button>
          </el-space>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

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

  <el-dialog v-model="delVisible" :title="`删除项目：${delTarget?.name||''}`" width="520px">
    <div style="line-height:1.8; margin-bottom: 8px;">
      此操作将永久删除该项目、所有提交记录，以及 OSS 上已上传的文件。请谨慎操作。
    </div>
    <el-alert type="warning" :closable="false" show-icon title="删除后不可恢复" style="margin-bottom: 10px;" />
    <div>为确认，请输入如下文字：</div>
    <div style="margin: 6px 0; color: var(--el-text-color-primary); font-weight: 500;">
      我确认删除{{ delTarget?.name || '' }}
    </div>
    <el-input v-model="delInput" placeholder="请输入上面的完整文字" />
    <template #footer>
      <el-button @click="delVisible = false">取消</el-button>
      <el-button type="danger" :disabled="delInput.trim() !== delPhrase" @click="doDelete">删除</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="progressVisible" title="正在删除项目" width="480px" :close-on-click-modal="false" :close-on-press-escape="false">
    <div v-if="task">
      <div style="margin-bottom:8px;">{{ delTarget?.name || '' }}</div>
      <el-progress :percentage="progressPct" :text-inside="true" :stroke-width="18" />
      <div style="margin-top:8px; color: var(--el-text-color-secondary);">
        已删除文件：{{ task.deletedFiles || 0 }} / {{ task.totalFiles || 0 }}；状态：{{ task.status }}
      </div>
    </div>
    <template #footer>
      <el-button @click="progressVisible=false" :disabled="task && task.status==='RUNNING'">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import api from '../../api'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
onMounted(()=>{ if (!auth.loaded) auth.loadMe() })

const projects = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.adminListProjects()
    const origin = window.location.origin
    const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
    projects.value = data.map(p => ({
      ...p,
      _userLink: `${origin}${base}/user/projects/${p.id}`
    }))
  } finally { loading.value = false }
}

onMounted(load)

const edit = (row) => {
  const id = row.id
  if (!id) return
  // 强制从详情页读取完整字段
  const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
  window.location.href = `${base}/admin/projects/${id}/edit`
}

const viewSubmissions = (row) => {
  const id = row.id
  const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
  window.location.href = `${base}/admin/projects/${id}/submissions`
}

  const exportCsv = async (row) => {
  const id = row.id
  const { data } = await api.exportSubmissions(id)
  const blob = new Blob([data], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `project-${id}-submissions.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}

const toggleOffline = async (row, v) => {
  try {
    await api.updateProject(row.id, { offline: v })
    ElMessage.success('已更新下线状态')
    load()
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

const copy = async (text) => {
  try { await navigator.clipboard.writeText(text); ElMessage.success('已复制') }
  catch { ElMessage.error('复制失败') }
}

const logout = async () => { await auth.logout(); location.href = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') + '/admin/login' }

const parseMs = (v) => {
  if (v === null || v === undefined) return null
  if (typeof v === 'number' && !Number.isNaN(v)) return v
  if (typeof v === 'string') {
    const n = Number(v)
    if (!Number.isNaN(n)) return n
    const d = Date.parse(v)
    if (!Number.isNaN(d)) return d
  }
  return null
}

const isExpired = (row) => {
  if (!row) return false
  if (row.expired === true) return true
  const ms = parseMs(row.endAt)
  if (ms != null) return Date.now() > ms
  return false
}

const statusOf = (row) => {
  if (!row) return { text: '进行中', type: 'success' }
  const expired = isExpired(row)
  const offline = !!row.offline
  if (offline) return { text: '已下线', type: 'info' }
  if (expired) return { text: '已过期', type: 'warning' }
  return { text: '进行中', type: 'success' }
}

const pwdVisible = ref(false)
const pwdForm = ref({ currentPassword: '', newPassword: '' })
const openChangePwd = () => {}
const changePwd = async () => {}

const delProject = async (row) => {
  try {
    await api.deleteProject(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

// 删除确认对话框逻辑
const delVisible = ref(false)
const delTarget = ref(null)
const delInput = ref('')
const delPhrase = computed(() => `我确认删除${delTarget.value?.name || ''}`)
const openDelete = (row) => { delTarget.value = row; delInput.value = ''; delVisible.value = true }
// 异步删除 + 进度查看
const task = ref(null)
let pollTimer = null
const progressVisible = ref(false)
const progressPct = computed(()=>{
  const t = task.value
  if (!t || !t.totalFiles) return 0
  return Math.floor((t.deletedFiles / t.totalFiles) * 100)
})
const doDelete = async () => {
  if (delInput.value.trim() !== delPhrase.value) return
  try {
    const { data } = await api.adminStartDeleteProject(delTarget.value.id)
    delVisible.value = false
    progressVisible.value = true
    task.value = { taskId: data.taskId, status: data.status, totalFiles: 0, deletedFiles: 0 }
    pollTimer && clearInterval(pollTimer)
    pollTimer = setInterval(async () => {
      try {
        const { data: st } = await api.adminGetTask(task.value.taskId)
        task.value = st
        if (st.status === 'COMPLETED' || st.status === 'FAILED') {
          clearInterval(pollTimer)
          pollTimer = null
          if (st.status === 'COMPLETED') { ElMessage.success('删除完成'); progressVisible.value = false; load() }
          else { ElMessage.error('删除失败：' + (st.message||'')) }
        }
      } catch (e) {}
    }, 1200)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除任务创建失败')
  }
}
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

:deep(.el-tag--warning) {
  background-color: var(--kf-warning-light);
  border-color: var(--kf-warning-light);
  color: var(--kf-warning);
}

:deep(.el-tag--info) {
  background-color: var(--kf-info-light);
  border-color: var(--kf-info-light);
  color: var(--kf-info);
}

/* 对话框样式 */
:deep(.el-dialog) {
  border-radius: 8px;
}

:deep(.el-dialog__header) {
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
  padding: 15px 20px;
}

:deep(.el-dialog__title) {
  color: var(--kf-text-primary);
  font-weight: bold;
}

:deep(.el-dialog__body) {
  padding: 20px;
}

:deep(.el-dialog__footer) {
  padding: 15px 20px;
  border-top: 1px solid var(--kf-border-color);
}

/* 表单项样式 */
:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-form-item__label) {
  color: var(--kf-text-primary);
  font-weight: 500;
}

/* 文本省略样式 */
.nowrap-ellipsis {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
