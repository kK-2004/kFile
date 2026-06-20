## Context

k-File 当前的对象存储由 `com.kk.oss.OssService`（默认实现 `AliOssService`）承载，服务于项目提交、归档、MCP 工具与 `/file/oss/**` 下载代理。OSS 配置由 `OssProperties`（`oss.*`）+ 环境变量驱动，`@ConditionalOnProperty(value="oss.type", havingValue="ali", matchIfMissing=true)` 保证阿里云实现始终装配。

SUPER 管理后台（`/admin/*`，`AdminXxx.vue`，`App.vue` 顶部 `isSuper` 导航）当前只有项目/模板/用户/设置等模块，缺少直接的「文件管理」入口。用户希望新增 MinIO 作为第二数据源并配套一个 SUPER 文件管理页面，MinIO 的接入方式参考 `drug-store` 项目（`MinioConfig` + `MinioObjectStorageAdapter` + `io.minio:minio:8.5.17`）。

关键约束：**本期 OSS 行为完全不变**，MinIO 仅在 SUPER 后台使用，用户端提交、归档、MCP 等链路继续走 OSS。

## Goals / Non-Goals

**Goals:**
- 后端：以独立 `minio.*` 配置注入 MinIO，与 `oss.*` 并列；新增可复用的 MinIO 存储服务，覆盖目录浏览/上传/下载/删除/建文件夹。
- 后端：新增 SUPER 专属 `/api/admin/files/**` 管理接口与 `/file/minio/**` 下载代理（与 `/file/oss/**` 对称）。
- 前端：新增 `AdminFiles.vue`，支持 OSS / MinIO 两个独立 bucket 的切换浏览与文件操作。
- 上传路径/文件名规则与 `AliOssService` 保持一致语义（日期分目录、`baseName` 防穿越、`normalizePrefix`）。
- MinIO 可选：未配置时不装配 bean，前端隐藏该数据源。

**Non-Goals:**
- 不改造用户端提交流程、归档任务、MCP 工具去使用 MinIO。
- 不实现 OSS ↔ MinIO 跨数据源复制/迁移。
- 不做 MinIO 分片/断点续传（首期单次 PUT；大文件由 MinIO 服务端流式处理）。
- 不为 MinIO 引入独立的元数据表/数据库持久化（直读 bucket）。
- 不做 ACL / 多租户精细化权限（统一 SUPER 角色）。

## Decisions

### 1. 配置与装配：独立 `minio.*` + 条件 Bean
- 新增 `MinioProperties`（`minio.endpoint/access-key/secret-key/bucket/prefix/enabled`），`@ConfigurationProperties(prefix="minio")`。
- `MinioConfig` 暴露 `MinioClient` 与 `MinioStorageService`，标注 `@ConditionalOnProperty(value="minio.enabled", havingValue="true")`，未配置时不装配。
- **Why not 复用 `oss.type` 切换**：用户明确要求 OSS 与 MinIO 并列共存、各自独立 bucket 浏览；复用 `oss.type` 会让两者互斥替换默认 `OssService`，破坏现有提交流程。独立配置后 OSS 实现与链路零改动。

### 2. 抽象 `StorageBrowserService`，OSS/MinIO 双实现
- 新建接口 `com.kk.storage.StorageBrowserService`，方法：`List<Entry> list(prefix)`、`upload(prefix, file)`、`delete(key)`、`mkdir(prefix, name)`、`downloadUrl(key, download, expire)`、`stat(key)`。
- `Entry { name; isDir; size; lastModified; key; }`。
- 提供两个实现：`AliOssBrowserService`（复用现有 `AliOssService` 的 list 能力——需新增 `listObjects`，用 `OSSClient.listObjectsV2` + delimiter`/`）与 `MinioStorageService`（基于 MinIO `listObjects`/`putObject`/`removeObject`/`getPresignedObjectUrl`）。
- **Why not 让 `OssService` 直接长出 list 方法**：`OssService` 现有职责是面向「项目提交/代理下载」，混入目录浏览会污染接口；新接口聚焦文件管理，OSS 实现内部复用 `AliOssService` 已有的 `OSSClient`。
- 一个 `@Component` 工厂/Map（`StorageBrowserRegistry`，key="oss"/"minio"）按字符串数据源分发，便于 Controller 与前端通过单一参数切换。

### 3. 上传路径/文件名规则对齐 OSS
- 目录浏览以「前缀（prefix）」为当前路径，MinIO/OSS 均按 `prefix` 列对象，delimiter=`/`，区分「子目录」与「对象」。
- 上传：目标目录 `dirPrefix` → `normalizePrefix(dirPrefix)`（沿用 `AliOssService.normalizePrefix`，补全 `oss.prefix`/`minio.prefix` 根前缀并以 `/` 结尾）→ 文件名走 `baseName(originalName)`（剥离路径、移除 `..` 防穿越）→ 追加日期子目录 `yyyy/MM/dd/`（与 `AliOssService.upload` 一致）→ 拼最终 key。
- **建文件夹**：对象存储无真目录，按惯例上传一个 0 字节占位对象 `<prefix>/<name>/.keep`（OSS 与 MinIO 通用）。
- **同名覆盖**：保留对象存储默认覆盖语义（与 OSS 现有上传一致，不做去重）。

