## 1. Maven 聚合工程与模块脚手架

- [ ] 1.1 确认现在所在是v3分支
- [ ] 1.2 将根 `pom.xml` 改为 `<packaging>pom</packaging>` 聚合工程，并声明 `<modules>kfile-common, kfile-api, kfile-service, kfile-gateway</modules>`
- [ ] 1.3 在 `<dependencyManagement>` 中引入 Spring Cloud、Spring Cloud Alibaba、Spring AI 三套 BOM
- [ ] 1.4 在根 POM 的 `<repositories>` 中加入 GitHub Packages 仓库 `https://maven.pkg.github.com/kK-2004/kk-common`，并在 `<dependencies>` / `<dependencyManagement>` 中声明 `com.kK-2004:kk-common:0.1.0-SNAPSHOT`
- [ ] 1.5 在 CI 与本地构建文档中说明如何配置 `~/.m2/settings.xml` 的 `GITHUB_ACTOR` / `GITHUB_TOKEN`（`read:packages` 权限）；token 不进仓库
- [ ] 1.6 创建空的 `kfile-common` 模块（项目专属错误码枚举、常量；通用类型直接复用 `kk-common`，不重复实现 `TransDTO`/`PageResponse`/`BusinessException`）
- [ ] 1.7 创建空的 `kfile-api` 模块（Feign DTO 与 `@FeignClient` 接口；响应类型统一为 `TransDTO<T>` / `PageResponse<T>`）
- [ ] 1.8 创建 `kfile-service` 模块骨架，引入 `spring-boot-starter-web`、Nacos discovery 与 `kk-common`；将既有 `src/` 迁移到 `kfile-service/src/`
- [ ] 1.9 验证 `mvn -pl kfile-service -am package` 产出可运行 JAR，且启动时 `kk-common` 的 `RequestContextFilter`、全局异常处理器、`RedisUtil`、`DistributedLockFactory` 等 Bean 通过 Spring Boot 3 `AutoConfiguration.imports` 自动注册
- [ ] 1.10 创建 `kfile-gateway` 模块骨架，引入 `spring-cloud-starter-gateway`、Nacos discovery、OpenFeign、Spring AI MCP Server（Streamable HTTP）starter 与 `kk-common`；启动时校验 `RequestContextFilter` 在网关侧生效

## 2. Nacos 与配置隔离

- [ ] 2.1 建立 Nacos 命名空间 `kfile-v3` 与分组 `kfile-v3`（记录命名空间 ID 用于环境配置）
- [ ] 2.2 在 `kfile-gateway/src/main/resources/application.yml` 中设置 `spring.application.name=kfile-v3-gateway`
- [ ] 2.3 在 `kfile-service/src/main/resources/application-v3.yml` 中设置 `spring.application.name=kfile-v3-service`（过渡期保留原 application.yml 不动以兼容 v2 单体构建）
- [ ] 2.4 将两个模块的 Nacos discovery 与 config 指向 `kfile-v3` 命名空间/分组
- [ ] 2.5 在 Nacos `kfile-v3` 命名空间下发布初始 dataId（`kfile-v3-gateway.yml`、`kfile-v3-service.yml`）

## 3. 数据库与迁移

- [ ] 3.1 决策并记录：使用独立 `kfile_v3` 数据库 vs 共享 DB + `v3_` 表前缀
- [ ] 3.2 在 `kfile-service/src/main/resources/db/migration/v3/` 下接入迁移工具（Flyway 或等价物），仅作用于 `v3` 迁移
- [ ] 3.3 编写 `V3_001__create_v3_api_token.sql`，创建 `v3_api_token(token_hash, subject, roles, namespaces, enabled, expires_at, last_used_at, created_at)` 并对 `token_hash` 建索引
- [ ] 3.4 增加 CI lint/check：拒绝任何位于 `db/migration/v3/` 下、操作非 `v3_` 前缀表的迁移
- [ ] 3.5 在 `v3` 数据库上应用迁移并验证 `v3_api_token` 已存在

## 4. Bearer Token 鉴权（网关）

