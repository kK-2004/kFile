## ADDED Requirements

### Requirement: 每个 MCP 请求都必须携带 Bearer Token
系统必须要求每个发往 `/mcp/{namespace}` 的请求都携带 `Authorization: Bearer <token>` 请求头。缺失或格式错误的请求必须以 HTTP 401 拒绝，并且不得调用任何 MCP 方法。

#### Scenario: 缺失 Authorization 请求头
- **WHEN** 任意请求到达 `/mcp/user` 或 `/mcp/admin`，但未携带 `Authorization` 请求头
- **THEN** 网关返回 HTTP 401，且不执行工具派发

#### Scenario: 非 Bearer 类型的 Authorization
- **WHEN** 请求携带 `Authorization: Basic ...` 或 `Authorization: <token>`（缺少 `Bearer ` 前缀）
- **THEN** 网关返回 HTTP 401

### Requirement: Token 存储与查找
系统必须将 Token 存储在与任何 `v2` 用户、角色或会话表完全独立的 `v3` Token 表中。原始 Token 绝不能被存储，仅持久化其 SHA-256 计算结果 `tokenHash`。Token 记录至少包含以下字段：`tokenHash`、`subject`、`roles`、`namespaces`、`enabled`、`expiresAt`、`lastUsedAt`。Token 查找必须先对客户端提交的 Token 计算 SHA-256，再按 `enabled=true` 且 `expiresAt` 为空或大于当前时间的条件检索记录。

#### Scenario: 有效、启用且未过期的 Token
- **WHEN** 请求携带的 Token 经 SHA-256 后命中一条 `enabled=true` 且 `expiresAt` 为空或大于当前时间的记录
- **THEN** 网关接受该请求，并继续执行命名空间授权检查

#### Scenario: 已停用的 Token
- **WHEN** 请求携带的 Token 命中一条 `enabled=false` 的记录
- **THEN** 网关返回 HTTP 401，原因码为 `auth_invalid`

#### Scenario: 已过期的 Token
- **WHEN** 请求携带的 Token 命中一条 `expiresAt <= 当前时间` 的记录
- **THEN** 网关返回 HTTP 401，原因码为 `auth_invalid`

#### Scenario: 未知 Token
- **WHEN** 请求携带的 Token 经 SHA-256 后未命中任何记录
- **THEN** 网关返回 HTTP 401，原因码为 `auth_invalid`

#### Scenario: 原始 Token 不被持久化
- **WHEN** Token 被创建或被使用
- **THEN** 任何日志、数据库列、审计记录均不包含原始 Token 值，仅可包含其哈希值

### Requirement: 命名空间声明强制校验
系统必须校验路径变量 `{namespace}` 属于该 Token 的 `namespaces` 集合。若不属于，网关必须返回 HTTP 403，原因码为 `namespace_denied`。

#### Scenario: user Token 试图访问 admin 命名空间
- **WHEN** 一个 `namespaces=user` 的 Token 调用 `/mcp/admin` 上的任意方法
- **THEN** 网关返回 HTTP 403，原因码为 `namespace_denied`，且不执行工具

#### Scenario: 多命名空间 Token 按请求生效
- **WHEN** 一个 `namespaces=user,admin` 的 Token 先调用 `/mcp/user` 再调用 `/mcp/admin`
- **THEN** 两次请求均通过命名空间授权

### Requirement: 工具级角色授权
系统必须校验调用方在已解析命名空间内有权调用所请求的工具。需要 `admin` 角色的工具必须拒绝 `roles` 不包含 `admin` 的调用方。

#### Scenario: user 角色调用 user 命名空间工具
- **WHEN** `roles=user` 的 Token 在 `/mcp/user` 上调用 `kfile_create_project`
- **THEN** 调用通过授权并继续派发

#### Scenario: user 角色调用仅限 admin 的工具
- **WHEN** `roles=user` 的 Token 通过多命名空间机制进入 `/mcp/admin` 后调用 `kfile_list_projects`
- **THEN** 网关返回 HTTP 403，原因码为 `tool_denied`

### Requirement: 最近使用时间追踪
系统必须在认证成功后将 `lastUsedAt` 更新为当前时间。该更新可以异步、尽力而为地执行；`lastUsedAt` 更新失败必须不影响请求本身的成功。

#### Scenario: 成功认证后更新 lastUsedAt
- **WHEN** 请求在 T 时刻成功通过认证
- **THEN** Token 记录的 `lastUsedAt` 在 60 秒内被更新为不早于 T 的时间

#### Scenario: lastUsedAt 更新失败不影响请求
- **WHEN** 写入 `lastUsedAt` 的数据库操作失败
- **THEN** 请求仍然成功，并以 WARN 级别记录该失败

### Requirement: Token 查找使用 kk-common 缓存
系统必须使用 `kk-common` 提供的 `RedisUtil.queryWithPassThrough` 缓存 Token 查找结果，缓存键格式为 `v3:token:{tokenHash}`，并启用穿透防护（对未命中也写入空值哨兵），防止未知 Token 探测压垮数据库。Token 被停用、过期或删除时，必须使缓存失效，使下一次请求拒绝即时生效。

#### Scenario: 高频相同 Token 仅一次 DB 查询
- **WHEN** 在缓存 TTL 内同一个有效 Token 连续被使用 N 次
- **THEN** 数据库 Token 表只在首次被查询，其余命中均经由 `RedisUtil.queryWithPassThrough` 命中缓存

#### Scenario: 未知 Token 启用穿透防护
- **WHEN** 同一个未知 Token 在短时间内被频繁尝试
- **THEN** 数据库不会被反复扫描，缓存中存在空值哨兵，请求继续以 401 `auth_invalid` 拒绝

#### Scenario: Token 停用即时生效
- **WHEN** 运维通过管理工具将一个 Token 置为 `enabled=false`
- **THEN** 该工具同时清除 `v3:token:{tokenHash}` 缓存条目，下一次请求被识别为停用并以 401 `auth_invalid` 拒绝
