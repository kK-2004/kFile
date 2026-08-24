## ADDED Requirements

### Requirement: 分享条目规范化存储

系统 SHALL 将分享链接的条目存储于独立的 `share_link_item` 表中，每条记录独立包含 `kind`（`FOLDER` / `FILE` / `SUBMISSION`）、来源引用标识（`stored_file_id` 或 `submission_id`）、`relativePath`、`storageSource`、`storageKey`、`size`、`filename`、`deleted`（软删置灰标志）与 `downloadCount`。系统 SHALL 在 `share_link` 上维护 `share_type` 列，取值为 `FOLDER_SYNC`、`FILE_SET`、`SUBMISSION_SYNC` 三者之一。`share_link_item` SHALL 在 `(share_link_id, kind, ref_id)` 上具有唯一约束。

#### Scenario: 新建分享链接生成规范化条目
- **WHEN** 系统创建任意类型的新分享链接
- **THEN** 系统 SHALL 在 `share_link` 写入 `share_type`，并为每个被分享的文件 / 文件夹 / 提交在 `share_link_item` 写入一条规范化记录，记录其 `kind` 与来源引用标识

#### Scenario: 唯一约束防止重复条目
- **WHEN** 同一分享链接对同一来源引用（相同 `kind` 与 `ref_id`）尝试插入第二条条目
- **THEN** 系统 SHALL 通过 `(share_link_id, kind, ref_id)` 唯一约束拒绝重复插入

---

### Requirement: 分享类型由选中结构判定

系统 SHALL 在创建分享链接时，依据被分享内容的结构自动判定 `share_type`：当文件管理入口传入的选中集合**恰好包含一个文件夹节点**时，SHALL 判定为 `FOLDER_SYNC`；当选中集合为多选（多于一个节点、单个文件、或同时包含文件夹与文件）时，SHALL 判定为 `FILE_SET`，并在创建时将其中被选中的文件夹展开为扁平的文件条目；当分享来源为项目提交（`projectId` 非空）时，SHALL 判定为 `SUBMISSION_SYNC`。

#### Scenario: 选中单个文件夹判定为 FOLDER_SYNC
- **WHEN** 管理员在文件管理页选中恰好一个文件夹并创建分享
- **THEN** 系统 SHALL 创建 `share_type = FOLDER_SYNC` 的链接，并记录该文件夹的 `stored_file_id` 作为根引用

#### Scenario: 多选文件（含整选文件夹内容）判定为 FILE_SET
- **WHEN** 管理员选中多个文件、或选中某文件夹的全部内容、或同时选中文件夹与文件并创建分享
- **THEN** 系统 SHALL 创建 `share_type = FILE_SET` 的链接，在创建时将被选文件夹展开为扁平文件条目，且后续不跟随任何文件夹内容的变化

#### Scenario: 项目提交分享判定为 SUBMISSION_SYNC
- **WHEN** 管理员从项目提交记录创建分享链接（`projectId` 非空）
- **THEN** 系统 SHALL 创建 `share_type = SUBMISSION_SYNC` 的链接，记录 `projectId` 与可选的提交人字段过滤条件

---

### Requirement: 文件夹分享访问时实时同步新增与删除

对于 `share_type = FOLDER_SYNC` 的分享链接，系统 SHALL 在每次公开访问（`GET /api/share/{code}`）时，实时重新计算根文件夹当前的全部文件集合，并与已存 `share_link_item` 进行差异比对：根文件夹下**新增**的文件 SHALL 被插入为新的条目；已**消失**（被删除）的文件 SHALL 将其对应条目的 `deleted` 置为 `true`（保留条目用于置灰展示与下载计数，不物理删除）。系统 SHALL NOT 将文件夹内文件的新增反映到 `FILE_SET` 类型的链接。

#### Scenario: 文件夹新增文件实时出现在分享中
- **WHEN** 一个 `FOLDER_SYNC` 链接被访问，且其根文件夹自上次同步后新增了一个文件
- **THEN** 系统 SHALL 在返回的条目列表中包含该新增文件，并在 `share_link_item` 持久化对应记录

#### Scenario: 文件夹删除文件后分享中该行置灰
- **WHEN** 一个 `FOLDER_SYNC` 链接被访问，且其根文件夹下某文件已被删除
- **THEN** 系统 SHALL 将该文件对应条目的 `deleted` 置为 `true`，且返回的条目仍保留该文件记录（带 `deleted` 标志）以便前端置灰展示

#### Scenario: 根文件夹本身被删除
- **WHEN** 一个 `FOLDER_SYNC` 链接被访问，且其根文件夹节点已不存在
- **THEN** 系统 SHALL 将根条目的 `deleted` 置为 `true`，SHALL NOT 抛出错误或返回 404，并 SHALL 保留既有子条目原状返回

---

### Requirement: 多选文件分享仅在文件被删除时置灰

对于 `share_type = FILE_SET` 的分享链接，系统 SHALL 在每次公开访问时，逐条检测各条目引用的 `stored_file` 是否仍然存在：当被引用的文件**已不存在**时，SHALL 将该条目的 `deleted` 置为 `true`；当选中的节点是文件夹且该文件夹已被删除时，SHALL 将对应文件夹条目的 `deleted` 置为 `true`。系统 SHALL NOT 因文件夹内容的新增或删除而修改 `FILE_SET` 链接的条目集合（新增文件不进列表）。被标记 `deleted = true` 的条目 SHALL 在返回结果中保留，并由前端渲染为整行置灰且显示「已删除」。

#### Scenario: 文件被删除后对应条目置灰
- **WHEN** 一个 `FILE_SET` 链接被访问，且其中某条目引用的文件已被删除
- **THEN** 系统 SHALL 将该条目 `deleted` 置为 `true`，并在返回结果中保留该条目

