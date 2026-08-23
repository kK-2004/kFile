## Context

分享链接当前是「创建即冻结」的快照模型：`share_link.data`（LONGTEXT JSON）里一次性塞死一串条目（`u/f/p/s/storageSource/storageKey/downloadCount`），访问时只为永久链接重新签名下载 URL，**从不**比对条目与文件/提交的当前状态。两个创建入口都这样：

1. **文件管理分享**（`AdminFilesController.share` → `StoredFileService.createShare`）：选中即快照；文件夹通过 `collectShareEntries` 递归展开成扁平条目，丢失了"这是一个文件夹"的语义，因此后续无法跟随文件夹内容变化。
2. **项目提交分享**（`ShareController.createShare`）：前端从 `archive-manifest` 拉到一串预签名 URL 当 entries，本质也是死快照；之后的新提交 / 删除提交完全无法反映。

底层事实（影响设计）：
- `stored_file` 是邻接表（`parent_id` 自引用），文件夹/文件同表，靠 `type` 区分；**硬删除**，无软删标志。
- 提交「最新有效」= `valid=true` 且为该 `submitterFingerprint` 下 `createdAt` 最大者；通过 `findVisibleByProjectOrderByCreatedAtDesc` + 内存按 fingerprint 去重得到。提交可被按字段硬删（`delete-by-field`），也会被保留策略软失效（`valid=false`）。
- 无 Flyway/Liquibase，靠 Hibernate `ddl-auto: update` 出表。
- 访问入口是公开 `permitAll` 的 `GET /api/share/{code}`，下载计数走 `findByCodeForUpdate` 悲观锁。

利益相关方：管理员（创建分享）、无登录访客（访问分享，对延迟敏感）。

## Goals / Non-Goals

**Goals:**
- 访问分享时，按分享类型实时同步条目，使「分享一个文件夹」「分享最新提交」名副其实。
- 文件夹分享：文件夹内文件**新增 / 删除**都实时反映（删除项保留置灰可追溯）。
- 多选文件分享（含整选文件夹内容、混选文件夹+文件）：被删文件 / 被删文件夹行**置灰显示「已删除」**，不跟随内容变化；新增文件不入列。
- 项目提交分享：新提交自动入列、被删 / 失效提交直接从列表移除（实时视图，不置灰）。
- 不破坏历史分享链接（仅 `data` JSON、无类型标记的老链接仍可只读访问）。

**Non-Goals:**
- 不改分享链接的过期 / 权限 / 计数语义（`expireAt`、`downloadCount`、公开访问保持不变）。
- 不引入消息队列 / 推送 / WebSocket；同步发生在「访问时」拉取，不做事件驱动主动刷新。
- 不做历史 `share_link` 数据的强制回填迁移。
- 不改前端创建分享的协议（仍发 `fileIds` / `entries`）。
- 不为 `stored_file` 引入软删列（硬删不变；「已删除」语义在 `share_link_item` 侧表达）。

## Decisions

### D1：条目从 JSON blob 拆为规范化 `share_link_item` 表
**选择**：新增 `ShareLinkItem` 实体（`share_link_item` 表），逐条记录 `kind`(FOLDER/FILE/SUBMISSION)、`refId`（`stored_file_id` 或 `submission_id`）、`relativePath`、`storageSource`/`storageKey`、`size`、`filename`、`deleted`(bool)、`createdAt`/`updatedAt`。`ShareLink` 增列 `share_type`。

**理由**：当前 JSON blob 无法高效表达"这条引用的是哪个文件夹 / 哪条提交"以及"这条是否已被软删置灰"，而这两点正是实时同步与置灰渲染的全部基础。规范化后，同步 = 对 `share_link_item` 做 upsert / 软删，渲染 = 查 `share_link_item`，计数仍挂在 `share_link_item.downloadCount`。

**备选（否决）**：继续用 JSON blob + 在 JSON 内补 `refId`/`deleted`/`type` 字段。否决理由：访问时 diff 需要频繁读写整个大 JSON、悲观锁粒度变粗、计数与置灰状态混在同一 LONGTEXT 难以原子更新；且 `ddl-auto: update` 下加表成本极低，规范化收益明确。

### D2：`share_type` 三态决定同步策略
**选择**：
- `FOLDER_SYNC`：根是文件夹。访问时实时重算该文件夹当前全部文件集，与已存 `item` diff：新增文件 → 插入 item；消失文件 → 将对应 item `deleted=true`（保留置灰，不物理删，避免下载计数丢失与历史可追溯）。根文件夹本身被删 → 根行置灰。
- `FILE_SET`：多选文件（含整选文件夹内容、混选）。**创建时即把文件夹展开成扁平文件条目**并锁定，之后不跟随任何文件夹内容变化；仅当某条引用的 `stored_file` 不存在时 → `deleted=true`。若选中的是文件夹节点且该文件夹被删 → 该文件夹行（kind=FOLDER）置灰。
- `SUBMISSION_SYNC`：记 `projectId` + 可选 `fieldKey`/`fieldValue`。访问时重算"当前每提交人最新有效提交"集合（复用 `findVisibleByProjectOrderByCreatedAtDesc` + 按 `submitterFingerprint` 去重），**整表替换**：不再匹配的 item 物理删除、新匹配的 item 插入。**不置灰**（用户明确要求提交分享是干净实时视图）。