- [ ] 4.1 在 `kfile-gateway` 中定义 `V3ApiToken` JPA/MyBatis 实体，映射 `v3_api_token`
- [ ] 4.2 实现 `TokenLookupService`：对客户端 Token 计算 SHA-256，使用 `kk-common` 的 `RedisUtil.queryWithPassThrough` 按 `v3:token:{tokenHash}` 缓存查找结果（启用穿透防护）
- [ ] 4.3 实现网关 `WebFilter`（或 `GlobalFilter`）`BearerAuthFilter`：提取 `Authorization: Bearer`；缺失/格式错误 → 401；查找 Token；停用/过期/未知 → 401，原因 `auth_invalid`
- [ ] 4.4 实现 `NamespaceAuthorizationFilter`：读取 `{namespace}` 路径变量，若不在 Token 的 `namespaces` 中则返回 403，原因 `namespace_denied`
- [ ] 4.5 在 Token 停用/删除/过期改写处调用 `RedisUtil` 删除 `v3:token:{tokenHash}` 缓存条目，确保停用即时生效
- [ ] 4.6 实现异步 `last_used_at` 更新器；失败必须不影响请求，且以 WARN 记录
- [ ] 4.7 鉴权成功后将 `subject` 写入 `kk-common` 的 `RequestContext.userId`，把网关生成的 `request_id` 写入 `RequestContext.traceId`
- [ ] 4.8 在 `docs/v3/` 提供 dev/staging 环境签发首批 Token 的 CLI/SQL 操作手册
- [ ] 4.9 检查日志，确保任何位置都不会写出原始 Token 值（在过滤器层做脱敏）

## 5. MCP 服务装配（网关）

- [ ] 5.1 配置 Spring AI MCP Server 的 Streamable HTTP 传输，基础路径 `/mcp/{namespace}`
- [ ] 5.2 实现按 `namespace` 分桶的 `ToolRegistry`，分别为 `user`、`admin` 维护独立注册表
- [ ] 5.3 实现工具派发解析器：先按路径变量选择注册表，再按工具名解析
- [ ] 5.4 对未知命名空间（如 `/mcp/staff`）返回 HTTP 404
- [ ] 5.5 实现 `ToolAuthorizationFilter`：当 Token 的 roles 不满足该命名空间下工具所需角色时，返回 403，原因 `tool_denied`
- [ ] 5.6 工具调用时把 Feign 返回的 `TransDTO.fail(...)` 还原为 `BusinessException`（来自 `kk-common`），并映射为 MCP `tools/call` 错误响应（携带原始 `errorCode`、`message`）
- [ ] 5.7 为每次 `tools/call` 输出结构化 INFO/WARN 日志，包含 `request_id`、`namespace`、`tool`、`subject`、`latency_ms`、`result_status` / `reason`

## 6. 内部 RPC Facade（kfile-service）

- [ ] 6.1 在 `kfile-service` 中新增 `/internal/rpc/**` 控制器：`ProjectInternalRpcController`、`SubmissionInternalRpcController`，响应统一返回 `TransDTO<T>` / `PageResponse<T>`
- [ ] 6.2 实现 `InternalTokenFilter`：任何 `/internal/rpc/**` 请求若未携带合法 `X-KFile-Internal-Token` 共享密钥，一律 401 拒绝
- [ ] 6.3 将 `/internal/rpc/**` 绑定到非公网监听器（独立 connector / 管理端口），或在生产部署文档中明确所需的网络 ACL
- [ ] 6.4 复用 `kk-common` 的 `RequestContextFilter` 把 `X-KFile-Request-Id` → `RequestContext.traceId`、`X-KFile-Actor` → `RequestContext.userId`、`X-KFile-Client-IP` → `RequestContext.clientIp`；新增请求作用域 Bean `RpcActorContext` 承载 `X-KFile-Roles` / `X-KFile-User-Agent`；缺 `X-KFile-Actor` 时返回 400
- [ ] 6.5 重构既有服务层方法，使公开 HTTP 控制器与 `/internal/rpc/**` 控制器复用同一服务层路径（不重复业务规则）
- [ ] 6.6 实现 RPC 操作：项目 create/get/list、submitter 校验、upload-init（透传到现有 `direct-init` 服务代码）、upload-complete（透传到现有 `direct-complete` 服务代码）
- [ ] 6.7 业务错误统一抛 `kk-common` 的 `BusinessException`（带 `ErrorCode`），系统错误抛 `SystemException`，由 `kk-common` 全局异常处理器转译为 `TransDTO.fail(...)`；不在控制器中自定义错误体
- [ ] 6.8 验证 `/internal/rpc/submissions/upload-complete` 上的文件类型、大小、重复、允许名单校验，与公开端点完全一致

## 7. Feign 客户端（kfile-api）

- [ ] 7.1 在 `kfile-api` 中定义 DTO：项目 create/get/list、submitter 校验、upload-init、upload-complete；返回类型统一以 `TransDTO<T>` / `PageResponse<T>` 包装
- [ ] 7.2 定义 `ProjectRpcClient`：`@FeignClient(name="kfile-v3-service", path="/internal/rpc/projects")`
- [ ] 7.3 定义 `SubmissionRpcClient`：`@FeignClient(name="kfile-v3-service", path="/internal/rpc/submissions")`
- [ ] 7.4 在 `kfile-gateway` 中配置 Feign 拦截器：注入 `X-KFile-Internal-Token`，并从已认证的 MCP 请求透传 `X-KFile-Actor`、`X-KFile-Roles`、`X-KFile-Client-IP`、`X-KFile-User-Agent`、`X-KFile-Request-Id`
- [ ] 7.5 在 `kfile-gateway` 中配置 Feign `ErrorDecoder`：把 `TransDTO.fail(...)` 反序列化为 `BusinessException`（含 `ErrorCode`），交由工具层映射为 MCP 错误

