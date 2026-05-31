## Context

KFile `v2` 是一个目前在生产运行的单体 Spring Boot 应用，同时承载：
- 面向终端用户的前端能力（登录、项目查看、提交上传——包括通过 `direct-init` / `direct-complete` / `direct-multipart-*` 完成的 OSS 直传），以及
- 管理后台（项目 CRUD、用户管理、文件类型校验）。

鉴权基于 session cookie，存储基于 OSS。`openspec/specs/` 下已记录的能力包括：`admin-user-management`、`auth-mode-check`、`project-file-type-validation`、`standalone-docker-deploy`。

我们需要将 KFile 的一部分能力以 MCP 形式暴露给 AI 助手。最稳妥、也是干系人已经达成共识的路径是——**不**对 `v2` 进行 MCP 改造，而是新开一条并行的 `v3` 线，使用微服务布局、Bearer Token 鉴权，并在 Spring Cloud Gateway 中内嵌 Spring AI MCP Server（Streamable HTTP）。

`v2` 继续以其现有部署、端口、Nacos 配置和数据库不变地运行。`v3` 在独立基础设施上并行运行，并且是唯一承载 MCP 的版本线。

干系人 / 约束：
- `v2` 的生产用户必须无任何回归。
- MCP 必须在单一进程（网关）上终止，以便集中执行鉴权、命名空间隔离与可观测性。
- 首批 MCP 工具集小而精，已完成端到端验证；`direct-multipart-*` 通过 MCP 暴露的需求被推迟。
- 业务逻辑尽量复用，仅在迁移成本明显低于抽象成本时才允许复制。

## Goals / Non-Goals

**Goals：**
- 搭建一条与 `v2` **运维隔离**的 `v3` 线：独立的构建、部署、Nacos 命名空间/分组、入口，以及（推荐的）数据库 schema。
- 将 `v3` 重构为 Maven 多模块聚合工程（`kfile-common`、`kfile-api`、`kfile-service`、`kfile-gateway`）。
- 让 `kfile-gateway` 成为**唯一的 MCP 终止点**，使用 Streamable HTTP，进行 Bearer 鉴权、按命名空间隔离的工具注册表，并通过 Feign 向 `kfile-service` 扇出。
- 提供 `user` 与 `admin` 两个 MCP 命名空间，工具集合按提案约定。
- 引入 `v3` 专属的 Bearer Token 模型（与 `v2` session 完全分离），并使用与 `v2` 用户/角色表无关的 Token 表。
- 在 `kfile-service` 暴露 `/internal/rpc/**`，**仅** `v3-gateway` 可调用，actor 上下文通过 `X-KFile-*` 请求头透传。

**Non-Goals：**
- 修改 `v2` 的部署、配置、代码、数据库表。
- 将 `v2` 用户或 session 迁移到 `v3` Bearer Token（本变更不做 SSO 桥接）。
- 下线 `v2` 或任何 `v2` 的 HTTP 端点。
- 将 `direct-multipart-*` 包装为 MCP 工具（推迟到后续变更）。
- 提供完整的管理员 Token 签发 UI/API（本轮 Token 引导可通过手动 SQL/CLI 完成；正式管理界面属于后续工作）。
- 重构 OSS、文件类型校验引擎或任何持久化层。

## Decisions

### D1. 并行 `v3` 线，而非原地演进
**选择：** 把 `v3` 作为并行的代码库/分支与并行的部署来构建，而不是对 `v2` 进行重构。

**理由：** 本变更引入 Spring Cloud、Spring Cloud Alibaba、OpenFeign、Spring Cloud Gateway、Spring AI 以及一套全新的鉴权模型。把这些塞进现存单体会带来 classpath 冲突、与 session 鉴权的意外耦合，以及对生产用户的部署回归风险。

**已考虑的备选：**
- *原地演进*（在 `v2` 中加 MCP 控制器与 Bearer Filter）：拒绝——会让 `v2` 的发布节奏被 MCP 工作绑架，模糊鉴权边界，且未来仍需做微服务拆分。
- *全新重写*：拒绝——会无谓重复已经稳定运行的 OSS/上传/校验逻辑；`v3` 应当复用既有 `kfile-service` 业务代码。

