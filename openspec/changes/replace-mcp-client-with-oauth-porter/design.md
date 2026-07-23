## Context

当前远程能力由 Spring AI 1.0.6 在后端暴露 `/mcp/sse` 与 `/mcp/messages`，但用户实际接入的是独立的 Node.js `mcp-bridge`：它在本地充当 stdio MCP server 和远端 MCP client，打开自建授权页取得 6 个月 token，将 token 保存到本地文件，再负责 SSE 重连、工具发现和工具调用转发。后端还要求 bridge 把 token 作为隐藏参数 `__kfile_access_token` 注入每次工具调用，以跨越 transport 线程边界重建 `SecurityContext`。

这套流程外观类似 OAuth，实质上没有标准的 protected-resource metadata、authorization-server metadata、authorization code、PKCE、resource audience 或 refresh token。直接连接后端的 agent 因而无法从 `401` 自动发现授权服务，也无法自行完成登录和重试。

本变更横跨 Spring Security、MCP transport、前端授权页、token 数据模型、部署配置与客户端分发。现有约束包括：Java 21 / Spring Boot 3.5.6 / Spring AI 1.0.6、Vue 3、Hibernate `ddl-auto: update`（无 Flyway/Liquibase）、Web 管理端继续使用 session 登录，以及所有 MCP 工具必须复用现有角色和项目权限判断。实施目标为 Spring AI 1.1.8，并将 Spring Boot 补丁版本对齐到 3.5.15，以使用官方已支持的 WebMVC Streamable HTTP 与对应依赖基线。

## Goals / Non-Goals

**Goals:**

- 让支持 MCP OAuth 的 agent 只配置一个远程 MCP URL，即可在收到 `401` 后自动发现 OAuth 端点、打开浏览器授权、换取 token 并重试工具请求。
- 使用 Spring AI 1.1.8 的 WebMVC Streamable HTTP server 在 `/mcp` 接收远程请求，继续复用现有 `@Tool`、`ToolCallbackProvider` 和自动生成的工具 schema。
- 在后端提供轻量 `McpPorter`，把经过 OAuth 认证的工具调用交给既有 `ToolCallbackProvider`，不再部署或调用任何 MCP client。
- 使用 Authorization Code + PKCE、短期 access token、轮换 refresh token、resource audience 与严格 redirect URI 校验，替换明文回调和 6 个月 bearer token。
- 保持业务工具的名称、参数、返回值、角色限制和项目权限行为不变，并保持 Web session 登录行为不变。
- 给现有 stdio bridge 用户提供清晰的一次性迁移路径。

**Non-Goals:**

- 不在此次变更中重构 `McpProjectTools` 的业务逻辑或新增业务工具。
- 不移除 Spring AI、不改用原生 MCP Java SDK 手工注册全部工具，也不自行解析 MCP JSON-RPC/Streamable HTTP 协议。
- 不让 k-File 后端代理第三方 MCP server，也不引入出站 MCP client。
- 不为不支持远程 MCP OAuth 的旧 agent 保留一个新的 stdio 兼容程序。
- 不把 OAuth scope 设计成第二套项目权限系统；首期仅使用 `mcp:tools` 门槛，细粒度授权仍由现有角色和项目权限负责。
- 不迁移旧长期 token；切换后旧 token 必须重新授权。

## Decisions

### D1：后端同时作为 OAuth Authorization Server 与 MCP Resource Server

**选择**：在同一个 Spring Boot 应用内引入 Spring Authorization Server 与 OAuth2 Resource Server 支持。应用发布 authorization-server metadata，并为 MCP URL 发布 RFC 9728 protected-resource metadata；`/mcp` 是受保护资源，授权服务与现有 Web 登录共享 `AdminUser` 和 session。

**理由**：用户身份与权限数据都在本应用中，同进程部署可直接复用现有登录、禁用用户检查和审计能力，也避免维护第二个身份服务。Spring Security 负责协议参数校验和标准错误语义，减少自行实现 OAuth 状态机的安全风险。

**备选（否决）**：继续扩展 `/api/mcp/authorize?redirect_uri=...` 并把 token 放进回调查询参数。该方案无法被通用 agent 发现，不具备 PKCE、authorization code 和 token audience，且明文 token 会进入浏览器历史与日志。

### D2：以标准发现链驱动 agent 自动登录

**选择**：所有未携带 token、token 无效或过期的 `/mcp` 请求返回 `401`，并带：

```http
WWW-Authenticate: Bearer resource_metadata="https://<public-host>/.well-known/oauth-protected-resource/mcp", scope="mcp:tools"
```

protected-resource metadata 的 `resource` 为规范化 MCP URL，`authorization_servers` 指向同站 OAuth issuer；authorization-server metadata 暴露 authorization、token、registration 和 revocation endpoint，并明确 `S256` PKCE 支持。服务端从显式配置的公共基址生成这些绝对 URL，不信任任意 `Host`/转发头。

**理由**：这是 MCP 客户端在 `401` 后定位授权服务并自动继续的标准链路。路径级 metadata 同时避免未来同域多个受保护资源发生歧义。

