## Why

当前内容中心的存储能力（OSS/MinIO 多数据源、预签名直传/下载、统一 key 规则）只服务于自身的 Web 前端与 MCP 工具；其他应用想复用文件存储只能各自对接对象存储、重复实现预签名与 key 管理逻辑。本变更赋予项目「内容中心」角色：将文件上传/下载能力以「应用凭证 + 开放 API + 官方 Java SDK」的形式开放给其他应用，实现文件存储统一收口。

## What Changes

- **开放应用管理（SUPER 管理后台）**：新增「开放应用」管理页，配置其他应用的 `appName` + `appToken`——appToken 由系统生成（不透明随机串）、仅创建/轮换时明文展示一次、落库只存 SHA-256 哈希（对齐 `security.oauth` 包既有凭证模式）；`appName` 全局唯一；支持禁用/启用、轮换（旧 token 立即失效）、**删除（级联清理该应用全部文件：对象 + 节点 + 分片残留，二次确认展示文件数与总大小，共享目录保留）**、配置/修改 SDK 上传文件的虚拟根路径 `rootPath`（斜杠分隔、逐段校验；不配置默认 `开放应用/<appName>`，**修改时同步迁移该应用已上传的全部文件至新路径后请求才返回**）、查看 `lastUsedAt`。
- **默认数据源（系统设置）**：系统设置新增「开放 API 默认数据源」配置项（`configs` 表新 key），决定开放 API 请求不传数据源时使用的默认 sourceId；候选值来自 `StorageBrowserRegistry.sources()`（已启用数据源），保存时校验。
- **开放文件 API（`/api/open/**`）**：新增 Bearer appToken 鉴权的独立 SecurityFilterChain + 专用过滤器（校验 token 哈希与应用启用状态，写入应用身份上下文），提供：
  - **预签名上传**：初始化（返回预签名 PUT 直链 + 最终 storageKey）→ 客户端直传对象存储 → 确认登记（复用 `StorageBrowserService` 预签名与 stat 能力）；文件登记进虚拟文件树的应用上传根目录（管理端可配置 `rootPath`，默认 `开放应用/<appName>`），SUPER 可在既有「文件管理」页浏览；分片直传（大文件断点续传）按数据源能力开放（首期 MinIO）。
  - **预签名下载**：按 storageKey 返回限时预签名下载链接，支持指定下载文件名（Content-Disposition）。
  - **数据源路由**：每个请求可选传 `source`（sourceId）；不传用系统配置的默认值；传入的 source 必须已启用，否则 400。
- **Java SDK（新 Maven 工程 `sdk/`）**：纯 JDK HttpClient + Jackson 的轻量客户端（零 Spring 依赖、独立构建），封装 appToken 鉴权、简单上传（取预签名 URL → 自动 PUT → 确认）、分片断点续传、获取下载链接、数据源选择；供其他 Java 应用以 Maven 坐标引入。
- **前端**：管理后台新增「开放应用」页（列表/创建/轮换/启停）；系统设置页新增默认数据源下拉。

## Capabilities

### New Capabilities
- `open-app-credential-management`: SUPER 管理后台的开放应用凭证管理——appName/appToken 的创建（token 一次性明文展示、哈希落库）、轮换、启停、删除（级联清理应用文件）、SDK 上传根路径（rootPath）配置（修改触发全量文件同步迁移）、lastUsedAt 审计，以及开放 API 默认数据源的系统设置。
- `open-file-api`: 面向外部应用的开放文件 API——Bearer appToken 鉴权、按请求选择数据源（默认取系统配置）、预签名直传上传（简单 + 按源能力的分片，文件登记进应用可配置的上传根目录）与预签名下载链接。
- `content-sdk`: 官方 Java SDK（独立 Maven 工程 `sdk/`）——封装开放 API 的鉴权与上传/下载流程，供其他 Java 应用集成。

### Modified Capabilities
<!-- 无：开放能力走新增的独立过滤链与新表，不改变现有 specs 的需求行为 -->

## Impact

- **代码**：新增 `com.kk.openapi.*`（`OpenApp` 实体、`OpenAppService`、管理端 `OpenAppController`、开放端 `OpenFileController`、`AppTokenAuthFilter`）；`SecurityConfig` 新增一条 `@Order` 过滤链；上传/下载复用 `StorageBrowserService`/`StorageKeys`/`MultipartUploadService`，文件登记复用 `stored_file` 虚拟树；rootPath 变更配套**同步迁移**（`StorageBrowserService` 新增 `copy` 能力 + 迁移服务搬移对象、更新虚拟树）。
- **数据库**：新增 `open_app` 表（appName 唯一、tokenHash、rootPath、enabled、lastUsedAt 等，`ddl-auto: update` 自动创建）；`configs` 表新增默认数据源 key；无既有表结构变更。
- **构建**：仓库新增独立构建的 `sdk/` Maven 工程（`mvn -f sdk/pom.xml`）；现有单模块构建、Dockerfile/Jenkinsfile/deploy.sh 不受影响。
- **前端**：`frontend/src/views/admin/` 新增开放应用页（含 rootPath 修改时的迁移加载态与统计展示）；`AdminSettings.vue` 增加默认数据源配置；路由与导航更新（仅 SUPER 可见）。
- **安全**：`/api/open/**` 与现有 `/mcp`、`/oauth2`、Web 会话链隔离；应用身份不与 AdminUser 混用；token 仅哈希落库、轮换即失效。
- **范围限制**：首期开放能力仅上传与下载（不含列表/删除/元数据查询）；用户端提交、归档、MCP 链路不变。
