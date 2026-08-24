## ADDED Requirements

### Requirement: appToken 鉴权
`/api/open/**` SHALL 通过独立的 STATELESS 安全链鉴权：请求携带 `Authorization: Bearer <appToken>`，系统按 token SHA-256 哈希查找应用并校验启用状态；失败返回 `401` + `ApiError{message}`。应用身份与 AdminUser 会话体系 MUST 完全隔离。

#### Scenario: 有效 token 通过
- **WHEN** 请求携带已启用应用的有效 appToken 调用开放接口
- **THEN** 系统以该应用身份执行（`ROLE_OPEN_APP`），返回 `200`

#### Scenario: 缺失或无效 token 被拒绝
- **WHEN** 请求无 Authorization 头、格式错误、token 未注册、已轮换或应用已禁用
- **THEN** 系统返回 `401 Unauthorized`，响应体为 `ApiError{message}`

#### Scenario: 应用身份与管理身份互不可达
- **WHEN** 仅携带 appToken 请求 `/api/admin/**`
- **THEN** 系统返回 `401`（无 session）
- **WHEN** 仅携带管理员 session cookie 请求 `/api/open/**`
- **THEN** 系统返回 `401`（无 Bearer token）

### Requirement: 数据源路由
开放 API 的每个请求 SHALL 支持可选 `source` 参数：为空时使用系统设置的默认数据源（未配置默认 `oss`）；非空时 MUST 为已启用数据源，否则 `400`。

#### Scenario: 不传 source 使用默认值
- **WHEN** 应用调用上传初始化且未传 `source`，系统默认数据源为 `minio`
- **THEN** 对象写入 minio，响应的 `source` 为 `minio`

#### Scenario: 显式指定数据源
- **WHEN** 应用传 `source: "oss"` 且 oss 已启用
- **THEN** 操作路由到 oss

#### Scenario: 未知或未启用数据源被拒绝
- **WHEN** 应用传 `source: "minio"` 但 MinIO 未启用
- **THEN** 系统返回 `400 Bad Request`，message 说明未知或未启用的数据源

### Requirement: 预签名简单上传（直传）
系统 SHALL 提供两步直传：`POST /api/open/uploads`（body：originalName、contentType?、size?、path?、source?）返回 `putUrl`（预签名 PUT 直链）、`storageKey`、`fileId`、`expiresIn`；客户端直传对象存储后 `POST /api/open/uploads/complete`（storageKey、source）登记完成。

#### Scenario: 初始化直传
- **WHEN** 应用请求上传初始化 `{originalName: "report.pdf", contentType: "application/pdf"}`
- **THEN** 系统返回预签名 putUrl 与 storageKey（形如 `<prefix>/<应用根路径>/<timestamp-uuid>/report.pdf`，应用根路径为管理端配置的 `rootPath`、未配置默认 `开放应用/<appName>`），并创建 `status=UPLOADING` 的 FILE 节点（`open_app_id` 归属该应用）

#### Scenario: 完成登记
- **WHEN** 客户端已 PUT 对象后请求 complete
- **THEN** 系统经 `stat()` 校验对象存在，回填真实 size 并置 `UPLOADED`，返回 fileId/name/size/contentType

#### Scenario: 对象不存在时完成被拒绝
- **WHEN** 客户端未上传对象即请求 complete
- **THEN** 系统返回 `400 Bad Request`

#### Scenario: 非法 path 被拒绝
- **WHEN** 初始化请求的 `path` 含 `..`、`/` 起止异常或非法片段
- **THEN** 系统返回 `400 Bad Request`

#### Scenario: SUPER 可在文件管理中浏览应用文件
- **WHEN** 应用完成上传后，SUPER 用户打开文件管理的对应数据源
- **THEN** 应用根目录（管理端配置的 `rootPath`，或默认「开放应用/<appName>」）下可见该文件

### Requirement: 分片上传（按数据源能力）
系统 SHALL 对支持分片的数据源（首期 MinIO）提供分片直传：`multipart/init`（以 `contentMd5` 为幂等 key）→ `multipart/sign`（按 chunk 签发 URL，续传时返回已传 part 列表）→ `multipart/complete`（ETag 校验合并、回填 size）。

#### Scenario: 初始化分片上传
- **WHEN** 应用对 MinIO 源请求 `multipart/init {originalName, size, contentMd5, totalChunks}`
- **THEN** 系统返回 uploadId、chunkKeyPrefix、storageKey、totalChunks

#### Scenario: 断点续传
- **WHEN** 同一 `contentMd5` 再次 init，且此前已成功上传部分分片
- **THEN** 响应包含已上传的 `uploadedParts`（chunkId + etag），客户端跳过这些分片

#### Scenario: 完成合并
- **WHEN** 所有分片上传完毕后请求 `multipart/complete` 并携带各分片 ETag
- **THEN** 系统合并对象、登记 FILE 节点（`UPLOADED`，归属应用），返回 storageKey 与 fileId

#### Scenario: 不支持分片的数据源被拒绝
- **WHEN** 应用对不支持分片的数据源（如 oss）请求 `multipart/init`
- **THEN** 系统返回 `400 Bad Request`，message 说明数据源不支持分片上传

### Requirement: 预签名下载链接
系统 SHALL 提供 `POST /api/open/download-links`：入参 `fileId` 或 `storageKey + source`，可选 `filename`（覆盖 Content-Disposition）与 `expiresIn`（默认 300s，MUST clamp 到 [60, 3600]），返回限时预签名下载 URL。

#### Scenario: 按 fileId 获取下载链接
- **WHEN** 应用以有效 fileId 请求下载链接
- **THEN** 系统返回 `{url, expiresIn}`，URL 在有效期内可直接下载对象

#### Scenario: 按 storageKey + source 获取
- **WHEN** 应用回传上传响应中的 storageKey 与 source 请求下载链接
- **THEN** 系统返回该对象的预签名下载 URL

#### Scenario: expiresIn 边界收敛
- **WHEN** 请求 `expiresIn: 99999`
- **THEN** 系统按 3600 生成；请求 `expiresIn: 1` 时按 60 生成

#### Scenario: 文件不存在
- **WHEN** fileId 或 storageKey 无对应记录/对象
- **THEN** 系统返回 `404 Not Found`

#### Scenario: 指定下载文件名
- **WHEN** 请求携带 `filename: "月度报表.pdf"`
- **THEN** 下载时 Content-Disposition 使用该文件名

### Requirement: 开放端点限流
开放 API 端点 SHALL 应用 IP 维度限流（复用既有令牌桶），超频返回 `429`。

#### Scenario: 超频被限流
- **WHEN** 同一 IP 在短时间内超出限流阈值的并发/频率调用开放端点
- **THEN** 系统返回 `429 Too Many Requests`
