<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>可参与项目</span>
      </div>
    </template>

    <el-alert v-if="!hasAdmin" type="info" show-icon title="仅管理员可查看项目列表" style="margin-bottom:12px;" />

    <el-table v-else :data="projects" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="项目名称" />
      <el-table-column label="状态" width="140">
        <template #default="{row}">
          <el-tag :type="statusOf(row).type">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{row}">
          <el-button size="small" type="primary" :disabled="row.offline" @click="open(row)">提交</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import api from '../../api'
import { useAuthStore } from '../../stores/auth'

const projects = ref([])
const loading = ref(false)
const auth = useAuthStore()
const hasAdmin = computed(() => !!auth.user)

const load = async () => {
  loading.value = true
  try {
    if (!auth.loaded) await auth.loadMe()
    if (!auth.user) { projects.value = []; return }
    const { data } = await api.adminListProjects()
    projects.value = data
  } finally { loading.value = false }
}

onMounted(load)

const open = (row)=>{ window.location.href = `/user/projects/${row.id}` }

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
  // 同时考虑上线状态与过期状态：仅当上线且未过期时显示“进行中”
  if (offline) return { text: '已下线', type: 'info' }
  if (expired) return { text: '已过期', type: 'warning' }
  return { text: '进行中', type: 'success' }
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
  padding: 12px 0;
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

:deep(.el-button--primary:disabled) {
  background-color: var(--kf-disabled-bg);
  border-color: var(--kf-disabled-bg);
  color: var(--kf-disabled-text);
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
</style>
