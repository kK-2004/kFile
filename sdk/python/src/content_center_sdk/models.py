from __future__ import annotations

from dataclasses import dataclass


DEFAULT_PART_SIZE = 5 * 1024 * 1024


@dataclass(slots=True)
class UploadOptions:
    source: str | None = None
    path: str | None = None
    content_type: str | None = None


@dataclass(slots=True)
class MultipartOptions:
    source: str | None = None
    path: str | None = None
    content_type: str | None = None
    part_size_bytes: int = DEFAULT_PART_SIZE


@dataclass(slots=True)
class UploadInit:
    storage_key: str
    source: str
    put_url: str
    expires_in: int
    file_id: int | None


@dataclass(slots=True)
class UploadResult:
    file_id: int | None
    name: str
    size: int
    content_type: str | None
    storage_key: str
    source: str


@dataclass(slots=True)
class UploadedPart:
    part_number: int
    etag: str


@dataclass(slots=True)
class MultipartInit:
    upload_id: str
    chunk_key_prefix: str
    storage_key: str
    total_chunks: int
    file_id: int | None
    uploaded_parts: list[UploadedPart]
    already_done: bool


@dataclass(slots=True)
class MultipartPart:
    chunk_id: int
    etag: str


@dataclass(slots=True)
class MultipartComplete:
    storage_key: str
    file_id: int | None
    size: int


@dataclass(slots=True)
class DownloadLinkRequest:
    file_id: int | None = None
    key: str | None = None
    source: str | None = None
    filename: str | None = None
    expires_in: int | None = None

    @classmethod
    def by_file_id(cls, file_id: int) -> "DownloadLinkRequest":
        return cls(file_id=file_id)

    @classmethod
    def by_key(cls, key: str, source: str) -> "DownloadLinkRequest":
        return cls(key=key, source=source)


@dataclass(slots=True)
class DownloadLink:
    url: str
    expires_in: int


@dataclass(slots=True)
class CdnLinkRequest:
    file_id: int
    expires_in: int | None = None


@dataclass(slots=True)
class CdnLink:
    url: str
    expires_in: int
    permanent: bool
    content_type: str | None