## 8. MCP 工具——user 命名空间

- [ ] 8.1 实现 `kfile_create_project`（user）：强制以调用方为 owner，校验既有用户配额；调用 `ProjectRpcClient.create`
- [ ] 8.2 实现 `kfile_validate_submitter`：调用 submitter 校验 RPC，返回布尔值与原因
- [ ] 8.3 实现 `kfile_create_upload_urls`：通过 `SubmissionRpcClient.uploadInit` 包装 `direct-init`，返回预签名 PUT URL
- [ ] 8.4 实现 `kfile_complete_upload`：通过 `SubmissionRpcClient.uploadComplete` 包装 `direct-complete`；将服务错误以原始错误码/信息映射为 MCP 错误

## 9. MCP 工具——admin 命名空间

- [ ] 9.1 实现 `kfile_create_project`（admin）：允许指定 owner；按 admin 策略调用 `ProjectRpcClient.create`
- [ ] 9.2 实现 `kfile_get_project`：调用 `ProjectRpcClient.get`
- [ ] 9.3 实现 `kfile_list_projects`：调用 `ProjectRpcClient.list`，支持分页参数

## 10. 部署制品

- [ ] 10.1 创建 `kfile-gateway/Dockerfile` 与 `kfile-service/Dockerfile`
- [ ] 10.2 在仓库根目录创建 `docker-compose-v3.yml`，启动 `kfile-v3-gateway` 与 `kfile-v3-service`（本地开发可附带 Nacos）——**不**修改既有 `docker-compose.yml`
- [ ] 10.3 通过 `git diff` 验证 `docker-compose.yml`、`Dockerfile`、`Jenkinsfile`、`deploy.sh` 无任何功能性改动
- [ ] 10.4 配置专属入口：选择 `kfile-v3.example.com` 域名或 `/kfile-v3/**` + `/mcp/**` 路径前缀，确保 `/mcp/{namespace}` 可达

## 11. 测试

- [ ] 11.1 各模块单元测试通过：`mvn -pl kfile-common,kfile-api,kfile-service,kfile-gateway test`
- [ ] 11.2 集成测试：`/mcp/user` initialize → tools/list 返回四个 user 工具 → tools/call 完成创建项目、submitter 校验、申请上传 URL、完成上传（对接 OSS 模拟器或预发 OSS）
- [ ] 11.3 集成测试：`/mcp/admin` initialize → tools/list 返回三个 admin 工具 → tools/call 完成创建项目、获取项目、列表项目
- [ ] 11.4 鉴权负向用例：缺 Token → 401；非 Bearer → 401；未知 Token → 401；停用 Token → 401；过期 Token → 401
- [ ] 11.5 授权负向用例：仅 user 命名空间的 Token 调 `/mcp/admin` → 403 `namespace_denied`；通过多命名空间 Token 进入后用 user 角色调 admin 工具 → 403 `tool_denied`
- [ ] 11.6 RPC 负向用例：外部调用 `/internal/rpc/**` 不带 `X-KFile-Internal-Token` → 401；缺 `X-KFile-Actor` → 400
- [ ] 11.7 `kk-common` 集成验证：响应使用 `TransDTO`/`PageResponse` 序列化形态；抛 `BusinessException` 时 HTTP 体为 `TransDTO.fail(...)`；同一 Token 高频访问时 Redis 命中、DB 仅查一次；Token 停用后下一次请求即 401；网关日志含 `RequestContext.traceId` 与 `userId`
- [ ] 11.7 回归测试：`kfile-service` 既有 HTTP 端点（登录、项目 CRUD、提交、direct-init/complete、direct-multipart-*）全部通过
- [ ] 11.8 本地起栈：`docker compose -f docker-compose-v3.yml up`；验证网关健康、服务健康、双方均注册到 Nacos `kfile-v3` 命名空间
- [ ] 11.9 回滚演练：`docker compose -f docker-compose-v3.yml down`；确认 Nacos 中无 `v3` 服务，且 `v2` 不受影响

## 12. 文档与上线

- [ ] 12.1 在 `docs/v3/tokens.md` 中记录 Bearer Token 签发流程
- [ ] 12.2 在 `docs/v3/mcp.md` 中记录 MCP 客户端接入说明（Streamable HTTP 端点、命名空间、可用工具）
- [ ] 12.3 在 `docs/v3/ops.md` 中记录运维 Runbook：部署、健康检查、扩缩容、回滚
- [ ] 12.4 预发软启动：签发少量测试 Token，连续观测一周指标
- [ ] 12.5 生产上线仅 `v3` 入口；确认 `v2` 流量与指标不变
