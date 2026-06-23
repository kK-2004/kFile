<template>
  <section class="hero-root">
    <div class="hero-backdrop" aria-hidden="true">
      <div class="glow-orb primary"></div>
      <div class="glow-orb secondary"></div>
      <div class="hero-grain"></div>
    </div>

    <div class="hero-shell">
      <div class="hero-copy-block">
        <p class="hero-kicker animate-fade-up" style="animation-delay: 0.1s">
          <span class="badge">k-File</span>
          <span class="subtitle">新一代文件收集工作台</span>
        </p>

        <h1 class="hero-title animate-fade-up" style="animation-delay: 0.2s">
          <span>把收集、校验、归档</span>
          <span class="text-gradient">收进同一条流水线</span>
        </h1>

        <p class="hero-description animate-fade-up" style="animation-delay: 0.3s">
          专为课程作业、培训材料与团队资料收集打造。自动命名、名单校验、进度催收和对象存储双源备份，让繁琐的文件管理回归简单。
        </p>

        <div class="hero-actions animate-fade-up" style="animation-delay: 0.4s">
          <button class="action-btn primary" type="button" @click="showBetaModal = true">
            立即开始收集
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" class="icon-arrow">
              <path fill-rule="evenodd" d="M3 10a.75.75 0 0 1 .75-.75h9.638L10.23 6.293a.75.75 0 1 1 1.04-1.086l4.999 4.75a.75.75 0 0 1 0 1.086l-4.999 4.75a.75.75 0 1 1-1.04-1.086l3.158-2.957H3.75A.75.75 0 0 1 3 10Z" clip-rule="evenodd" />
            </svg>
          </button>

          <RouterLink class="action-btn secondary" to="/admin/login">
            管理端入口
          </RouterLink>
        </div>

        <div class="hero-stats animate-fade-up" style="animation-delay: 0.5s" aria-label="核心能力概览">
          <div v-for="item in stats" :key="item.label" class="stat-item">
            <strong>{{ item.value }}</strong>
            <span>{{ item.label }}</span>
          </div>
        </div>
      </div>

      <div class="hero-panel-wrapper animate-fade-up" style="animation-delay: 0.3s">
        <div class="hero-panel" aria-label="收集项目概览">
          <div class="panel-topbar">
            <span class="mac-dot close"></span>
            <span class="mac-dot minimize"></span>
            <span class="mac-dot maximize"></span>
          </div>

          <div class="panel-content">
            <div class="panel-header">
              <div class="header-info">
                <span class="eyebrow">正在收集</span>
                <h2>期末材料归档</h2>
              </div>
              <div class="header-progress">87%</div>
            </div>

            <div class="status-strip">
              <span class="label">截止时间</span>
              <strong class="value highlight">今晚 23:59</strong>
            </div>

            <div class="progress-bar">
              <div class="progress-fill" style="width: 87%"></div>
            </div>

            <div class="submission-list">
              <div v-for="row in panelRows" :key="row.label" class="list-row">
                <div class="row-info">
                  <span :class="['dot', row.tone]"></span>
                  <span class="row-label">{{ row.label }}</span>
                </div>
                <strong class="row-value">{{ row.value }}</strong>
              </div>
            </div>

            <div class="rule-card">
              <div class="rule-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M13.5 16.875h3.375m0 0h3.375m-3.375 0V13.5m0 3.375v3.375M6 10.5h2.25a2.25 2.25 0 0 0 2.25-2.25V6a2.25 2.25 0 0 0-2.25-2.25H6A2.25 2.25 0 0 0 3.75 6v2.25A2.25 2.25 0 0 0 6 10.5Zm0 9.75h2.25A2.25 2.25 0 0 0 10.5 18v-2.25a2.25 2.25 0 0 0-2.25-2.25H6a2.25 2.25 0 0 0-2.25 2.25V18A2.25 2.25 0 0 0 6 20.25Zm9.75-9.75H18a2.25 2.25 0 0 0 2.25-2.25V6A2.25 2.25 0 0 0 18 3.75h-2.25A2.25 2.25 0 0 0 13.5 6v2.25a2.25 2.25 0 0 0 2.25 2.25Z" /></svg>
              </div>
              <div class="rule-text">
                <span>自动命名规则</span>
                <strong>学号-姓名-项目名</strong>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="feature-section animate-fade-up" style="animation-delay: 0.6s">
      <div class="hero-tags" aria-label="适用场景">
        <span v-for="tag in tags" :key="tag" class="tag-pill">{{ tag }}</span>
      </div>

      <div class="feature-grid">
        <article v-for="feature in features" :key="feature.title" class="bento-card">
          <div :class="['feature-icon', feature.tone]">{{ feature.code }}</div>
          <h3>{{ feature.title }}</h3>
          <p>{{ feature.text }}</p>
        </article>
      </div>

      <div class="roadmap-section animate-fade-up" style="animation-delay: 0.7s">
        <div class="roadmap-header">
          <span class="roadmap-eyebrow">Product Pipeline</span>
          <h2>产品演进流水线</h2>
        </div>
        <div class="timeline">
          <div v-for="(item, index) in roadmapItems" :key="index" class="timeline-item" :class="item.status">
            <div class="timeline-node">
              <div class="node-inner"></div>
            </div>
            <div class="timeline-card">
              <div class="card-header">
                <span class="status-badge">{{ item.statusText }}</span>
                <h4>{{ item.title }}</h4>
              </div>
              <p>{{ item.desc }}</p>
            </div>
          </div>
        </div>
      </div>

      <p class="hero-footer">Powered by kk</p>
    </div>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showBetaModal" class="modal-overlay" role="dialog" aria-modal="true">
          <div class="modal-backdrop" @click="showBetaModal = false"></div>

          <div class="modal-content">
            <div class="modal-badge">
              <span class="pulse-dot"></span>
              Beta 内测版
            </div>
            <h3>k-File 正在内测</h3>
            <p>当前版本仅对受邀用户开放。<br />如需体验，请联系管理员添加测试权限。</p>

            <div class="contact-box">
              <div class="contact-info">
                <span class="label">联系管理员 (QQ)</span>
                <span class="value">{{ contactQQ }}</span>
              </div>
              <button
                  class="copy-btn"
                  :class="{ 'is-copied': copied }"
                  @click="copyToClipboard"
              >
                {{ copied ? '已复制' : '复制' }}
              </button>
            </div>

            <button class="close-btn" @click="showBetaModal = false">关闭</button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { copyText } from '../utils/clipboard'
