"""
SAM2 세그멘테이션 + Track 추론.

POST /infer/sam2/segment — 포인트/박스 입력 → polygon
POST /infer/sam2/track   — 다음 프레임 추적, 동일 트랙 ID 유지
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64
from app.models.sam2_loader import get_sam2_model
from app.schemas import (
    Sam2SegmentRequest,
    Sam2SegmentResponse,
    Sam2TrackRequest,
    Sam2TrackResponse,
)

router = APIRouter()
logger = logging.getLogger(__name__)


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
        return _mock_segment(width, height, req)
    return _mock_segment(width, height, req)


@router.post("/track", response_model=Sam2TrackResponse)
async def track(req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 프레임 폴리곤을 다음 프레임으로 전파. 동일 track_id 유지."""
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

    if _should_mock():
        return _mock_track(req)
    return _mock_track(req)


def _should_mock() -> bool:
    """mock 모드이거나 모델이 로드되지 않은 경우 True."""
    return get_settings().ai_mock_mode or get_sam2_model() is None


def _mock_segment(width: int, height: int, req: Sam2SegmentRequest) -> Sam2SegmentResponse:
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
    return Sam2SegmentResponse(polygon=polygon, score=0.95)


def _mock_track(req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 폴리곤을 그대로 다음 프레임에 매핑 (동일 track_id 유지)."""
    return Sam2TrackResponse(
        track_id=req.track_id,
        polygon=[list(p) for p in req.prev_polygon],
        score=0.9,
    )