### D2. 模块布局
**选择：** Maven 聚合工程，含四个模块：
- `kfile-common`：纯工具、错误码、常量。不引入 Spring Boot Starter。**依赖 `com.kK-2004:kk-common`**，沿用其 `TransDTO`/`PageResponse`/`ErrorCode`/`BusinessException`/`SystemException` 等通用类型；`kfile-common` 只增加项目专属的错误码枚举与常量。
- `kfile-api`：Feign DTO 与 `@FeignClient` 接口（`ProjectRpcClient`、`SubmissionRpcClient`）。依赖 `kfile-common`。Feign 的请求/响应统一以 `kk-common` 的 `TransDTO<T>` / `PageResponse<T>` 为外壳。
- `kfile-service`：原 `v2` 业务代码原样迁入，新增 `/internal/rpc/**` 控制器。依赖 `kfile-common`、`kfile-api`。`kk-common` 通过 Spring Boot 3 `AutoConfiguration.imports` 自动装配全局异常处理器、`RequestContextFilter`、`RedisUtil`、`DistributedLockFactory`，本模块不再自实现这些组件。
- `kfile-gateway`：Spring Cloud Gateway + Spring AI MCP Server。依赖 `kfile-common`、`kfile-api`，从 `kfile-api` 引入 Feign 客户端。复用 `kk-common` 的 `RequestContext` 在网关侧承载 `traceId`/`subject`，并复用 `BusinessException` 与全局异常体系把 MCP 错误统一成 `TransDTO.fail(...)` 或 MCP 错误响应。

**理由：** 把 DTO 从 `kfile-service` 中分离出来，使网关只需依赖小巧的 `kfile-api` 而无需引入服务代码；将基础设施关注点（网关）与业务关注点（服务）解耦。复用 `kk-common` 避免在 `kfile-common` 中重复造轮子，保持响应/异常/Redis/分布式锁/请求上下文与 `kK-2004` 生态其它项目一致。

**已考虑的备选：**
- *单 Spring Boot 模块 + 多 Profile*：拒绝——网关与服务的 starter、端口、部署生命周期都不一样。
- *三模块（无 `kfile-common`）*：拒绝——DTO 与共享错误码需要某处归宿；`kfile-common` 提供了无依赖的归宿。

### D3. MCP 传输：网关内的 Streamable HTTP
**选择：** Spring AI MCP Server（Streamable HTTP）**内嵌于** Spring Cloud Gateway，路径 `/mcp/{namespace}`，其中 `namespace ∈ {user, admin}`。

**理由：** Streamable HTTP 是当前推荐的 HTTP 前置 MCP 传输方式；将其内嵌于网关可保证只有一处鉴权/授权边界。命名空间通过路径变量映射到**独立的工具注册表**；网关在鉴权之后再选择对应注册表。

**已考虑的备选：**
- *MCP Server 作为网关后的独立服务*：v1 阶段拒绝——多一跳，多一处鉴权面；网关本来就要读 Bearer Token。
- *仅使用 SSE*：拒绝——Streamable HTTP 是更前向兼容的传输。

### D4. Bearer Token 鉴权，独立于 `v2` session
**选择：** 新建 `v3_api_token` 表：
```
token_hash      VARCHAR(64)   -- 原始 Token 的 SHA-256；原始 Token 不入库
subject         VARCHAR(128)  -- 逻辑 actor ID（user id 或 service id）
roles           VARCHAR(256)  -- 逗号分隔，例如 "user" 或 "admin,user"
namespaces      VARCHAR(256)  -- 逗号分隔的允许 MCP 命名空间，例如 "user" 或 "user,admin"
enabled         BOOLEAN
expires_at      TIMESTAMP NULL
last_used_at    TIMESTAMP NULL
created_at      TIMESTAMP
```

网关鉴权流程：
1. 提取 `Authorization: Bearer <token>`（缺失即拒绝）。
2. 计算哈希并查 `v3_api_token`（未命中 / 已停用 / 已过期 → 拒绝）。
3. 解析路径中的 `{namespace}`；不在 Token 的 `namespaces` 里 → 拒绝。
4. 解析工具注册表；该工具不允许该 Token 的 `roles` → 拒绝。
5. 异步、尽力而为更新 `last_used_at`。

**理由：** `v2` 的 session 鉴权基于 cookie，与前端来源、CSRF 模型紧耦合。MCP 客户端是无头的，需要长生命周期的 Bearer 凭据。独立表也意味着吊销 `v3` Token 不会意外让 `v2` 管理员掉线。

**已考虑的备选：**
- *复用 `v2` 用户表，并基于同一行签发 Token*：推迟——后续可做，但本轮不希望 `v3` 基础设施持有指向 `v2` 表的外键。`subject` 是自由字符串，未来桥接很容易实现。
- *JWT（无状态）*：v1 阶段拒绝——吊销也得依赖黑名单，而 DB 查找天然提供即时停用与最近使用统计。

