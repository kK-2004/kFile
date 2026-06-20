## Why

MCP 服务端当前基于 spring-ai 1.0.6 的旧 SSE 传输（`/mcp/sse` + `/mcp/messages`，有状态长连接）。这迫使我们维护一个 Node `mcp-bridge` 中间层把 stdio 转成 SSE，带来了三个持续的运维痛点：bridge 包版本缓存导致 agent 拿不到最新工具定义、SSE 长连接静默断开导致调用超时、工具定义在 bridge 本地重复维护。spring-ai 1.1.0（GA 正式版）引入了 Streamable HTTP 传输（单一无状态 `/mcp` 端点），让客户端可直连后端、后端成为工具定义的唯一真相源，可一次性消除上述三个问题。

## What Changes

- **BREAKING**：MCP 传输从旧 SSE（`/mcp/sse` + `/mcp/messages`）迁移到 spring-ai 1.1.0 的 Streamable HTTP（单一 `/mcp` 端点，WebMVC 版）。旧 SSE 端点下线。
- **BREAKING**：废弃 `mcp-bridge` 中间层。客户端改为直连后端 `/mcp`（streamable-http 传输 + Bearer header）。bridge 目录停止维护，不再发新版 npm（已发布历史版本保留供旧客户端回退）。
- 移除 `McpToolRegistration` 的 `__kfile_access_token` 隐藏参数鉴权包装（约 70 行），鉴权统一由 `McpTokenAuthFilter` 在 filter 层用 Bearer header 完成。
- 升级 spring-ai 1.0.6 → 1.1.0（starter artifact 同名，BOM 管理版本）。
- `McpTokenAuthFilter` 与 `SecurityConfig` 适配新端点路径 `/mcp`（无尾部斜杠的匹配）。

## Capabilities

### New Capabilities
<!-- 无新增 capability。传输迁移与鉴权统一是对既有 mcp-project-tools / long-lived-access-token 的修改。 -->

### Modified Capabilities
- `mcp-project-tools`：MCP 传输层从旧 SSE 改为 Streamable HTTP（单一 `/mcp` 端点）；鉴权方式从"工具入参隐藏 token + filter 双层"统一为"filter 层 Bearer header 单层"；废弃 bridge 直连语义。
- `long-lived-access-token`：令牌鉴权统一到 filter 层（`McpTokenAuthFilter` 校验 Bearer header），不再依赖工具层隐藏参数传递 token；token 失效仍返回 401 + `TOKEN_INVALID`。

## Impact

- **依赖**：`pom.xml` 的 `spring-ai.version` 1.0.6 → 1.1.0；MCP Java SDK 随之 0.10.0 → 0.12.1。
- **后端代码**：`McpProjectTools.java`（不变）、`McpToolRegistration.java`（大幅简化，移除 token 包装）、`McpTokenAuthFilter.java`（端点匹配）、`SecurityConfig.java`（授权规则）、`application.yml`（传输配置）。
- **mcp-bridge**：停止维护。删除会破坏仍依赖旧 SSE 的客户端，故保留代码与历史 npm 包，但不再发新版本。
- **客户端配置**：所有接入 k-File MCP 的客户端（ZCode / workbuddy 等）需改为直连 `/mcp`（streamable-http）+ Bearer header。这是客户端运维项，不阻塞后端迁移。
- **风险**：spring-ai 跨 minor 版本升级可能有未知 API 变化（编译期暴露）；Streamable HTTP 的 yml 配置项拼写需读 1.1.0 `McpServerProperties` 源码确认；迁移期 SSE 端点下线会让旧 bridge 客户端断连（需协调切换）。
