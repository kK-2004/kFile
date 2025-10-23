<template>
  <div class="admin-submissions-fullscreen">
  <el-card class="submissions-card" v-loading="loading || submitting" :element-loading-text="submitting ? '正在提交，请稍候…' : '正在加载…'">
    <template #header>
      <div class="card-header">
        <span>{{ headerTitle }}</span>
      </div>
    </template>

    <div v-if="project">
      <!-- 顶部导航按钮移除，避免与上传操作重复 -->

      <div v-if="mode==='status'" style="margin-bottom: 16px;">
        <el-form label-width="120px" style="max-width: 800px;">
          <el-form-item :label="queryLabel">
            <el-input v-model="queryValue" :placeholder="`请输入${queryLabel}`" style="max-width: 320px;" />
            <el-button style="margin-left:8px;" :disabled="!queryValue" @click="queryStatusByField">查询</el-button>
          </el-form-item>
          <el-form-item label="查询结果">
            <div v-if="latest.exists" style="display:flex; flex-direction:column; gap:6px;">
              <div>
                <el-tag size="small" :type="latest.expired ? 'warning' : 'success'">
                  {{ latest.expired ? '逾期' : '正常' }}
                </el-tag>
                <span style="margin-left:8px; color: var(--el-text-color-secondary);">
                  时间：{{ formatTimestamp(latest.createdAt) }}（第 {{ latest.submitCount }} 次）
                </span>
              </div>

              <!-- 版本链 -->
              <div class="timeline-container" v-if="Array.isArray(versions) && versions.length">
                <div class="timeline-wrapper">
                  <div v-for="(ver, idx) in versions" :key="ver.id" class="timeline-item">
                    <div v-if="idx < versions.length - 1" class="timeline-line"></div>
                    <div class="timeline-icon" :class="idx===0 ? 'status-latest' : (idx < versions.length - 1 ? 'status-middle' : 'status-earliest')">
                      <svg v-if="idx === 0" class="icon-svg" fill="currentColor" viewBox="0 0 20 20">
                        <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
                      </svg>
                      <div v-else class="timeline-dot"></div>
                    </div>
                    <div class="version-content">
                      <div class="version-header">
                        <div class="version-title-wrapper">
                          <span class="version-title">{{ idx===0 ? '最新提交' : `历史提交 ${versions.length - idx}` }}</span>
                        </div>
                        <time class="version-time">{{ formatTimestamp(ver.createdAt) }}</time>
                      </div>
                      <div class="file-list" v-if="Array.isArray(ver.fileNames) && ver.fileNames.length">
                        <div v-for="(name, i2) in ver.fileNames" :key="i2" class="file-item">
                          <div class="file-info">
                            <div class="file-icon">
                              <svg class="file-icon-svg" fill="currentColor" viewBox="0 0 20 20">
                                <path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clip-rule="evenodd" />
                              </svg>
                            </div>
                            <span class="file-name">{{ name }}</span>
                          </div>
                        </div>
                      </div>
                      <div v-else class="no-files">暂无文件</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-else style="color: var(--el-text-color-secondary);">暂无记录</div>
          </el-form-item>
        </el-form>
      </div>

      <template v-if="mode==='submit'">
        <el-alert v-if="project.offline" type="warning" show-icon title="项目已下线，无法提交" />
        <el-alert v-else-if="isPastDeadline && !project.allowOverdue" type="warning" show-icon :title="`项目已过期，无法提交（截止时间：${endAtText}）`" />
        <el-alert v-else-if="isPastDeadline && project.allowOverdue" type="warning" show-icon :title="`当前为逾期提交（截止时间：${endAtText}），该提交将标记为逾期`" />
        <el-alert v-else-if="project && project.allowResubmit === false" type="info" show-icon title="本项目每个提交者仅可提交一次，如需修改请联系管理员" />
        <el-alert v-if="!project.allowResubmit && latest.exists" type="warning" show-icon title="已存在您的提交记录，重复提交已被禁止" />
        <div v-else style="margin-bottom: 10px; color: var(--el-text-color-secondary);">截止时间：{{ endAtText }}</div>

        <el-form :model="submitter" label-width="120px" style="max-width: 800px; margin-top: 12px;">
          <div style="margin-bottom: 10px; display:flex; align-items:center; gap:8px;">
            <el-tag v-if="project.userSubmitStatusText" :type="project.userSubmitStatusType || 'info'">
              {{ project.userSubmitStatusText }}
            </el-tag>
          </div>
        <template v-for="field in expectedFields" :key="field.key">
          <el-form-item :label="field.label || field.key" :required="field.required">
            <template v-if="(field.type||'text')==='select' && Array.isArray(field.options)">
              <el-select v-model="submitter[field.key]" filterable placeholder="请选择">
                <el-option v-for="opt in field.options" :key="opt" :label="opt" :value="opt" />
              </el-select>
            </template>
            <template v-else>
              <el-input v-model="submitter[field.key]" :placeholder="field.placeholder || ''" />
            </template>
          </el-form-item>
        </template>

        <el-form-item label="选择文件">
          <div class="upload-box">
            <el-upload
              class="upload-area"
              drag
              :multiple="!!project.allowMultiFiles"
              :auto-upload="false"
              :on-change="onFileChange"
              :on-remove="onFileRemove"
              :file-list="fileList"
              :limit="uploadLimit"
              :disabled="!project.allowResubmit && latest.exists"
              :accept="acceptAttr"
            >
              <i class="el-icon-upload upload-icon"></i>
              <div class="upload-text">将文件拖拽到此处，或 <em>点击上传</em></div>
              <template #tip>
                <div class="upload-tip">
                  <div>允许类型：{{ (project.allowedFileTypes||[]).join(', ') || '不限' }}</div>
                  <div>大小上限：{{ sizeLimitText }}</div>
                </div>
              </template>
            </el-upload>
            <div class="actions">
              <el-button type="primary" :disabled="disableSubmit" @click="submit">开始上传</el-button>
              <el-button :disabled="!fileList.length" @click="clearFiles">清空</el-button>
            </div>
          </div>
        </el-form-item>

        <!-- 移除重复的提交/返回按钮，统一在上传区域下方操作 -->
        </el-form>
      </template>
    </div>
  </el-card>

  <!-- 上传中弹窗（置于页面中上） -->
  <el-dialog
    v-model="showUploadDialog"
    title="正在上传"
    width="420px"
    :show-close="false"
    :close-on-click-modal="false"
    align-center
    :top="'15vh'"
  >
    <div style="display:flex; flex-direction:column; gap:12px;">
      <div style="color: var(--el-text-color-regular); font-size: 14px;">
        {{ currentFileName ? `正在上传第 ${currentFileIndex}/${totalFilesCount} 个：` + currentFileName : '准备上传...' }}
      </div>
      <div style="display:flex; justify-content:space-between; font-size:12px; color: var(--el-text-color-secondary);">
        <span>速度：{{ speedBps ? (formatBytes(speedBps) + '/s') : '—' }}</span>
        <span>{{ formatBytes(uploadedBytes) }} / {{ formatBytes(totalBytes) }}</span>
      </div>
      <el-progress :percentage="uploadProgress" :stroke-width="16" :text-inside="true"/>
      <div style="color: var(--el-text-color-secondary); font-size: 12px;">请勿关闭页面，上传完成后将自动关闭本窗口。</div>
    </div>
  </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const id = route.params.id
