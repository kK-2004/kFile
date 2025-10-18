<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>提交记录 - 项目 {{ projectId }}</span>
        <el-space>
          <el-button @click="$router.back()">返回</el-button>
          <el-button type="primary" @click="exportCsv">导出CSV</el-button>
        </el-space>
      </div>
    </template>

    <el-form inline style="margin-bottom: 10px;">
      <el-form-item label="字段">
        <el-select v-model="filterKey" placeholder="选择字段" style="width: 200px;">
          <el-option v-for="f in expectedFields" :key="f.key" :label="f.label + ' ('+f.key+')'" :value="f.key" />
        </el-select>
      </el-form-item>
      <el-form-item label="值">
        <el-input v-model="filterValue" placeholder="输入匹配值" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="applyFilter">搜索</el-button>
        <el-button @click="resetFilter">重置</el-button>
        <el-button type="success" @click="downloadZip()">打包全部</el-button>
        <el-button type="warning" :disabled="!filterKey || !filterValue" @click="downloadZip(true)">按条件打包</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="filteredContent" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80"/>
      <el-table-column prop="submitCount" label="次数" width="80"/>
      <el-table-column label="提交者" min-width="220">
        <template #default="{row}">
          <div v-for="(v,k) in parseSubmitter(row)" :key="k" style="line-height:1.6;">
            <strong>{{k}}:</strong> <span>{{v}}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="文件URL" min-width="260">
        <template #default="{row}">
          <div v-for="(u,idx) in parseUrls(row)" :key="idx" style="display:flex;align-items:center;gap:6px;line-height:1.6; max-width:100%;">
            <span class="nowrap-ellipsis" style="max-width:420px; display:inline-block;" :title="u">{{u}}</span>
            <el-button size="small" @click="copy(u)">复制</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="过期" width="80">
        <template #default="{row}"><el-tag :type="row.expired?'danger':'success'">{{ row.expired?'是':'否' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="ipAddress" label="IP" width="140"/>
      <el-table-column prop="ipCountry" label="国家" width="120"/>
      <el-table-column prop="ipProvince" label="省份" width="120"/>
      <el-table-column prop="ipCity" label="城市" width="120"/>
      <el-table-column prop="osName" label="系统" />
      <el-table-column prop="browserName" label="浏览器" />
      <el-table-column prop="deviceType" label="设备" width="100"/>
      <el-table-column prop="createdAt" label="创建时间" width="180"/>
    </el-table>

    <div style="margin-top: 16px; text-align: right;">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="page.totalElements"
        :page-size="size"
        :current-page="pageNumber+1"
        @current-change="onPageChange"
      />
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const auth = useAuthStore()
onMounted(()=>{ if (!auth.loaded) auth.loadMe() })
const projectId = route.params.id
  const pageNumber = ref(0)
  const size = ref(20)
  const page = ref({ content: [], totalElements: 0 })
  const loading = ref(false)
  const expectedFields = ref([])
  const filterKey = ref('')
  const filterValue = ref('')
  const filteredContent = computed(()=>{
    if (!filterKey.value || !filterValue.value) return page.value.content
    const prefix = String(filterValue.value)
    return page.value.content.filter(row => {
      const m = parseSubmitter(row)
      const val = m ? m[filterKey.value] : undefined
      if (val === undefined || val === null) return false
      return String(val).startsWith(prefix)
    })
  })

  const load = async () => {
    loading.value = true
    try {
      const { data } = await api.pageSubmissions(projectId, pageNumber.value, size.value)
      page.value = data
      // 尝试从项目接口读取期望字段作为筛选项
      const pj = await api.getProject(projectId)
      expectedFields.value = Array.isArray(pj.data.expectedUserFields) ? pj.data.expectedUserFields : []
    } finally { loading.value = false }
  }

onMounted(load)

const onPageChange = (p)=>{ pageNumber.value = p-1; load() }

  const exportCsv = async () => {
    const { data } = await api.exportSubmissions(projectId)
    const blob = new Blob([data], { type: 'text/csv;charset=utf-8;' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `project-${projectId}-submissions.csv`
    link.click()
    URL.revokeObjectURL(link.href)
  }
  const applyFilter = ()=>{}
  const resetFilter = ()=>{ filterKey.value=''; filterValue.value='' }
  const downloadZip = async (byFilter=false) => {
    const params = byFilter ? { fieldKey: filterKey.value, fieldValue: filterValue.value } : {}
    const { data } = await api.exportZip(projectId, params.fieldKey, params.fieldValue)
    const blob = new Blob([data], { type: 'application/zip' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = byFilter && params.fieldKey && params.fieldValue ? `project-${projectId}-${params.fieldKey}-${params.fieldValue}.zip` : `project-${projectId}.zip`
    link.click()
    URL.revokeObjectURL(link.href)
  }

  const parseSubmitter = (row) => {
    try { return row.submitterInfo ? JSON.parse(row.submitterInfo) : {} } catch { return {} }
  }
  const parseUrls = (row) => {
    try { return row.fileUrls ? JSON.parse(row.fileUrls) : [] } catch { return [] }
  }
  const copy = async (text) => {
    try { await navigator.clipboard.writeText(text) } catch {}
  }
  const logout = async () => { await auth.logout(); location.href = '/admin/login' }
</script>

<style scoped>
.card-header{ display:flex; align-items:center; justify-content:space-between }
</style>