#### Scenario: 多选中的文件夹被删除后该行置灰
- **WHEN** 一个 `FILE_SET` 链接被访问，且其中一条 `kind = FOLDER` 的条目引用的文件夹已被删除
- **THEN** 系统 SHALL 将该文件夹条目 `deleted` 置为 `true`，并在返回结果中保留该条目

#### Scenario: 文件夹内容变化不影响 FILE_SET
- **WHEN** 一个 `FILE_SET` 链接被访问，且其创建时展开的某文件夹后续新增或删除了文件
- **THEN** 系统 SHALL NOT 在条目集合中新增或移除任何条目（多选语义为冻结快照）

---

### Requirement: 项目提交分享访问时实时反映最新有效提交

对于 `share_type = SUBMISSION_SYNC` 的分享链接，系统 SHALL 在每次公开访问时，依据记录的 `projectId` 与可选过滤条件，实时重算「当前每个提交人（`submitterFingerprint`）的最新有效提交」集合（即 `valid = true` 或为空，且按 `createdAt` 降序后每个 fingerprint 取首条）。系统 SHALL 用重算结果**整体替换**该链接的条目集合：新提交 SHALL 被加入；不再匹配（被删除、被保留策略失效、或不再满足过滤条件）的提交 SHALL 从条目集合中**移除**（不置灰、不保留）。`SUBMISSION_SYNC` 的条目 SHALL 记录 `submission_id` 而非预签名 URL，下载链接 SHALL 在访问时按 `submission_id` 现取 `fileUrls` 重新签名生成。

#### Scenario: 新提交自动加入分享
- **WHEN** 一个 `SUBMISSION_SYNC` 链接被访问，且该项目下某提交人产生了新的有效提交
- **THEN** 系统 SHALL 在返回的条目列表中包含该提交人的最新提交（而非其旧提交），并在 `share_link_item` 持久化对应记录

#### Scenario: 同一提交人重提交后旧提交被替换
- **WHEN** 一个 `SUBMISSION_SYNC` 链接被访问，且某提交人此前在列表中的提交已被其更新的提交取代
- **THEN** 系统 SHALL 在返回的条目列表中以该提交人的最新提交替换旧提交

#### Scenario: 提交被删除后从列表移除（不置灰）
- **WHEN** 一个 `SUBMISSION_SYNC` 链接被访问，且此前在列表中的某提交已被删除或失效（`valid = false`）
- **THEN** 系统 SHALL 从返回的条目列表中移除该提交，SHALL NOT 将其置为 `deleted`，SHALL NOT 在前端置灰展示

#### Scenario: 提交分享下载链接访问时现签
- **WHEN** 一个 `SUBMISSION_SYNC` 链接被访问
- **THEN** 系统 SHALL 依据条目的 `submission_id` 读取提交的 `fileUrls` 并在响应时生成短时有效的下载链接，SHALL NOT 依赖创建时烘焙的预签名 URL

---

### Requirement: 公开访问先同步后渲染

系统 SHALL 在公开访问 `GET /api/share/{code}` 时，对非历史遗留链接，于同一事务内先按其 `share_type` 执行同步（更新 `share_link_item`），再查询最终的 `share_link_item` 列表并返回。返回结果 SHALL 为每个有效（`deleted = false` 且需要下载）的条目附带按 `storageSource` / `storageKey` 现签的短时下载链接；内部字段（`storageSource`、`storageKey`、来源 `ref_id`）SHALL 在返回前剥离。同步逻辑 SHALL 幂等，重复访问相同状态不产生重复条目或错误。

#### Scenario: 访问触发同步并返回最终列表
- **WHEN** 访客访问一个非历史遗留的分享链接
- **THEN** 系统 SHALL 先按 `share_type` 同步 `share_link_item`，再返回同步后的最终条目列表，且条目的下载链接为现签的短时链接

#### Scenario: 重复访问幂等
- **WHEN** 同一分享链接在源状态未变化时被连续访问多次
- **THEN** 系统 SHALL NOT 产生重复条目、SHALL NOT 抛出错误，且返回的条目集合保持一致

---

### Requirement: 历史遗留分享链接只读兜底

对于在本次变更前创建、`share_type` 为空的分享链接，系统 SHALL 在公开访问时按只读快照兜底处理：直接解析其 `data` JSON 返回条目列表，SHALL NOT 执行任何同步、SHALL NOT 写入 `share_link_item`、SHALL NOT 对其条目做置灰处理。系统 SHALL NOT 强制回填历史链接的 `share_type` 与条目。

#### Scenario: 历史 JSON-only 链接仍可访问
- **WHEN** 访客访问一个 `share_type` 为空的历史分享链接
- **THEN** 系统 SHALL 直接解析其 `data` JSON 返回条目，SHALL NOT 执行同步或写入 `share_link_item`，访问正常成功

---

### Requirement: 前端按删除标志渲染置灰行

前端分享下载页 SHALL 识别条目的 `deleted` 标志：被标记 `deleted = true` 的条目 SHALL 被渲染为整行置灰、显示「已删除」文案，并隐藏其下载按钮。未被标记的条目 SHALL 正常渲染并可下载。文件夹分享的树形视图 SHALL 随实时同步结果动态增减节点。

#### Scenario: 已删除条目整行置灰且不可下载
- **WHEN** 分享下载页渲染一个 `deleted = true` 的条目
- **THEN** 该行 SHALL 整行置灰、显示「已删除」文案，且 SHALL NOT 展示下载按钮

#### Scenario: 正常条目可下载
- **WHEN** 分享下载页渲染一个 `deleted = false` 的条目
- **THEN** 该行 SHALL 正常展示并允许下载
