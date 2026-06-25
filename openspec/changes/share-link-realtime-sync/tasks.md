## 1. 数据模型

- [x] 1.1 新增 `ShareLinkItem` 实体（`src/main/java/com/kk/share/entity/ShareLinkItem.java`）：字段 `id`、`shareLink`(ManyToOne)、`kind`(FOLDER/FILE/SUBMISSION)、`refId`(Long，存 `stored_file_id` 或 `submission_id`)、`relativePath`、`filename`、`storageSource`、`storageKey`、`size`、`deleted`(boolean, default false)、`downloadCount`(int, default 0)、`submitterFingerprint`/`submitterInfo`/`submitCount`（提交分享用，可空）、`createdAt`、`updatedAt`；表名 `share_link_item`。
- [x] 1.2 在 `ShareLink` 实体新增 `shareType` 列（枚举 `FOLDER_SYNC`/`FILE_SET`/`SUBMISSION_SYNC`，可空用于历史兼容）；为 `FOLDER_SYNC`/`SUBMISSION_SYNC` 预留 `rootStoredFileId`、`projectId`、`fieldKey`、`fieldValue` 等同步所需元数据列（可复用现有 `projectId`，其余新增）。
- [x] 1.3 新增 `ShareLinkItemRepository`：`findByShareLink(ShareLink)`、`findByShareLinkIdOrderByRelativePath(...)`、`findByShareLinkIdAndKindAndRefId(...)`、`deleteByShareLinkIdAndRefIdIn(...)`、`existsByShareLinkIdAndKindAndRefId(...)` 等。
- [x] 1.4 在 `share_link_item` 上声明 `(share_link_id, kind, ref_id)` 唯一约束（`@Table(uniqueConstraints=...)`），依赖 `ddl-auto: update` 出表。
- [x] 1.5 用 IntelliJ `build_project` 编译，确认表/实体映射无误。

## 2. 创建链路重构（按类型分流）

- [x] 2.1 在 `StoredFileService.createShare` 改写：当 `nodeIds` 恰好 1 个且为文件夹 → 调 `shareLinkService.createFolderSync(rootNodeId, expireSeconds, filename)`；否则 → 调 `createFileSet(nodeIds, ...)`（多选里的文件夹在创建时展开为扁平文件条目）。
- [x] 2.2 在 `ShareLinkService` 新增 `createFolderSync`：写 `ShareLink(shareType=FOLDER_SYNC, rootStoredFileId)`，初始化时对根文件夹做一次 `collectShareEntries` 生成 `ShareLinkItem`（kind=FILE，`relativePath` 含文件夹前缀）。
- [x] 2.3 新增 `createFileSet`：写 `ShareLink(shareType=FILE_SET)`，逐 `nodeId` 生成 item——文件→kind=FILE；文件夹→展开成扁平 FILE item（创建时锁定，后续不跟随）。被选文件夹节点本身若需作为可置灰行，另存一条 kind=FOLDER item 记其 `refId`。
- [x] 2.4 重构项目提交分享入口（`ShareController.createShare` / 对应 service）：当 `projectId != null` 时 → 调 `shareLinkService.createSubmissionSync(projectId, fieldKey, fieldValue, expireSeconds, filename)`；写 `ShareLink(shareType=SUBMISSION_SYNC, projectId, fieldKey, fieldValue)`；按现有 `findVisibleByProjectOrderByCreatedAtDesc` + fingerprint 去重得到最新有效提交，逐条写 item（kind=SUBMISSION，`refId=submission.id`，存 `submitterFingerprint`/`submitterInfo`/`submitCount`/`filename`/`size`），**不烘焙预签名 URL**。
- [x] 2.5 保留 `ShareLinkService.create(...)` 旧签名仅给历史兜底/测试用，或重构所有调用点后删除。
- [x] 2.6 编译并冒烟：分别用单文件夹、多选文件、项目提交创建分享，检查 DB 中 `share_type` 与 `share_link_item` 落库正确。