**类型判定（创建时）**：
- 文件管理入口 `fileIds`：若**恰好一个**且为文件夹 → `FOLDER_SYNC`；否则（>1 个、或单文件、或含文件夹的多选）→ `FILE_SET`（多选里的文件夹在创建时展开）。
- 提交分享入口（`projectId != null`）→ `SUBMISSION_SYNC`。

**理由**：三类分享的用户心智与可追溯诉求不同——文件夹要"活的"、多选文件要"冻结但标失效"、提交要"干净的最新视图"。一个 `share_type` + 三套同步器把差异收敛到一处。

**备选（否决）**：用单个 `live` 布尔。否决理由：无法区分"FOLDER_SYNC 的删文件需置灰"与"SUBMISSION_SYNC 的删提交需移除"，两者删除处理截然不同，必须三态。

### D3：同步发生在「公开访问时」，同步 + 渲染同事务
**选择**：`ShareController.getShare` 在事务内：①按 `share_type` 调对应 `SyncStrategy.sync(link)` 落库更新 `share_link_item`；②查最终 `share_link_item` 列表；③为需要下载的条目按 `storageSource`/`storageKey` 重新签名短时 URL（沿用现有 600s 逻辑）；④剥离内部字段返回。

**理由**：访客每次访问天然是"拉取最新"的时机，无需后台轮询或事件总线；事务保证同步与渲染一致。三类同步都只读各自源表（`stored_file` / `submissions`）+ 写 `share_link_item`，开销可控。

**并发**：同一链接被并发访问时，多个事务可能同时 upsert。采用 `share_link_item` 上 `(share_link_id, ref_kind, ref_id)` 唯一约束 + 同步逻辑幂等（按 `refId` diff，重复插入被唯一约束挡住，删除项 idempotent 置 `deleted=true`），避免计数错乱。

**备选（否决）**：访问时只读不写、实时计算 diff 仅用于本次响应。否决理由：下载计数（`downloadCount`）与置灰状态需要持久化；访客每次重算也无谓放大读放大。

### D4：`FILE_SET` 的"已删除"判定依据源记录是否存在
**选择**：`FILE_SET` 同步器遍历 items，按 `refId` 查 `stored_file`；查不到 → `deleted=true`（不物理删 item，保留行用于置灰展示与历史计数）。**不尝试**判断"文件被移到别的文件夹"（硬删即视为删除）。

**理由**：`stored_file` 是硬删除，查不到 = 已删，判定简单可靠；保留 item 行才能实现用户要求的"整行置灰显示已删除"。

### D5：提交分享记 `submission_id` 而非预签名 URL
**选择**：`SUBMISSION_SYNC` 创建/同步时，item 存 `submission_id`（而非像旧 `archive-manifest` 那样烘焙预签名 URL）。访问时按 `submission_id` 取提交的 `fileUrls`，重新签名。item 的 `filename`/`size`/`submitterInfo` 等展示字段在同步时填入。

**理由**：预签名 URL 有时效，烘焙进快照正是当前"过期链接下载失效"的隐患根因；记 `submission_id` 让 URL 在访问时现签，与永久链接语义一致。

## Risks / Trade-offs

- **[访问延迟增加]** 每次访问多一次"读源表 + diff + 写 item"。→ 缓解：三类同步查询都走已有索引（`stored_file.parent_id`、`submissions` 的 `valid`+`project` 复合索引）；单链接条目量通常 ≤ 数百，diff 在内存完成；写操作仅在有变化时发生（先比对再写）。监控 `getShare` p99。
- **[并发同步竞态]** 同一链接并发访问可能并发 upsert。→ 缓解：`(share_link_id, ref_kind, ref_id)` 唯一约束 + 幂等同步逻辑；下载计数仍用现有 `findByCodeForUpdate` 悲观锁路径，与同步路径分离。
- **[历史链接兼容]** 老 `share_link` 无 `share_type`、无 item。→ 缓解：`getShare` 检测到 `share_type == null` 时走**只读快照兜底**（直接解析 `data` JSON 返回，不同步、不置灰），保证不被破坏；不做强制回填。
- **[FOLDER_SYNC 根文件夹被删后的语义]** 根文件夹消失时，用户期望根行置灰 + 已删子文件仍置灰保留。→ 缓解：同步器先判根 `stored_file` 是否存在，不存在则把根 item 置灰并保留所有子 item 现状（不新增不重算）。
- **[SUBMISSION_SYNC 整表替换丢失下载计数]** 物理删除不匹配 item 会丢其 `downloadCount`。→ 缓解：提交分享的计数本就语义模糊（提交随时更替），接受丢失；链接级 `share_link.downloadCount` 仍累计保留。如后续需要可改"软删+保留计数"。
- **[多选文件中"移动文件"误判为删除]** `stored_file` 移动后 `refId` 仍存在，不会误删；仅硬删才置灰——符合预期。已确认非问题。
