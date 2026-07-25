<template>
  <div class="authorize-wrap">
    <el-card class="authorize-card">
      <template #header>
        <div class="authorize-header">
          <h2 class="authorize-title">MCP 授权确认</h2>
          <p class="authorize-subtitle">授权 Agent 通过标准 OAuth 连接到 k-File</p>
        </div>
      </template>

      <!-- 加载/跳转中提示 -->
      <div v-if="!consent && !errorMsg" class="authorize-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在准备授权页…</span>
      </div>

      <!-- 参数校验失败 -->
      <el-alert
        v-if="errorMsg"
        type="error"
        :closable="false"
        show-icon
        :title="errorMsg"
        description="授权请求参数无效，请从 Agent 重新发起。"
      />

      <!-- consent 页面 -->
      <template v-else-if="consent && !result">
        <div class="info-block">
          <div class="info-row">
            <span class="info-label">当前用户</span>
            <span class="info-value">{{ consent.username }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">应用</span>
            <span class="info-value">{{ consent.clientName || consent.clientId }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">回调地址</span>
            <span class="info-value break">{{ consent.redirectUri }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">权限</span>
            <span class="info-value">{{ consent.scope }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">资源</span>
            <span class="info-value break">{{ consent.resource }}</span>
          </div>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin: 12px 0;"
          title="短期访问令牌"
          description="批准后将签发短期 access token（默认 15 分钟）与可刷新的 refresh token，不会在浏览器地址栏显示任何令牌。"
        />

        <div class="actions">
          <el-button @click="decide('deny')" :disabled="busy">拒绝</el-button>
          <el-button type="primary" :loading="busy" @click="decide('approve')">批准</el-button>
        </div>
      </template>

      <!-- 授权结果页面 -->
      <template v-else-if="result">
        <div class="result-block">
          <el-icon class="result-icon" :class="result === 'approved' ? 'success' : 'denied'">
            <CircleCheck v-if="result === 'approved'" />
            <CircleClose v-else />
          </el-icon>
          <h3 class="result-title">{{ result === 'approved' ? '授权成功' : '已拒绝授权' }}</h3>
          <p class="result-desc">
            <template v-if="result === 'approved'">
              已授权 {{ consent?.clientName || consent?.clientId }} 访问 k-File。<br/>
              请返回 Agent 应用继续操作，本页面可安全关闭。
            </template>
            <template v-else>
              已拒绝 {{ consent?.clientName || consent?.clientId }} 的授权请求。<br/>
            </template>
          </p>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { CircleCheck, CircleClose } from '@element-plus/icons-vue'
import api from '../../api'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const consent = ref(null)
const errorMsg = ref('')
const busy = ref(false)
const result = ref('')  // '' | 'approved' | 'denied'

onMounted(async () => {
  await loadConsent()
})

async function loadConsent() {
  errorMsg.value = ''
  const params = {
    client_id: route.query.client_id,
    redirect_uri: route.query.redirect_uri,
    response_type: route.query.response_type,
    state: route.query.state,
    scope: route.query.scope,
    resource: route.query.resource,
    code_challenge: route.query.code_challenge,
    code_challenge_method: route.query.code_challenge_method
  }
  try {
    const { data } = await api.instance.get('/oauth2/authorize', { params })
    consent.value = data
  } catch (e) {
    // 401：未登录，后端返回 authorizeUrl，跳登录页（登录后回到本页）
    const status = e?.response?.status
    if (status === 401) {
      router.replace({ path: '/admin/login', query: { redirect: route.fullPath } })
      return
    }
    errorMsg.value = e?.response?.data?.error_description || e?.response?.data?.error || '授权请求无效'
  }
}

async function decide(decision) {
  if (!consent.value) return
  busy.value = true
  try {
    const payload = {
      client_id: consent.value.clientId,
      redirect_uri: consent.value.redirectUri,
      response_type: 'code',
      state: consent.value.state,
      scope: consent.value.scope,
      resource: consent.value.resource,
      code_challenge: route.query.code_challenge,
      code_challenge_method: route.query.code_challenge_method,
      decision
    }
    const { data } = await api.instance.post('/oauth2/consent', payload)
    // 先切换到结果页（不阻塞），再触发回调 URL 让 Agent 接收 code/error。
    // 自定义 scheme（如 workbuddy://）或 loopback 回调不会导航走当前页面，
    // 所以必须先切换 UI 到结果页，否则页面停在 consent。
    result.value = decision === 'approve' ? 'approved' : 'denied'
    // 用隐藏 iframe 触发回调（避免 location.href 导致页面跳走/报错），
    // http(s) loopback 和自定义 scheme 都能通过 iframe 触发系统处理。
    triggerRedirect(data.redirect)
  } catch (e) {
    const msg = e?.response?.data?.error_description || e?.message || '操作失败'
    ElMessage.error(msg)
  } finally {
    busy.value = false
  }
}

/** 触发回调 URL：优先用隐藏 iframe（不离开当前页面），兜底 location.href。 */
function triggerRedirect(url) {
  try {
    const iframe = document.createElement('iframe')
    iframe.style.display = 'none'
    iframe.src = url
    document.body.appendChild(iframe)
    // 3 秒后移除 iframe（回调 scheme 通常被系统立即拦截处理）
    setTimeout(() => iframe.remove(), 3000)
  } catch {
    // iframe 失败（如 X-Frame-Options），兜底直接跳转
    window.location.href = url
  }
}

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
.authorize-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 0;
  color: var(--kf-text-sub, #888);
}
.authorize-card {
  width: 560px;
  max-width: 100%;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.08);
}
.authorize-header { text-align: center; }
.authorize-title { margin: 0; font-size: 22px; font-weight: 600; color: var(--kf-text-primary, #333); }
.authorize-subtitle { margin: 4px 0 0; font-size: 13px; color: var(--kf-text-sub, #888); }
.info-block { display: flex; flex-direction: column; gap: 12px; margin: 8px 0; }
.info-row { display: flex; gap: 12px; font-size: 14px; }
.info-label { width: 80px; flex-shrink: 0; color: var(--kf-text-sub, #888); }
.info-value { color: var(--kf-text-primary, #333); word-break: break-all; }
.info-value.break { word-break: break-all; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 16px; }
.result-block { text-align: center; padding: 20px 0; }
.result-icon { font-size: 56px; margin-bottom: 12px; }
.result-icon.success { color: #22c55e; }
.result-icon.denied { color: #ef4444; }
.result-title { margin: 0 0 8px; font-size: 20px; font-weight: 600; }
.result-desc { margin: 0 0 20px; font-size: 14px; line-height: 1.7; color: var(--kf-text-sub, #888); }
</style>
