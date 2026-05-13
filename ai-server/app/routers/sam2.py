"""
SAM2 세그멘테이션 + Track 추론.

POST /infer/sam2/segment — 포인트/박스 입력 → polygon
POST /infer/sam2/track   — 다음 프레임 추적, 동일 트랙 ID 유지
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64, decode_image_b64_pil
from app.models.sam2_loader import get_sam2_model
from app.schemas import (
    Sam2SegmentRequest,
    Sam2SegmentResponse,
    Sam2TrackRequest,
    Sam2TrackResponse,
)

router = APIRouter()
logger = logging.getLogger(__name__)

# 운영에서 mock 응답이 첫 호출 시 1회 WARN 출력하기 위한 플래그
_mock_warned: bool = False


@router.post("/segment", response_model=Sam2SegmentResponse)
async def segment(req: Sam2SegmentRequest) -> Sam2SegmentResponse:
    """클릭 포인트 또는 박스를 받아 폴리곤 마스크 반환."""
    width, height = decode_image_b64(req.image_b64)
    logger.info(
        "[SAM2] segment received image_size=%dx%d points=%s box=%s",
        width,
        height,
        bool(req.points),
        bool(req.box),
    )

    if _should_mock():
        reason = _mock_reason()
        _warn_mock_once("segment", reason)
        return _mock_segment(width, height, req, reason)
    return _real_segment(get_sam2_model(), req)


@router.post("/track", response_model=Sam2TrackResponse)
async def track(req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 프레임 폴리곤을 다음 프레임으로 전파. 동일 track_id 유지."""
    if _should_mock():
        reason = _mock_reason()
        _warn_mock_once("track", reason)
        logger.info(
            "[SAM2][MOCK] track reason=%s track_id=%s points=%d",
            reason,
            req.track_id,
            len(req.prev_polygon),
        )
        return _mock_track(req, reason)

    pw, ph = decode_image_b64(req.prev_image_b64)
    nw, nh = decode_image_b64(req.next_image_b64)
    logger.info(
        "[SAM2] track received track_id=%s prev=%dx%d next=%dx%d points=%d",
        req.track_id,
        pw,
        ph,
        nw,
        nh,
        len(req.prev_polygon),
    )
    return _real_track(get_sam2_model(), req)


def _should_mock() -> bool:
    """mock 모드이거나 모델이 로드되지 않은 경우 True."""
    return get_settings().ai_mock_mode or get_sam2_model() is None


def _mock_reason() -> str:
    """mock 응답 사유를 결정한다.

    MEDIUM-4 fix: 이전 구현은 ``ai_mock_mode=False`` + 모델 인스턴스 정상일 때
    (이 경로는 ``_should_mock()`` 정의상 진입 불가) "env_mock" 을 반환해
    yolo_loader 패턴과 어긋났다. 가중치 부재 시 ``weights_missing`` 을
    반환하도록 정정한다.

    - ai_mock_mode=True            → "env_mock"
    - ai_mock_mode=False + 모델 None → "weights_missing"
    - (도달 불가) 기타              → "weights_missing" 로 보수적 fallback
    """
    if get_settings().ai_mock_mode:
        return "env_mock"
    return "weights_missing"


def _warn_mock_once(op: str, reason: str) -> None:
    """프로세스 수명 동안 mock 응답이 처음 발생할 때 1회만 WARN 로그."""
    global _mock_warned
    if not _mock_warned:
        logger.warning(
            "[SAM2][MOCK] returning mock %s "
            "(model not loaded or AI_MOCK_MODE=true) reason=%s",
            op,
            reason,
        )
        _mock_warned = True


def reset_mock_warn_flag() -> None:
    """테스트용 — mock WARN 플래그 초기화."""
    global _mock_warned
    _mock_warned = False


# ────────────────────────────────────────────────────────────────────
# 실제 추론
# ────────────────────────────────────────────────────────────────────

