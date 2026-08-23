<template>
  <div class="open-apps-wrap">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>开放应用</span>
          <el-button type="primary" size="small" @click="openCreate">+ 新建应用</el-button>
        </div>
      </template>

      <el-table :data="apps" v-loading="loading" empty-text="暂无开放应用">
        <el-table-column prop="appName" label="应用名" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
        <el-table-column label="上传根路径" min-width="180">
          <template #default="{ row }">
            <code class="root-path">{{ row.rootPathEffective }}</code>
            <el-tag v-if="!row.rootPath" size="small" type="info" class="default-tag">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="v => toggleEnabled(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">
            <span>{{ formatTime(row.lastUsedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span>{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="rotate(row)">轮换</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建应用 -->
    <el-dialog v-model="createVisible" title="新建开放应用" width="520px">
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="应用名">
          <el-input v-model="createForm.appName" placeholder="如 crm（唯一，不含 /）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" placeholder="选填" />
        </el-form-item>
        <el-form-item label="上传根路径">
          <el-input v-model="createForm.rootPath" placeholder="选填，如 crm/2026；留空=默认 开放应用/<appName>" />
          <div class="hint">斜杠分隔的虚拟目录；修改已有应用根路径时会同步迁移存量文件</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑应用（description / rootPath，rootPath 变更触发同步迁移） -->
    <el-dialog v-model="editVisible" title="编辑开放应用" width="520px">
      <el-form :model="editForm" label-width="110px">
        <el-form-item label="应用名">
          <el-input :model-value="editForm.appName" disabled />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" />
        </el-form-item>
        <el-form-item label="上传根路径">
          <el-input v-model="editForm.rootPath" :placeholder="`清空=默认 开放应用/${editForm.appName}`" />
          <div class="hint">
            保存后立即生效：<b>该应用全部已上传文件将同步迁移到新路径</b>，文件较多时耗时较长、请勿关闭页面
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">
          {{ editing ? '迁移中…' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- token 一次性展示（创建 / 轮换共用） -->
    <el-dialog v-model="tokenVisible" title="appToken（仅显示一次）" width="560px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" class="token-alert"
                title="请立即复制保存：关闭后无法再次查看，落库仅存哈希。" />
      <div class="token-box">
        <code>{{ tokenShown }}</code>
        <el-button size="small" :type="tokenCopied ? 'success' : 'primary'" @click="copyToken">
          {{ tokenCopied ? '已复制' : '复制' }}
        </el-button>
      </div>
      <div class="hint">接入方以 HTTP 头 <code>Authorization: Bearer {{ tokenShown }}</code> 调用开放 API。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { copyText } from '../../utils/clipboard'

const apps = ref([])
const loading = ref(false)

const formatTime = (v) => {
  if (!v) return '—'
  try {
    const d = new Date(v)
    const pad = n => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch { return v }
}

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.adminOpenAppList()
    apps.value = Array.isArray(data) ? data : []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally { loading.value = false }
}
onMounted(load)

// ===== 新建 =====
const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({ appName: '', description: '', rootPath: '' })
const openCreate = () => {
  createForm.value = { appName: '', description: '', rootPath: '' }
  createVisible.value = true
}
const submitCreate = async () => {
  const name = (createForm.value.appName || '').trim()
  if (!name) { ElMessage.warning('请输入应用名'); return }
  try {
    creating.value = true
    const { data } = await api.adminOpenAppCreate({
      appName: name,
      description: (createForm.value.description || '').trim(),
      rootPath: (createForm.value.rootPath || '').trim()
    })
    createVisible.value = false
    showToken(data?.token, `应用「${name}」已创建`)
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally { creating.value = false }
}

// ===== 编辑（rootPath 变更 = 同步迁移） =====
const editVisible = ref(false)
const editing = ref(false)
const editForm = ref({ id: null, appName: '', description: '', rootPath: '' })
const openEdit = (row) => {
  editForm.value = { id: row.id, appName: row.appName, description: row.description || '', rootPath: row.rootPath || '' }
  editVisible.value = true
}
const submitEdit = async () => {
  const f = editForm.value
  const newRoot = (f.rootPath || '').trim()
  const rootChanged = newRoot !== (apps.value.find(a => a.id === f.id)?.rootPath || '')
  if (rootChanged) {
    try {
      await ElMessageBox.confirm(
        '修改上传根路径会把该应用全部已上传文件同步迁移到新路径，完成后才返回。确认继续？',
        '根路径迁移', { type: 'warning', confirmButtonText: '开始迁移' }
      )
    } catch { return }
  }
  try {
    editing.value = true
    const payload = { description: (f.description || '').trim() }
    if (rootChanged) payload.rootPath = newRoot
    const { data } = await api.adminOpenAppUpdate(f.id, payload)
    editVisible.value = false
    if (rootChanged && data?.migration) {
      const m = data.migration
      let msg = `已迁移 ${m.moved} 个文件`
      if (m.skipped > 0) msg += `，跳过 ${m.skipped} 个（${(m.skippedFiles || []).join('；')}）`
      ElMessage({ type: m.skipped > 0 ? 'warning' : 'success', message: msg, duration: 6000 })
    } else {
      ElMessage.success('已保存')
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally { editing.value = false }
}

// ===== 轮换 =====
const rotate = async (row) => {
  try {
    await ElMessageBox.confirm(
      `轮换「${row.appName}」的 appToken？旧 token 立即失效，接入方需更新为新 token。`,
      '轮换确认', { type: 'warning', confirmButtonText: '轮换' }
    )
  } catch { return }
  try {
    const { data } = await api.adminOpenAppRotate(row.id)
    showToken(data?.token, '已轮换，旧 token 立即失效')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '轮换失败')
  }
}

// ===== 删除（级联清理文件） =====
const humanSize = (bytes) => {
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return bytes + ' B'
}
const remove = async (row) => {
  let stats = null
  try {
    const { data } = await api.adminOpenAppStats(row.id)
    stats = data
  } catch { /* 统计失败不阻断确认 */ }
  const fileDesc = stats
    ? `该应用名下 <b>${stats.fileCount}</b> 个文件（共 <b>${humanSize(stats.totalBytes)}</b>）将被<b>永久删除且不可恢复</b>，应用记录与 token 一并删除。`
    : '该应用名下全部文件将被<b>永久删除且不可恢复</b>，应用记录与 token 一并删除。'
  try {
    await ElMessageBox.confirm(
      `删除应用「${row.appName}」？${fileDesc}`,
      '删除确认',
      { type: 'error', confirmButtonText: '确认删除', dangerouslyUseHTMLString: true }
    )
  } catch { return }
  try {
    const { data } = await api.adminOpenAppDelete(row.id)
    let msg = `已删除应用及 ${data?.deletedFiles ?? 0} 个文件`
    if (data?.failedObjects > 0) msg += `（${data.failedObjects} 个对象删除失败，可稍后手动清理）`
    ElMessage({ type: data?.failedObjects > 0 ? 'warning' : 'success', message: msg, duration: 6000 })
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

// ===== 启停 =====
const toggleEnabled = async (row, enabled) => {
  try {
    await api.adminOpenAppSetEnabled(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已禁用（token 立即失效）')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

// ===== token 一次性展示 =====
const tokenVisible = ref(false)
const tokenShown = ref('')
const tokenCopied = ref(false)
const showToken = (token, tip) => {
  tokenShown.value = token || ''
  tokenCopied.value = false
  tokenVisible.value = true
  if (tip) ElMessage.success(tip)
}
const copyToken = async () => {
  try {
    await copyText(tokenShown.value)
    tokenCopied.value = true
    ElMessage.success('已复制')
    setTimeout(() => { tokenCopied.value = false }, 2000)
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<style scoped>
.open-apps-wrap { display: flex; flex-direction: column; gap: 20px; }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  background-color: var(--kf-header-bg);
  border-bottom: 1px solid var(--kf-border-color);
  border-radius: 8px 8px 0 0;
}
.hint { font-size: 12px; color: var(--kf-muted); margin-left: 10px; line-height: 1.8; }
.intro { margin: 0 0 14px 4px; }
.intro code, .hint code {
  background: var(--kf-hover-bg, rgba(0,0,0,0.05));
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}
.root-path {
  background: var(--kf-hover-bg, rgba(0,0,0,0.05));
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}
.default-tag { margin-left: 6px; }
.token-alert { margin-bottom: 12px; }
.token-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border: 1px dashed var(--kf-border);
  border-radius: 8px;
  background: var(--kf-hover-bg, rgba(0,0,0,0.03));
  margin-bottom: 8px;
}
.token-box code {
  flex: 1;
  word-break: break-all;
  font-size: 13px;
}
</style>
