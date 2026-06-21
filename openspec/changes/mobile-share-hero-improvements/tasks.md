## 1. 移动端适配

- [x] 1.1 App.vue header：小屏（<768px）汉堡菜单 + el-drawer 抽屉导航；大屏保持原样。新增 `isMobile` composable（matchMedia 监听）
- [x] 1.2 admin.css：移除 `.el-table__body-wrapper { overflow-x: hidden }` → `auto`；移除全局 overflow-x:hidden 对表格的影响
- [x] 1.3 admin.css：card-header / 工具栏加 `flex-wrap: wrap` 响应式
- [x] 1.4 各 admin 列表页（AdminProjects/Submissions/Files/Shares/Users/Templates）：card-header flex-wrap 补丁
- [ ] 1.5 UserSubmit.vue：清理行 1436 转义 class hack；统一断点为 md(768)
- [ ] 1.6 ShareDownload.vue：share-page padding 小屏断点（<768 收窄到 12px）

## 2. Hero 首页美化 + 内容更新

- [x] 2.1 Hero.vue：新增特性卡片——MCP AI 集成、文件管理（多级文件夹/断点续传/配额）、对象存储（OSS+MinIO 双源）
- [x] 2.2 Hero.vue：网格改 lg:grid-cols-3 / sm:grid-cols-2 / grid-cols-1
- [x] 2.3 Hero.vue：视觉美化（动效、暗色模式优化、卡片间距）

## 3. 分享页多选 + 单文件下载

- [x] 3.1 ShareDownload.vue：文件列表加 checkbox 列 + 单文件下载按钮
- [x] 3.2 ShareDownload.vue：「下载选中」按钮（JSZip 打包选中文件）+ 保留「打包下载全部」
- [x] 3.3 ShareDownload.vue：单文件下载（直接 a 标签下载，不走 zip）

## 4. 下载量计数（后端）

- [x] 4.1 ShareLink 实体加 `downloadCount`（int，默认 0）字段
- [x] 4.2 新增 `POST /api/share/{code}/download`（permitAll）：downloadCount 原子自增 + 对应文件 entries[entryIndex].downloadCount 自增
- [x] 4.3 ShareController.getShare 返回链接维度 downloadCount + entries[].downloadCount
- [x] 4.4 ShareLinkAdminController.list 返回 downloadCount

## 5. 下载量计数（前端）

- [x] 5.1 ShareDownload.vue：下载时调 `POST /api/share/{code}/download`（单文件传 entryIndex；打包全部循环传每个 index）
- [x] 5.2 ShareDownload.vue：展示链接总下载次数 + 每文件下载次数（getShare 返回）
- [x] 5.3 AdminShares.vue：列表加「下载量」列（downloadCount）

## 6. 验证

- [x] 6.1 build_project + vite build 通过
- [ ] 6.2 手测：移动端 header 抽屉、表格滚动、工具栏换行
- [ ] 6.3 手测：Hero 内容完整、响应式、暗色
- [ ] 6.4 手测：分享页多选下载 + 单文件下载 + 计数展示 + 管理页下载量列