def _real_segment(model: Any, req: Sam2SegmentRequest) -> Sam2SegmentResponse:
    """ultralytics SAM으로 실제 세그멘테이션 수행."""
    pil_image = decode_image_b64_pil(req.image_b64)
    try:
        if req.box and len(req.box) == 4:
            results = model.predict(pil_image, bboxes=[req.box], verbose=False)
        elif req.points:
            labels = [[1] * len(req.points)]
            results = model.predict(pil_image, points=[req.points], labels=labels, verbose=False)
        else:
            w, h = pil_image.width, pil_image.height
            results = model.predict(
                pil_image, points=[[[w / 2, h / 2]]], labels=[[1]], verbose=False
            )

        if results and results[0].masks is not None and len(results[0].masks.xy) > 0:
            polygon = results[0].masks.xy[0].tolist()
            score = float(results[0].masks.data[0].max().item())
            logger.info(
                "[SAM2] segment real polygon_pts=%d score=%.3f", len(polygon), score
            )
            return Sam2SegmentResponse(
                polygon=polygon,
                score=min(score, 1.0),
                mock=False,
                source="model",
                mock_reason=None,
            )
    finally:
        pil_image.close()

    # 마스크가 비어있으면 mock으로 fallback
    logger.warning("[SAM2] segment real returned no mask — mock fallback")
    _warn_mock_once("segment", "empty_mask")
    return _mock_segment(pil_image.width, pil_image.height, req, "empty_mask")


def _real_track(model: Any, req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 폴리곤 bbox를 프롬프트로 다음 프레임을 세그멘테이션."""
    xs = [p[0] for p in req.prev_polygon]
    ys = [p[1] for p in req.prev_polygon]
    bbox = [min(xs), min(ys), max(xs), max(ys)]

    pil_image = decode_image_b64_pil(req.next_image_b64)
    try:
        results = model.predict(pil_image, bboxes=[bbox], verbose=False)

        if results and results[0].masks is not None and len(results[0].masks.xy) > 0:
            polygon = results[0].masks.xy[0].tolist()
            score = float(results[0].masks.data[0].max().item())
            logger.info(
                "[SAM2] track real track_id=%s polygon_pts=%d score=%.3f",
                req.track_id,
                len(polygon),
                score,
            )
            return Sam2TrackResponse(
                track_id=req.track_id,
                polygon=polygon,
                score=min(score, 1.0),
                mock=False,
                source="model",
                mock_reason=None,
            )
    finally:
        pil_image.close()

    # 마스크 없으면 이전 폴리곤 그대로 반환 (mock fallback)
    logger.warning("[SAM2] track real returned no mask — prev polygon fallback")
    _warn_mock_once("track", "empty_mask")
    return Sam2TrackResponse(
        track_id=req.track_id,
        polygon=[list(p) for p in req.prev_polygon],
        score=0.5,
        mock=True,
        source="mock",
        mock_reason="empty_mask",
    )


# ────────────────────────────────────────────────────────────────────
# Mock 추론 (fallback)
# ────────────────────────────────────────────────────────────────────

def _mock_segment(
    width: int, height: int, req: Sam2SegmentRequest, reason: str = "env_mock"
) -> Sam2SegmentResponse:
    """포인트 또는 박스 주변에 사각 폴리곤 생성."""
    if req.box and len(req.box) == 4:
        x1, y1, x2, y2 = req.box
    elif req.points:
        cx, cy = req.points[0][0], req.points[0][1]
        half = min(width, height) * 0.1
        x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half
    else:
        x1, y1, x2, y2 = width * 0.4, height * 0.4, width * 0.6, height * 0.6
    polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
    return Sam2SegmentResponse(
        polygon=polygon, score=0.95, mock=True, source="mock", mock_reason=reason
    )


def _mock_track(req: Sam2TrackRequest, reason: str = "env_mock") -> Sam2TrackResponse:
    """이전 폴리곤을 그대로 다음 프레임에 매핑 (동일 track_id 유지)."""
    return Sam2TrackResponse(
        track_id=req.track_id,
        polygon=[list(p) for p in req.prev_polygon],
        score=0.9,
        mock=True,
        source="mock",
        mock_reason=reason,
    )
