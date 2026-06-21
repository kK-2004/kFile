<template>
  <div class="share-page">
    <!-- error state -->
    <div v-if="error" class="error-fullscreen">
      <div class="error-card" :class="errorClass">
        <div class="error-icon-wrap">
          <svg v-if="errorType === 'expired'" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <svg v-else-if="errorType === 'notfound'" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#6b7280" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
          <svg v-else width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
        </div>
        <h2 class="error-title">{{ errorTitle }}</h2>
        <p class="error-text">{{ error }}</p>
      </div>
    </div>

    <template v-else-if="shareData">
      <!-- hero -->
      <section class="hero">
        <span class="hero-badge">项目名称</span>
        <h1 class="hero-title">{{ shareData.n }}</h1>
      </section>

      <!-- main content -->
      <div class="bento-grid">
        <!-- file list card -->
        <div class="card file-card">
          <div class="file-card-header">
            <h2 class="card-title">文件列表</h2>
            <div class="view-toggle" v-if="hasFolders">
              <button class="toggle-btn" :class="{ active: viewMode === 'folder' }" @click="viewMode = 'folder'">文件夹视图</button>
              <button class="toggle-btn" :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">文件列表</button>
            </div>
            <span class="file-count">共 {{ shareData.e.length }} 项</span>
          </div>
          <div class="file-table-wrap">
            <!-- 文件夹视图 -->
            <template v-if="viewMode === 'folder' && hasFolders">
              <div class="folder-tree">
                <template v-for="(fld, i) in folderTree.folders" :key="'f'+i">
                  <folder-node :node="fld" :depth="0" />
                </template>
                <div v-for="(f, idx) in folderTree.files" :key="'rf'+idx" class="tree-row tree-file" :style="{paddingLeft: '8px'}">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                  <span class="tree-name">{{ f.f }}</span>
                  <span class="tree-size">{{ f.s != null ? formatSize(f.s) : '—' }}</span>
                </div>
              </div>
            </template>
            <!-- 文件列表视图 -->
            <template v-else>
              <table class="file-table">
                <thead>
                  <tr>
                    <th style="width:36px;"><input type="checkbox" :checked="allSelected" @change="toggleSelectAll($event.target.checked)" /></th>
                    <th>文件名</th>
                    <th class="text-right" style="width:70px;">下载</th>
                    <th class="text-right" style="width:80px;">大小</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(f, i) in visibleFiles" :key="i"
                      :class="{ 'row-selected': selectedIndexes.has(f._idx) }"
                      @click="onRowClick($event, f._idx)">
                    <td @click.stop><input type="checkbox" :checked="selectedIndexes.has(f._idx)" @change="toggleSelect(f._idx)" /></td>
                    <td>
                      <div class="file-name-cell">
                        <div class="file-type-icon" :class="fileTypeClass(f.f)">
                          <svg v-if="fileTypeClass(f.f)==='type-zip'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8v13H3V8"/><path d="M1 3h22v5H1z"/><path d="M10 12h4"/></svg>
                          <svg v-else-if="fileTypeClass(f.f)==='type-img'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
                          <svg v-else-if="fileTypeClass(f.f)==='type-video'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>
                          <svg v-else-if="fileTypeClass(f.f)==='type-pdf'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                          <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                        </div>
                        <span class="file-name-text">{{ f.p ? f.p + '/' : '' }}{{ f.f }}</span>
                      </div>
                    </td>
                    <td class="text-right">
                      <button class="dl-single-btn" @click.stop="downloadSingle(f._idx)" title="下载此文件">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                      </button>
                    </td>
                    <td class="text-right file-size-cell">{{ f.s != null ? formatSize(f.s) : '—' }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-if="shareData.e.length > 10" class="file-card-footer">
                <button class="toggle-more-btn" @click="showAllFiles = !showAllFiles">
                  {{ showAllFiles ? '收起' : `查看全部 ${shareData.e.length} 个文件` }}
                </button>
              </div>
            </template>
          </div>
        </div>

        <!-- sidebar -->
        <div class="sidebar">
          <!-- download card -->
          <div class="card download-card">
            <button class="dl-btn" :disabled="downloading || expired" @click="startDownload">
              <svg v-if="!downloading && !downloadDone" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              <svg v-if="downloading" class="spin" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 2a10 10 0 0 1 10 10"/></svg>
              <svg v-if="downloadDone" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
              <span>{{ downloading ? `打包中 ${progress}%` : downloadDone ? '下载完成' : expired ? '链接已过期' : '打包下载全部' }}</span>
            </button>
            <div v-if="downloading" class="progress-track">
              <div class="progress-fill" :style="{ width: progress + '%' }"></div>
            </div>
          </div>

          <!-- details card -->
          <div class="card info-card">
            <h3 class="card-title" style="margin-bottom:12px;">传输详情</h3>
            <div class="info-row">
              <div class="meta-item">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                <span class="info-label">文件总数</span>
              </div>
              <span class="info-val">{{ shareData.e.length }} 个</span>
            </div>
            <div class="info-row">
              <div class="meta-item">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
                <span class="info-label">打包大小</span>
              </div>
              <span class="info-val">{{ totalSizeText }}</span>
            </div>
            <div class="info-row">
              <div class="meta-item">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                <span class="info-label">链接有效期</span>
              </div>
              <span class="info-val">{{ expireText }}</span>
            </div>
            <div class="info-row last">
              <div class="meta-item">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/></svg>
                <span class="info-label">文件格式</span>
              </div>
              <span class="info-val">ZIP 压缩包</span>
            </div>
          </div>

        </div>
      </div>
    </template>

    <!-- 多选浮动操作栏（与管理端文件管理一致） -->
    <transition name="fade-up">
      <div v-if="selectedIndexes.size > 0" class="share-selection-bar">
        <div class="share-selection-count">已选 {{ selectedIndexes.size }} 项</div>
        <div class="share-selection-actions">
          <button class="share-action-btn share-action-primary" :disabled="downloading" @click="downloadSelected">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            下载选中
          </button>
          <button class="share-action-btn share-action-cancel" @click="clearSelection">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import JSZip from 'jszip'
import api from '../api'
import FolderNode from '../components/FolderNode.vue'

const shareData = ref(null)
const shareCode = ref('')
const error = ref('')
const downloading = ref(false)
const progress = ref(0)
const downloadDone = ref(false)
const showAllFiles = ref(false)
const viewMode = ref('folder') // 'folder' | 'list'

const now = ref(Date.now())
let timer = null

onMounted(async () => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
  try {
    const params = new URLSearchParams(window.location.search)
    const s = params.get('s')
    const d = params.get('d')
    if (s) {
      shareCode.value = s
      try {
        const { data } = await api.getShare(s)
        if (!data?.entries || !Array.isArray(data.entries) || data.entries.length === 0) {
          error.value = '分享数据无效'
          return
        }
        shareData.value = {
          n: data.filename || 'download.zip',
          e: data.entries.map(e => ({ u: e.u || e.url, f: e.f || e.filename, p: e.p || '', s: e.s ?? e.size })),
          exp: data.expireAt ? Math.floor(data.expireAt / 1000) : null
        }
      } catch (e) {
        if (e?.response?.status === 410) {
          error.value = '分享链接已过期'
        } else if (e?.response?.status === 404) {
          error.value = '分享链接不存在'
        } else {
          error.value = '加载分享数据失败'
        }
        return
      }
    } else if (d) {
      const json = decodeURIComponent(escape(atob(d)))
      const parsed = JSON.parse(json)
      if (!parsed.e || !Array.isArray(parsed.e) || parsed.e.length === 0) {
        error.value = '分享数据无效'
        return
      }
      shareData.value = parsed
    } else {
      error.value = '无效的分享链接'
      return
    }
  } catch (e) {
    error.value = '分享链接解析失败: ' + (e?.message || '')
  }
})

