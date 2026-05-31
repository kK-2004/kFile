## ADDED Requirements

### Requirement: 按命名空间提供 MCP Streamable HTTP 入口
系统必须在 `kfile-v3-gateway` 上以 Streamable HTTP 方式暴露 MCP Server，路径为 `/mcp/{namespace}`，其中 `namespace` 为路径变量。网关必须在该入口处终止 MCP 协议，且必须是 `v3` 中唯一对外提供 MCP 协议的进程。

#### Scenario: 在 user 命名空间执行 initialize
- **WHEN** MCP 客户端使用 `namespaces` 包含 `user` 的有效 Bearer Token 向 `POST /mcp/user` 发起 `initialize` 请求
- **THEN** 网关返回 MCP `initialize` 响应，并声明 `user` 命名空间下的能力与工具列表

#### Scenario: 在 admin 命名空间执行 initialize
- **WHEN** MCP 客户端使用 `namespaces` 包含 `admin` 的有效 Bearer Token 向 `POST /mcp/admin` 发起 `initialize` 请求
- **THEN** 网关返回 MCP `initialize` 响应，并声明 `admin` 命名空间下的能力与工具列表

#### Scenario: 未知命名空间
- **WHEN** MCP 客户端向 `/mcp/<未知>`（例如 `/mcp/staff`）发送任意 MCP 请求
- **THEN** 网关返回 HTTP 404，且不调用任何工具

### Requirement: 按命名空间隔离的工具注册表
系统必须为每个 MCP 命名空间维护独立的工具注册表。`user` 命名空间的工具集必须严格等于：`kfile_create_project`、`kfile_validate_submitter`、`kfile_create_upload_urls`、`kfile_complete_upload`。`admin` 命名空间的工具集必须严格等于：`kfile_create_project`、`kfile_get_project`、`kfile_list_projects`。同名工具可同时存在于多个命名空间，网关必须按 `(namespace, tool)` 解析具体实现。

#### Scenario: 在 user 命名空间执行 tools/list
- **WHEN** 已授权客户端在 `/mcp/user` 上调用 `tools/list`
- **THEN** 响应中恰好列出 `kfile_create_project`、`kfile_validate_submitter`、`kfile_create_upload_urls`、`kfile_complete_upload`

#### Scenario: 在 admin 命名空间执行 tools/list
- **WHEN** 已授权客户端在 `/mcp/admin` 上调用 `tools/list`
- **THEN** 响应中恰好列出 `kfile_create_project`、`kfile_get_project`、`kfile_list_projects`

#### Scenario: 同名工具按命名空间路由到不同实现
- **WHEN** 在 `/mcp/user` 与 `/mcp/admin` 上以相同参数调用 `kfile_create_project`
- **THEN** 网关分别派发到各自命名空间的实现（user 命名空间强制以调用方为 owner 并执行用户配额；admin 命名空间允许指定 owner）

### Requirement: 通过 Feign 派发到 kfile-service
系统必须通过 `kfile-api` 中定义的 OpenFeign 客户端，将每个 MCP 工具的执行转发到 `kfile-service` 的 `/internal/rpc/**` Facade。工具内除 MCP 参数到 DTO 的映射、错误转换以及命名空间内的策略校验外，必须不包含其他业务逻辑；工具必须不直接调用 `kfile-service` 的对外公开 HTTP 端点。Feign 调用必须按 `kk-common` 的 `TransDTO<T>` / `PageResponse<T>` 反序列化，并把 `TransDTO.fail(...)` 还原为 `BusinessException` 后再映射为 MCP 错误。

#### Scenario: 工具调用透传到 /internal/rpc
- **WHEN** 已授权客户端在 `/mcp/user` 上对 `kfile_create_project` 触发 `tools/call`
- **THEN** 网关向 `kfile-v3-service` 发起 Feign 请求 `POST /internal/rpc/projects`，并基于已认证的请求填充 `X-KFile-Actor`、`X-KFile-Roles`、`X-KFile-Client-IP`、`X-KFile-User-Agent` 与 `X-KFile-Request-Id`

#### Scenario: 服务错误映射为 MCP 错误
- **WHEN** 底层 `/internal/rpc/**` 调用返回 `TransDTO.fail(errorCode, message)`
- **THEN** 网关将该响应还原成 `BusinessException`，并以携带原始 `errorCode` 与 `message` 的 MCP `tools/call` 错误响应返回，而不是统一的 500

### Requirement: MCP 请求可观测性
系统必须为每次 MCP `tools/call` 至少记录以下字段：`request_id`、`namespace`、`tool`、`subject`、`latency_ms`、`result_status`。鉴权或授权失败必须以 WARN 级别记录，并附带原因码（取值范围：`auth_invalid`、`namespace_denied`、`tool_denied`、`tool_error`）。

#### Scenario: 成功调用被记录
- **WHEN** 一次已授权的 `tools/call` 成功完成
- **THEN** 输出一条 INFO 日志，包含 `namespace`、`tool`、`subject`、`latency_ms`，且 `result_status=ok`

#### Scenario: 授权失败带原因记录
- **WHEN** 一个 `namespaces` 不包含 `admin` 的 Token 在 `/mcp/admin` 上调用工具
- **THEN** 输出一条 WARN 日志，`reason=namespace_denied`，且工具未被执行
