<template>
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
      <el-form-item>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '../../api'
import { ElMessage } from 'element-plus'

const form = ref({ monthlyLimitUser: null, userTotalQuotaBytes: null, allowedFileTypes: [] })
const quotaGB = ref(null)
const types = ref([])
const typeSelectable = computed(()=> Array.from(new Set([...(types.value||[])])))
const saving = ref(false)

const load = async () => {
  try {
    const { data } = await api.adminGetConfig()
    form.value.monthlyLimitUser = data.monthlyLimitUser ?? null
    form.value.userTotalQuotaBytes = data.userTotalQuotaBytes ?? null
    types.value = Array.isArray(data.allowedFileTypes) ? data.allowedFileTypes : []
    quotaGB.value = (data.userTotalQuotaBytes === null || data.userTotalQuotaBytes === undefined)
      ? null
      : Math.round(Number(data.userTotalQuotaBytes)/1024/1024/1024)
  } catch (e) {}
}
onMounted(load)

const save = async () => {
  try {
    saving.value = true
    const payload = {
      monthlyLimitUser: form.value.monthlyLimitUser ?? null,
      userTotalQuotaBytes: (quotaGB.value && quotaGB.value > 0) ? Math.round(Number(quotaGB.value) * 1024 * 1024 * 1024) : 0,
      allowedFileTypes: (types.value || []).map(s => String(s||'').replace(/^\./,''))
    }
    if (payload.userTotalQuotaBytes === 0) payload.userTotalQuotaBytes = null
    await api.adminUpdateConfig(payload)
    ElMessage.success('已保存设置')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally { saving.value = false }
}

</script>

<style scoped>
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
</style>
