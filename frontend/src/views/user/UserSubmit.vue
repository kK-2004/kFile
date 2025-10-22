<template>
  <el-card v-loading="loading || submitting" :element-loading-text="submitting ? '正在提交，请稍候…' : '正在加载…'">
    <template #header>
      <div class="card-header">
        <span>{{ headerTitle }}</span>
      </div>
    </template>

    <div v-if="project">
      <div style="margin-bottom: 10px; display:flex; gap:8px; align-items:center;">
        <el-button v-if="mode==='submit'" size="small" @click="switchToStatus">查询提交状态</el-button>
        <el-button v-else size="small" @click="switchToSubmit">返回提交</el-button>
      </div>

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
          <el-upload
            :multiple="!!project.allowMultiFiles"
            :auto-upload="false"
            :on-change="onFileChange"
            :file-list="fileList"
            :limit="project.allowMultiFiles ? 10 : 1"
            drag>
            <i class="el-icon-upload"></i>
            <div class="el-upload__text">拖拽或 <em>点击上传</em></div>
            <template #tip>
              <div class="el-upload__tip">
                <div>允许的类型：{{ (project.allowedFileTypes||[]).join(', ') || '不限' }}</div>
                <div>大小上限：{{ sizeLimitText }}</div>
              </div>
            </template>
          </el-upload>
        </el-form-item>

        <el-form-item>
          <el-space>
            <el-button type="primary" :disabled="submitting || project.offline || (isPastDeadline && !project.allowOverdue)" @click="submit">提交</el-button>
            <el-button @click="$router.back()">返回</el-button>
          </el-space>
        </el-form-item>
        </el-form>
      </template>
    </div>
  </el-card>
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
  submitting.value = true
  try {
    const { data } = await api.submit(id, submitter.value, files.value)
    ElMessage.success('提交成功')
    fileList.value = []
    files.value = []
    // 刷新最近提交状态
    await queryStatus()
  } catch (e) {
    const msg = e?.response?.data?.message || '提交失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
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
