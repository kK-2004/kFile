## Context

k-File 当前的对象存储由 `com.kk.oss.OssService`（默认 `AliOssService`）承载，服务于项目提交、归档、MCP 工具与 `/file/oss/**` 下载代理。SUPER 管理后台缺少直接的「文件管理」入口。本期新增 MinIO 作为可选第二数据源并配套 SUPER 文件管理页面。

**关键约束**：OSS 行为完全不变；MinIO 仅在 SUPER 后台使用；用户端提交、归档、MCP 链路继续走 OSS。

## Goals / Non-Goals

**Goals:**
- 后端：独立 `minio.*` 配置注入 MinIO，与 `oss.*` 并列；MinIO 缺省禁用、未配置不装配 bean。
- 后端：DB 维护的**虚拟多级文件树**（`stored_file`），前端选虚拟路径、后端在对象存储扁平存储，互不耦合。
- 后端：对象 storageKey 规则对齐用户端 OSS 直传（`SubmissionController.directInit`）：`<prefix>/<虚拟文件夹路径>/<yyyyMMddHHmmssSSS-uuid8>/<真实文件名.ext>`。
- 后端：SUPER 专属 `/api/admin/files/**`（列表/上传/下载签名/删除/建文件夹/分享）与 `/file/minio/**` 下载代理。
- 后端：浏览器直传（对称 OSS direct-init/complete）；>50MB 大文件断点续传（仅 MinIO，AWS SDK v2 S3 multipart + S3 ListParts 状态续传，不引入 Redis）。
- 前端：`AdminFiles.vue` 支持虚拟树导航 + 上传模态框（选存储源 + 队列面板 + 真实进度）+ 创建/删除/下载/分享。
- MinIO 可选：未配置时不装配 bean，前端无 MinIO 选项。

**Non-Goals:**
- 不改造用户端提交、归档任务、MCP 工具去用 MinIO。
- 不做移动/重命名、跨数据源复制/迁移。
- 断点续传仅 MinIO；OSS 大文件仍走单次 presigned PUT（两套 multipart API 完全不同，不统一）。
- 分享仅文件级（文件夹递归分享属后续迭代）。
- 不做 ACL / 多租户精细化权限（统一 SUPER 角色）。

## Decisions

### 1. 配置与装配：独立 `minio.*` + 条件 Bean
- `MinioProperties`（`minio.enabled/endpoint/access-key/secret-key/bucket/prefix/presigned-direct`），`@ConfigurationProperties(prefix="minio")`。
- `MinioConfig` 暴露 `MinioClient`，`@ConditionalOnProperty(value="minio.enabled", havingValue="true")`，未配置不装配；启动 ensureBucket。
- **Why not 复用 `oss.type` 切换**：用户要求 OSS 与 MinIO 并列共存；复用 `oss.type` 会让两者互斥替换默认 `OssService`，破坏现有链路。

### 2. DB 虚拟文件树（核心决策，替代最初的「镜像 MinIO 目录」）
- 新增 `StoredFile` 实体（`stored_file` 表）：`id/parentId(自引用 FK)/name/type(FOLDER|FILE)/storageSource/storageKey/originalName/size/contentType/createdAt/updatedAt`。
- 前端选「虚拟路径」（即 parentId 链）；后端在 MinIO 扁平存储（key 与路径无关）；下载/分享按 DB 记录的 `storageKey`+`storageSource` 取预签名。
- **Why DB 而非镜像目录**：对象存储无真目录，镜像方案只能靠 `.keep` 占位，实测导致「文件夹被当文件展示、点击无反应、前端误展示 MinIO 真实结构」。DB 虚拟树彻底解耦展示与存储，支持任意多级文件夹，无占位对象污染。

### 3. 对象 storageKey 规则对齐用户端 OSS 直传（真实文件名 + 一次性子目录）
- `storageKey = <rootPrefix>/<folderPath>/<yyyyMMddHHmmssSSS-uuid8>/<真实文件名.ext>`。
- `folderPath` = 从根到当前 parentId 的虚拟文件夹名字链（根目录为空）；`yyyyMMddHHmmssSSS-uuid8` = 一次性子目录防同名覆盖；真实文件名 = `baseName(originalName)`。
- **Why 真实文件名 not MD5**：完全对齐现有用户端 OSS 直传 `SubmissionController.directInit`——保留真实文件名，下载时用 `originalName` 设 Content-Disposition 还原文件名；一次性子目录已足够防同名覆盖。
- 下载/分享时按 DB 记录的 `storageKey` 取对象，`downloadUrl(key, download, expire, originalName)` 用 originalName 作下载文件名。

