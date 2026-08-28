## Context

当前 `sdk/` 是一个独立的 Java Maven 工程，`sdk/pom.xml`、`sdk/src/main`、`sdk/src/test` 与 `sdk/README.md` 共同构成 Java SDK。Java 客户端已经封装了 `/api/open/**` 的 appToken 鉴权、简单预签名上传、MinIO 分片断点续传、预签名下载链接和媒体 CDN 预览链接；服务端契约通过 JSON `POST` 调用内容中心，文件字节通过不带 Bearer token 的预签名 `PUT` 直传对象存储。

本变更需要同时处理目录迁移、三种语言的 API 设计、各自的测试/依赖管理、文档和 CI。服务端端点与响应契约不变，目标是让语言 SDK 在相同安全边界和上传语义下分别符合 Java、Go、Python 的使用习惯。现有工作区有未提交的服务端和前端修改，本变更只规划 SDK 相关文件，不依赖或覆盖这些修改。

## Goals / Non-Goals

**Goals:**

- 将现有 Java 工程完整迁移到 `sdk/java/`，保留 `com.kk:content-center-sdk` 坐标、`com.kk.sdk` 包名和现有公开 API 行为。
- 在 `sdk/` 下建立清晰的 `java/`、`go/`、`python/` 语言边界，并提供根 README 作为导航。
- 实现 Go 官方 SDK，覆盖拆分式简单上传、文件/流式简单上传、拆分式分片上传、文件分片断点续传、下载链接和 CDN 预览。
- 实现基于 uv 的 Python 官方同步 SDK，覆盖与 Go SDK 相同的开放文件 API 能力，并提供类型标注和可关闭的客户端资源。
- 统一三种 SDK 的跨语言契约：API 请求带 `Authorization: Bearer <appToken>`；预签名 PUT 不带该头；默认分片大小 5 MiB；非 2xx 响应解析 `ApiError.message`；网络错误以 status `-1` 表示。
- 用本地 stub/transport 测试验证请求体、鉴权头、上传编排、断点续传、错误解析和下载/CDN 响应，不依赖真实对象存储。
- 让 Java 发布只关注 `sdk/java/`，并为所有 SDK 提供可重复的本地/CI 验证命令。

**Non-Goals:**

- 不修改服务端 `/api/open/**` 端点、鉴权模型、数据源路由或错误响应结构。
- 不把三个语言 SDK 合并成代码生成器，也不引入跨语言共享运行时。
- 不在本期实现异步 Python API、Go 并发分片上传、自动重试/退避、断点状态持久化或上传进度回调。
- 不改变 Java SDK 的 Maven 坐标、包名、默认值或已有方法签名；目录路径变化仅影响仓库内直接构建命令。
- 不要求本期为 Go/Python 接入新的外部包仓库发布自动化；先完成可安装、可测试、可被项目引用的包结构与文档。

## Decisions

### 1. 目录按语言拆分，根目录只做导航

采用以下布局：

```text
sdk/
├── README.md
├── java/
│   ├── README.md
│   ├── pom.xml
│   ├── src/main/java/...
│   └── src/test/java/...
├── go/
│   ├── README.md
│   ├── go.mod
│   ├── client.go
│   ├── types.go
│   ├── errors.go
│   └── client_test.go
└── python/
    ├── README.md
    ├── pyproject.toml
    ├── uv.lock
    ├── src/content_center_sdk/...
    └── tests/...
```

Java 工程整体移动而不是复制，避免双份源码逐渐漂移。根 `sdk/README.md` 只说明选择语言、共享 API 契约和验证命令；Java 的完整接入文档随 Maven 工程移动到 `sdk/java/README.md`，Go/Python 各自维护生态特有的安装与示例。

替代方案是保留 Java 在根目录、将 Go/Python 放到子目录；该方案会让根目录继续携带 Java 特殊语义，且无法形成统一的 `sdk/<language>` 入口，因此不采用。

### 2. 三个 SDK 共享 HTTP 契约，不共享实现

所有语言都实现同一组端点：

