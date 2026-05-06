"""
공통 예외 + 핸들러.

OWASP A05/A08 — 예외 메시지에 스택트레이스/내부 경로 노출 금지.
모든 예외는 ErrorResponse 형식으로 변환된다.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas import ErrorResponse

logger = logging.getLogger(__name__)


class InvalidImageError(Exception):
    """base64 디코드 실패 또는 지원하지 않는 이미지 형식."""


class ImageTooLargeError(Exception):
    """이미지 크기 한도 초과."""


def _err(error_code: str, message: str, status_code: int) -> JSONResponse:
    payload = ErrorResponse(error_code=error_code, message=message).model_dump()
    return JSONResponse(status_code=status_code, content=payload)


def register_exception_handlers(app: FastAPI) -> None:
    """FastAPI 앱에 공통 예외 핸들러를 등록한다."""

    @app.exception_handler(InvalidImageError)
    async def _invalid_image(_: Request, exc: InvalidImageError) -> JSONResponse:
        logger.warning("[AI] invalid image error message=%s", str(exc))
        return _err("INVALID_IMAGE", str(exc) or "유효하지 않은 이미지", status.HTTP_400_BAD_REQUEST)

    @app.exception_handler(ImageTooLargeError)
    async def _image_too_large(_: Request, exc: ImageTooLargeError) -> JSONResponse:
        logger.warning("[AI] image too large message=%s", str(exc))
        return _err(
            "IMAGE_TOO_LARGE",
            str(exc) or "이미지 크기 한도 초과",
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        # 사용자 입력 메시지는 길어질 수 있으므로 첫 에러만 노출
        first = exc.errors()[0] if exc.errors() else {"msg": "validation error"}
        return _err(
            "VALIDATION_ERROR",
            f"{first.get('msg', 'validation error')}",
            status.HTTP_400_BAD_REQUEST,
        )

    @app.exception_handler(HTTPException)
    async def _http_exc(_: Request, exc: HTTPException) -> JSONResponse:
        return _err("HTTP_ERROR", str(exc.detail), exc.status_code)

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        # 스택트레이스/내부 경로 노출 금지 (security.md)
        logger.exception("[AI] unhandled exception type=%s", type(exc).__name__)
        return _err("INTERNAL_ERROR", "서버 내부 오류", status.HTTP_500_INTERNAL_SERVER_ERROR)
