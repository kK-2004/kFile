from __future__ import annotations


class ContentCenterError(Exception):
    """An error returned by the content center or the presigned storage URL."""

    def __init__(self, status: int, message: str, cause: Exception | None = None) -> None:
        self.status = status
        self.message = message
        self.cause = cause
        super().__init__(message)

    def __str__(self) -> str:
        return self.message
