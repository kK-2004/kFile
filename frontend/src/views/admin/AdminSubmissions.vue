<template>
  <div class="admin-submissions-fullscreen">
    <el-card class="submissions-card">
      <template #header>
        <div class="card-header">
          <span class="header-title">
            提交记录 - <span class="project-name-highlight">{{ projectName }}</span>
          </span>
          <el-space>
            <el-button @click="$router.back()">返回</el-button>
            <el-button type="primary" @click="exportCsv">导出CSV</el-button>
            <el-button type="success" @click="manualVisible = true">手动上传</el-button>
            <el-button @click="showMissing">未提交名单</el-button>
          </el-space>
        </div>
      </template>

      <div class="filter-section">
        <el-form inline class="filter-form">
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
            <el-button type="success" @click="startArchive(false)">打包</el-button>
            <el-tooltip
              effect="dark"
              placement="top"
              content="仅下载每位提交者的最新一次提交。"
            >
              <span class="help-icon">?</span>
            </el-tooltip>
            <el-button class="cond-zip-btn" :disabled="!hasFilteredResult" @click="startArchive(true)"
                       style="margin-left: 8px;">按条件打包</el-button>
            <el-tooltip
              effect="dark"
              placement="top"
              content="输入“字段”和“值”后，将按该字段对记录进行前缀匹配，仅下载每位提交者的最新一次提交；若无匹配内容则不可用。"
            >
              <span class="help-icon">?</span>
            </el-tooltip>
          </el-form-item>
        </el-form>
      </div>

      <!-- 未提交名单弹窗 -->
      <el-dialog v-model="missingVisible" title="未提交名单" width="600px">
        <div v-if="missing.enabled">
          <div class="mb-3 text-sm text-gray-600">共 {{ missing.missingCount || (missing.missing||[]).length }} 条</div>
          <el-table :data="missing.missing" style="width:100%" max-height="360">
            <el-table-column v-for="k in (missing.keys||[])" :key="k" :prop="k" :label="getFieldLabel(k)" />
          </el-table>
        </div>
        <div v-else class="text-gray-500">{{ missing.message || '未配置允许提交名单' }}</div>
        <template #footer>
          <span class="dialog-footer">
            <el-button @click="missingVisible = false">关闭</el-button>
            <el-button type="primary" @click="downloadMissing">下载CSV</el-button>
          </span>
        </template>
      </el-dialog>

      <div class="table-container">
        <el-table
            :data="groupedContent"
            v-loading="loading"
            height="100%"
            style="width: 100%"
        >
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
                            <span class="file-name" @click="download(file.url, file.name)">
                              {{ file.name || filename(file.url) }}
                            </span>
                          </div>
                          <!-- 操作按钮 -->
                          <div class="file-actions">
                            <button @click="download(file.url, file.name)" class="action-btn action-btn-primary">
                              <svg class="action-icon" fill="currentColor" viewBox="0 0 20 20">
                                <path fill-rule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clip-rule="evenodd" />
                              </svg>
                              下载
                            </button>
                            <button @click="openPresign(file.url, file.name, 'copy')" class="action-btn action-btn-secondary">
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
                <strong>{{ getFieldLabel(k) }}:</strong> <span>{{v}}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="submitCount" label="次数" width="80"/>
          <el-table-column label="最新文件" min-width="220">
            <template #default="{row}">
              <a v-if="row.latestUrl" href="javascript:void(0)" @click="download(row.latestUrl, row.latestName)" class="file-link">{{ row.latestName || filename(row.latestUrl) }}</a>
            </template>
          </el-table-column>
          <el-table-column label="逾期" width="80">
            <template #default="{row}"><el-tag :type="isOverdue(row)?'danger':'success'">{{ isOverdue(row)?'是':'否' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="ipAddress" label="IP" width="140"/>
          <el-table-column prop="osName" label="系统" />
          <el-table-column prop="browserName" label="浏览器" />
          <el-table-column prop="deviceType" label="设备" width="100"/>
          <el-table-column label="创建时间" width="200">
            <template #default="{row}">{{ formatDateTimeLocal(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{row}">
              <el-button type="danger" size="small" @click="openDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>


    </el-card>
    <!-- 打包进度窗口 -->
    <el-dialog v-model="archProgressVisible" title="正在打包" width="480px" :close-on-click-modal="false" :close-on-press-escape="false">
      <div v-if="archTask">
        <div style="margin-bottom:8px;">{{ projectName }}</div>
        <el-progress :percentage="archPct" :text-inside="true" :stroke-width="18" />
        <div style="margin-top:8px; color: var(--el-text-color-secondary);">
          进度：{{ archTask.processedEntries || 0 }} / {{ archTask.totalEntries || 0 }}
        </div>
      </div>
      <template #footer>
        <el-button @click="archProgressVisible=false" :disabled="archTask && archTask.status==='RUNNING'">关闭</el-button>
      </template>
    </el-dialog>
    <!-- 手动上传：按项目配置字段填写（与用户端一致），但不做限制校验 -->
    <el-dialog v-model="manualVisible" title="手动上传（不受项目限制）" width="600px">
      <el-form label-width="120px">
        <template v-if="(manualFields||[]).length">
          <el-form-item v-for="f in manualFields" :key="f.key" :label="f.label || f.key">
            <template v-if="(f.type||'text') === 'select' && Array.isArray(f.options)">
              <el-select v-model="manualSubmitter[f.key]" placeholder="请选择" style="width: 320px;">
                <el-option v-for="opt in f.options" :key="opt" :label="opt" :value="opt" />
              </el-select>
            </template>
            <template v-else>
              <el-input v-model="manualSubmitter[f.key]" :placeholder="f.placeholder || ''" style="width: 320px;" />
            </template>
          </el-form-item>
        </template>
        <el-form-item label="选择文件">
          <el-upload drag :auto-upload="false" :on-change="onManualFileChange" multiple>
            <div class="el-upload__text">拖拽文件到此处，或点击选择</div>
          </el-upload>
          <div v-if="manualFiles.length" style="margin-left: 12px; color: var(--el-text-color-secondary);">已选择 {{ manualFiles.length }} 个文件</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible=false">取消</el-button>
        <el-button type="primary" :disabled="!manualFiles.length" @click="doManualUpload">上传</el-button>
      </template>
    </el-dialog>

    <!-- 删除确认 -->
    <el-dialog v-model="delVisible" title="删除该用户所有提交" width="520px">
      <div style="line-height:1.8; margin-bottom: 8px;">
        将按字段「{{ delFieldKey }}」=「{{ delFieldValue }}」删除该用户在本项目的所有提交及其文件。此操作不可恢复。
      </div>
      <el-alert type="warning" :closable="false" show-icon title="删除后不可恢复" style="margin-bottom: 10px;" />
      <div>为确认，请输入如下文字：</div>
      <div style="margin: 6px 0; color: var(--el-text-color-primary); font-weight: 500;">我确认删除</div>
      <el-input v-model="delInput" placeholder="请输入：我确认删除" />
      <template #footer>
        <el-button @click="delVisible=false">取消</el-button>
        <el-button type="danger" :disabled="delInput.trim() !== '我确认删除'" @click="doDelete">删除</el-button>
      </template>
    </el-dialog>

    <!-- 预签名下载/复制：选择有效期 -->
    <el-dialog v-model="presignDialogVisible" title="生成预签名链接" width="400px">
      <div style="margin-bottom: 12px;">
        目标文件：<span style="font-weight:500;">{{ presignTargetName }}</span>
      </div>
      <el-form label-width="80px">
        <el-form-item label="有效期">
          <el-radio-group v-model="presignExpire">
            <el-radio-button v-for="opt in presignOptions" :key="opt.value" :label="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="presignDialogVisible=false" :disabled="presignLoading">取消</el-button>
        <el-button type="primary" :loading="presignLoading" @click="doPresign">
          {{ presignAction === 'download' ? '下载' : '复制链接' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, onUnmounted } from 'vue'
import JSZip from 'jszip'
import api from '../../api'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const auth = useAuthStore()

const projectId = route.params.id
const pageNumber = ref(0)
// 默认拉取更多数据以配合滚动查看
const size = ref(100)
const page = ref({ content: [], totalElements: 0 })
const project = ref(null)
const loading = ref(false)
const expectedFields = ref([])
const filterKey = ref('')
const filterValue = ref('')
const tableMaxHeight = ref(400)

// 动态计算表格最大高度（Element Plus 使用 max-height 时表头为 sticky）
const calculateTableHeight = () => {
  // header(64px) + main padding(48px) + card header(约70px) + filter(约80px) + 额外间距(40px)
  const fixedHeight = 64 + 48 + 70 + 80 + 40
  const h = Math.max(240, window.innerHeight - fixedHeight)
  tableMaxHeight.value = h
}

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

    // 管理端使用 admin 接口获取完整配置（含 allowedSubmitterList），用于渲染下拉
    const pj = await api.adminGetProject(projectId)
    project.value = pj.data
    if (Array.isArray(project.value.expectedUserFields)) {
      expectedFields.value = project.value.expectedUserFields
    } else if (typeof project.value.expectedUserFields === 'string') {
      try { expectedFields.value = JSON.parse(project.value.expectedUserFields) || [] } catch { expectedFields.value = [] }
    } else {
      expectedFields.value = []
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!auth.loaded) auth.loadMe()
  load()
  calculateTableHeight()
  window.addEventListener('resize', calculateTableHeight)
})

