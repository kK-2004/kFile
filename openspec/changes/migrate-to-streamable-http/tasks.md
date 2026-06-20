## 1. 依赖升级与编译兼容

- [ ] 1.1 把 `pom.xml` 的 `<spring-ai.version>` 从 `1.0.6` 改为 `1.1.0`（BOM 管理 starter 版本，artifact 名 `spring-ai-starter-mcp-server-webmvc` 不变）
- [ ] 1.2 执行 `mvn compile`，修复因 spring-ai 跨 minor 版本产生的编译错误（重点关注 `ToolCallbackProvider`/`MethodToolCallbackProvider`/`@Tool` 的 API 兼容）
- [ ] 1.3 确认项目能正常启动（`mvn spring-boot:run` 或 IntelliJ 启动），无 bean 装配/自动配置冲突

## 2. 确认 Streamable HTTP 配置项

- [ ] 2.1 从 1.1.0 的 `spring-ai-starter-mcp-server-webmvc` jar 中读取 `McpServerProperties.java` 源码，确认 Streamable HTTP 的 yml 配置项拼写（`streamable-http.mcp-endpoint` vs `streamable-endpoint`）与默认端点路径
- [ ] 2.2 确认 1.1.0 的自动配置类（读 `AutoConfiguration.imports`）：Streamable HTTP 由哪个 auto-config 装配，是否默认开启
- [ ] 2.3 确认 SSE 与 Streamable HTTP 是否可共存（用于评估紧急回退可行性）

## 3. 切换传输配置

- [ ] 3.1 修改 `src/main/resources/application.yml`：删除 `sse-endpoint` / `sse-message-endpoint`，按 2.1 确认的拼写加入 Streamable HTTP 配置，端点路径 `/mcp`
- [ ] 3.2 启动应用，确认 `/mcp` 端点已注册（日志或 actuator mappings）

## 4. 移除工具层鉴权包装（核心简化）

- [ ] 4.1 简化 `src/main/java/com/kk/mcp/McpToolRegistration.java`：删除 `withMcpAccessToken`、`callWithToken`、`extractToken`、`TokenInput` record，移除 `McpTokenService` 注入与 `ACCESS_TOKEN_ARG` 常量
- [ ] 4.2 `mcpToolCallbackProvider` 直接 `return MethodToolCallbackProvider.builder().toolObjects(tools).build()`
- [ ] 4.3 确认 `McpProjectTools` 各工具方法里的 `SecurityContextHolder.getContext().getAuthentication()` 在 filter 层设置后仍可取到（无需改动工具方法本身）
- [ ] 4.4 编译验证

## 5. filter 层与 Security 适配端点路径

- [ ] 5.1 修改 `McpTokenAuthFilter.isMcpTransportRequest`：端点匹配改为 `path.equals("/mcp") || path.startsWith("/mcp/")`（覆盖无尾斜杠的 `/mcp`）
- [ ] 5.2 修改 `SecurityConfig`：授权规则 `requestMatchers("/mcp/**")` 补充为 `requestMatchers("/mcp", "/mcp/**")`，确保 `/mcp`（无尾斜杠）受 Bearer 鉴权保护
- [ ] 5.3 编译验证

## 6. 端到端验证

- [ ] 6.1 用 curl/HTTP 客户端 `POST /mcp`（带有效 Bearer header）完成 MCP 握手 + `tools/list`，确认返回全部工具及完整 description/inputSchema
- [ ] 6.2 调用 `list_my_projects`（带 Bearer header），确认返回数据且权限正确（SUPER 全部 / ADMIN 仅分配的）
- [ ] 6.3 无 token / 错误 token / 已吊销 token 请求 `/mcp`，确认返回 401 + `{errorCode:"TOKEN_INVALID"}`
- [ ] 6.4 确认旧 SSE 端点 `/mcp/sse` 已不可用（迁移切净）

## 7. 废弃 bridge

- [ ] 7.1 在 `mcp-bridge/README.md` 顶部加"已废弃，客户端直连后端 /mcp"声明，附直连配置示例（url + transport:streamable-http + Authorization header）
- [ ] 7.2 确认不再向 npm 发新版本（保留历史包供旧客户端回退，不执行 `npm publish`）

## 8. 更新 OpenSpec 主 spec

- [ ] 8.1 把 `openspec/specs/mcp-project-tools/spec.md` 的"MCP 服务端 SSE 传输"改为"MCP 服务端 Streamable HTTP 传输"（按 delta 内容）
- [ ] 8.2 把 `openspec/specs/long-lived-access-token/spec.md` 的"令牌鉴权与安全上下文注入"更新为单一 filter 层鉴权 + 401 TOKEN_INVALID（按 delta 内容）
- [ ] 8.3 在 `long-lived-access-token/spec.md` 记录 bridge 隐藏参数鉴权的移除（Reason/Migration）

## 9. 客户端切换（运维项，不阻塞后端）

- [ ] 9.1 ZCode 配置改为直连 `/mcp`（streamable-http + Bearer header），验证工具可见可调
- [ ] 9.2 workbuddy 配置改为直连 `/mcp`，验证（消除之前的 bridge 包缓存与 schema 不刷新问题）
- [ ] 9.3 token 获取流程：客户端调 `POST /api/mcp/login` 拿 accessToken，配进 header
