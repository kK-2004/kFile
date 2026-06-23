<template>
  <div class="settings-wrap">
  <el-card>
    <template #header>
      <div class="card-header">
        <span>系统设置</span>
      </div>
    </template>

    <el-form :model="form" label-width="180px" style="max-width:640px;">
      <el-form-item label="USER 每月创建项目上限">
        <el-input-number v-model="form.monthlyLimitUser" :min="0" :step="1" />
        <div class="hint">0 表示不限制</div>
      </el-form-item>
      <el-form-item label="USER 总存储配额 (GB)">
        <el-input-number v-model="quotaGB" :min="0" :step="1" />
        <div class="hint">0 表示不限制；建议设置为 1 GB</div>
      </el-form-item>
      <el-form-item label="USER 允许的文件扩展名">
        <el-select v-model="types" multiple filterable allow-create default-first-option placeholder="输入扩展名，如: pdf、zip" style="width:100%">
          <el-option v-for="t in typeSelectable" :key="t" :value="t" :label="t" />
        </el-select>
        <div class="hint">留空表示不限制类型；匹配时忽略大小写，可不带点</div>
      </el-form-item>
      <el-form-item label="MCP 授权回调白名单">
        <el-select v-model="mcpPrefixes" multiple filterable allow-create default-first-option placeholder="输入允许的前缀，如: http://localhost:、" style="width:100%">
        </el-select>
        <div class="hint">MCP 网页授权的 redirect_uri 必须命中以下前缀之一；留空表示拒绝全部回调（最安全）。例如 http://localhost:、https://file.example.com/</div>
      </el-form-item>

      <el-divider content-position="left">kMessage 截止提醒</el-divider>
      <el-form-item label="接收群 ID">
        <el-input v-model="kmsg.groupId" placeholder="飞书群 groupId（接收提醒卡片的群）" clearable style="width:100%"/>
        <div class="hint">由 kMessage 端托管的飞书群 groupId；k-File 仅向该群发送，渠道实例由 kMessage 内部根据 groupId 自动解析。</div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </el-form-item>
    </el-form>
  </el-card>

  <!-- 首页产品路线图管理 -->
  <el-card class="roadmap-card">
    <template #header>
      <div class="card-header">
        <span>首页产品路线图</span>
        <el-button type="primary" size="small" @click="addRoadmapItem">+ 新增条目</el-button>
      </div>
    </template>

    <div class="roadmap-hint">
      拖拽左侧 <span class="drag-handle-demo">⋮⋮</span> 调整顺序（首页从上到下展示）。每项 4 个字段：状态、状态文案、标题、描述。留空保存即清空（首页回退到内置默认）。
    </div>

    <div v-if="!roadmapItems.length" class="roadmap-empty">
      暂无路线图条目。点击右上角「新增条目」开始配置，或留空使用首页默认内容。
    </div>

    <div class="roadmap-list">
      <div
        v-for="(item, index) in roadmapItems"
        :key="item._uid"
        class="roadmap-row"
        :class="{ 'drag-over': dragOverIndex === index, dragging: dragIndex === index }"
        draggable="true"
        @dragstart="onDragStart(index)"
        @dragover.prevent="onDragOver(index)"
        @dragleave="onDragLeave"
        @drop.prevent="onDrop(index)"
        @dragend="onDragEnd"
      >
        <div class="drag-handle" title="拖拽排序">⋮⋮</div>
        <div class="roadmap-fields">
          <el-form-item label="状态" class="rf-status">
            <el-select v-model="item.status" placeholder="选择状态">
              <el-option label="最近上线 (done)" value="done" />
              <el-option label="正在开发 (developing)" value="developing" />
              <el-option label="规划中 (planned)" value="planned" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态文案" class="rf-text">
            <el-input v-model="item.statusText" placeholder="如：最近上线 / 正在开发" />
          </el-form-item>
          <el-form-item label="标题" class="rf-title">
            <el-input v-model="item.title" placeholder="如：MCP AI 助手能力" />
          </el-form-item>
          <el-form-item label="描述" class="rf-desc">
            <el-input v-model="item.desc" type="textarea" :rows="2" placeholder="一句话描述该路线图条目" />
          </el-form-item>
        </div>
        <div class="roadmap-ops">
          <el-button size="small" @click="moveRoadmap(index, -1)" :disabled="index === 0" title="上移">↑</el-button>
          <el-button size="small" @click="moveRoadmap(index, 1)" :disabled="index === roadmapItems.length - 1" title="下移">↓</el-button>
          <el-button size="small" type="danger" @click="removeRoadmapItem(index)" title="删除">删除</el-button>
        </div>
      </div>
    </div>

    <div class="roadmap-actions">
      <el-button @click="resetRoadmapToDefault">恢复为默认内容</el-button>
      <el-button type="primary" @click="saveRoadmap" :loading="roadmapSaving">保存路线图</el-button>
    </div>
  </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const form = ref({ monthlyLimitUser: null, userTotalQuotaBytes: null, allowedFileTypes: [] })
