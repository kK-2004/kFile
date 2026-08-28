# Content Center Go SDK

Go client for the k-File open file API. The module uses only the Go standard library and supports Go 1.22 or newer.

## Install

```bash
go get github.com/kK-2004/kFile/sdk/go
```

发布时由 CI 创建 `sdk/go/vX.Y.Z` Git tag；消费方仍使用 `@vX.Y.Z` 获取对应版本：

```bash
go get github.com/kK-2004/kFile/sdk/go@v0.1.0
```

## Simple upload

```go
package main

import (
    "context"
    "log"

    contentcentersdk "github.com/kK-2004/kFile/sdk/go"
)

func main() {
    client, err := contentcentersdk.NewClient(contentcentersdk.ClientConfig{
        BaseURL:  "https://file.example.com",
        AppToken: "kapp_xxx",
    })
    if err != nil {
        log.Fatal(err)
    }

    result, err := client.Upload(context.Background(), "report.pdf", contentcentersdk.UploadOptions{
        Source:      "oss",
        Path:        "reports/2026",
        ContentType: "application/pdf",
    })
    if err != nil {
        log.Fatal(err)
    }
    log.Printf("fileId=%d storageKey=%s", result.FileID, result.StorageKey)
}
```

For browser or frontend direct upload, call `InitUpload`, let the browser PUT to the returned `PutURL`, then call `CompleteUpload`. For an `io.Reader`, use `UploadReader` and pass the optional size pointer.

## Multipart resume

`UploadMultipart` calculates the whole-file MD5, requests the server's uploaded-part list, skips completed parts, and completes the upload with all ETags. It is intended for a MinIO source:

```go
result, err := client.UploadMultipart(ctx, "video.mp4", contentcentersdk.MultipartOptions{
    Source:        "minio",
    PartSizeBytes: 10 * 1024 * 1024,
})
```

The part size is clamped to the 5 MiB minimum required by the service contract. Uploads are serial and accept `context.Context` cancellation.

## Download and CDN links

```go
request := contentcentersdk.DownloadLinkByFileID(result.FileID)
filename := "report.pdf"
request.Filename = &filename
link, err := client.GetDownloadLink(ctx, request)

preview, err := client.GetCDNLink(ctx, contentcentersdk.CDNLinkByFileID(result.FileID))
```

`GetDownloadLink` also accepts `DownloadLinkByKey(storageKey, source)`. URLs returned by the service are passed through unchanged.

## Errors and credentials

Non-2xx responses return `*ContentCenterError`. Inspect `Status` and `Message`; network, serialization, and response parsing errors use `Status == -1`. A 401 indicates an invalid, rotated, or disabled appToken.

The SDK sends `Authorization: Bearer <appToken>` only to content-center API requests. It deliberately does not send the appToken to presigned object-storage PUT URLs.

## Test locally

```bash
cd sdk/go
GOCACHE=/private/tmp/kfile-go-cache go test ./...
```