## 3. 访问时同步策略

- [x] 3.1 定义 `ShareSyncStrategy` 接口：`void sync(ShareLink link)`；提供 `syncAndLoad` 统一入口（同步后查 item 列表）。
- [x] 3.2 实现 `FolderSyncStrategy`：按 `rootStoredFileId` 重算当前文件夹全部文件（递归 `findByParentId`）；先判根是否存在（不存在→根行 `deleted=true` 且不重算子项）；存在则与已存 FILE item 按 `refId` diff：新增→插 item，消失→`deleted=true`。幂等。
- [x] 3.3 实现 `FileSetSyncStrategy`：遍历 item，按 `refId` 查 `stored_file`，查不到→`deleted=true`（不物理删 item）；不做新增、不跟随文件夹内容。
- [x] 3.4 实现 `SubmissionSyncStrategy`：按 `projectId`/`fieldKey`/`fieldValue` 复用 `ArchiveTaskService.buildManifest` 的选择逻辑（visible + fingerprint 去重 + 可选字段过滤）得到当前最新有效提交集合；按 `submission_id` diff：当前集合中的 id 但 item 无→插 item；item 有但当前集合无→物理删除该 item（不置灰）。幂等。
- [x] 3.5 在 `ShareLinkService` 按 `shareType` 分发到对应 strategy；`shareType==null` 走只读兜底（不调任何 strategy）。

## 4. 公开访问接口改造

- [x] 4.1 重构 `ShareController.getShare`：事务内 `shareType==null`→解析 `data` JSON 兜底返回；否则→`shareLinkService.syncAndLoad(link)` 同步并取 item 列表。
- [x] 4.2 对每个 `deleted=false` 的 item，按 `storageSource`/`storageKey`（文件夹/多选文件）或按 `submission_id`→`fileUrls`（提交分享）现签 600s 下载 URL；剥离 `storageSource`/`storageKey`/`refId` 等内部字段后返回。
- [x] 4.3 返回结构包含条目级 `deleted` 标志、`filename`/`relativePath`/`size`/`downloadCount`，以及链接级 `expireAt`/`downloadCount`，保持对前端的最小破坏。
- [x] 4.4 复核 `recordDownload`（`/api/share/{code}/download`）：改为按 item id 增 `share_link_item.downloadCount` 并累计 `share_link.downloadCount`；处理 `deleted=true` 条目（不应再计 / 或前端已隐藏下载按钮）。
- [x] 4.5 编译并冒烟 `getShare`：对三类链接各访问一次，确认同步、现签 URL、`deleted` 标志正确。

## 5. 前端渲染

- [x] 5.1 `ShareDownload.vue` 列表/树形渲染识别 `deleted`：整行置灰、显示「已删除」、隐藏下载按钮（单文件下载与打包下载均排除已删条目）。
- [x] 5.2 文件夹分享的树形视图随实时同步结果动态增减节点（依赖访问返回的最新条目即可，无需前端额外拉取）。
- [x] 5.3 确认打包下载（JSZip）跳过 `deleted=true` 条目，避免 404。
- [ ] 5.4 本地验证三类分享的前端表现：文件夹增删反映、多选文件删除置灰、提交分享实时增减且删除不置灰。

## 6. 兼容与验证

- [x] 6.1 历史 JSON-only 链接（`share_type=null`）只读兜底：访问返回 `data` 解析结果，不写 `share_link_item`、不报错。
- [ ] 6.2 并发幂等验证：对同一链接并发 `getShare`，确认无重复 item、无计数错乱（依赖唯一约束 + 幂等 diff）。
- [x] 6.3 用 IntelliJ `build_project` 全量编译通过；运行相关单元/集成测试（如有）。
- [ ] 6.4 端到端手测三类分享的创建 → 变更源（加/删文件、新提交/删提交）→ 再访问，核对 spec 中每个 Scenario。
- [x] 6.5 更新 `AdminShares.vue` 管理页（若展示条目计数/下载计数）以兼容新 item 表数据来源。
