<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>{{ isEdit ? '编辑项目' : '新建项目' }}</span>
      </div>
    </template>
    <el-form :model="form" label-width="120px" style="max-width: 1200px;" class="project-form">
      <el-form-item label="项目名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="可重复提交">
        <el-switch v-model="form.allowResubmit" />
      </el-form-item>
      <el-form-item label="文件扩展名白名单">
        <el-select v-model="allowedTypes" multiple filterable allow-create default-first-option placeholder="选择/输入扩展名">
          <el-option v-for="t in typeSelectable" :key="t" :value="t" :label="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="单文件大小上限(MB)">
        <el-input v-model.number="fileSizeLimitMB" type="number" placeholder="留空不限制" />
      </el-form-item>
      <el-form-item label="开始时间">
        <el-date-picker v-model="form.startAt" type="datetime" placeholder="可选" value-format="x" />
      </el-form-item>
      <el-form-item label="截止时间">
        <el-date-picker v-model="form.endAt" type="datetime" placeholder="可选" value-format="x" />
      </el-form-item>
      <el-form-item label="期望用户字段">
        <div style="width:100%">
          <div style="margin-bottom:8px">
            <el-button size="small" type="primary" @click="addField">新增字段</el-button>
          </div>
          <el-table :data="expectedFields" size="small" style="width:100%" ref="fieldsTable">
            <el-table-column label="排序" width="80" align="center">
              <template #default>
                <span class="drag-handle" title="拖拽排序">☰</span>
              </template>
            </el-table-column>
            <el-table-column label="Key" min-width="160">
              <template #default="{ row }"><el-input v-model="row.key" placeholder="唯一标识，如 studentNo"/></template>
            </el-table-column>
            <el-table-column label="显示名称" min-width="160">
              <template #default="{ row }"><el-input v-model="row.label" placeholder="如 学号"/></template>
            </el-table-column>
            <el-table-column label="类型" width="140">
              <template #default="{ row }">
                <el-select v-model="row.type" style="width:120px">
                  <el-option value="text" label="文本" />
                  <el-option value="select" label="下拉" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="下拉选项" min-width="240">
              <template #default="{ row }">
                <el-select v-if="row.type==='select'" v-model="row._options" multiple allow-create filterable default-first-option placeholder="输入后回车添加">
                  <el-option v-for="opt in row._options" :key="opt" :value="opt" :label="opt" />
                </el-select>
                <span v-else style="color:var(--el-text-color-secondary)">—</span>
              </template>
            </el-table-column>
            <el-table-column label="占位说明" min-width="200">
              <template #default="{ row }"><el-input v-model="row.placeholder" placeholder="可选"/></template>
            </el-table-column>
            <el-table-column label="必填" width="100" align="center">
              <template #default="{ row }"><el-switch v-model="row.required"/></template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ $index }">
                <el-button size="small" type="danger" @click="removeField($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-form-item>
      <el-form-item label="上传路径层级（可多级且可排序）">
        <div style="width:100%">
          <div style="margin-bottom:8px">
            <el-button size="small" @click="addSeg">新增层级</el-button>
          </div>
          <el-table :data="pathSegments" size="small" style="width:100%" ref="segmentsTable">
            <el-table-column label="排序" width="80" align="center">
              <template #default>
                <span class="drag-handle" title="拖拽排序">☰</span>
              </template>
            </el-table-column>
            <el-table-column label="#" width="60" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="来源" min-width="260">
              <template #default="{ row }">
                <el-select v-model="row.value" placeholder="选择: 项目名称 或 期望字段">
                  <el-option :value="'$project'" label="项目名称" />
                  <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="f.label + ' ('+f.key+')'" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" align="center">
              <template #default="{ $index }">
                <el-button size="small" @click="moveUp($index)" :disabled="$index===0">上移</el-button>
                <el-button size="small" @click="moveDown($index)" :disabled="$index===pathSegments.length-1">下移</el-button>
                <el-button size="small" type="danger" @click="removeSeg($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-form-item>
      <el-form-item label="下线">
        <el-switch v-model="form.offline" />
      </el-form-item>
      <el-form-item>
        <el-space>
          <el-button type="primary" @click="save">保存</el-button>
          <el-button @click="$router.back()">返回</el-button>
        </el-space>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import api from '../../api'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const route = useRoute();
const router = useRouter();
const isEdit = computed(()=>!!route.params.id)

const form = ref({
  name: '',
  allowResubmit: true,
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
  }
}

const addField = () => { expectedFields.value.push({ key: '', label: '', required: false, placeholder: '' }) }
const removeField = (idx) => { expectedFields.value.splice(idx, 1); bindRowDrag() }
const addSeg = () => { pathSegments.value.push({ value: '$project' }); bindSegDrag() }
const removeSeg = (idx) => { pathSegments.value.splice(idx, 1); bindSegDrag() }
const moveUp = (idx) => { if (idx>0) { const t = pathSegments.value[idx]; pathSegments.value.splice(idx,1); pathSegments.value.splice(idx-1,0,t) } }
const moveDown = (idx) => { if (idx<pathSegments.value.length-1) { const t = pathSegments.value[idx]; pathSegments.value.splice(idx,1); pathSegments.value.splice(idx+1,0,t) } }
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
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragover = (ev) => { ev.preventDefault() }
      tr.ondrop = (ev) => {
        ev.preventDefault()
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
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragover = (ev) => { ev.preventDefault() }
      tr.ondrop = (ev) => {
        ev.preventDefault()
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
.card-header{ display:flex; align-items:center; justify-content:space-between }
</style>
