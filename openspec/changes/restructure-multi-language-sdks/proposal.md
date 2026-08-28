## Why

当前 `sdk/` 目录只有 Java SDK，目录结构、构建入口和发布流程都把 SDK 能力绑定在单一语言上；Go 与 Python 应用无法复用同一套官方开放文件 API 客户端。现在已有稳定的 `/api/open/**` 契约和成熟的 Java 实现，适合在不改服务端接口的前提下完成多语言 SDK 重构，降低后续维护与接入成本。

## What Changes

- 将现有 Java SDK（`pom.xml`、源码、测试、Java 文档）移动到 `sdk/java/`，保留 Maven 坐标、Java 包名和对外 API 行为。
- 将 `sdk/` 重构为多语言 SDK 根目录，新增统一入口 README，说明各语言 SDK 的安装、构建、版本与 API 契约。
- 新增 `sdk/go/` 官方 Go SDK：覆盖 appToken 鉴权、简单上传、分片断点续传、下载链接和媒体 CDN 预览链接。
- 新增 `sdk/python/` 官方 Python SDK：使用 `pyproject.toml` 与 uv 管理项目，覆盖与 Go SDK 对齐的开放文件 API 能力。
- 为 Java、Go、Python SDK 分别补充独立测试、使用文档和错误处理约定；预签名对象存储 PUT 请求不携带内容中心 Bearer token。
- 更新 SDK 发布/构建工作流及仓库内命令，使 Java 发布指向 `sdk/java/`，并增加 Go/Python 的验证入口。

## Capabilities

### New Capabilities

- `go-sdk`: 提供可独立引入、使用标准库实现的 Go 官方 SDK，封装开放文件 API 的鉴权、上传、续传、下载和 CDN 预览。
- `python-sdk`: 提供基于 uv 项目管理的 Python 官方 SDK，封装与服务端契约一致的同步客户端、上传续传、下载和 CDN 预览能力。

### Modified Capabilities

- `content-sdk`: 将 Java SDK 的规范从根目录 `sdk/` 迁移到 `sdk/java/`，保持现有 Maven 坐标和 Java API，并补充多语言根目录与构建/发布路径约束。

## Impact

- 目录与构建：`sdk/`、Java Maven 工程、Go module、Python `pyproject.toml`/uv lockfile、各语言测试与 README。
- CI/CD：`.github/workflows/sdk-release.yml` 的 Java 路径、版本读取、变更检测和验证命令；新增跨语言 SDK 验证步骤或工作流配置。
- 使用者：Java 消费方的 Maven 坐标与包 API 保持不变，但源码构建路径变为 `mvn -f sdk/java/pom.xml`；Go/Python 消费方获得新的官方安装入口。
- 服务端 API：不新增或修改 `/api/open/**` 端点，三个 SDK 共享现有请求、响应与 `ApiError{message}` 错误契约。
- 版本与发布：三种 SDK 保持各自语言生态的包版本与发布方式，避免把 Java 的 Maven 发布规则强加到 Go/Python。
