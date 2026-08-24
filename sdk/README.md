# content-center-sdk

内容中心（k-File）开放 API 官方 Java SDK：**appToken 鉴权 + 预签名直传上传（简单 / 分片断点续传）+ 下载链接 + 媒体 CDN 预览链接**。

- 零 Spring 依赖：HTTP 使用 JDK `java.net.http.HttpClient`，JSON 使用 Jackson，字节码目标 **Java 17**
- 文件字节经预签名 URL **直传对象存储**，不经过内容中心服务端（上传流量不占服务端带宽）
- 所有非 2xx 响应统一抛 `ContentCenterException`（含 HTTP 状态码与服务端错误信息）

## 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17 及以上（编译目标 17，21 可直接使用） |
| 运行依赖 | 仅 `jackson-databind`（由 SDK 传递引入，无其它传递依赖） |
| 网络 | 能访问内容中心服务端地址与对象存储预签名 URL 域名 |

## 快速开始

### 第 1 步：获取 appToken

内容中心 SUPER 管理员在「管理端 → 开放应用」新建应用，**创建/轮换时一次性展示** appToken（形如 `kfile_xxxxxxxxxxxxxxxxxxxxxxxx`，落库仅存哈希，关闭弹窗后无法再查）。token 泄露随时可让管理员轮换（旧 token 立即失效）或禁用应用。

### 第 2 步：配置 Maven 仓库与认证

SDK 发布在 GitHub Packages（私有，匿名不可访问）。在你的项目中：

**pom.xml 加仓库：**

```xml
<repositories>
  <repository>
    <id>github-kfile</id>
    <url>https://maven.pkg.github.com/kK-2004/kFile</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

**`~/.m2/settings.xml`（或 CI 的 settings）配置 PAT 认证**——需要一个具有 `read:packages` 权限的 GitHub Personal Access Token：

```xml
<settings>
  <servers>
    <!-- id 必须与上面 repository 的 id 一致 -->
    <server>
      <id>github-kfile</id>
      <username>你的GitHub用户名</username>
      <password>ghp_你的PAT</password>
    </server>
  </servers>
</settings>
```

> CI 环境建议把 PAT 放入 CI 的 secret，运行时生成 settings.xml，不要提交到代码库。

### 第 3 步：引入依赖

```xml
<dependency>
  <groupId>com.kk</groupId>
  <artifactId>content-center-sdk</artifactId>
  <version>0.1.2</version>
</dependency>
```

### 第 4 步：调用

```java
import com.kk.sdk.ContentCenterClient;
import com.kk.sdk.ContentCenterClient.*;
import com.kk.sdk.ContentCenterException;

ContentCenterClient client = ContentCenterClient.builder()
        .baseUrl("https://content-center.example.com")   // 内容中心部署地址（不带尾斜杠亦可）
        .appToken("kfile_xxxxxxxxxxxxxxxxxxxxxxxx")
        .build();

// 简单上传：SDK 自动完成三步（init → PUT 直传 → complete 确认）
UploadResult result = client.upload(Path.of("report.pdf"),
        UploadOptions.defaults()
                .source("oss")                        // 可选，不传用该应用在后台配置的默认数据源（未配置兜底 oss）
                .path("avatars/2026")                 // 可选，应用上传根路径下的子目录
                .contentType("application/pdf"));     // 可选，建议填写

System.out.println("fileId=" + result.fileId() + " size=" + result.size());

// 下载：拿限时预签名 URL（可直接下发给浏览器/第三方）
DownloadLink link = client.getDownloadLink(
        DownloadLinkRequest.ofFileId(result.fileId()).filename("报表.pdf").expiresIn(600));
System.out.println(link.url());

// 媒体预览：仅支持图片、音频、视频；默认永久有效，可直接交给浏览器媒体标签
UploadResult media = client.upload(Path.of("cover.png"),
        UploadOptions.defaults().contentType("image/png"));
CdnLink preview = client.getCdnLink(CdnLinkRequest.ofFileId(media.fileId()));
System.out.println(preview.url());
```

## 客户端构建（Builder）

| 方法 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `baseUrl(String)` | ✓ | — | 内容中心部署地址，如 `https://file.example.com` |
| `appToken(String)` | ✓ | — | 管理员签发的 appToken |
| `connectTimeout(Duration)` | | 10s | TCP 连接超时 |
| `requestTimeout(Duration)` | | 10min | 单个 HTTP 请求超时（含大文件分片 PUT，按网络情况调整） |

客户端实例线程安全、可复用，建议应用内单例。缺 `baseUrl`/`appToken` 在 `build()` 时抛 `IllegalArgumentException`。

## API 参考

### 简单上传 `upload`