import api from '../api'

const stats = [
  { value: '7+', label: '核心能力' },
  { value: '0', label: '人工改名' },
  { value: '2', label: '存储源' }
]

const panelRows = [
  { value: '128', label: '已提交文件', tone: 'hot' },
  { value: '38', label: '已自动重命名', tone: 'success' },
  { value: '11', label: '待提醒人员', tone: 'warning' }
]

const tags = ['课程作业', '培训材料', '活动报名', '团队归档', 'MCP 助手', '对象存储']

const features = [
  { code: '截止', tone: 'hot', title: '智能截止控制', text: '一键设置截止时间，到期自动停止收集，支持单人多文件提交。' },
  { code: '命名', tone: 'success', title: '配置与自动命名', text: '提前导入人员名单并配置文件格式，系统为您自动整理规范文件名。' },
  { code: '催收', tone: 'warning', title: '实时进度催收', text: '实时统计提交进度，一键导出未提交名单，提醒更精准。' },
  { code: '打包', tone: 'primary', title: '一键打包下载', text: '按项目批量打包生成下载直链，限时分享也能集中管理。' },
  { code: 'AI', tone: 'neutral', title: 'MCP AI 集成', text: '让 AI 客户端查询项目、查看提交、生成分享链接，减少重复操作。' },
  { code: '存储', tone: 'dark', title: '对象存储双源', text: '支持阿里云 OSS 与 MinIO，浏览器直传，兼顾性能和弹性。' }
]

const showBetaModal = ref(false)
const contactQQ = '2604159440'
const copied = ref(false)