**备选（否决）**：在 MCP tool error 中返回“请登录”或额外暴露 `kfile_login` 工具。授权必须发生在 HTTP 边界；tool error 无法触发通用 MCP host 的 OAuth 行为。

### D3：Authorization Code + PKCE，支持动态注册的 public client

**选择**：支持预注册 client 和 RFC 7591 Dynamic Client Registration。未知 agent 可自动注册 public client（`token_endpoint_auth_method=none`）；注册时保存 redirect URI 精确值，localhost 可使用 HTTP，其他地址必须使用 HTTPS。授权请求必须包含 `state`、`resource`、`code_challenge` 和 `code_challenge_method=S256`，token 请求必须使用匹配的 `code_verifier`、`redirect_uri`、`client_id` 与 `resource`。授权码单次使用且短时有效。

用户进入 authorization endpoint 后，未登录则先走现有管理端登录并返回原授权请求；已登录则展示 client、redirect URI 和 scope，确认后仅把 `code` 与原始 `state` 回传 agent。取消授权返回标准 OAuth error。

**理由**：DCR 能覆盖事先不知道 client id 的桌面/CLI agent；PKCE 与精确 redirect URI 绑定能防止授权码截获和开放重定向。保留预注册能力便于对已知 agent 做更严格治理。

**备选（暂不采用）**：仅支持 Client ID Metadata Document。其部署更轻，但并非所有现有 agent 都支持；首期用 DCR 获得更广兼容性，后续可作为附加注册方式加入。

### D4：短期不透明 access token + refresh token 轮换

**选择**：access token 默认为 15 分钟、refresh token 默认为 30 天，均可配置；token 只通过 token endpoint 响应返回，数据库只保存不可逆哈希及 client、用户、scope、resource、过期/吊销状态。每次 refresh 都轮换 refresh token；旧 refresh token 再次出现时吊销同一授权链。提供标准 revocation endpoint，用户被禁用、授权被撤销或 token audience 不匹配时立即拒绝。

MCP 的每个 HTTP 请求都必须通过 `Authorization: Bearer` 携带 access token，token 不得出现在 URL、工具参数、日志或 MCP session 状态中。access token 的 `resource` 必须与规范化 `/mcp` URL 完全匹配。

**理由**：不透明 token 延续项目当前“不在数据库存明文”的安全属性，并允许即时吊销；短期 access token 降低泄露影响，refresh 轮换让 agent 可保持会话而无需 6 个月静态凭据。

**备选（否决）**：自包含长效 JWT。它减少查库，但在当前单体应用中收益有限，且即时吊销、用户禁用和 refresh 重用检测更复杂。

### D5：Spring AI 1.1.8 提供 Streamable HTTP，`McpPorter` 只做进程内工具适配

**选择**：保留 `spring-ai-starter-mcp-server-webmvc`，将 Spring AI 从 1.0.6 升级到 1.1.8，并配置 `spring.ai.mcp.server.protocol=STREAMABLE`、`spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp`。Spring AI server 负责 `initialize`、session/协议协商、`tools/list`、`tools/call` 与可选流式响应；现有 `McpProjectTools`、`@Tool` 和 `ToolCallbackProvider` 继续作为工具定义来源。

`McpPorter` 不重复实现 MCP 协议路由，而是在 Spring AI 的 HTTP request/transport context 与 tool callback 之间传播已认证 OAuth principal，并在 callback 执行边界建立、恢复 `SecurityContext`。porter 不发起网络请求、不建立 MCP/SSE client、不缓存 bearer token，也不接受 `__kfile_access_token` 等模型可控隐藏参数。MCP session 绑定首次认证的 subject/client/resource，后续请求身份不一致时拒绝。

**理由**：Spring AI 1.1.8 已原生支持 WebMVC Streamable HTTP 和 transport context，升级成本小于移除 Spring AI 后用原生 SDK 重写全部工具 schema 与注册；同时仍可消除 bridge 的远程 client、重连、工具清单镜像和 token 注入。认证事实只来自 HTTP bearer token，模型输入不能伪造身份。

**备选（否决）**：在后端 porter 中创建一个 MCP client 再连接本机 `/mcp/sse`。这会重复协议栈、引入环回网络与第二次认证，也违背“后端仅转发、不需要 MCP client”的目标。

**备选（否决）**：移除 Spring AI，直接使用 `io.modelcontextprotocol.sdk:mcp-core` Servlet transport 并手工声明所有 `SyncToolSpecification`。该方案可行，但会丢失现有 `@Tool`/`ToolCallbackProvider` 的自动 schema 与注册能力，在本项目没有额外收益。

### D6：独立 SecurityFilterChain 隔离 Web、OAuth 与 MCP 行为

**选择**：按优先级拆分三条安全链：OAuth endpoints 使用 Authorization Server 规则和必要的 CSRF/consent 防护；`/mcp` 使用无 session 的 bearer resource-server 认证及 MCP 专用 challenge；其余 Web/API 沿用当前 session 规则。porter 只接受具有 `mcp:tools` scope 且 resource 匹配的 token，随后把 AdminUser 角色转换为现有 `ROLE_SUPER` / `ROLE_ADMIN` authorities。