### D5. `/internal/rpc/**` Facade 与 actor 透传
**选择：** `kfile-service` 暴露 `/internal/rpc/projects/**` 与 `/internal/rpc/submissions/**`。这些端点：
- 绑定到非公网监听器，或通过 `RemoteAddrFilter` + 共享密钥头 `X-KFile-Internal-Token` 限制为仅 `v3-gateway` 可调。
- 读取 `X-KFile-Actor`、`X-KFile-Roles`、`X-KFile-Client-IP`、`X-KFile-User-Agent`，并复用 `kk-common` 的 `RequestContext`/`RequestContextFilter`：将上述头映射到 `RequestContext` 的 `traceId`/`userId`/`staffId`/`clientIp` 字段（`X-KFile-Actor` → `userId`，`X-KFile-Client-IP` → `clientIp`，由网关生成的 `X-KFile-Request-Id` → `traceId`），其余 `v3` 专属字段（`roles`、`userAgent`）放入 `RpcActorContext`，在 `RequestContext` 之上构建。
- 复用与公开 HTTP 控制器相同的服务层代码路径（**不**复制业务逻辑，仅提供另一个控制器适配器）。
- 响应统一使用 `kk-common` 的 `TransDTO<T>` / `PageResponse<T>`，错误统一抛 `BusinessException` / `SystemException` 由 `kk-common` 全局异常处理器转译为 `TransDTO.fail(...)`，避免在 RPC 层重复实现错误体格式。

**理由：** `v3` 公开 HTTP 端点仍需为前端工作；`/internal/rpc/**` 是另一个适配器，而非另一份实现。复用 `kk-common` 的 `RequestContext` 可与 `kK-2004` 其它服务保持一致的链路追踪/审计语义，并让 Feign 端只需从 `TransDTO` 解包结果。

**已考虑的备选：**
- *gRPC*：v1 阶段拒绝——增加工具链负担；HTTP+Feign 足够支撑预期调用量。
- *进程内直调（合并网关与服务）*：拒绝——破坏部署隔离目标，并阻碍独立扩缩容。

### D6. MCP 工具集（首批）
- `user` 命名空间：`kfile_create_project`、`kfile_validate_submitter`、`kfile_create_upload_urls`（包装 `direct-init`）、`kfile_complete_upload`（包装 `direct-complete`）。
- `admin` 命名空间：`kfile_create_project`、`kfile_get_project`、`kfile_list_projects`。
- `direct-multipart-*` **不**包装。现有 HTTP 路径保留。

`kfile_create_project` 同时存在于两个命名空间，但**角色校验与默认项目可见性/配额策略不同**——admin 可以指定任意 owner；user 绑定到自身 subject 并受用户既有配额限制。

**理由：** 在不引入 multipart 流式复杂度的前提下，用最小工具集证明端到端 MCP 价值（项目 + 小文件提交）。

### D7. 部署隔离
- 应用名：`kfile-v3-gateway`、`kfile-v3-service`（Nacos service ID 与 Spring `application.name`）。
- Nacos 命名空间：`kfile-v3`；分组：`kfile-v3`。与 `v2` 现用值不重叠。
- 入口：独立域名（如 `kfile-v3.example.com`）**或**独立路径前缀（HTTP 用 `/kfile-v3/**`，MCP 用 `/mcp/**`）。无论选哪种，MCP 入口都是 `/mcp/{namespace}`。
- 新建 `docker-compose-v3.yml`。**不**修改既有 `docker-compose.yml`。
- DB：推荐新建 `kfile_v3` schema/database。如必须共用 DB，则所有新表以 `v3_` 前缀命名，且仅做新增。

**理由：** 命名、命名空间与入口的隔离能避免误打误撞的跨流量。独立的 compose 文件意味着回滚到仅 `v2` 状态非常简单：停止 `v3` stack 即可。

### D8. 可观测性
- 网关对每次 MCP 调用以 INFO 记录 `tool`、`namespace`、`subject`、`latency_ms`、`result`；失败以 WARN 记录，原因属于 `auth_invalid`、`namespace_denied`、`tool_denied`、`tool_error` 之一。
- `/internal/rpc/**` 携带由网关生成的 `X-KFile-Request-Id`，向服务层日志透传以便关联；该值同时写入 `kk-common` 的 `RequestContext.traceId`，使既有 `RequestContextFilter` 在 MDC 中输出 traceId。
- 健康检查端点：`kfile-v3-gateway` 与 `kfile-v3-service` 各自的 `/actuator/health`；都注册到 Nacos `kfile-v3` 命名空间。

