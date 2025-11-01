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
            <template v-else>
              <el-tag type="success" size="small">新建模式</el-tag>
              <el-tag v-if="showUserQuota" type="info" size="small" class="quota-tag">
                剩余额度：{{ quotaDisplay.remaining }} / {{ quotaDisplay.limit }}，重置：{{ quotaDisplay.resetDate }}，总配额：{{ quotaDisplay.totalGB }} GB
              </el-tag>
            </template>
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

        <!-- 自动识别创建（放在期望字段之前） -->
        <div class="form-section">
          <div class="section-title">
            <el-icon><Document /></el-icon>
            <span>自动识别创建</span>
          </div>
          <div class="auto-detect-bar">
            <el-space>
              <el-button type="primary" @click="openAutoDetect">选择 CSV 并自动创建</el-button>
              <span class="hint">从包含表头的 CSV 自动识别字段（如 major, sid）并生成期望字段与限制名单。</span>
            </el-space>
            <input ref="csvInputRef" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange" />
          </div>
          <div v-if="autoPreview.headers.length" class="auto-summary">
            <div>已检测到字段：
              <el-tag v-for="h in autoPreview.headers" :key="h" size="small" style="margin-right:6px">{{ h }}</el-tag>
              （{{ autoPreview.count }} 行）
            </div>
          </div>
          <el-switch v-model="autoRestrict" active-text="开启提交者限制（仅允许识别名单）" inactive-text="不开启提交者限制" />
          <div class="advanced-toggle">
            <el-switch v-model="showAdvanced" active-text="显示高级设置" inactive-text="隐藏高级设置" />
          </div>
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

        <!-- 提交者限制（可选） -->
        <!-- 高级：保留旧的手动名单导入（可选，可隐藏） -->
        <div class="form-section" v-if="showAdvanced">
          <div class="section-title">
            <el-icon><User /></el-icon>
            <span>提交者限制（高级）</span>
          </div>
          <div class="field-hint" style="margin-bottom:8px">通常无需配置；若你未使用“自动识别创建”，可在此手动设置限制字段与名单。</div>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="限定字段（可多选）">
                <el-select v-model="form.allowedSubmitterKeys" multiple placeholder="选择需要限定的字段">
                  <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="列表格式">
                <el-radio-group v-model="listMode">
                  <el-radio-button label="csv" :disabled="(form.allowedSubmitterKeys||[]).length !== 1">逗号分隔</el-radio-button>
                  <el-radio-button label="json">JSON 列表</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="允许名单">
            <div style="width:100%">
              <div class="import-bar">
                <el-space>
                  <el-button @click="triggerCsvSelect">从 CSV 导入</el-button>
                  <span class="hint">CSV 第一行是表头，导入后映射到限定字段并生成 {{ listMode==='csv' ? '逗号分隔' : 'JSON' }} 列表。</span>
                </el-space>
                <input ref="csvInputRef2" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange2" />
              </div>
              <el-input type="textarea" :rows="6" v-model="listText" :placeholder="listPlaceholder" />
            </div>
          </el-form-item>
        </div>

        <!-- 自动识别创建结果确认对话框 -->
        <el-dialog v-model="autoDialogVisible" title="自动识别创建" width="560px">
          <div v-if="csvHeaders.length">
            <p class="mb-3 text-sm" style="color: var(--kf-muted);">检测到表头并读取 {{ csvPreviewCount }} 行。将根据唯一值数量自动为字段选择类型：</p>
            <ul class="mb-3 text-sm" style="color: var(--kf-muted);">
              <li>唯一值少（如“专业”）：设为 下拉(select)，并自动生成选项</li>
              <li>唯一值多（如“学号”）：设为 文本(text)</li>
            </ul>
            <div class="mb-2">字段预览：</div>
            <el-table :data="autoFieldPreview" size="small" style="width:100%">
              <el-table-column prop="key" label="字段名" width="160" />
              <el-table-column prop="type" label="类型" width="120" />
              <el-table-column prop="uniqueCount" label="唯一值" width="100" />
              <el-table-column label="示例/选项">
                <template #default="{ row }">
                  <span v-if="row.type==='select'">{{ row.options.slice(0,6).join(', ') }}<span v-if="row.options.length>6"> …</span></span>
                  <span v-else>{{ row.sample }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div v-else class="text-gray-500">未检测到 CSV 表头，请确认文件内容。</div>
          <template #footer>
            <el-button @click="autoDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="applyAutoDetect">应用到项目</el-button>
          </template>
        </el-dialog>

        <!-- 旧 CSV 映射对话框（手动名单导入用） -->
        <el-dialog v-model="csvDialogVisible" :title="`从 CSV 导入（${csvFileName}）`" width="520px">
          <div v-if="csvHeaders.length">
            <div class="mb-3 text-sm" style="color: var(--kf-muted);">检测到 {{ csvPreviewCount }} 行数据。请为每个限定字段选择 CSV 列。</div>
            <el-form label-width="140px">
              <el-form-item v-for="k in (form.allowedSubmitterKeys||[])" :key="k" :label="`${k}`">
                <el-select v-model="csvMapping[k]" style="width: 260px;" placeholder="选择列">
                  <el-option v-for="h in csvHeaders" :key="h" :label="h" :value="h" />
                </el-select>
              </el-form-item>
            </el-form>
          </div>
          <div v-else class="text-gray-500">未检测到 CSV 表头，请确认文件内容。</div>
          <template #footer>
            <el-button @click="csvDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="confirmCsvImport">生成名单</el-button>
          </template>
        </el-dialog>


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
  offline: false,
  allowedSubmitterKeys: []
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
const listMode = ref('csv')
const listText = ref('')
// 自动识别/CSV 导入
const csvPurpose = ref('allow') // 'auto' | 'allow'
const csvInputRef = ref(null)
const csvInputRef2 = ref(null)
const csvDialogVisible = ref(false)
const csvFileName = ref('')
const csvHeaders = ref([])
const csvRows = ref([]) // array of objects by header
const csvMapping = ref({}) // key -> header
const csvPreviewCount = computed(() => Array.isArray(csvRows.value) ? csvRows.value.length : 0)
// 自动识别预览
const autoDialogVisible = ref(false)
const autoPreview = ref({ headers: [], count: 0 })
const autoFieldPreview = ref([])
const autoRestrict = ref(false)
const showAdvanced = ref(false)

function openAutoDetect() { csvPurpose.value = 'auto'; csvInputRef.value?.click() }
function triggerCsvSelect() { csvPurpose.value = 'allow'; (csvInputRef2.value||csvInputRef.value)?.click() }
function onCsvFileChange(e) {
  const file = e?.target?.files?.[0]
  if (!file) return
  csvFileName.value = file.name || 'data.csv'
  const reader = new FileReader()
  reader.onload = () => {
    try {
      const text = decodeBufferToText(reader.result)
      const { headers, rows } = parseCsv(text)
      if (!headers.length) throw new Error('未检测到表头')
      csvHeaders.value = headers
      csvRows.value = rows
      if (csvPurpose.value === 'auto') {
        prepareAutoPreview()
        autoDialogVisible.value = true
      } else {
        // 预填映射：按完全相同或大小写不敏感匹配
        const keys = form.value?.allowedSubmitterKeys || []
        const map = {}
        for (const k of keys) {
          const exact = headers.find(h => h === k)
          const ci = exact || headers.find(h => h.toLowerCase() === k.toLowerCase())
          map[k] = ci || headers[0]
        }
        csvMapping.value = map
        csvDialogVisible.value = true
      }
    } catch (err) {
      ElMessage.error(err?.message || 'CSV 解析失败')
    } finally {
      // 重置 input 以便重复选择同一文件
      e.target.value = ''
    }
  }
  reader.onerror = () => { ElMessage.error('读取文件失败') }
  // 读取为二进制缓冲区，后续自行按编码解码，兼容 GBK/GB18030/UTF-8
  reader.readAsArrayBuffer(file)
}
function onCsvFileChange2(e) { return onCsvFileChange(e) }

function parseCsv(text) {
  // 简易 CSV 解析：支持逗号/分号/Tab 分隔、引号包裹、双引号转义、换行
  // 1) 自动探测分隔符（首行非引号内的分隔符数量最大者）
  function detectDelimiter(src) {
    const candidates = [',', ';', '\t']
    let inQ = false
    const counts = { ',': 0, ';': 0, '\t': 0 }
    for (let i = 0; i < src.length; i++) {
      const ch = src[i]
      if (ch === '\n' || ch === '\r') break
      if (ch === '"') { inQ = !inQ; continue }
      if (!inQ && (ch === ',' || ch === ';' || ch === '\t')) counts[ch]++
    }
    let best = ','
    let max = counts[best]
    for (const c of candidates) { if (counts[c] > max) { best = c; max = counts[c] } }
    return best
  }

  const delim = detectDelimiter(text)
  const rows = []
  let i = 0, inQuotes = false, field = ''
  const out = []
  function pushField() { out.push(field); field = '' }
  function pushRow() { rows.push(out.slice()); out.length = 0 }
  while (i < text.length) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i+1] === '"') { field += '"'; i++ } else { inQuotes = false }
      } else { field += ch }
    } else {
      if (ch === '"') { inQuotes = true }
      else if (ch === delim) { pushField() }
      else if (ch === '\n') { pushField(); pushRow() }
      else if (ch === '\r') { /* ignore */ }
      else { field += ch }
    }
    i++
  }
  // last
  pushField(); pushRow()
  // trim whitespace
  const trimmed = rows.map(r => r.map(c => (c??'').trim()))
  // remove empty trailing rows
  while (trimmed.length && trimmed[trimmed.length-1].every(c => c === '')) trimmed.pop()
  if (trimmed.length === 0) return { headers: [], rows: [] }
  const headers = trimmed[0]
  const dataRows = trimmed.slice(1).filter(r => r.some(c => c !== ''))
  const objs = dataRows.map(r => {
    const o = {}
    headers.forEach((h, idx) => { o[h] = r[idx] ?? '' })
    return o
  })
  return { headers, rows: objs }
}

