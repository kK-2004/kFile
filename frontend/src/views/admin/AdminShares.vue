<template>
  <el-card class="shares-card">
    <template #header>
      <div class="card-header">
        <div class="header-left">
          <h2 class="page-title">分享链接管理</h2>
        </div>
        <div class="header-right">
          <el-input v-model="keyword" placeholder="按项目名或文件名搜索" size="default" style="width:220px" clearable
            @keyup.enter="onSearch" @clear="onSearch">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="shareType" placeholder="类型" style="width:120px" @change="onTypeChange">
            <el-option v-for="option in shareTypeOptions" :key="option.value" :value="option.value" :label="option.label" />
          </el-select>
          <el-button @click="onSearch">搜索</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table :data="nodes" v-loading="loading" height="100%" style="flex:1;min-height:0;width:100%;">
      <el-table-column label="所属" min-width="120" align="center">
        <template #default="{row}">
          <KPopover
            v-if="isLocationOverflowing(row)"
            class="share-location-popover-root"
            placement="top"
            trigger="hover"
            :show-arrow="true"
            @update:open="onLocationPopoverOpen(row, $event)"
          >
            <KPopoverTrigger class="share-location-trigger">
              <span
                :ref="(el) => setLocationCellRef(row, el)"
                class="share-location-ellipsis"
              >{{ shareLocationText(row) }}</span>
            </KPopoverTrigger>
            <KPopoverContent>
              <div
                class="share-location-popover-content"
                :data-share-location-key="locationKey(row)"
              >{{ shareLocationText(row) }}</div>
            </KPopoverContent>
          </KPopover>
          <span
            v-else
            :ref="(el) => setLocationCellRef(row, el)"
            class="share-location-ellipsis share-location-plain"
          >{{ shareLocationText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" min-width="90" align="center">
        <template #default="{row}">
          <el-tag v-if="row.shareType === 'FOLDER_SYNC'" size="small" type="success">文件夹</el-tag>
          <el-tag v-else-if="row.shareType === 'FILE_SET'" size="small">文件集</el-tag>
          <el-tag v-else-if="row.shareType === 'SUBMISSION_SYNC'" size="small" type="warning">提交</el-tag>
          <el-tag v-else-if="row.shareType === 'CDN'" size="small" type="primary">CDN</el-tag>
          <el-tag v-else size="small" type="info">历史</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="文件名" min-width="160" align="center" prop="filename" show-overflow-tooltip />
      <el-table-column label="文件数" min-width="80" align="center" prop="fileCount" />
      <el-table-column label="下载量" min-width="90" align="center">
        <template #default="{row}">
          <el-tooltip
            v-if="row.fileDownloads && row.fileDownloads.length"
            placement="top"
            effect="light"
            popper-class="share-dl-tooltip"
          >
            <template #content>
              <div class="dl-tip">
                <div class="dl-tip-row" v-for="(f, i) in row.fileDownloads" :key="i">
                  <span class="dl-tip-name" :title="f.name">{{ f.name }}</span>
                  <span class="dl-tip-count">{{ f.count }}</span>
                </div>
              </div>
            </template>
            <span class="dl-total">{{ row.downloadCount }}</span>
          </el-tooltip>
          <span v-else class="dl-total">{{ row.downloadCount || 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="80" align="center">
        <template #default="{row}">
          <el-tag v-if="row.permanent" size="small" type="warning">永久</el-tag>
          <el-tag v-else-if="row.expired" size="small" type="info">已过期</el-tag>
          <el-tag v-else size="small" type="success">有效</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160" align="center">
        <template #default="{row}">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="过期时间" min-width="160" align="center">
        <template #default="{row}">{{ row.permanent ? '永久有效' : formatTime(row.expireAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="200" align="center" fixed="right">
        <template #default="{row}">
          <div class="opt-group">
            <el-button size="small" @click="copyLink(row)">复制</el-button>
            <el-button v-if="row.shareType === 'CDN'" size="small" @click="openRenew(row)">续期</el-button>
            <el-button size="small" type="danger" @click="confirmDelete(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty><span>暂无分享链接</span></template>
    </el-table>

    <el-dialog v-model="renewVisible" title="续期 CDN 链接" width="420px">
      <el-form label-width="80px">
        <el-form-item label="有效期">
          <el-select v-model="renewExpire" style="width:100%">
            <el-option
              v-for="option in shareExpiryOptions"
              :key="option.value"
              :value="option.value"
              :label="option.label"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="renew-hint">有效期从确认续期的当前时间重新计算，永久链接不会过期。</div>
      <template #footer>
        <el-button @click="renewVisible = false">取消</el-button>
        <el-button type="primary" :loading="renewLoading" @click="submitRenew">确认续期</el-button>
      </template>
    </el-dialog>

    <div class="shares-pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 15, 20, 30, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="onPage"
        @size-change="onSize"
      />
    </div>
  </el-card>
</template>

<script setup>
import { ref, onBeforeUnmount, onMounted, nextTick } from 'vue'
import api from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { KPopover, KPopoverTrigger, KPopoverContent } from '@kk-2004/ui-components'
import {
  buildShareLinkUrl,
  isTextOverflowing,
  shareExpiryOptions,
  shareLocationText,
  shareTypeOptions
} from '../../utils/shareLinks'

const nodes = ref([])
const loading = ref(false)
const keyword = ref('')
const shareType = ref('ALL')
const currentPage = ref(1)
const pageSize = ref(15)
const total = ref(0)
const locationCellRefs = new Map()
const locationOverflow = ref(new Map())
let locationResizeObserver = null
const renewVisible = ref(false)
const renewRow = ref(null)
const renewExpire = ref(0)
const renewLoading = ref(false)

const locationKey = (row) => `${row?.shareType || 'HISTORY'}:${row?.id ?? row?.code ?? ''}`

const setLocationCellRef = (row, element) => {
  const key = locationKey(row)
  const previous = locationCellRefs.get(key)
  if (previous && previous !== element) locationResizeObserver?.unobserve(previous)
  if (element) {
    locationCellRefs.set(key, element)
    locationResizeObserver?.observe(element)
  } else {
    locationCellRefs.delete(key)
  }
}

const measureLocationOverflow = async () => {
  await nextTick()
  const next = new Map()
  for (const [key, element] of locationCellRefs) {
    next.set(key, isTextOverflowing(element))
  }
  const previous = locationOverflow.value
  const changed = next.size !== previous.size
    || [...next].some(([key, value]) => previous.get(key) !== value)
  if (changed) locationOverflow.value = next
}

const isLocationOverflowing = (row) => locationOverflow.value.get(locationKey(row)) === true

const clampPopoverToViewport = (popover) => {
  if (!popover) return
  const rect = popover.getBoundingClientRect()
  const margin = 12
  let adjustedLeft = rect.left
  if (rect.left < margin) adjustedLeft += margin - rect.left
  if (rect.right > window.innerWidth - margin) {
    adjustedLeft -= rect.right - (window.innerWidth - margin)
  }
  if (Math.abs(adjustedLeft - rect.left) < 0.5) return

  const currentLeft = Number.parseFloat(popover.style.left)
  if (Number.isFinite(currentLeft)) popover.style.left = `${currentLeft + adjustedLeft - rect.left}px`
}

const onLocationPopoverOpen = async (row, open) => {
  if (!open) return
  await nextTick()
  await nextTick()
  const key = locationKey(row)
  const content = [...document.querySelectorAll('.share-location-popover-content')]
    .find(element => element.dataset.shareLocationKey === key)
  clampPopoverToViewport(content?.closest('.k-popover'))
}

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.adminListShares(currentPage.value - 1, pageSize.value, keyword.value, shareType.value)
    nodes.value = data?.nodes || []
    total.value = data?.total || 0
    await measureLocationOverflow()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally { loading.value = false }
}

const onSearch = () => { currentPage.value = 1; load() }
const onTypeChange = () => { currentPage.value = 1; load() }
const onPage = (p) => { load() }
const onSize = (s) => { currentPage.value = 1; load() }

const shareUrl = (row) => buildShareLinkUrl(row)
const copyLink = async (row) => {
  try {
    await navigator.clipboard.writeText(shareUrl(row))
    ElMessage.success('已复制')
  } catch { ElMessage.warning('复制失败，请手动复制') }
}

const openRenew = (row) => {
  renewRow.value = row
  renewExpire.value = 0
  renewVisible.value = true
}

const submitRenew = async () => {
  if (!renewRow.value) return
  renewLoading.value = true
  try {
    await api.adminRenewCdnLink(renewRow.value.id, renewExpire.value)
    ElMessage.success('CDN 链接已续期')
    renewVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '续期失败')
  } finally {
    renewLoading.value = false
  }
}

const confirmDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确认删除该分享链接？删除后无法恢复。', '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await api.adminDeleteShare(row.id, row.shareType)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

const formatTime = (t) => {
  if (!t) return '-'
  try { return new Date(t).toLocaleString() } catch { return String(t) }
}

onMounted(() => {
  locationResizeObserver = typeof ResizeObserver === 'undefined'
    ? null
    : new ResizeObserver(() => measureLocationOverflow())
  for (const element of locationCellRefs.values()) locationResizeObserver?.observe(element)
  window.addEventListener('resize', measureLocationOverflow)
  load()
})

onBeforeUnmount(() => {
  locationResizeObserver?.disconnect()
  window.removeEventListener('resize', measureLocationOverflow)
})
</script>

<style scoped>
.shares-card {
  height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  border-radius: 0;
  border-left: none; border-right: none; border-bottom: none;
}
.shares-card :deep(.el-card__body) {
  flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: visible;
}
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; }
.header-right { display: flex; align-items: center; gap: 8px; }
.page-title { margin: 0; font-size: 18px; font-weight: 600; }
.shares-pagination { margin-top: 12px; display: flex; justify-content: flex-end; flex-shrink: 0; }
.share-location-popover-root {
  display: block;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
.share-location-trigger {
  display: block;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}
.share-location-ellipsis {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--kf-text);
}
.share-location-popover-content {
  max-width: 320px;
  color: var(--kf-text);
  line-height: 1.5;
  overflow-wrap: anywhere;
  white-space: normal;
}
.share-location-plain {
  width: 100%;
}
.dl-total { cursor: default; font-variant-numeric: tabular-nums; }
.share-dl-tooltip { max-width: 360px; }
.dl-tip { display: flex; flex-direction: column; gap: 2px; }
.dl-tip-row { display: flex; align-items: center; gap: 16px; min-width: 180px; }
.dl-tip-name {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--kf-text);
}
.dl-tip-count {
  font-size: 12px; font-weight: 600; color: var(--kf-primary);
  font-variant-numeric: tabular-nums;
}
.opt-group {
  display: flex;
  gap: 6px;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: center;
}
/* 操作列按钮收紧 */
.opt-group .el-button {
  margin-left: 0;
  padding: 0 10px;
  height: 28px;
}
.renew-hint {
  color: var(--kf-text-secondary, #909399);
  font-size: 12px;
  line-height: 1.5;
  margin: -4px 0 0 80px;
}
</style>
