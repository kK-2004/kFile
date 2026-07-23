## Why

当前接入依赖本地 `mcp-bridge` 同时充当 MCP client、管理长期令牌并代理工具，agent 无法使用标准 MCP OAuth 能力自动完成未认证后的登录与重试，也增加了单独发布和维护客户端的成本。应将认证发现、OAuth 授权和 MCP 请求转发收敛到后端，使兼容 MCP OAuth 的 agent 可直接连接服务。

## What Changes

- 后端提供符合 MCP OAuth 约定的受保护资源发现、授权服务发现、授权码（PKCE）换取/刷新 access token 流程；未认证或 token 失效时返回带资源元数据提示的 `401`，由 agent 自动发起浏览器登录并重试。
- 将 Spring AI 从 1.0.6 升级到 1.1.8，保留现有 `@Tool` 与 `ToolCallbackProvider`，把 MCP transport 从 legacy SSE 双端点切换为 `/mcp` Streamable HTTP 单端点。
- 新增后端 MCP porter 层，在 Spring AI MCP server 与既有工具实现之间传播可信 OAuth 身份并完成进程内调用；后端不创建或使用 MCP client。
- 保留现有 Web session 登录和项目权限模型；OAuth 授权页复用已登录管理员身份，未登录时先进入现有 Web 登录流程。
- **BREAKING** 移除本地 `mcp-bridge`/stdio MCP server、`kfile_login`/`kfile_logout` 工具及其本地令牌文件、SSE client、重连和工具代理逻辑，agent 改为直接配置后端远程 MCP URL。
- **BREAKING** 废弃账号密码换取 6 个月长期令牌的 `/api/mcp/login` 及现有长期令牌管理流程，改用短期 OAuth access token 与 refresh token 生命周期。
- 更新部署与接入文档，说明远程 MCP URL、OAuth 回调要求、反向代理配置和迁移方式。

## Capabilities

### New Capabilities

- `mcp-oauth-authorization`: MCP OAuth 资源发现、授权码 + PKCE、token 签发/刷新/吊销，以及 agent 自动处理 `401` 登录挑战的行为契约。

### Modified Capabilities

- `mcp-project-tools`: 将长期令牌保护的 SSE 接入改为 OAuth 保护的后端 porter 直连入口，并以 OAuth 用户身份执行既有工具。
- `long-lived-access-token`: 移除账号密码签发、6 个月长期 token、原令牌列表/吊销和 bearer 鉴权要求，由标准 OAuth token 生命周期替代。

## Impact

- **后端**：Spring AI 1.0.6 → 1.1.8、Spring Boot 3.5.x 补丁基线、Spring Security 配置、MCP Streamable HTTP/porter、认证过滤与安全上下文、OAuth 授权与 token 服务、数据库实体及清理任务。
- **前端**：MCP 授权确认页与登录后回跳流程；移除旧长期令牌管理入口或改为 OAuth 授权管理视图。
- **客户端**：删除 `mcp-bridge/` npm 包及其发布流程；现有用户需将 stdio 配置迁移为后端远程 MCP URL，并使用支持 MCP OAuth 的 agent。
- **协议/API**：新增 well-known 元数据和 OAuth endpoints；MCP 未认证响应、transport URL 与 token 语义发生变化；业务工具名称、参数和权限规则保持不变。
- **部署**：反向代理需正确透传 `Authorization`、`WWW-Authenticate`、OAuth 回调与 MCP 长连接/流式响应。
