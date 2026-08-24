## ADDED Requirements

### Requirement: SUPER 可访问文件管理入口
系统 SHALL 在 SUPER 管理后台提供「文件管理」页面（路由 `/admin/files`），仅 `SUPER` 角色可见可访问；非 SUPER 用户 MUST 无法访问该页面及其后端接口。

#### Scenario: SUPER 用户看到文件管理导航
- **WHEN** 拥有 `SUPER` 角色的管理员登录后台
- **THEN** 顶部导航出现「文件管理」入口，点击进入 `/admin/files`

#### Scenario: 非 SUPER 用户被拒绝
- **WHEN** `ADMIN`（非 SUPER）用户调用任意 `/api/admin/files/**` 接口
- **THEN** 系统返回 `403 Forbidden`

#### Scenario: 未登录用户被拒绝
- **WHEN** 未认证用户调用 `/api/admin/files/**` 接口
- **THEN** 系统返回 `401 Unauthorized`

### Requirement: DB 虚拟多级文件树
系统 SHALL 用数据库维护虚拟多级文件夹结构（`stored_file` 表，parentId 自引用，type=FOLDER|FILE），与对象存储的真实扁平结构解耦；前端通过 parentId 导航任意多级文件夹。

#### Scenario: 列出根目录
- **WHEN** 用户请求 `GET /api/admin/files/list?parentId=`（parentId 为空）
- **THEN** 系统返回根级子项列表（文件夹在前，按名排序）+ 当前路径面包屑 `path`

#### Scenario: 进入子文件夹
- **WHEN** 用户请求 `GET /api/admin/files/list?parentId=5`
- **THEN** 系统返回 parentId=5 的下一级子项 + 从根到该节点的面包屑 `path`

#### Scenario: 对象存储扁平
- **WHEN** 用户上传文件到任意虚拟路径
- **THEN** 对象的 storageKey 为 `<prefix>/<虚拟文件夹路径>/<timestamp-uuid>/<真实文件名.ext>`（不含虚拟树层级的真实文件夹，但保留真实文件名 + 一次性子目录防覆盖），下载时用 originalName 还原文件名

### Requirement: 创建文件夹
系统 SHALL 支持在指定父目录下创建虚拟文件夹；同父目录下同名同 type MUST 被拒绝。

#### Scenario: 创建文件夹
- **WHEN** 用户请求 `POST /api/admin/files/mkdir {parentId: 5, name: "2026"}`
- **THEN** 系统在 parentId=5 下创建 type=FOLDER 的 StoredFile，返回该节点

#### Scenario: 同名冲突
- **WHEN** 用户在 parentId=5 下创建已存在的同名文件夹
- **THEN** 系统返回 `409 Conflict`

