# Design: add-open-api-sdk

## Context

- 现状：内容中心已具备多数据源存储抽象 `StorageBrowserService`（oss / minio，`StorageBrowserRegistry` 按 sourceId 分发）、预签名 PUT/GET 直链、`StorageKeys` 统一 key 规则、`stored_file` DB 虚拟文件树（SUPER 文件管理用）、`AppConfigService`（`configs` 表 k/v 系统设置）。
- 安全架构：`SecurityConfig` 三条链——`@Order(1)` `/mcp`（OAuth bearer，STATELESS）、`@Order(2)` `/oauth2/**`、`@Order(3)` `/**`（Web session）。凭证管理已有成熟模式：`OAuthCrypto`（32 字节随机不透明 token + SHA-256 哈希落库，明文只出现一次）、`OAuthClientRegistration`（lastUsedAt/disabled 审计字段）。
- 约束：表结构靠 JPA `ddl-auto: update` 自动建；错误响应惯例 `ApiError{message}` + `GlobalExceptionHandler`；无统一 Result 包装、无 swagger；构建为单模块 Maven（Dockerfile/Jenkinsfile/deploy.sh 依赖现有 jar 路径）。
- 目标用户：内部其他 Java 应用（服务端对服务端），以 Maven 坐标引入 SDK 接入。

## Goals / Non-Goals

**Goals:**
- SUPER 管理后台配置开放应用（appName + appToken）：创建、轮换、启停、lastUsedAt 审计。
- 系统设置配置「开放 API 默认数据源」；开放 API 请求可选传 `source` 覆盖。
- 开放 API：预签名直传上传（简单 PUT + MinIO 分片断点续传）、预签名下载链接；上传文件登记进虚拟树，SUPER 可在文件管理中浏览。
- 官方 Java SDK（`sdk/` 独立 Maven 工程）：封装鉴权 + 上传（含分片续传）+ 下载链接。

**Non-Goals:**
- 不开放列表/删除/元数据查询/分享（首期仅上传与下载，按用户选型）。
- 不做 per-app 存储配额、per-app 限流（仅沿用 IP 维度注解限流做粗粒度防护）。
- 不做 OAuth 双 token（access/refresh）——单静态 appToken + 手动轮换足够。
- 不把 SDK 加入服务端 reactor 构建（独立构建，见决策 6）。
- 用户端提交、归档、MCP、分享链路零改动。

## Decisions

### 1. 凭证模型：单静态 appToken，哈希落库，仅明文展示一次
- `open_app` 表：`appName`（唯一）、`tokenHash`（SHA-256 hex，唯一索引）、`description`、`rootPath`（可空，SDK 上传虚拟根路径）、`enabled`、`lastUsedAt`、时间戳。
- token 明文 = `OAuthCrypto.generateOpaqueToken()`（43 字符 urlsafe base64），加 `kapp_` 前缀便于识别与密钥扫描；仅在创建/轮换响应中出现一次，落库只存哈希。轮换 = 覆盖 `tokenHash`，旧 token 立即失效。
- 删除 = 级联清理（SUPER，`DELETE /{id}`，二次确认前经 `GET /{id}/stats` 展示文件数与总大小）：遍历 `open_app_id` 名下 FILE 节点 → best-effort 删对象（失败计入 failedObjects 不阻断）→ 删分片残留记录（活跃分片先 abort）与节点 → 沿有效根路径清理搬空目录（共享目录自动保留）→ 删应用记录（token 立即失效）。停权不想动文件时仍用「禁用」。
- 为什么不做 OAuth：接入方是机器对机器的内部服务，无用户授权语义；MCP OAuth 链解决的是「用户代理授权」，复用它反而引入不需要的 consent/refresh 复杂度。

### 2. 鉴权：新增独立 SecurityFilterChain + `OpenAppAuthFilter`
- 链序重排：`@Order(1)` mcp → `@Order(2)` `/api/open/**`（新）→ `@Order(3)` oauth2 → `@Order(4)` web `/**`。matcher 互斥，重排无行为影响；open 链必须排在 web 兜底链之前。
- `OpenAppAuthFilter`（OncePerRequestFilter，仿 `McpBearerAuthFilter`）：取 `Authorization: Bearer <token>` → SHA-256 → 按 `tokenHash` 唯一索引查 `open_app` → `enabled=false` 视为无效 → 写入 SecurityContext（principal=`OpenAppPrincipal`，authority=`ROLE_OPEN_APP`）。失败不设认证，entry point 返回 401 `ApiError{message}`（复用 `RestAuthenticationEntryPoint`）。
- open 链 STATELESS、禁 CSRF、`anyRequest().hasRole("OPEN_APP")`；应用身份与 AdminUser 会话体系完全隔离——appToken 访问不了 `/api/admin/**`（无 session），管理员 cookie 也访问不了 `/api/open/**`（无 token）。
- `lastUsedAt` 节流更新：距上次 >60s 才写库，避免每请求一次 UPDATE。
- 粗粒度限流：开放端点加 `@RateLimit(ip=true)`（复用现有令牌桶 AOP）。

