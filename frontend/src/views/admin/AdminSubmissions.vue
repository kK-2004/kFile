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

    <el-table :data="groupedContent" v-loading="loading">
      <el-table-column type="expand">
        <template #default="{ row }">
          <!-- 时间线风格的版本链 -->
          <div class="timeline-container">
            <div class="timeline-wrapper">
              <div v-for="(version, index) in row.versions" :key="version.id" class="timeline-item">
                <!-- 时间线连接线 -->
                <div v-if="index < row.versions.length - 1" class="timeline-line"></div>

                <!-- 状态图标 -->
                <div class="timeline-icon" :class="getVersionStatusClass(version, index, row.versions.length)">
                  <!-- 最新版本 - 绿色勾号 -->
                  <svg v-if="index === 0" class="icon-svg" fill="currentColor" viewBox="0 0 20 20">
                    <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
                  </svg>
                  <!-- 历史版本 - 蓝色上传图标 -->
                  <svg v-else-if="index < row.versions.length - 1" class="icon-svg" fill="currentColor" viewBox="0 0 20 20">
                    <path fill-rule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clip-rule="evenodd" />
                  </svg>
                  <!-- 最早版本 - 灰色圆点 -->
                  <div v-else class="timeline-dot"></div>
                </div>

                <!-- 版本信息 -->
                <div class="version-content">
                  <!-- 版本标题和时间 -->
                  <div class="version-header">
                    <div class="version-title-wrapper">
                      <span class="version-title">
                        {{ getVersionTitle(index, row.versions.length) }}
                      </span>
                      <span v-if="index === 0" class="latest-badge">
                        最新
                      </span>
                    </div>
                    <time class="version-time">{{ formatDateTime(version.createdAt) }}</time>
                  </div>

                  <!-- 文件列表 -->
                  <div v-if="version.files && version.files.length > 0" class="file-list">
                    <div v-for="(file, fileIndex) in version.files" :key="fileIndex" class="file-item">
                      <div class="file-info">
                        <!-- 文件图标 -->
                        <div class="file-icon">
                          <svg class="file-icon-svg" fill="currentColor" viewBox="0 0 20 20">
                            <path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clip-rule="evenodd" />
                          </svg>
                        </div>
                        <!-- 文件名 -->
                        <span class="file-name" @click="download(file)">
                          {{ filename(file) }}
                        </span>
                      </div>
                      <!-- 操作按钮 -->
                      <div class="file-actions">
                        <button @click="download(file)" class="action-btn action-btn-primary">
                          <svg class="action-icon" fill="currentColor" viewBox="0 0 20 20">
                            <path fill-rule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clip-rule="evenodd" />
                          </svg>
                          下载
                        </button>
                        <button @click="copy(file)" class="action-btn action-btn-secondary">
                          <svg class="action-icon" fill="currentColor" viewBox="0 0 20 20">
                            <path d="M8 3a1 1 0 011-1h2a1 1 0 110 2H9a1 1 0 01-1-1z" />
                            <path d="M6 3a2 2 0 00-2 2v11a2 2 0 002 2h8a2 2 0 002-2V5a2 2 0 00-2-2 3 3 0 01-3 3H9a3 3 0 01-3-3z" />
                          </svg>
                          复制
                        </button>
                      </div>
                    </div>
                  </div>

                  <!-- 无文件提示 -->
                  <div v-else class="no-files">
                    暂无文件
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="提交者" min-width="220">
        <template #default="{row}">
          <div v-for="(v,k) in parseSubmitter(row)" :key="k" style="line-height:1.6;">
            <strong>{{k}}:</strong> <span>{{v}}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="submitCount" label="次数" width="80"/>
      <el-table-column label="最新文件" min-width="220">
        <template #default="{row}">
          <a v-if="row.latestUrl" href="javascript:void(0)" @click="download(row.latestUrl)">{{ filename(row.latestUrl) }}</a>
        </template>
      </el-table-column>
      <el-table-column label="逾期" width="80">
        <template #default="{row}"><el-tag :type="isOverdue(row)?'danger':'success'">{{ isOverdue(row)?'是':'否' }}</el-tag></template>
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
const project = ref(null)
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
    project.value = pj.data
    if (Array.isArray(project.value.expectedUserFields)) {
      expectedFields.value = project.value.expectedUserFields
    } else if (typeof project.value.expectedUserFields === 'string') {
      try { expectedFields.value = JSON.parse(project.value.expectedUserFields) || [] } catch { expectedFields.value = [] }
    } else {
      expectedFields.value = []
    }
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
  try {
    if (!row || !row.submitterInfo) return {}
    if (typeof row.submitterInfo === 'object') return row.submitterInfo
    return JSON.parse(row.submitterInfo)
  } catch { return {} }
}
const parseUrls = (row) => {
  try {
    if (!row || !row.fileUrls) return []
    if (Array.isArray(row.fileUrls)) return row.fileUrls
    if (typeof row.fileUrls === 'string') {
      const s = row.fileUrls.trim()
      if (s.startsWith('[')) return JSON.parse(s)
    }
    return []
  } catch { return [] }
}
const filename = (u) => {
  if (!u) return ''
  const q = u.split('?')[0]
  const i = Math.max(q.lastIndexOf('/'), q.lastIndexOf('\\'))
  return i >= 0 ? q.substring(i+1) : q
}
const download = (u) => {
  if (!u) return
  const a = document.createElement('a')
  a.href = u
  a.target = '_blank'
  a.click()
}
const copy = async (text) => {
  try { await navigator.clipboard.writeText(text) } catch {}
}
const logout = async () => { await auth.logout(); location.href = '/admin/login' }

