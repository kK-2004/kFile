## Why

分享链接当前是「创建时一次性落库的快照」：`share_link.data` 里以 JSON 存死一串文件/提交条目，访问时只为永久链接重新签名下载 URL，从不比对文件或提交的当前状态。结果是：

- 文件管理里**分享了一个文件夹**，之后往里加文件 / 删文件，链接里看不到变化（用户期望实时反映）。
- **项目提交分享**（管理端-提交记录-分享链接，按"当前项目所有最新提交"生成），之后有新提交 / 删除某个用户提交，链接里的内容也不会更新——这与"最新提交"的语义直接冲突。

我们希望分享链接从「静态快照」演进为「访问时按类型实时同步」：文件夹分享与提交分享走实时视图（增删都反映），多选文件分享在文件被删时保留可追溯的置灰"已删除"行。这样既贴合用户对"分享一个文件夹/最新提交"的直觉，又不会让多选文件分享在文件被删后留下失效下载链接。

## What Changes

### 数据模型

- **新增 `share_link_item` 表**：把分享条目从 `share_link.data` JSON blob 中拆出来，作为规范化的逐条记录。每条记录独立追踪 `kind`（FOLDER / FILE / SUBMISSION）、来源引用（`stored_file_id` 或 `submission_id`）、`deleted`（软删置灰标志）、`relativePath`、原始 `storageSource`/`storageKey` 等访问态字段。
- `share_link` 表新增 `share_type` 列（`FOLDER_SYNC` / `FILE_SET` / `SUBMISSION_SYNC`），决定访问时的同步策略。
- `share_link.data` JSON 仅在迁移期保留为只读回退，新写入与读取均以 `share_link_item` 为准。

### 分享创建（按类型分流）

- **分享文件夹** → `FOLDER_SYNC`：创建时快照该文件夹下当前文件，记下根 `stored_file_id`。访问时实时重算该文件夹当前文件集，对新增条目插入、对消失条目软删置灰（保持可追溯）。
- **多选文件**（即使包含整个文件夹内容、或同时混选文件夹+文件）→ `FILE_SET`：逐文件快照，不跟随文件夹内容；文件被删时置 `deleted=true` 整行置灰显示"已删除"；若被选中的文件夹本身被删，该文件夹行也置灰。新增文件不进列表。
- **项目提交分享** → `SUBMISSION_SYNC`：记下 `projectId` + 可选 `fieldKey`/`fieldValue` 过滤条件。访问时实时调用现有"每提交人最新有效提交"逻辑（`findVisibleByProjectOrderByCreatedAtDesc` + 按 `submitterFingerprint` 去重），新提交自动入列，被删/失效提交直接从列表移除（实时视图，不置灰）。

### 分享访问（实时同步）

- 公开 GET `/api/share/{code}` 在渲染前根据 `share_type` 执行同步：
  - `FOLDER_SYNC`：diff 当前文件夹文件集与已存条目，upsert 新增、软删消失项。
  - `FILE_SET`：检测被引用 `stored_file` 是否已不存在，置 `deleted=true`。
  - `SUBMISSION_SYNC`：重算当前最新有效提交集合，替换条目集（移除不再匹配项，插入新匹配项）。
- 同步落库后再返回最终列表；前端按 `deleted` 标志渲染整行置灰 + "已删除"。

### 前端

- `ShareDownload.vue` 渲染层支持 `deleted` 标志：整行置灰、显示"已删除"、隐藏下载按钮。
- 文件夹分享的树形视图随实时同步结果增减节点。
- 创建分享的调用链不变（前端仍发 `fileIds` / `entries`），类型由后端按选中结构判定。

## Capabilities

### New Capabilities
- `share-link-realtime-sync`: 访问时按分享类型实时同步分享条目（文件夹/提交走实时增删视图，多选文件走删除置灰），并以此为分享内容唯一真实来源。

### Modified Capabilities
（无：现有 spec 均不涉及分享条目的可见性/时效语义。）

## Impact

### 后端
- **数据层**：新增 `ShareLinkItem` 实体 + `share_link_item` 表（Hibernate `ddl-auto: update`，无手写迁移）；`ShareLink` 增列 `share_type`。
- **服务层**：`ShareLinkService` 重构为按类型创建 + 访问时同步；`StoredFileService.createShare` 改为产出 `FOLDER_SYNC` / `FILE_SET`；提交分享入口改走 `SUBMISSION_SYNC`（复用 `ArchiveTaskService.buildManifest` 的选择逻辑但记 `submission_id` 而非预签名 URL）。
- **控制器**：`ShareController.getShare` 同步逻辑前置；`AdminFilesController.share` / `ShareController.createShare` 适配新创建路径。
- **依赖**：不引入新依赖；复用现有 `StoredFileRepository`、`SubmissionRepository`、`StorageBrowserRegistry`。

### 前端
- `ShareDownload.vue`：`deleted` 行渲染 + 隐藏下载。
- 创建链路无协议变更（`fileIds` / `entries` 入参不变）。

### 兼容性
- 历史 `share_link`（仅 `data` JSON、无 `share_type`）按只读快照兜底显示，访问不被破坏；不做强制回填。
- `share_link_item` 为新增表，不影响既有功能。
