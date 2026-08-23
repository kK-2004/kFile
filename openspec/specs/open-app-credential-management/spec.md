# open-app-credential-management Specification

## Purpose
SUPER 管理后台的开放应用凭证管理：appName/appToken 创建（一次性明文、哈希落库）、轮换、启停、级联删除（连带清理应用文件）、SDK 上传根路径（rootPath）配置与迁移、lastUsedAt 审计，以及开放 API 默认数据源设置。
## Requirements
### Requirement: 创建开放应用（SUPER）
系统 SHALL 允许 `SUPER` 角色管理员创建开放应用：提交 `appName`（全局唯一）、可选 `description` 与可选 `rootPath`（见「SDK 上传根路径配置」）；`appToken` 由系统生成（`kapp_` 前缀 + ≥32 字节随机串），仅在创建响应中明文返回一次，落库只存 SHA-256 哈希。

#### Scenario: 创建成功并一次性展示 token
- **WHEN** SUPER 用户请求 `POST /api/admin/open-apps {appName: "crm", description: "客户系统"}`
- **THEN** 系统创建应用记录，响应包含 appName/description/enabled=true 与明文 `appToken`（仅此一次）

#### Scenario: appName 重复被拒绝
- **WHEN** SUPER 用户创建的应用名与已有应用相同
- **THEN** 系统返回 `409 Conflict`

#### Scenario: 非 SUPER 或未登录被拒绝
- **WHEN** `ADMIN` 角色用户或未认证用户调用 `/api/admin/open-apps/**`
- **THEN** 系统分别返回 `403 Forbidden` / `401 Unauthorized`

### Requirement: token 轮换
系统 SHALL 支持对开放应用轮换 appToken：轮换响应一次性返回新明文 token，`tokenHash` 被覆盖，旧 token 立即失效。

#### Scenario: 轮换后旧 token 失效
- **WHEN** SUPER 用户对应用执行轮换，随后分别用旧 token 与新 token 调用任意 `/api/open/**` 接口
- **THEN** 旧 token 返回 `401 Unauthorized`，新 token 返回 `200`

#### Scenario: 明文不可再查
- **WHEN** SUPER 用户查询应用列表或详情
- **THEN** 响应不包含 token 明文，也不包含 tokenHash

### Requirement: 启用与禁用
系统 SHALL 支持启用/禁用开放应用；禁用后其 token 的所有开放 API 调用立即失败，重新启用后恢复。

#### Scenario: 禁用立即生效
- **WHEN** SUPER 用户禁用应用后，该应用用原 token 调用 `/api/open/**`
- **THEN** 系统返回 `401 Unauthorized`
- **WHEN** 重新启用后再次调用
- **THEN** 系统返回 `200`

### Requirement: SDK 上传根路径配置与文件迁移
系统 SHALL 支持为每个开放应用配置 SDK 上传文件的虚拟根路径 `rootPath`：创建时可选填、创建后 SUPER 可修改；路径斜杠分隔、逐段安全校验（MUST 拒绝 `..`、空段等穿越片段）；未配置时默认 `开放应用/<appName>`。修改 rootPath 时系统 SHALL **同步迁移**该应用全部已登记文件至新根路径（对象搬运 + 虚拟树节点更新 + 旧对象删除），迁移完成后请求才返回迁移统计；任一文件迁移失败 MUST 整体失败并保持原状（rootPath 与文件位置不变）。

#### Scenario: 创建时配置根路径
- **WHEN** SUPER 用户创建应用时填写 `rootPath: "crm/2026"`
- **THEN** 该应用后续上传的文件登记在虚拟目录 `crm/2026` 之下（请求可选 `path` 参数再追加子目录）