onUnmounted(() => { if (timer) clearInterval(timer) })

const expired = computed(() => {
  const exp = shareData.value?.exp
  if (!exp) return false
  return now.value / 1000 >= exp
})

const errorType = computed(() => {
  if (error.value.includes('过期')) return 'expired'
  if (error.value.includes('不存在') || error.value.includes('无效')) return 'notfound'
  return 'error'
})

const errorTitle = computed(() => {
  if (errorType.value === 'expired') return '链接已过期'
  if (errorType.value === 'notfound') return '链接不存在'
  return '无法加载分享内容'
})

const expireText = computed(() => {
  const exp = shareData.value?.exp
  if (!exp) return '永久有效'
  const remain = Math.max(0, exp - Math.floor(now.value / 1000))
  if (remain === 0) return '已过期'
  if (remain < 60) return `${remain} 秒后过期`
  if (remain < 3600) return `${Math.round(remain / 60)} 分钟后过期`
  if (remain < 86400) return `${Math.round(remain / 3600)} 小时后过期`
  return `${Math.round(remain / 86400)} 天后过期`
})

const visibleFiles = computed(() => {
  const files = showAllFiles.value ? shareData.value.e : shareData.value.e.slice(0, 10)
  // 加上原始索引 _idx（用于选择/单文件下载/计数）
  return files.map((f, i) => ({ ...f, _idx: shareData.value.e.indexOf(f) }))
})

