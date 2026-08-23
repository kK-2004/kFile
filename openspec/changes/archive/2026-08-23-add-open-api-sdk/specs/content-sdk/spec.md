## ADDED Requirements

### Requirement: SDK 独立构建与依赖约束
官方 Java SDK SHALL 位于仓库 `sdk/` 目录，作为独立 Maven 工程（`com.kk:content-center-sdk`）构建（`mvn -f sdk/pom.xml`），MUST NOT 加入服务端 reactor、MUST NOT 依赖 Spring；运行依赖仅 Jackson，HTTP 使用 JDK `java.net.http.HttpClient`，字节码目标 Java 17。

#### Scenario: 独立构建成功
- **WHEN** 在仓库根目录执行 `mvn -f sdk/pom.xml clean verify`
- **THEN** SDK 构建与测试通过，产出可被其他应用以 Maven 坐标引入的 jar；服务端既有构建（根 pom）不受影响

#### Scenario: 依赖保持轻量
- **WHEN** 查看 SDK 的依赖树
- **THEN** 编译期依赖不含 Spring 及重框架，仅 Jackson 与 JDK

### Requirement: 客户端构建与鉴权注入
SDK SHALL 提供 `ContentCenterClient`（builder 配置 baseUrl、appToken、超时）；所有对开放 API 的请求 MUST 自动携带 `Authorization: Bearer <appToken>` 头。

#### Scenario: 构建客户端并发起调用
- **WHEN** 使用 builder 设定 baseUrl 与 appToken 后调用任意 API 方法
- **THEN** 发出的 HTTP 请求头包含正确的 Bearer token

### Requirement: 简单上传编排
SDK SHALL 提供 `upload` 方法封装完整简单直传流程：初始化（取 putUrl/storageKey/fileId）→ 用 `java.net.http.HttpClient` 直传对象存储（PUT，携带声明的 Content-Type）→ 确认登记，返回含 fileId、size 的结果对象。

#### Scenario: 上传本地文件成功
- **WHEN** 调用 `client.upload(Path.of("report.pdf"), options)` 且服务端与对象存储可用
- **THEN** SDK 完成三步流程并返回 fileId/size/storageKey/source

#### Scenario: 直传失败报错
- **WHEN** 对象存储 PUT 返回非 2xx
- **THEN** SDK 抛出携带状态码与原因的 `ContentCenterException`，不调用 complete

### Requirement: 分片上传与断点续传
SDK SHALL 提供分片上传方法：默认 5MB 分片、用 JDK `MessageDigest` 计算整文件 MD5 作为幂等 key；初始化响应含已传 `uploadedParts` 时 MUST 跳过对应分片，仅上传缺失部分后完成合并。

#### Scenario: 首次分片上传
- **WHEN** 调用 `uploadMultipart` 上传 120MB 文件（默认 5MB 分片）
- **THEN** SDK 上传 25 个分片并 complete，返回 fileId 与 storageKey

#### Scenario: 中断后续传
- **WHEN** 上传进行到一半中断后，用同一文件重新调用 `uploadMultipart`
- **THEN** SDK 通过相同 MD5 获得已传分片列表，仅上传剩余分片即完成

### Requirement: 下载链接获取
SDK SHALL 提供获取预签名下载链接的方法（入参 fileId 或 storageKey+source，可选 filename/expiresIn），返回 url 与 expiresIn。

#### Scenario: 获取下载链接
- **WHEN** 调用 `getDownloadLink(fileId)`
- **THEN** SDK 返回服务端签发的限时下载 URL

### Requirement: 错误处理
SDK 对非 2xx 响应 SHALL 抛出 `ContentCenterException`，携带 HTTP 状态码并解析 `ApiError{message}` 为错误信息；对 401 提供明确「token 无效或应用被禁用/已轮换」类提示。

#### Scenario: 解析服务端错误
- **WHEN** 服务端返回 `400 {"message":"未知或未启用的数据源: minio"}`
- **THEN** SDK 抛出的异常 status=400、message 为「未知或未启用的数据源: minio」

#### Scenario: token 失效
- **WHEN** 服务端返回 401
- **THEN** SDK 抛出异常并提示检查 appToken 是否已轮换或应用被禁用
