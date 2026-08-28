import pytest
import httpx
import hashlib
import json
from io import BytesIO

from content_center_sdk import (
    CdnLinkRequest,
    ContentCenterClient,
    ContentCenterError,
    DownloadLinkRequest,
    MultipartOptions,
    UploadOptions,
)


def test_client_rejects_missing_configuration() -> None:
    with pytest.raises(ContentCenterError):
        ContentCenterClient("", "token")
    with pytest.raises(ContentCenterError):
        ContentCenterClient("https://file.example", "")


def test_api_request_injects_bearer_and_parses_api_error() -> None:
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers["Authorization"]
        captured["content_type"] = request.headers["Content-Type"]
        return httpx.Response(
            400,
            json={"message": "未知或未启用的数据源: minio"},
            request=request,
        )

    transport = httpx.MockTransport(handler)
    with ContentCenterClient("https://file.example", "kapp_test", transport=transport) as client:
        with pytest.raises(ContentCenterError) as exc_info:
            client.init_upload("a.txt")

    assert exc_info.value.status == 400
    assert exc_info.value.message == "未知或未启用的数据源: minio"
    assert captured == {
        "authorization": "Bearer kapp_test",
        "content_type": "application/json",
    }


def test_unauthorized_error_hints_token_rotation() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"message": "无权限，请登录"}, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        with pytest.raises(ContentCenterError, match="轮换"):
            client.init_upload("a.txt")


