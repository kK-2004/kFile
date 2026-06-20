## Context

k-File 的 MCP 服务端当前基于 spring-ai 1.0.6 的旧 SSE 传输（`/mcp/sse` 建立长连接 + `/mcp/messages` 收发消息）。因为 MCP 的 SSE 传输是有状态长连接，无法直接被只支持 stdio 或 streamable-http 的 MCP 客户端使用，所以历史上引入了一个 Node 编写的 `mcp-bridge`：它对外暴露 stdio，对内连后端 SSE，并附带浏览器 OAuth 登录流程。

bridge 带来了三个持续的运维痛点：
1. **包版本缓存**：客户端配置 `@latest`，但 npx/全局包会缓存，不必然拉最新版——后端加了工具，agent 拿不到（已多次遇到）。
2. **SSE 长连接静默断开**：代理空闲超时 / NAT 回收会杀掉连接，bridge 无内置重连，工具调用挂起超时（虽已在 0.2.5 加了重连，但仍是脆弱点）。
3. **工具定义重复维护**：bridge 本地硬编码一份工具 schema（description/inputSchema），后端改工具要同步改 bridge + 发 npm。

spring-ai 1.1.0（2025-11-12 GA）引入了 Streamable HTTP 传输（MCP 2025-03 规范）：单一无状态 `/mcp` 端点，POST 发请求、GET 可升级为 SSE 流式。客户端可直连后端，后端成为工具定义的唯一真相源。

当前后端 MCP 相关代码现状（来自调研）：
- MCP 配置**纯声明式**（`application.yml`），无手动 transport 实例化、无直接 `io.modelcontextprotocol` SDK 类引用。
- 工具用 `@Tool`/`@ToolParam` 注解在 `McpProjectTools` 上，通过 `McpToolRegistration` 提供 `ToolCallbackProvider` bean。
- 鉴权有**两层**：`McpTokenAuthFilter`（servlet filter，校验 Bearer header）+ `McpToolRegistration` 的 `__kfile_access_token` 隐藏参数包装（从工具入参挖 token 再鉴权）。后者是 bridge 时代的设计（bridge 把 token 塞进每个工具调用的 JSON 参数）。
- `McpTokenAuthFilter` 靠 `path.startsWith("/mcp/")` 匹配，注册在 Spring Security 链的 `UsernamePasswordAuthenticationFilter` 之前。

## Goals / Non-Goals

**Goals:**
- 把 MCP 传输切到 spring-ai 1.1.0 的 Streamable HTTP（WebMVC 版），客户端直连后端 `/mcp`。
- 废弃 `mcp-bridge`，消除包版本缓存与工具定义重复维护问题。
- 鉴权统一到 filter 层（Bearer header），移除工具层 `__kfile_access_token` 双重鉴权。
- 不引入 WebFlux（避免 servlet/webflux 混用），不引入 Higress/网关层。

**Non-Goals:**
- 不改工具方法本身（`McpProjectTools` 的 `@Tool` 方法与返回结构不动）。
- 不删除已发布的 npm 包（旧客户端兼容回退）。
- 不做 SSE 与 Streamable HTTP 的长期双跑（目标是切净；1.1.0 两者虽共存，但 SSE 到 1.1.7 才弃用，可作为紧急回退而非常态）。
- 不改 token 的签发/吊销/清理逻辑（已在上一变更实现，本次只动鉴权"层"）。

## Decisions

### 决策 1：用官方 spring-ai 1.1.0 WebMVC Streamable HTTP，不用 WebFlux / 不用 Alibaba

**选择**：`spring-ai-starter-mcp-server-webmvc`（artifact 名不变，只升版本）+ Streamable HTTP 配置。

**理由**：
- starter artifact 同名，本质是"升版本 + 改传输配置"，不引入新架构。
- WebMVC 版让 `McpTokenAuthFilter`（servlet `OncePerRequestFilter`）直接复用，鉴权层零改动成本。
- 1.1.0 是 GA 正式版（非快照），生产可用。

**备选**：
- WebFlux 版（`...-server-webflux`）：需 servlet→webflux 混用，filter 链要重写为 `WebFilter`，风险高。WebMVC 版已能满足，没必要。
- Spring AI Alibaba + Higress：引入网关层，当前场景不需要 MCP 多 server 聚合；且 Alibaba Streamable 代理有已知 bug（`Mcp-Session-Id` 透传，Issue #3892）。

### 决策 2：废弃 bridge，客户端直连（不保留 stdio 适配层）

**选择**：删除对 bridge 的依赖，客户端直连 `/mcp`。bridge 目录停止维护、不再发 npm。