const quotaGB = ref(null)
const types = ref([])
const mcpPrefixes = ref([])
const kmsg = ref({ groupId: '' })
const typeSelectable = computed(()=> Array.from(new Set([...(types.value||[])])))
const saving = ref(false)

const load = async () => {
  try {
    const { data } = await api.adminGetConfig()
    form.value.monthlyLimitUser = data.monthlyLimitUser ?? null
    form.value.userTotalQuotaBytes = data.userTotalQuotaBytes ?? null
    types.value = Array.isArray(data.allowedFileTypes) ? data.allowedFileTypes : []
    mcpPrefixes.value = Array.isArray(data.mcpRedirectPrefixes) ? data.mcpRedirectPrefixes : []
    kmsg.value.groupId = data.kmessageGroupId || ''
    quotaGB.value = (data.userTotalQuotaBytes === null || data.userTotalQuotaBytes === undefined)
      ? null
      : Math.round(Number(data.userTotalQuotaBytes)/1024/1024/1024)
    // 路线图
    const items = Array.isArray(data.roadmapItems) ? data.roadmapItems : []
    roadmapItems.value = items.map(normalizeRoadmap)
  } catch (e) {}
}
onMounted(load)

const save = async () => {
  try {
    saving.value = true
    const payload = {
      monthlyLimitUser: form.value.monthlyLimitUser ?? null,
      userTotalQuotaBytes: (quotaGB.value && quotaGB.value > 0) ? Math.round(Number(quotaGB.value) * 1024 * 1024 * 1024) : 0,
      allowedFileTypes: (types.value || []).map(s => String(s||'').replace(/^\./,'')),
      mcpRedirectPrefixes: (mcpPrefixes.value || []).map(s => String(s||'').trim()).filter(Boolean),
      kmessageGroupId: (kmsg.value.groupId || '').trim()
    }
    if (payload.userTotalQuotaBytes === 0) payload.userTotalQuotaBytes = null
    await api.adminUpdateConfig(payload)
    ElMessage.success('已保存设置')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally { saving.value = false }
}

// ===================== 首页路线图 =====================
let uidSeq = 0
const genUid = () => `rm-${Date.now()}-${uidSeq++}`

// 归一化：保证 4 字段齐全，附带临时 _uid（不提交后端）
const normalizeRoadmap = (raw) => ({
  _uid: genUid(),
  status: String(raw?.status || 'planned'),
  statusText: String(raw?.statusText || ''),
  title: String(raw?.title || ''),
  desc: String(raw?.desc || '')
})

const DEFAULT_ROADMAP = [
  { status: 'developing', statusText: '正在开发', title: '飞书 / 钉钉群机器人集成', desc: '绑定群机器人，实现收集进度定时播报，并在截止时间前自动 @未交人员。' },
  { status: 'developing', statusText: '正在开发', title: '多层级目录与自定义归档', desc: '支持按班级、部门或自定义表单字段，自动将提交的文件分类归档至不同子目录。' },
  { status: 'done', statusText: '最近上线', title: 'MinIO 私有化存储源', desc: '新增对 MinIO 的全面支持，满足纯内网与强隐私安全环境下的私有化部署需求。' },
  { status: 'done', statusText: '最近上线', title: 'MCP AI 助手能力', desc: '开放 Model Context Protocol 接口，可通过第三方 AI 客户端直接查询收集状态。' }
]

const roadmapItems = ref([])
const roadmapSaving = ref(false)

const addRoadmapItem = () => {
  roadmapItems.value.push(normalizeRoadmap({ status: 'planned', statusText: '规划中', title: '', desc: '' }))
}
const removeRoadmapItem = (index) => {
  roadmapItems.value.splice(index, 1)
}
const moveRoadmap = (index, delta) => {
  const target = index + delta
  if (target < 0 || target >= roadmapItems.value.length) return
  const arr = roadmapItems.value
  const [item] = arr.splice(index, 1)
  arr.splice(target, 0, item)
}

// 原生 HTML5 拖拽排序（resetDrag 必须先定义，避免 TDZ）
const dragIndex = ref(-1)
const dragOverIndex = ref(-1)
const resetDrag = () => { dragIndex.value = -1; dragOverIndex.value = -1 }
const onDragStart = (index) => { dragIndex.value = index }
const onDragOver = (index) => { if (dragIndex.value !== -1) dragOverIndex.value = index }
const onDragLeave = () => { dragOverIndex.value = -1 }
const onDrop = (index) => {
  const from = dragIndex.value
  if (from === -1 || from === index) { resetDrag(); return }
  const arr = roadmapItems.value
  const [item] = arr.splice(from, 1)
  arr.splice(index, 0, item)
  resetDrag()
}
const onDragEnd = resetDrag

const resetRoadmapToDefault = async () => {
  try {
    await ElMessageBox.confirm('确定恢复为内置默认路线图？当前编辑内容将被覆盖（仍需点击「保存路线图」生效）。', '提示', { type: 'warning' })
  } catch { return }
  roadmapItems.value = DEFAULT_ROADMAP.map(normalizeRoadmap)
}

const saveRoadmap = async () => {
  // 提交时剥离 _uid；空标题的条目跳过（视为无效）
  const payload = roadmapItems.value
    .map(({ _uid, ...rest }) => rest)
    .filter(it => (it.title || '').trim() !== '')
  try {
    roadmapSaving.value = true
    await api.adminUpdateConfig({ roadmapItems: payload })
    ElMessage.success('路线图已保存')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally { roadmapSaving.value = false }
}
</script>

<style scoped>
.settings-wrap { display: flex; flex-direction: column; gap: 20px; }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
  border-radius: 8px 8px 0 0;
}
.hint { font-size: 12px; color: var(--kf-muted); margin-left: 10px; }

/* 路线图管理 */
.roadmap-card .roadmap-hint {
  font-size: 13px;
  color: var(--kf-muted);
  line-height: 1.7;
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--kf-hover-bg, rgba(0,0,0,0.03));
  border-radius: 8px;
}
.drag-handle-demo { color: var(--kf-primary); font-weight: 700; letter-spacing: -2px; }
.roadmap-empty {
  padding: 32px;
  text-align: center;
  color: var(--kf-muted);
  border: 1px dashed var(--kf-border);
  border-radius: 8px;
}
.roadmap-list { display: flex; flex-direction: column; gap: 12px; }
.roadmap-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--kf-border);
  border-radius: 10px;
  background: var(--kf-bg);
  transition: border-color 0.15s, box-shadow 0.15s, opacity 0.15s;
}
.roadmap-row.drag-over { border-color: var(--kf-primary); box-shadow: 0 0 0 2px rgba(64,158,255,0.15); }
.roadmap-row.dragging { opacity: 0.4; }
.drag-handle {
  flex-shrink: 0;
  cursor: grab;
  user-select: none;
  padding: 4px 2px;
  color: var(--kf-muted);
  font-weight: 700;
  letter-spacing: -2px;
  line-height: 1.4;
}
.drag-handle:active { cursor: grabbing; }
.roadmap-fields {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 16px;
}
.roadmap-fields :deep(.el-form-item) { margin-bottom: 8px; }
.roadmap-fields :deep(.el-form-item__label) { font-size: 13px; padding: 0 8px 0 0; }
.rf-desc { grid-column: 1 / -1; }
.roadmap-ops {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.roadmap-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--kf-border);
}

@media (max-width: 768px) {
  .roadmap-row { flex-direction: column; }
  .roadmap-ops { flex-direction: row; flex-wrap: wrap; }
  .roadmap-fields { grid-template-columns: 1fr; }
}
</style>
