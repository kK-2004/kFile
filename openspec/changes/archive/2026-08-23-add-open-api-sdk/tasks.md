## 1. 数据模型与系统设置

- [x] 1.1 新增 `OpenApp` 实体与 `OpenAppRepository`：`open_app` 表（appName 唯一、tokenHash 唯一索引、description、rootPath 可空、enabled、lastUsedAt、createdAt/updatedAt），JPA 自动建表
- [x] 1.2 `StoredFile` 实体新增可空列 `open_app_id`（平铺列，不建 FK），与应用上传归属对齐
- [x] 1.3 `AppConfigService` 新增 key `OPEN_API_DEFAULT_SOURCE`；`AdminConfigController` GET/PUT 白名单接入该字段，保存时经 `StorageBrowserRegistry` 校验、未配置默认 `oss`
- [x] 1.4 新增 `OpenAppService`：创建（复用 `OAuthCrypto.generateOpaqueToken` 加 `kapp_` 前缀、SHA-256 落库、明文仅返回一次）、轮换（覆盖 tokenHash）、启停、列表（不含明文与哈希）；rootPath 逐段 `StorageKeys.safeName()` 校验并归一化（空/非法 400，空值=默认 `开放应用/<appName>`）；appName 冲突抛 409 语义异常
- [x] 1.5 `StorageBrowserService` 新增 `copy(fromKey, toKey)` 能力：`AliOssBrowserService`（CopyObject）与 `MinioStorageService`（copyObject）实现，供迁移搬运对象
- [x] 1.6 新增 `OpenAppMigrationService`：修改 rootPath 时同步迁移——遍历应用旧根下 FILE 节点（跳过活跃分片上传记录，计入 skipped）→ `copy` 到新 key + `stat()` 校验 → 全部成功后单事务（懒建新目录链、节点 reparent、storageKey 改写、清理搬空旧目录）→ best-effort 删除旧对象（失败告警不回滚）；任一 copy 失败清理已复制新对象并抛 500 保持原状；返回 `{moved, skipped}` 统计

## 2. 开放 API 鉴权链

- [x] 2.1 新增 `OpenAppPrincipal` 与 `OpenAppAuthFilter`（仿 `McpBearerAuthFilter`）：解析 Bearer → SHA-256 → 按 tokenHash 查 `open_app` → enabled 校验 → 写入 SecurityContext（`ROLE_OPEN_APP`）；鉴权成功节流更新 `lastUsedAt`（>60s）
- [x] 2.2 `SecurityConfig` 新增 `@Order(2)` `/api/open/**` 链（STATELESS、禁 CSRF、`anyRequest().hasRole("OPEN_APP")`、401 返回 `ApiError{message}`），oauth 链重排为 `@Order(3)`、web 链 `@Order(4)`
- [x] 2.3 鉴权单元/集成测试：有效 token 通过；缺失/未注册/已轮换/已禁用均 401；仅 appToken 访问 `/api/admin/**` 401；仅 session cookie 访问 `/api/open/**` 401

## 3. 开放文件 API（`/api/open/**`）

- [x] 3.1 新增 `OpenFileService` 目录解析：应用根路径 = `open_app.rootPath`（斜杠分隔逐段 `StorageKeys.safeName()`、懒创建幂等），未配置默认「开放应用/<appName>」；请求可选 `path` 逐段同样校验后追加其下；`resolveSource(source)`：空取默认配置、非法抛 `IllegalArgumentException`
- [x] 3.2 简单上传：`uploads` init（`StorageKeys.buildDirectUploadKey` + `presignedPutUrl` + 建 `UPLOADING` FILE 节点写 `open_app_id`）与 `uploads/complete`（`stat()` 校验回填 size 置 `UPLOADED`；对象缺失 400）
- [x] 3.3 分片上传：`uploads/multipart/init|sign|complete` 复用 `MultipartUploadService`（parentId 指向应用目录节点，登记补写 `open_app_id`，`contentMd5` 幂等续传）；不支持的数据源（非 minio）init 返回 400
- [x] 3.4 下载：`download-links`（fileId 优先，或 storageKey+source 回传校验；expiresIn clamp [60,3600] 默认 300；filename 覆盖 Content-Disposition；未找到 404）
- [x] 3.5 新增 `OpenFileController`（Bearer 应用身份）+ 端点 `@RateLimit(ip=true)` 粗粒度限流，错误语义走 `GlobalExceptionHandler`
- [x] 3.6 API 集成测试（MockMvc + mock 存储）：source 默认/覆盖/未启用 400；init/complete 生命周期与 UPLOADING→UPLOADED；非法 path 400；expiresIn 边界 clamp；下载 404