// 将 ArrayBuffer 解码为字符串：优先 UTF-8（BOM 识别），若出现大量替换符则回退 GB18030/GBK
function decodeBufferToText(result) {
  try {
    if (!(result instanceof ArrayBuffer)) return String(result || '')
    let bytes = new Uint8Array(result)
    // BOM 检测
    if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) {
      bytes = bytes.subarray(3)
      return new TextDecoder('utf-8', { fatal: false }).decode(bytes)
    }
    if (bytes.length >= 2) {
      // UTF-16 LE/BE
      if (bytes[0] === 0xFF && bytes[1] === 0xFE) return new TextDecoder('utf-16le').decode(bytes.subarray(2))
      if (bytes[0] === 0xFE && bytes[1] === 0xFF) return new TextDecoder('utf-16be').decode(bytes.subarray(2))
    }
    // 尝试 UTF-8
    let text = new TextDecoder('utf-8', { fatal: false }).decode(bytes)
    const bad = (text.match(/\uFFFD/g) || []).length
    const ratio = bad / Math.max(1, text.length)
    if (ratio > 0.01) {
      // 尝试 GB18030 或 GBK
      try { text = new TextDecoder('gb18030', { fatal: false }).decode(bytes); return text } catch {}
      try { text = new TextDecoder('gbk', { fatal: false }).decode(bytes); return text } catch {}
    }
    return text
  } catch {
    try { return new TextDecoder('utf-8').decode(new Uint8Array(result)) } catch { return '' }
  }
}