const copyToClipboard = async () => {
  try {
    await copyText(contactQQ)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch (err) {
    console.error('Failed to copy!', err)
  }
}

// 产品路线图：默认值（后端未配置时兜底），onMounted 拉取覆盖
const DEFAULT_ROADMAP = [
  {
    status: 'developing',
    statusText: '正在开发',
    title: '飞书机器人集成',
    desc: '绑定群机器人，实现收集进度定时播报，并在截止时间前自动 @未交人员。'
  },
  {
    status: 'done',
    statusText: '最近上线',
    title: 'MinIO 私有化存储源',
    desc: '新增对 MinIO 的全面支持，满足纯内网与强隐私安全环境下的私有化部署需求。'
  },
  {
    status: 'done',
    statusText: '最近上线',
    title: 'MCP AI 助手能力',
    desc: '开放 Model Context Protocol 接口，可通过第三方 AI 客户端直接查询收集状态。'
  }
]
const roadmapItems = ref(DEFAULT_ROADMAP)

onMounted(async () => {
  try {
    const { data } = await api.getHeroData()
    const items = data?.roadmapItems
    if (Array.isArray(items) && items.length > 0) {
      roadmapItems.value = items
    }
  } catch (e) {
    // 接口不可用时保留默认值，不影响首页渲染
  }
})
</script>

<style scoped>
/* ================== 主题变量 (Modern Design System) ================== */
.hero-root,
.modal-overlay {
  --bg-base: #f7f3ec;
  --bg-surface: #fffaf2;
  --bg-elevated: rgba(255, 250, 242, 0.72);
  --text-main: #211c17;
  --text-muted: #74685c;
  --border-color: rgba(82, 65, 45, 0.14);

  --primary: #e85b3a;
  --primary-hover: #c94527;
  --primary-light: rgba(232, 91, 58, 0.12);

  --color-hot: #e85b3a;
  --color-success: #7f8d48;
  --color-warning: #c9832d;
  --color-neutral: #8b7964;
  --color-dark: #5f564d;
  --color-info: #3b82f6;

  --shadow-sm: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
  --shadow-lg: 0 20px 40px -8px rgba(72, 44, 24, 0.12);
  --shadow-glass: inset 0 1px 0 0 rgba(255, 255, 255, 0.8);
}

.hero-root {
  position: relative;
  overflow: hidden;
  min-height: calc(100vh - 64px);
  padding: clamp(4rem, 8vw, 6rem) clamp(1.5rem, 5vw, 4rem) 4rem;
  background-color: var(--bg-base);
  color: var(--text-main);
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

/* 深色模式适配 */
:global(html.dark) .hero-root,
:global(html.dark) .modal-overlay {
  --bg-base: #11100e;
  --bg-surface: #1b1713;
  --bg-elevated: rgba(35, 30, 24, 0.68);
  --text-main: #f7efe5;
  --text-muted: #b9afa1;
  --border-color: rgba(246, 230, 203, 0.13);
  --primary: #ff6a4a;
  --primary-hover: #e24d34;
  --primary-light: rgba(255, 106, 74, 0.13);
  --color-hot: #ff6a4a;
  --color-success: #a0a66a;
  --color-warning: #e0a957;
  --color-neutral: #b8aa98;
  --color-dark: #d08c59;
  --color-info: #60a5fa;
  --shadow-lg: 0 20px 40px -8px rgba(0, 0, 0, 0.4);
  --shadow-glass: inset 0 1px 0 0 rgba(255, 255, 255, 0.05);
}

/* ================== 背景动效 ================== */
.hero-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.glow-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.4;
}

.glow-orb.primary {
  width: 600px;
  height: 600px;
  background: radial-gradient(circle, var(--primary-light) 0%, transparent 70%);
  top: -100px;
  right: -100px;
}

.glow-orb.secondary {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(224, 169, 87, 0.10) 0%, transparent 70%);
  bottom: 10%;
  left: -150px;
}

.hero-grain {
  position: absolute;
  inset: 0;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)' opacity='0.03'/%3E%3C/svg%3E");
}

/* ================== 布局容器 ================== */
.hero-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(3rem, 6vw, 6rem);
  align-items: center;
  max-width: 1280px;
  margin: 0 auto;
}

/* ================== 左侧文案 ================== */
.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}

