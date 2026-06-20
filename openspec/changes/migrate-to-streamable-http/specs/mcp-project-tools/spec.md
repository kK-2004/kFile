## MODIFIED Requirements

### Requirement: MCP 服务端 Streamable HTTP 传输

系统 SHALL 提供基于 Streamable HTTP 传输（spring-ai 1.1.0，MCP 2025-03 规范）的 MCP（Model Context Protocol）服务端，作为 Spring Boot 应用的一部分，通过单一无状态端点 `/mcp`（POST 发请求、GET 可升级为 SSE 流式）暴露，使用长期令牌鉴权。系统 SHALL NOT 再提供旧的 SSE 端点（`/mcp/sse`、`/mcp/messages`）。客户端 SHALL 直连 `/mcp`（不经过 bridge 中间层），令牌通过 HTTP `Authorization: Bearer <token>` header 携带，由 filter 层（`McpTokenAuthFilter`）统一校验。未通过令牌鉴权的 MCP 请求 SHALL 被拒绝（401 + `{errorCode:"TOKEN_INVALID"}`）。

#### Scenario: 未携带令牌请求 MCP 被拒
- **WHEN** 客户端在未携带有效长期令牌的情况下请求 MCP 端点 `/mcp`
- **THEN** 系统 SHALL 返回 401 与 `{errorCode:"TOKEN_INVALID"}`，拒绝处理 MCP 请求

#### Scenario: 携带有效令牌请求 MCP 成功
- **WHEN** 客户端携带有效长期令牌（Authorization Bearer header）请求 MCP 端点 `/mcp`
- **THEN** 系统 SHALL 处理 MCP 请求（握手 / tools/list / tools/call 等）

#### Scenario: 工具定义由后端单一来源返回
- **WHEN** 已鉴权客户端请求 `tools/list`
- **THEN** 系统 SHALL 返回后端 `@Tool` 注解定义的全部工具及其 description/inputSchema
- **AND** SHALL NOT 依赖任何中间层本地缓存或硬编码的工具清单

#### Scenario: 旧 SSE 端点不再可用
- **WHEN** 客户端尝试连接旧 SSE 端点 `/mcp/sse` 或 `/mcp/messages`
- **THEN** 系统 SHALL NOT 以 MCP SSE 传输响应（端点已下线）
