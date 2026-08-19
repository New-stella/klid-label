"""
SAM2 세그멘테이션 + Track 추론.

POST /infer/sam2/segment — 포인트/박스 입력 → polygon
POST /infer/sam2/track   — 다음 프레임 추적, 동일 트랙 ID 유지
"""

from __future__ import annotations

import logging
import math
import re
from typing import Any

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64, decode_image_b64_pil
from app.models.sam2_loader import get_sam2_model
from app.startup_guard import refuse_mock_in_deployed_env
from app.schemas import (
    MAX_TRACK_ID_LENGTH,
    Sam2SegmentRequest,
    Sam2SegmentResponse,
    Sam2TrackRequest,
    Sam2TrackResponse,
)

router = APIRouter()
logger = logging.getLogger(__name__)

# 운영에서 mock 응답이 첫 호출 시 1회 WARN 출력하기 위한 플래그
_mock_warned: bool = False

#: 로그 라인을 위조할 수 있는 문자 (CR/LF · C0 제어 · DEL).
_CTRL = re.compile(r"[\r\n\x00-\x1f\x7f]")


def _safe(value: Any, limit: int = MAX_TRACK_ID_LENGTH) -> str:
    """로그 출력 전 외부 입력 정제 (CWE-117 Log Injection).

    스키마(``TRACK_ID_PATTERN``)가 개행을 앞단에서 막지만, 내부 직접 호출·``model_construct``
    같은 검증 우회 경로가 존재하므로 **출력 시점에도** 방어한다(defense in depth).
    BE ``LogSanitizer`` 와 동일한 목적이며, BE 는 ai-server 로 trackId 원문을 전달하므로
    ai-server 쪽 로그도 독립적으로 정제해야 한다.
    """
    return _CTRL.sub("_", str(value))[:limit]


def _safe_iter(value: Any) -> "list[Any]":
    """어떤 값이든 예외 없이 순회 가능한 리스트로 바꾼다.

    ``value or []`` 를 쓰지 않는 이유: 진리값 판정이 임의 객체의 ``__bool__``/``__len__`` 을
    호출하므로(numpy 배열은 ``ValueError`` 를 던진다) "예외 없음" 보증이 그 자리에서 깨진다.
    """
    if value is None:
        return []
    try:
        return list(value)
    except TypeError:
        return []


def _sanitize_polygon(polygon: Any) -> list[list[float]]:
    """어떤 입력이든 유한 float ``[x, y]`` 리스트로 정규화. 실패 원소는 버린다.

    fallback 경로의 좌표 정규화를 이 한 곳에 모아, 각 호출부가 자기만의 방어를 재구현하다
    빠뜨리는 일(CWE-755)을 막는다. 비순회/None/dict/str/과대수치(``float(10**400)``) 등
    어떤 원소가 섞여도 예외를 던지지 않는다.
    """
    out: list[list[float]] = []
    iterator = _safe_iter(polygon)
    for p in iterator:
        try:
            x, y = float(p[0]), float(p[1])
        except (TypeError, ValueError, IndexError, KeyError, OverflowError):
            continue
        if math.isfinite(x) and math.isfinite(y):
            out.append([x, y])
    return out


def _sanitize_coords(coords: Any) -> list[float]:
    """평면 좌표 배열(``box`` = ``[x1, y1, x2, y2]``)을 유한 float 리스트로 정규화.

    하나라도 변환 불가/비유한이면 빈 리스트를 돌려준다 — bbox 는 4개가 모두 성립해야
    의미가 있으므로 부분 채택하지 않는다.
    """
    out: list[float] = []
    for v in _safe_iter(coords):
        try:
            f = float(v)
        except (TypeError, ValueError, OverflowError):
            return []
        if not math.isfinite(f):
            return []
        out.append(f)
    return out


