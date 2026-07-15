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

    이슈2 fix: loader 가 추적한 실제 사유(``get_sam2_mock_reason()``)를 우선 위임한다.
    yolo 라우터와 동일하게, sam2 미설치/로드실패(``load_failed``)인데도
    ``weights_missing`` 으로 오표기되던 문제를 정정한다.

    - loader 사유가 있으면 그 값 (env_mock | weights_missing | load_failed)
    - 없으면(get_sam2_model 미호출 등) ai_mock_mode→env_mock, else weights_missing 폴백
    """
    from app.models.sam2_loader import get_sam2_mock_reason

    reason = get_sam2_mock_reason()
    if reason is not None:
        return reason
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
# 실제 추론 (Meta 공식 sam2 SAM2ImagePredictor)
# ────────────────────────────────────────────────────────────────────

def _pil_to_rgb_np(pil_image: Any) -> Any:
    """PIL 이미지를 numpy RGB (H, W, 3) 로 변환."""
    import numpy as np  # noqa: WPS433 (lazy — 추론 경로에서만 필요)

    return np.asarray(pil_image.convert("RGB"))


def _mask_to_polygon(mask: Any) -> tuple[list[list[float]], float] | None:
    """binary/float mask (H, W) → 최대 면적 외곽 polygon + 면적.

    cv2.findContours(RETR_EXTERNAL, CHAIN_APPROX_SIMPLE) 로 외곽 윤곽을 뽑아
    가장 큰 contour 를 ``[[x, y], ...]`` float 좌표로 반환한다.
    contour 가 없으면(빈/비정상 마스크) 예외 대신 None 을 반환해 호출자가
    mock fallback 하도록 한다 (보안 가드 — graceful).
    """
    import cv2  # noqa: WPS433 (lazy — opencv 추론 경로 전용)
    import numpy as np  # noqa: WPS433

    try:
        bin_mask = (np.asarray(mask) > 0.5).astype(np.uint8)
        contours, _ = cv2.findContours(
            bin_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
        )
        if not contours:
            return None
        largest = max(contours, key=cv2.contourArea)
        area = float(cv2.contourArea(largest))
        pts = largest.reshape(-1, 2)
        if len(pts) < 3:
            # 이슈5: 점/선 수준 윤곽(폴리곤 미성립)은 skip — 추적성 위해 debug 로그
            logger.debug("[SAM2] contour pts=%d (<3) — polygon skip", len(pts))
            return None
        polygon = [[float(x), float(y)] for x, y in pts]
        return polygon, area
    except Exception:  # noqa: BLE001 — graceful: 변환 실패는 mock fallback (에러 무시 아님)
        logger.warning("[SAM2] mask→polygon 변환 실패 — mock fallback", exc_info=True)
        return None


def _predict_masks(model: Any, np_img: Any, *, box: Any = None, points: Any = None) -> Any:
    """SAM2ImagePredictor 로 set_image 후 predict — (masks, scores) 반환."""
    import numpy as np  # noqa: WPS433

    model.set_image(np_img)
    if box is not None:
        masks, scores, _ = model.predict(
            box=np.asarray(box, dtype=np.float32), multimask_output=False
        )
    else:
        coords = np.asarray(points, dtype=np.float32)
        labels = np.ones((len(points),), dtype=np.int32)
        masks, scores, _ = model.predict(
            point_coords=coords, point_labels=labels, multimask_output=False
        )
    return masks, scores


def _best_mask_polygon(masks: Any, scores: Any) -> tuple[list[list[float]], float] | None:
    """(N,H,W) 마스크 중 최고 score 마스크 → polygon + clamp score."""
    import numpy as np  # noqa: WPS433

    arr = np.asarray(masks)
    sc = np.asarray(scores).reshape(-1)
    if arr.ndim == 2:  # (H, W) 단일 마스크 방어
        arr = arr[None, ...]
    if arr.shape[0] == 0 or sc.shape[0] == 0:
        return None
    best = int(np.argmax(sc))
    converted = _mask_to_polygon(arr[best])
    if converted is None:
        return None
    polygon, _area = converted
    # 이슈3: SAM2 score 는 음수 가능 → ge=0.0 위반(pydantic 500) 방지로 하한 clamp
    score = max(0.0, min(float(sc[best]), 1.0))
    return polygon, score


def _real_segment(model: Any, req: Sam2SegmentRequest) -> Sam2SegmentResponse:
    """Meta sam2 SAM2ImagePredictor 로 실제 세그멘테이션 수행."""
    pil_image = decode_image_b64_pil(req.image_b64)
    width, height = pil_image.width, pil_image.height
    try:
        np_img = _pil_to_rgb_np(pil_image)
        if req.box and len(req.box) == 4:
            masks, scores = _predict_masks(model, np_img, box=req.box)
        elif req.points:
            masks, scores = _predict_masks(model, np_img, points=req.points)
        else:
            center = [[width / 2, height / 2]]
            masks, scores = _predict_masks(model, np_img, points=center)
    except Exception:  # noqa: BLE001 — graceful: 추론 실패는 mock fallback (크래시 금지)
        logger.exception("[SAM2] segment real predict 실패 — mock fallback")
        _warn_mock_once("segment", "empty_mask")
        return _mock_segment(width, height, req, "empty_mask")
    finally:
        pil_image.close()

    result = _best_mask_polygon(masks, scores)
    if result is not None:
        polygon, score = result
        logger.info("[SAM2] segment real polygon_pts=%d score=%.3f", len(polygon), score)
        return Sam2SegmentResponse(
            polygon=polygon, score=score, mock=False, source="model", mock_reason=None
        )

    # 마스크가 비거나 contour 없으면 mock 으로 fallback
    logger.warning("[SAM2] segment real returned no mask — mock fallback")
    _warn_mock_once("segment", "empty_mask")
    return _mock_segment(width, height, req, "empty_mask")


def _real_track(model: Any, req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 폴리곤 bbox를 프롬프트로 다음 프레임을 세그멘테이션."""
    xs = [p[0] for p in req.prev_polygon]
    ys = [p[1] for p in req.prev_polygon]
    bbox = [min(xs), min(ys), max(xs), max(ys)]

    pil_image = decode_image_b64_pil(req.next_image_b64)
    try:
        np_img = _pil_to_rgb_np(pil_image)
        masks, scores = _predict_masks(model, np_img, box=bbox)
    except Exception:  # noqa: BLE001 — graceful: 추론 실패는 이전 폴리곤 mock fallback (크래시 금지)
        logger.exception(
            "[SAM2] track real predict 실패 track_id=%s — prev polygon fallback", req.track_id
        )
        _warn_mock_once("track", "empty_mask")
        return Sam2TrackResponse(
            track_id=req.track_id,
            polygon=[list(p) for p in req.prev_polygon],
            score=0.5,
            mock=True,
            source="mock",
            mock_reason="empty_mask",
        )
    finally:
        pil_image.close()

    result = _best_mask_polygon(masks, scores)
    if result is not None:
        polygon, score = result
        logger.info(
            "[SAM2] track real track_id=%s polygon_pts=%d score=%.3f",
            req.track_id,
            len(polygon),
            score,
        )
        return Sam2TrackResponse(
            track_id=req.track_id,
            polygon=polygon,
            score=score,
            mock=False,
            source="model",
            mock_reason=None,
        )

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
