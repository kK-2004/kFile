## Context

k-File 前端当前面向桌面端设计，移动端体验差。首页内容过时。分享页功能单一。本变更统一改善移动端适配、更新首页内容、增强分享下载能力与统计。

## Goals / Non-Goals

**Goals:**
- 移动端：header 汉堡菜单抽屉、表格横向滚动、工具栏自适应换行、断点统一。
- Hero：更新特性卡片覆盖 MCP/文件管理/对象存储，视觉美化。
- 分享：多选 + 单文件下载、下载量计数（链接 + 文件双维度）。

**Non-Goals:**
- 不重写 admin 页面为卡片列表（仅修复表格滚动 + 工具栏换行）。
- 不做 PWA / 离线支持。
- 不做下载日志审计（仅计数，不记录谁下载）。
- 不做 Hero 的 SEO/i18n。

## Decisions

### 1. 移动端导航：汉堡菜单 + el-drawer 抽屉
- 小屏（<768px）header 隐藏所有导航按钮，显示汉堡图标；点击打开 el-drawer（左侧抽屉）展示导航列表。
- 大屏（>=768px）保持现有 header 按钮布局。
- 用 `window.matchMedia('(max-width: 768px)')` + resize 监听切换（响应式 composable）。
- **Why 抽屉非下拉**：导航项 10+，下拉高度不够；抽屉更适合长列表。

### 2. 表格移动端：允许横向滚动
- 移除 `admin.css:115-117` 的 `.el-table__body-wrapper { overflow-x: hidden }` → 改为 `auto`。
- 移除全局 `overflow-x: hidden`（admin.css:168）对 el-table 的影响（或限定到非 table 区域）。
- **Why 不改卡片列表**：改卡片成本高（每页重写），横向滚动是 Element Plus 表格的标准移动端方案。

### 3. 响应式断点统一
- 所有自定义 @media 统一用 Tailwind 默认断点：sm(640)/md(768)/lg(1024)。
- 清理 UserSubmit.vue 行 1436 的转义 class hack。

### 4. Hero 内容更新
- 保留原有 4 卡（截止控制/命名校验/进度催收/打包下载），新增 2-3 卡：
  - MCP 集成（AI 客户端直接操作项目/文件）
  - 文件管理（多级文件夹/断点续传/配额管理）
  - 对象存储（OSS + MinIO 双数据源）
- 布局改为 3 列网格（lg）/ 2 列（sm）/ 1 列（mobile）。

### 5. 分享页多选 + 单文件下载
- 文件列表每行加 checkbox；勾选后「下载选中」按钮激活（JSZip 打包选中文件）。
- 每行加「下载」按钮（单文件直接下载，不走 zip）。
- 保留「打包下载全部」。

### 6. 下载量计数：链接维度 + 文件维度
- ShareLink 实体加 `downloadCount`（int，链接维度总下载次数）。
- ShareLink.data 的 entries[].downloadCount（文件维度，后端动态读写 JSON）。
- 新增 `POST /api/share/{code}/download`（permitAll，无鉴权）：body `{ entryIndex }`（打包下载传 null/不传），后端 `downloadCount++` + 对应文件 `downloadCount++`。
- 分享页 GET 时返回 entries[].downloadCount + 链接维度 downloadCount。
- AdminShares 列表加「下载量」列（链接维度）。
- **Why POST 非 GET**：下载是动作；且支持 body 传 entryIndex。计数在点击下载时前端调一次。

## Risks / Trade-offs

- **[计数不精确]** → 客户端 fetch 预签名 URL 后可能取消下载，计数仍 +1。可接受（计数是粗略统计，非精确审计）。
- **[data JSON 并发写]** → entries[].downloadCount 并发自增可能竞争；用 `@Transactional` + 乐观锁或 UPDATE 原子自增（`download_count = download_count + 1`）避免。
- **[移动端表格体验]** → 横向滚动非最优体验但成本最低；后续可按需改卡片列表（Non-Goal）。

## Migration Plan

1. 后端先加 ShareLink.downloadCount + 端点（兼容现有）。
2. 前端移动端适配 + Hero 更新。
3. 分享页多选下载 + 计数。
4. 无 DB schema 破坏性变更（仅加列）；回滚无影响。
