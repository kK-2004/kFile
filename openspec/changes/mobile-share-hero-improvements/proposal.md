## Why

k-File 的前端目前面向桌面端设计，移动端体验差（header 导航溢出、表格裁切、无响应式适配）。同时首页（Hero）内容过时——仅展示"作业收集"4 个特性，未覆盖已上线的 MCP 集成、文件管理、对象存储等核心能力。分享页功能单一（仅打包下载全部），缺少单文件/多选下载与下载量统计。

## What Changes

- **移动端适配**：
  - App.vue header 在小屏（<768px）折叠为汉堡菜单 + 抽屉式导航（el-drawer）。
  - admin 列表页（AdminProjects/Submissions/Files/Shares/Users/Templates）el-table 在小屏允许横向滚动（移除 admin.css 的 overflow-x:hidden 限制）。
  - 各页面 card-header / 工具栏在窄屏自适应换行（flex-wrap）。
  - 统一响应式断点为 Tailwind 默认（sm 640 / md 768 / lg 1024）。
- **Hero 首页美化 + 内容更新**：
  - 更新特性卡片：保留原有 4 项，新增 MCP 集成、文件管理（多级文件夹/断点续传/配额）、对象存储（OSS+MinIO 双源）等已上线能力。
  - 视觉美化：动效、暗色模式优化、响应式断点。
- **分享页增强**：
  - 文件列表支持 checkbox 多选 + 单文件下载按钮。
  - 「打包下载选中」按钮（选中文件打包）+ 保留「打包下载全部」。
  - 下载量计数：ShareLink 加 downloadCount + 每文件维度计数（data.entries[].downloadCount）。
  - 分享页展示下载次数（链接维度 + 文件维度）。
  - 分享管理页（AdminShares）展示链接维度下载量列。
  - 新增后端端点 `POST /api/share/{code}/download` 记录下载（链接 +1、对应文件 +1）。

## Capabilities

### Modified Capabilities
- `mobile-responsive`: 全站移动端响应式适配（header 抽屉、表格滚动、工具栏换行、断点统一）。
- `share-download`: 分享页支持多选/单文件下载 + 下载量计数（链接维度 + 文件维度）。
- `hero-content`: 首页内容更新与美化（覆盖 MCP/文件管理/对象存储，暗色/响应式优化）。

## Impact

- **前端**：App.vue（汉堡菜单+抽屉）、Hero.vue（重写内容）、ShareDownload.vue（多选+计数）、AdminShares.vue（下载量列）、admin.css（移除 overflow-x:hidden + 响应式补丁）、各 admin 列表页（card-header flex-wrap）。
- **后端**：ShareLink 实体加 downloadCount 字段；ShareController 加 `POST /api/share/{code}/download` 端点（计数自增）；ShareLinkAdminController.list 返回 downloadCount。
- **数据库**：ShareLink 表加 `download_count` 列（ddl-auto:update 自动）；data JSON 的 entries[].downloadCount 由后端动态维护。
- **无破坏性变更**：现有 API 兼容；OSS 提交/归档/MCP 链路不受影响。