- 简单上传：`POST /api/open/uploads` → 预签名 `PUT` → `POST /api/open/uploads/complete`。
- 分片上传：`POST /api/open/uploads/multipart/init` → 每个缺失分片调用 `multipart/sign` 并 `PUT` → `multipart/complete`。
- 下载：`POST /api/open/download-links`，支持 `fileId` 或 `key + source`，可选 `filename` 与 `expiresIn`。
- 媒体预览：`POST /api/open/cdn-links`，支持 `fileId` 与可选 `expiresIn`。

请求字段使用服务端现有命名（`originalName`、`contentType`、`contentMd5`、`chunkId`、`parts` 等），不在 SDK 层改名后再暴露不一致的 wire contract。所有 API 请求只在内容中心请求上注入 Bearer token；对象存储预签名 URL 的 PUT 只带必要的 `Content-Type`。`uploadedParts.partNumber` 按服务端的 1-based 约定保存，签名和 complete 的 `chunkId` 仍按服务端的 0-based 约定转换。

替代方案是为 Go/Python 重新设计 REST 端点或调用服务端原始对象存储 API；这会破坏现有应用隔离、越权校验和未来兼容性，因此不采用。

### 3. Go 使用标准库，采用显式 context 和错误类型

`sdk/go` 使用 `net/http`、`encoding/json`、`crypto/md5`、`io`、`os` 等标准库，不引入第三方 HTTP/JSON 依赖。`NewClient` 接收 `BaseURL`、`AppToken` 和可选 `*http.Client`；每个公开请求方法接收 `context.Context`，允许调用方取消长时间上传。

公开 API 采用 Go 惯用形式：`Upload(ctx, path, options)`、`UploadReader(ctx, reader, filename, size, options)`、`UploadMultipart(ctx, path, options)`，拆分式方法使用 `InitUpload`、`CompleteUpload`、`InitMultipartUpload`、`SignMultipartPart`、`CompleteMultipartUpload`、`GetDownloadLink`、`GetCDNLink`。`ContentCenterError` 暴露 `Status`、`Message`，实现 `error` 并保留底层网络错误；网络失败使用 `Status == -1`。

替代方案是使用第三方 SDK 或只暴露一个泛型 `Do` 方法；前者增加依赖和发布负担，后者无法保证预签名上传和断点续传语义，均不采用。

### 4. Python 使用 uv + pyproject，提供同步 httpx 客户端

`sdk/python` 使用标准 `pyproject.toml` 定义包元数据、Python 版本下限、运行依赖 `httpx` 和开发依赖 `pytest`；用 `uv.lock` 固定可复现开发环境，导入包名为 `content_center_sdk`。客户端为同步 `ContentCenterClient`，支持上下文管理器 `with ContentCenterClient(...)`，避免连接池泄漏；上传方法接受 `pathlib.Path` 或已打开的二进制文件对象。

Python 公开对象使用 `dataclass` 与类型标注：`UploadOptions`、`MultipartOptions`、`DownloadLinkRequest`、`UploadResult`、`DownloadLink`、`CdnLink` 等。`ContentCenterError` 暴露 `status`、`message`，网络失败同样使用 `-1`。`httpx` 只用于请求编排和流式 PUT，不把 `Authorization` 默认挂到预签名 URL 请求上。

替代方案是仅使用 Python 标准库 `urllib`。它能降低依赖数量，但会让连接池、超时、二进制流请求和测试 transport 的实现更分散；本期接受一个小型成熟 HTTP 依赖换取清晰 API 和可测性。

### 5. 断点续传保持 Java 语义，先保证正确性再考虑性能

三种 SDK 的整文件上传均按以下流程实现：计算整文件 MD5；以 `contentMd5` 初始化；如果 `alreadyDone` 直接返回；将服务端返回的已上传 part 映射到分片号；只读取并上传缺失分片；规范化响应 ETag（去除外层双引号）；提交完整的 `chunkId`/`etag` 列表。默认分片大小为 5 MiB，调用方传入更小值时提升到 5 MiB。

