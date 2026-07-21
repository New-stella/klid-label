"""
증강 AI 벤더 목 라우터 (placeholder / 확장 지점).

이 라우터는 향후 **증강 AI 목**(SFR-07: WINTER / NIGHT / RAIN 3종 영상 증강)을 얹기 위한
확장 지점이다. 아직 실제 증강 로직은 구현하지 않으며, 자리(엔드포인트 형태)만 확정한다.
향후 구현 시에는 VLM(verify/describe)과 동일한 **비동기 콜백 패턴**을 따를 예정이다:
요청 즉시 accepted 로 접수 → 진행률 시뮬레이션 → 완료 시 callback_url 로 결과 POST.

향후 계약(예정 — 확정 아님):
  요청  : {"request_id", "orgnl_raw_sn"(원본 영상 참조), "augment_type"(WINTER|NIGHT|RAIN),
           "callback_url"}
  응답  : 즉시 {"request_id", "status":"accepted"} (VLM 규격과 동일)
  콜백  : 증강 결과로 **새 영상(RAW_SN) 생성** — CLAUDE.md "증강 = 새 영상" 규칙.
          원본(부모)은 새 영상의 ORGNL_RAW_SN 으로 참조되고, 라벨/메타는 해상도 동일 시
          좌표 그대로 복사. 새 영상은 미검수(PENDING)로 시작.

현재 동작:
- GET  /v1/augment/status : not_implemented placeholder(등록 확인용).
- POST /v1/augment        : 501 Not Implemented 스텁(확장 지점 명시, 로직 없음).
  501 도 공통 예외 규격(error_code/message)으로 반환하며 스택트레이스를 노출하지 않는다.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, status

from app.exceptions import MockApiError

router = APIRouter()
logger = logging.getLogger(__name__)

# not_implemented 안내 메시지(사용자 노출 가능).
_NOT_IMPLEMENTED_MESSAGE = "증강 AI(WINTER/NIGHT/RAIN)는 추후 구현"


@router.get("/v1/augment/status")
async def status_check() -> dict[str, str]:
    """증강 AI 목 서버 상태 — Phase 4 확장 지점, 로직 구현 전까지 not_implemented."""
    return {"status": "not_implemented", "message": _NOT_IMPLEMENTED_MESSAGE}


@router.post("/v1/augment")
async def augment() -> None:
    """증강 요청 스텁 — 미구현(501).

    향후 원본 영상 참조 + augment_type(WINTER|NIGHT|RAIN)을 접수해 비동기 콜백으로
    새 영상(RAW_SN) 생성 결과를 전달할 예정이다. 현재는 확장 지점만 확정하고
    실제 증강/콜백은 수행하지 않는다. 공통 핸들러가 규격 응답으로 변환한다(스택트레이스 미노출).
    """
    raise MockApiError(
        status.HTTP_501_NOT_IMPLEMENTED,
        _NOT_IMPLEMENTED_MESSAGE,
        "NOT_IMPLEMENTED",
    )