### D9. 复用 `kk-common` 公共底座
**选择：** 引入 `com.kK-2004:kk-common:0.1.0-SNAPSHOT`（Java 包 `com.kk2004.common.*`）作为 `v3` 通用底座，覆盖以下职责：
- **统一响应**：所有 `/internal/rpc/**` 与 `v3` 公开 HTTP 接口返回 `TransDTO<T>` 或 `PageResponse<T>`。
- **异常体系**：业务错误一律抛 `BusinessException`（带 `ErrorCode`），系统错误抛 `SystemException`，由 `kk-common` 提供的全局异常处理器统一转译；网关侧把 Feign 反序列化得到的 `TransDTO.fail(...)` 还原成对应异常再映射为 MCP 错误。
- **请求上下文**：`RequestContextFilter` + `RequestContext` 承载 `traceId/userId/staffId/clientIp`；`v3` 网关在认证后写入这些字段，并通过 Feign 拦截器透传。`v3` 专属字段（`roles`、`userAgent`、`namespaces`）以 `RpcActorContext` 装饰 `RequestContext`，不做侵入式扩展。
- **Redis**：使用 `RedisUtil` 的 `queryWithPassThrough` 缓存 Token 查找结果，缓存 key `v3:token:{tokenHash}`，TTL 取自 `CacheExpireEnum`；启用穿透防护以抵御未知 Token 探测。
- **分布式锁**：使用 `DistributedLockFactory` 为可能并发的写场景提供锁（例如同一 `subject` 的项目创建去重），避免重复实现 SETNX。

**理由：** `kk-common` 已经在 `kK-2004` 其它服务中沉淀了响应/异常/Redis/锁/请求上下文的最佳实践（GenericJackson2JsonRedisSerializer、SCAN 删除、Redisson 看门狗等），重复造轮子会出现序列化、错误体、Trace 字段不一致的问题。

**已考虑的备选：**
- *仅在 `kfile-common` 内复制需要的类*：拒绝——后续每次 `kk-common` 升级都要再复制一次，且失去自动装配。
- *只用部分能力（如只用响应壳，不用 Redis/锁）*：拒绝——`kk-common` 自动装配后多出的 Bean 是按需启用的，不会强制使用，无负担。

**约束：** `kk-common` 当前以 `0.1.0-SNAPSHOT` 形式发布于 GitHub Packages，**未发布稳定版本**。`v3` 在 GA 前必须钉到一个非 SNAPSHOT 版本（由 `kk-common` 维护方打 tag 触发自动发布），否则会有构建不可重复的风险。

## Risks / Trade-offs

- **`v2` 与 `v3-service` 之间的代码重复** → 缓解：`v3-service` 起步阶段把当前 `src/` 拷入 `kfile-service/`，之后独立演进。把短期重复视为换取 `v2` 隔离所付出的成本；若后续重复带来真实压力，再做一次抽离即可。
- **同时维护两条线** → 缓解：分别建立面板、告警、Runbook。`v3` 以软启动入口起步，仅由显式签发的 Token 引流。
- **Token 泄露 = 在被吊销前可访问其完整命名空间** → 缓解：Token 仅以哈希入库，按 namespace 与 roles 限定作用域，支持 `expires_at`，每次请求都查 DB（而非 JWT）以让 `enabled=false` 立刻生效。后续可在网关增加按 Token 的限流。
- **Spring AI MCP Server 仍是较新的依赖** → 缓解：固定具体的 BOM 版本；集成测试覆盖两个命名空间下的 initialize / tools/list / tools/call；如若小版本破坏 API，则按住版本号。
- **`/internal/rpc/**` 暴露到错误网络** → 缓解：绑定到内部监听器，并要求每个请求携带 `X-KFile-Internal-Token` 共享密钥；在控制器之前的 `WebFilter` 处拦截。
- **`v2` 与 `v3` 共用 OSS 桶**（早期阶段大概率如此）→ 缓解：`v3` 的对象 key 使用独立前缀（如 `v3/`），便于生命周期策略和审计区分。`v3` 代码不触碰 `v2` key。
- **数据库隔离回退到“共享 DB”** → 缓解：评审约束所有 `v3` 迁移文件都放在 `kfile-service/src/main/resources/db/migration/v3/` 下，且仅创建 `v3_` 表；CI lint 校验拒绝改动 `v2` 表的迁移。
- **`kk-common` 当前为 `0.1.0-SNAPSHOT`，构建可能不可重复** → 缓解：在公司内部 Maven 仓库镜像该 SNAPSHOT，或推动 `kk-common` 维护方打稳定 tag；`v3` GA 前禁止依赖 SNAPSHOT 版本；POM 中固定 `<updatePolicy>never</updatePolicy>` 避免 CI 拉到不一致的 SNAPSHOT。
- **`kk-common` 自动装配带来非预期 Bean** → 缓解：上线前用 `--debug` 启动一次梳理 `Positive matches` 列表，对不需要的自动配置使用 `spring.autoconfigure.exclude` 显式关闭；与既有 `v2` 单体在 `application.yml` 中保持隔离配置文件。
- **GitHub Packages 拉取需要 token，CI 凭据管理** → 缓解：把 `GITHUB_ACTOR`/`GITHUB_TOKEN` 注入 CI/构建机的 `~/.m2/settings.xml`；本地开发提供 `docs/v3/build.md` 手册说明配置方式；不要把 token 提交到仓库。

