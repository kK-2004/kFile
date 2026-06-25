<template>
  <div class="folder-node">
    <div class="tree-row tree-folder" :style="{ paddingLeft: depth * 16 + 8 + 'px' }" @click="expanded = !expanded">
      <svg class="caret" :class="{ collapsed: !expanded }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#e6a23c" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      <span class="tree-name">{{ node.name }}</span>
    </div>
    <template v-if="expanded">
      <!-- 子文件夹 -->
      <FolderNode
        v-for="(child, i) in node.folders"
        :key="'c'+i"
        :node="child"
        :depth="depth + 1"
      />
      <!-- 文件 -->
      <div
        v-for="(f, idx) in node.files"
        :key="'f'+idx"
        class="tree-row tree-file"
        :class="{ 'tree-file-deleted': f.deleted }"
        :style="{ paddingLeft: (depth + 1) * 16 + 8 + 'px' }"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <span class="tree-name">{{ f.f }}</span>
        <span v-if="f.deleted" class="tree-deleted-tag">已删除</span>
        <span class="tree-size">{{ f.deleted ? '—' : (f.s != null ? formatSize(f.s) : '—') }}</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue'
defineOptions({ name: 'FolderNode' })
defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 }
})
const expanded = ref(true) // 默认展开
const formatSize = (b) => {
  if (b == null) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, n = Number(b)
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}
</script>

<style scoped>
.folder-node { }
.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  font-size: 14px;
  color: #374151;
}
.tree-folder { cursor: pointer; user-select: none; }
.tree-folder:hover { background: rgba(0, 0, 0, 0.03); }
.tree-folder .tree-name { font-weight: 600; }
.tree-file { color: #6b7280; }
.tree-file-deleted { opacity: 0.55; }
.tree-deleted-tag {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  color: #b91c1c;
  background: #fee2e2;
  flex-shrink: 0;
}
.caret {
  color: #9ca3af;
  transition: transform 0.15s ease;
  flex-shrink: 0;
}
.caret.collapsed { transform: rotate(0deg); }
.caret:not(.collapsed) { transform: rotate(90deg); }
.tree-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-size { color: #9ca3af; font-size: 12px; flex-shrink: 0; }
</style>