#### Scenario: 防止路径穿越
- **WHEN** 用户传入含 `/`、`\` 或 `..` 的 `name`
- **THEN** 系统 MUST 拒绝或剥离危险片段

### Requirement: 上传文件（浏览器直传，带进度）
系统 SHALL 支持将文件经**浏览器直传**到指定虚拟目录（文件不经业务后端）；上传目标目录由 parentId 决定，存储源由 source 参数决定；前端 MUST 展示真实上传进度。

#### Scenario: 上传到指定目录
- **WHEN** 用户请求 `POST /api/admin/files/upload-init {parentId:5, source:"minio", originalName:"report.pdf", contentType}`，浏览器按返回的 putUrl 直接 PUT 文件，再请求 `POST /api/admin/files/upload-complete`
- **THEN** 系统按 `<prefix>/<虚拟文件夹路径>/<timestamp-uuid>/report.pdf` 上传到对象存储 → 在 parentId=5 下创建 type=FILE 的 StoredFile（stat 取真实 size），返回该节点

#### Scenario: OSS 直传 Content-Type 签名一致
- **WHEN** 存储源=oss 且文件有 Content-Type
- **THEN** `upload-init` MUST 将 contentType 纳入预签名，浏览器 PUT 时 Content-Type 与签名一致（否则 OSS 校验签名失败 403）

#### Scenario: 上传源未启用
- **WHEN** 请求 source=minio 但 MinIO 未启用
- **THEN** 系统返回 `400 Bad Request`，提示存储源不可用

### Requirement: 下载文件
系统 SHALL 通过 DB 记录的 storageKey + storageSource 生成下载链接；MinIO 文件可通过 `/file/minio/**` 代理访问。

#### Scenario: 管理员获取下载链接
- **WHEN** 用户请求 `GET /api/admin/files/download-url?fileId=10`
- **THEN** 系统按该文件的 storageSource 经 registry 生成预签名直链或代理 URL，返回 `{url}`

#### Scenario: 代理路径公开访问
- **WHEN** 任意客户端 `GET /file/minio/<key>`
- **THEN** 系统默认 302 到预签名直链；`?proxy=1` 流式；`?download=1` 强制下载；未登录可访问

### Requirement: 删除文件/文件夹（递归）
系统 SHALL 支持删除单个文件或文件夹；文件夹 MUST 递归删除全部子节点；删除 MUST 同时清理对象存储真实对象与 DB 记录。

#### Scenario: 删除文件
- **WHEN** 用户请求 `DELETE /api/admin/files/10`（type=FILE）
- **THEN** 系统删除 MinIO 对象 + DB 行，后续 list 不再返回它

#### Scenario: 递归删除文件夹
- **WHEN** 用户请求 `DELETE /api/admin/files/5`（type=FOLDER）
- **THEN** 系统 MUST 递归删除其下所有文件（对象+行）与子文件夹

#### Scenario: 对象删除失败不阻断
- **WHEN** MinIO 删除某对象失败
- **THEN** 系统 MUST 仍删除对应 DB 行（避免孤儿 DB 行），并在响应中返回失败计数

### Requirement: 分享文件（复用既有分享能力）
系统 SHALL 复用既有 `ShareLinkService` + `ShareDownload.vue` 实现文件分享；管理员勾选多个文件生成分享链接，访问者经 `/share?s=<code>` 下载。

#### Scenario: 创建分享
- **WHEN** 用户请求 `POST /api/admin/files/share {fileIds:[10,11], expireSeconds:3600, filename:"batch.zip"}`
- **THEN** 系统对每个 FILE 预签名 MinIO URL → 构造 `entries=[{u,f,s}]` → 调 `ShareLinkService.create`，返回 `{code, expireAt}`

#### Scenario: 访问分享
- **WHEN** 访问者打开 `/share?s=<code>`
- **THEN** 现有 `ShareDownload.vue` 渲染文件列表、过期倒计时、打包下载（客户端 ZIP）

#### Scenario: 分享过期
- **WHEN** 分享的 expireAt 已过
- **THEN** 现有逻辑返回 `410 Gone`

### Requirement: 大文件断点续传（>50MB，仅 MinIO）
系统 SHALL 对存储源=minio 且 >50MB 的文件支持断点续传分片上传；采用 AWS SDK v2 对 MinIO 发 S3 multipart（CreateMultipartUpload/UploadPart/CompleteMultipartUpload/ListParts）；进度状态全在 MinIO（经 ListParts 查已传 part），**不引入 Redis**；uploadId 持久化在 DB `StoredFileUpload` 表（contentMd5 唯一索引定位）；前端用 SparkMD5 算整文件 MD5 作为幂等 key；OSS 与 ≤50MB 文件仍走单次 presigned PUT。

#### Scenario: 分片上传完整流程
- **WHEN** 用户上传 51MB 文件到 minio，前端按 5MB 切片（totalChunks=11，chunkId 0..10）并用 SparkMD5 算整文件 MD5
- **THEN** `upload-multipart-init` 创建 S3 multipart 拿 uploadId + 写 StoredFileUpload(UPLOADING) + 写 StoredFile(UPLOADING)；前端逐个 `upload-multipart-sign` 拿 presigned URL → PUT chunk → 收集 part ETag；全部 chunk 完成后 `upload-multipart-complete` 合并，StoredFile status=UPLOADED + StoredFileUpload status=UPLOADED

#### Scenario: 断点续传（基于 ListParts）
- **WHEN** 上传中断后重新发起同一文件（相同 contentMd5）
- **THEN** `upload-multipart-init` 按 contentMd5 查 DB 拿 uploadId → 调 `ListParts(uploadId)` 查 MinIO 已上传的 partNumber 集合 → 返回 `uploadedParts`；前端仅 PUT 未上传的 chunk

#### Scenario: 分片校验失败
- **WHEN** `upload-multipart-complete` 传入的 part ETag 与 MinIO 记录不一致（分片损坏/篡改）
- **THEN** MinIO 在 complete 阶段拒绝，系统 abort 该 multipart，返回「分片校验不一致，请重新上传」，清空 StoredFileUpload + StoredFile(UPLOADING) 记录

#### Scenario: 分片路径
- **WHEN** 在虚拟文件夹 `A/B/` 下分片上传 `report.pdf`
- **THEN** chunk 对象 key 为 `<prefix>/_chunks/A/B/<timestamp-uuid>/report.pdf-<chunkId>`（timestamp-uuid 在 init 时生成一次，所有 chunk 共用）；合并后最终 key 为 `<prefix>/A/B/<timestamp-uuid>/report.pdf`

#### Scenario: 分片进度可见
- **WHEN** 分片上传中
- **THEN** 前端 MUST 按已上传字节数/总字节数（或已完成 chunk 数/totalChunks）展示真实进度，非模拟进度

#### Scenario: ≤50MB 或 OSS 不分片
- **WHEN** 文件 ≤50MB 或存储源=oss
- **THEN** 走单次 presigned PUT（upload-init/upload-complete），不进入分片流程

#### Scenario: 放弃上传的资源回收
- **WHEN** 用户放弃上传（StoredFileUpload 留在 UPLOADING 状态超过阈值如 24h）
- **THEN** 定时清理任务 `MultipartUploadCleanupTask` 扫描到该记录 → 调 `abortMultipartUpload` 释放 MinIO 未完成 multipart → 删 StoredFileUpload + StoredFile(UPLOADING) 行