function confirmCsvImport() {
  try {
    const keys = form.value?.allowedSubmitterKeys || []
    if (keys.length === 0) { ElMessage.warning('请先选择限定字段'); return }
    // 校验映射
    for (const k of keys) {
      if (!csvMapping.value[k]) { ElMessage.warning('请为字段 '+k+' 选择对应列'); return }
    }
    if (listMode.value === 'csv') {
      if (keys.length !== 1) { ElMessage.error('逗号分隔模式仅支持单字段'); return }
      const col = csvMapping.value[keys[0]]
      const values = []
      const seen = new Set()
      for (const row of csvRows.value) {
        const v = String(row[col] ?? '').trim()
        if (!v || seen.has(v)) continue
        seen.add(v)
        values.push(v)
      }
      listText.value = values.join(',')
    } else {
      // JSON 数组
      const arr = []
      for (const row of csvRows.value) {
        const obj = {}
        let empty = true
        for (const k of keys) {
          const col = csvMapping.value[k]
          const v = String(row[col] ?? '').trim()
          obj[k] = v
          if (v) empty = false
        }
        if (!empty) arr.push(obj)
      }
      listText.value = JSON.stringify(arr, null, 2)
    }
    csvDialogVisible.value = false
    ElMessage.success('已从 CSV 生成允许名单')
  } catch (e) {
    ElMessage.error('生成失败')
  }
}

