# Content Center Python SDK

同步 Python 客户端，用于调用 k-File 内容中心开放文件 API。项目使用 uv 管理依赖，要求 Python 3.11 或更新版本。

## 安装与测试

在本仓库中同步开发环境：

```bash
cd sdk/python
uv sync --locked
uv run pytest
```

其他 uv 项目可以直接引用本地包：

```bash
uv add ../k-file/sdk/python
```

发布到 Python 包仓库后，也可以使用包名 `content-center-sdk` 添加依赖。

发布到 PyPI 前先升级 `pyproject.toml` 中的版本，然后执行：

```bash
uv version --bump patch
uv sync --locked
uv run pytest
uv build --no-sources
export UV_PUBLISH_TOKEN="pypi-xxxxxxxx"
uv publish
unset UV_PUBLISH_TOKEN
```

仓库的 `sdk-py` 发布 job 会执行同样的构建和测试，并使用 GitHub Secret `PYPI_API_TOKEN` 发布；输入版本（如果填写）必须与 `pyproject.toml` 一致。

## 客户端与简单上传

客户端是同步的，并支持上下文管理器关闭 HTTP 连接：

```python
from pathlib import Path

from content_center_sdk import ContentCenterClient, UploadOptions

with ContentCenterClient(
    "https://file.example.com",
    "kapp_xxx",
) as client:
    result = client.upload(
        Path("report.pdf"),
        UploadOptions(
            source="oss",
            path="reports/2026",
            content_type="application/pdf",
        ),
    )
    print(result.file_id, result.storage_key)
```

需要浏览器直传时，先调用 `init_upload`，让浏览器 PUT 到返回的 `put_url`，再调用 `complete_upload`。对已打开的二进制文件对象使用 `upload_fileobj`。

## 分片断点续传

`upload_multipart` 会计算整文件 MD5，以 MinIO 为例初始化上传，跳过服务端已记录的分片，上传缺失部分并提交 ETag：

```python
from content_center_sdk import MultipartOptions

with ContentCenterClient("https://file.example.com", "kapp_xxx") as client:
    result = client.upload_multipart(
        "video.mp4",
        MultipartOptions(source="minio", part_size_bytes=10 * 1024 * 1024),
    )
```

分片大小会提升到服务端要求的 5 MiB 最小值。上传按顺序执行，客户端关闭或请求失败后，用同一文件再次调用即可续传。

## 下载与 CDN 预览

```python
from content_center_sdk import CdnLinkRequest, DownloadLinkRequest

download = client.get_download_link(
    DownloadLinkRequest(file_id=result.file_id, filename="report.pdf", expires_in=300)
)
preview = client.get_cdn_link(CdnLinkRequest(file_id=result.file_id))
print(download.url, preview.url)
```

也可以用 `DownloadLinkRequest.by_key(storage_key, source)` 按 storage key 获取下载链接。服务端返回的 URL 会原样返回。

## 错误与凭证安全

非 2xx 响应会抛出 `ContentCenterError`，通过 `status` 和 `message` 获取 HTTP 状态与服务端 `ApiError.message`。网络、序列化和响应解析失败的 `status` 为 `-1`；401 通常表示 appToken 无效、已轮换或应用已禁用。

SDK 只向内容中心 API 请求发送 `Authorization: Bearer <appToken>`，不会把 appToken 发送到预签名对象存储 PUT URL。
