# content-center-sdk

内容中心（k-File）开放 API 官方 Java SDK：**appToken 鉴权 + 预签名直传上传（简单 / 分片断点续传）+ 预签名下载链接**。

- 零 Spring 依赖：HTTP 使用 JDK `java.net.http.HttpClient`，JSON 使用 Jackson，字节码目标 **Java 17**
- 文件字节经预签名 URL **直传对象存储**，不经过内容中心服务端

## 构建与发布

本地构建：

```bash
mvn -f sdk/pom.xml clean install
```

正式发布由 CI 自动完成（`.github/workflows/sdk-release.yml`）：

- **触发**：合入 master 且 `sdk/` 源码或 pom 有变化时自动执行；也可在 Actions 页面手动触发。纯文档变化不发布。
- **版本控制**：版本号取自 `sdk/pom.xml` 的 `<version>`（仅允许正式版本，不支持 SNAPSHOT）。发布前 CI 会查询 GitHub Packages，**同一版本已存在则拒绝发布**——发新版必须先升级 pom 中的版本号。
- **发布目标**：GitHub Packages `https://maven.pkg.github.com/kK-2004/kFile`，发布前先跑全部测试。

服务端主工程构建不受影响（SDK 为独立 Maven 工程，不参与 reactor）。

## 接入

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/kK-2004/kFile</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.kk</groupId>
  <artifactId>content-center-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

> 拉取需要 GitHub PAT（`read:packages`）配置到 Maven settings.xml 的 `github` server（与拉取 kmessage-sdk 同一模式）。

appToken 由内容中心 SUPER 管理员在「管理端 → 开放应用」创建/轮换时一次性提供，形如 `kfile_xxx`。

```java
ContentCenterClient client = ContentCenterClient.builder()
        .baseUrl("https://content-center.example.com")   // 内容中心部署地址
        .appToken("kfile_xxxxxxxxxxxxxxxxxxxxxxxx")
        .build();

// 1. 简单上传（自动三步：init → 直传 PUT → complete）
UploadResult result = client.upload(Path.of("report.pdf"),
        UploadOptions.defaults()
                .source("oss")                        // 可选：不传用服务端配置的默认数据源
                .path("avatars/2026")                 // 可选：应用根路径下的子目录
                .contentType("application/pdf"));

// 2. 大文件分片断点续传（默认 5MB 分片；以整文件 MD5 为幂等 key，中断重传自动跳过已传分片）
UploadResult big = client.uploadMultipart(Path.of("video.mp4"),
        MultipartOptions.defaults().source("minio")); // 分片仅支持具备能力的数据源（MinIO）

// 3. 获取限时预签名下载链接（默认 300s，服务端 clamp 到 [60, 3600]）
DownloadLink link = client.getDownloadLink(
        DownloadLinkRequest.ofFileId(result.fileId()).filename("报表.pdf").expiresIn(600));
// 或回传上传响应中的 storageKey + source：
// client.getDownloadLink(DownloadLinkRequest.ofKey(result.storageKey(), result.source()));
```

## 错误处理

非 2xx 响应抛出 `ContentCenterException`（`getStatus()` 为 HTTP 状态码，`-1` 表示传输层失败），
message 来自服务端 `ApiError{message}`；401 会附带「appToken 可能已轮换或应用被禁用」提示。

| 状态码 | 含义 |
|---|---|
| 400 | 参数非法 / 数据源未启用 / 对象未上传即确认等（见 message） |
| 401 | appToken 缺失、无效、已轮换或应用被禁用 |
| 404 | 下载的 fileId/storageKey 不存在或不属于本应用 |
| 429 | 触发限流（IP 维度令牌桶） |
| 500 | 服务端错误（如 rootPath 迁移失败，整体保持原状） |

## 服务端契约（v0.1）

SDK 封装以下端点（均需 `Authorization: Bearer <appToken>`，详见服务端 `docs/open-api.md`）：

| SDK 方法 | 端点 |
|---|---|
| `upload` | `POST /api/open/uploads` → PUT 预签名 URL → `POST /api/open/uploads/complete` |
| `uploadMultipart` | `POST /api/open/uploads/multipart/init` →（`/sign` + PUT）× N → `POST /api/open/uploads/multipart/complete` |
| `getDownloadLink` | `POST /api/open/download-links` |

修改 SDK 契约需同步更新服务端 `docs/open-api.md` 与本文件。
