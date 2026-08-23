## 1. 依赖与配置基线

- [x] 1.1 将 Spring AI 从 1.0.6 升级到 1.1.8，保留 `spring-ai-starter-mcp-server-webmvc`，并加入 Spring Authorization Server 与 OAuth2 Resource Server 依赖
- [x] 1.2 将 Spring Boot 从 3.5.6 对齐到 Spring AI 1.1.8 的 3.5.15 补丁基线，运行最小启动与 Maven 依赖收敛检查确认无版本冲突
- [x] 1.3 在应用配置中增加可信 `app.public-base-url`、规范化 MCP resource URL、issuer、`mcp:tools` scope、authorization code/access token/refresh token 有效期及 DCR 限流配置
- [x] 1.4 增加配置校验：生产公共基址必须为 HTTPS（localhost 开发例外），issuer、metadata endpoint 与 MCP resource 必须同配置一致

## 2. OAuth 持久化与生命周期模型

- [x] 2.1 新增 OAuth client registration 实体、repository 与唯一约束，保存 client id、精确 redirect URIs、grant/response 类型、认证方式、创建/最后使用/过期状态
- [x] 2.2 新增 OAuth authorization grant 与 token family 实体、repository 和索引，关联 AdminUser、client、scope、resource、授权/吊销状态
- [x] 2.3 新增 authorization code、access token 与 refresh token 的哈希存储和状态字段，保证明文只在协议响应构造期间存在且不进入日志
- [x] 2.4 实现短期 authorization code 单次消费、access token 过期/吊销校验、refresh token 单事务轮换与旧 token 重用时整族吊销
- [x] 2.5 实现未使用动态 client、过期 code 和过期/吊销 token 元数据的定时清理，并验证不会物理删除仍需审计的活动 grant

## 3. OAuth 与 MCP 元数据发现

- [x] 3.1 实现 `/mcp` 对应的 RFC 9728 protected-resource metadata，返回规范化 `resource`、`authorization_servers` 与 `mcp:tools` scope
- [x] 3.2 配置 OAuth authorization-server metadata，发布 authorization、token、registration、revocation endpoints 及 `code_challenge_methods_supported=["S256"]`
- [x] 3.3 为缺失、无效、过期、吊销或 resource 不匹配的 MCP token 返回 `401`，并统一设置含 `resource_metadata` 和 `scope="mcp:tools"` 的 Bearer `WWW-Authenticate` header
- [x] 3.4 增加 metadata 与 challenge 测试，覆盖恶意 Host/转发头不能改变 issuer/resource/endpoint URL

## 4. OAuth client 注册与浏览器授权

- [x] 4.1 实现预注册 public client 读取与 RFC 7591 Dynamic Client Registration endpoint，限制为 Authorization Code + PKCE 所需的 public-client 元数据
- [x] 4.2 对动态注册增加字段白名单、重复注册去重、请求限流和审计日志，并拒绝非 localhost HTTP、非法 scheme 及格式错误的 redirect URI
- [x] 4.3 在 authorization request 校验 client id、scope、resource、state、精确 redirect URI、code challenge 与 `S256` 方法，任何校验失败均不得向未验证 URI 重定向
- [x] 4.4 将未登录 authorization request 安全保存并接入现有管理员 session 登录，登录成功后恢复完整请求且防止参数被篡改
- [x] 4.5 改造 MCP 授权页为标准 consent 页面，展示 client、redirect URI、scope 和当前用户，并分别处理批准与拒绝
- [x] 4.6 批准时签发绑定用户/client/redirect URI/resource/PKCE challenge 的短时单次 code，仅回传 `code` 与原始 `state`；拒绝时返回标准 `access_denied`

## 5. Token endpoint、刷新与撤销

- [x] 5.1 实现 authorization code token grant，严格校验 code 未消费、client、redirect URI、resource 与 PKCE verifier 后签发不透明 access/refresh token
- [x] 5.2 实现 refresh token grant，保持原 client/用户/scope/resource 绑定并轮换 refresh token，覆盖并发刷新和旧 token 重放
- [x] 5.3 实现标准 token revocation endpoint，使 access token、refresh token 或整个授权 grant 的撤销即时生效且响应不泄露 token 是否存在
- [x] 5.4 在 token 校验时联动 AdminUser 存在/启用状态；用户禁用、删除或 grant 撤销后立即拒绝 access 与 refresh token
- [x] 5.5 增加 OAuth 协议测试，覆盖成功换取、code 重用、错误 verifier、错误 redirect/resource/client、过期、刷新轮换、重放检测与撤销

## 6. 安全链隔离