onUnmounted(() => {
  window.removeEventListener('resize', calculateTableHeight)
})

// 当前项目名称（若未加载到项目则回退为“项目 {id}”）
const projectName = computed(() => project.value?.name || `项目 ${projectId}`)

// 字段 label 映射，用于提交者信息中将 key 显示为 label
const fieldLabelMap = computed(() => {
  const map = {}
  for (const f of expectedFields.value || []) {
    if (f && f.key) map[f.key] = f.label || f.key
  }
  return map
})

const getFieldLabel = (key) => fieldLabelMap.value[key] || key

const exportCsv = async () => {
  const { data } = await api.exportSubmissions(projectId)
  const blob = new Blob([data], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `project-${projectId}-submissions.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}

const applyFilter = ()=>{ pageNumber.value = 0; load() }
const resetFilter = ()=>{ filterKey.value=''; filterValue.value=''; pageNumber.value=0; load() }

// 异步打包（前端并发下载 + 打包）
const archProgressVisible = ref(false)
const archTask = ref(null)
const archPct = computed(()=>{
  const t = archTask.value
  if (!t || !t.totalEntries) return 0
  return Math.floor((t.processedEntries / t.totalEntries) * 100)
})
const startArchive = async (byFilter=false) => {
  const params = byFilter ? { fieldKey: filterKey.value, fieldValue: filterValue.value } : {}
  try {
    const { data } = await api.adminArchiveManifest(projectId, params.fieldKey, params.fieldValue)
    const entries = data?.entries || []
    if (!entries.length) {
      if (typeof ElMessage !== 'undefined') ElMessage.info('没有可打包的文件')
      return
    }
    archTask.value = { status: 'RUNNING', totalEntries: entries.length, processedEntries: 0 }
    archProgressVisible.value = true

    const zip = new JSZip()
    const concurrency = 4
    let index = 0
    let processed = 0

    const worker = async () => {
      while (true) {
        const current = index++
        if (current >= entries.length) break
        const e = entries[current]
        try {
          const resp = await fetch(e.url)
          if (!resp.ok) throw new Error(`下载失败: ${resp.status}`)
          const buf = await resp.arrayBuffer()
          const name = e.filename || e.key || `file-${current + 1}`
          zip.file(name, buf)
        } catch (err) {
          console.warn('download failed for entry', e, err)
        } finally {
          processed++
          archTask.value = { ...archTask.value, processedEntries: processed }
        }
      }
    }

    const workers = []
    const workerCount = Math.min(concurrency, entries.length)
    for (let i = 0; i < workerCount; i++) {
      workers.push(worker())
    }
    await Promise.all(workers)

    const blob = await zip.generateAsync({ type: 'blob' })
    const filename = data.filename || `project-${projectId}.zip`
    const ok = await saveBlobToTarget(blob, filename, null)
    if (ok) {
      if (typeof ElMessage !== 'undefined') ElMessage.success('打包完成，文件已保存')
    } else {
      if (typeof ElMessage !== 'undefined') ElMessage.error('打包完成，但保存文件失败')
    }
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '打包失败'
    if (typeof ElMessage !== 'undefined') ElMessage.error(msg)
  } finally {
    archProgressVisible.value = false
  }
}

// 将 blob 保存到目标位置：优先目录句柄，其次保存文件对话框，最后触发浏览器下载
async function saveBlobToTarget(blob, filename, dirHandle) {
  // 1) 目录句柄
  if (dirHandle && dirHandle.getFileHandle) {
    try {
      // 某些浏览器需要显式申请权限
      if (dirHandle.requestPermission && dirHandle.queryPermission) {
        const st = await dirHandle.queryPermission({ mode: 'readwrite' })
        if (st !== 'granted') {
          const r = await dirHandle.requestPermission({ mode: 'readwrite' })
          if (r !== 'granted') throw new Error('permission denied')
        }
      }
      const fileHandle = await dirHandle.getFileHandle(filename, { create: true })
      const writable = await fileHandle.createWritable()
      await writable.write(blob)
      await writable.close()
      return true
    } catch (e) {
      // 继续尝试其他方式
      console.warn('save via directory handle failed:', e)
    }
  }
  // 2) 保存文件对话框（更通用）
  if ('showSaveFilePicker' in window) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{ description: 'ZIP archive', accept: { 'application/zip': ['.zip'] } }]
      })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return true
    } catch (e) {
      console.warn('save via showSaveFilePicker failed:', e)
    }
  }
  // 3) 退化为浏览器下载
  try {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    return true
  } catch (e) {
    console.warn('fallback download failed:', e)
    return false
  }
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

const parseNames = (row) => {
  try {
    if (!row || !row.fileNames) return []
    if (Array.isArray(row.fileNames)) return row.fileNames
    if (typeof row.fileNames === 'string') {
      const s = row.fileNames.trim()
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

// 精确本地时间（YYYY-MM-DD HH:mm:ss，本地时区）
const formatDateTimeLocal = (ms) => {
  if (ms == null) return ''
  const d = new Date(Number(ms))
  if (isNaN(d.getTime())) return ''
  const y = d.getFullYear()
  const M = String(d.getMonth()+1).padStart(2,'0')
  const D = String(d.getDate()).padStart(2,'0')
  const h = String(d.getHours()).padStart(2,'0')
  const m = String(d.getMinutes()).padStart(2,'0')
  const s = String(d.getSeconds()).padStart(2,'0')
  return `${y}-${M}-${D} ${h}:${m}:${s}`
}

// 预签名有效期选择
const presignDialogVisible = ref(false)
const presignTargetUrl = ref('')
const presignTargetName = ref('')
const presignAction = ref('download') // 'download' | 'copy'
const presignExpire = ref(300) // 默认 5 分钟
const presignLoading = ref(false)

const presignOptions = [
  { label: '5 分钟', value: 5 * 60 },
  { label: '10 分钟', value: 10 * 60 },
  { label: '30 分钟', value: 30 * 60 },
  { label: '1 天', value: 24 * 60 * 60 },
  { label: '7 天', value: 7 * 24 * 60 * 60 }
]

// 直接下载：使用默认有效期（例如 5 分钟）生成预签名并立即打开
const download = async (u, name) => {
  if (!u) return
  try {
    const { data } = await api.adminPresignedUrl(projectId, u, 5 * 60, true)
    const url = data?.url || data
    if (!url) throw new Error('未返回预签名链接')
    const a = document.createElement('a')
    a.href = url
    a.target = '_blank'
    if (name || filename(u)) a.download = name || filename(u)
    a.click()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '下载失败'
    if (typeof ElMessage !== 'undefined') ElMessage.error(msg)
  }
}

// 仅在复制时弹窗选择有效期
const openPresign = (u, name, action) => {
  if (!u) return
  presignTargetUrl.value = u
  presignTargetName.value = name || filename(u)
  presignAction.value = action || 'download'
  presignExpire.value = 300
  presignDialogVisible.value = true
}

const doPresign = async () => {
  if (!presignTargetUrl.value) return
  presignLoading.value = true
  try {
    const { data } = await api.adminPresignedUrl(projectId, presignTargetUrl.value, presignExpire.value, true)
    const url = data?.url || data
    if (!url) throw new Error('未返回预签名链接')
    if (presignAction.value === 'download') {
      const a = document.createElement('a')
      a.href = url
      a.target = '_blank'
      if (presignTargetName.value) a.download = presignTargetName.value
      a.click()
    } else {
      try {
        await navigator.clipboard.writeText(url)
        if (typeof ElMessage !== 'undefined') ElMessage.success('已复制预签名链接')
      } catch {
        if (typeof ElMessage !== 'undefined') ElMessage.error('复制失败，请手动复制')
      }
    }
    presignDialogVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '获取预签名链接失败'
    if (typeof ElMessage !== 'undefined') ElMessage.error(msg)
  } finally {
    presignLoading.value = false
  }
}

// 手动上传
const manualVisible = ref(false)
// 管理端：按项目配置字段填写，构造对象；不强制 required，允许留空
const manualSubmitter = ref({})
// 管理端优先使用 allowedSubmitterKeys 作为关键字段（与“未提交名单”一致），否则回退 expectedFields
// 管理端手动上传与用户端保持一致：按 expectedUserFields 渲染（含下拉/placeholder）
const manualFields = computed(() => expectedFields.value || [])
const manualFiles = ref([])
const onManualFileChange = (file, fileList) => { manualFiles.value = fileList.map(f => f.raw).filter(Boolean) }
const doManualUpload = async () => {
  try {
    // 构造提交者对象：移除 undefined，仅保留非空字符串或有值的字段
    const sub = {}
    for (const f of manualFields.value || []) {
      const k = f?.key
      if (!k) continue
      const v = manualSubmitter.value?.[k]
      if (v === undefined || v === null) continue
      const s = typeof v === 'string' ? v.trim() : v
      if (s === '') continue
      sub[k] = s
    }
    await api.adminManualUpload(projectId, sub, manualFiles.value, { onUploadProgress: () => {} })
    if (typeof ElMessage !== 'undefined') ElMessage.success('上传成功')
    manualVisible.value = false
    manualFiles.value = []
    manualSubmitter.value = {}
    load()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '上传失败'
    if (typeof ElMessage !== 'undefined') ElMessage.error(msg)
  }
}

// 删除该用户所有提交（按字段=值，限定本项目）
const delVisible = ref(false)
const delInput = ref('')
const delFieldKey = ref('')
const delFieldValue = ref('')
let delTargetRow = null
const openDelete = (row) => {
  delTargetRow = row
  // 优先 queryFieldKey，其次当前筛选字段，再其次从提交者中取第一个字段
  const m = parseSubmitter(row)
  const prefKey = project.value?.queryFieldKey || (filterKey.value || Object.keys(m||{})[0] || '')
  delFieldKey.value = prefKey
  delFieldValue.value = prefKey ? (m?.[prefKey] ?? '') : ''
  delInput.value = ''
  delVisible.value = true
}
const doDelete = async () => {
  try {
    await api.adminDeleteSubmissionsByField({ fieldKey: delFieldKey.value, fieldValue: String(delFieldValue.value||''), projectId })
    if (typeof ElMessage !== 'undefined') ElMessage.success('删除完成')
    delVisible.value = false
    load()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '删除失败'
    if (typeof ElMessage !== 'undefined') ElMessage.error(msg)
  }
}

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
    const latestNames = parseNames(latest)
    const latestIdx = latestUrls && latestUrls.length ? (latestUrls.length - 1) : -1
    const latestUrl = latestIdx >= 0 ? latestUrls[latestIdx] : ''
    const latestName = latestIdx >= 0 ? (latestNames[latestIdx] || filename(latestUrl)) : ''
    groups.push({
      id: latest.id,
      submitCount: latest.submitCount,
      submitterInfo: latest.submitterInfo,
      submitterFingerprint: k,
      latestUrl,
      latestName,
      latestCreatedAt: latest.createdAt,
      ipAddress: latest.ipAddress,
      osName: latest.osName,
      browserName: latest.browserName,
      deviceType: latest.deviceType,
      createdAt: latest.createdAt,
      versions: list.map(s => {
        const urls = parseUrls(s)
        const names = parseNames(s)
        const files = urls.map((u, i) => ({ url: u, name: names[i] || filename(u) }))
        return { id: s.id, createdAt: s.createdAt, files }
      })
    })
  }
  return groups
})

// 是否存在按条件过滤后的结果，用于控制“按条件打包”按钮可用性
const hasFilteredResult = computed(() => {
  if (!filterKey.value || !filterValue.value) return false
  return groupedContent.value.length > 0
})

// 未提交名单
const missingVisible = ref(false)
const missing = ref({ enabled: false, keys: [], missing: [], message: '' })
const showMissing = async () => {
  try {
    const { data } = await api.adminMissingAllowed(projectId)
    missing.value = data || { enabled: false, message: '未配置允许提交名单' }
    if (!missing.value.enabled) {
      ElMessage.warning(missing.value.message || '未配置允许提交名单')
      return
    }
    missingVisible.value = true
  } catch (e) { ElMessage.error('获取未提交名单失败') }
}
const downloadMissing = async () => {
  try {
    const res = await api.adminDownloadMissingAllowedCsv(projectId)
    const blob = new Blob([res.data], { type: 'text/csv;charset=utf-8;' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `project-${projectId}-missing.csv`
    link.click()
    URL.revokeObjectURL(link.href)
  } catch (e) { ElMessage.error('下载失败') }
}

const isOverdue = (groupRow) => {
  try {
    const end = project.value?.endAt
    if (!end) return false
    return new Date(groupRow.latestCreatedAt) > new Date(end)
  } catch { return false }
}

// 时间线状态样式
const getVersionStatusClass = (version, index, totalVersions) => {
  if (index === 0) return 'status-latest'
  if (index < totalVersions - 1) return 'status-middle'
  return 'status-earliest'
}

const getVersionTitle = (index, totalVersions) => {
  if (index === 0) return '最新提交'
  if (index === totalVersions - 1) return '初始提交'
  return `第 ${totalVersions - index} 次提交`
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
/* 全屏布局 - 考虑 header 高度 */
.admin-submissions-fullscreen {
  height: calc(100vh - 64px - 2px);
  padding: 0;
  overflow: hidden;
  box-sizing: border-box;
}

.submissions-card {
  height: 99.6%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: none;
  box-shadow: none;
  background: transparent;
}

.submissions-card :deep(.el-card__header) {
  padding: 20px 0;
  flex-shrink: 0;
  border: none;
  background: transparent;
}

.submissions-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  background: #ffffff;
  border-radius: 8px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  font-size: 20px;
  font-weight: 600;
  color: #111827;
}

/* 突出显示当前项目名称 */
.project-tag {
  margin-left: 6px;
  background-color: #eef2ff; /* 柔和高亮背景 */
  color: #3730a3;            /* 深蓝紫文字 */
  border-color: #c7d2fe;     /* 边框与背景协调 */
  font-weight: 600;
}

/* 筛选区域 */
.filter-section {
  flex-shrink: 0;
  margin-bottom: 0;
  padding: 24px 24px 20px;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffff;
}

/* 表格容器 */
.table-container {
  flex: 1;
  overflow: hidden;
  min-height: 0;
  background: #ffffff;
}

/* 表格样式优化 - 参考图片风格 */
.table-container :deep(.el-table) {
  background: transparent;
  font-size: 14px;
}

/* 移除外边框 */
.table-container :deep(.el-table::before),
.table-container :deep(.el-table::after) {
  display: none;
}

.table-container :deep(.el-table td.el-table__cell),
.table-container :deep(.el-table th.el-table__cell.is-leaf) {
  border: none;
}

/* 表头样式 */
.table-container :deep(.el-table__header-wrapper) {
  background: #ffffff;
}

.table-container :deep(.el-table th.el-table__cell) {
  background: #ffffff;
  color: #111827;
  font-weight: 500;
  font-size: 13px;
  padding: 16px 0;
  border-bottom: 1px solid #e5e7eb;
}

/* 表格行样式 */
.table-container :deep(.el-table__row) {
  background: #ffffff;
}

.table-container :deep(.el-table tbody tr:hover > td) {
  background-color: #f9fafb !important;
}

.table-container :deep(.el-table td.el-table__cell) {
  padding: 20px 0;
  color: #6b7280;
  border-bottom: 1px solid #f3f4f6;
}

/* 展开行样式 */
.table-container :deep(.el-table__expanded-cell) {
  padding: 0;
  background: #fafafa;
  border-bottom: 1px solid #e5e7eb;
}

/* 展开图标样式 */
.table-container :deep(.el-table__expand-icon) {
  color: #6b7280;
}

.table-container :deep(.el-table__expand-icon .el-icon) {
  font-size: 14px;
}

/* 链接样式 */
.file-link {
  color: #6366f1;
  text-decoration: none;
  transition: color 0.2s;
  font-weight: 500;
}

.file-link:hover {
  color: #4f46e5;
  text-decoration: underline;
}

/* 按钮样式优化 */
:deep(.el-button) {
  border-radius: 6px;
  font-weight: 500;
  padding: 8px 16px;
}

:deep(.el-button--primary) {
  background-color: #6366f1;
  border-color: #6366f1;
}

:deep(.el-button--primary:hover) {
  background-color: #4f46e5;
  border-color: #4f46e5;
}

/* 时间线样式 */
.timeline-container {
  padding: 24px 32px;
  /* 使用表格的滚动条，避免与内层滚动条重叠 */
  max-height: unset;
  overflow: visible;
  box-sizing: border-box;
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

.timeline-icon.status-latest { background-color: #10b981; }
.timeline-icon.status-middle { background-color: #3b82f6; }
.timeline-icon.status-earliest { background-color: #9ca3af; }

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

.version-content { flex: 1; min-width: 0; }

.version-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.version-title-wrapper { display: flex; align-items: center; gap: 8px; }
.version-title { font-size: 14px; font-weight: 500; color: #111827; }
.latest-badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 9999px; font-size: 12px; font-weight: 500; background-color: #dcfce7; color: #166534; }
.version-time { font-size: 14px; color: #6b7280; }

.file-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.file-item { display: flex; align-items: center; justify-content: space-between; padding: 12px; background-color: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; transition: all 0.2s; }
.file-item:hover { background-color: #f9fafb; border-color: #d1d5db; }
.file-info { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.file-icon { flex-shrink: 0; }
.file-icon-svg { width: 20px; height: 20px; color: #9ca3af; }
.file-name { font-size: 14px; color: #111827; cursor: pointer; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; transition: color 0.2s; }
.file-name:hover { color: #6366f1; }
.file-actions { display: flex; align-items: center; gap: 8px; }
.action-btn { display: inline-flex; align-items: center; padding: 6px 12px; border: none; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.2s; background: transparent; }
.action-btn:focus { outline: none; }
.action-btn-primary { color: #6366f1; }
.action-btn-primary:hover { color: #4f46e5; background-color: #eef2ff; }
.action-btn-secondary { color: #6b7280; }
.action-btn-secondary:hover { color: #4b5563; background-color: #f3f4f6; }
.action-icon { width: 14px; height: 14px; margin-right: 4px; }
.no-files { margin-top: 8px; font-size: 14px; color: #9ca3af; font-style: italic; }

/* 帮助问号图标 */
.help-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-left: 6px;
  border-radius: 50%;
  border: 1px solid #e5e7eb;
  color: #6b7280;
  cursor: help;
  user-select: none;
}
.help-icon:hover {
  background-color: #f3f4f6;
  color: #374151;
}

/* 过滤工具条居中 */
.filter-form {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 16px;
}
.filter-form .el-form-item {
  margin-bottom: 0;
}

/* 按条件打包按钮：改为白色风格 */
.cond-zip-btn {
  background-color: #ffffff;
  border-color: #e5e7eb;
  color: #374151;
}
.cond-zip-btn:hover:not(.is-disabled) {
  background-color: #f9fafb;
  border-color: #d1d5db;
  color: #111827;
}
/* 禁用态：置灰，禁止 hover 与位移 */
.cond-zip-btn.is-disabled,
.cond-zip-btn[disabled] {
  background-color: #f3f4f6 !important;
  border-color: #e5e7eb !important;
  color: #9ca3af !important;
  cursor: not-allowed !important;
  box-shadow: none !important;
  transform: none !important;
  pointer-events: none; /* 阻止 hover 视觉反馈 */
}
.cond-zip-btn.is-disabled:hover,
.cond-zip-btn[disabled]:hover {
  background-color: #f3f4f6 !important;
  border-color: #e5e7eb !important;
  color: #9ca3af !important;
  box-shadow: none !important;
  transform: none !important;
}

.project-name-highlight {
  background: linear-gradient(180deg, transparent 60%, #fef08a 60%);
  color: #111827;
  font-weight: 700;
  font-size: 21px;
  padding: 0 4px;
}
</style>