const loading = ref(false)
const project = ref(null)
const expectedFields = ref([])
const submitter = ref({})
const files = ref([])
const fileList = ref([])
const latest = ref({ exists: false })
const querying = ref(false)
const submitting = ref(false)
const uploadProgress = ref(0)
const showUploadDialog = ref(false)
const currentFileName = ref('')
const currentFileIndex = ref(0)
const totalFilesCount = ref(0)
const uploadedBytes = ref(0)
const totalBytes = ref(0)
const speedBps = ref(0)

function formatBytes(n) {
  const num = Number(n||0)
  if (num < 1024) return num + ' B'
  if (num < 1024*1024) return (num/1024).toFixed(1) + ' KB'
  if (num < 1024*1024*1024) return (num/1024/1024).toFixed(1) + ' MB'
  return (num/1024/1024/1024).toFixed(2) + ' GB'
}
const auth = useAuthStore()
const isAdmin = computed(() => !!auth.user)
const mode = ref('submit') // 'submit' | 'status'
const headerTitle = computed(() => `${mode.value==='submit' ? '提交' : '查询提交状态'} - ${project.value?.name || ''}`)
const queryValue = ref('')
const queryLabel = computed(() => {
  const key = project.value?.queryFieldKey
  if (!key) return '查询字段'
  const f = (expectedFields.value||[]).find(x => x.key === key)
  return f?.label || key
})
const versions = ref([])

