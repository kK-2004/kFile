<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">模板管理</h1>
      <div>
        <el-button type="primary" @click="openCreate">新建模板</el-button>
      </div>
    </div>

    <el-table :data="templates" v-loading="loading" empty-text="暂无模板，点击右上角新建">
      <el-table-column prop="id" label="ID" width="80"/>
      <el-table-column prop="name" label="模板名称"/>
      <el-table-column label="字段数" width="90">
        <template #default="{row}">{{ Array.isArray(row.expectedUserFields) ? row.expectedUserFields.length : 0 }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{row}">{{ formatTs(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="确定删除该模板？已用此模板创建的项目不受影响" @confirm="remove(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑抽屉 -->
    <el-drawer v-model="showForm" :title="editingId ? '编辑模板' : '新建模板'" size="60%">
      <el-form :model="form" label-position="top" class="tpl-form">

        <el-form-item label="模板名称" class="required-field">
          <el-input v-model="form.name" placeholder="如：课程作业收集"/>
        </el-form-item>

        <div class="switch-grid">
          <div class="switch-card"><span class="switch-label">可重复提交</span><el-switch v-model="form.allowResubmit"/></div>
          <div class="switch-card"><span class="switch-label">允许多文件</span><el-switch v-model="form.allowMultiFiles"/></div>
          <div class="switch-card"><span class="switch-label">允许逾期提交</span><el-switch v-model="form.allowOverdue"/></div>
        </div>

        <el-divider content-position="left">收集字段</el-divider>
        <div class="section-actions">
          <el-button type="primary" :icon="Plus" size="small" @click="addField">新增字段</el-button>
        </div>
        <el-table :data="expectedFields" row-key="_rid" class="modern-table" empty-text="暂无字段">
          <el-table-column label="Key" min-width="140">
            <template #default="{row}"><el-input v-model="row.key" placeholder="如 studentNo" class="table-input"/></template>
          </el-table-column>
          <el-table-column label="显示名称" min-width="140">
            <template #default="{row}"><el-input v-model="row.label" placeholder="如 学号" class="table-input"/></template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{row}">
              <el-select v-model="row.type" class="table-input">
                <el-option value="text" label="文本"/>
                <el-option value="select" label="下拉"/>
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="选项/占位符" min-width="200">
            <template #default="{row}">
              <el-select v-if="row.type==='select'" v-model="row._options" multiple allow-create filterable default-first-option placeholder="回车添加选项" class="table-input"/>
              <el-input v-else v-model="row.placeholder" placeholder="提示文字" class="table-input"/>
            </template>
          </el-table-column>
          <el-table-column label="必填" width="70" align="center">
            <template #default="{row}"><el-checkbox v-model="row.required"/></template>
          </el-table-column>
          <el-table-column label="显示" width="70" align="center">
            <template #default="{row}"><el-checkbox v-model="row.display"/></template>
          </el-table-column>
          <el-table-column width="60" align="center">
            <template #default="{ $index }"><el-button link type="danger" :icon="Delete" @click="removeField($index)"/></template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">状态反馈</el-divider>
        <el-form-item label="提示类型">
          <el-select v-model="form.userSubmitStatusType">
            <el-option label="普通 (Info)" value="info"/>
            <el-option label="成功 (Success)" value="success"/>
            <el-option label="警告 (Warning)" value="warning"/>
            <el-option label="危险 (Danger)" value="danger"/>
          </el-select>
        </el-form-item>
        <el-form-item label="提示文案"><el-input v-model="form.userSubmitStatusText" placeholder="如：请务必核对学号..."/></el-form-item>
        <el-form-item label="查询主键（用户查询凭证）">
          <el-select v-model="form.queryFieldKey" placeholder="选择唯一标识字段" clearable>
            <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`"/>
          </el-select>
        </el-form-item>

        <el-divider content-position="left">存储路径结构</el-divider>
        <div class="section-actions">
          <el-button type="primary" :icon="Plus" size="small" @click="addSeg">添加层级</el-button>
        </div>
        <el-table :data="pathSegments" class="modern-table" empty-text="暂无层级">
          <el-table-column label="层级" width="80">
            <template #default="{ $index }"><span class="step-badge">Level {{ $index + 1 }}</span></template>
          </el-table-column>
          <el-table-column label="命名来源">
            <template #default="{row}">
              <el-select v-model="row.value" placeholder="选择来源" class="table-input">
                <el-option value="$project" label="项目名称 (固定)"/>
                <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`字段: ${f.label} (${f.key})`"/>
              </el-select>
            </template>
          </el-table-column>
          <el-table-column width="60" align="center">
            <template #default="{ $index }"><el-button link type="danger" :icon="Delete" @click="removeSeg($index)"/></template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">提交者限制（高级，可选）</el-divider>
        <el-form-item label="限制依据字段">
          <el-select v-model="form.allowedSubmitterKeys" multiple placeholder="选择字段（留空表示不限制）">
            <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`"/>
          </el-select>
        </el-form-item>
        <el-form-item label="允许名单（JSON 数组）">
          <el-input v-model="listText" type="textarea" :rows="4" placeholder='如：["1001","1002"] 或 [{"class":"一班","studentNo":"1001"}]'/>
        </el-form-item>

        <el-divider content-position="left">文件自动命名（可选）</el-divider>
        <el-form-item label="开启自动命名"><el-switch v-model="form.autoFileNamingEnabled"/></el-form-item>
        <template v-if="form.autoFileNamingEnabled">
          <el-form-item label="拼接符号">
            <el-select v-model="separatorChoice">
              <el-option value=" " label="空格"/>
              <el-option value="+" label="+"/>
              <el-option value="-" label="-"/>
              <el-option value="_" label="_"/>
              <el-option value="" label="不拼接"/>
              <el-option value="__custom__" label="自定义..."/>
            </el-select>
          </el-form-item>
          <el-form-item v-if="separatorChoice==='__custom__'" label="自定义拼接符号"><el-input v-model="separatorCustom"/></el-form-item>
          <div class="section-actions">
            <el-button type="primary" :icon="Plus" size="small" @click="addAutoNameField">添加字段</el-button>
          </div>
          <el-table :data="autoNameFields" class="modern-table" empty-text="请添加至少一个字段">
            <el-table-column label="字段">
              <template #default="{row}">
                <el-select v-model="row.value" placeholder="选择字段" class="table-input">
                  <el-option value="$project" label="项目名称"/>
                  <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`"/>
                </el-select>
              </template>
            </el-table-column>
            <el-table-column width="60" align="center">
              <template #default="{ $index }"><el-button link type="danger" :icon="Delete" @click="removeAutoNameField($index)"/></template>
            </el-table-column>
          </el-table>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存模板</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const templates = ref([])
const loading = ref(false)
const showForm = ref(false)
const editingId = ref(null)
const saving = ref(false)

const form = ref(emptyForm())
const expectedFields = ref([])
const pathSegments = ref([])
const listText = ref('')
const autoNameFields = ref([])
const separatorChoice = ref(' ')
const separatorCustom = ref('')
let _ridSeed = 1
const nextRid = () => `rid_${Date.now()}_${_ridSeed++}`

function emptyForm() {
  return {
    name: '',
    allowResubmit: true,
    allowMultiFiles: false,
    allowOverdue: false,
    userSubmitStatusType: 'info',
    userSubmitStatusText: '',
    queryFieldKey: '',
    allowedSubmitterKeys: [],
    autoFileNamingEnabled: false
  }
}

const formatTs = (ts) => ts ? new Date(ts).toLocaleString() : '-'

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.adminListTemplates()
    templates.value = data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally { loading.value = false }
}
onMounted(async () => { if (!auth.loaded) await auth.loadMe(); await load() })

function resetEditor() {
  form.value = emptyForm()
  expectedFields.value = []
  pathSegments.value = [{ value: '$project' }]
  listText.value = ''
  autoNameFields.value = []
  separatorChoice.value = ' '
  separatorCustom.value = ''
}

const openCreate = () => { editingId.value = null; resetEditor(); showForm.value = true }

const openEdit = (row) => {
  editingId.value = row.id
  resetEditor()
  form.value = {
    name: row.name || '',
    allowResubmit: row.allowResubmit != null ? row.allowResubmit : true,
    allowMultiFiles: row.allowMultiFiles != null ? row.allowMultiFiles : false,
    allowOverdue: !!row.allowOverdue,
    userSubmitStatusType: row.userSubmitStatusType || 'info',
    userSubmitStatusText: row.userSubmitStatusText || '',
    queryFieldKey: row.queryFieldKey || '',
    allowedSubmitterKeys: Array.isArray(row.allowedSubmitterKeys) ? row.allowedSubmitterKeys : [],
    autoFileNamingEnabled: !!row.autoFileNamingEnabled
  }
  expectedFields.value = Array.isArray(row.expectedUserFields) ? row.expectedUserFields.map(f => ({
    _rid: nextRid(), key: f.key, label: f.label, placeholder: f.placeholder || '',
    required: !!f.required, display: f.display !== false, type: f.type || 'text',
    _options: Array.isArray(f.options) ? f.options : []
  })) : []
  pathSegments.value = Array.isArray(row.pathSegments) ? row.pathSegments.map(v => ({ value: v })) : [{ value: '$project' }]
  // 提交者名单
  const hasSingleKey = (form.value.allowedSubmitterKeys || []).length === 1
  if (row.allowedSubmitterList != null) {
    const v = row.allowedSubmitterList
    if (hasSingleKey && Array.isArray(v) && v.every(x => typeof x === 'string')) listText.value = v.join(',')
    else listText.value = typeof v === 'string' ? v : JSON.stringify(v, null, 2)
  } else listText.value = ''
  // 自动命名
  const cfg = (row.autoFileNamingConfig && typeof row.autoFileNamingConfig === 'object') ? row.autoFileNamingConfig : {}
  autoNameFields.value = Array.isArray(cfg.fields) ? cfg.fields.map(v => ({ value: v })) : []
  const sep = cfg.separator == null ? ' ' : String(cfg.separator)
  const presets = new Set([' ', '+', '-', '_', ''])
  if (presets.has(sep)) { separatorChoice.value = sep; separatorCustom.value = '' }
  else { separatorChoice.value = '__custom__'; separatorCustom.value = sep }
  showForm.value = true
}

// 字段操作
const addField = () => expectedFields.value.push({ _rid: nextRid(), key: '', label: '', required: false, display: true, placeholder: '', type: 'text', _options: [] })
const removeField = (i) => expectedFields.value.splice(i, 1)
const addSeg = () => pathSegments.value.push({ value: '$project' })
const removeSeg = (i) => pathSegments.value.splice(i, 1)
const addAutoNameField = () => autoNameFields.value.push({ value: '$project' })
const removeAutoNameField = (i) => autoNameFields.value.splice(i, 1)

const separatorValue = () => separatorChoice.value === '__custom__' ? (separatorCustom.value ?? '') : separatorChoice.value

function buildPayload() {
  if (!form.value.name || !form.value.name.trim()) throw new Error('请输入模板名称')
  const keys = new Set()
  for (const f of expectedFields.value) {
    if (!f.key || !f.label) throw new Error('期望字段的 Key 和显示名称不能为空')
    if (keys.has(f.key)) throw new Error('期望字段 Key 不可重复: ' + f.key)
    keys.add(f.key)
  }
  const expectedUserFields = expectedFields.value.map(f => ({
    key: f.key, label: f.label, placeholder: f.placeholder || '', required: !!f.required,
    display: f.display !== false, type: f.type || 'text',
    options: f.type === 'select' ? (Array.isArray(f._options) ? f._options : []) : undefined
  }))
  const segs = pathSegments.value.map(s => s.value).filter(Boolean)
  let allowedSubmitterList = null
  const akeys = (form.value.allowedSubmitterKeys || []).filter(Boolean)
  if (akeys.length > 0 && listText.value && listText.value.trim()) {
    try {
      const parsed = JSON.parse(listText.value)
      if (!Array.isArray(parsed)) throw new Error()
      allowedSubmitterList = parsed
    } catch { throw new Error('允许名单必须是 JSON 数组') }
  }
  const nameFields = autoNameFields.value.map(x => x.value).filter(Boolean)
  if (form.value.autoFileNamingEnabled && !nameFields.length) throw new Error('已开启自动命名，请添加至少一个字段')
  return {
    name: form.value.name.trim(),
    expectedUserFields,
    pathSegments: segs.length ? segs : null,
    userSubmitStatusType: form.value.userSubmitStatusType,
    userSubmitStatusText: form.value.userSubmitStatusText,
    queryFieldKey: form.value.queryFieldKey,
    allowedSubmitterKeys: akeys.length ? akeys : null,
    allowedSubmitterList,
    autoFileNamingEnabled: !!form.value.autoFileNamingEnabled,
    autoFileNamingConfig: form.value.autoFileNamingEnabled ? {
      fields: nameFields,
      separator: separatorValue() ?? '',
      aliases: {}
    } : null,
    allowResubmit: form.value.allowResubmit,
    allowMultiFiles: form.value.allowMultiFiles,
    allowOverdue: form.value.allowOverdue
  }
}

const save = async () => {
  saving.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) await api.adminUpdateTemplate(editingId.value, payload)
    else await api.adminCreateTemplate(payload)
    ElMessage.success('已保存')
    showForm.value = false
    await load()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败'
    ElMessage.error(msg)
  } finally { saving.value = false }
}

const remove = async (row) => {
  try {
    await api.adminDeleteTemplate(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}
</script>

<style scoped>
.page-container { padding: 20px; max-width: 1100px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; font-weight: 600; color: var(--kf-text-primary, #333); }
.switch-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; margin: 12px 0; }
.switch-card { display: flex; align-items: center; justify-content: space-between; background: #f9f9f9; padding: 10px 14px; border-radius: 8px; }
.switch-label { font-size: 14px; color: #353740; }
.tpl-form { padding: 0 8px; }
.section-actions { margin: 8px 0; }
.modern-table { border: 1px solid #e5e5e5; border-radius: 8px; overflow: hidden; margin-bottom: 12px; }
.table-input { width: 100%; }
.step-badge { display: inline-block; padding: 2px 8px; background: #f0f0f0; border-radius: 4px; font-size: 12px; }
.required-field :deep(.el-form-item__label)::before { content: '*'; color: #f56c6c; margin-right: 4px; }
:deep(.el-divider__text) { font-weight: 600; font-size: 14px; }
</style>
