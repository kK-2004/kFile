<template>
  <div class="page-container">
    <div class="project-form-container">

      <div class="page-header">
        <div class="header-left">
          <div class="back-btn" @click="$router.back()">
            <el-icon><Back /></el-icon>
          </div>
          <div class="title-group">
            <h1 class="page-title">{{ isEdit ? '编辑项目：' + form.name : '新建项目' }}</h1>
            <span
                v-if="!isEdit && showUserQuota"
                class="quota-text"
            >
              剩余额度: {{ quotaDisplay.remaining }} / {{ quotaDisplay.limit }}
            </span>
          </div>
        </div>
        <div class="header-right">
          <el-button @click="$router.back()">取消</el-button>
          <el-button type="primary" @click="save" :loading="saving">保存项目</el-button>
        </div>
      </div>

      <div class="content-wrapper">
        <el-form :model="form" label-position="top" class="project-form">

          <div class="form-section card-style">
            <div class="section-header">
              <div class="header-text">
                <h3>基本设置</h3>
                <p class="section-desc">配置项目的核心信息、时间限制及文件规则。</p>
              </div>
            </div>

            <div class="form-content">
              <el-form-item label="项目名称" class="required-field full-width">
                <el-input v-model="form.name" placeholder="请输入项目名称" size="large" />
              </el-form-item>

              <div class="grid-row">
                <el-form-item label="开始时间">
                  <el-date-picker v-model="form.startAt" type="datetime" placeholder="选择开始时间" value-format="x" style="width: 100%"/>
                </el-form-item>
                <el-form-item label="截止时间">
                  <el-date-picker v-model="form.endAt" type="datetime" placeholder="选择截止时间" value-format="x" style="width: 100%"/>
                </el-form-item>
              </div>

              <div class="switch-grid">
                <div class="switch-card">
                  <span class="switch-label">可重复提交</span>
                  <el-switch v-model="form.allowResubmit" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">允许多文件</span>
                  <el-switch v-model="form.allowMultiFiles" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">允许逾期提交</span>
                  <el-switch v-model="form.allowOverdue" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">项目状态</span>
                  <el-switch
                      v-model="form.offline"
                      :active-value="false"
                      :inactive-value="true"
                      active-text="在线"
                      inactive-text="已下线"
                      inline-prompt
                      style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
                  />
                </div>
              </div>

              <div class="grid-row mt-4">
                <el-form-item label="文件大小上限 (MB)">
                  <el-input v-model.number="fileSizeLimitMB" type="number" placeholder="留空不限制">
                    <template #append>MB</template>
                  </el-input>
                </el-form-item>
                <el-form-item label="文件扩展名白名单">
                  <el-select
                      v-model="allowedTypes"
                      multiple
                      filterable
                      allow-create
                      default-first-option
                      placeholder="所有类型 (或输入 pdf, zip...)"
                  >
                    <el-option v-for="t in typeSelectable" :key="t" :value="t" :label="`.${t}`" />
                  </el-select>
                </el-form-item>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>智能识别</h3>
                <p class="section-desc">上传 Excel/CSV 表头自动生成字段配置。</p>
              </div>
              <el-button type="primary" @click="openAutoDetect">
                <el-icon class="el-icon--left"><Document /></el-icon>
                导入表单
              </el-button>
              <input ref="csvInputRef" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange" />
            </div>

            <div class="form-content">
              <div v-if="autoPreview.headers.length" class="detect-status-bar">
                <el-icon><Check /></el-icon>
                <span>已识别 {{ autoPreview.count }} 行数据，包含字段：</span>
                <div class="tags-wrapper">
                  <el-tag v-for="h in autoPreview.headers" :key="h" size="small" type="info">{{ h }}</el-tag>
                </div>
              </div>

              <div class="advanced-setting-area">
                <el-checkbox v-model="autoRestrict" label="开启提交者限制（仅允许识别名单内的用户提交）" />
                <el-button type="primary" size="small" @click="showAdvanced = !showAdvanced">
                  {{ showAdvanced ? '隐藏高级设置' : '展开高级设置' }}
                </el-button>
              </div>
            </div>
          </div>

          <div class="form-section card-style" v-if="showAdvanced">
            <div class="section-header">
              <div class="header-text">
                <h3>提交限制 (高级)</h3>
                <p class="section-desc">仅在未使用“自动识别”时需手动配置。</p>
              </div>
            </div>

            <div class="form-content">
              <div class="advanced-box">
                <div class="grid-row">
                  <el-form-item label="限制依据字段">
                    <el-select v-model="form.allowedSubmitterKeys" multiple placeholder="选择字段">
                      <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="名单格式">
                    <el-radio-group v-model="listMode">
                      <el-radio-button label="csv" :disabled="(form.allowedSubmitterKeys||[]).length !== 1">单列逗号</el-radio-button>
                      <el-radio-button label="json">JSON 列表</el-radio-button>
                    </el-radio-group>
                  </el-form-item>
                </div>

                <el-form-item label="白名单数据">
                  <div class="list-editor-container">
                    <div class="editor-toolbar">
                      <el-button size="small" @click="triggerCsvSelect">导入 CSV 填充</el-button>
                      <input ref="csvInputRef2" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange2" />
                      <span class="editor-hint">支持从 CSV 读取并转换为下方格式</span>
                    </div>
                    <el-input
                        type="textarea"
                        :rows="6"
                        v-model="listText"
                        :placeholder="listPlaceholder"
                        class="code-textarea"
                    />
                  </div>
                </el-form-item>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>收集字段配置</h3>
                <p class="section-desc">定义用户需要填写或选择的信息。</p>
              </div>
              <el-button type="primary" :icon="Plus" @click="addField">新增字段</el-button>
            </div>

            <div class="form-content">
              <div class="custom-table-wrapper">
                <el-table :data="expectedFields" row-key="key" class="modern-table" ref="fieldsTable" empty-text="暂无配置，请点击右上方添加">
                  <el-table-column width="40" align="center">
                    <template #default>
                      <div class="drag-handle-icon">⋮⋮</div>
                    </template>
                  </el-table-column>

                  <el-table-column label="字段标识 (Key)" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.key" placeholder="如: studentNo" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="显示名称" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.label" placeholder="如: 学号" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="类型" width="110">
                    <template #default="{ row }">
                      <el-select v-model="row.type" class="table-input">
                        <el-option value="text" label="文本" />
                        <el-option value="select" label="下拉" />
                      </el-select>
                    </template>
                  </el-table-column>

                  <el-table-column label="选项配置 / 占位符" min-width="200">
                    <template #default="{ row }">
                      <el-select
                          v-if="row.type==='select'"
                          v-model="row._options"
                          multiple allow-create filterable default-first-option
                          placeholder="回车添加选项"
                          class="table-input"
                      />
                      <el-input v-else v-model="row.placeholder" placeholder="输入框提示文字" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="必填" width="70" align="center">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.required" />
                    </template>
                  </el-table-column>

                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeField($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header">
              <div class="header-text">
                <h3>状态反馈</h3>
                <p class="section-desc">配置用户在查询界面看到的信息。</p>
              </div>
            </div>
            <div class="form-content">
              <div class="form-grid">
                <div class="grid-row-sidebar">
                  <el-form-item label="提示类型">
                    <el-select v-model="form.userSubmitStatusType">
                      <el-option label="普通 (Info)" value="info" />
                      <el-option label="成功 (Success)" value="success" />
                      <el-option label="警告 (Warning)" value="warning" />
                      <el-option label="危险 (Danger)" value="danger" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="提示文案" class="flex-grow">
                    <el-input v-model="form.userSubmitStatusText" placeholder="例如：请务必核对学号..." />
                  </el-form-item>
                </div>
                <div class="grid-row-sidebar" id="main_key">
                  <el-form-item label="查询主键 (用户查询凭证)">
                    <el-select v-model="form.queryFieldKey" placeholder="请选择唯一标识字段 (如：学号)">
                      <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                    </el-select>
                  </el-form-item>
                </div>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>存储路径结构</h3>
                <p class="section-desc">定义文件在服务器上的存储目录层级。</p>
              </div>
              <el-button type="primary" :icon="Plus" @click="addSeg">添加层级</el-button>
            </div>

            <div class="form-content">
              <div class="custom-table-wrapper">
                <el-table :data="pathSegments" class="modern-table" ref="segmentsTable" empty-text="暂无层级">
                  <el-table-column width="40" align="center">
                    <template #default>
                      <div class="drag-handle-icon">⋮⋮</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="层级序号" width="100">
                    <template #default="{ $index }">
                      <span class="step-badge">Level {{ $index + 1 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="命名来源">
                    <template #default="{ row }">
                      <el-select v-model="row.value" placeholder="选择来源" class="table-input">
                        <el-option :value="'$project'" label="项目名称 (固定)" />
                        <el-option
                            v-for="f in expectedFields"
                            :key="f.key"
                            :value="f.key"
                            :label="`字段: ${f.label} (${f.key})`"
                        />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeSeg($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>

        </el-form>
      </div>

      <el-dialog v-model="autoDialogVisible" title="确认自动配置" width="600px" class="modern-dialog">
        <div v-if="csvHeaders.length">
          <p class="dialog-desc">已分析 CSV 文件，即将应用以下配置：</p>
          <div class="preview-stats">
            <div class="stat-item">
              <strong>{{ csvPreviewCount }}</strong>
              <span>数据行数</span>
            </div>
            <div class="stat-item">
              <strong>{{ autoFieldPreview.length }}</strong>
              <span>检测字段</span>
            </div>
          </div>

          <el-table :data="autoFieldPreview" height="300" class="dialog-table">
            <el-table-column prop="key" label="字段名" width="140" />
            <el-table-column prop="type" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type==='select'?'warning':''">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="预览">
              <template #default="{ row }">
                <span v-if="row.type==='select'" class="text-truncate">{{ row.options.join(', ') }}</span>
                <span v-else class="text-gray">{{ row.sample }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else class="empty-state">未检测到有效数据</div>
        <template #footer>
          <el-button @click="autoDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="applyAutoDetect">应用配置</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="csvDialogVisible" title="导入名单映射" width="500px" class="modern-dialog">
        <div class="dialog-content">
          <p class="dialog-desc">请选择 CSV 列与限制字段的对应关系：</p>
          <el-form label-position="top">
            <el-form-item v-for="k in (form.allowedSubmitterKeys||[])" :key="k" :label="`字段: ${k}`">
              <el-select v-model="csvMapping[k]" placeholder="选择对应的 CSV 列">
                <el-option v-for="h in csvHeaders" :key="h" :label="h" :value="h" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <template #footer>
          <el-button @click="csvDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="confirmCsvImport">确认导入</el-button>
        </template>
      </el-dialog>

    </div>
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
      _options: f.type === 'select' ? (Array.isArray(f._options) ? f._options : []) : undefined
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
/* 定义 CSS 变量：OpenAI/Modern SaaS 风格 */
:root {
  --oa-bg: #f9f9f9;
  --oa-text-main: #202123;
  --oa-text-sub: #6e6e80;
  --oa-border: #e5e5e5;
  --oa-primary: #10a37f;
  --oa-radius: 8px;
  --oa-shadow: 0 1px 2px 0 rgba(0,0,0,0.05);
}

.hidden { display: none; }

.page-container {
  height: calc(100vh - 65px);
  background-color: #f7f7f8;
  color: #353740;
  font-family: -apple-system, "system-ui", "Segoe UI", Helvetica, Arial, sans-serif;
  padding-bottom: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.project-form-container {
  width: 100%;
  max-width: 1000px;
  margin: 0 auto;
  padding: 0 20px;

  /* 关键修改：设置为相对定位，作为 Header 绝对定位的参考系 */
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* 顶部 Header - 核心修改：改为悬浮毛玻璃状态 */
.page-header {
  position: absolute; /* 悬浮在内容上方 */
  top: 0;
  left: 0;
  width: 100%; /* 占满容器宽度 */
  z-index: 100; /* 确保层级最高 */

  display: flex;
  align-items: center;
  justify-content: space-between;

  /* 调整高度和内边距 */
  height: 72px;
  padding: 0; /* 移除左右 padding，因为容器已有 padding */

  /* 毛玻璃特效核心代码 */
  background: rgba(247, 247, 248, 0.85); /* 稍微降低透明度，让下方内容隐约可见 */
  backdrop-filter: blur(12px); /* 高斯模糊效果 */
  -webkit-backdrop-filter: blur(12px); /* Safari 支持 */

  /* 可选：添加一条极细的分割线，增加精致感 */
  border-bottom: 1px solid rgba(0,0,0,0.05);

  mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
}

.header-left, .header-right {
  /* 确保内容不会贴边，因为 .page-header 现在 padding 是 0 */
  padding: 0 4px;
}

.content-wrapper {
  flex: 1;
  /* 确保溢出内容可滚动 */
  overflow-y: scroll;

  /* 顶部留白避开毛玻璃 Header */
  padding-top: 88px;
  padding-bottom: 20px;

  /* 方案 A: 标准隐藏 (增加 !important 确保生效) */
  scrollbar-width: none !important; /* Firefox */
  -ms-overflow-style: none !important; /* IE/Edge */
}

/* 方案 B: Webkit 强力隐藏 */
.content-wrapper::-webkit-scrollbar {
  width: 0 !important;
  height: 0 !important;
  display: none !important;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.back-btn {
  cursor: pointer;
  padding: 8px;
  border-radius: 6px;
  color: #6e6e80;
  transition: background 0.2s;
  background: #fff;
  border: 1px solid rgba(0,0,0,0.05);
}
.back-btn:hover { background: #ececf1; }

.title-group {
  display: flex;
  flex-direction: column;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #202123;
  line-height: 1.2;
}

.quota-text {
  font-size: 12px;
  color: #8e8ea0;
}

/* 核心修改：内容区域
   设置为 flex: 1 自动占据剩余高度
   并开启 overflow-y: auto 实现内部滚动
   调整 padding-bottom 减少留白
*/
.content-wrapper {
  flex: 1;
  overflow-y: auto; /* 开启垂直滚动 */
  padding-bottom: 20px; /* 减少底部留白 */

  /* 隐藏滚动条样式 (可选) */
  scrollbar-width: thin;
  scrollbar-color: #d1d5db transparent;
}

/* Form Section 卡片 */
.form-section {
  margin-bottom: 24px;
}

.card-style {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-radius: 12px;
  box-shadow: 0 1px 2px 0 rgba(0,0,0,0.03);
  overflow: hidden; /* 确保子元素不溢出圆角 */
}

/* Section Header 放在卡片内部 */
.section-header {
  padding: 20px 24px 16px; /* 内部 padding */
  border-bottom: 1px solid #f2f2f2;
}

.section-header h3 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 4px 0;
  color: #202123;
}

.section-desc {
  font-size: 13px;
  color: #6e6e80;
  margin: 0;
}

.section-header.with-action {
  display: flex;
  justify-content: space-between;
  align-items: flex-start; /* 对齐顶部 */
}

/* 表单内容区 */
.form-content {
  padding: 24px;
}

/* 分隔线 - 不需要了 */
.section-divider { display: none; }

/* 表单网格系统 */
.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grid-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.grid-row-sidebar {
  display: flex;
  gap: 20px;
}
.flex-grow { flex: 1; }

.grid-row-sidebar > .el-form-item:first-child {
  width: 160px;
  flex-shrink: 0;
}

.grid-row-sidebar#main_key > .el-form-item:first-child {
  width: 300px;
  flex-shrink: 0;
}

/* 开关卡片组 */
.switch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-top: 8px;
}

.switch-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f9f9f9;
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  transition: all 0.2s;
}
.switch-card:hover {
  background: #f0f0f0;
}
.switch-label {
  font-size: 14px;
  font-weight: 500;
  color: #353740;
}

/* 智能识别样式 */
.detect-status-bar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
  color: #15803d;
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 12px;
}
.tags-wrapper {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.advanced-setting-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f9f9f9;
  padding: 10px 16px;
  border-radius: 6px;
}

/* 表格样式重写 - Modern */
.custom-table-wrapper {
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.modern-table :deep(th.el-table__cell) {
  background-color: #f7f7f8;
  color: #6e6e80;
  font-weight: 500;
  font-size: 12px;
  text-transform: uppercase;
  border-bottom: 1px solid #e5e5e5;
  height: 40px;
  padding: 4px 0;
}

.modern-table :deep(td.el-table__cell) {
  border-bottom: 1px solid #f2f2f2;
  padding: 12px 0;
}
.modern-table :deep(.el-table__row:last-child td.el-table__cell) {
  border-bottom: none;
}

.table-input :deep(.el-input__wrapper),
.table-input :deep(.el-select__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding-left: 8px;
}
.table-input :deep(.el-input__wrapper.is-focus),
.table-input :deep(.el-select__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px #10a37f inset !important;
}

.drag-handle-icon {
  cursor: grab;
  color: #d9d9e3;
  font-weight: 900;
  letter-spacing: -1px;
}
.drag-handle-icon:hover { color: #6e6e80; }

/* Element Plus 覆盖 */
:deep(.el-form-item__label) {
  font-weight: 500;
  color: #353740;
  font-size: 13px;
  padding-bottom: 6px;
}
:deep(.el-input__wrapper), :deep(.el-select__wrapper) {
  border-radius: 6px;
  box-shadow: 0 0 0 1px #d9d9e3 inset;
  transition: all 0.2s;
}
:deep(.el-input__wrapper:hover), :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px #8e8ea0 inset;
}
:deep(.el-input__wrapper.is-focus), :deep(.el-select__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(16, 163, 127, 0.2) inset, 0 0 0 1px #10a37f inset;
}
:deep(.el-button) {
  border-radius: 6px;
  font-weight: 500;
}
:deep(.el-button--primary) {
  background-color: #1a1a1a;
  border-color: #1a1a1a;
}
:deep(.el-button--primary:hover) {
  background-color: #353740;
  border-color: #353740;
}

/* 列表编辑器 */
.list-editor-container {
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  overflow: hidden;
}
.editor-toolbar {
  background: #f7f7f8;
  padding: 8px 12px;
  border-bottom: 1px solid #e5e5e5;
  display: flex;
  align-items: center;
  gap: 12px;
}
.editor-hint { font-size: 12px; color: #8e8ea0; }
.code-textarea :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 12px;
  font-family: monospace;
  font-size: 13px;
  background: #fff;
}

/* 拖拽反馈 */
:deep(.dragging) {
  background: #fafafa !important;
  opacity: 0.8;
}
:deep(.drag-over) {
  border-bottom: 2px solid #10a37f !important;
}

/* 响应式 */
@media (max-width: 768px) {
  .page-header { flex-direction: column; align-items: stretch; gap: 12px; }
  .grid-row { grid-template-columns: 1fr; }
  .grid-row-sidebar { flex-direction: column; gap: 0; }
  .section-header { flex-direction: column; gap: 10px; }
}
</style>