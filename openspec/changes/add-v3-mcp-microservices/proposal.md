## Why

当前 KFile 代码库（`v2`）是一个单体 Spring Boot 应用，同时承载终端用户文件提交流程与管理后台。我们需要通过 Model Context Protocol（MCP）将 KFile 能力开放给 AI 助手——但若把 MCP、微服务路由与 Bearer Token 鉴权直接塞进线上 `v2`，会带来生产用户回归风险，并与现有的 session-cookie 鉴权模型相冲突。通过新开一条隔离的 `v3` 线（独立的 Maven 多模块构建、独立的 Nacos 命名空间、独立的部署），我们可以在并行环境下开发 MCP + 微服务架构，同时让 `v2` 继续无干扰地承载生产流量。

## What Changes

- 新增与 `v2` 并行运行的 `v3` 版本线。**不修改 `v2` 的部署、端口、镜像名、Nacos 配置、数据库表，以及任何 session/鉴权代码。**
- 将构建重组为 Maven 聚合工程，包含四个模块：
  - `kfile-common` —— 通用工具、错误码、常量。
  - `kfile-api` —— Feign DTO 与客户端（`ProjectRpcClient`、`SubmissionRpcClient`）。
  - `kfile-service` —— 迁移自现有后端（DB、OSS、HTTP API），并新增 `/internal/rpc/**` Facade。
  - `kfile-gateway` —— Spring Cloud Gateway + Spring AI MCP Server（Streamable HTTP），是唯一对外提供 MCP 的进程。
- 在根 POM 中引入 Spring Cloud、Spring Cloud Alibaba（Nacos Discovery/Config）以及 Spring AI 的 BOM。
- 引入 `com.kK-2004:kk-common:0.1.0-SNAPSHOT`（基础包 `com.kk2004.common.*`）作为公共底座，复用其统一响应（`TransDTO`、`PageResponse`）、异常体系（`BusinessException`、`SystemException`、`ErrorCode` + 全局异常处理器）、`RedisUtil`（cache-aside/穿透防护/SCAN 删除/List JSON 缓存）、`DistributedLock`（Redisson 看门狗）以及 `RequestContext` + `RequestContextFilter`（traceId/userId/staffId/clientIp）。本变更**不**重复实现这些能力。
- `kfile-gateway` 暴露 `/mcp/{namespace}`（`user`、`admin`），使用 **Bearer Token** 鉴权——**不复用** `v2` 的 session cookie。
- 新增 `v3` 的 Token 模型：`tokenHash`、`subject`、`roles`、`namespaces`、`enabled`、`expiresAt`、`lastUsedAt`，存放于**新建**的 Token 表，避免触碰 `v2` 的用户/角色表。
- MCP 工具（首批）：
  - `user` 命名空间：`kfile_create_project`、`kfile_validate_submitter`、`kfile_create_upload_urls`（包装 `direct-init`）、`kfile_complete_upload`（包装 `direct-complete`）。
  - `admin` 命名空间：`kfile_create_project`、`kfile_get_project`、`kfile_list_projects`。
  - 本轮**不**将 `direct-multipart-*` 包装为 MCP 工具，现有 HTTP 端点保持不变。
- 网关通过请求头向 RPC 透传上下文：`X-KFile-Actor`、`X-KFile-Roles`、`X-KFile-Client-IP`、`X-KFile-User-Agent`。`/internal/rpc/**` 仅接受来自 `v3-gateway` 的调用。
- 部署隔离：
  - 独立应用名：`kfile-v3-gateway`、`kfile-v3-service`。
  - 独立 Nacos 命名空间/分组：`kfile-v3`。
  - 独立入口：例如 `kfile-v3.example.com` 或 `/kfile-v3` 路径前缀；MCP 入口为 `/mcp/{namespace}`。
  - 新增 `docker-compose-v3.yml`（不替换 `docker-compose.yml`）。
  - **建议为 `v3` 使用独立的数据库/schema**；如必须共用一个数据库，则所有新增表都必须是新增、不可修改 `v2` 已拥有的列语义。

## Capabilities

### New Capabilities
- `mcp-gateway`：基于 Spring Cloud Gateway 的应用，内嵌 Spring AI MCP Server（Streamable HTTP），在 `/mcp/{namespace}` 终止 MCP 协议，承担 Bearer Token + 命名空间 + 工具的多级授权，并通过 OpenFeign 将工具调用转发到后端服务。
- `v3-bearer-auth`：`v3` 专属的 Bearer Token 鉴权机制（Token 签发、哈希、查找、角色/命名空间声明、过期、最近使用追踪），与 `v2` 的 session-cookie 鉴权完全独立。
- `internal-rpc-facade`：`kfile-service` 上的内部 HTTP Facade（`/internal/rpc/projects/**`、`/internal/rpc/submissions/**`），仅允许 `v3-gateway` 调用，通过 `X-KFile-*` 请求头接受 actor 上下文，并复用既有业务逻辑。
- `v3-deployment-isolation`：独立的构建/部署制品（应用名、Nacos 命名空间/分组、入口、docker-compose、可选独立 DB schema），使 `v3` 与 `v2` 并行运行而互不影响。

### Modified Capabilities
<!-- 无。v3 复用或拷贝 v2 业务逻辑，但不改变 v2 的规范级行为。现有四个 spec（admin-user-management、auth-mode-check、project-file-type-validation、standalone-docker-deploy）描述 v2 行为，本提案不予修改。 -->

## Impact

- **受影响代码**：新增顶层 Maven 模块；既有 `src/` 迁移到 `kfile-service/`。新建 `kfile-gateway/`、`kfile-api/`、`kfile-common/` 模块。既有 controller、service、repository、OSS 代码均被复用；在 `kfile-service` 中新增 `/internal/rpc/**` 控制器。
- **API**：
  - 新增：`POST/GET /mcp/{namespace}`（MCP Streamable HTTP）、`/internal/rpc/projects/**`、`/internal/rpc/submissions/**`。
  - 不变：所有现有前端 HTTP 端点（登录、项目 CRUD、提交、direct-init、direct-complete、direct-multipart-*）。
- **依赖**：新增 Spring Cloud、Spring Cloud Alibaba（Nacos）、OpenFeign、Spring AI MCP Server（Streamable HTTP）、Spring Cloud Gateway，以及 `com.kK-2004:kk-common`（GitHub Packages，需配置 `https://maven.pkg.github.com/kK-2004/kk-common` 仓库与读取 Token）。`v2` 部署沿用其当前依赖集合。
- **基础设施**：
  - 新增 Nacos 命名空间/分组 `kfile-v3`。
  - 新增 `v3` 入口（域名或路径前缀）。
  - 新增 `docker-compose-v3.yml`（以及/或独立的 K8s 清单）。
  - 推荐：为 `v3` Token 与 `v3` 专属表新建数据库 schema。
- **运维**：需要并行监控两条部署线（`v2` 与 `v3`）；独立的健康检查、日志、面板。Token 生命周期管理（签发/吊销）成为 `v3` 新增的运维面。
- **不在范围**：将 `v2` 用户迁移到 `v3`；下线 `v2`；将 `direct-multipart-*` 包装为 MCP 工具。
