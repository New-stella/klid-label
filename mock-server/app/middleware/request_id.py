"""
X-Request-Id 미들웨어.

- 요청 헤더 X-Request-Id가 있으면 반사, 없거나 안전하지 않으면 12자리 hex uuid 생성
- contextvars에 저장하여 핸들러/로거에서 접근 가능
- 응답 헤더에도 동일 값 포함
- CRLF 등 헤더 변조 방지 (CWE-113 Header Manipulation)
"""

from __future__ import annotations

import logging
import uuid
from contextvars import ContextVar

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

REQUEST_ID_HEADER = "X-Request-Id"
_request_id_ctx: ContextVar[str] = ContextVar("request_id", default="-")

logger = logging.getLogger(__name__)


def get_request_id() -> str:
    """현재 요청의 request id 반환 (없으면 '-')."""
    return _request_id_ctx.get()


def _generate_id() -> str:
    return uuid.uuid4().hex[:12]


def _is_safe_id(value: str) -> bool:
    if not value or len(value) > 64:
        return False
    return all(c.isalnum() or c in "-_" for c in value)


class RequestIdMiddleware(BaseHTTPMiddleware):
    """X-Request-Id 헤더 처리."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        rid = request.headers.get(REQUEST_ID_HEADER) or _generate_id()
        # 헤더 변조 방지 — 영숫자/하이픈/언더스코어만 허용
        if not _is_safe_id(rid):
            rid = _generate_id()
        token = _request_id_ctx.set(rid)
        try:
            response = await call_next(request)
        finally:
            _request_id_ctx.reset(token)
        response.headers[REQUEST_ID_HEADER] = rid
        return response