def _echo_polygon(polygon: Any) -> list[list[float]]:
    """이전 폴리곤을 그대로 되돌려주기 위한 **예외 없는** 복사.

    ``_sanitize_polygon`` 과 달리 원소 길이를 2로 강제하지 않는다 — track fallback 의 계약은
    "받은 좌표를 그대로 돌려준다"이므로 좌표 개수를 임의로 바꾸지 않는다. 변환 불가
    (비순회·비수치·비유한) 원소만 버린다.
    """
    out: list[list[float]] = []
    for p in _safe_iter(polygon):
        if isinstance(p, (str, bytes)):
            continue
        try:
            values = [float(v) for v in p]
        except (TypeError, ValueError, OverflowError):
            continue
        if values and all(math.isfinite(v) for v in values):
            out.append(values)
    return out


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
        # 배포 환경에서는 가짜 라벨을 내보내지 않는다 — 실패가 오염보다 낫다.
        refuse_mock_in_deployed_env("SAM2 분할", reason)
        _warn_mock_once("segment", reason)
        return _mock_segment(width, height, req, reason)
    return _real_segment(get_sam2_model(), req)


@router.post("/track", response_model=Sam2TrackResponse)
async def track(req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 프레임 폴리곤을 다음 프레임으로 전파. 동일 track_id 유지."""
    if _should_mock():
        reason = _mock_reason()
        refuse_mock_in_deployed_env("SAM2 추적", reason)
        _warn_mock_once("track", reason)
        logger.info(
            "[SAM2][MOCK] track reason=%s track_id=%s points=%d",
            reason,
            _safe(req.track_id),
            _polygon_len(req.prev_polygon),
        )
        return _mock_track(req, reason)

    pw, ph = decode_image_b64(req.prev_image_b64)
    nw, nh = decode_image_b64(req.next_image_b64)
    logger.info(
        "[SAM2] track received track_id=%s prev=%dx%d next=%dx%d points=%d",
        _safe(req.track_id),
        pw,
        ph,
        nw,
        nh,
        _polygon_len(req.prev_polygon),
    )
    return _real_track(get_sam2_model(), req)


def _polygon_len(polygon: Any) -> int:
    """길이 산출도 예외 금지 — 검증 우회 경로에서 비순회 값이 들어와도 로그가 터지지 않게 한다."""
    try:
        return len(polygon)
    except TypeError:
        return 0


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


def _polygon_bbox(polygon: Any) -> list[float] | None:
    """폴리곤 → ``[x1, y1, x2, y2]`` bbox. 유효 좌표가 하나도 없으면 None.

    스키마(``Point2D``)가 원소 2개·유한값을 강제하지만, 내부 직접 호출 등 검증 우회 경로에서도
    예외 대신 None 을 돌려 호출자가 이전 폴리곤 fallback 하도록 한다 (보안 가드 — graceful).
    정규화는 ``_sanitize_polygon`` 에 위임하므로 이 시점의 좌표는 이미 유한 float 쌍이다
    (``float(10**400)`` 같은 OverflowError 케이스 포함해 정규화 단계에서 걸러진다).
    """
    points = _sanitize_polygon(polygon)
    if not points:
        logger.warning("[SAM2] prev_polygon bbox 유도 실패 — prev polygon fallback")
        return None
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return [min(xs), min(ys), max(xs), max(ys)]


def _prev_polygon_fallback(req: Sam2TrackRequest, reason: str) -> Sam2TrackResponse:
    """추적이 끊기지 않도록 이전 폴리곤을 그대로 반환하는 mock 응답.

    이 함수는 track 의 마지막 방어선이므로 **어떤 입력에도 예외를 던지지 않는다**(CWE-755).
    구 구현의 ``[list(p) for p in req.prev_polygon]`` 은 원소가 None·int 면 TypeError 로,
    dict·str 이면 응답 스키마 ValidationError 로 폭발했다 — 둘 다 500 이다.
    """
    _warn_mock_once("track", reason)
    return Sam2TrackResponse(
        track_id=_safe(req.track_id),
        polygon=_echo_polygon(req.prev_polygon),
        score=0.5,
        mock=True,
        source="mock",
        mock_reason=reason,
    )


def _real_track(model: Any, req: Sam2TrackRequest) -> Sam2TrackResponse:
    """이전 폴리곤 bbox를 프롬프트로 다음 프레임을 세그멘테이션."""
    bbox = _polygon_bbox(req.prev_polygon)
    if bbox is None:
        return _prev_polygon_fallback(req, "empty_mask")

    pil_image = decode_image_b64_pil(req.next_image_b64)
    try:
        np_img = _pil_to_rgb_np(pil_image)
        masks, scores = _predict_masks(model, np_img, box=bbox)
    except Exception:  # noqa: BLE001 — graceful: 추론 실패는 이전 폴리곤 mock fallback (크래시 금지)
        logger.exception(
            "[SAM2] track real predict 실패 track_id=%s — prev polygon fallback",
            _safe(req.track_id),
        )
        return _prev_polygon_fallback(req, "empty_mask")
    finally:
        pil_image.close()

    result = _best_mask_polygon(masks, scores)
    if result is not None:
        polygon, score = result
        logger.info(
            "[SAM2] track real track_id=%s polygon_pts=%d score=%.3f",
            _safe(req.track_id),
            len(polygon),
            score,
        )
        return Sam2TrackResponse(
            track_id=_safe(req.track_id),
            polygon=polygon,
            score=score,
            mock=False,
            source="model",
            mock_reason=None,
        )

    # 마스크 없으면 이전 폴리곤 그대로 반환 (mock fallback)
    logger.warning("[SAM2] track real returned no mask — prev polygon fallback")
    return _prev_polygon_fallback(req, "empty_mask")


# ────────────────────────────────────────────────────────────────────
# Mock 추론 (fallback)
# ────────────────────────────────────────────────────────────────────

def _mock_segment(
    width: int, height: int, req: Sam2SegmentRequest, reason: str = "env_mock"
) -> Sam2SegmentResponse:
    """포인트 또는 박스 주변에 사각 폴리곤 생성.

    이 함수는 추론 실패 시의 마지막 방어선이므로 **어떤 입력에도 예외를 던지지 않는다**
    (CWE-755). 스키마(``Point2D``/``Coord``)가 좌표 원소 2개·유한값을 강제하지만, 검증 우회
    경로에서 원소가 모자라거나(``[[5]]``) 형태가 다르거나(``None``/``"ab"``/``{...}``)
    유한하지 않은(``NaN``/``1e400``) 좌표가 들어와도 이미지 중앙 기본 폴리곤으로 처리한다.
    값 정규화는 전부 ``_sanitize_polygon`` 한 곳에 위임한다.
    """
    box = _sanitize_coords(req.box)
    points = _sanitize_polygon(req.points)
    if len(box) == 4:
        x1, y1, x2, y2 = box
    elif points:
        cx, cy = points[0]
        half = min(width, height) * 0.1
        x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half
    else:
        # points/box 미지정 또는 좌표 형태 비정상 → 이미지 중앙 기본 폴리곤
        x1, y1, x2, y2 = width * 0.4, height * 0.4, width * 0.6, height * 0.6
    polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
    return Sam2SegmentResponse(
        polygon=polygon, score=0.95, mock=True, source="mock", mock_reason=reason
    )


def _mock_track(req: Sam2TrackRequest, reason: str = "env_mock") -> Sam2TrackResponse:
    """이전 폴리곤을 그대로 다음 프레임에 매핑 (동일 track_id 유지).

    ``_prev_polygon_fallback`` 과 동일하게 예외를 던지지 않는다 (CWE-755).
    """
    return Sam2TrackResponse(
        track_id=_safe(req.track_id),
        polygon=_echo_polygon(req.prev_polygon),
        score=0.9,
        mock=True,
        source="mock",
        mock_reason=reason,
    )