def test_upload_fileobj_runs_three_steps_and_keeps_bearer_off_put() -> None:
    paths: list[str] = []
    captured: dict[str, bytes | str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        if request.url.path == "/api/open/uploads":
            assert request.headers["Authorization"] == "Bearer kapp_test"
            return httpx.Response(
                200,
                json={
                    "storageKey": "k1",
                    "source": "oss",
                    "putUrl": "https://storage.example/put",
                    "expiresIn": 600,
                    "fileId": 1,
                },
                request=request,
            )
        if request.url.path == "/put":
            captured["authorization"] = request.headers.get("Authorization", "")
            captured["content_type"] = request.headers["Content-Type"]
            captured["body"] = request.content
            return httpx.Response(200, request=request)
        if request.url.path == "/api/open/uploads/complete":
            return httpx.Response(
                200,
                json={"fileId": 1, "name": "a.txt", "size": 3, "contentType": "text/plain"},
                request=request,
            )
        return httpx.Response(404, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        result = client.upload_fileobj(
            BytesIO(b"abc"),
            "a.txt",
            3,
            UploadOptions(source="oss", path="reports", content_type="text/plain"),
        )

    assert result.file_id == 1
    assert result.storage_key == "k1"
    assert result.source == "oss"
    assert paths == ["/api/open/uploads", "/put", "/api/open/uploads/complete"]
    assert captured == {
        "authorization": "",
        "content_type": "text/plain",
        "body": b"abc",
    }


def test_upload_put_failure_does_not_call_complete() -> None:
    complete_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal complete_calls
        if request.url.path == "/api/open/uploads":
            return httpx.Response(
                200,
                json={
                    "storageKey": "k1",
                    "source": "oss",
                    "putUrl": "https://storage.example/put-fail",
                    "expiresIn": 600,
                    "fileId": 1,
                },
                request=request,
            )
        if request.url.path == "/put-fail":
            return httpx.Response(403, request=request)
        if request.url.path == "/api/open/uploads/complete":
            complete_calls += 1
        return httpx.Response(200, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        with pytest.raises(ContentCenterError) as exc_info:
            client.upload_fileobj(BytesIO(b"abc"), "a.txt", 3)

    assert exc_info.value.status == 403
    assert complete_calls == 0


def test_upload_multipart_resumes_missing_parts_and_completes_with_etags(tmp_path) -> None:
    data = b"a" * (5 * 1024 * 1024) + b"xyz"
    path = tmp_path / "sample.bin"
    path.write_bytes(data)
    expected_md5 = hashlib.md5(data).hexdigest()
    init_body: dict[str, object] = {}
    complete_body: dict[str, object] = {}
    part_uploads: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/open/uploads/multipart/init":
            init_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "uploadId": "u",
                    "chunkKeyPrefix": "p",
                    "storageKey": "sk",
                    "totalChunks": 2,
                    "fileId": 9,
                    "uploadedParts": [{"partNumber": 1, "etag": "e1"}],
                    "alreadyDone": False,
                },
                request=request,
            )
        if request.url.path == "/api/open/uploads/multipart/sign":
            assert json.loads(request.content)["chunkId"] == 1
            return httpx.Response(
                200,
                json={"url": "https://storage.example/part"},
                request=request,
            )
        if request.url.path == "/part":
            assert request.headers.get("Authorization") is None
            part_uploads.append(request.content)
            return httpx.Response(200, headers={"ETag": '"e2"'}, request=request)
        if request.url.path == "/api/open/uploads/multipart/complete":
            complete_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={"storageKey": "sk", "fileId": 9, "size": len(data)},
                request=request,
            )
        return httpx.Response(404, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        result = client.upload_multipart(path, MultipartOptions(source="minio"))

    assert result.file_id == 9
    assert result.storage_key == "sk"
    assert result.size == len(data)
    assert init_body == {
        "originalName": "sample.bin",
        "size": len(data),
        "totalChunks": 2,
        "contentMd5": expected_md5,
        "source": "minio",
    }
    assert part_uploads == [b"xyz"]
    assert complete_body == {
        "contentMd5": expected_md5,
        "parts": [
            {"chunkId": 0, "etag": "e1"},
            {"chunkId": 1, "etag": "e2"},
        ],
    }


def test_upload_multipart_already_done_skips_signing_and_complete(tmp_path) -> None:
    path = tmp_path / "done.bin"
    path.write_bytes(b"already uploaded")
    sign_calls = 0
    complete_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal sign_calls, complete_calls
        if request.url.path == "/api/open/uploads/multipart/init":
            return httpx.Response(
                200,
                json={"storageKey": "sk", "totalChunks": 1, "fileId": 7, "alreadyDone": True},
                request=request,
            )
        if request.url.path == "/api/open/uploads/multipart/sign":
            sign_calls += 1
        if request.url.path == "/api/open/uploads/multipart/complete":
            complete_calls += 1
        return httpx.Response(200, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        result = client.upload_multipart(path, MultipartOptions(source="minio"))

    assert result.file_id == 7
    assert result.storage_key == "sk"
    assert result.size == len(b"already uploaded")
    assert sign_calls == 0
    assert complete_calls == 0


def test_get_download_and_cdn_links_preserve_server_urls() -> None:
    captured: dict[str, dict[str, object]] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if request.url.path == "/api/open/download-links":
            captured["download"] = body
            return httpx.Response(
                200,
                json={"url": "https://storage.example/download?a=1", "expiresIn": 300},
                request=request,
            )
        if request.url.path == "/api/open/cdn-links":
            captured["cdn"] = body
            return httpx.Response(
                200,
                json={
                    "url": "https://file.example/file/cdn/token",
                    "expiresIn": 0,
                    "permanent": True,
                    "contentType": "image/png",
                },
                request=request,
            )
        return httpx.Response(404, request=request)

    with ContentCenterClient(
        "https://file.example", "kapp_test", transport=httpx.MockTransport(handler)
    ) as client:
        download_request = DownloadLinkRequest.by_file_id(9)
        download_request.filename = "report.pdf"
        download_request.expires_in = 300
        download = client.get_download_link(download_request)
        cdn = client.get_cdn_link(CdnLinkRequest(file_id=9))

    assert download.url == "https://storage.example/download?a=1"
    assert download.expires_in == 300
    assert cdn.url == "https://file.example/file/cdn/token"
    assert cdn.permanent is True
    assert cdn.content_type == "image/png"
    assert captured["download"] == {
        "fileId": 9,
        "filename": "report.pdf",
        "expiresIn": 300,
    }
    assert captured["cdn"] == {"fileId": 9}
