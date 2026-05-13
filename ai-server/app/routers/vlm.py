"""
VLM 객체 검증 (V1.7) — YOLO/SAM2가 감지한 객체에 대한 분류 정합성 검증.

POST /infer/vlm/verify-objects
- 입력: 프레임 이미지 + 검증 대상 객체 목록 (obj_id, expected_label, bbox)
- 출력: per-object verified bool + confidence

> V1.7 — 시계열 메타 자동 생성 엔드포인트는 만들지 않는다 (외부 시스템 책임).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64
from app.models.vlm_loader import get_vlm_model
from app.schemas import (
    ObjectVerification,
    VlmVerifyRequest,
    VlmVerifyResponse,
)

router = APIRouter()
logger = logging.getLogger(__name__)

# Mock 모드에서 검증 결과를 결정적으로 생성하기 위한 화이트리스트
_KNOWN_LABELS: frozenset[str] = frozenset(
    {"person", "car", "bicycle", "motorbike", "bus", "truck", "dog", "cat"}
)

# 운영에서 mock 응답이 첫 호출 시 1회 WARN 출력하기 위한 플래그
_mock_warned: bool = False


@router.post("/verify-objects", response_model=VlmVerifyResponse)
async def verify_objects(req: VlmVerifyRequest) -> VlmVerifyResponse:
    """객체 단위 검증 결과 반환.

    현재 실제 VLM 검증은 구현되지 않아 항상 mock 응답을 반환한다.
    mock 여부는 응답에 mock=true / source="mock" 로 표시한다.
    """
    width, height = decode_image_b64(req.image_b64)
    logger.info(
        "[VLM] verify-objects received image_size=%dx%d objects=%d",
        width,
        height,
        len(req.objects),
    )

    reason = _mock_reason()
    _warn_mock_once(reason)
    return _mock_verify(req, reason)


def _should_mock() -> bool:
    """mock 모드이거나 모델이 로드되지 않은 경우 True."""
    return get_settings().ai_mock_mode or get_vlm_model() is None


def _mock_reason() -> str:
    """mock 응답 사유."""
    if get_settings().ai_mock_mode:
        return "env_mock"
    if get_vlm_model() is None:
        return "weights_missing"
    return "not_implemented"


def _warn_mock_once(reason: str) -> None:
    global _mock_warned
    if not _mock_warned:
        logger.warning(
            "[VLM][MOCK] returning mock verify-objects "
            "(model not loaded or AI_MOCK_MODE=true) reason=%s",
            reason,
        )
        _mock_warned = True


def reset_mock_warn_flag() -> None:
    """테스트용 — mock WARN 플래그 초기화."""
    global _mock_warned
    _mock_warned = False


def _mock_verify(req: VlmVerifyRequest, reason: str = "env_mock") -> VlmVerifyResponse:
    """expected_label이 _KNOWN_LABELS에 있으면 verified=true, 아니면 false."""
    results: list[ObjectVerification] = []
    for obj in req.objects:
        verified = obj.expected_label.lower() in _KNOWN_LABELS
        results.append(
            ObjectVerification(
                obj_id=obj.obj_id,
                expected_label=obj.expected_label,
                verified=verified,
                confidence=0.92 if verified else 0.18,
            )
        )
    return VlmVerifyResponse(
        results=results, mock=True, source="mock", mock_reason=reason
    )