### 4. 管理接口与权限
- 新增 `AdminFilesController`（`/api/admin/files`），全部 `@PreAuthorize("hasRole('SUPER')")`：
  - `GET /api/admin/files/sources` → 返回可用数据源 `[{id:"oss",label:"OSS"},{id:"minio",label:"MinIO"}]`（仅返回已启用的）。
  - `GET /api/admin/files/list?source=&prefix=` → `Entry[]`（含子目录与文件）。
  - `POST /api/admin/files/upload?source=&prefix=`（multipart）→ 上传后的 key/代理 URL。
  - `POST /api/admin/files/mkdir` `{source, prefix, name}` → 占位对象 key。
  - `DELETE /api/admin/files?source=&key=` → 删除单个对象。
  - `GET /api/admin/files/download-url?source=&key=&download=&expireSeconds=` → 预签名直链或代理 URL。
- **Why SUPER-only**：文件管理可触及任意 bucket 内容，敏感度高；用户已确认仅 SUPER 可见。SecurityConfig 维持 `/api/admin/**` authenticated + 方法级 SUPER，无需新增 matcher。

### 5. 下载代理 `/file/minio/**`
- 在 `FileProxyController` 增加 `GET /file/minio/**` 分支（或新增 `MinioProxyController`），逻辑与 `/file/oss/**` 对称：默认 302 到 MinIO `getPresignedObjectUrl`，`?proxy=1` 走流式代理。
- SecurityConfig 新增 `.requestMatchers(HttpMethod.GET, "/file/minio/**").permitAll()`，与 OSS 一致。
- **Why 对称**：保持与 `/file/oss/**` 同构，前端可直接拼 `/file/minio/<key>` 做内嵌预览/下载，复用现有 302 模式不占带宽。

### 6. MinIO SDK 版本与依赖兼容性
- 采用 `io.minio:minio:8.5.17`（与 drug-store 一致）。其传递依赖 okhttp/okio 需与 spring-boot-starter-web（Tomcat）、aliyun-sdk-oss 的传递依赖在 `mvn dependency:tree` 中确认无冲突；如冲突用 `<exclusions>` 或显式版本锁定。

## Risks / Trade-offs

- **[MinIO 未启用时前端数据源空] → Mitigation**：`/api/admin/files/sources` 仅返回已启用数据源；前端在仅一个数据源时不显示切换器，OSS 必装保证永远 ≥1 个。
- **[OSS 缺少 list 能力需扩展现有实现] → Mitigation**：新增能力仅作用于 `StorageBrowserService.OSS` 实现，不修改 `OssService`/`AliOssService` 现有方法签名，零回归风险；用 `listObjectsV2` + delimiter 控制，限制单次 1000 条并支持翻页。
- **[大文件上传占带宽/超时] → Mitigation**：首期单次 PUT，超时沿用 `application.yml` multipart 配置；后续可在新接口上叠加分片（Non-Goal 本期）。
- **[删除无回收站，误删不可恢复] → Mitigation**：前端删除前 `el-message-box` 二次确认显示完整 key；后端不引入软删除（Non-Goal）。
- **[MinIO endpoint 暴露在预签名直链域名] → Mitigation**：直链域名由 MinIO endpoint 决定；若需隐藏，前端默认走 `/file/minio/**` 代理（302 仅在内网环境使用），可通过 `minio.presigned-direct=false` 强制代理。
- **[okhttp 与 aliyun-sdk-oss 依赖冲突] → Mitigation**：在 tasks 中要求实现后跑 `mvn dependency:tree` 校验，必要时 exclusions。
- **[目录占位对象 `.keep` 污染列表] → Mitigation**：`list` 实现过滤名为 `.keep` 且 size=0 的占位对象，仅将其所在前缀作为目录返回。

## Migration Plan

1. 后端先落地（配置 + 服务 + 接口 + 代理），MinIO 默认 `enabled=false`，不影响现网。
2. 部署侧补充 `MINIO_*` 环境变量到 `deploy.sh`/`Dockerfile`/`docker-compose.yml`，仅在需要时启用。
3. 前端页面与导航上线；OSS 数据源始终可见，MinIO 在 `sources` 返回时才出现。
4. **回滚**：将 `minio.enabled` 置回 `false`（或不注入环境变量），MinIO bean 不装配、接口返回 OSS 单源，前端自动退化为仅 OSS；无 DB schema 变更，无需数据回滚。

## Open Questions

- MinIO 是否需要支持公开 bucket 直读（endpoint 公网可达）？本期默认走预签名直链，公开读属 Non-Goal，后续按需开关。
- 是否需要批量删除？本期 Non-Goal，逐个删除即可；如后续需要再加 `DELETE /api/admin/files/batch`。