### 3. 数据源路由：可选 `source` + 系统配置默认值
- `AppConfigService` 新 key `OPEN_API_DEFAULT_SOURCE`，由 `AdminConfigController` 的 GET/PUT 维护（加入既有白名单 DTO）；保存与读取时均经 `StorageBrowserRegistry.get()` 校验，未配置默认 `oss`。
- 请求体可选 `source`：为空 → 取配置默认值；非空 → `registry.get(source)`，未知/未启用抛 `IllegalArgumentException` → 400（现有异常语义）。
- 默认源被禁用（如 minio 关闭后配置仍指向 minio）：请求时报「未知或未启用的数据源: minio」，管理端保存时校验降低发生概率。

### 4. 文件登记：复用 `stored_file` 虚拟树，新增 `open_app_id` 归属列
- `stored_file` 加可空列 `open_app_id`（对齐 `uploader_id` 平铺列风格，不建 FK）；应用上传的文件归属记录到应用。
- 目录结构：应用上传根目录取 `open_app.rootPath`（管理端配置，斜杠分隔虚拟路径，逐段 `StorageKeys.safeName()` 校验、懒创建幂等）；未配置时默认 `开放应用/<appName>`（固定根 `开放应用` 懒创建 + 每应用同名子目录，`appName` 唯一保证不冲突）。请求可选 `path` 参数（`a/b/c`，逐段同样校验）追加在根目录之下。
- `rootPath` 变更 = **同步全量迁移**：修改时后端把该应用名下全部已登记文件搬到新根路径，迁移全部完成后请求才返回（响应含 `moved/skipped` 统计）；不采用「只改配置不动文件」。
- 迁移实现：`StorageBrowserService` 新增 `copy(fromKey, toKey)`（OSS CopyObject / MinIO copyObject；S3 系无原子 rename）。流程：① 逐文件 copy 到新 key 并 `stat()` 校验 → ② 全部成功后单事务更新 DB（懒建新目录链、FILE 节点 reparent + storageKey 改写、删除搬空的旧目录节点）→ ③ best-effort 删除旧对象（失败仅告警不回滚，孤儿对象留待清理任务）。任一 copy 失败：清理已复制的新对象、DB 不动、返回 500，应用保持原 rootPath。
- 跳过项：活跃分片上传中的文件（关联未完成 `StoredFileUpload`）不迁移（搬移 `_chunks/` 会破坏续传状态），计入 `skipped` 并在响应列明，分片完成后可再次修改 rootPath 触发迁移。
- 允许多应用配置相同 rootPath（共享目录），文件归属仍由 `open_app_id` 区分。
- 新建 `OpenFileService` 组合 `StorageBrowserRegistry` + `StorageKeys` + `StoredFile` 仓库，**不直接复用** `StoredFileService.initUpload/completeUpload`——后者与 `AdminUser` uploader/配额耦合；应用上传跳过个人配额检查，登记时写 `openAppId`。storageKey 规则与既有直传一致：`<prefix>/<根路径段链>/<path>/<timestamp-uuid>/<真实文件名>`（根路径段链 = rootPath 或默认 `开放应用/<appName>`）。
- 简单上传生命周期对齐 SUPER 文件管理：init 建 FILE 节点（`status=UPLOADING`）→ 客户端 PUT 直传 → complete 时 `stat()` 校验对象存在并回填真实 size、置 `UPLOADED`。对象不存在 → 400。

### 5. 开放 API 契约（`/api/open/**`，除注明外均为 POST + JSON）
- 简单上传：`POST /api/open/uploads` `{originalName, contentType?, size?, path?, source?}` → `{storageKey, source, putUrl, expiresIn, fileId}`；`POST /api/open/uploads/complete` `{storageKey, source}` → `{fileId, name, size, contentType}`。
- 分片上传（仅支持的数据源，首期 MinIO，复用 `MultipartUploadService`，其 parentId 指向应用目录节点）：`POST /api/open/uploads/multipart/init`（`contentMd5` 作幂等/续传 key）→ `multipart/sign`（按 chunk 签 URL，续传时返回已传 part）→ `multipart/complete`（ETag 校验合并、回填 size）。不支持的数据源调用 init → 400「数据源不支持分片上传」。
- 下载：`POST /api/open/download-links` `{fileId | storageKey+source, filename?, expiresIn?}` → `{url, expiresIn}`；`expiresIn` 默认 300s、clamp 到 [60, 3600]；`filename` 覆盖 Content-Disposition。优先 `fileId`（DB 查 key+source，天然防越权猜测），`storageKey+source` 仅要求调用方回传上传响应原值。
- 错误契约沿用 `ApiError{message}`（401/403/400/404 由现有 handler 语义覆盖）；SDK 按此解析。

