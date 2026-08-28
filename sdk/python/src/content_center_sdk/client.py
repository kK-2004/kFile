from __future__ import annotations

import json
import hashlib
from collections.abc import Mapping
from pathlib import Path
from typing import Any, BinaryIO

import httpx

from .errors import ContentCenterError
from .models import (
    CdnLink,
    CdnLinkRequest,
    DownloadLink,
    DownloadLinkRequest,
    MultipartComplete,
    MultipartInit,
    MultipartOptions,
    MultipartPart,
    UploadInit,
    UploadOptions,
    UploadResult,
    UploadedPart,
)


class ContentCenterClient:
    """Synchronous client for the content center open API."""

    def __init__(
        self,
        base_url: str,
        app_token: str,
        *,
        timeout: float = 600.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        normalized_base_url = base_url.strip().rstrip("/")
        if not normalized_base_url:
            raise ContentCenterError(-1, "base_url cannot be empty")
        if not app_token.strip():
            raise ContentCenterError(-1, "app_token cannot be empty")
        self._base_url = normalized_base_url
        self._app_token = app_token.strip()
        self._client = httpx.Client(timeout=timeout, transport=transport)

    def __enter__(self) -> "ContentCenterClient":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.close()

    def close(self) -> None:
        self._client.close()

    def _post_json(self, path: str, payload: Mapping[str, Any]) -> dict[str, Any]:
        try:
            response = self._client.post(
                f"{self._base_url}{path}",
                headers={
                    "Authorization": f"Bearer {self._app_token}",
                    "Content-Type": "application/json",
                },
                json=dict(payload),
            )
        except httpx.HTTPError as exc:
            raise ContentCenterError(-1, f"network request failed: {exc}", exc) from exc
        return self._parse_response(response)

    def _put_presigned(self, url: str, content: Any, content_type: str) -> str:
        try:
            response = self._client.put(
                url,
                headers={"Content-Type": content_type},
                content=content,
            )
        except httpx.HTTPError as exc:
            raise ContentCenterError(-1, f"presigned PUT failed: {exc}", exc) from exc
        self._raise_for_error(response)
        return response.headers.get("ETag", "").strip('"')

    @staticmethod
    def _parse_response(response: httpx.Response) -> dict[str, Any]:
        if response.is_error:
            ContentCenterClient._raise_for_error(response)
        try:
            data = response.json()
        except (json.JSONDecodeError, ValueError) as exc:
            raise ContentCenterError(-1, f"failed to decode response: {exc}", exc) from exc
        if not isinstance(data, dict):
            raise ContentCenterError(-1, "response JSON must be an object")
        return data

    @staticmethod
    def _raise_for_error(response: httpx.Response) -> None:
        if 200 <= response.status_code < 300:
            return
        message: str | None = None
        try:
            body = response.json()
            if isinstance(body, dict) and body.get("message") is not None:
                message = str(body["message"])
        except (json.JSONDecodeError, ValueError):
            pass
        if not message:
            if response.status_code == 401:
                message = f"appToken 无效、已轮换或应用被禁用 (HTTP {response.status_code})"
            else:
                message = f"请求失败 (HTTP {response.status_code})"
        elif response.status_code == 401:
            message += "（appToken 可能已轮换或应用被禁用）"
        raise ContentCenterError(response.status_code, message)

    @staticmethod
    def _upload_payload(filename: str, size: int | None, options: UploadOptions) -> dict[str, Any]:
        payload: dict[str, Any] = {"originalName": filename}
        if size is not None:
            payload["size"] = size
        if options.content_type is not None:
            payload["contentType"] = options.content_type
        if options.path is not None:
            payload["path"] = options.path
        if options.source is not None:
            payload["source"] = options.source
        return payload

    def init_upload(
        self,
        filename: str,
        size: int | None = None,
        options: UploadOptions | None = None,
    ) -> UploadInit:
        payload = self._upload_payload(filename, size, options or UploadOptions())
        data = self._post_json("/api/open/uploads", payload)
        return UploadInit(
            storage_key=str(data["storageKey"]),
            source=str(data["source"]),
            put_url=str(data["putUrl"]),
            expires_in=int(data.get("expiresIn", 0)),
            file_id=int(data["fileId"]) if data.get("fileId") is not None else None,
        )

    def complete_upload(self, storage_key: str, source: str) -> UploadResult:
        data = self._post_json(
            "/api/open/uploads/complete",
            {"storageKey": storage_key, "source": source},
        )
        return UploadResult(
            file_id=int(data["fileId"]) if data.get("fileId") is not None else None,
            name=str(data["name"]),
            size=int(data["size"]),
            content_type=str(data["contentType"]) if data.get("contentType") is not None else None,
            storage_key=storage_key,
            source=source,
        )

    def upload_fileobj(
        self,
        fileobj: BinaryIO,
        filename: str,
        size: int | None = None,
        options: UploadOptions | None = None,
    ) -> UploadResult:
        effective_options = options or UploadOptions()
        init = self.init_upload(filename, size, effective_options)
        content_type = effective_options.content_type or "application/octet-stream"
        self._put_presigned(init.put_url, fileobj, content_type)
        return self.complete_upload(init.storage_key, init.source)

    def upload(self, path: str | Path, options: UploadOptions | None = None) -> UploadResult:
        file_path = Path(path)
        try:
            size = file_path.stat().st_size
            with file_path.open("rb") as fileobj:
                return self.upload_fileobj(fileobj, file_path.name, size, options)
        except OSError as exc:
            raise ContentCenterError(-1, f"failed to read local file: {exc}", exc) from exc

    def init_multipart_upload(
        self,
        filename: str,
        file_size: int,
        total_chunks: int,
        content_md5: str,
        options: MultipartOptions | None = None,
    ) -> MultipartInit:
        effective_options = options or MultipartOptions()
        payload: dict[str, Any] = {
            "originalName": filename,
            "size": file_size,
            "totalChunks": total_chunks,
            "contentMd5": content_md5,
        }
        payload.update(self._upload_payload(filename, None, UploadOptions(
            source=effective_options.source,
            path=effective_options.path,
            content_type=effective_options.content_type,
        )))
        data = self._post_json("/api/open/uploads/multipart/init", payload)
        parts = [
            UploadedPart(part_number=int(part["partNumber"]), etag=str(part["etag"]))
            for part in data.get("uploadedParts", [])
        ]
        return MultipartInit(
            upload_id=str(data.get("uploadId", "")),
            chunk_key_prefix=str(data.get("chunkKeyPrefix", "")),
            storage_key=str(data.get("storageKey", "")),
            total_chunks=int(data.get("totalChunks", total_chunks)),
            file_id=int(data["fileId"]) if data.get("fileId") is not None else None,
            uploaded_parts=parts,
            already_done=bool(data.get("alreadyDone", False)),
        )

    def sign_multipart_part(self, content_md5: str, chunk_id: int) -> str:
        data = self._post_json(
            "/api/open/uploads/multipart/sign",
            {"contentMd5": content_md5, "chunkId": chunk_id},
        )
        url = str(data.get("url", ""))
        if not url:
            raise ContentCenterError(-1, "multipart sign response is missing url")
        return url

    def complete_multipart_upload(self, content_md5: str, parts: list[MultipartPart]) -> MultipartComplete:
        data = self._post_json(
            "/api/open/uploads/multipart/complete",
            {
                "contentMd5": content_md5,
                "parts": [{"chunkId": part.chunk_id, "etag": part.etag} for part in parts],
            },
        )
        return MultipartComplete(
            storage_key=str(data["storageKey"]),
            file_id=int(data["fileId"]) if data.get("fileId") is not None else None,
            size=int(data["size"]),
        )

    def upload_multipart(
        self,
        path: str | Path,
        options: MultipartOptions | None = None,
    ) -> UploadResult:
        file_path = Path(path)
        effective_options = options or MultipartOptions()
        try:
            file_size = file_path.stat().st_size
            with file_path.open("rb") as fileobj:
                digest = hashlib.md5()
                while chunk := fileobj.read(1024 * 1024):
                    digest.update(chunk)
                content_md5 = digest.hexdigest()
                fileobj.seek(0)

                part_size = max(effective_options.part_size_bytes, 5 * 1024 * 1024)
                total_chunks = (file_size + part_size - 1) // part_size
                init = self.init_multipart_upload(
                    file_path.name,
                    file_size,
                    total_chunks,
                    content_md5,
                    effective_options,
                )
                if init.already_done:
                    return UploadResult(
                        file_id=init.file_id,
                        name=file_path.name,
                        size=file_size,
                        content_type=effective_options.content_type,
                        storage_key=init.storage_key,
                        source=effective_options.source or "",
                    )

                etags = {
                    part.part_number: part.etag.strip('"')
                    for part in init.uploaded_parts
                    if part.part_number > 0 and part.etag.strip('"')
                }
                for chunk_id in range(total_chunks):
                    part_number = chunk_id + 1
                    if part_number in etags:
                        continue
                    fileobj.seek(chunk_id * part_size)
                    part = fileobj.read(part_size)
                    if not part:
                        raise ContentCenterError(-1, f"multipart part {part_number} is empty")
                    url = self.sign_multipart_part(content_md5, chunk_id)
                    etag = self._put_presigned(url, part, "application/octet-stream")
                    if not etag:
                        raise ContentCenterError(-1, f"multipart part {part_number} response is missing ETag")
                    etags[part_number] = etag

                parts = [
                    MultipartPart(chunk_id=chunk_id, etag=etags[chunk_id + 1])
                    for chunk_id in range(total_chunks)
                ]
                complete = self.complete_multipart_upload(content_md5, parts)
                return UploadResult(
                    file_id=complete.file_id or init.file_id,
                    name=file_path.name,
                    size=complete.size,
                    content_type=effective_options.content_type,
                    storage_key=complete.storage_key or init.storage_key,
                    source=effective_options.source or "",
                )
        except OSError as exc:
            raise ContentCenterError(-1, f"failed to read local file: {exc}", exc) from exc

    def get_download_link(self, request: DownloadLinkRequest) -> DownloadLink:
        payload = {
            key: value
            for key, value in {
                "fileId": request.file_id,
                "key": request.key,
                "source": request.source,
                "filename": request.filename,
                "expiresIn": request.expires_in,
            }.items()
            if value is not None
        }
        data = self._post_json("/api/open/download-links", payload)
        return DownloadLink(url=str(data["url"]), expires_in=int(data["expiresIn"]))

    def get_cdn_link(self, request: CdnLinkRequest) -> CdnLink:
        payload: dict[str, Any] = {"fileId": request.file_id}
        if request.expires_in is not None:
            payload["expiresIn"] = request.expires_in
        data = self._post_json("/api/open/cdn-links", payload)
        return CdnLink(
            url=str(data["url"]),
            expires_in=int(data["expiresIn"]),
            permanent=bool(data["permanent"]),
            content_type=str(data["contentType"]) if data.get("contentType") is not None else None,
        )
