<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>可参与项目</span>
      </div>
    </template>

    <el-table :data="projects" v-loading="loading">
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
import { onMounted, ref } from 'vue'
import api from '../../api'

const projects = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.listProjects()
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
.card-header{ display:flex; align-items:center; justify-content:space-between }
</style>