**理由**：当前单一 filter chain 全局关闭 CSRF、同时容纳 session 与 bearer。拆分后不同入口的认证状态、错误格式与 session 策略不会互相污染，也能保证 Web API 不意外接受 MCP token。

### D7：新增 OAuth 持久化模型，旧长期 token 表不做自动破坏性迁移

**选择**：新增 client registration、authorization grant、authorization code/access token/refresh token 所需持久化实体和唯一索引；旧 `mcp_access_tokens` 数据在切换后不再参与鉴权。由于项目使用 `ddl-auto: update`，部署只做加表/加列，旧表保留以支持快速回滚，稳定后再由人工运维删除。

**理由**：OAuth grant 需要 client、redirect URI、scope、resource 与 token family 等旧表没有的语义。强行复用旧表会产生大量可空字段和错误兼容假设；自动删表又会让回滚不可逆。

## Risks / Trade-offs

- **[agent 兼容性差异]** 不同 MCP host 对 Streamable HTTP、DCR 和 metadata path 的支持程度不同。→ 使用协议要求的标准 discovery 响应，建立至少 Codex/Claude/Cursor 中项目实际支持对象的集成测试矩阵，并在文档列出最低版本。
- **[DCR 被滥用造成数据库膨胀]** registration endpoint 无需用户登录即可调用。→ 限制字段与 redirect scheme、按 IP/client metadata 限流、去重相同注册、设置未使用 client 的过期清理任务，并记录审计日志。
- **[反向代理导致 issuer/resource 不一致]** 错误的 scheme、host 或 context path 会让 agent 拒绝 token。→ 使用唯一的 `app.public-base-url` 配置生成 issuer、metadata 和 audience；启动时校验 HTTPS（开发 localhost 例外），端到端测试代理后的 URL。
- **[工具执行丢失身份]** 直接沿用 1.0.6 的隐藏 token 参数不适用于 agent 直连，且 transport 调度可能跨线程。→ 使用 Spring AI 1.1.8 的 WebMVC HTTP request/transport context 传播不可变身份，并在 callback 边界建立/恢复安全上下文；增加并发用户隔离测试。
- **[refresh token 重放]** 被盗旧 refresh token 可能与合法刷新并发。→ 单事务轮换、token family 状态与唯一约束；检测到已使用 token 时整族吊销并要求重新登录。
- **[切换造成旧用户中断]** stdio bridge 和旧 6 个月 token 都不能继续使用。→ 先并行发布新 `/mcp` 与文档并验证，再关闭旧入口；在发布说明中提供配置前后对照和明确截止版本。
- **[Spring AI 升级回归]** 从 1.0.6 升到 1.1.8 并对齐 Spring Boot 3.5.15 可能改变自动配置或工具 schema。→ 保留同一个 server starter 和 `ToolCallbackProvider`，先运行依赖/启动测试，再逐项快照比对 tools/list schema、tools/call 输出、协议协商与错误映射。

## Migration Plan

1. 将 Spring AI 升级到 1.1.8、Spring Boot 对齐到 3.5.15，在保留现有 `@Tool`/`ToolCallbackProvider` 的前提下完成启动与工具 schema 回归。
2. 以加法方式引入 OAuth 依赖、持久化表、metadata、DCR、authorization/token/revocation endpoints，并保持现有 Web session 登录可用。
3. 配置 Spring AI `/mcp` Streamable HTTP + `McpPorter`，完成 bearer resource/audience 校验和 request-context 身份传播；此阶段保留旧 `/mcp/sse` 与 bridge 作为短期验证回退。
4. 用真实 agent 验证完整路径：首次请求 `401` → metadata discovery → DCR → 浏览器登录/同意 → code + PKCE 换 token → 自动重试 → tools/list 和 tools/call；同时验证 refresh、撤销、过期与并发用户隔离。
5. 更新生产反向代理与 `app.public-base-url`，发布远程 MCP URL 的配置迁移说明，并给现有用户一个切换窗口。
6. 删除 `mcp-bridge/`、`kfile_login`/`kfile_logout` 相关文档与 npm 发布配置；移除 `/api/mcp/login`、旧授权明文 token 回调、旧 token 管理 UI、`McpTokenAuthFilter`、隐藏参数注入和长期 token 清理逻辑。
7. 停止读取旧 `mcp_access_tokens` 表；观察一个发布周期后由运维手工备份并清理遗留表。

**回滚**：在旧表尚未清理时，可恢复旧 filter、SSE 配置与 bridge 版本，并关闭新 `/mcp` 路由；新 OAuth 表为附加数据，不影响旧 session 或长期 token。已经迁移到新 URL 的用户需临时恢复旧 stdio 配置。

## Open Questions

- 无阻塞问题。access/refresh token 默认期限、DCR 限流阈值和迁移窗口均做成配置项，实施时按生产环境基线确定具体值。