.hero-kicker .badge {
  background: var(--primary-light);
  color: var(--primary);
  padding: 0.25rem 0.75rem;
  border-radius: 99px;
  font-size: 0.875rem;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.hero-kicker .subtitle {
  color: var(--text-muted);
  font-size: 0.9rem;
  font-weight: 500;
}

.hero-title {
  font-size: clamp(2rem, 3.5vw, 3.5rem);
  line-height: 1.1;
  font-weight: 800;
  letter-spacing: -0.03em;
  margin: 0 0 1.5rem;
}

.text-gradient {
  font-size: clamp(2.5rem, 4.5vw, 4.5rem);
  background: linear-gradient(135deg, var(--primary), #FF8A00);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  display: block;
}

.hero-description {
  font-size: clamp(1rem, 1.2vw, 1.25rem);
  line-height: 1.7;
  color: var(--text-muted);
  max-width: 540px;
  margin-bottom: 2.5rem;
}

/* ================== 按钮组 ================== */
.hero-actions {
  display: flex;
  gap: 1rem;
  margin-bottom: 3.5rem;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  height: 3.5rem;
  padding: 0 1.75rem;
  border-radius: 12px;
  font-size: 1rem;
  font-weight: 600;
  text-decoration: none;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  cursor: pointer;
}

.action-btn.primary {
  background: var(--primary);
  color: white;
  border: none;
  box-shadow: 0 8px 20px -6px rgba(255, 90, 54, 0.4);
}

.action-btn.primary:hover {
  background: var(--primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 12px 24px -8px rgba(255, 90, 54, 0.5);
}

.action-btn.secondary {
  background: var(--bg-surface);
  color: var(--text-main);
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-sm);
}

.action-btn.secondary:hover {
  border-color: var(--text-muted);
  transform: translateY(-2px);
}

.icon-arrow {
  width: 1.25rem;
  height: 1.25rem;
  transition: transform 0.2s;
}

.action-btn.primary:hover .icon-arrow {
  transform: translateX(4px);
}

/* ================== 数据统计 ================== */
.hero-stats {
  display: flex;
  gap: 3rem;
  padding-top: 2.5rem;
  border-top: 1px solid var(--border-color);
}

.stat-item strong {
  display: block;
  font-size: 2rem;
  font-weight: 800;
  line-height: 1.2;
  color: var(--text-main);
}

.stat-item span {
  font-size: 0.875rem;
  color: var(--text-muted);
  font-weight: 500;
}

/* ================== 右侧演示面板 (Glassmorphism) ================== */
.hero-panel-wrapper {
  perspective: 1000px;
}

.hero-panel {
  background: var(--bg-elevated);
  backdrop-filter: blur(24px);
  border: 1px solid var(--border-color);
  border-radius: 24px;
  box-shadow: var(--shadow-lg), var(--shadow-glass);
  overflow: hidden;
  transform: rotateY(-4deg) rotateX(2deg);
  transition: transform 0.4s ease;
}

.hero-panel:hover {
  transform: rotateY(0) rotateX(0);
}

.panel-topbar {
  display: flex;
  gap: 8px;
  padding: 16px;
  background: rgba(0,0,0,0.02);
  border-bottom: 1px solid var(--border-color);
}

.mac-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}
.mac-dot.close { background: #FF5F56; }
.mac-dot.minimize { background: #FFBD2E; }
.mac-dot.maximize { background: #27C93F; }

.panel-content {
  padding: 24px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 24px;
}

.eyebrow {
  font-size: 0.875rem;
  color: var(--primary);
  font-weight: 700;
}

.header-info h2 {
  font-size: 1.5rem;
  margin: 4px 0 0;
}

.header-progress {
  font-size: 2.5rem;
  font-weight: 800;
  color: var(--text-main);
  line-height: 1;
}

.status-strip, .rule-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  margin-bottom: 16px;
}

.status-strip .label { color: var(--text-muted); font-size: 0.875rem; }
.status-strip .value.highlight { color: var(--color-hot); font-weight: 700; }

.progress-bar {
  height: 8px;
  background: var(--border-color);
  border-radius: 99px;
  margin-bottom: 24px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--primary), #FF8A00);
  border-radius: 99px;
}

.submission-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}

.list-row {
  display: flex;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--bg-base);
  border-radius: 10px;
  font-size: 0.95rem;
}

.row-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.hot { background: var(--color-hot); box-shadow: 0 0 0 4px rgba(239, 68, 68, 0.1); }
.dot.success { background: var(--color-success); box-shadow: 0 0 0 4px rgba(127, 141, 72, 0.12); }
.dot.warning { background: var(--color-warning); box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.1); }

.rule-card {
  margin-bottom: 0;
  justify-content: flex-start;
  gap: 16px;
}

.rule-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-light);
  color: var(--primary);
  border-radius: 10px;
}

.rule-text span { display: block; font-size: 0.75rem; color: var(--text-muted); }
.rule-text strong { display: block; font-size: 0.95rem; }

