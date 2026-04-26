<template>
  <div class="share-page">
    <!-- error state -->
    <div v-if="error" class="error-card">
      <div class="error-icon-wrap">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
      </div>
      <h2 style="margin:0 0 8px;font-size:18px;font-weight:600;color:#1d1d1f;">无法加载分享内容</h2>
      <p class="error-text">{{ error }}</p>
    </div>

    <template v-else-if="shareData">
      <!-- hero -->
      <section class="hero">
        <span class="hero-badge">项目名称</span>
        <h1 class="hero-title">{{ shareData.n }}</h1>
        <div class="hero-meta">
          <div class="meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            <span>{{ shareData.e.length }} 个文件</span>
          </div>
          <div class="meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
            <span>{{ totalSizeText }}</span>
          </div>
          <div class="meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
            <span>{{ expireText }}</span>
          </div>
        </div>
      </section>

      <!-- main content -->
      <div class="bento-grid">
        <!-- file list card -->
        <div class="card file-card">
          <div class="file-card-header">
            <h2 class="card-title">文件列表</h2>
            <span class="file-count">共 {{ shareData.e.length }} 项</span>
          </div>
          <div class="file-table-wrap">
            <table class="file-table">
              <thead>
                <tr>
                  <th>文件名</th>
                  <th class="text-right">大小</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(f, i) in visibleFiles" :key="i">
                  <td>
                    <div class="file-name-cell">
                      <div class="file-type-icon" :class="fileTypeClass(f.f)">
                        <svg v-if="fileTypeClass(f.f)==='type-zip'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8v13H3V8"/><path d="M1 3h22v5H1z"/><path d="M10 12h4"/></svg>
                        <svg v-else-if="fileTypeClass(f.f)==='type-img'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
                        <svg v-else-if="fileTypeClass(f.f)==='type-video'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>
                        <svg v-else-if="fileTypeClass(f.f)==='type-pdf'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                        <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                      </div>
                      <span class="file-name-text">{{ f.f }}</span>
                    </div>
                  </td>
                  <td class="text-right file-size-cell">{{ f.s != null ? formatSize(f.s) : '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="shareData.e.length > 10" class="file-card-footer">
            <button class="toggle-more-btn" @click="showAllFiles = !showAllFiles">
              {{ showAllFiles ? '收起' : `查看全部 ${shareData.e.length} 个文件` }}
            </button>
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
              <span class="info-label">文件总数</span>
              <span class="info-val">{{ shareData.e.length }} 个</span>
            </div>
            <div class="info-row">
              <span class="info-label">打包大小</span>
              <span class="info-val">{{ totalSizeText }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">链接有效期</span>
              <span class="info-val">{{ expireText }}</span>
            </div>
            <div class="info-row last">
              <span class="info-label">文件格式</span>
              <span class="info-val">ZIP 压缩包</span>
            </div>
          </div>

        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import JSZip from 'jszip'

const shareData = ref(null)
const error = ref('')
const downloading = ref(false)
const progress = ref(0)
const downloadDone = ref(false)
const showAllFiles = ref(false)

const now = ref(Date.now())
let timer = null

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
  try {
    const params = new URLSearchParams(window.location.search)
    const d = params.get('d')
    if (!d) { error.value = '无效的分享链接'; return }
    // encodeURIComponent 已在编码端处理了 +/= 等，URLSearchParams.get 会自动解码
    // 直接 atob 即可
    const json = decodeURIComponent(escape(atob(d)))
    const parsed = JSON.parse(json)
    if (!parsed.e || !Array.isArray(parsed.e) || parsed.e.length === 0) {
      error.value = '分享数据无效'
      return
    }
    shareData.value = parsed
    console.log('[ShareDownload] parsed shareData:', JSON.stringify(parsed).slice(0, 500))
  } catch (e) {
    console.error('[ShareDownload] parse error:', e)
    error.value = '分享链接解析失败: ' + (e?.message || '')
  }
})

onUnmounted(() => { if (timer) clearInterval(timer) })

const expired = computed(() => {
  const exp = shareData.value?.exp
  if (!exp) return false
  return now.value / 1000 >= exp
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
  return showAllFiles.value ? shareData.value.e : shareData.value.e.slice(0, 10)
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

const startDownload = async () => {
  if (!shareData.value || downloading.value) return
  downloading.value = true
  progress.value = 0
  downloadDone.value = false

  const entries = shareData.value.e
  const zip = new JSZip()
  const concurrency = 4
  let index = 0
  let processed = 0

  const worker = async () => {
    while (true) {
      const current = index++
      if (current >= entries.length) break
      const e = entries[current]
      try {
        const resp = await fetch(e.u)
        if (!resp.ok) throw new Error(`下载失败: ${resp.status}`)
        const buf = await resp.arrayBuffer()
        zip.file(e.f || `file-${current + 1}`, buf)
      } catch (err) {
        console.warn('download failed for entry', e, err)
      } finally {
        processed++
        progress.value = Math.floor((processed / entries.length) * 100)
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
.share-page > .bento-grid {
  padding-bottom: 48px;
}

/* ── error ── */
.error-card {
  max-width: 420px;
  margin: 120px auto 0;
  background: var(--surface);
  border-radius: 16px;
  padding: 48px 36px;
  text-align: center;
  box-shadow: 0 4px 20px rgba(0,0,0,0.04);
}
.error-icon-wrap {
  width: 52px; height: 52px;
  border-radius: 50%;
  background: #fef2f2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}
.error-text { color: var(--text-muted); font-size: 15px; margin: 0; }

/* ── hero ── */
.hero {
  padding: 64px 0 40px;
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