// 文件多选
const selectedIndexes = ref(new Set())
const toggleSelect = (idx) => {
  const s = new Set(selectedIndexes.value)
  if (s.has(idx)) s.delete(idx); else s.add(idx)
  selectedIndexes.value = s
}
// 点击整行切换选中（checkbox 和下载按钮已 stop，不会重复触发）
const onRowClick = (e, idx) => {
  toggleSelect(idx)
}
const toggleSelectAll = (checked) => {
  if (checked) {
    selectedIndexes.value = new Set(shareData.value.e.map((_, i) => i))
  } else {
    clearSelection()
  }
}
const allSelected = computed(() =>
  shareData.value?.e?.length > 0 && selectedIndexes.value.size === shareData.value.e.length
)

const clearSelection = () => {
  selectedIndexes.value = new Set()
}

const buildDownloadEntries = (indexes) => {
  if (!shareData.value) return []
  return indexes
    .map((entryIndex) => {
      const entry = shareData.value.e[entryIndex]
      return entry ? { ...entry, _entryIndex: entryIndex } : null
    })
    .filter(Boolean)
}

// 记录下载计数
const recordDownload = async (entryIndexes) => {
  if (!shareCode.value) return
  const indexes = Array.isArray(entryIndexes) ? entryIndexes : [entryIndexes]
  const validIndexes = indexes.filter(i => Number.isInteger(i))
  try {
    await fetch(`/api/share/${shareCode.value}/download`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(validIndexes.length === 1 ? { entryIndex: validIndexes[0] } : { entryIndexes: validIndexes })
    })
  } catch { /* ignore */ }
}

// 单文件下载（直接 a 标签，不走 zip）
const downloadSingle = async (idx) => {
  const e = shareData.value.e[idx]
  if (!e || !e.u) return
  recordDownload(idx)
  const a = document.createElement('a')
  a.href = e.u
  a.target = '_blank'
  a.download = e.f
  document.body.appendChild(a)
  a.click()
  a.remove()
}

// 是否有任何文件带路径（用于决定是否展示「文件夹视图」选项）
const hasFolders = computed(() => shareData.value?.e.some(f => f.p))

// 文件夹树：{ folders: [{name, children: <tree>}], files: [entry] }
const folderTree = computed(() => {
  if (!shareData.value) return { folders: [], files: [] }
  const root = { folders: [], files: [] }
  for (const e of shareData.value.e) {
    const segs = (e.p || '').split('/').filter(Boolean)
    let node = root
    for (const seg of segs) {
      let next = node.folders.find(f => f.name === seg)
      if (!next) { next = { name: seg, folders: [], files: [] }; node.folders.push(next) }
      node = next
    }
    node.files.push(e)
  }
  return root
})

