## ADDED Requirements

### Requirement: Go SDK 独立模块与轻量依赖

Go SDK SHALL 位于 `sdk/go/`，以独立 Go module 提供 `contentcentersdk` 包；其运行实现 MUST 仅使用 Go 标准库，不依赖服务端源码、Java SDK 或第三方 HTTP/JSON 客户端。

#### Scenario: Go module 可独立验证

- **WHEN** 在 `sdk/go/` 执行 `go test ./...`
- **THEN** Go SDK 编译并通过全部测试，且测试不要求启动内容中心服务端或连接真实对象存储

### Requirement: Go 客户端配置与鉴权

Go SDK SHALL 提供 `NewClient`，配置内容中心 `BaseURL`、`AppToken` 和可选的 `*http.Client`；所有内容中心 API 方法 SHALL 接收 `context.Context` 并自动携带 `Authorization: Bearer <appToken>` 与 JSON Content-Type。预签名对象存储 PUT 请求 MUST NOT 携带内容中心 Bearer token。

#### Scenario: API 请求注入 Bearer

- **WHEN** 使用 `NewClient` 调用 `InitUpload` 或任意其他内容中心 API 方法
- **THEN** 请求包含准确的 `Authorization: Bearer <appToken>`，并使用调用方提供的 context

#### Scenario: 预签名 PUT 隔离凭证

- **WHEN** `Upload` 或 `UploadMultipart` 向服务端返回的预签名 URL 发起 PUT
- **THEN** PUT 请求不包含 `Authorization`，只发送必要的 Content-Type 和文件字节

### Requirement: Go 简单上传

Go SDK SHALL 提供 `InitUpload`、`CompleteUpload`、`Upload` 和 `UploadReader`。完整上传 MUST 按 init → 预签名 PUT → complete 顺序执行；支持 `source`、`path`、`contentType` 等选项；预签名 PUT 返回非 2xx 时 MUST 返回错误且不调用 complete。

#### Scenario: 本地文件完成三步上传

- **WHEN** 调用 `Upload(ctx, filePath, options)` 且 init、预签名 PUT、complete 均成功
- **THEN** SDK 返回包含 `FileID`、`Name`、`Size`、`ContentType`、`StorageKey`、`Source` 的上传结果

#### Scenario: PUT 失败阻止确认

- **WHEN** 预签名 PUT 返回非 2xx
- **THEN** SDK 返回包含 PUT 状态码的 `ContentCenterError`，并且不向 `/api/open/uploads/complete` 发起请求

### Requirement: Go 分片上传与断点续传

Go SDK SHALL 提供 `InitMultipartUpload`、`SignMultipartPart`、`CompleteMultipartUpload` 和 `UploadMultipart`。`UploadMultipart` MUST 以整文件 MD5 作为 `contentMd5`，默认使用 5 MiB 分片；初始化返回 `alreadyDone=true` 时直接返回；对 `uploadedParts` 中已有的 1-based `partNumber` MUST 跳过 PUT，仅为缺失分片签名、上传并收集 ETag，最后提交完整的 0-based `chunkId`/ETag 列表。

#### Scenario: 首次分片上传

- **WHEN** 调用 `UploadMultipart` 上传文件且初始化没有已上传分片
- **THEN** SDK 按分片顺序为每个分片签名并 PUT，读取响应 ETag 后调用 complete，返回存储 key、file ID 和大小

#### Scenario: 续传跳过已有分片

- **WHEN** 初始化返回某些 `uploadedParts` 或 `alreadyDone=true`
- **THEN** SDK 对已有分片不重复 PUT；若 `alreadyDone=true` 则不签名、不 PUT、不重复 complete

### Requirement: Go 下载与媒体 CDN 预览

Go SDK SHALL 提供 `GetDownloadLink` 和 `GetCDNLink`。下载请求 MUST 支持 `fileId` 或 `key + source` 二选一，并透传可选 `filename` 与 `expiresIn`；CDN 请求 MUST 支持 `fileId` 与可选 `expiresIn`，并解析 `url`、`expiresIn`、`permanent`、`contentType`。

#### Scenario: 获取下载链接

- **WHEN** 调用 `GetDownloadLink(ctx, request)` 使用 file ID 或 storage key
- **THEN** SDK 返回服务端响应中的 URL 与有效期，不自行拼接或修改预签名 URL

#### Scenario: 获取媒体 CDN 链接

- **WHEN** 调用 `GetCDNLink(ctx, request)` 使用 file ID
- **THEN** SDK 返回稳定 CDN URL、有效期、永久标志和媒体 Content-Type

### Requirement: Go 错误语义

Go SDK 对内容中心或对象存储的非 2xx 响应 SHALL 返回 `ContentCenterError`，至少暴露 `Status` 与 `Message`，并优先解析 JSON `ApiError.message`；网络、序列化或响应解析失败 SHALL 使用 `Status == -1`，401 错误 SHALL 提示 appToken 可能已轮换或应用已禁用。

#### Scenario: 解析业务错误

- **WHEN** 内容中心返回 `400 {"message":"未知或未启用的数据源: minio"}`
- **THEN** 错误的 `Status` 为 400，`Message` 包含服务端 message

#### Scenario: 提示失效 token

- **WHEN** 内容中心返回 401
- **THEN** 错误 message 提示检查 appToken 是否无效、已轮换或应用已禁用
