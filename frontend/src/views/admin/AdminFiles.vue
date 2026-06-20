<template>
  <el-card class="files-card">
    <template #header>
      <div class="card-header">
        <div class="header-left">
          <h2 class="page-title">文件管理</h2>
        </div>
        <div class="header-right">
          <!-- SUPER 可切换「全部/我的」；ADMIN 强制 mine -->
          <el-radio-group v-if="isSuper" v-model="scope" size="small" style="margin-right:8px;">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="mine">我的</el-radio-button>
          </el-radio-group>
          <!-- 配额显示（ADMIN 或受限时） -->
          <span v-if="!quota.unlimited" class="quota-hint">
            已用 {{ formatSize(quota.used) }} / {{ formatSize(quota.quota) }}
          </span>
          <el-button type="primary" @click="openMkdir">新建文件夹</el-button>
          <el-button type="success" @click="openUploadModal">上传文件</el-button>
          <el-button @click="reload">刷新</el-button>
        </div>
      </div>
    </template>

    <!-- 面包屑（由后端返回的 path 渲染） + 搜索框 -->
    <div class="toolbar-row">
      <el-breadcrumb separator="/" class="breadcrumb-bar">
        <el-breadcrumb-item>
          <el-link :underline="false" @click="goParent(null)">根目录</el-link>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-for="crumb in breadcrumb" :key="crumb.id">
          <el-link :underline="false" @click="goParent(crumb.id)">{{ crumb.name }}</el-link>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <el-input
        v-model="keyword"
        placeholder="搜索当前目录"
        size="small"
        clearable
        style="width:200px; margin-left:auto;"
        @keyup.enter="onSearch"
        @clear="onSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button size="small" @click="onSearch">搜索</el-button>
    </div>

    <el-table
      ref="tableRef"
      :data="nodes"
      v-loading="loading"
      @selection-change="onSelectionChange"
      row-key="id"
      height="100%"
      style="flex:1; min-height:0;"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column label="名称" min-width="320">
        <template #default="{row}">
          <el-icon v-if="row.type === 'FOLDER'" color="#e6a23c" style="vertical-align:middle"><Folder /></el-icon>
          <el-icon v-else color="#909399" style="vertical-align:middle"><Document /></el-icon>
          <el-link
            :underline="false"
            style="margin-left:6px; vertical-align:middle"
            @click="onNameClick(row)"
          >{{ row.name }}</el-link>
          <el-tag v-if="row.type === 'FILE' && row.storageSource" size="small" type="info" style="margin-left:8px">
            {{ sourceLabel(row.storageSource) }}
          </el-tag>
          <!-- 上传中（队列里正在传的）显示进度；否则 UPLOADING = 中断 -->
          <template v-if="row.status === 'UPLOADING'">
            <el-tag v-if="getUploadItem(row)" size="small" type="success" style="margin-left:8px">上传中</el-tag>
            <el-tag v-else size="small" type="warning" style="margin-left:8px">上传中断</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="140">
        <template #default="{row}">
          <template v-if="row.status === 'UPLOADING' && getUploadItem(row)">
            <el-progress :percentage="getUploadItem(row).percent" :stroke-width="8" style="width:120px" />
          </template>
          <span v-else>{{ row.type === 'FOLDER' ? '-' : formatSize(row.size) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="修改时间" width="200">
        <template #default="{row}">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320">
        <template #default="{row}">
          <el-space>
            <el-button size="small" v-if="row.type === 'FILE' && row.status !== 'UPLOADING'" @click="download(row)">下载</el-button>
            <el-button size="small" v-if="row.type === 'FILE' && row.status !== 'UPLOADING'" @click="shareOne(row)">分享</el-button>
            <el-button size="small" type="warning" v-if="row.status === 'UPLOADING' && !getUploadItem(row)" @click="resumeUpload(row)">续传</el-button>
            <el-button size="small" type="danger" @click="confirmDelete(row)">删除</el-button>
          </el-space>
        </template>
      </el-table-column>
      <template #empty>
        <span>目录为空</span>
      </template>
    </el-table>

    <!-- 分页（每页条数可选） -->
    <div class="files-pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="pageSizeOptions"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="goList"
        @size-change="onPageSizeChange"
      />
    </div>
  </el-card>

  <!-- 多选浮动操作栏（屏幕下方居中） -->
  <transition name="fade-up">
    <div v-if="selection.length" class="selection-bar">
      <div class="selection-count">已选 {{ selection.length }} 项</div>
      <div class="selection-actions">
        <el-button type="primary" @click="openShare">
          <el-icon style="vertical-align:middle;margin-right:4px"><Share /></el-icon>分享
        </el-button>
        <el-button type="danger" @click="confirmDeleteSelection">
          <el-icon style="vertical-align:middle;margin-right:4px"><Delete /></el-icon>删除
        </el-button>
        <el-button link @click="clearSelection">取消</el-button>
      </div>
    </div>
  </transition>

  <!-- 续传用的隐藏文件选择器 -->
  <input ref="resumeFileInput" type="file" style="display:none" @change="onResumeFileSelected"/>

  <!-- 上传模态框：选存储源 + 拖拽上传 + 队列进度（并发3，多余排队） -->
  <el-dialog v-model="uploadModalVisible" title="上传文件" width="640px" @close="onUploadModalClose">
    <div style="margin-bottom:12px; display:flex; align-items:center; gap:8px;">
      <span class="source-label">存储源</span>
      <el-select v-model="uploadSource" size="default" style="width:140px" :disabled="sources.length === 0">
        <el-option v-for="s in sources" :key="s.id" :value="s.id" :label="s.label" />
      </el-select>
      <span style="color:#909399; font-size:12px; margin-left:auto;">拖拽到下方区域上传，支持多选；同时上传 3 个，多余排队</span>
    </div>

    <!-- 拖拽上传区 -->
    <el-upload
      :show-file-list="false"
      :auto-upload="false"
      :on-change="onUploadFilesAdded"
      drag
      multiple
      :disabled="!uploadSource"
      class="upload-dragger"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
      <template #tip>
        <div class="upload-tip">大于 50MB 的 MinIO 文件自动分片断点续传（分片并发上传）</div>
      </template>
    </el-upload>

    <!-- 上传队列（固定高度 + 滚动条） -->
    <div class="upload-queue">
      <div v-if="!uploads.length" style="color:#909399; text-align:center; padding:24px 0;">
        暂无上传任务
      </div>
      <div v-for="u in uploads" :key="u.uid" class="upload-item">
        <div class="upload-item-head">
          <span class="upload-item-name">{{ u.name }}</span>
          <el-tag size="small" :type="queueTagType(u)">
            {{ queueStatusText(u) }}
          </el-tag>
          <el-button v-if="u.status !== 'uploading' && u.status !== 'queued'" link size="small" @click="removeUpload(u.uid)">×</el-button>
        </div>
        <el-progress
          v-if="u.status === 'uploading'"
          :percentage="u.percent"
          :stroke-width="6"
          :show-text="false"
        />
        <div v-if="u.status === 'error'" style="color:#f56c6c; font-size:12px;">{{ u.error }}</div>
      </div>
    </div>
  </el-dialog>

  <!-- 新建文件夹 -->
  <el-dialog v-model="mkdirVisible" title="新建文件夹" width="420px">
    <el-form @submit.prevent>
      <el-form-item label="文件夹名">
        <el-input v-model="mkdirName" placeholder="如 2026" @keyup.enter="doMkdir" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="mkdirVisible=false">取消</el-button>
      <el-button type="primary" :loading="mkdirLoading" @click="doMkdir">创建</el-button>
    </template>
  </el-dialog>

  <!-- 分享 -->
  <el-dialog v-model="shareVisible" title="分享文件" width="520px">
    <el-form label-width="100px" v-if="!shareResult">
      <el-form-item label="过期时间">
        <el-select v-model="shareExpire" style="width:100%">
          <el-option v-for="o in expireOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="下载包名">
        <el-input v-model="shareFilename" placeholder="如 batch.zip，可留空" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="shareLoading" @click="doShare">生成分享链接</el-button>
      </el-form-item>
    </el-form>
    <div v-else class="share-result">
      <el-input :model-value="shareResult" readonly>
        <template #append>
          <el-button @click="copyShare">复制</el-button>
        </template>
      </el-input>
      <div class="share-hint">{{ shareExpire === 0 ? '永久有效，下载时动态生成短时效链接。' : `链接将在 ${shareExpireLabel} 后失效。` }}</div>
      <el-button style="margin-top:8px" @click="resetShare">再生成一个</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../../api'
import { useAuthStore } from '../../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder, Document, UploadFilled, Share, Delete, Search } from '@element-plus/icons-vue'
import SparkMD5 from 'spark-md5'

const auth = useAuthStore()
const isSuper = computed(() => auth.user && (auth.user.role||'').toUpperCase() === 'SUPER')
// scope: ADMIN 强制 mine（只看自己上传的）；SUPER 默认 all，可切 mine
const scope = ref('all') // all | mine
// 配额（ADMIN 显示已用/总额；SUPER 不限）
const quota = ref({ used: 0, quota: 0, unlimited: true })
// 搜索（当前目录 name like）
const keyword = ref('')
// 续传
const resumeFileInput = ref(null)
const pendingResume = ref(null) // {row} 待续传的文件记录
const resumeUpload = (row) => {
  pendingResume.value = row
  resumeFileInput.value?.click()
}
const onResumeFileSelected = async (e) => {
  const file = e.target.files?.[0]
  e.target.value = '' // 重置以便重选
  if (!file || !pendingResume.value) return
  const row = pendingResume.value
  pendingResume.value = null
  // 校验文件名 + 大小一致（快速校验，跳过 MD5 预计算；完整性由 complete 的 part ETag 兜底）
  if (file.name !== row.originalName && file.name !== row.name) {
    ElMessage.warning(`请选择同一个文件（期望：${row.originalName || row.name}）`)
    return
  }
  if (row.size && file.size !== row.size) {
    ElMessage.error(`文件大小不一致（期望 ${row.size} 字节，实际 ${file.size} 字节），请选择原文件`)
    return
  }
  // 直接走分片续传（uploadChunked 内部算 MD5 → init 检测续传 → 只传剩余 chunk）
  const uid = `resume-${Date.now()}`
  const item = ref({ uid, name: file.name, percent: 0, status: 'uploading', mode: 'chunk', error: '', _file: file })
  uploads.value.push(item.value)
  uploadModalVisible.value = true
  activeCount++
  processUpload(item.value)
}

const SOURCE_KEY = 'kfile.fileManager.uploadSource'
const MULTIPART_THRESHOLD = 50 * 1024 * 1024 // >50MB 走分片
const CHUNK_SIZE = 5 * 1024 * 1024            // 5MB 分片

const sources = ref([])
// 上传源：记忆到 localStorage，默认 minio
const uploadSource = ref(localStorage.getItem(SOURCE_KEY) || 'minio')
watch(uploadSource, (v) => { if (v) localStorage.setItem(SOURCE_KEY, v) })

// 上传模态框
const uploadModalVisible = ref(false)
const openUploadModal = () => { uploadModalVisible.value = true }
const onUploadModalClose = () => {
  // 关闭模态框不清空正在上传的 item（后台继续传）；只移除已完成/失败的
  uploads.value = uploads.value.filter(u => u.status === 'uploading' || u.status === 'queued')
}

const currentParentId = ref(null)   // null = 根
const nodes = ref([])
const breadcrumb = ref([])
const loading = ref(false)
const selection = ref([])

// 分页
const currentPage = ref(1)          // el-pagination 从 1 开始
const pageSize = ref(15)
const total = ref(0)
const pageSizeOptions = [10, 15, 20, 30, 50]
const goList = (page) => {
  currentPage.value = page
  load()
}
const onPageSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  load()
}

const mkdirVisible = ref(false)
const mkdirName = ref('')
const mkdirLoading = ref(false)

// 上传面板
const uploads = ref([])   // {uid, name, percent, status}

// 分享
const shareVisible = ref(false)
const shareExpire = ref(3600)
const shareFilename = ref('')
const shareLoading = ref(false)
const shareResult = ref('')
const expireOptions = [
  { value: 300, label: '5 分钟' },
  { value: 600, label: '10 分钟' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
  { value: 86400, label: '1 天' },
  { value: 604800, label: '7 天' },
  { value: 2592000, label: '30 天' },
  { value: 0, label: '永久' }
]
const shareExpireLabel = computed(() =>
  expireOptions.find(o => o.value === shareExpire.value)?.label || `${shareExpire.value} 秒`
)

const sourceLabel = (id) => sources.value.find(s => s.id === id)?.label || id

const loadSources = async () => {
  try {
    const { data } = await api.adminFileSources()
    sources.value = Array.isArray(data) ? data : []
    // 当前选中的源若已不在启用列表，回退到第一个
    if (sources.value.length && !sources.value.find(s => s.id === uploadSource.value)) {
      uploadSource.value = sources.value[0].id
    }
  } catch {
    sources.value = []
  }
}

const load = async () => {
  loading.value = true
  try {
    // ADMIN 强制 scope=mine
    const effScope = isSuper.value ? scope.value : 'mine'
    const { data } = await api.adminFileList(currentParentId.value, currentPage.value - 1, pageSize.value, effScope, keyword.value)
    nodes.value = Array.isArray(data?.nodes) ? data.nodes : []
    breadcrumb.value = Array.isArray(data?.path) ? data.path : []
    total.value = data?.total ?? 0
    if (data?.totalPages && currentPage.value > data.totalPages && data.totalPages > 0) {
      currentPage.value = data.totalPages
      return load()
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
    nodes.value = []
  } finally {
    loading.value = false
  }
}

const loadQuota = async () => {
  try {
    const { data } = await api.adminFileQuota()
    quota.value = data || { used: 0, quota: 0, unlimited: true }
  } catch { /* ignore */ }
}

const reload = () => { load(); loadQuota() }

const onSearch = () => {
  currentPage.value = 1
  load()
}

const goParent = (id) => {
  currentParentId.value = id
  currentPage.value = 1
  load()
}

const onNameClick = (row) => {
  if (row.type === 'FOLDER') goParent(row.id)
}

const openMkdir = () => {
  mkdirName.value = ''
  mkdirVisible.value = true
}
const doMkdir = async () => {
  if (!mkdirName.value.trim()) { ElMessage.warning('请输入文件夹名'); return }
  mkdirLoading.value = true
  try {
    await api.adminFileMkdir({ parentId: currentParentId.value, name: mkdirName.value.trim() })
    ElMessage.success('已创建文件夹')
    mkdirVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    mkdirLoading.value = false
  }
}

const confirmDelete = async (row) => {
  const msg = row.type === 'FOLDER'
    ? `将递归删除文件夹「${row.name}」及其所有子项，此操作不可恢复，确认删除？`
    : `确认删除「${row.key || row.name}」？此操作不可恢复。`
  try {
    await ElMessageBox.confirm(msg, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    const { data } = await api.adminFileDelete(row.id)
    if (data?.failedObjects > 0) {
      ElMessage.warning(`已删除 ${data.deletedDb} 条记录，其中 ${data.failedObjects} 个对象存储删除失败`)
    } else {
      ElMessage.success('已删除')
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

const download = async (row) => {
  try {
    const { data } = await api.adminFileDownloadUrl(row.id, { download: true })
    if (!data?.url) { ElMessage.error('获取下载链接失败'); return }
    const a = document.createElement('a')
    a.href = data.url
    a.target = '_blank'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '下载失败')
  }
}

// 上传队列调度：同时最多并发 3，多余排队
const MAX_CONCURRENT = 3
let activeCount = 0

// el-upload on-change 回调（auto-upload=false）：多选时逐个触发，统一入队
const onUploadFilesAdded = (file) => {
  if (!uploadSource.value) {
    ElMessage.error('请先选择存储源')
    return
  }
  // file 是 UploadFile 对象，取 raw File
  const raw = file.raw || file
  if (!raw) return
  const uid = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const useChunk = uploadSource.value === 'minio' && raw.size > MULTIPART_THRESHOLD
  uploads.value.push({ uid, name: raw.name, percent: 0, status: 'queued', mode: useChunk ? 'chunk' : 'single', error: '', _file: raw })
  pumpQueue()
}

// 调度：补足并发槽位
const pumpQueue = () => {
  while (activeCount < MAX_CONCURRENT) {
    const next = uploads.value.find(u => u.status === 'queued')
    if (!next) break
    activeCount++
    processUpload(next)
  }
}

// 实际执行单个上传
const processUpload = async (item) => {
  const file = item._file
  item.status = 'uploading'
  try {
    if (item.mode === 'chunk') {
      await uploadChunked(file, item)
    } else {
      await uploadSingle(file, item)
    }
    item.percent = 100
    item.status = 'done'
    ElMessage.success(`已上传：${file.name}`)
    await load()
    loadQuota() // 刷新配额显示
  } catch (e) {
    item.status = 'error'
    item.error = e?.response?.data?.message || e?.message || ''
    ElMessage.error(`上传失败：${file.name}（${item.error}）`)
  } finally {
    activeCount--
    // 清理已完成/失败太久的，保留最近若干
    refreshUploads()
    pumpQueue()
  }
}

// 从上传队列里找正在传的 item（按文件名匹配）；返回 null 表示不在队列（=中断）
const getUploadItem = (row) => {
  const item = uploads.value.find(u => u.name === (row.originalName || row.name) && (u.status === 'uploading' || u.status === 'queued'))
  return item && item.status === 'uploading' ? item : null
}

const queueTagType = (u) => u.status === 'done' ? 'success' : (u.status === 'error' ? 'danger' : (u.status === 'queued' ? 'info' : 'warning'))
const queueStatusText = (u) => {
  if (u.status === 'done') return '完成'
  if (u.status === 'error') return '失败'
  if (u.status === 'queued') return '排队中'
  return (u.mode === 'chunk' ? '分片 ' : '') + u.percent + '%'
}

// 单次直传（≤50MB 或 oss）：init→PUT→complete
const uploadSingle = async (file, item) => {
  const contentType = file.type || 'application/octet-stream'
  const { data: init } = await api.adminFileUploadInit({
    parentId: currentParentId.value,
    source: uploadSource.value,
    originalName: file.name,
    contentType
  })
  // 如果是续传命中的已上传文件（理论上单次不命中），直接返回
  await api.directPutObject(init.putUrl, file, contentType, (e) => {
    if (e.total) item.percent = Math.min(99, Math.round((e.loaded / e.total) * 100))
  })
  await api.adminFileUploadComplete({
    parentId: currentParentId.value,
    storageSource: init.storageSource,
    storageKey: init.storageKey,
    originalName: file.name,
    contentType,
    size: file.size
  })
}

// 分片断点续传（>50MB，minio）：SparkMD5 → init → 循环(sign→PUT→收集etag) → complete
const uploadChunked = async (file, item) => {
  const contentType = file.type || 'application/octet-stream'
  // 1. SparkMD5 流式增量算整文件 MD5
  const contentMd5 = await computeFileMd5(file, (p) => { item.percent = Math.min(5, Math.round(p * 5)) })
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

  // 2. init（后端 createMultipartUpload + ListParts 续传判断）
  const { data: init } = await api.adminFileMultipartInit({
    parentId: currentParentId.value,
    originalName: file.name,
    contentType,
    fileSize: file.size,
    totalChunks,
    contentMd5
  })
  if (init.alreadyDone) {
    // 已上传完成（幂等命中）
    item.percent = 100
    return
  }

  // uploadedParts 是 [{partNumber, etag}]（S3 partNumber 从 1 开始）
  const uploadedMap = new Map()
  for (const p of (init.uploadedParts || [])) uploadedMap.set(p.partNumber, p.etag)
  let completedBytes = 0
  for (let i = 0; i < totalChunks; i++) {
    if (uploadedMap.has(i + 1)) completedBytes += chunkByteLength(file.size, i)
  }

  // 3. 收集待上传 chunk + 已完成（复用 etag）
  const parts = new Array(totalChunks)
  const pendingTasks = []
  for (let chunkId = 0; chunkId < totalChunks; chunkId++) {
    const partNumber = chunkId + 1
    if (uploadedMap.has(partNumber)) {
      parts[chunkId] = { chunkId, etag: uploadedMap.get(partNumber) }
      continue
    }
    pendingTasks.push(chunkId)
  }

  // 4. 并发上传待传 chunk（并发 5），每个完成更新进度
  const CONCURRENCY = 5
  let cursor = 0
  const uploadOne = async () => {
    while (cursor < pendingTasks.length) {
      const chunkId = pendingTasks[cursor++]
      const start = chunkId * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.size)
      const { data: sign } = await api.adminFileMultipartSign({ contentMd5, chunkId })
      const blob = file.slice(start, end)
      const resp = await api.directPutObject(sign.url, blob, contentType, null)
      const etag = (resp.headers.etag || resp.headers.ETag || '').replace(/"/g, '')
      parts[chunkId] = { chunkId, etag }
      completedBytes += (end - start)
      item.percent = Math.min(99, Math.round((completedBytes / file.size) * 100))
    }
  }
  const workers = []
  for (let i = 0; i < Math.min(CONCURRENCY, pendingTasks.length); i++) workers.push(uploadOne())
  await Promise.all(workers)

  // 5. complete（后端校验 part 数 + completeMultipartUpload，MinIO 自动校验每个 part ETag）
  await api.adminFileMultipartComplete({ contentMd5, parts })
}

// SparkMD5 流式增量算整文件 MD5；onProgress(0~1) 反馈 MD5 计算进度
const computeFileMd5 = (file, onProgress) => new Promise((resolve, reject) => {
  const spark = new SparkMD5.ArrayBuffer()
  const reader = new FileReader()
  const total = Math.ceil(file.size / CHUNK_SIZE)
  let cur = 0
  const loadNext = () => {
    const start = cur * CHUNK_SIZE
    const end = Math.min(start + CHUNK_SIZE, file.size)
    reader.readAsArrayBuffer(file.slice(start, end))
  }
  reader.onload = (e) => {
    spark.append(e.target.result)
    cur++
    if (onProgress) onProgress(cur / total)
    if (cur < total) loadNext()
    else resolve(spark.end())
  }
  reader.onerror = (e) => reject(e)
  loadNext()
})

const chunkByteLength = (fileSize, chunkId) => {
  const start = chunkId * CHUNK_SIZE
  const end = Math.min(start + CHUNK_SIZE, fileSize)
  return end - start
}


// 队列维护：保留所有项（done/error 仍可见，用户可手动 × 或关闭弹窗清空）
const refreshUploads = () => {
  // 仅做容量保护：done/error 项超过 50 个时移除最早的，避免无限增长
  if (uploads.value.length > 50) {
    uploads.value = uploads.value.filter((u, i) => u.status === 'uploading' || u.status === 'queued' || i >= uploads.value.length - 50)
  }
}

const removeUpload = (uid) => {
  uploads.value = uploads.value.filter(u => u.uid !== uid)
}

const tableRef = ref(null)
const onSelectionChange = (rows) => { selection.value = rows }

const clearSelection = () => {
  tableRef.value?.clearSelection()
  selection.value = []
}

// 批量删除选中的节点（文件 + 文件夹，文件夹递归）
const confirmDeleteSelection = async () => {
  if (!selection.value.length) return
  const folders = selection.value.filter(r => r.type === 'FOLDER').length
  const files = selection.value.filter(r => r.type === 'FILE').length
  const desc = [
    folders ? `${folders} 个文件夹（含其下所有内容）` : '',
    files ? `${files} 个文件` : ''
  ].filter(Boolean).join('、')
  try {
    await ElMessageBox.confirm(
      `将删除选中的 ${desc}，此操作不可恢复，确认删除？`,
      '批量删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  let failed = 0
  for (const row of selection.value) {
    try {
      await api.adminFileDelete(row.id)
    } catch {
      failed++
    }
  }
  if (failed > 0) ElMessage.warning(`${selection.value.length - failed} 项已删除，${failed} 项删除失败`)
  else ElMessage.success('已删除选中项')
  clearSelection()
  await load()
  loadQuota()
}

// 待分享的文件 id 列表（支持多选勾选 + 单文件直接分享）
const shareTargetIds = ref([])
const openShare = () => {
  if (!selection.value.length) { ElMessage.warning('请先勾选要分享的文件'); return }
  shareTargetIds.value = selection.value.map(r => r.id)
  shareResult.value = ''
  shareFilename.value = ''
  shareExpire.value = 3600
  shareVisible.value = true
}
// 单文件直接分享（无需勾选）
const shareOne = (row) => {
  shareTargetIds.value = [row.id]
  shareResult.value = ''
  // 单文件默认打包名用真实文件名
  shareFilename.value = row.originalName || row.name || ''
  shareExpire.value = 3600
  shareVisible.value = true
}
const doShare = async () => {
  shareLoading.value = true
  try {
    const { data } = await api.adminFileShare({
      fileIds: shareTargetIds.value,
      expireSeconds: shareExpire.value,
      filename: shareFilename.value
    })
    if (!data?.code) { ElMessage.error('生成分享失败'); return }
    shareResult.value = `${window.location.origin}/share?s=${data.code}`
    ElMessage.success('分享链接已生成')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '分享失败')
  } finally {
    shareLoading.value = false
  }
}
const copyShare = async () => {
  try {
    await navigator.clipboard.writeText(shareResult.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}
const resetShare = () => { shareResult.value = '' }

const formatSize = (b) => {
  if (b == null) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, n = Number(b)
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}
const formatTime = (t) => {
  if (!t) return '-'
  try { return new Date(t).toLocaleString() } catch { return String(t) }
}

// scope 切换（仅 SUPER）→ 重新加载
watch(scope, () => { currentPage.value = 1; load() })

onMounted(async () => {
  if (!auth.loaded) await auth.loadMe()
  await loadSources()
  await load()
  await loadQuota()
})
</script>

<style scoped>
/* 卡片撑满可视区高度（main 从固定头部 64px 处开始，卡片占满剩余到底部） */
.files-card {
  height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  border-radius: 0;
  border-left: none;
  border-right: none;
  border-bottom: none;
}
.files-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: visible;
}
.files-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}

.toolbar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.breadcrumb-bar { flex: 1; min-width: 0; overflow: hidden; }
.quota-hint {
  font-size: 12px;
  color: var(--kf-muted);
  margin-right: 4px;
  white-space: nowrap;
}

/* 多选浮动操作栏（屏幕下方居中） */
.selection-bar {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  background: var(--kf-bg);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}
.selection-count {
  font-size: 13px;
  color: var(--kf-muted);
  font-weight: 500;
}
.selection-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.fade-up-enter-active, .fade-up-leave-active { transition: all 0.25s ease; }
.fade-up-enter-from, .fade-up-leave-to { opacity: 0; transform: translateX(-50%) translateY(20px); }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.header-left { display: flex; align-items: center; }
.header-right { display: flex; align-items: center; gap: 8px; }
.page-title { margin: 0; font-size: 18px; font-weight: 600; }
.source-label { color: var(--kf-muted); font-size: 13px; }

/* 拖拽上传区 */
.upload-dragger {
  width: 100%;
}
.upload-dragger :deep(.el-upload-dragger) {
  width: 100%;
  padding: 18px 20px;
}
.upload-tip {
  color: var(--kf-muted);
  font-size: 12px;
  text-align: center;
  margin-top: 4px;
}

/* 上传队列：固定高度 + 滚动条 */
.upload-queue {
  margin-top: 16px;
  max-height: 320px;
  overflow-y: auto;
  padding: 10px 12px;
  background: var(--kf-surface-dim);
  border: 1px solid var(--kf-border);
  border-radius: 6px;
}
.upload-item {
  margin-bottom: 10px;
}
.upload-item:last-child { margin-bottom: 0; }
.upload-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.upload-item-name {
  flex: 1;
  font-size: 13px;
  color: var(--kf-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-hint {
  margin-top: 8px;
  color: var(--kf-muted);
  font-size: 12px;
}
</style>