const totalBytes = computed(() => {
  if (!shareData.value) return 0
  return shareData.value.e.reduce((sum, f) => sum + (f.s || 0), 0)
})

const totalSizeText = computed(() => {
  const b = totalBytes.value
  if (b === 0) return '—'
  return formatSize(b)
})

function formatSize(bytes) {
  if (bytes == null || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

function fileTypeClass(name) {
  if (!name) return 'type-doc'
  const ext = name.split('.').pop().toLowerCase()
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return 'type-zip'
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'type-img'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'type-video'
  if (['pdf'].includes(ext)) return 'type-pdf'
  return 'type-doc'
}

// 打包下载选中的文件
const downloadSelected = async () => {
  if (!shareData.value || downloading.value || selectedIndexes.value.size === 0) return
  const indexes = [...selectedIndexes.value].sort((a, b) => a - b)
  const entries = buildDownloadEntries(indexes)
  if (!entries.length) return
  // 记录计数（每个选中文件）
  recordDownload(entries.map(e => e._entryIndex))
  clearSelection()
  await doZipDownload(entries)
}

const startDownload = async () => {
  if (!shareData.value || downloading.value) return
  const entries = buildDownloadEntries(shareData.value.e.map((_, i) => i))
  if (!entries.length) return
  // 记录每个文件下载计数
  recordDownload(entries.map(e => e._entryIndex))
  clearSelection()
  await doZipDownload(entries)
}

// 核心打包逻辑（entries → fetch → JSZip → 保存）
const doZipDownload = async (entries) => {
  if (!entries || !entries.length) return
  downloading.value = true
  progress.value = 0
  downloadDone.value = false

  const zip = new JSZip()
  const concurrency = 4
  let index = 0
  const totalBytes = entries.reduce((s, e) => s + (e.s || 0), 0)
  let downloadedBytes = 0
  const updateProgress = () => {
    if (totalBytes > 0) progress.value = Math.min(99, Math.floor((downloadedBytes / totalBytes) * 100))
  }

  const worker = async () => {
    while (true) {
      const current = index++
      if (current >= entries.length) break
      const e = entries[current]
      try {
        const resp = await fetch(e.u)
        if (!resp.ok) throw new Error(`下载失败: ${resp.status}`)
        const contentLength = Number(resp.headers.get('content-length') || e.s || 0)
        if (resp.body && contentLength > 0) {
          const reader = resp.body.getReader()
          const chunks = []
          while (true) {
            const { done, value } = await reader.read()
            if (done) break
            chunks.push(value)
            downloadedBytes += value.length
            updateProgress()
          }
          const buf = await new Blob(chunks).arrayBuffer()
          const zipPath = e.p ? `${e.p}/${e.f}` : (e.f || `file-${current + 1}`)
          zip.file(zipPath, buf)
        } else {
          const buf = await resp.arrayBuffer()
          downloadedBytes += contentLength || buf.byteLength
          updateProgress()
          const zipPath = e.p ? `${e.p}/${e.f}` : (e.f || `file-${current + 1}`)
          zip.file(zipPath, buf)
        }
      } catch (err) {
        console.warn('download failed for entry', e, err)
        downloadedBytes += (e.s || 0)
        updateProgress()
      }
    }
  }

  const workers = []
  const workerCount = Math.min(concurrency, entries.length)
  for (let i = 0; i < workerCount; i++) workers.push(worker())
  await Promise.all(workers)

  try {
    const blob = await zip.generateAsync({ type: 'blob' })
    const filename = shareData.value.n || 'archive.zip'
    if ('showSaveFilePicker' in window) {
      try {
        const handle = await window.showSaveFilePicker({
          suggestedName: filename,
          types: [{ description: 'ZIP archive', accept: { 'application/zip': ['.zip'] } }]
        })
        const writable = await handle.createWritable()
        await writable.write(blob)
        await writable.close()
        downloadDone.value = true
        return
      } catch (e) {
        if (e.name === 'AbortError') { downloading.value = false; return }
      }
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
    downloadDone.value = true
  } catch (e) {
    error.value = '打包保存失败: ' + (e?.message || '')
  } finally {
    downloading.value = false
  }
}
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Manrope:wght@600;700&display=swap');

/* ── tokens ── */
.share-page {
  --primary: #0F4C81;
  --primary-light: #d2e4ff;
  --surface: #ffffff;
  --surface-dim: #f8f9fa;
  --surface-container: #eaebec;
  --border: #e5e5ea;
  --text-primary: #1d1d1f;
  --text-secondary: #42474f;
  --text-muted: #86868b;
}
/* 暗色覆盖 */
.dark .share-page {
  --primary: #5594c8;
  --primary-light: #1a3a5c;
  --surface: #1d1e1f;
  --surface-dim: #141414;
  --surface-container: #262728;
  --border: #3a3a3c;
  --text-primary: #e5eaf3;
  --text-secondary: #a3a6ad;
  --text-muted: #86868b;
}

.share-page {
  min-height: 100vh;
  background: var(--surface-dim);
  font-family: 'Inter', -apple-system, "system-ui", "Segoe UI", Helvetica, Arial, sans-serif;
  color: var(--text-primary);
}

.share-page > * {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px;
}
@media (max-width: 768px) {
  .share-page > * { padding: 0 12px; }
}
.share-page > .bento-grid {
  padding-bottom: 48px;
}

/* ── error ── */
.error-fullscreen {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}
.error-card {
  max-width: 420px;
  width: 100%;
  background: var(--surface);
  border-radius: 16px;
  padding: 48px 36px;
  text-align: center;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
}
.error-icon-wrap {
  width: 52px; height: 52px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}
.error-card.expired .error-icon-wrap { background: #fffbeb; }
.error-card.notfound .error-icon-wrap { background: #f3f4f6; }
.error-card.error .error-icon-wrap { background: #fef2f2; }
.error-title { margin: 0 0 8px; font-size: 18px; font-weight: 600; color: #1d1d1f; }
.error-text { color: var(--text-muted); font-size: 15px; margin: 0; }

/* ── hero ── */
.hero {
  padding: 32px 0 20px;
}
.hero-badge {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #006492;
  background: #cae6ff;
  padding: 4px 12px;
  border-radius: 999px;
  margin-bottom: 16px;
}
.hero-title {
  font-family: 'Manrope', -apple-system, sans-serif;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.15;
  color: var(--primary);
  margin: 0 0 20px;
  max-width: 680px;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  color: var(--text-secondary);
  font-size: 15px;
}
.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.meta-item svg { color: var(--primary); opacity: 0.6; }

/* ── bento grid ── */
.bento-grid {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 24px;
  align-items: start;
}
@media (max-width: 840px) {
  .bento-grid { grid-template-columns: 1fr; }
  .hero-title { font-size: 28px; }
  .hero { padding: 40px 0 28px; }
}

/* ── card base ── */
.card {
  background: var(--surface);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.04);
  overflow: hidden;
}
.card-title {
  font-family: 'Manrope', -apple-system, sans-serif;
  font-size: 16px;
  font-weight: 600;
  color: var(--primary);
  margin: 0;
}

/* ── file list ── */
.file-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-container);
}
.file-count { font-size: 13px; color: var(--text-muted); }

.view-toggle {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}
.toggle-btn {
  border: none;
  background: transparent;
  padding: 5px 12px;
  font-size: 12px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s;
}
.toggle-btn.active {
  background: var(--primary, #4f46e5);
  color: #fff;
}
.toggle-btn:not(.active):hover { background: var(--surface-hover, #f3f4f6); }

.folder-tree {
  padding: 4px 0;
}
.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  font-size: 14px;
  color: #374151;
}
.tree-folder .tree-name { font-weight: 600; }
.tree-file { color: #6b7280; }
.tree-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-size { color: #9ca3af; font-size: 12px; flex-shrink: 0; }

.file-table-wrap { overflow-x: auto; }
.file-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 14px;
}
.file-table thead th {
  padding: 12px 24px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--border);
}
.file-table tbody tr {
  transition: background 0.15s;
}
.file-table tbody tr:hover {
  background: var(--surface-dim);
}
.file-table td {
  padding: 12px 24px;
  border-bottom: 1px solid #f5f5f7;
}
.text-right { text-align: right; }

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}
.file-type-icon {
  width: 36px; height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.type-zip { background: #fef3c7; color: #d97706; }
.type-img { background: #dbeafe; color: #3b82f6; }
.type-video { background: #ede9fe; color: #7c3aed; }
.type-pdf { background: #fee2e2; color: #ef4444; }
.type-doc { background: #f3f4f6; color: #6b7280; }

.file-name-text {
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-size-cell {
  color: var(--text-muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.file-card-footer {
  padding: 12px;
  text-align: center;
  border-top: 1px solid #f5f5f7;
}
.toggle-more-btn {
  font-size: 13px;
  font-weight: 600;
  color: var(--primary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 8px 16px;
  border-radius: 8px;
  transition: background 0.15s;
}
.toggle-more-btn:hover { background: var(--surface-container); }

/* ── sidebar ── */
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* download card */
.download-card { padding: 20px; }
.dl-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 14px 24px;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  font-family: 'Manrope', -apple-system, sans-serif;
  border: none;
  cursor: pointer;
  transition: transform 0.1s, opacity 0.2s;
  background: var(--primary);
  color: #fff;
  box-shadow: 0 8px 30px rgba(15,76,129,0.2);
}
.dl-btn:hover:not(:disabled) { transform: scale(1.02); }
.dl-btn:active:not(:disabled) { transform: scale(0.98); }
.dl-btn:disabled { opacity: 0.65; cursor: not-allowed; transform: none; }
.dl-single-btn {
  background: none; border: none; cursor: pointer; font-size: 14px;
  color: var(--text-secondary); padding: 4px 8px; border-radius: 4px;
  display: inline-flex; align-items: center;
}
.dl-single-btn:hover { background: var(--surface-container); color: var(--primary); }
.file-table input[type="checkbox"] { cursor: pointer; }
.file-table tbody tr { cursor: pointer; transition: background 0.15s; }
.file-table tbody tr:hover { background: var(--surface-container); }
.file-table tbody tr.row-selected { background: var(--primary-light); }

/* 多选浮动操作栏（与管理端一致） */
.share-selection-bar {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  z-index: 2000; display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 12px 24px; background: var(--surface); border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18); border: 1px solid var(--border);
}
.share-selection-count { font-size: 13px; color: var(--text-secondary); font-weight: 500; }
.share-selection-actions { display: flex; align-items: center; gap: 12px; }
.share-action-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 8px; font-size: 14px; font-weight: 500;
  cursor: pointer; border: none; transition: all 0.25s ease;
}
.share-action-primary { background: var(--primary); color: #fff; }
.share-action-primary:hover { opacity: 0.9; }
.share-action-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.share-action-cancel { background: transparent; color: var(--text-muted); }
.share-action-cancel:hover { background: var(--surface-container); }
.fade-up-enter-active, .fade-up-leave-active { transition: all 0.25s ease; }
.fade-up-enter-from, .fade-up-leave-to { opacity: 0; transform: translateX(-50%) translateY(20px); }

.progress-track {
  height: 3px;
  background: var(--surface-container);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 14px;
}
.progress-fill {
  height: 100%;
  background: var(--primary);
  border-radius: 2px;
  transition: width 0.3s ease;
}

/* info card */
.info-card { padding: 20px; }
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--surface-container);
  font-size: 14px;
}
.info-row.last { border-bottom: none; }
.info-label { color: var(--text-muted); }
.info-val { font-weight: 600; color: var(--text-primary); }


/* spin animation */
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