Go 与 Python 本期采用串行分片上传，与现有 Java SDK 一致，避免并发、顺序和对象存储限流带来的额外差异。大文件按固定大小读取，不把整文件内容载入内存；MD5 计算与分片上传分别进行，确保可重复读取文件。

### 6. Java 发布路径与跨语言 CI 分离

将 `.github/workflows/sdk-release.yml` 中所有 Java 路径改为 `sdk/java/`，变更检测只把 `sdk/java/pom.xml` 与 `sdk/java/src/**` 视为 Maven 发布变化；Maven 坐标与 GitHub Packages URL 不变。新增或调整 SDK CI 验证，使 `mvn -f sdk/java/pom.xml clean verify`、`go test ./...`、`uv run pytest` 在 SDK 相关变更中可执行。

Java 发布仍采用现有正式版本不可覆盖规则。Go/Python 版本在各自清单中独立维护，首版使用 `0.1.0`，不让 Java 的 GitHub Packages 发布条件阻塞另外两种语言的测试。

### 7. 以本地 stub 验证 wire contract 和安全边界

Go 使用 `httptest.Server`，Python 使用 `httpx.MockTransport`（必要时配合内存文件），Java 保留现有 JDK `HttpServer` 测试并随目录移动。每套测试必须覆盖：API Bearer 注入、预签名 PUT 不带 Bearer、简单上传三步、PUT 失败不调用 complete、分片续传跳过已上传分片、`ApiError.message` 与 401 提示、下载链接和 CDN 响应解析。测试不访问真实服务端或对象存储。

## Risks / Trade-offs

- [语言实现可能与服务端字段或分片编号漂移] → 在三个 SDK 的 spec 中明确端点、字段和 0/1-based 转换；用 stub 测试断言实际 JSON，并把服务端契约文档链接放入各 SDK README。
- [Java 目录迁移造成现有构建命令失败] → 保留 Maven 坐标和源码包名；同步更新 Java README、CI 和仓库搜索到的路径；迁移后执行 `mvn -f sdk/java/pom.xml clean verify`。
- [预签名 PUT 误带 Bearer 导致对象存储拒绝或泄露 token] → API 请求与 storage PUT 使用独立 header 构造；测试明确断言 PUT 请求没有 `Authorization`。
- [Python 依赖/uv lock 与执行环境不一致] → 固定 `requires-python` 与依赖范围，提交 `uv.lock`，CI 先执行 `uv sync --locked` 再运行测试。
- [多语言 SDK 发布节奏不同] → 版本和发布入口按语言隔离，根 README 明示当前发布方式；本期只自动验证 Go/Python，不把未定义的注册表发布流程混入 Java 发版。
- [大文件 MD5 计算与串行上传耗时较长] → 全程流式读取、不把整文件载入内存；串行行为与 Java 对齐，后续可在独立变更中增加可选并发与进度回调。

## Migration Plan

1. 创建 `sdk/java/`，将 `sdk/pom.xml`、`sdk/src/` 和 Java README 移入，保留 Java 包名、Maven 坐标与版本；创建 Go/Python 项目骨架和根 README。
2. 更新 Java README、CI、OpenSpec 文档和仓库内构建引用；确认不存在仍指向 `sdk/pom.xml` 或 `sdk/src/**` 的有效路径。
3. 先让 Java 在新路径通过原有测试，再实现 Go/Python 的共享契约与各自 stub 测试。
4. 使用 `mvn -f sdk/java/pom.xml clean verify`、`go test ./...`、`uv sync --locked`、`uv run pytest` 完成验证；CI 对 `sdk/**` 变更执行三套测试，Java 发版只在 Java 代码变化时触发。
5. 回滚时可整体恢复 `sdk/java/` 到 `sdk/` 并回退路径/CI 文档；服务端没有数据库或 API 迁移，因此不需要数据回滚。

## Open Questions

- Go module 与 Python 包的正式外部注册表发布（例如 Go tag、PyPI trusted publishing）不在本期范围内；实现完成后可根据仓库发布策略单独决定。
- 是否需要异步 Python API、并发分片、自动重试和进度回调，留待真实接入方提出性能需求后另立变更。
