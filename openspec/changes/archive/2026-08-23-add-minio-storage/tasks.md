## 1. 依赖与配置

- [x] 1.1 `pom.xml` 引入 `io.minio:minio:8.5.17`，`mvn dependency:tree` 校验与 `aliyun-sdk-oss`/okhttp/okio 无冲突
- [x] 1.2 新增 `com.kk.config.MinioProperties`（`@ConfigurationProperties(prefix="minio")`）：`enabled / endpoint / accessKey / secretKey / bucket / prefix / presignedDirect`
- [x] 1.3 `application.yml` 增加 `minio` 段（默认 `enabled: false`，值走 `${MINIO_*}`），dev/prod 留占位
- [x] 1.4 `deploy.sh` / `docker-compose.yml` 补充 `MINIO_*` 环境变量；`docs/` 增加 MinIO 容器与配置说明

## 2. 后端：MinIO 客户端（已完成，无需改动）

- [x] 2.1 `com.kk.storage.config.MinioConfig`：`MinioClient` Bean，`@ConditionalOnProperty(minio.enabled=true)`，ensureBucket
- [x] 2.2 `MinioProxyController`：`GET /file/minio/**`（302/流式/强制下载），未启用返回 404
- [x] 2.3 `SecurityConfig` 放开 `GET /file/minio/**`

## 3. 后端：存储层改造（DB 虚拟树 + 扁平存储）

- [x] 3.1 `StorageBrowserService` 接口瘦身：保留 `sourceId/sourceLabel/downloadUrl/delete/stat`，新增 `putObject(storageKey, MultipartFile)`，删除 `list/upload(prefix)/mkdir`
- [x] 3.2 `StorageKeys`：新增 `buildStorageKey(rootPrefix, md5Hex, originalName)` = `<rootPrefix>/<yyyy/MM/dd>/<md5>.<ext>` + `md5Hex`/`extension`；保留 `baseName/safeName`；删除镜像目录遗留辅助
- [x] 3.3 `MinioStorageService`：去掉 `list/mkdir/upload(prefix)` 镜像目录方法，实现 `putObject(storageKey, file)`；保留 `downloadUrl/delete/stat/proxyUrl/getObjectStream`
- [x] 3.4 `AliOssBrowserService`：同上改造
- [x] 3.5 `StorageBrowserRegistry`：保留 `sources/get/all`

## 4. 后端：DB 虚拟树

- [x] 4.1 新增 `com.kk.storage.entity.StoredFile`（`stored_file` 表）：`id/parentId/self-ref FK/name/type(FOLDER|FILE)/storageSource/storageKey/originalName/size/contentType/createdAt/updatedAt`；索引 `parent_id`、`(storage_source,storage_key)`
- [x] 4.2 新增 `StoredFileRepository`：`listChildren(parentId)`（处理 null）、`findByParentIsNullAndNameAndType`、`findByParentIdAndNameAndType`、`findByParentId`
- [x] 4.3 新增 `com.kk.storage.service.StoredFileService`：`availableSources`、`listChildren`（含面包屑）、`mkdir`（重名校验 409）、`upload(parentId,source,file)`（MD5→buildStorageKey→putObject→写 DB）、`delete`（递归，删对象+行）、`downloadUrl`、`createShare`（复用 ShareLinkService）

## 5. 后端：管理接口（重写 AdminFilesController）

- [x] 5.1 重写 `AdminFilesController`（`/api/admin/files`，类级 SUPER）：`GET /sources` → 可用数据源、`GET /list?parentId=` → `{nodes,path}`、`POST /mkdir {parentId,name}`、`POST /upload-init {parentId,source,originalName,contentType}` → `{storageKey,storageSource,putUrl,expireSeconds}`、`POST /upload-complete` → `StoredFileNode`、`DELETE /{id}` → `{ok,deletedDb,failedObjects}`、`GET /download-url?fileId=`、`POST /share {fileIds[],expireSeconds,filename?}`
- [x] 5.2 `StoredFileNode`/`StoredFileSource`/`UploadInitReq`/`UploadCompleteReq` DTO：不暴露 storageKey；ConflictException→409、IllegalArgumentException→400

## 6. 前端：API 与页面（重写）

- [x] 6.1 `api/index.js`：`adminFileSources()` / `adminFileList(parentId)` / `adminFileMkdir({parentId,name})` / `adminFileUploadInit({parentId,source,originalName,contentType})` / `adminFileUploadComplete(...)` / `directPutObject(putUrl,file,contentType,onUploadProgress)` / `adminFileDelete(id)` / `adminFileDownloadUrl(fileId,{download,expireSeconds})` / `adminFileShare({fileIds,expireSeconds,filename})`
- [x] 6.2 重写 `AdminFiles.vue`：parentId 驱动单一虚拟树；`list` 返回的 `path` 渲染面包屑（点击回溯）；`el-table` 区分文件夹📁(点击进入)/文件📄(带存储源 tag)；列：名称/大小/修改时间/操作
- [x] 6.3 上传存储源下拉：可选（默认 minio），记忆到 `localStorage`；上传源未启用时禁用上传按钮
- [x] 6.4 上传：浏览器直传 init→PUT→complete，`onUploadProgress` 真实进度面板（`el-progress` + 多文件列表）
- [x] 6.5 新建文件夹：`el-dialog` 输入名（`safeName` 校验由后端兜底）
- [x] 6.6 下载：调 `download-url` 触发下载
- [x] 6.7 删除：`el-message-box` 二次确认（文件夹显示「将递归删除所有子项」）
- [x] 6.8 分享：行勾选(FILE) + 单文件「分享」按钮 → `el-dialog` 选过期(5m/10m/30m/1h/1d/7d/30d) + 下载包名 → 调 `/share` → 显示 `/share?s=<code>` + 复制 + 再生成

