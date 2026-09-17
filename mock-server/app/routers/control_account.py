"""
관제지원 계정 서비스 세션 창구 목 — 저작도구 서버의 세션 중계(갱신·로그아웃) 실동작 검증용.

저작도구는 관제 채널 세션을 연장·종료할 때 브라우저 대신 서버가 관제 계정 창구를 부른다(INT-015).
그 주소는 관제 통지 수신처와 **별개 설정**(authoring.control-account.url)이며, 로컬은 이 목 서버를 가리킨다.

엔드포인트 (관제 실측 계약):
- POST /api/account/auth/refresh — 헤더 ``x-access-token`` 에 **refresh 토큰**, 바디 없음
    · 없거나 무효(서명·만료·종류) → HTTP 401 ``{"error": 900205, "message": "리프레시 토큰이 없습니다."}``
    · 유효 → 200 ``{"error": 0, "message": "success", "data": {"session_token", "refresh_token"}}``
    · 목 비밀키(MOCK_CONTROL_JWT_SECRET) 미설정 → 503 (새 토큰을 서명할 수 없다 — 저작도구에는 일시 장애)
- POST /api/account/auth/logout — 헤더 ``x-access-token`` 에 **access 토큰** → 200 ``{"error": 0, "message": "success"}``

목의 한계(인지):
- 서버 세션 상태를 두지 않는다 — 교체 전 refresh 토큰을 다시 보내도 서명·만료만 맞으면 갱신된다.
  관제가 그 요청을 어떻게 다루는지는 미확인(INT-015 「미확인 — 관제 확인 대상」)이라 흉내 내지 않는다.
- 로그아웃은 헤더 유무와 무관하게 성공으로 답한다(관제 응답과 무관하게 저작도구 결말이 같다).

보안:
- 토큰 원문·클레임 값을 로그에 싣지 않는다(CWE-532) — 실패 분류와 헤더 유무만 남긴다.
- 인증 없음 — 로컬/테스트 전용. 신뢰망에서만 기동한다.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, Request, status
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.exceptions import MockApiError
from app.services import control_jwt

router = APIRouter()
logger = logging.getLogger(__name__)

REFRESH_PATH = "/api/account/auth/refresh"
LOGOUT_PATH = "/api/account/auth/logout"
TOKEN_HEADER = "x-access-token"

# 관제 실측 — refresh 토큰이 없거나 서버 세션이 정리됐을 때의 오류 번호와 문구.
ERROR_REFRESH_TOKEN_MISSING = 900205
REFRESH_TOKEN_MISSING_MESSAGE = "리프레시 토큰이 없습니다."


def _rejected(reason: str) -> JSONResponse:
    logger.info("[MOCK][CONTROL-ACCOUNT] refresh rejected reason=%s", reason)
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        content={"error": ERROR_REFRESH_TOKEN_MISSING, "message": REFRESH_TOKEN_MISSING_MESSAGE},
    )


@router.post(REFRESH_PATH)
async def refresh(request: Request) -> Any:
    """세션 갱신 — refresh 토큰을 검증하고 클레임을 복사해 새 토큰 쌍을 발급한다."""
    token = (request.headers.get(TOKEN_HEADER) or "").strip()
    if not token:
        return _rejected("missing")
    secret = (get_settings().control_jwt_secret or "").strip()
    if not secret:
        # 새 토큰을 서명할 수 없다 — 비밀키를 지어내지 않고 일시 장애로 드러낸다.
        logger.warning(
            "[MOCK][CONTROL-ACCOUNT] MOCK_CONTROL_JWT_SECRET 미설정 — 갱신 창구가 새 토큰을 서명할 수 없다"
        )
        raise MockApiError(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "목 서버에 토큰 서명 비밀키가 설정되지 않았습니다.",
            "CONTROL_JWT_SECRET_NOT_CONFIGURED",
        )
    try:
        session_token, refresh_token = control_jwt.reissue(token, secret, int(time.time()))
    except control_jwt.InvalidTokenError as exc:
        return _rejected(exc.reason)
    logger.info("[MOCK][CONTROL-ACCOUNT] refresh succeeded")
    return {
        "error": 0,
        "message": "success",
        "data": {"session_token": session_token, "refresh_token": refresh_token},
    }


@router.post(LOGOUT_PATH)
async def logout(request: Request) -> dict[str, Any]:
    """세션 로그아웃 — 목은 세션 상태가 없으므로 항상 성공으로 답한다."""
    token_present = bool((request.headers.get(TOKEN_HEADER) or "").strip())
    logger.info("[MOCK][CONTROL-ACCOUNT] logout token_present=%s", token_present)
    return {"error": 0, "message": "success"}
