<template>
  <el-card class="shares-card">
    <template #header>
      <div class="card-header">
        <div class="header-left">
          <h2 class="page-title">分享链接管理</h2>
        </div>
        <div class="header-right">
          <el-input v-model="keyword" placeholder="按项目名搜索" size="default" style="width:200px" clearable
            @keyup.enter="onSearch" @clear="onSearch">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button @click="onSearch">搜索</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table :data="nodes" v-loading="loading" height="100%" style="flex:1;min-height:0;width:100%;">
      <el-table-column label="所属" min-width="120" align="center" show-overflow-tooltip>
        <template #default="{row}">
          <span>{{ row.projectName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" min-width="90" align="center">
        <template #default="{row}">
          <el-tag v-if="row.shareType === 'FOLDER_SYNC'" size="small" type="success">文件夹</el-tag>
          <el-tag v-else-if="row.shareType === 'FILE_SET'" size="small">文件集</el-tag>
          <el-tag v-else-if="row.shareType === 'SUBMISSION_SYNC'" size="small" type="warning">提交</el-tag>
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
      <el-table-column label="操作" min-width="150" align="center" fixed="right">
        <template #default="{row}">
          <div class="opt-group">
            <el-button size="small" @click="copyLink(row)">复制</el-button>
            <el-button size="small" type="danger" @click="confirmDelete(row)">删除</el-button>
          </div>
          </template>
      </el-table-column>
      <template #empty><span>暂无分享链接</span></template>
    </el-table>

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
import { ref, onMounted } from 'vue'
import api from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

const nodes = ref([])
const loading = ref(false)
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(15)
const total = ref(0)

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.adminListShares(currentPage.value - 1, pageSize.value, keyword.value)
    nodes.value = data?.nodes || []
    total.value = data?.total || 0
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally { loading.value = false }
}

const onSearch = () => { currentPage.value = 1; load() }
const onPage = (p) => { load() }
const onSize = (s) => { currentPage.value = 1; load() }

const shareUrl = (row) => `${window.location.origin}/share?s=${row.code}`
const copyLink = async (row) => {
  try {
    await navigator.clipboard.writeText(shareUrl(row))
    ElMessage.success('已复制')
  } catch { ElMessage.warning('复制失败，请手动复制') }
}

const confirmDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确认删除该分享链接？删除后无法恢复。', '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await api.adminDeleteShare(row.id)
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

onMounted(load)
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
</style>
