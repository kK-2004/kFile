## ADDED Requirements

### Requirement: Python SDK 使用 uv 独立打包

Python SDK SHALL 位于 `sdk/python/`，通过 `pyproject.toml` 定义包元数据、Python 版本要求、运行依赖和 pytest 开发依赖，并提交 `uv.lock` 以支持锁定安装；导入包名 SHALL 为 `content_center_sdk`，不得依赖 Java SDK 或服务端源码。

#### Scenario: uv 环境可复现

- **WHEN** 在 `sdk/python/` 执行 `uv sync --locked` 后运行 `uv run pytest`
- **THEN** 依赖安装、包构建和全部测试通过，且测试不要求真实内容中心或对象存储

### Requirement: Python 客户端资源与鉴权

Python SDK SHALL 提供带类型标注的同步 `ContentCenterClient`，配置 `base_url`、`app_token` 和请求超时，支持上下文管理器以关闭 HTTP 资源。所有内容中心 API 请求 SHALL 自动携带 `Authorization: Bearer <app_token>`；预签名对象存储 PUT MUST NOT 携带该 Bearer token。

#### Scenario: API 请求注入 Bearer

- **WHEN** 在 `ContentCenterClient` 上调用任意内容中心 API 方法
- **THEN** 请求包含准确的 Bearer token、JSON Content-Type，并遵守客户端超时

#### Scenario: 预签名 PUT 隔离凭证

- **WHEN** `upload` 或 `upload_multipart` 向预签名 URL 发起 PUT
- **THEN** PUT 请求不包含 `Authorization`，只发送必要的 Content-Type 和文件内容

### Requirement: Python 简单上传

Python SDK SHALL 提供 `init_upload`、`complete_upload`、`upload` 和面向二进制文件对象的上传入口；完整上传 MUST 按 init → 预签名 PUT → complete 顺序执行，并支持 `UploadOptions` 的 source、path、content_type；预签名 PUT 非 2xx 时 MUST 抛出错误且不调用 complete。

#### Scenario: Path 文件完成三步上传

- **WHEN** 调用 `upload(pathlib.Path(...), options)` 且三步请求成功
- **THEN** SDK 返回包含 `file_id`、`name`、`size`、`content_type`、`storage_key`、`source` 的 `UploadResult`

#### Scenario: PUT 失败阻止确认

- **WHEN** 预签名 PUT 返回非 2xx
- **THEN** SDK 抛出包含 PUT 状态码的 `ContentCenterError`，并且不向 `/api/open/uploads/complete` 发起请求

### Requirement: Python 分片上传与断点续传

Python SDK SHALL 提供拆分式 multipart 方法与 `upload_multipart`；完整上传 MUST 以整文件 MD5 作为 `contentMd5`，默认使用 5 MiB 分片；初始化返回 `alreadyDone=true` 时直接返回；对 `uploadedParts` 中已有的 1-based `partNumber` MUST 跳过 PUT，仅上传缺失分片并提交完整的 0-based `chunkId`/ETag 列表。

#### Scenario: 首次分片上传

- **WHEN** 调用 `upload_multipart` 上传文件且没有已上传分片
- **THEN** SDK 为每个分片签名并 PUT，收集 ETag 后调用 complete 并返回上传结果

#### Scenario: 续传跳过已有分片

- **WHEN** 初始化返回已上传分片或 `alreadyDone=true`
- **THEN** SDK 不重复上传已有分片；`alreadyDone=true` 时不再签名、PUT 或 complete

### Requirement: Python 下载与媒体 CDN 预览

Python SDK SHALL 提供 `get_download_link` 和 `get_cdn_link`。下载请求 MUST 支持 file ID 或 key + source 二选一，并支持 filename、expires_in；CDN 请求 MUST 支持 file ID 与可选 expires_in，并解析 URL、有效期、永久标志和媒体 Content-Type。

#### Scenario: 获取下载链接

- **WHEN** 调用 `get_download_link(DownloadLinkRequest(...))`
- **THEN** SDK 返回服务端签发的 URL 与 expires_in，不自行修改 URL

#### Scenario: 获取媒体 CDN 链接

- **WHEN** 调用 `get_cdn_link(CdnLinkRequest(file_id=...))`
- **THEN** SDK 返回 CDN URL、expires_in、permanent 与 content_type

### Requirement: Python 错误语义

Python SDK 对内容中心或对象存储的非 2xx 响应 SHALL 抛出 `ContentCenterError`，至少暴露 `status` 与 `message`，并优先解析 JSON `ApiError.message`；网络、序列化或响应解析失败 SHALL 使用 `status == -1`，401 错误 SHALL 提示 appToken 可能已轮换或应用已禁用。

#### Scenario: 解析业务错误

- **WHEN** 内容中心返回 400 且 body 为 `{"message":"未知或未启用的数据源: minio"}`
- **THEN** 异常的 `status` 为 400，`message` 包含服务端 message

#### Scenario: 提示失效 token

- **WHEN** 内容中心返回 401
- **THEN** 异常 message 提示检查 appToken 是否无效、已轮换或应用已禁用