// 分组：按 submitterFingerprint 聚合为一个"版本链"
const groupedContent = computed(() => {
  const items = filteredContent.value
  const map = new Map()
  for (const row of items) {
    if (row.valid === false) continue // 只展示有效记录
    const key = row.submitterFingerprint || row.id
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(row)
  }
  const groups = []
  for (const [k, list] of map.entries()) {
    list.sort((a,b)=> new Date(b.createdAt) - new Date(a.createdAt))
    const latest = list[0]
    const latestUrls = parseUrls(latest)
    groups.push({
      id: latest.id,
      submitCount: latest.submitCount,
      submitterInfo: latest.submitterInfo,
      submitterFingerprint: k,
      latestUrl: latestUrls && latestUrls.length ? latestUrls[latestUrls.length-1] : '',
      latestCreatedAt: latest.createdAt,
      ipAddress: latest.ipAddress,
      ipCountry: latest.ipCountry,
      ipProvince: latest.ipProvince,
      ipCity: latest.ipCity,
      osName: latest.osName,
      browserName: latest.browserName,
      deviceType: latest.deviceType,
      createdAt: latest.createdAt,
      versions: list.map(s => ({ id: s.id, createdAt: s.createdAt, files: parseUrls(s) }))
    })
  }
  return groups
})

const isOverdue = (groupRow) => {
  try {
    const end = project.value?.endAt
    if (!end) return false
    return new Date(groupRow.latestCreatedAt) > new Date(end)
  } catch { return false }
}

// 新增的时间线相关方法
const getVersionStatusClass = (version, index, totalVersions) => {
  if (index === 0) {
    // 最新版本 - 绿色
    return 'status-latest'
  } else if (index < totalVersions - 1) {
    // 中间版本 - 蓝色
    return 'status-middle'
  } else {
    // 最早版本 - 灰色
    return 'status-earliest'
  }
}

const getVersionTitle = (index, totalVersions) => {
  if (index === 0) {
    return '最新提交'
  } else if (index === totalVersions - 1) {
    return '初始提交'
  } else {
    return `第 ${totalVersions - index} 次提交`
  }
}

const formatDateTime = (dateTimeStr) => {
  try {
    const date = new Date(dateTimeStr)
    const now = new Date()
    const diffMs = now - date
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

    if (diffDays === 0) {
      return '今天 ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    } else if (diffDays === 1) {
      return '昨天 ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    } else if (diffDays < 7) {
      return `${diffDays}天前`
    } else {
      return date.toLocaleDateString('zh-CN', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      })
    }
  } catch {
    return dateTimeStr
  }
}
</script>

<style scoped>
.card-header{
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 时间线样式 */
.timeline-container {
  padding: 16px 24px;
}

.timeline-wrapper {
  position: relative;
}

.timeline-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  padding-bottom: 24px;
}

.timeline-item:last-child {
  padding-bottom: 0;
}

.timeline-line {
  position: absolute;
  left: 16px;
  top: 32px;
  width: 1px;
  height: calc(100% - 8px);
  background-color: #e5e7eb;
}

.timeline-icon {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  margin-right: 16px;
  flex-shrink: 0;
}

.timeline-icon.status-latest {
  background-color: #10b981;
}

.timeline-icon.status-middle {
  background-color: #3b82f6;
}

.timeline-icon.status-earliest {
  background-color: #9ca3af;
}

.icon-svg {
  width: 16px;
  height: 16px;
  color: white;
}

.timeline-dot {
  width: 8px;
  height: 8px;
  background-color: white;
  border-radius: 50%;
}

.version-content {
  flex: 1;
  min-width: 0;
}

.version-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.version-title-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-title {
  font-size: 14px;
  font-weight: 500;
  color: #111827;
}

.latest-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
  background-color: #dcfce7;
  color: #166534;
}

.version-time {
  font-size: 14px;
  color: #6b7280;
}

.file-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px;
  background-color: #f9fafb;
  border-radius: 8px;
  transition: background-color 0.2s;
}

.file-item:hover {
  background-color: #f3f4f6;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.file-icon {
  flex-shrink: 0;
}

.file-icon-svg {
  width: 20px;
  height: 20px;
  color: #9ca3af;
}

.file-name {
  font-size: 14px;
  color: #111827;
  cursor: pointer;
  text-overflow: ellipsis;
  overflow: hidden;
  white-space: nowrap;
  transition: color 0.2s;
}

.file-name:hover {
  color: #2563eb;
}

.file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border: none;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s;
  background: transparent;
}

.action-btn:focus {
  outline: none;
}

.action-btn-primary {
  color: #2563eb;
}

.action-btn-primary:hover {
  color: #1d4ed8;
}

.action-btn-secondary {
  color: #6b7280;
}

.action-btn-secondary:hover {
  color: #4b5563;
}

.action-icon {
  width: 12px;
  height: 12px;
  margin-right: 4px;
}

.no-files {
  margin-top: 8px;
  font-size: 14px;
  color: #6b7280;
  font-style: italic;
}
</style>