### 4. 抽象 `StorageBrowserService`，OSS/MinIO 双实现（浏览器直传）
- 接口保留：`sourceId()/sourceLabel()/downloadUrl(key,download,expire,downloadFilename)/delete(key)/stat(key)/presignedPutUrl(key,expire,contentType)`。
- 删除原镜像方案遗留的 `list(prefix)/upload(prefix,file)/mkdir(prefix,name)/putObject(...)`。
- `StorageBrowserRegistry` 按 `oss`/`minio` 分发（`downloadUrl`/`delete`/`stat`/`presignedPutUrl`）。
- **上传不经业务后端**：`initUpload` 签发 presigned PUT 直链 → 前端直接 PUT → `completeUpload` 回调 stat 取真实 size + 落库。对称 OSS direct-init/complete。

### 5. 上传存储源前端可选
- 顶部下拉选存储源（OSS/MinIO，仅显示已启用的），记忆到 `localStorage`（key `kfile.fileManager.uploadSource`），默认 minio。
- 上传时由请求带的 `source` 参数决定存到哪，`registry.get(source).presignedPutUrl(...)`；该源未启用时报 400。
- **Why 前端可选 not 后端全局配置**：用户明确要求前端可选并记忆，后端每次按请求 source 分发；去掉无意义的全局 `app.file-manager.upload-source`。

### 6. 管理接口与权限（SUPER）
- `AdminFilesController`（`/api/admin/files`），全部 `@PreAuthorize("hasRole('SUPER')")`：
  - `GET /sources` → 可用数据源列表
  - `GET /list?parentId=` → `{nodes:[StoredFileNode], path:[{id,name}]}`
  - `POST /mkdir {parentId, name}` → `StoredFileNode`
  - `POST /upload-init {parentId, source, originalName, contentType}` → `{storageKey, storageSource, putUrl, expireSeconds}`（≤50MB 单次直传）
  - `POST /upload-complete {parentId, storageSource, storageKey, originalName, contentType}` → `StoredFileNode`
  - `POST /upload-multipart-init` / `/upload-multipart-sign` / `/upload-multipart-part-complete` / `/upload-multipart-complete`（>50MB 断点续传，见 Decision 10）
  - `DELETE /{id}` → `{ok, deletedDb, failedObjects}`
  - `GET /download-url?fileId=&download=&expireSeconds=` → `{url}`
  - `POST /share {fileIds[], expireSeconds, filename?}` → `{code, expireAt}`
- `StoredFileNode` DTO **不暴露 storageKey**（安全）；ConflictException→409、IllegalArgumentException→400（既有 GlobalExceptionHandler）。
- **删除语义**：文件删 DB 行 + 对象；文件夹递归删全部子节点；对象删除失败记 warn 但不阻断 DB 删除，失败计数返回。

### 7. 下载代理 `/file/minio/**`
- `MinioProxyController`：默认 302 到 MinIO 预签名直链，`?proxy=1` 流式，`?download=1` 强制下载。
- SecurityConfig 新增 `.requestMatchers(HttpMethod.GET, "/file/minio/**").permitAll()`，与 OSS 一致。
- 下载主入口仍是 `download-url`（管理员/分享用）；代理路径给内嵌预览用。

### 8. 分享完全复用现有能力
- 现有 `ShareLinkService.create(projectId, filename, entries, expireSeconds)` + `ShareDownload.vue` 与存储无关（entries 预先烘焙 URL）。
- 新建 `StoredFileService.createShare(fileIds, expireSeconds, filename)`：对每个 FILE 经 `downloadUrl` 预签名 → `entries=[{u,f,s}]` → 调既有 `ShareLinkService.create(null, filename, entries, expireSeconds)`。
- 前端 `/share?s=<code>` 页面、过期 410、客户端 ZIP 打包下载——全部复用。

