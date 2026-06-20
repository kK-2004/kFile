## MODIFIED Requirements

### Requirement: 令牌鉴权与安全上下文注入

系统 SHALL 通过单一 filter 层（`McpTokenAuthFilter`）对携带 `Authorization: Bearer <token>` 的 MCP 请求（端点 `/mcp`）进行令牌鉴权：对请求中的 token 计算 SHA-256 并查库，命中且未过期且未吊销时，系统 SHALL 以该 token 绑定的 AdminUser 身份建立 Spring Security 认证上下文（authorities 含其角色），使工具调用内的权限校验天然复用既有逻辑。系统 SHALL NOT 在工具调用层（ToolCallback）再做一次独立的 token 鉴权——鉴权 SHALL 只发生在 filter 层。鉴权失败（未提供 token / 无效 / 过期 / 已吊销）SHALL 导致请求未认证，并 SHALL 返回 401 与响应体 `{errorCode:"TOKEN_INVALID"}`，使客户端能据此识别"本地 token 已失效，需重新登录"并清除本地令牌，与网络抖动等可重试错误区分。

#### Scenario: 有效 token 鉴权成功并以绑定用户身份执行
- **WHEN** 请求携带一个有效且未过期未吊销的 token 访问 MCP 端点 `/mcp`
- **THEN** 系统 SHALL 以该 token 绑定的 AdminUser 身份建立认证上下文
- **AND** 该请求内的权限校验 SHALL 与该 AdminUser 直接登录时一致

#### Scenario: 过期 token 鉴权失败
- **WHEN** 请求携带一个已超过 6 个月有效期的 token
- **THEN** 系统 SHALL 返回 401 与 `{errorCode:"TOKEN_INVALID"}`

#### Scenario: 已吊销 token 鉴权失败
- **WHEN** 请求携带一个已被吊销的 token
- **THEN** 系统 SHALL 返回 401 与 `{errorCode:"TOKEN_INVALID"}`

#### Scenario: 工具调用不再重复鉴权
- **WHEN** 已通过 filter 层鉴权的请求调用某个 MCP 工具
- **THEN** 工具执行 SHALL 直接复用 filter 层设置的 SecurityContext
- **AND** SHALL NOT 要求工具入参携带额外的 token 字段（如已废弃的 `__kfile_access_token`）

## REMOVED Requirements

### Requirement: bridge 中间层工具鉴权
**Reason**: bridge 中间层已废弃（客户端直连后端 Streamable HTTP）。bridge 时代的"把 token 注入每个工具调用的 JSON 参数、由工具层包装回调重新鉴权"机制（`__kfile_access_token` 隐藏参数）随之移除，鉴权统一到 filter 层。
**Migration**: 客户端改为直连 `/mcp`（streamable-http 传输），令牌通过 HTTP `Authorization: Bearer <token>` header 携带。不再需要 bridge，也不再需要工具入参携带 token。工具定义直接由后端 `tools/list` 返回。