#### Scenario: 修改根路径触发同步迁移
- **WHEN** SUPER 用户将应用根路径从 `crm/2026` 改为 `crm/2027`，该应用名下已有 10 个已上传文件
- **THEN** 请求阻塞至 10 个文件全部迁移完成才返回：对象复制到新路径下的新 key、旧对象删除、FILE 节点移入新目录链、搬空的旧目录节点清理，响应含迁移统计（如 `moved=10`）

#### Scenario: 迁移失败保持原状
- **WHEN** 迁移过程中任一对象复制失败
- **THEN** 系统清理已复制的新对象、不更新数据库，返回 `500`；应用 rootPath 与全部文件保持迁移前状态

#### Scenario: 活跃分片上传跳过迁移
- **WHEN** 迁移时该应用存在进行中的分片上传（未完成的 multipart 记录）
- **THEN** 该文件跳过迁移并计入 `skipped`（响应列明），其余文件正常迁移；分片完成后可再次修改 rootPath 迁移

#### Scenario: 非法根路径被拒绝
- **WHEN** 配置的 `rootPath` 含 `..`、空段或非法字符
- **THEN** 系统返回 `400 Bad Request`

#### Scenario: 未配置时使用默认根路径
- **WHEN** 应用未配置 rootPath 即上传文件
- **THEN** 文件登记在默认目录 `开放应用/<appName>` 下

### Requirement: lastUsedAt 审计
系统 SHALL 记录每个开放应用的最近使用时间（开放 API 鉴权成功时更新，更新间隔节流至 ≥60 秒一次），并 SUPER 可见。

#### Scenario: 调用后可审计
- **WHEN** 应用成功调用开放 API 后，SUPER 用户查看应用列表
- **THEN** 该应用的 `lastUsedAt` 反映最近一次成功调用时间

### Requirement: 开放 API 默认数据源设置
系统设置 SHALL 提供「开放 API 默认数据源」配置项（configs key `OPEN_API_DEFAULT_SOURCE`）：候选为当前已启用数据源，保存时校验；未配置时默认 `oss`。

#### Scenario: 保存合法数据源
- **WHEN** SUPER 用户在系统设置中将默认数据源设为已启用的 `minio`
- **THEN** 保存成功，后续未传 `source` 的开放 API 请求使用 minio

#### Scenario: 保存未启用的数据源被拒绝
- **WHEN** SUPER 用户将默认数据源设为当前未启用的 sourceId
- **THEN** 系统返回 `400 Bad Request`

### Requirement: 删除应用（连带文件清理）
系统 SHALL 支持删除开放应用：`DELETE /api/admin/open-apps/{id}`（SUPER）级联清理该应用名下全部文件——删除对象存储对象、`stored_file` FILE 节点与分片上传残留记录，随后删除应用记录（其 token 立即失效）。搬空后的应用根路径目录链 SHALL 被清理；仍含其他文件（如多应用共享目录）的目录 MUST 保留。删除 MUST 经二次确认，确认前展示该应用文件数与总大小（`GET /{id}/stats`）；删除结果返回 `{deletedFiles, failedObjects}`，对象删除失败不阻断（计入 failedObjects 仅告警）。

#### Scenario: 删除应用连带清理全部文件
- **WHEN** SUPER 删除应用 crm（名下 12 个已上传文件）并确认
- **THEN** 12 个对象与对应节点、分片残留记录、应用记录全部删除，token 立即失效，响应 `{deletedFiles: 12, failedObjects: 0}`

#### Scenario: 共享目录保留
- **WHEN** 两个应用配置了相同 rootPath，删除其中之一
- **THEN** 被删应用的文件清理；目录因仍含另一应用的文件而保留

#### Scenario: 删除前统计与强确认
- **WHEN** SUPER 点击删除
- **THEN** 系统先展示该应用文件数与总大小（`GET /api/admin/open-apps/{id}/stats`），确认后才执行删除

#### Scenario: 对象删除失败不阻断
- **WHEN** 某对象删除时对象存储报错
- **THEN** 节点与记录仍删除，该项计入 `failedObjects`，响应正常返回