### 9. 依赖与兼容性
- `io.minio:minio:8.5.17`（与 drug-store 一致）：`mvn dependency:tree` 校验，aliyun-sdk-oss 自带 HTTP 客户端，MinIO 带 okhttp 4.12.0 + okio 3.6.0，无冲突。
- AWS SDK v2（`software.amazon.awssdk:s3` BOM 2.46.x + `url-connection-client`）：仅用于 MinIO S3 multipart；与 MinIO SDK 并存，各管各的（MinIO SDK 管 stat/presigned GET，AWS SDK 管 multipart + presign UploadPart + ListParts）。
- **不引入 Redis**：分片进度状态全在 MinIO（S3 multipart upload 的服务端状态），经 `ListParts(uploadId)` 查询已上传 part。uploadId 持久化在 DB `StoredFileUpload` 表。

### 10. 大文件断点续传（>50MB，仅 MinIO，无 Redis）
**触发与分片**：文件 >50MB 且存储源=minio 走分片；≤50MB 或源=oss 走单次 presigned PUT（Decision 4）。前端按 5MB 切片，`chunkId` 从 0 开始，`totalChunks = ceil(size/5MB)`，前端用 **SparkMD5** 流式增量计算整文件 MD5（作为幂等 key + 续传识别）。

**客户端选型（关键）**：MinIO Java SDK 8.5.17 **不暴露** S3 multipart（CreateMultipartUpload/UploadPart/CompleteMultipartUpload/ListParts）——只暴露 composeObject。经反编译验证 `MinioClient` 无这些公共方法。但 MinIO 服务端 S3 兼容，故引入 **AWS SDK v2**（`S3Client` + `S3Presigner`）对 MinIO 发 S3 multipart。`S3Presigner.presignUploadPart` 给前端签 chunk 直传 URL（partNumber=chunkId+1，S3 从 1 开始）。

**进度状态（无 Redis，全在 MinIO）**：
- `StoredFileUpload` 表持久化 uploadId/contentMd5/storageKey 等（contentMd5 唯一索引，作为幂等 key）。
- 续传时后端按 contentMd5 查表拿 uploadId → `S3Client.listParts(bucket, key, uploadId)` 查已上传 part 列表 → 返回 partNumber 集合给前端 → 前端只 PUT 未上传的 chunk。
- **Why not Redis bitmap**：S3/MinIO multipart 本身有服务端状态（ListParts 即查），无需额外状态组件；少一个依赖、少一处状态一致性维护。uploadId 在 DB 持久化兜底（ListParts 需 uploadId）。

**MD5 校验（SparkMD5）**：
- 前端 SparkMD5 算整文件 MD5，作为 `StoredFileUpload.contentMd5`（幂等 key + 续传识别同一文件）。
- **part 级完整性**：`CompleteMultipartUpload` 传每个 part 上传时返回的 ETag，MinIO 在 complete 阶段自动校验，不匹配拒绝——分片损坏/篡改天然被拦截。
- **为什么不用整文件 MD5 终检**：multipart 合并后目标对象 ETag 是复合值（`MD5(MD5(part1)‖...)+"-"+N`），无法与前端整文件 MD5 直接对比；唯一可靠方式是后端读回流式算（违背直传，不采用）。SparkMD5 的整文件 MD5 仅用于幂等/续传，不用于终检。

**持久化元数据（`StoredFileUpload` 表）**：与 `StoredFile` 分离（后者是文件树节点）。字段：`id / contentMd5(唯一索引，幂等 key) / storedFileId / uploadId(S3) / chunkKeyPrefix / storageKey(最终) / bucket / totalChunks / status(UPLOADING|UPLOADED|FAILED) / createdAt / updatedAt`。`StoredFile` 加 `status` 字段（UPLOADING/UPLOADED，默认 UPLOADED 用于非分片文件）。

**分片存储路径**（按用户要求）：
- chunk 路径：`<rootPrefix>/_chunks/<虚拟文件夹路径>/<timestamp-uuid>/<真实文件名-chunkId>`，时间戳在 init 时生成一次（同一文件所有 chunk 共用）。
- 合并后最终路径：`<rootPrefix>/<虚拟文件夹路径>/<timestamp-uuid>/<真实文件名.ext>`（复用 chunk 路径的 timestamp-uuid）。