> **先区分两种上传方式**
>
> | 方法 | 适用场景 | 文件数据链路 |
> |---|---|---|
> | `initUpload` + `completeUpload` | **浏览器预签名直传** | 浏览器先向业务后端取得 `putUrl`，然后直接 PUT 对象存储；业务后端和 KFile 内容中心都不转发文件字节 |
> | `upload` | **后端 SDK 上传** | 文件需要先到达调用 SDK 的业务后端，再由该后端执行 PUT；因此从浏览器视角看会经过业务后端，但文件字节仍不会经过 KFile 内容中心服务 |
>
> 如果目标是“前端不能把文件传给业务服务器”，请使用 `initUpload`，不要在业务后端接收 `MultipartFile` 后再调用 `upload`。

```java
// 文件（推荐，自动取文件名与大小）
UploadResult upload(Path file, UploadOptions options)
// 流（filename 必填；size 可为 null）
UploadResult upload(InputStream in, String filename, Long size, UploadOptions options)
```

**UploadOptions**（`UploadOptions.defaults()` 起步，链式覆盖）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `source` | null | 数据源 sourceId（如 `oss` / `minio`）；null = 该应用在管理端「开放应用」配置的默认数据源（未配置兜底 `oss`） |
| `path` | null | 应用上传根路径下的子目录（斜杠分隔，如 `avatars/2026`）；每段会被校验，含 `..` 直接 400 |
| `contentType` | null | 文件 MIME 类型。**填写后 PUT 直传会携带一致的 Content-Type**（OSS 预签名要求二者一致）；不填按 `application/octet-stream` 上传 |

**UploadResult 字段**：`fileId`（后续下载用，建议持久化）、`name`、`size`（服务端 stat 校验后的真实大小）、`contentType`、`storageKey`、`source`。

**行为要点**：`upload` 由调用 SDK 的后端进程读取文件，并依次执行「向内容中心初始化拿预签名 PUT URL（有效期 600s）→ 后端进程 PUT 对象存储 → 向内容中心确认登记」。直传失败会抛异常且**不会调用确认**；预签名 URL 过期（如本机网络慢超 10 分钟才 PUT）会报错，重新调用 `upload` 即可。

浏览器直传场景可由业务后端只调用拆分式方法，appToken 留在后端，文件字节由浏览器直接 PUT：

```java
UploadInitResponse init = client.initUpload("report.pdf", size, options);
// 将 init.putUrl() 返回给浏览器；浏览器 PUT 成功后：
UploadResult result = client.completeUpload(init.storageKey(), init.source());
```

### 分片断点续传 `uploadMultipart`（大文件）

```java
UploadResult uploadMultipart(Path file, MultipartOptions options)
```

**MultipartOptions**（`MultipartOptions.defaults()` 起步）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `source` | null | **必须是支持分片的数据源（当前为 `minio`）**，其它值在 init 即返回 400 |
| `path` | null | 同 `upload` |
| `contentType` | null | 同 `upload` |
| `partSizeBytes` | 5MB | 分片大小，最小 5MB（S3 协议约束），更大的分片可提高大文件吞吐 |

**断点续传原理**：SDK 计算整文件 MD5 作为幂等 key；init 时服务端返回**已成功上传的分片列表**，SDK 只补传缺失的分片后合并。因此同一文件中断后**用同一方法重试即可续传**（无需记录任何中间状态）；服务端识别到「文件此前已完整传完」会直接返回成功（`alreadyDone`）。

适合 GB 级大文件；几十 MB 内的小文件用 `upload` 即可。

浏览器分片直传对应拆分式方法为 `initMultipartUpload`、`signMultipartPart`、`completeMultipartUpload`；业务后端只签名和确认，浏览器负责 PUT 分片并收集 ETag。

### 下载链接 `getDownloadLink`

```java
// 方式一（推荐）：用上传返回的 fileId
DownloadLink getDownloadLink(DownloadLinkRequest.ofFileId(long fileId))
// 方式二：回传上传响应中的 storageKey + source
DownloadLink getDownloadLink(DownloadLinkRequest.ofKey(String storageKey, String source))
```

**DownloadLinkRequest 可链式附加**：

| 方法 | 说明 |
|---|---|
| `filename(String)` | 下载时浏览器保存的文件名（Content-Disposition），默认用上传时的原始文件名 |
| `expiresIn(long)` | 链接有效期秒数，服务端收敛到 **[60, 3600]**，默认 300 |

返回 `DownloadLink(url, expiresIn)`：URL 在有效期内可直接 GET 下载（可下发给浏览器/第三方，无需再带 appToken）。

### 媒体 CDN 预览 `getCdnLink`

```java
// 默认永久有效；服务端只允许 image/*、audio/*、video/*
CdnLink preview = client.getCdnLink(CdnLinkRequest.ofFileId(fileId));

// 指定有效期（秒）
CdnLink preview = client.getCdnLink(
        CdnLinkRequest.ofFileId(fileId).expiresIn(3600));
```