## 7. 验证与回归

- [ ] 7.1 `build_project` 全量编译；`vite build` 通过
- [ ] 7.2 SUPER 全流程：建多级文件夹 → 导航 → 选存储源 → 上传(看进度) → 列表 → 下载 → 删除(递归)
- [ ] 7.3 MinIO 真实存储：对象 storageKey 为 `<prefix>/<虚拟文件夹路径>/<timestamp-uuid>/<真实文件名.ext>`；下载还原真实文件名；存储源选择记忆
- [ ] 7.4 分享：勾选文件 → 生成链接 → 无痕窗口打开 `/share?s=` → 下载 → 过期后 410
- [ ] 7.5 权限：ADMIN → 403；存储源未启用时上传报 400；OSS 提交/归档/MCP 链路无变化

## 8. 断点续传（>50MB，仅 MinIO，无 Redis）

### 8.1 依赖与配置
- [x] 8.1.1 `pom.xml` 引入 AWS SDK v2 BOM（`software.amazon.awssdk:bom`）+ `s3` + `url-connection-client`；`mvn dependency:tree` 校验与 minio SDK/okhttp 无冲突。**不引入 Redis**（进度状态全在 MinIO）
- [x] 8.1.2 前端 `package.json` 引入 `spark-md5`（流式增量 MD5）

### 8.2 后端：S3 客户端 + 进度表
- [x] 8.2.1 新增 `com.kk.storage.config.MinioS3Config`：`@ConditionalOnProperty(minio.enabled=true)`，装配 `S3Client` + `S3Presigner`（endpoint/pathStyle/region/credentials 与 MinioClient 一致）
- [x] 8.2.2 新增 `StoredFileUpload` 实体（`stored_file_upload` 表）：`id/contentMd5(唯一)/storedFileId/uploadId/chunkKeyPrefix/storageKey/bucket/totalChunks/status(UPLOADING|UPLOADED|FAILED)/createdAt/updatedAt` + repo（`findByContentMd5`、`findByStatusAndUpdatedAtBefore`）
- [x] 8.2.3 `StoredFile` 加 `status` 字段（varchar 16，UPLOADING/UPLOADED，默认 UPLOADED）

### 8.3 后端：分片服务与接口
- [x] 8.3.1 新增 `com.kk.storage.service.MultipartUploadService`：`init`（createMultipartUpload + 写 StoredFileUpload/StoredFile(UPLOADING) + 续传时 listParts 返回已传 part）、`sign`（presignUploadPart）、`complete`（校验 parts 数 + completeMultipartUpload + StoredFile(UPLOADED)）、`abort`
- [x] 8.3.2 续传逻辑：init 时按 contentMd5 查 DB StoredFileUpload → status=UPLOADED 直接返回成功；status=UPLOADING 则按 uploadId `listParts` 返回已传 partNumber 集合
- [x] 8.3.3 `StorageKeys` 加 `buildChunkKeyPrefix`/`buildMergedStorageKey`/`timestampUuid`（chunk 路径含 `_chunks`）
- [x] 8.3.4 `AdminFilesController` 加 3 端点：`POST /upload-multipart-init`（含 listParts 续传判断）/ `/upload-multipart-sign` / `/upload-multipart-complete`（前端本地收集 part ETag，complete 时一并提交，无需 part-complete 端点）

### 8.4 后端：定时清理
- [x] 8.4.1 新增 `MultipartUploadCleanupTask`（`@Scheduled`，参考 `ShareCleanupTask`）：扫描 StoredFileUpload status=UPLOADING 且 updatedAt 超过 24h 的记录 → `abortMultipartUpload` + 删 DB 记录 + 删 StoredFile(UPLOADING)

### 8.5 前端：分片上传 + 上传模态框
- [x] 8.5.1 上传改为模态框：点「上传文件」→ 弹模态框选存储源 + 拖拽/选择文件 → 队列面板（在模态框内）
- [x] 8.5.2 `api/index.js` 加 3 个 multipart API（`adminFileMultipartInit/Sign/Complete`）
- [x] 8.5.3 分片上传：fileSize>50MB 且 source=minio → SparkMD5 算整文件 MD5 → init → 循环(sign→PUT→收集 ETag) → complete；≤50MB 或 oss → 单次 init→PUT→complete
- [x] 8.5.4 真实进度：小文件用 `onUploadProgress` 的 `loaded/total`；大文件用 `已上传字节/总字节`
- [x] 8.5.5 断点续传：init 返回 uploadedParts 时复用 etag 跳过已上传 chunk，仅传剩余

### 8.6 验证
- [x] 8.6.1 `build_project` 全量编译 + `vite build` 通过
- [ ] 8.6.2 >50MB 文件分片上传（看真实进度）→ 中断 → 刷新续传（ListParts 查已传）→ complete 成功
- [ ] 8.6.3 MD5 校验失败场景（篡改 part etag → complete 被拒）
- [ ] 8.6.4 放弃上传 → 24h 后定时清理回收
- [ ] 8.6.5 ≤50MB 文件走单次直传
