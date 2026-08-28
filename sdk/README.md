# Content Center SDKs

内容中心（k-File）开放 API 的官方多语言 SDK。三个 SDK 共享 `/api/open/**` 服务端契约，但按语言生态独立构建、测试和发布。

## 选择 SDK

| 语言 | 目录 | 安装/构建入口 | 说明 |
|---|---|---|---|
| Java | [`java/`](java/) | `mvn -f sdk/java/pom.xml clean verify` | Maven 坐标 `com.kk:content-center-sdk`，Java 17+ |
| Go | [`go/`](go/) | `cd sdk/go && go test ./...` | module `github.com/kK-2004/kFile/sdk/go`，仅标准库 |
| Python | [`python/`](python/) | `cd sdk/python && uv sync --locked && uv run pytest` | Python 3.11+，uv 管理，同步 `httpx` 客户端 |

各语言的完整安装、API 示例和错误处理说明见对应目录的 README。

## 共享能力

SDK 都封装以下开放 API：

- 简单上传：初始化预签名 PUT → 直传对象存储 → complete 确认。
- 分片上传：整文件 MD5 幂等、5 MiB 默认分片、缺失分片续传和 ETag 合并。
- 下载链接：按 `fileId` 或 `storageKey + source` 获取限时预签名 URL。
- 媒体 CDN 预览：按 `fileId` 获取图片、音频或视频的稳定预览 URL。

所有内容中心 API 请求携带：

```text
Authorization: Bearer <appToken>
```

预签名对象存储 PUT 请求不会携带 appToken，只发送必要的 `Content-Type` 和文件字节。非 2xx 响应解析服务端 `ApiError.message`；网络或传输层错误在各 SDK 中以状态 `-1` 表示。401 通常意味着 appToken 已失效、被轮换或对应应用已禁用。

## 本地验证

在仓库根目录执行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f sdk/java/pom.xml clean verify
(cd sdk/go && go test ./...)
(cd sdk/python && uv sync --locked && uv run pytest)
```

测试使用本地 HTTP stub/transport，不需要真实内容中心或对象存储凭证。

## 发布

在 GitHub Actions 的 `SDK Release` 中选择对应模块：

- `sdk-java`：沿用现有 Maven 发布流程，发布到 GitHub Packages。
- `sdk-go`：输入版本号（如 `0.1.0`），通过 `sdk/go/v0.1.0` tag 发布 Go module。
- `sdk-py`：输入版本号（可留空），构建并发布 `content-center-sdk` 到 PyPI；需要配置 `PYPI_API_TOKEN` Secret。

`Deploy to Server` 的手动触发也提供相同的三个 SDK 选项。Java 版本读取 `sdk/java/pom.xml`，Python 版本读取 `sdk/python/pyproject.toml`。