返回 `CdnLink(url, expiresIn, permanent, contentType)`。`url` 是稳定的公开地址，可直接用于 `<img>`、`<audio>`、`<video>`；SDK 调用方不需要解析或修改对象存储预签名 URL。永久链接会在服务端访问时动态换取短期对象存储签名，删除文件后自动失效。文件不属于当前应用或不是图片、音频、视频时，服务端拒绝生成链接。

## 完整示例（含异常处理）

```java
ContentCenterClient client = ContentCenterClient.builder()
        .baseUrl("https://file.example.com").appToken(token).build();

try {
    UploadResult result = client.uploadMultipart(Path.of("/data/video.mp4"),
            MultipartOptions.defaults().source("minio").partSizeBytes(10 * 1024 * 1024));

    DownloadLink link = client.getDownloadLink(
            DownloadLinkRequest.ofFileId(result.fileId()).expiresIn(3600));
    // ... 把 link.url() 发给调用方
} catch (ContentCenterException e) {
    if (e.getStatus() == 401) {
        // appToken 无效/已轮换/应用被禁用 → 联系管理员，勿自动重试
    } else if (e.getStatus() == 429) {
        // 触发限流 → 退避后重试
    } else if (e.getStatus() == -1) {
        // 网络层失败 → 可重试（uploadMultipart 会自动续传）
    } else {
        // 其余按 message 处理
    }
}
```

## 错误处理

非 2xx 响应统一抛 `ContentCenterException`：`getStatus()` 返回 HTTP 状态码（**-1 表示连接失败/中断等传输层错误**），message 来自服务端 `ApiError{message}`（中文，可直接展示/记日志）；401 的 message 会附带「appToken 可能已轮换或应用被禁用」提示。

| 状态码 | 含义 | 建议 |
|---|---|---|
| 400 | 参数非法 / source 未启用 / 对象未上传即确认 / 分片校验失败 / path 含非法段 | 看 message 修参数 |
| 401 | appToken 缺失、无效、已轮换或应用被禁用 | 找管理员确认 token，勿重试 |
| 404 | fileId/storageKey 不存在或不属于本应用 | 检查 id 是否来自本应用的上传结果 |
| 429 | 触发限流（IP 维度令牌桶；分片签名端点阈值更高） | 退避重试 |
| 500 | 服务端错误（如管理员正在做 rootPath 迁移且失败） | 稍后重试或联系管理员 |

## 行为与约束（FAQ）

- **上传落点**：文件落在管理员为应用配置的「上传根路径」（`rootPath`，默认 `开放应用/<应用名>`）+ 你传入的 `path`。修改根路径、迁移文件等由服务端管理端完成，对 SDK 透明。
- **`source` 怎么选**：不传 = 该应用在管理端「开放应用」配置的默认数据源（管理员按应用配置；未配置兜底 `oss`）。传错值（未启用的数据源）会得到 400。
- **为什么分片上传只能 MinIO**：分片能力取决于服务端数据源，当前仅 MinIO 提供（OSS 走简单直传）。
- **幂等性**：简单上传每次调用都会产生新文件（storageKey 含时间戳-uuid 防覆盖）；分片上传按整文件 MD5 幂等，同文件重试不会重复占空间。
- **并发**：多个线程可同时用同一 client 实例；分片上传内部为串行逐片上传。
- **数据归属**：应用只能下载/确认**自己上传**的文件（fileId 越权访问返回 404）。

## 版本与发布

- 版本号取自 `sdk/pom.xml`，仅允许**正式版本**（无 SNAPSHOT）；**同一版本只发布一次，不可覆盖**（CI 发布前会校验）。升级 SDK = 改 pom 版本号 + 发布新版本。
- 发布由 CI 完成（`.github/workflows/sdk-release.yml`）：在 Actions 页面手动触发，或在 `Deploy to Server` 工作流手动触发时选择模块 `sdk`。发布前自动跑全部测试。
- 消费方升级：改 pom 中 SDK 的 `<version>` 即可，API 变更见本文件「服务端契约」。

## 服务端契约（v0.1）

SDK 封装以下端点（均携带 `Authorization: Bearer <appToken>`，协议细节与 curl 示例见服务端仓库 `docs/open-api.md`）：

| SDK 方法 | 端点 |
|---|---|
| `upload` | `POST /api/open/uploads` → PUT 预签名 URL → `POST /api/open/uploads/complete` |
| `initUpload` / `completeUpload` | 拆分调用简单上传 init/complete，供浏览器直传 |
| `uploadMultipart` | `POST /api/open/uploads/multipart/init` →（`/sign` + PUT）× N → `POST /api/open/uploads/multipart/complete` |
| `initMultipartUpload` / `signMultipartPart` / `completeMultipartUpload` | 拆分调用分片端点，供浏览器直传 |
| `getDownloadLink` | `POST /api/open/download-links` |
| `getCdnLink` | `POST /api/open/cdn-links` |

修改 SDK 契约需同步更新服务端 `docs/open-api.md` 与本文件。