**理由**：直连让后端成为唯一真相源，彻底解决包缓存 + 工具定义不刷新。保留 bridge 等于保留所有痛点。

**代价**：只支持 stdio 的客户端无法接入。但目标客户端（ZCode / workbuddy）支持 streamable-http，可接受。

### 决策 3：鉴权统一到 filter 层，移除工具层 `__kfile_access_token` 包装

**选择**：删除 `McpToolRegistration` 的 `withMcpAccessToken`/`callWithToken`/`extractToken`（约 70 行），`ToolCallbackProvider` 直接返回 `MethodToolCallbackProvider` 结果。鉴权完全由 `McpTokenAuthFilter` 完成。

**理由**：Streamable HTTP 下客户端带 Bearer header，filter 层已校验并设置 SecurityContext，工具层无需再挖 token。双层鉴权是 bridge 注入参数时代的遗留，移除是纯简化（无功能损失）。工具方法里现有的 `SecurityContextHolder.getContext().getAuthentication()` 在 filter 设置后仍有效。

### 决策 4：Streamable HTTP 端点路径匹配用 `path.equals("/mcp") || path.startsWith("/mcp/")`

**选择**：`McpTokenAuthFilter.isMcpTransportRequest` 与 `SecurityConfig` 授权规则都覆盖 `/mcp`（无尾斜杠）。

**理由**：Streamable HTTP 默认端点是 `/mcp`（非 `/mcp/`）。当前 `startsWith("/mcp/")` 不匹配 `/mcp`。Spring Security 的 `/mcp/**` 也不匹配无后缀的 `/mcp`。需显式补 `requestMatchers("/mcp", "/mcp/**")`。

## Risks / Trade-offs

- **[spring-ai 跨 minor 升级的未知 API 变化]** → 编译期会暴露，逐个修。重点验证 `ToolCallbackProvider` bean 仍被 1.1.0 自动配置识别（调研确认 `@Tool`/`MethodToolCallbackProvider` 接口不变，风险低）。
- **[Streamable HTTP yml 配置项拼写不确定]**（`streamable-http.mcp-endpoint` vs `streamable-endpoint`）→ 升级后第一时间读 1.1.0 的 `McpServerProperties.java` 源码确认，再定配置。这是低风险试配置项。
- **[SSE 端点下线让旧 bridge 客户端断连]** → 迁移需协调客户端切换。紧急情况可临时在 1.1.0 同时启用 SSE（两者共存到 1.1.7）作为回退。
- **[客户端直连的 header 配置因客户端而异]** → ZCode / workbuddy 配 Authorization header 的方式不同，需逐个验证。属客户端运维项，不阻塞后端。
- **[trade-off：只支持 stdio 的客户端无法接入]** → 已在决策 2 接受，目标客户端均支持 streamable-http。

## Migration Plan

1. **升级 + 编译**：`spring-ai.version` 1.0.6 → 1.1.0，`mvn compile` 暴露并修复兼容问题。
2. **定 yml 配置**：读 1.1.0 `McpServerProperties` 源码确认 Streamable HTTP 配置项拼写，改 `application.yml`（删 sse-endpoint/sse-message-endpoint，加 streamable 配置）。
3. **移除工具层鉴权**：简化 `McpToolRegistration`，删除 token 包装。
4. **适配 filter**：`McpTokenAuthFilter` 端点匹配补 `/mcp`；`SecurityConfig` 授权规则补 `/mcp`。
5. **启动验证**：`POST /mcp`（Bearer header）完成握手 + `tools/list` 返回全部工具；无/错 token → 401 + TOKEN_INVALID。
6. **客户端切换**：各客户端改为直连 `/mcp`。
7. **更新 spec**：`mcp-project-tools` / `long-lived-access-token` 的传输与鉴权措辞。

**回退策略**：1.1.0 下 SSE 与 Streamable 共存。若 Streamable 出问题，可临时重新启用 SSE 端点（`/mcp/sse`）让旧 bridge 客户端回退，bridge 历史版本仍在 npm。最坏情况把 `spring-ai.version` 回退到 1.0.6（git revert）。

## Open Questions

- Streamable HTTP 的确切 yml 配置项拼写（`streamable-http.mcp-endpoint` vs `streamable-endpoint`）—— 执行时读 1.1.0 `McpServerProperties` 源码定。
- 1.1.0 自动配置类名是否有变化（`WebMvcMcpServerAutoConfiguration` 是否仍是 Streamable HTTP 的入口）—— 执行时读 `AutoConfiguration.imports` 确认。
- 各客户端（ZCode / workbuddy）直连 `/mcp` + Bearer header 的配置方式 —— 迁移期逐个验证。