function prepareAutoPreview() {
  const headers = csvHeaders.value || []
  const rows = csvRows.value || []
  autoPreview.value = { headers, count: rows.length }
  const fields = []
  for (const h of headers) {
    const values = rows.map(r => String(r[h] ?? '').trim()).filter(Boolean)
    const uniq = Array.from(new Set(values))
    const uniqueCount = uniq.length
    const total = values.length
    const allUnique = uniqueCount >= total * 0.9 && total > 20
    const type = (!allUnique && uniqueCount <= 50) ? 'select' : 'text'
    fields.push({ key: h, type, uniqueCount, options: type==='select' ? uniq.slice(0, 500) : [], sample: values[0] || '' })
  }
  autoFieldPreview.value = fields
}

function applyAutoDetect() {
  try {
    // 1) 生成期望字段
    expectedFields.value = autoFieldPreview.value.map(f => ({
      key: f.key,
      label: f.key,
      placeholder: '',
      required: true,
      type: f.type,
      _options: Array.isArray(f.options) ? f.options : []
    }))
    // 2) 建议查询字段：优先 sid 学号
    const headers = csvHeaders.value || []
    const sidKey = headers.find(h => h.toLowerCase() === 'sid' || h.toLowerCase() === 'studentno' || h.toLowerCase() === '学号')
    form.value.queryFieldKey = sidKey || headers[0] || ''
    // 3) 限制名单
    form.value.allowedSubmitterKeys = headers.slice()
    const rows = csvRows.value || []
    const allowed = rows.map(r => {
      const o = {}
      for (const k of form.value.allowedSubmitterKeys) o[k] = String(r[k] ?? '').trim()
      return o
    })
    // 开启限制开关
    autoRestrict.value = true
    // 将名单缓存到 listText 以便保存时复用（JSON）
    listMode.value = 'json'
    listText.value = JSON.stringify(allowed, null, 2)
    // 关闭对话框并提示
    autoDialogVisible.value = false
    ElMessage.success('已自动识别并填充字段与名单')
  } catch (e) {
    ElMessage.error('自动识别失败')
  }
}
const listPlaceholder = computed(() => {
  const keys = form.value?.allowedSubmitterKeys || []
  if (keys.length === 1 && listMode.value === 'csv') {
    return `请输入 ${keys[0]} 的逗号分隔值，例如：1001,1002,1003`
  }
  const hint = keys.length > 1 ? `对象需包含字段：${keys.join(', ')}` : '建议为字符串数组或对象数组'
  return `请输入 JSON 数组，例如：\n[\n  { "${keys[0]||'field'}": "v1"${keys.length>1?`, "${keys[1]}": "w1"`:''} }\n] (${hint})`
})

const load = async () => {
  if (!isEdit.value) return
  // 管理端使用 admin 接口，避免公共接口的敏感信息裁剪
  const { data } = await api.adminGetProject(route.params.id)
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
  // 限定提交者：载入已有配置
  form.value.allowedSubmitterKeys = Array.isArray(data.allowedSubmitterKeys) ? data.allowedSubmitterKeys : []
  const hasSingleKey = (form.value.allowedSubmitterKeys||[]).length === 1
  if (data.allowedSubmitterList != null) {
    try {
      const v = data.allowedSubmitterList
      if (hasSingleKey && Array.isArray(v) && v.every(x => typeof x === 'string')) {
        listMode.value = 'csv'
        listText.value = v.join(',')
      } else {
        listMode.value = 'json'
        listText.value = typeof v === 'string' ? v : JSON.stringify(v, null, 2)
      }
    } catch {}
  } else {
    listMode.value = hasSingleKey ? 'csv' : 'json'
    listText.value = ''
  }
  // 根据后端状态同步开关（存在 keys 且存在名单即视为已限制）
  autoRestrict.value = (Array.isArray(form.value.allowedSubmitterKeys) && form.value.allowedSubmitterKeys.length > 0 && data.allowedSubmitterList != null)
  // 绑定拖拽（初次加载后）
  bindRowDrag()
  bindSegDrag()
}

