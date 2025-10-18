<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="card-header">
        <span>提交 - {{ project?.name || '' }}</span>
      </div>
    </template>

    <div v-if="project">
      <el-alert v-if="project.offline" type="warning" show-icon title="项目已下线，无法提交" />
      <el-alert v-else-if="isExpired" type="warning" show-icon :title="`项目已过期，无法提交（截止时间：${endAtText}）`" />
      <div v-else style="margin-bottom: 10px; color: var(--el-text-color-secondary);">
        截止时间：{{ endAtText }}
      </div>

      <el-form :model="submitter" label-width="120px" style="max-width: 800px; margin-top: 12px;">
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
            multiple
            :auto-upload="false"
            :on-change="onFileChange"
            :file-list="fileList"
            :limit="10"
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
            <el-button type="primary" :disabled="project.offline || isExpired" @click="submit">提交</el-button>
            <el-button @click="$router.back()">返回</el-button>
          </el-space>
        </el-form-item>
      </el-form>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

const route = useRoute()
const id = route.params.id
const loading = ref(false)
const project = ref(null)
const expectedFields = ref([])
const submitter = ref({})
const files = ref([])
const fileList = ref([])

const sizeLimitText = computed(()=>{
  if (!project.value || !project.value.fileSizeLimitBytes) return '不限制'
  const n = project.value.fileSizeLimitBytes
  if (n > 1024*1024) return (n/1024/1024).toFixed(1)+ ' MB'
  if (n > 1024) return (n/1024).toFixed(1)+ ' KB'
  return n + ' B'
})

const isExpired = computed(() => {
  if (!project.value) return false
  if (project.value.expired === true) return true
  if (project.value.endAt) return Date.now() > Number(project.value.endAt)
  return false
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
    const { data } = await api.getProject(id)
    project.value = data
    expectedFields.value = Array.isArray(data.expectedUserFields) ? data.expectedUserFields : []
  } finally { loading.value = false }
}
onMounted(load)

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
  try {
    const { data } = await api.submit(id, submitter.value, files.value)
    ElMessage.success('提交成功')
    fileList.value = []
    files.value = []
  } catch (e) {
    const msg = e?.response?.data?.message || '提交失败'
    ElMessage.error(msg)
  }
}
</script>

<style scoped>
.card-header{ display:flex; align-items:center; justify-content:space-between }
</style>
