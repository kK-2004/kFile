## 1. SDK 目录重构与 Java 兼容迁移

- [x] 1.1 创建 `sdk/java/`、`sdk/go/`、`sdk/python/` 目录，并将现有 `sdk/pom.xml`、`sdk/src/` 与 Java 接入文档移动到 `sdk/java/`；确认 `com.kk.sdk` 包名和 Maven 坐标 `com.kk:content-center-sdk` 未变化
- [x] 1.2 更新 `sdk/java/README.md` 中的构建、版本、发布和路径引用，将 `mvn -f sdk/pom.xml ...` 改为 `mvn -f sdk/java/pom.xml ...`；运行 `mvn -f sdk/java/pom.xml clean verify` 确认 Java 测试通过
- [x] 1.3 新建 `sdk/README.md` 作为多语言入口，列出 Java/Go/Python 的目录、安装方式、共享 `/api/open/**` 能力、Bearer 与预签名 PUT 的安全边界，以及三套本地验证命令

## 2. Java 发布与跨语言 CI

- [x] 2.1 修改 `.github/workflows/sdk-release.yml` 的变更检测、Maven cache、版本读取、错误提示、`clean verify` 和 `deploy` 路径为 `sdk/java/`；仅当 `sdk/java/pom.xml` 或 `sdk/java/src/**` 变化时触发 Java Maven 发布，保留现有 GitHub Packages 坐标与版本不可覆盖校验
- [x] 2.2 新建 `.github/workflows/sdk-ci.yml`，在 `sdk/**` 相关 push/pull request 上运行 Java `mvn -f sdk/java/pom.xml clean verify`、Go `go test ./...` 和 Python `uv sync --locked && uv run pytest`，并使用对应的 JDK、Go 与 uv setup action
- [x] 2.3 搜索并更新仓库内仍指向 `sdk/pom.xml`、`sdk/src/**` 或旧 Java README 位置的有效文档与脚本引用；用 `rg -n "sdk/(pom\.xml|src/)" .github sdk openspec docs` 确认只剩迁移说明或历史归档引用

## 3. Go SDK 基础模块与 HTTP 契约

- [x] 3.1 创建 `sdk/go/go.mod`（module `github.com/kK-2004/kFile/sdk/go`）以及 `sdk/go/types.go`、`sdk/go/errors.go`，定义 `ClientConfig`、`UploadOptions`、`MultipartOptions`、上传/分片/下载/CDN 响应类型和 `ContentCenterError{Status, Message, Err}`；运行 `go test ./...` 确认基础包可编译
- [x] 3.2 在 `sdk/go/client.go` 实现 `NewClient`、URL 规范化、带 context 的 JSON POST helper 和错误解析 helper；内容中心请求注入 `Authorization: Bearer <token>`，非 2xx 优先读取 `ApiError.message`，网络/序列化/解析错误使用 `Status == -1`
- [x] 3.3 在 `sdk/go/client.go` 实现 `InitUpload`、`CompleteUpload`、`Upload` 与 `UploadReader`；使用独立的预签名 PUT request，不继承 Bearer header，设置选项中的 Content-Type，PUT 失败时返回错误且不调用 complete
- [x] 3.4 在 `sdk/go/client.go` 实现 `InitMultipartUpload`、`SignMultipartPart`、`CompleteMultipartUpload` 与 `UploadMultipart`；以流式读取计算 MD5，默认/最小分片为 `5 * 1024 * 1024` 字节，按 1-based `uploadedParts` 跳过已有分片并提交 0-based `chunkId` 与去引号 ETag
- [x] 3.5 在 `sdk/go/client.go` 实现 `GetDownloadLink` 与 `GetCDNLink`，分别支持 file ID 或 key+source、filename/expiresIn，以及 CDN 的 permanent/contentType 字段；不要在客户端改写服务端返回的 URL
- [x] 3.6 在 `sdk/go/client_test.go` 使用 `httptest.Server` 覆盖 Bearer 注入、预签名 PUT 无 Bearer、简单上传三步、PUT 失败不 complete、分片续传跳过、alreadyDone、ApiError/401、下载链接和 CDN 响应解析；运行 `go test ./...`
- [x] 3.7 新建 `sdk/go/README.md`，提供 `go get`/module 引用、`NewClient`、简单上传、分片续传、下载/CDN、错误处理示例，并注明 SDK 不会把 appToken 发送到预签名对象存储 URL

