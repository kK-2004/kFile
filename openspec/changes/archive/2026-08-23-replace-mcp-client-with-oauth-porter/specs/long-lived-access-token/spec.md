## REMOVED Requirements

### Requirement: MCP 长期令牌登录签发

**Reason**: 账号密码直换 6 个月 bearer token 不是标准 MCP OAuth 流程，无法让 agent 通过 `401` 自动发现、授权和刷新。

**Migration**: 移除 `/api/mcp/login`；agent 改用 MCP protected-resource metadata 发现 Authorization Code + PKCE 流程并取得短期 OAuth token。

### Requirement: 令牌存储安全

**Reason**: 旧要求只描述单一长期 token，不能覆盖 authorization code、短期 access token、轮换 refresh token、client 与 resource 绑定。

**Migration**: 由 `mcp-oauth-authorization` 的 OAuth token 存储要求接管，所有凭据继续只保存不可逆哈希和必要元数据。

### Requirement: 令牌鉴权与安全上下文注入

**Reason**: 旧过滤器验证 6 个月 token，并依赖 bridge 把 token 注入工具参数；该身份传播方式不符合 OAuth HTTP 边界认证且可被模型参数影响。

**Migration**: 由 OAuth Resource Server 在 `/mcp` HTTP 边界校验 bearer token 和 resource，由 `McpPorter` 通过可信 request context 建立既有 AdminUser 安全上下文。

### Requirement: 令牌不影响现有 session 认证

**Reason**: 长期令牌机制整体移除，其与 session 并存的专用要求不再适用。

**Migration**: 使用 `mcp-oauth-authorization` 中 OAuth、MCP bearer 与 Web session 三条安全链隔离的要求，Web session 行为保持不变。

### Requirement: 令牌吊销

**Reason**: 旧 token 列表和自定义吊销流程只管理长期 token，不能表达按 OAuth client/grant 撤销或 refresh token family 失效。

**Migration**: 使用标准 revocation endpoint 和 MCP OAuth grant 管理；现有长期 token 在切换时全部失效，用户需在 agent 中重新授权。