const sizeLimitText = computed(()=>{
  if (!project.value || !project.value.fileSizeLimitBytes) return '不限制'
  const n = project.value.fileSizeLimitBytes
  if (n > 1024*1024) return (n/1024/1024).toFixed(1)+ ' MB'
  if (n > 1024) return (n/1024).toFixed(1)+ ' KB'
  return n + ' B'
})

// 上传 UI 辅助
const uploadLimit = computed(() => project.value?.allowMultiFiles ? 10 : 1)
const acceptAttr = computed(() => {
  const list = project.value?.allowedFileTypes || []
  if (!Array.isArray(list) || list.length === 0) return ''
  // 转成 .ext,.ext2 形式
  const items = list.map(x => String(x||'').trim().toLowerCase()).filter(Boolean).map(x => x.startsWith('.') ? x : ('.' + x))
  return items.join(',')
})

const isPastDeadline = computed(() => {
  if (!project.value) return false
  if (project.value.endAt) return Date.now() > Number(project.value.endAt)
  return project.value.expired === true
})

function formatTimestamp(ms) {
  const d = new Date(Number(ms))
  if (isNaN(d.getTime())) return '无'
  const y = d.getFullYear()
  const M = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  const s = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${M}-${day} ${h}:${m}:${s}`
}

  const endAtText = computed(() => {
    if (!project.value || !project.value.endAt) return '无'
    try { return formatTimestamp(project.value.endAt) } catch { return '无' }
  })

  const disableSubmit = computed(() => {
    const p = project.value || {}
    const baseBlocked = submitting.value || p.offline || (isPastDeadline.value && !p.allowOverdue)
    const repeatBlocked = (p.allowResubmit === false) && !!latest.value?.exists
    return baseBlocked || repeatBlocked
  })

const load = async () => {
  loading.value = true
  try {
    if (!auth.loaded) await auth.loadMe()
    const { data } = await api.getProject(id)
    project.value = data
    expectedFields.value = Array.isArray(data.expectedUserFields) ? data.expectedUserFields : []
  } finally { loading.value = false }
}
onMounted(load)

const switchToStatus = () => { mode.value = 'status'; latest.value = { exists: false }; queryValue.value = '' }
const switchToSubmit = () => { mode.value = 'submit' }

const onFileChange = (file, list) => {
  fileList.value = list
  files.value = list.map(x => x.raw).filter(Boolean)
}
const onFileRemove = (file, list) => {
  fileList.value = list
  files.value = list.map(x => x.raw).filter(Boolean)
}
const removeAt = (idx) => {
  fileList.value.splice(idx, 1)
  files.value = fileList.value.map(x => x.raw).filter(Boolean)
}
const clearFiles = () => {
  fileList.value = []
  files.value = []
}

const validateFiles = () => {
  if (!project.value) return true
  const white = project.value.allowedFileTypes || []
  const sizeLimit = project.value.fileSizeLimitBytes
  for (const f of files.value) {
    const ext = (f.name.split('.').pop() || '').toLowerCase()
    if (white.length > 0 && !white.some(t => t.toLowerCase() === ext)) {
      ElMessage.error(`文件类型不允许: ${f.name}`); return false
    }
    if (sizeLimit && f.size > sizeLimit) { ElMessage.error(`文件过大: ${f.name}`); return false }
  }
  return true
}

const submit = async () => {
  if (!validateFiles()) return
  // 若不允许重复提交，先检查是否已有记录，避免用户在同一页面连续提交导致上传后失败
  try {
    if (!project.value?.allowResubmit) {
      const { data } = await api.latestStatus(id, JSON.stringify(submitter.value||{}))
      if (data?.exists) {
        ElMessage.error('该项目不允许重复提交，已存在您的最新记录')
        return
      }
    }
  } catch (e) {
    // 忽略查询失败，按原流程继续（后端仍会二次校验）
  }
  submitting.value = true
  try {
    showUploadDialog.value = true
    const metas = files.value.map(f => ({ name: f.name, type: f.type, size: f.size }))
    const total = files.value.reduce((s, f) => s + (f.size || 0), 0) || 1
    let uploadedAll = 0
    totalFilesCount.value = files.value.length
    totalBytes.value = total
    uploadedBytes.value = 0
    speedBps.value = 0
    let lastTickBytes = 0
    let lastTickTime = Date.now()
    const LARGE_THRESHOLD = 100 * 1024 * 1024 // 100MB 以上走分片
    const useMultipart = files.value.some(f => (f.size||0) >= LARGE_THRESHOLD)
    const keys = []

    if (!useMultipart) {
      // 单次 PUT 直传
      const { data } = await api.directInit(id, submitter.value, metas)
      const entries = Array.isArray(data?.entries) ? data.entries : []
      if (entries.length !== files.value.length) throw new Error('直传初始化失败')
      for (let i = 0; i < files.value.length; i++) {
        const f = files.value[i]
        const putUrl = entries[i].putUrl
        currentFileIndex.value = i + 1
        currentFileName.value = f.name
        let lastLoaded = 0
        await api.directPut(putUrl, f, (evt) => {
          const loaded = (evt?.loaded || 0)
          const overall = uploadedAll + loaded
          uploadProgress.value = Math.floor((overall / total) * 100)
          uploadedBytes.value = overall
          const now = Date.now()
          const dt = now - lastTickTime
          if (dt >= 200) {
            const dBytes = overall - lastTickBytes
            const inst = dBytes / (dt / 1000)
            speedBps.value = speedBps.value > 0 ? (0.8 * speedBps.value + 0.2 * inst) : inst
            lastTickTime = now
            lastTickBytes = overall
          }
        })
        uploadedAll += f.size || 0
        uploadProgress.value = Math.floor((uploadedAll / total) * 100)
        uploadedBytes.value = uploadedAll
      }
      keys.push(...(data.entries.map(e=>e.key)))
    } else {
      // 分片直传（稳定性更好）
      const { data } = await api.directMultipartInit(id, submitter.value, metas)
      const entries = Array.isArray(data?.entries) ? data.entries : []
      if (entries.length !== files.value.length) throw new Error('直传分片初始化失败')
      for (let i = 0; i < files.value.length; i++) {
        const f = files.value[i]
        const entry = entries[i]
        const partSize = entry.partSize || (10 * 1024 * 1024)
        currentFileIndex.value = i + 1
        currentFileName.value = f.name
        const parts = []
        let sent = 0
        const totalParts = Math.ceil((f.size||0)/partSize)
        for (let p = 0; p < totalParts; p++) {
          const start = p * partSize
          const end = Math.min(f.size, start + partSize)
          const blob = f.slice(start, end)
          const partNumber = p + 1
          const { data: sign } = await api.directMultipartSign(id, entry.key, entry.uploadId, partNumber, blob.size, f.type)
          const res = await axios.put(sign.url, blob, { headers: { 'Content-Type': f.type || 'application/octet-stream' }, timeout: 0 })
          const etag = (res.headers && (res.headers['etag'] || res.headers['ETag'])) || ''
          parts.push({ partNumber, eTag: etag.replaceAll('"','') })
          sent += blob.size
          const overall = uploadedAll + sent
          uploadProgress.value = Math.floor((overall / total) * 100)
          uploadedBytes.value = overall
          const now = Date.now()
          const dt = now - lastTickTime
          if (dt >= 200) {
            const dBytes = overall - lastTickBytes
            const inst = dBytes / (dt / 1000)
            speedBps.value = speedBps.value > 0 ? (0.8 * speedBps.value + 0.2 * inst) : inst
            lastTickTime = now
            lastTickBytes = overall
          }
        }
        await api.directMultipartComplete(id, entry.key, entry.uploadId, parts)
        uploadedAll += f.size || 0
        uploadProgress.value = Math.floor((uploadedAll / total) * 100)
        uploadedBytes.value = uploadedAll
        keys.push(entry.key)
      }
    }

    // 完成入库
    // 上传结束后进入“保存中”阶段，避免 100% 时用户误解已完成
    currentFileName.value = '正在保存...'
    await api.directComplete(id, submitter.value, keys)

    ElMessage.success('提交成功')
    // 成功后跳转到“查询状态”页，并自动按当前条件查询
    if (project.value && project.value.queryFieldKey) {
      const key = project.value.queryFieldKey
      const val = submitter.value?.[key] || ''
      mode.value = 'status'
      if (val) { queryValue.value = val; await queryStatusByField() } else { await queryStatus() }
    } else {
      mode.value = 'status'
      await queryStatus()
    }
    // 清空本地文件选择
    fileList.value = []
    files.value = []
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '提交失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
    uploadProgress.value = 0
    showUploadDialog.value = false
    currentFileName.value = ''
    currentFileIndex.value = 0
    totalFilesCount.value = 0
    uploadedBytes.value = 0
    totalBytes.value = 0
    speedBps.value = 0
  }
}

const queryStatus = async () => {
  if (!project.value) return
  querying.value = true
  try {
    const { data } = await api.latestStatus(id, JSON.stringify(submitter.value||{}))
    latest.value = {
      exists: !!data.exists,
      createdAt: data.createdAt,
      submitCount: data.submitCount,
      expired: !!data.expired,
      fileNames: Array.isArray(data.fileNames) ? data.fileNames : []
    }
    versions.value = Array.isArray(data.versions) ? data.versions : []
  } catch (e) {
    ElMessage.error('查询失败')
  } finally {
    querying.value = false
  }
}

const queryStatusByField = async () => {
  if (!project.value || !project.value.queryFieldKey || !queryValue.value) return
  querying.value = true
  try {
    const { data } = await api.latestStatus(id, { fieldValue: queryValue.value })
    latest.value = {
      exists: !!data.exists,
      createdAt: data.createdAt,
      submitCount: data.submitCount,
      expired: !!data.expired,
      fileNames: Array.isArray(data.fileNames) ? data.fileNames : []
    }
    versions.value = Array.isArray(data.versions) ? data.versions : []
  } catch (e) {
    ElMessage.error('查询失败')
  } finally {
    querying.value = false
  }
}

const download = (u) => { const a = document.createElement('a'); a.href = u; a.target = '_blank'; a.click() }
const filename = (u) => { if (!u) return ''; const q = u.split('?')[0]; const i = Math.max(q.lastIndexOf('/'), q.lastIndexOf('\\')); return i >= 0 ? q.substring(i+1) : q }
</script>

<style scoped>
.admin-submissions-fullscreen { padding: 24px; background: #f5f7fb; min-height: calc(100vh - 64px); box-sizing: border-box; }
.submissions-card { border-radius: 12px; overflow: hidden; box-shadow: 0 8px 24px rgba(0,0,0,0.06); }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background-color: var(--kf-header-bg);
  border-radius: 8px 8px 0 0;
}

.card-header span {
  font-size: 18px;
  font-weight: bold;
}

/* 表单样式优化 */
:deep(.el-form) {
  margin-top: 20px;
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-form-item__label) {
  font-weight: 500;
}

/* 警告框样式 */
:deep(.el-alert) {
  border-radius: 8px;
  margin-bottom: 15px;
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


/* 上传组件样式 */
:deep(.el-upload) {
  width: 100%;
}

:deep(.el-upload-dragger) {
  width: 100%;
  border-radius: 8px;
}

:deep(.el-upload-dragger:hover) {
  border-color: var(--kf-primary);
}

:deep(.el-upload__tip) {
  font-size: 12px;
}

/* 新增：更现代的上传布局和列表样式 */
.upload-layout { display: grid; grid-template-columns: 1.2fr 1fr; gap: 16px; align-items: start; width: 100%; }
@media (max-width: 960px) { .upload-layout { grid-template-columns: 1fr; } }
.upload-left, .upload-right { background: #ffffff; border: 1px solid #eef0f4; border-radius: 10px; padding: 14px; }
.upload-box { background: #ffffff; border: 1px solid #eef0f4; border-radius: 10px; padding: 14px; }
.upload-area { width: 100%; border: 2px dashed #e5e7eb; border-radius: 10px; padding: 30px 16px; text-align: center; transition: all .2s; }
.upload-area:hover { border-color: #6366f1; background: #fafbff; }
.upload-icon { font-size: 32px; color: #6366f1; margin-bottom: 8px; }
.upload-text { font-size: 14px; color: #6b7280; }
.upload-text em { color: #6366f1; font-style: normal; }
.upload-tip { margin-top: 8px; font-size: 12px; color: #9ca3af; }
.file-list-title { display: flex; align-items: center; gap: 8px; font-weight: 600; color: #111827; margin-bottom: 8px; }
.file-list { display: flex; flex-direction: column; gap: 8px; }
.file-item { display: flex; align-items: center; justify-content: space-between; border: 1px solid #eef0f4; border-radius: 8px; padding: 8px 10px; background: #fff; }
.file-meta { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.file-name { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #111827; }
.file-extra { font-size: 12px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 18px; border: 1px dashed #e5e7eb; border-radius: 10px; background: #fafafa; color: #6b7280; }
.empty-title { font-size: 14px; color: #374151; }
.empty-sub { font-size: 12px; }
.actions { display: flex; gap: 10px; margin-top: 10px; }

/* 选择器样式 */
:deep(.el-select) {
  width: 100%;
}

/* 输入框样式 */
:deep(.el-input__wrapper) {
  border-radius: 4px;
}
</style>
<style scoped>
.progress-wrap { margin: 8px 0; }
</style>
<style scoped>
/* 时间线样式（与后台一致的视觉） */
.timeline-container { padding: 16px 24px; overflow-y: auto; box-sizing: border-box; }
.timeline-wrapper { position: relative; }
.timeline-item { position: relative; display: flex; align-items: flex-start; padding-bottom: 16px; }
.timeline-item:last-child { padding-bottom: 0; }
.timeline-line { position: absolute; left: 16px; top: 32px; width: 1px; height: calc(100% - 8px); background-color: #e5e7eb; }
.timeline-icon { position: relative; z-index: 10; display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 50%; margin-right: 16px; flex-shrink: 0; }
.timeline-icon.status-latest { background-color: #10b981; }
.timeline-icon.status-middle { background-color: #3b82f6; }
.timeline-icon.status-earliest { background-color: #9ca3af; }
.icon-svg { width: 16px; height: 16px; color: white; }
.timeline-dot { width: 8px; height: 8px; background-color: white; border-radius: 50%; }
.version-content { flex: 1; min-width: 0; }
.version-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.version-title-wrapper { display: flex; align-items: center; gap: 8px; }
.version-title { font-size: 14px; font-weight: 500; color: #111827; }
.version-time { font-size: 14px; color: #6b7280; }
.file-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.file-item { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; background-color: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; }
.file-info { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.file-icon-svg { width: 18px; height: 18px; color: #9ca3af; }
.file-name { font-size: 14px; color: #111827; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; }
.no-files { margin-top: 8px; font-size: 14px; color: #9ca3af; font-style: italic; }
</style>
