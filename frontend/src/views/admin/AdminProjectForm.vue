<template>
  <div class="project-form-container">
    <el-card class="project-form-card">
      <template #header>
        <div class="card-header">
          <div class="header-title">
            <el-icon class="header-icon">
              <Document />
            </el-icon>
            <span>{{ isEdit ? '编辑项目' : '新建项目' }}</span>
          </div>
          <div class="header-actions">
            <el-tag v-if="isEdit" type="info" size="small">编辑模式</el-tag>
            <el-tag v-else type="success" size="small">新建模式</el-tag>
          </div>
        </div>
      </template>

      <el-form :model="form" label-width="140px" class="project-form">
        <!-- 基本信息与设置 -->
        <div class="form-section">
          <div class="section-title">
            <el-icon><Setting /></el-icon>
            <span>基本信息与设置</span>
          </div>

          <el-form-item label="项目名称" class="required-field">
            <el-input
                v-model="form.name"
                placeholder="请输入项目名称"
                class="form-input"
            />
          </el-form-item>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="开始时间">
                <el-date-picker
                    v-model="form.startAt"
                    type="datetime"
                    placeholder="选择开始时间"
                    value-format="x"
                    class="form-input"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="截止时间">
                <el-date-picker
                    v-model="form.endAt"
                    type="datetime"
                    placeholder="选择截止时间"
                    value-format="x"
                    class="form-input"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="8">
              <el-form-item label="可重复提交">
                <div class="switch-wrapper">
                  <el-switch
                      v-model="form.allowResubmit"
                      :active-text="form.allowResubmit ? '允许' : '禁止'"
                      inline-prompt
                  />
                  <span class="switch-desc">{{ form.allowResubmit ? '允许多次提交' : '只能提交一次' }}</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="允许多文件">
                <div class="switch-wrapper">
                  <el-switch
                      v-model="form.allowMultiFiles"
                      :active-text="form.allowMultiFiles ? '允许' : '禁止'"
                      inline-prompt
                  />
                  <span class="switch-desc">{{ form.allowMultiFiles ? '一次可上传多个文件' : '一次仅允许一个文件' }}</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="允许逾期提交">
                <div class="switch-wrapper">
                  <el-switch
                      v-model="form.allowOverdue"
                      :active-text="form.allowOverdue ? '允许' : '禁止'"
                      inline-prompt
                  />
                  <span class="switch-desc">{{ form.allowOverdue ? '到期后仍可提交（将标记为逾期）' : '到期后不可提交' }}</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="项目状态">
                <div class="switch-wrapper">
                  <el-switch
                      v-model="form.offline"
                      :active-text="form.offline ? '已下线' : '在线'"
                      :active-color="form.offline ? '#f56c6c' : '#67c23a'"
                      inline-prompt
                  />
                  <span class="switch-desc">{{ form.offline ? '已下线' : '正常运行' }}</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="文件大小上限">
                <div class="size-input-wrapper">
                  <el-input
                      v-model.number="fileSizeLimitMB"
                      type="number"
                      placeholder="如: 100"
                      class="size-input"
                  />
                  <span class="size-unit">MB</span>
                </div>
                <div class="field-hint">留空不限制</div>
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="文件扩展名白名单">
            <el-select
                v-model="allowedTypes"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="选择或输入文件扩展名 (如: pdf, doc, zip)"
                class="form-input"
            >
              <el-option v-for="t in typeSelectable" :key="t" :value="t" :label="`.${t}`" />
            </el-select>
            <div class="field-hint">不设置则允许所有文件类型</div>
          </el-form-item>
        </div>

        <!-- 用户字段配置 -->
        <div class="form-section">
          <div class="section-title">
            <el-icon><InfoFilled /></el-icon>
            <span>用户端提交状态提示</span>
          </div>
          <el-row :gutter="20">
            <el-col :span="8">
              <el-form-item label="提示类型">
                <el-select v-model="form.userSubmitStatusType" placeholder="选择标签类型">
                  <el-option label="Info" value="info" />
                  <el-option label="Success" value="success" />
                  <el-option label="Warning" value="warning" />
                  <el-option label="Danger" value="danger" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="提示文案">
                <el-input v-model="form.userSubmitStatusText" placeholder="如：请先填写学号后再提交" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="查询字段（用户端查询状态使用）">
                <el-select v-model="form.queryFieldKey" placeholder="请选择一个期望字段">
                  <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
        </div>
        <div class="form-section">
          <div class="section-title">
            <el-icon><User /></el-icon>
            <span>期望用户字段</span>
          </div>

          <div class="table-container">
            <div class="table-header">
              <el-button
                  size="default"
                  type="primary"
                  @click="addField"
                  :icon="Plus"
              >
                新增字段
              </el-button>
              <div class="table-hint">
                <el-icon><InfoFilled /></el-icon>
                <span>拖拽 ☰ 图标可调整字段顺序</span>
              </div>
            </div>

            <el-table
                :data="expectedFields"
                size="default"
                class="fields-table"
                ref="fieldsTable"
                empty-text="暂无字段，点击上方按钮添加"
            >
              <el-table-column label="排序" width="80" align="center">
                <template #default>
                  <span class="drag-handle" title="拖拽排序">☰</span>
                </template>
              </el-table-column>
              <el-table-column label="字段标识 (Key)" min-width="180">
                <template #default="{ row }">
                  <el-input
                      v-model="row.key"
                      placeholder="如: studentNo"
                      size="small"
                  />
                </template>
              </el-table-column>
              <el-table-column label="显示名称" min-width="150">
                <template #default="{ row }">
                  <el-input
                      v-model="row.label"
                      placeholder="如: 学号"
                      size="small"
                  />
                </template>
              </el-table-column>
              <el-table-column label="字段类型" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small" style="width:100%">
                    <el-option value="text" label="文本" />
                    <el-option value="select" label="下拉" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="下拉选项" min-width="220">
                <template #default="{ row }">
                  <el-select
                      v-if="row.type==='select'"
                      v-model="row._options"
                      multiple
                      allow-create
                      filterable
                      default-first-option
                      placeholder="输入后回车添加选项"
                      size="small"
                      style="width:100%"
                  >
                    <el-option v-for="opt in row._options" :key="opt" :value="opt" :label="opt" />
                  </el-select>
                  <span v-else class="na-text">—</span>
                </template>
              </el-table-column>
              <el-table-column label="占位说明" min-width="160">
                <template #default="{ row }">
                  <el-input
                      v-model="row.placeholder"
                      placeholder="提示文字"
                      size="small"
                  />
                </template>
              </el-table-column>
              <el-table-column label="必填" width="80" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.required" size="small"/>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" align="center">
                <template #default="{ $index }">
                  <el-button
                      size="small"
                      type="danger"
                      text
                      @click="removeField($index)"
                      :icon="Delete"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 路径层级配置 -->
        <div class="form-section">
          <div class="section-title">
            <el-icon><FolderOpened /></el-icon>
            <span>上传路径层级</span>
          </div>

          <div class="table-container">
            <div class="table-header">
              <el-button
                  size="default"
                  @click="addSeg"
                  :icon="Plus"
              >
                新增层级
              </el-button>
              <div class="table-hint">
                <el-icon><InfoFilled /></el-icon>
                <span>配置文件上传的目录结构，可多级且可排序</span>
              </div>
            </div>

            <el-table
                :data="pathSegments"
                size="default"
                class="segments-table"
                ref="segmentsTable"
                empty-text="暂无层级，点击上方按钮添加"
            >
              <el-table-column label="排序" width="80" align="center">
                <template #default>
                  <span class="drag-handle" title="拖拽排序">☰</span>
                </template>
              </el-table-column>
              <el-table-column label="层级" width="80" align="center">
                <template #default="{ $index }">
                  <el-tag size="small" type="info">{{ $index + 1 }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="数据来源" min-width="300">
                <template #default="{ row }">
                  <el-select
                      v-model="row.value"
                      placeholder="选择: 项目名称 或 期望字段"
                      size="small"
                      style="width:100%"
                  >
                    <el-option :value="'$project'" label="项目名称" />
                    <el-option
                        v-for="f in expectedFields"
                        :key="f.key"
                        :value="f.key"
                        :label="`${f.label} (${f.key})`"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="center">
                <template #default="{ $index }">
                  <el-button
                      type="danger"
                      size="small"
                      @click="removeSeg($index)"
                      :icon="Delete"
                  >删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div class="form-actions">
          <el-button
              type="primary"
              size="large"
              @click="save"
              :loading="saving"
              :icon="Check"
          >
            {{ isEdit ? '保存修改' : '创建项目' }}
          </el-button>
          <el-button
              size="large"
              @click="$router.back()"
              :icon="Back"
          >
            返回
          </el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import api from '../../api'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { Document, Setting, User, FolderOpened, Plus, Delete, InfoFilled, Check, Back } from '@element-plus/icons-vue'

const route = useRoute();
const router = useRouter();
const isEdit = computed(()=>!!route.params.id)
const saving = ref(false)

const form = ref({
  name: '',
  allowResubmit: true,
  allowMultiFiles: true,
  allowOverdue: false,
  userSubmitStatusType: 'info',
  userSubmitStatusText: '',
  queryFieldKey: '',
  fileSizeLimitBytes: null,
  startAt: null,
  endAt: null,
  offline: false
})
const allowedTypes = ref([])
const fileSizeLimitMB = ref(null)
const typeOptions = ['doc','docx','zip','rar','7z','pdf','txt']
const typeSelectable = computed(()=>{
  const set = new Set([ ...typeOptions, ...allowedTypes.value.filter(Boolean) ])
  return Array.from(set)
})
const expectedFields = ref([])
const pathSegments = ref([]) // 例如: ['$project','studentNo']
const fieldsTable = ref()
const segmentsTable = ref()

const load = async () => {
  if (!isEdit.value) return
  const { data } = await api.getProject(route.params.id)
  form.value = {
    id: data.id,
    name: data.name,
    allowResubmit: data.allowResubmit,
    allowMultiFiles: data.allowMultiFiles != null ? data.allowMultiFiles : true,
    allowOverdue: data.allowOverdue || false,
    userSubmitStatusType: data.userSubmitStatusType || 'info',
    userSubmitStatusText: data.userSubmitStatusText || '',
    queryFieldKey: data.queryFieldKey || '',
    fileSizeLimitBytes: data.fileSizeLimitBytes,
    startAt: data.startAt,
    endAt: data.endAt,
    offline: data.offline || false
  }
  allowedTypes.value = data.allowedFileTypes || []
  expectedFields.value = Array.isArray(data.expectedUserFields) ? data.expectedUserFields.map(f=>({
    key: f.key,
    label: f.label,
    placeholder: f.placeholder || '',
    required: !!f.required,
    type: f.type || 'text',
    _options: Array.isArray(f.options) ? f.options : []
  })) : []
  form.value.pathFieldKey = data.pathFieldKey || ''
  pathSegments.value = Array.isArray(data.pathSegments) ? data.pathSegments.map(v=>({value:v}))
      : (data.pathFieldKey ? [{value:data.pathFieldKey}] : [{value:'$project'}])
  if (data.fileSizeLimitBytes && Number.isFinite(data.fileSizeLimitBytes)) {
    fileSizeLimitMB.value = +(data.fileSizeLimitBytes / (1024 * 1024)).toFixed(2)
  } else {
    fileSizeLimitMB.value = null
  }
  // 绑定拖拽（初次加载后）
  bindRowDrag()
  bindSegDrag()
}
onMounted(load)
watch(expectedFields, () => bindRowDrag(), { deep: true })
watch(pathSegments, () => bindSegDrag(), { deep: true })

const save = async () => {
  if (saving.value) return
  saving.value = true

  try {
    const payload = { ...form.value, allowedFileTypes: allowedTypes.value }
    // ensure numeric timestamps or null
    payload.startAt = payload.startAt ? Number(payload.startAt) : null
    payload.endAt = payload.endAt ? Number(payload.endAt) : null
    // 校验 expectedFields
    const keys = new Set()
    for (const f of expectedFields.value) {
      if (!f.key || !f.label) { throw new Error('期望字段的 Key 和 显示名称 不能为空') }
      if (keys.has(f.key)) { throw new Error('期望字段 Key 不可重复: ' + f.key) }
      keys.add(f.key)
    }
    // 映射字段（包含下拉选项）
    payload.expectedUserFields = expectedFields.value.map(f=>({
      key: f.key,
      label: f.label,
      placeholder: f.placeholder || '',
      required: !!f.required,
      type: f.type || 'text',
      options: f.type === 'select' ? (Array.isArray(f._options) ? f._options : []) : undefined
    }))
    // 若为下拉类型，至少一个选项
    for (const f of payload.expectedUserFields) {
      if ((f.type||'text') === 'select') {
        if (!Array.isArray(f.options) || f.options.length === 0) {
          throw new Error(`字段 ${f.label||f.key} 的下拉选项不能为空`)
        }
      }
    }
    // 如果选择了路径字段，但在期望字段里不存在，阻止保存
    if (payload.pathFieldKey && !expectedFields.value.some(f => f.key === payload.pathFieldKey)) {
      throw new Error('上传路径字段必须在期望字段列表中')
    }
    // 路径层级
    const segs = pathSegments.value.map(s=>s.value).filter(Boolean)
    if (segs.length === 0) throw new Error('请至少配置一个上传路径层级')
    // 校验：除 $project 外，其它必须存在于期望字段
    for (const s of segs) {
      if (s !== '$project' && !expectedFields.value.some(f => f.key === s)) {
        throw new Error('路径层级中包含未知字段: ' + s)
      }
    }
    payload.pathSegments = segs
    // 将 MB 转换为字节
    if (fileSizeLimitMB.value != null && fileSizeLimitMB.value !== '' && !Number.isNaN(fileSizeLimitMB.value)) {
      const bytes = Math.round(Number(fileSizeLimitMB.value) * 1024 * 1024)
      payload.fileSizeLimitBytes = bytes > 0 ? bytes : null
    } else {
      payload.fileSizeLimitBytes = null
    }
    if (isEdit.value) await api.updateProject(route.params.id, payload)
    else await api.createProject(payload)
    ElMessage.success('已保存')
    router.push('/admin/projects')
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败，请检查表单'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

const addField = () => {
  expectedFields.value.push({
    key: '',
    label: '',
    required: false,
    placeholder: '',
    type: 'text',
    _options: []
  })
}
const removeField = (idx) => { expectedFields.value.splice(idx, 1); bindRowDrag() }
const addSeg = () => { pathSegments.value.push({ value: '$project' }); bindSegDrag() }
const removeSeg = (idx) => { pathSegments.value.splice(idx, 1); bindSegDrag() }
// 上移/下移由拖拽排序代替
const auth = useAuthStore()
onMounted(()=>{ if (!auth.loaded) auth.loadMe() })

function bindRowDrag() {
  nextTick(() => {
    const tableEl = fieldsTable.value?.$el
    const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
    if (!tbody) return
    const rows = tbody.querySelectorAll('tr')
    rows.forEach((tr, idx) => {
      tr.setAttribute('draggable', 'true')
      tr.style.cursor = 'move'
      tr.dataset.index = String(idx)
      tr.ondragstart = (ev) => {
        tr.classList.add('dragging')
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragend = () => {
        tr.classList.remove('dragging')
      }
      tr.ondragover = (ev) => {
        ev.preventDefault()
        tr.classList.add('drag-over')
      }
      tr.ondragleave = () => {
        tr.classList.remove('drag-over')
      }
      tr.ondrop = (ev) => {
        ev.preventDefault()
        tr.classList.remove('drag-over')
        const fromTxt = ev.dataTransfer?.getData('text/plain') || '-1'
        const from = parseInt(fromTxt)
        const to = parseInt(tr.dataset.index || '-1')
        if (!Number.isNaN(from) && !Number.isNaN(to) && from >= 0 && to >= 0 && from !== to) {
          const item = expectedFields.value.splice(from, 1)[0]
          expectedFields.value.splice(to, 0, item)
          bindRowDrag()
        }
      }
    })
  })
}

function bindSegDrag() {
  nextTick(() => {
    const tableEl = segmentsTable.value?.$el
    const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
    if (!tbody) return
    const rows = tbody.querySelectorAll('tr')
    rows.forEach((tr, idx) => {
      tr.setAttribute('draggable', 'true')
      tr.style.cursor = 'move'
      tr.dataset.index = String(idx)
      tr.ondragstart = (ev) => {
        tr.classList.add('dragging')
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragend = () => {
        tr.classList.remove('dragging')
      }
      tr.ondragover = (ev) => {
        ev.preventDefault()
        tr.classList.add('drag-over')
      }
      tr.ondragleave = () => {
        tr.classList.remove('drag-over')
      }
      tr.ondrop = (ev) => {
        ev.preventDefault()
        tr.classList.remove('drag-over')
        const fromTxt = ev.dataTransfer?.getData('text/plain') || '-1'
        const from = parseInt(fromTxt)
        const to = parseInt(tr.dataset.index || '-1')
        if (!Number.isNaN(from) && !Number.isNaN(to) && from >= 0 && to >= 0 && from !== to) {
          const item = pathSegments.value.splice(from, 1)[0]
          pathSegments.value.splice(to, 0, item)
          bindSegDrag()
        }
      }
    })
  })
}
</script>

<style scoped>
/* Container and card styling */
.project-form-container {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px;
}

.project-form-card {
  border-radius: 8px;
  border: 1px solid var(--kf-border-color);
  box-shadow: var(--kf-box-shadow);
  overflow: hidden;
}

/* Header styling */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
}

.header-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: bold;
  color: var(--kf-text-primary);
}

.header-icon {
  font-size: 20px;
  color: var(--kf-primary);
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* Form styling */
.project-form {
  padding: 20px 0;
}

.form-section {
  margin-bottom: 24px;
  padding: 20px;
  background: var(--kf-background);
  border-radius: 8px;
  border: 1px solid var(--kf-border-color);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 16px;
  font-weight: bold;
  color: var(--kf-text-primary);
  padding-bottom: 8px;
  border-bottom: 2px solid var(--kf-primary);
}

.section-title .el-icon {
  font-size: 18px;
  color: var(--kf-primary);
}

/* Form item styling */
.required-field :deep(.el-form-item__label) {
  position: relative;
}

.required-field :deep(.el-form-item__label::before) {
  content: '*';
  color: #f56c6c;
  margin-right: 4px;
}

.form-input {
  max-width: 100%;
}

.field-hint {
  font-size: 12px;
  color: var(--kf-muted);
  margin-top: 2px;
  line-height: 1.3;
}

/* Switch styling */
.switch-wrapper {
  display: flex;
  align-items: center;
  gap: 6px;
}

.switch-desc {
  font-size: 12px;
  color: var(--kf-muted);
  white-space: nowrap;
}

/* Size input styling */
.size-input-wrapper {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 140px;
}

.size-input {
  flex: 1;
}

.size-unit {
  font-size: 13px;
  font-weight: 500;
  color: var(--kf-text);
  white-space: nowrap;
}

/* Table container styling */
.table-container {
  background: var(--kf-background);
  border-radius: 8px;
  border: 1px solid var(--kf-border-color);
  overflow: hidden;
  box-shadow: var(--kf-box-shadow);
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
}

.table-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--kf-muted);
}

/* Table styling */
.fields-table,
.segments-table {
  border: none;
}

.fields-table :deep(.el-table__header),
.segments-table :deep(.el-table__header) {
  background: var(--kf-header-bg);
}

.fields-table :deep(.el-table__header th),
.segments-table :deep(.el-table__header th) {
  background: var(--kf-header-bg);
  font-weight: 500;
  color: var(--kf-text-primary);
  border-bottom: 1px solid var(--kf-border-color);
  padding: 12px;
}

.fields-table :deep(.el-table__body td),
.segments-table :deep(.el-table__body td) {
  padding: 12px;
}

.fields-table :deep(.el-table__body tr),
.segments-table :deep(.el-table__body tr) {
  transition: all 0.2s ease;
}

.fields-table :deep(.el-table__body tr:hover),
.segments-table :deep(.el-table__body tr:hover) {
  background: var(--kf-background-light);
}

/* Drag and drop styling */
.drag-handle {
  cursor: move;
  user-select: none;
  color: var(--kf-muted);
  font-size: 14px;
  transition: all 0.2s ease;
  padding: 2px;
  border-radius: 3px;
}

.drag-handle:hover {
  color: var(--kf-primary);
  background: rgba(64, 158, 255, 0.1);
}

:deep(.dragging) {
  opacity: 0.6;
  transform: scale(0.98);
}

:deep(.drag-over) {
  background: rgba(64, 158, 255, 0.1) !important;
  border-top: 2px solid var(--kf-primary);
}

/* N/A text styling */
.na-text {
  color: var(--kf-muted);
  font-style: italic;
}

/* Action buttons styling */
.form-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  padding: 20px 0 12px;
  border-top: 1px solid var(--kf-border-color);
  margin-top: 20px;
}

.form-actions .el-button {
  min-width: 120px;
  height: 38px;
  font-weight: 500;
  border-radius: 4px;
}

.form-actions .el-button--primary {
  background-color: var(--kf-primary);
  border-color: var(--kf-primary);
}

.form-actions .el-button--primary:hover {
  background-color: var(--kf-primary-hover);
  border-color: var(--kf-primary-hover);
}

/* Responsive design */
@media (max-width: 768px) {
  .project-form-container {
    padding: 8px;
  }

  .form-section {
    padding: 12px 16px;
    margin-bottom: 16px;
  }

  .el-row .el-col {
    margin-bottom: 8px;
  }

  .switch-desc {
    display: none; /* 移动端隐藏描述文字 */
  }

  .size-input-wrapper {
    max-width: 120px;
  }

  .table-header {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start;
    padding: 10px 12px;
  }

  .form-actions {
    flex-direction: column;
    align-items: center;
    padding: 16px 0 8px;
  }

  .form-actions .el-button {
    width: 100%;
    max-width: 280px;
    height: 36px;
  }
}

/* Animation enhancements */
.el-button {
  transition: all 0.2s ease;
}

.el-button:hover {
  transform: translateY(-1px);
}

.el-form-item {
  margin-bottom: 18px;
}

.el-card :deep(.el-card__body) {
  padding: 24px;
}

/* Tag styling */
.el-tag {
  border-radius: 6px;
}

/* Button group styling */
.el-button-group .el-button {
  margin: 0;
}
</style>
