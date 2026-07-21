"""
공통 예외 + 핸들러.

OWASP A08/A10 — 예외 메시지에 스택트레이스/내부 경로를 노출하지 않는다 (CWE-209).
모든 예외는 ErrorResponse 형식으로 변환한다.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas.common import ErrorResponse
from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

# 하위호환 — 기존에 ``app.exceptions.ErrorResponse`` 로 참조하던 경로 유지.
__all__ = ["ErrorResponse", "MockApiError", "register_exception_handlers"]


class MockApiError(Exception):
    """목 서버 규격 오류 — 상태코드 + 사용자 노출 가능 메시지."""

    def __init__(self, status_code: int, message: str, error_code: str = "MOCK_API_ERROR") -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.error_code = error_code


def _err(error_code: str, message: str, status_code: int) -> JSONResponse:
    payload = ErrorResponse(error_code=error_code, message=message).model_dump()
    return JSONResponse(status_code=status_code, content=payload)


def register_exception_handlers(app: FastAPI) -> None:
    """FastAPI 앱에 공통 예외 핸들러를 등록한다."""

    @app.exception_handler(MockApiError)
    async def _mock_api_error(_: Request, exc: MockApiError) -> JSONResponse:
        logger.warning(
            "[MOCK] api error code=%s message=%s",
            sanitize_for_log(exc.error_code),
            sanitize_for_log(exc.message),
        )
        return _err(exc.error_code, exc.message, exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        # 사용자 입력 메시지는 길 수 있으므로 첫 에러만 노출
        first = exc.errors()[0] if exc.errors() else {"msg": "validation error"}
        return _err(
            "VALIDATION_ERROR",
            str(first.get("msg", "validation error")),
            status.HTTP_400_BAD_REQUEST,
        )

    @app.exception_handler(HTTPException)
    async def _http_exc(_: Request, exc: HTTPException) -> JSONResponse:
        return _err("HTTP_ERROR", str(exc.detail), exc.status_code)

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        # 스택트레이스/내부 경로 노출 금지 (security.md CWE-209)
        logger.exception("[MOCK] unhandled exception type=%s", type(exc).__name__)
        return _err("INTERNAL_ERROR", "서버 내부 오류", status.HTTP_500_INTERNAL_SERVER_ERROR)
