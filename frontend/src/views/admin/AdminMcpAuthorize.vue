<template>
  <div class="authorize-wrap">
    <el-card class="authorize-card">
      <template #header>
        <div class="authorize-header">
          <h2 class="authorize-title">MCP 授权</h2>
          <p class="authorize-subtitle">为外部 Agent 签发访问令牌</p>
        </div>
      </template>

      <!-- 缺少 redirect_uri -->
      <el-alert
        v-if="!redirectUri"
        type="warning"
        :closable="false"
        title="缺少回调地址"
        description="URL 未携带 redirect_uri 参数，无法完成授权。请从 Agent 提供的链接进入。"
        show-icon
      />

      <template v-else>
        <div class="info-block">
          <div class="info-row">
            <span class="info-label">当前登录用户</span>
            <span class="info-value">{{ auth.user?.username || '-' }}（{{ auth.user?.role || '-' }}）</span>
          </div>
          <div class="info-row">
            <span class="info-label">回调地址</span>
            <span class="info-value break">{{ redirectUri }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">令牌有效期</span>
            <span class="info-value">6 个月</span>
          </div>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin: 12px 0;"
        >
          令牌等同你的管理员身份，请仅在可信 Agent 上使用。
        </el-alert>

        <el-alert
          v-if="errorMsg"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
          :title="errorMsg"
        />

        <div class="actions">
          <el-button @click="goBack">取消</el-button>
          <el-button type="primary" :loading="authorizing" @click="doAuthorize">授权并跳转</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const redirectUri = ref('')
const authorizing = ref(false)
const errorMsg = ref('')

onMounted(async () => {
  if (!auth.loaded) await auth.loadMe()
  // 支持 query 与 hash 两种形式
  redirectUri.value = route.query.redirect_uri || ''
})

const doAuthorize = async () => {
  if (!redirectUri.value) return
  errorMsg.value = ''
  authorizing.value = true
  try {
    const { data } = await api.mcpAuthorize(redirectUri.value)
    // 拼 {redirect_uri}?token=<明文> 并跳转（外链跳出站点）
    const sep = data.redirectUri.includes('?') ? '&' : '?'
    const target = data.redirectUri + sep + 'token=' + encodeURIComponent(data.accessToken)
    window.location.href = target
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '授权失败'
    errorMsg.value = msg
    ElMessage.error(msg)
  } finally {
    authorizing.value = false
  }
}

const goBack = () => router.push('/admin/users')
</script>

<style scoped>
.authorize-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 112px);
  padding: 24px;
  box-sizing: border-box;
}
.authorize-card {
  width: 540px;
  max-width: 100%;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.08);
}
.authorize-header { text-align: center; }
.authorize-title { margin: 0; font-size: 22px; font-weight: 600; color: var(--kf-text-primary, #333); }
.authorize-subtitle { margin: 4px 0 0; font-size: 13px; color: var(--kf-text-sub, #888); }
.info-block { display: flex; flex-direction: column; gap: 12px; margin: 8px 0; }
.info-row { display: flex; gap: 12px; font-size: 14px; }
.info-label { width: 100px; flex-shrink: 0; color: var(--kf-text-sub, #888); }
.info-value { color: var(--kf-text-primary, #333); word-break: break-all; }
.info-value.break { word-break: break-all; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 16px; }
</style>