**状态机流程**：
1. `upload-multipart-init {parentId, source, originalName, contentType, fileSize, totalChunks, contentMd5}` → 后端：按 contentMd5 查 DB 判断续传；全新则 `S3Client.createMultipartUpload` 拿 uploadId + 写 `StoredFileUpload`(UPLOADING) + 写 `StoredFile`(UPLOADING)；续传则按 uploadId `listParts` 返回已传 partNumber。返回 `{uploadId, chunkKeyPrefix, storageKey, totalChunks, uploadedParts:[{partNumber}]}`。
2. `upload-multipart-sign {contentMd5, chunkId}` → 查 uploadId → `S3Presigner.presignUploadPart(bucket, "<chunkKeyPrefix>/<chunkId>", uploadId, partNumber=chunkId+1)` → `{url, chunkId}`。
3. 前端 PUT chunk → 从响应头取 ETag（前端本地收集 `{chunkId, etag}`）。
4. `upload-multipart-complete {contentMd5, parts:[{chunkId, etag}...]}` → 后端校验 parts 数=totalChunks → `S3Client.completeMultipartUpload` 传 part 列表 → **MinIO 自动校验每个 part ETag** → 成功：`StoredFile` status=UPLOADED + size + `StoredFileUpload` status=UPLOADED；失败：abort + 报「分片校验不一致」+ 清 DB 记录。

**定时清理（回收放弃的上传）**：新增 `MultipartUploadCleanupTask`（`@Scheduled`，参考现有 `ShareCleanupTask`），扫描 `StoredFileUpload` status=UPLOADING 且 updatedAt 超过阈值（如 24h）的记录 → `S3Client.abortMultipartUpload` 释放 MinIO 未完成 multipart + 删 DB 记录 + 删对应 `StoredFile`(UPLOADING) 行。

## Risks / Trade-offs

- **[DB 行与对象存储对象不一致]** → 文件删除时先删对象再删 DB，对象删除失败仅 warn 记录不阻断 DB 删除，避免产生「DB 记录在但对象已删」的孤儿 DB 行；返回失败计数给前端。
- **[存储源未启用]** → 上传时 `registry.get` 抛 IllegalArgumentException → Controller 转 400，前端明确提示。
- **[同名文件夹/文件]** → Service 层校验同 parent 同名同 type，存在则报 409。
- **[分享 TTL 与预签名 TTL 联动]** → 复用现有约定：分享接口对每个文件用同一 `expireSeconds` 预签名，保证二者同步过期。
- **[大文件]** → ≤50MB 走单次 presigned PUT；>50MB（仅 MinIO）走 S3 multipart 断点续传。OSS 大文件本期仍单次 PUT（Non-Goal 统一两套 multipart）。
- **[分片上传中断恢复]** → 进度状态全在 MinIO（S3 multipart 服务端状态）：续传时 `ListParts(uploadId)` 查已传 part，前端只传剩余；uploadId 持久化在 DB `StoredFileUpload`（contentMd5 唯一索引定位）。无需 Redis。
- **[放弃上传的资源泄漏]** → 用户放弃上传时 StoredFileUpload 记录 + MinIO 未完成 multipart 会残留；`MultipartUploadCleanupTask` 定时（如 24h）扫描超时 UPLOADING 记录，`abortMultipartUpload` + 删 DB + 删 StoredFile(UPLOADING)。
- **[MinIO CORS]** → 浏览器直传 PUT chunk + 分享 fetch 预签名直链需 MinIO bucket 放行 CORS（PUT/GET/HEAD + Content-Type）；或设 `minio.presigned-direct=false` 走站内代理。docs/minio-storage.md 已说明。
- **[误删不可恢复]** → 前端删除二次确认，文件夹显示「将递归删除所有子项」；不引入软删除。

## Migration Plan

1. 后端先落地（配置 + 存储改造 + StoredFile + Service + Controller + 代理 + 断点续传 + 定时清理），MinIO 默认 `enabled=false`，不影响现网。
2. 部署侧补 `MINIO_*` 环境变量；需要时启用 MinIO。（无 Redis 依赖。）
3. 前端页面与导航上线。
4. **回滚**：`minio.enabled=false`（或不注入环境变量）→ MinIO bean 不装配、上传报 400、`/file/minio/**` 返回 404、文件管理树仍可浏览（既有 StoredFile 行）；断点续传相关 bean（S3Client/Presigner）同为 ConditionalOnProperty 不装配；无外部状态组件依赖（进度在 MinIO）。

## Open Questions

- 是否需要批量删除？本期 Non-Goal，逐个/递归删除即可；后续按需加批量接口。
- 文件夹分享是否需要？本期 Non-Goal（仅文件级）；后续可递归收集 FILE 预签名。
- OSS 大文件断点续传？本期 Non-Goal（OSS 用 AliOssService 既有 initiateMultipartUpload/uploadPart/complete，但与文件管理直传链路分离，统一成本高）；后续按需。