// 配额显示（仅新建页且非 SUPER 显示）
const quota = ref(null)
const showUserQuota = computed(() => auth.user && (auth.user.role||'').toUpperCase() !== 'SUPER' && !isEdit.value)
const quotaDisplay = computed(() => {
  const q = quota.value || {}
  const limit = q?.unlimited ? '不限' : (q?.limit ?? '-')
  const remaining = q?.unlimited ? '不限' : (q?.remaining ?? '-')
  const resetDate = q?.resetAt ? new Date(q.resetAt).toLocaleDateString() : '-'
  const totalGB = q?.userTotalQuotaBytes ? Math.round(Number(q.userTotalQuotaBytes)/1024/1024/1024) : 1
  return { limit, remaining, resetDate, totalGB }
})
const loadQuota = async () => { try { const { data } = await api.creationQuota(); quota.value = data } catch {} }
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
    // 提交者限制：若开启自动限制，则直接使用已识别的 keys + JSON 列表
    const akeys = Array.isArray(payload.allowedSubmitterKeys) ? payload.allowedSubmitterKeys.filter(Boolean) : []
    if (autoRestrict.value && akeys.length > 0) {
      try {
        if (!listText.value || !listText.value.trim()) throw new Error('EMPTY')
        const parsed = JSON.parse(listText.value || '[]')
        if (!Array.isArray(parsed)) throw new Error('JSON 列表必须是数组')
        if (parsed.length === 0) throw new Error('EMPTY')
        payload.allowedSubmitterList = parsed
      } catch (e) {
        throw new Error('自动识别的名单无效，请先导入 CSV 并点击“应用到项目”')
      }
    } else {
      // 高级：兼容手动模式
      if (akeys.length > 0) {
        if (listMode.value === 'csv') {
          if (akeys.length !== 1) throw new Error('逗号分隔模式仅支持单个字段')
          const arr = (listText.value || '').split(',').map(s => s.trim()).filter(Boolean)
          payload.allowedSubmitterList = arr
        } else {
          try {
            const parsed = JSON.parse(listText.value || '[]')
            if (!Array.isArray(parsed)) throw new Error('JSON 列表必须是数组')
            payload.allowedSubmitterList = parsed
          } catch (e) {
            throw new Error('允许名单 JSON 解析失败')
          }
        }
      } else {
        payload.allowedSubmitterList = null
        payload.allowedSubmitterKeys = []
      }
    }
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
onMounted(async()=>{ if (!auth.loaded) await auth.loadMe(); await loadQuota() })

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
.auto-detect-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  gap: 12px;
  flex-wrap: wrap;
}
.auto-summary { color: var(--kf-muted); font-size: 12px; margin-bottom: 8px; }
.advanced-toggle { margin-top: 10px; }
.import-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  gap: 12px;
  flex-wrap: wrap;
}
.hidden { display: none; }
.hint { color: var(--kf-muted); font-size: 12px; }
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

.quota-tag {
  border-radius: 6px;
}

/* Form styling */
.project-form {
  padding: 12px 0 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-section {
  margin-bottom: 24px;
  padding: 20px 20px 16px;
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid var(--kf-border-color);
  box-shadow: 0 6px 20px rgba(0,0,0,0.04);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-size: 18px;
  font-weight: 700;
  color: #111827;
}

.section-title .el-icon { font-size: 18px; color: #6b7280; }

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

/* 统一表单项的垂直对齐与间距 */
:deep(.el-form-item) { margin-bottom: 14px; }
:deep(.el-form-item__content) { align-items: center; }
:deep(.el-form-item__label) { font-weight: 500; color: #374151; }

/* 统一输入控件宽度与尺寸 */
:deep(.el-input),
:deep(.el-select),
:deep(.el-date-editor) { width: 100%; }
:deep(.el-input__inner),
:deep(.el-select .el-input__inner) { line-height: 36px; }

/* 顶部/分区操作栏布局更稳健 */
.table-header,
.auto-detect-bar,
.import-bar {
  align-items: center;
}
.table-header { flex-wrap: wrap; gap: 10px; }
.table-hint { margin-left: auto; }

/* 按钮风格统一（更简洁） */
:deep(.el-button) { border-radius: 8px; font-weight: 500; }
/* 保持原有主按钮配色（移除全局颜色覆盖） */

/* 小屏优化：提示自动换行，按钮保持可点区域 */
@media (max-width: 768px) {
  .auto-detect-bar .hint,
  .import-bar .hint { flex: 1 1 100%; }
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
