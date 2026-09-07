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
__all__ = [
    "ErrorResponse",
    "MockApiError",
    "GenAiApiError",
    "register_exception_handlers",
]


class MockApiError(Exception):
    """목 서버 규격 오류 — 상태코드 + 사용자 노출 가능 메시지."""

    def __init__(self, status_code: int, message: str, error_code: str = "MOCK_API_ERROR",
                 vendor_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.error_code = error_code
        # 사업자 규격이 <특정 상황에만> 본문에 싣는 숫자 코드(예: 정의되지 않은 event_type).
        #   ★ 그 밖의 오류에는 실리지 않는 것이 규격이므로 기본값은 None 이다 — 늘 실으면
        #     연동 시스템이 그 코드로 사유를 가르지 못한다.
        self.vendor_code = vendor_code


class GenAiApiError(MockApiError):
    """생성형 AI 명세서(§3.3) 오류.

    응답 본문에 명세서 필드 ``code`` 와 목 서버 공통 필드 ``error_code`` 를 **동일 값**으로
    함께 싣는다(명세서 소비자와 기존 목 공통 규격 양쪽 호환).
    """

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(status_code, message, code)
        self.code = code


def _err(error_code: str, message: str, status_code: int,
         vendor_code: int | None = None, detail: str | None = None) -> JSONResponse:
    payload = ErrorResponse(error_code=error_code, message=message).model_dump()
    # 사업자 규격 형태(detail · 조건부 code)를 함께 싣는다 — 목 공통 필드는 그대로 두어
    #   기존 소비자를 깨지 않는다(둘은 같은 사실의 두 표기다).
    if detail is not None:
        payload["detail"] = detail
    if vendor_code is not None:
        payload["code"] = vendor_code
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
        return _err(exc.error_code, exc.message, exc.status_code,
                    vendor_code=getattr(exc, "vendor_code", None), detail=exc.message)

    @app.exception_handler(GenAiApiError)
    async def _genai_api_error(_: Request, exc: GenAiApiError) -> JSONResponse:
        logger.warning(
            "[MOCK][GENAI] api error code=%s message=%s",
            sanitize_for_log(exc.code),
            sanitize_for_log(exc.message),
        )
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": exc.code, "error_code": exc.code, "message": exc.message},
        )

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