/* ================== 底部特性区 (Bento Grid) ================== */
.feature-section {
  position: relative;
  z-index: 1;
  max-width: 1280px;
  margin: 4rem auto 0;
  border-top: 1px solid var(--border-color);
  padding-top: 4rem;
}

.hero-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.75rem;
  margin-bottom: 3rem;
}

.tag-pill {
  padding: 0.5rem 1rem;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 99px;
  font-size: 0.875rem;
  color: var(--text-muted);
  font-weight: 500;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
}

.bento-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  padding: 24px;
  transition: all 0.3s ease;
}

.bento-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
  border-color: var(--primary-light);
}

.feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  font-size: 0.875rem;
  font-weight: 700;
  margin-bottom: 16px;
}
.feature-icon.hot { background: rgba(239, 68, 68, 0.1); color: var(--color-hot); }
.feature-icon.success { background: rgba(127, 141, 72, 0.12); color: var(--color-success); }
.feature-icon.warning { background: rgba(245, 158, 11, 0.1); color: var(--color-warning); }
.feature-icon.primary { background: var(--primary-light); color: var(--primary); }
.feature-icon.neutral { background: rgba(139, 121, 100, 0.14); color: var(--color-neutral); }
.feature-icon.dark { background: rgba(95, 86, 77, 0.14); color: var(--color-dark); }

.bento-card h3 { font-size: 1.125rem; margin: 0 0 8px; }
.bento-card p { font-size: 0.9rem; color: var(--text-muted); line-height: 1.6; margin: 0; }

.hero-footer {
  text-align: center;
  margin-top: 4rem;
  font-size: 0.875rem;
  color: var(--text-muted);
  opacity: 0.7;
}

/* ================== 弹窗样式优化 ================== */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

.modal-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 10, 8, 0.58);
  backdrop-filter: blur(8px);
}

.modal-content {
  position: relative;
  width: 100%;
  max-width: 400px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 24px;
  padding: 2rem;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  text-align: center;
}

.modal-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: var(--primary-light);
  color: var(--primary);
  border-radius: 99px;
  font-size: 0.875rem;
  font-weight: 600;
  margin-bottom: 16px;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background: currentColor;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

.modal-content h3 { font-size: 1.25rem; margin: 0 0 12px; }
.modal-content p { font-size: 0.95rem; color: var(--text-muted); line-height: 1.6; margin: 0 0 24px; }

.contact-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-base);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 24px;
  text-align: left;
}

.contact-info .label { display: block; font-size: 0.75rem; color: var(--text-muted); margin-bottom: 4px; }
.contact-info .value { font-family: monospace; font-size: 1.125rem; font-weight: 700; color: var(--text-main); }

.copy-btn {
  background: var(--primary);
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.copy-btn.is-copied { background: var(--color-success); }

.close-btn {
  width: 100%;
  padding: 12px;
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--text-main);
  border-radius: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}
.close-btn:hover { background: var(--bg-base); }

/* ================== 产品演进流水线 (Roadmap Pipeline) ================== */
.roadmap-section {
  max-width: 800px;
  margin: 5rem auto 0;
  padding-top: 4rem;
  border-top: 1px dashed var(--border-color);
}

.roadmap-header {
  text-align: center;
  margin-bottom: 3.5rem;
}

.roadmap-eyebrow {
  display: inline-block;
  font-size: 0.875rem;
  color: var(--primary);
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  margin-bottom: 0.5rem;
}

.roadmap-header h2 {
  font-size: 2rem;
  font-weight: 800;
  margin: 0;
}

.timeline {
  position: relative;
  padding-left: 2rem;
}

/* 垂直线（中心锚点 left:8px） */
.timeline::before {
  content: '';
  position: absolute;
  left: 8px;
  margin-left: -1px; /* 线宽 2px，向左偏移半个宽度使中心落在 8px */
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--border-color);
  border-radius: 2px;
}

.timeline-item {
  position: relative;
  margin-bottom: 2.5rem;
}

.timeline-item:last-child {
  margin-bottom: 0;
}

/* 时间轴节点（中心对齐到垂直线锚点） */
/* 节点是 .timeline-item 子元素，基准为内容区左缘（= 盒子左缘 + padding-left 2rem=32px）。
   垂直线锚点在盒子左缘 8px → 节点 left 需为 8-32 = -24px；translateX(-50%) 使节点中心落在该点，
   不受 border / box-sizing 影响。 */
