## Why

当前系统仅支持阿里云 OSS 作为对象存储后端，SUPER 管理员缺少一个通用的「文件管理」入口直接浏览/上传/下载/删除文件。引入 MinIO 作为可选的第二数据源，并配套一个 SUPER 文件管理页面，提供自建/私有化对象存储能力与统一文件管理。

## What Changes

- **后端**：MinIO 作为可选数据源（与 OSS 并列共存，互不影响主 OSS 链路）。
  - 新增 `minio.*` 配置（endpoint/accessKey/secretKey/bucket/prefix/enabled），通过 `application.yml` + 环境变量驱动，参考 `drug-store` MinIO 用法。
  - 新增 DB 维护的**虚拟多级文件树**：`stored_file` 表（parentId 自引用，type=FOLDER/FILE），前端选「虚拟路径」、后端在 MinIO **扁平存储**，二者解耦。
  - 对象存储 key 规则**对齐用户端 OSS 直传**（`SubmissionController.directInit`）：`<prefix>/<虚拟文件夹路径>/<yyyyMMddHHmmssSSS-uuid8>/<真实文件名.ext>`（一次性子目录防同名覆盖，真实文件名保留，下载用 originalName 还原文件名）。
  - **浏览器直传**：上传不经业务后端，对称 OSS direct-init/complete——`upload-init` 签发 presigned PUT 直链，前端直接 PUT 到对象存储，`upload-complete` 回调 stat 取真实 size + 落库。
  - **大文件断点续传（>50MB，仅 MinIO）**：新增 AWS SDK v2（`software.amazon.awssdk:s3`）对 MinIO 发 S3 multipart（CreateMultipartUpload/UploadPart/CompleteMultipartUpload）；前端按 5MB 分片直传 + 用 SparkMD5 算整文件 MD5 作为幂等 key；**进度状态全在 MinIO**——续传时后端用 `ListParts(uploadId)` 查已传 part（uploadId 存 DB `StoredFileUpload` 表），**不引入 Redis**；complete 阶段 MinIO 自动校验每个 part 的 ETag；加定时清理任务回收超时未完成的上传（参考 `ShareCleanupTask`）。
  - 新增 `/api/admin/files/**` 管理接口（列表/上传/上传分片/下载签名/删除/建文件夹/分享），**仅限 SUPER 角色**。
  - 新增 `GET /file/minio/**` 下载代理（302 预签名直链 / 流式代理 / 强制下载），与 `/file/oss/**` 对称。
  - **分享复用**现有 `ShareLinkService` + `ShareDownload.vue`：新建 `/api/admin/files/share` 对选中文件预签名 MinIO URL → 喂给既有分享服务。
- **前端**：重写 `AdminFiles.vue`（SUPER），单一虚拟树 + 面包屑 + 上传模态框（选存储源 + 上传队列面板 + 真实进度）+ 文件夹/文件列表 + 创建/删除/下载/分享；全部基于现有 Element Plus。
- **依赖**：`pom.xml` 引入 `io.minio:minio:8.5.17`（与 drug-store 一致）、AWS SDK v2（`software.amazon.awssdk:s3` BOM + `url-connection-client`，用于 MinIO S3 multipart）。
- **配置**：`application.yml` + dev/prod 增加 `minio.*` 段；`deploy.sh`/`docker-compose.yml`/`docs/` 补齐 MINIO_* 环境变量。

## Capabilities

### New Capabilities
- `file-management`: SUPER 管理后台的通用文件管理能力——以 DB 虚拟多级文件夹组织文件，MinIO 扁平存储，支持目录浏览/创建文件夹/浏览器直传上传（>50MB 断点续传，基于 S3 ListParts 状态续传、SparkMD5 幂等 key）/下载/删除（递归）/分享（复用既有分享页）。

### Modified Capabilities
<!-- 无现有 capability 的需求级变更；MinIO 作为新数据源接入，OSS 提交/归档/MCP 链路完全不变 -->

## Impact

- **代码**：
  - 新增 `com.kk.config.MinioProperties`、`com.kk.storage.*`（MinioConfig / MinioStorageService / AliOssBrowserService / StorageBrowserRegistry / StorageKeys / StoredFile 实体与仓库与服务）、`com.kk.admin.controller.AdminFilesController`、`com.kk.file.MinioProxyController`。
  - 断点续传：新增 `MinioS3Config`（AWS S3Client + S3Presigner）、`MultipartUploadService`、`StoredFileUpload` 实体与仓库、`StoredFile.status` 字段、定时清理任务 `MultipartUploadCleanupTask`（回收超时 UPLOADING 记录）。
  - `SecurityConfig` 放开 `/file/minio/**` GET。
- **数据库**：新增 `stored_file` 表（+ `status` 字段）与 `stored_file_upload` 表（分片元数据，含 uploadId/contentMd5，`ddl-auto: update` 自动创建），无 schema 变更影响既有表。
- **API**：新增 `/api/admin/files/**`（SUPER，含 `upload-multipart-init/sign/part-complete/complete`）与 `/file/minio/**`（公开 GET）；无对现有 API 的破坏性变更。
- **依赖**：新增 `io.minio:minio:8.5.17`（okhttp 4.12.0 / okio 3.6.0，已校验与 aliyun-sdk-oss 无冲突）、AWS SDK v2（`software.amazon.awssdk:s3` + `url-connection-client`）。
- **配置**：`minio.*` 段；MinIO 缺省禁用。不引入 Redis（进度状态全在 MinIO，经 ListParts 查询）。
- **部署**：`deploy.sh`/`Dockerfile`/`docker-compose.yml`/`docs/` 补充 MinIO 环境变量说明。
- **范围限制**：本期仅在 SUPER 后台使用 MinIO；用户端提交、归档任务、MCP 工具等仍走 OSS，不变更。断点续传仅 MinIO 存储源，OSS 仍走单次直传。
