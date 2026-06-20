## Why

当前系统仅支持阿里云 OSS 作为对象存储后端，所有文件（项目提交、归档、预签名直链）都依赖单一云厂商。SUPER 管理员缺少一个通用的「文件管理」入口来直接浏览、上传、下载、删除已存储的对象，只能通过项目提交页或归档任务间接操作。引入 MinIO 作为可选的第二数据源，一方面提供自建/私有化对象存储能力（降低成本、数据自主），另一方面补齐 SUPER 后台的直接文件管理能力。

## What Changes

- **后端**：新增 MinIO 数据源支持，与现有 OSS 并列共存（互不影响）。
  - 新增 `minio.*` 配置项（endpoint / accessKey / secretKey / bucket），通过 `application.yml` + 环境变量驱动，参考 `/Users/kk/Desktop/实训/code/drug-store` 的 MinIO 用法。
  - 新增 `MinioStorageService`，实现面向文件管理的基础能力（列出目录、上传、下载、删除、创建文件夹），上传时的路径/文件名处理参考现有 `AliOssService`（日期分目录、baseName 防穿越、normalizePrefix）。
  - 新增 `/api/admin/files/**` 管理接口（列表/上传/下载签名/删除/建文件夹），**仅限 SUPER 角色**，通过 `@PreAuthorize("hasRole('SUPER')")` 控制。
  - 新增 `GET /file/minio/**` 下载代理路径，复用 `FileProxyController` 既有模式（302 到 MinIO 预签名直链 / 流式代理），与 `/file/oss/**` 对称。
  - `SecurityConfig`：`/file/minio/**` 与 `/file/oss/**` 同样放开 GET 访问；`/api/admin/files/**` 走默认 authenticated + 方法级 SUPER 校验。
- **前端**：新增 `AdminFiles.vue` 文件管理页面，挂在 SUPER 导航下。
  - 顶部数据源切换（OSS / MinIO），两个 bucket 独立浏览，互不影响。
  - 目录列表 + 面包屑导航；支持创建文件夹、上传文件、删除文件、下载文件。
  - 上传路径/文件名由后端统一生成（前端只传原始文件与目标目录）。
- **依赖**：`pom.xml` 引入 `io.minio:minio:8.5.17`（与 drug-store 保持一致版本）。
- **配置**：`application.yml` 增加 `minio.*` 段及其它 profile 文件占位；`deploy.sh` / docker / 环境变量文档补齐 MINIO_* 变量。

## Capabilities

### New Capabilities
- `file-management`: SUPER 管理后台的通用文件管理能力——在 OSS / MinIO 两个独立数据源间切换，对所选 bucket 进行目录浏览、创建文件夹、上传、下载、删除。

### Modified Capabilities
<!-- 无现有 capability 的需求级变更；MinIO 作为新数据源接入，OSS 行为完全不变 -->

## Impact

- **代码**：
  - 新增 `com.kk.config.MinioProperties`、`com.kk.storage.MinioStorageService`、`com.kk.admin.controller.AdminFilesController`，扩展 `FileProxyController`（或新增对称的 minio 代理分支）。
  - `SecurityConfig` 放开 `/file/minio/**` GET。
- **API**：新增 `/api/admin/files/**`（SUPER）与 `/file/minio/**`（公开 GET）。无对现有 API 的破坏性变更。
- **依赖**：新增 `io.minio:minio:8.5.17`（含其 okhttp 依赖，需与现有依赖兼容性校验）。
- **配置**：`application.yml` / `application-dev.yml` / `application-prod.yml` 新增 `minio.*` 段及环境变量（MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET）；MinIO 缺省禁用，仅在配置齐全时启用，未启用时前端隐藏 MinIO 数据源选项。
- **部署**：`deploy.sh`、`Dockerfile`、`docker-compose.yml`、`docs/` 需补充 MinIO 环境变量说明（可选用 MinIO 容器）。
- **范围限制**：本期仅在 SUPER 后台使用 MinIO，用户端提交、归档任务、MCP 工具等仍走 OSS，不变更。