## Migration Plan

1. **模块脚手架**（不改变行为）：引入聚合 `pom.xml`，建好四个模块，把现有 `src/` 移动到 `kfile-service/`。`v2` 的部署脚本与 Dockerfile 仍然从冻结的分支/标签构建**单体**——它们不会切换到多模块构建。
2. **接入 `kk-common`**：在根 POM 加入 GitHub Packages 仓库（`https://maven.pkg.github.com/kK-2004/kk-common`）与依赖；在 CI 配置 `GITHUB_ACTOR`/`GITHUB_TOKEN`；在 `kfile-service`、`kfile-gateway` 启动验证 `RequestContextFilter`、全局异常处理器、`RedisUtil`、`DistributedLockFactory` 等 Bean 自动注册成功。把 `v3` 公开 HTTP 与 `/internal/rpc/**` 的响应改为 `TransDTO`/`PageResponse`，业务异常切换到 `BusinessException`/`SystemException`。
3. **拉起 `v3` 基础设施**：建好 `kfile-v3` Nacos 命名空间/分组，建好 `kfile_v3` DB（或 `v3_` 表），发布初始的网关/服务配置。
4. **实现网关核心**：Streamable HTTP MCP 端点、Bearer Token 过滤器、命名空间注册表（首版可空）；用 `RequestContext` 写入 `traceId`/`userId`，用 `RedisUtil.queryWithPassThrough` 缓存 Token 查询。
5. **实现 `kfile-api` 客户端与 `/internal/rpc/**`**，覆盖项目和提交，统一以 `TransDTO` 包裹响应。
6. **接入 `user` 命名空间工具**，再接入 `admin` 命名空间工具。每个工具上线时附带一个针对真实 `kfile-service` 的集成测试。
7. **软启动**：在预发部署 `v3` stack，签发少量测试 Token，对照 proposal 中的测试计划进行验证。
8. **仅对 `v3` 做生产上量**（对 `v2` 无影响）：发布 `kfile-v3.example.com`，向首批使用方签发 Token。`v2` 仍是浏览器用户的标准入口。

**回滚方案：** `docker compose -f docker-compose-v3.yml down`，下线 `v3` 入口。`v2` 不受影响，因为本变更没有触碰它。

## Open Questions

- Token 签发体验：v1 阶段是手动 SQL 插入，还是提供一个最小化的 `POST /v3/admin/tokens` 端点？（倾向手动；GA 前再评估。）
- `v3` 管理员 Token 的命名空间声明是否允许同时为 `["user","admin"]`（超级 Token），还是必须只取其一？（当前设计：Token 可以携带多个命名空间，由网关按路径选择。）
- 是否把 `v2` user id 镜像到 `subject`，还是在真实桥接到来前使用 `v3-` 前缀字符串？（当前设计：`subject` 为自由字符串，第一波使用 `v3-<purpose>` 字符串。）
- OSS 桶：与 `v2` 共用并通过 `v3/` key 前缀区分，还是为 `v3` 单独建桶？（交由运维定夺；本设计两种方案都支持。）
- 是否在 `v3` GA 前推动 `kk-common` 发布稳定 tag（如 `0.1.0`）？SNAPSHOT 在生产构建中风险较高。（倾向：必须；在 GA 前协调 `kk-common` 维护方完成发版。）
- `kk-common` 的 `RequestContext` 字段（`userId`、`staffId`）与我们的 `subject`/`actor` 概念之间是否需要更明确的命名约定？（当前设计：`X-KFile-Actor` → `RequestContext.userId`；`staffId` 留空或填同值。）
