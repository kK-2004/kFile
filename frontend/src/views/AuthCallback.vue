<template>
  <div class="callback-wrap">
    <el-result v-if="error" icon="error" title="登录未完成" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="retry">重新登录</el-button>
        <el-button @click="router.replace('/')">返回首页</el-button>
      </template>
    </el-result>
    <p v-else class="callback-hint">正在完成登录…</p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { handleCallback, login } from '../auth/oidc'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const store = useAuthStore()
const error = ref('')

const retry = () => login('/admin/projects')

// 网关拒绝原因（如登录策略禁止）的友好文案
const describe = (result) => {
  if (result.error === 'access_denied' && result.description === 'login_policy_denied') {
    return '当前账号被禁止登录 kFile，请联系管理员'
  }
  return result.description || result.error
}

onMounted(async () => {
  try {
    const result = await handleCallback(window.location.search)
    if (result.error) {
      error.value = describe(result)
      return
    }
    store.loaded = false
    await store.loadMe()
    router.replace(result.returnTo)
  } catch (e) {
    error.value = e?.message || '登录失败'
  }
})
</script>

<style scoped>
.callback-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 64px);
}

.callback-hint {
  color: #64748b;
}
</style>