### 6. SDK：独立 Maven 工程 `sdk/`，不进服务端 reactor
- Maven 聚合要求根 pom 改 `packaging=pom` 并移动 `src/`，会破坏 Dockerfile/Jenkinsfile/deploy.sh 与 IDE 工程；而 SDK 与服务端零代码共享（纯 HTTP 客户端），无 reactor 必要。故 `sdk/` 自带 pom（groupId `com.kk`，artifactId `content-center-sdk`），`mvn -f sdk/pom.xml` 独立构建，现有构建/部署完全不动。
- 依赖仅 Jackson（databind）+ JDK `java.net.http.HttpClient`；字节码目标 Java 17（扩大可接入面，服务端仍 21）。测试用 JDK 内置 `com.sun.net.httpserver` 起 stub 服务，不引 MockWebServer。
- API 面：`ContentCenterClient`（builder：baseUrl/appToken/超时）→ `upload(Path|InputStream, UploadOptions)`（内部：init → PUT 直传 → complete）、`uploadMultipart(...)`（默认 5MB 分片、`MessageDigest` MD5、按 init 返回的 uploadedParts 续传）、`getDownloadLink(...)`；`ContentCenterException`（status + message）承载 `ApiError`。

### 7. 管理后台前端
- 新页 `AdminOpenApps.vue`（SUPER）：列表（appName/description/rootPath/enabled/lastUsedAt）、创建对话框（可选 rootPath）与编辑对话框（修改 description/rootPath——rootPath 变更触发同步迁移，保存时展示迁移中加载态与 moved/skipped 统计，`PUT /api/admin/open-apps/{id}`）、轮换对话框（token 一次性展示 + 复制按钮，关闭后不可再查）、启停开关；接口 `/api/admin/open-apps/**`（走既有 session 链 + `@PreAuthorize("hasRole('SUPER')")`）。
- `AdminSettings.vue` 增加「开放 API 默认数据源」下拉，候选复用既有 `GET /api/admin/files/sources`。

## Risks / Trade-offs

- [token 泄漏] → 仅哈希落库（日志/DB 无明文）、轮换即时失效、生产强制 HTTPS（既有约束）；仍无法阻止接入方泄露，文档要求按环境独立发 token。
- [简单直传 PUT 未完成 → UPLOADING 孤儿节点] → 与既有 SUPER 直传行为一致，文件管理中可见可删；自动清理任务列为后续（参考 `MultipartUploadCleanupTask` 模式）。
- [默认数据源配置漂移]（配置 minio 后关闭 minio）→ 保存时校验 + 运行时明确 400 报错。
- [多应用配置相同 rootPath] → 允许共享目录（文件归属靠 `open_app_id` 区分，管理端明示）。
- [rootPath 同步迁移在文件多/对象大时耗时长] → 请求阻塞至完成，可能触发网关/代理超时；管理端保存时展示迁移中加载态并在文档提示大文件量场景；异步任务 + 进度查询列为后续增强（当前按需求同步等待）。
- [迁移后旧对象删除失败] → best-effort 删除、失败仅告警；孤儿对象由后续清理任务回收（同分片清理模式）。
- [应用上传无配额约束] → 首期接受（内部应用）；`open_app_id` 列已为后续按应用统计/配额预留。
- [SDK 与服务端契约漂移] → 契约刻意保持小（3 组端点）；`sdk/README.md` 记录契约与版本对应；服务端契约变更走 openspec 流程。
- [每请求一次 token 哈希查库] → `token_hash` 唯一索引 O(1)；首期不做缓存（QPS 内部量级），必要时加 60s 缓存（与 lastUsedAt 节流同周期）。

## Migration Plan

1. 纯增量：新表 `open_app`、新列 `stored_file.open_app_id` 由 `ddl-auto: update` 自动创建；新过滤链、新页面、`configs` 新 key 懒生效（未配置走默认 `oss`）。
2. 无数据迁移、无破坏性变更；对未配置开放应用的部署，行为与现状完全一致。
3. 回滚：还原代码即可；遗留的空表/空列/配置项无副作用。

## Open Questions

- 无阻塞项。后续候选（不在本期）：per-app 配额与限流、开放列表/删除/元数据、简单直传孤儿自动清理、SDK 发布到私服（当前 `mvn install` 本地使用）。