## 4. Python uv SDK 基础模块与 HTTP 契约

- [x] 4.1 创建 `sdk/python/pyproject.toml`，使用 `src/content_center_sdk` 布局、Python `>=3.11`、项目版本 `0.1.0`、`hatchling>=1.25,<2` 构建 backend、运行依赖 `httpx` 和开发依赖 `pytest`；执行 `uv lock` 生成并提交 `sdk/python/uv.lock`
- [x] 4.2 创建 `sdk/python/src/content_center_sdk/models.py`、`errors.py` 和 `__init__.py`，定义带类型标注的 `UploadOptions`、`MultipartOptions`、`DownloadLinkRequest`、上传/分片/下载/CDN dataclass，以及 `ContentCenterError(status, message)`；公开稳定的 import 入口
- [x] 4.3 在 `sdk/python/src/content_center_sdk/client.py` 实现同步 `ContentCenterClient`、`__enter__/__exit__`、超时配置、JSON POST helper 和错误解析；内容中心请求注入 Bearer，网络/解析错误使用 `status == -1`，预签名 PUT 单独构造 headers
- [x] 4.4 实现 `init_upload`、`complete_upload`、`upload` 与二进制 file-object 上传入口；支持 `Path`、source/path/content_type 选项和文件大小，按 init → PUT → complete 执行，PUT 失败时抛错且不调用 complete
- [x] 4.5 实现 `init_multipart_upload`、`sign_multipart_part`、`complete_multipart_upload` 与 `upload_multipart`；流式计算整文件 MD5，默认/最小分片为 5 MiB，跳过 1-based 已上传分片，提交 0-based chunkId/ETag，alreadyDone 时直接返回
- [x] 4.6 实现 `get_download_link` 与 `get_cdn_link`，透传 file_id 或 key+source、filename/expires_in 和 CDN expires_in，并返回对应 dataclass 字段而不修改 URL
- [x] 4.7 在 `sdk/python/tests/test_client.py` 使用 `httpx.MockTransport` 覆盖 Bearer、预签名 PUT 无 Bearer、简单上传三步、PUT 失败不 complete、分片续传/已完成、ApiError/401、下载和 CDN 解析；执行 `cd sdk/python && uv sync --locked && uv run pytest`
- [x] 4.8 新建 `sdk/python/README.md`，提供 uv 安装/同步、`ContentCenterClient` 上下文管理器、Path 上传、multipart 续传、下载/CDN、异常处理示例，并注明 Python SDK 为同步客户端

## 5. 跨语言契约与交付验证

- [x] 5.1 对照 `openspec/specs/open-file-api/spec.md` 与现有 Java SDK，核对 Java/Go/Python 的 JSON 字段、端点、默认 5 MiB 分片、0/1-based 分片编号、401 文案和 `status == -1` 网络错误语义；修正 README 与测试中的不一致
- [x] 5.2 在仓库根目录依次运行 `mvn -f sdk/java/pom.xml clean verify`、`cd sdk/go && go test ./...`、`cd sdk/python && uv sync --locked && uv run pytest`，确认三套 SDK 均通过且服务端根 Maven 构建文件未被改动
- [x] 5.3 运行 `git diff --check` 和 SDK 范围的 `rg` 路径审计，确认没有残留重复 Java 源码、旧构建路径、未锁定 Python 依赖或把 appToken 发送到预签名 PUT 的实现；记录最终验证结果供发布前复核