## 4. 管理端接口与前端

- [x] 4.1 新增 `OpenAppController`（`/api/admin/open-apps`：GET 列表、POST 创建、PUT `/{id}` 修改 description/rootPath——rootPath 变更经 `OpenAppMigrationService` 同步迁移后返回统计、POST `/{id}/rotate`、PUT `/{id}/enabled`；`@PreAuthorize("hasRole('SUPER')")`）
- [x] 4.5 删除应用（级联清理）：`GET /{id}/stats`（fileCount/totalBytes）、`DELETE /{id}` → `OpenAppService.deleteApp`（best-effort 删对象 + 删分片残留/活跃 abort + 删节点 + 清理搬空根路径目录（共享目录保留）+ 删应用记录，返回 deletedFiles/failedObjects）；前端删除按钮（强确认展示文件数与总大小）；服务测试（级联删除、共享目录保留、对象失败不阻断）
- [x] 4.2 管理端接口测试：SUPER 全通、ADMIN 403、未登录 401、创建重名 409、非法 rootPath 400、列表不含 token 明文/哈希；rootPath 迁移（成功统计、失败保持原状、活跃分片跳过）
- [x] 4.3 前端新增 `AdminOpenApps.vue`（SUPER）：列表（appName/description/rootPath/enabled/lastUsedAt）、创建对话框（可选 rootPath）与编辑对话框（修改 description/rootPath——保存时展示迁移中加载态，完成后提示 moved/skipped 统计）、轮换对话框（token 一次性展示 + 复制按钮）、启停开关；路由 `/admin/open-apps` 与导航入口
- [x] 4.4 `AdminSettings.vue` 新增「开放 API 默认数据源」下拉（候选来自既有 `GET /api/admin/files/sources`），接入 `PUT /api/admin/config`

## 5. Java SDK（`sdk/` 独立工程）

- [x] 5.1 创建 `sdk/pom.xml`（`com.kk:content-center-sdk`，Java 17、仅 Jackson 依赖、JUnit 5）与包结构 `com.kk.sdk`
- [x] 5.2 实现 `ContentCenterClient`（builder：baseUrl/appToken/超时）+ DTO（records）+ `ContentCenterException`（status + 解析 `ApiError{message}`；401 给出 token 轮换/禁用提示）；所有请求自动注入 Bearer
- [x] 5.3 实现简单上传编排 `upload(...)`：init → JDK HttpClient PUT 直传（携带 Content-Type）→ complete；PUT 失败抛异常且不调 complete
- [x] 5.4 实现分片上传 `uploadMultipart(...)`：默认 5MB 分片、`MessageDigest` MD5 幂等 key、按 init 返回的 uploadedParts 跳过已传、sign→PUT→complete
- [x] 5.5 实现下载链接方法 `getDownloadLink(...)`（fileId 或 storageKey+source、filename/expiresIn 透传）
- [x] 5.6 基于 JDK `com.sun.net.httpserver` 的 stub 集成测试：Bearer 注入、上传三步、分片续传跳过、错误解析
- [x] 5.7 编写 `sdk/README.md`（接入示例、契约说明、构建方式 `mvn -f sdk/pom.xml install`）

## 6. 文档与端到端验证

- [x] 6.1 编写 `docs/open-api.md`：鉴权说明、三组端点 curl 示例、source/默认数据源规则、应用 rootPath 配置与变更语义、错误码表、SDK 快速开始
- [ ] 6.2 端到端手工验证：创建应用→SDK 上传（oss 简单 + minio 分片断点续传）→ SUPER 文件管理可见→ SDK 下载链接可下载；修改应用 rootPath 验证存量文件同步迁移至新路径（对象与虚拟树一致、旧对象删除）；服务端 `mvn verify` 与前端 `npm run build` 全绿