- [x] 6.1 将现有单一 SecurityFilterChain 拆为有明确优先级的 OAuth、`/mcp` bearer 和 Web session 三条链，并为 consent 写操作启用合适的 CSRF/一次性确认防护
- [x] 6.2 限制 MCP bearer token 只能访问规范化 MCP resource，验证它不能直接访问 `/api/admin/**`，Web session 也不能替代 `/mcp` bearer 认证
- [x] 6.3 将有效 OAuth subject 映射到现有 AdminUser 与 `ROLE_SUPER`/`ROLE_ADMIN` authorities，scope 缺失返回 `403 insufficient_scope`，身份失败返回可发现的 `401`
- [x] 6.4 增加安全回归测试，确认现有 Web 登录、登出、session 和管理 API 权限行为不变

## 7. Streamable HTTP 与后端 McpPorter

- [x] 7.1 配置 Spring AI 1.1.8 `protocol=STREAMABLE` 与 `streamable-http.mcp-endpoint=/mcp`，移除旧 SSE 双端点配置并验证 initialize、session/协议版本协商、POST/GET/DELETE 与流式响应行为
- [x] 7.2 保留 `McpProjectTools` 的 `@Tool` 和现有 `ToolCallbackProvider`，实现薄 `McpPorter` 在 Spring AI MCP server 与本地 tool callback 之间完成进程内调用，且不引用或实例化任何 MCP client
- [x] 7.3 使用 Spring AI WebMVC HTTP request/transport context 传播不可由 tool arguments 构造的 OAuth subject、client、scope 与 resource，并拒绝跨用户/client 的 MCP session 复用
- [x] 7.4 重写现有 `McpToolRegistration` 安全装饰器：从可信 request context 建立临时 SecurityContext、调用后在 `finally` 恢复，同时保留 Spring AI 自动 schema/工具注册并移除 `__kfile_access_token` 解析与注入
- [x] 7.5 增加 porter 单元/集成测试，覆盖实时 tools/list、业务 tools/call、无出站 MCP 连接、工具异常映射、异步线程传播和并发用户身份隔离
- [x] 7.6 逐项回归现有 MCP 业务工具的名称、输入 schema、输出、SUPER/ADMIN 限制与项目权限，确认 OAuth 改造未改变业务契约

## 8. 授权管理界面

- [x] 8.1 在管理端增加当前用户 OAuth client/grant 列表 API 与界面，展示 client 名称、scope、创建/最近使用时间但不展示任何 token
- [x] 8.2 实现用户撤销自己的 grant、SUPER 撤销任意 grant 的权限校验与界面操作，并验证撤销后 agent 下一次请求收到 `401`
- [x] 8.3 删除旧“6 个月 MCP 访问令牌”签发/复制/列表文案和交互，确保 consent 页面不再把明文 token 放入浏览器 URL

## 9. 旧 bridge 与长期令牌清理

- [x] 9.1 删除 `/api/mcp/login`、旧 `/api/mcp/authorize` 明文 token 回调及旧 token 列表/吊销 controller 路由，清理前端对应 API 调用
- [x] 9.2 删除 `McpTokenAuthFilter`、`McpTokenService`、长期 token 清理任务和运行时 `mcp_access_tokens` repository/entity 引用，同时保留数据库旧表供发布回滚
- [x] 9.3 删除 `mcp-bridge/` Node.js stdio server、`kfile_login`/`kfile_logout`、本地 token 文件、SSE client/重连与 npm 发布配置
- [x] 9.4 移除 legacy `/mcp/sse`、`/mcp/messages` 配置和反向代理规则，确认仅保留 Spring AI MCP server starter、未引入任何 Spring AI/Java SDK MCP client starter 或出站 MCP client 实现

## 10. 部署、迁移文档与端到端验证

- [x] 10.1 更新开发/生产反向代理，透传 `Authorization`、`WWW-Authenticate` 和 Streamable HTTP 所需 method/header/流式响应，且不缓冲 MCP 流
- [x] 10.2 编写远程 MCP 接入文档，给出单一 `/mcp` URL 配置、支持的 agent 最低版本、首次 OAuth 行为、撤销/重新授权与自部署公共基址要求
- [x] 10.3 编写从 stdio `mcp-bridge` 到远程 URL 的配置前后对照，明确旧 6 个月 token 不迁移、旧 agent 不再受支持及回滚步骤
- [x] 10.4 用真实兼容 agent 完成首次 `401` → metadata → DCR → 浏览器登录/同意 → code + PKCE → 自动重试 → tools/list → tools/call 的端到端测试
- [x] 10.5 验证 access token 到期后的自动 refresh、refresh 被撤销后的重新授权、管理员禁用、错误 audience、并发用户隔离及反向代理后的绝对 URL
- [x] 10.6 运行后端与前端完整测试/构建，执行 `openspec validate replace-mcp-client-with-oauth-porter --strict`，并记录生产切换与旧表人工清理的后续运维项