.timeline-node {
  position: absolute;
  left: -24px;
  transform: translateX(-50%);
  top: 0.25rem;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--bg-base);
  border: 2px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
  transition: all 0.3s ease;
}

.node-inner {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: transparent;
  transition: background 0.3s ease;
}

/* 正在开发状态 - 橘色 + 呼吸灯 */
.timeline-item.developing .timeline-node {
  border-color: var(--primary);
  box-shadow: 0 0 0 4px var(--primary-light);
}
.timeline-item.developing .node-inner {
  background: var(--primary);
  animation: pulse-small 2s infinite;
}

/* 已上线状态 - 绿色静止 */
.timeline-item.done .timeline-node {
  border-color: var(--color-success);
}
.timeline-item.done .node-inner {
  background: var(--color-success);
}

/* 规划中状态 - 蓝色静止 */
.timeline-item.planned .timeline-node {
  border-color: var(--color-info);
}
.timeline-item.planned .node-inner {
  background: var(--color-info);
}

/* 右侧卡片内容 */
.timeline-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 1.5rem;
  transition: all 0.3s ease;
}

.timeline-card:hover {
  transform: translateX(4px);
  border-color: var(--primary-light);
  box-shadow: var(--shadow-sm);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.75rem;
}

.status-badge {
  font-size: 0.75rem;
  font-weight: 700;
  padding: 0.25rem 0.6rem;
  border-radius: 6px;
}

.timeline-item.developing .status-badge {
  background: var(--primary-light);
  color: var(--primary);
}

.timeline-item.done .status-badge {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.timeline-item.planned .status-badge {
  background: rgba(59, 130, 246, 0.12);
  color: var(--color-info);
}

.card-header h4 {
  font-size: 1.1rem;
  margin: 0;
  color: var(--text-main);
}

.timeline-card p {
  margin: 0;
  font-size: 0.9rem;
  color: var(--text-muted);
  line-height: 1.6;
}

@keyframes pulse-small {
  0% { box-shadow: 0 0 0 0 rgba(255, 90, 54, 0.4); }
  70% { box-shadow: 0 0 0 4px rgba(255, 90, 54, 0); }
  100% { box-shadow: 0 0 0 0 rgba(255, 90, 54, 0); }
}

/* 移动端流水线适配 */
@media (max-width: 768px) {
  .roadmap-section { padding-top: 2rem; margin-top: 3rem; }
  .timeline { padding-left: 1.5rem; }              /* padding 24px */
  .timeline::before { left: 4px; margin-left: -1px; } /* 线中心 4px */
  .timeline-node { left: -20px; width: 12px; height: 12px; } /* 4-24=-20px，配合 translateX(-50%) 居中 */
  .node-inner { width: 4px; height: 4px; }
  .card-header { flex-direction: column; align-items: flex-start; gap: 0.6rem; }
}

/* ================== 动画定义 ================== */
@keyframes fadeUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.animate-fade-up {
  opacity: 0;
  animation: fadeUp 0.8s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(255, 90, 54, 0.4); }
  70% { box-shadow: 0 0 0 6px rgba(255, 90, 54, 0); }
  100% { box-shadow: 0 0 0 0 rgba(255, 90, 54, 0); }
}

/* 弹窗过渡动画 */
.modal-enter-active, .modal-leave-active { transition: all 0.3s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; transform: scale(0.95) translateY(10px); }

/* ================== 响应式适配 ================== */
@media (max-width: 1024px) {
  .hero-shell {
    grid-template-columns: 1fr;
    text-align: center;
  }
  .hero-description {
    margin-left: auto;
    margin-right: auto;
  }
  .hero-actions {
    justify-content: center;
  }
  .hero-stats {
    justify-content: center;
  }
  .hero-panel {
    max-width: 600px;
    margin: 0 auto;
    transform: none;
  }
}

@media (max-width: 640px) {
  .hero-root { padding-top: 3rem; }
  .hero-actions { flex-direction: column; }
  .hero-stats { flex-direction: column; gap: 1.5rem; }
  .stat-item { padding-bottom: 1.5rem; border-bottom: 1px solid var(--border-color); }
  .stat-item:last-child { border-bottom: none; }
}
</style>
