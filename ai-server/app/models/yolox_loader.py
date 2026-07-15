"""YOLOX(ONNX Runtime) 탐지 백엔드 로더 + 영상 단위 트래커 LRU/TTL 캐시.

ultralytics YOLOv8 을 대체하는 YOLOX 단일 탐지 백엔드(싱글톤 모델 공유 +
clip 별 경량 ByteTrack 트래커 + mock 사유 + LRU/TTL).

YOLOX 후처리 (ultralytics 와의 차이):
- YOLOX 공식 ONNX export(tools/export_onnx.py)의 **표준 출력은 raw grid(미디코드)**이며,
  공식 onnx_inference.demo_postprocess 가 각 stride(8,16,32)별 grid+stride 를 적용해
  절대좌표로 복원한다. 본 로더의 표준 경로(decoded=False)도 동일하게 _decode_grid_if_needed
  로 grid decode 를 거친 뒤([cx,cy,w,h] 절대좌표) score 필터 → 클래스별 NMS → xyxy 역보정.
- decode_in_inference=True 로 export 한 모델은 이미 디코드된 [cx,cy,w,h] 절대좌표를 내므로
  decoded=True 로 grid decode 를 건너뛴다. 분기는 명시적 인자(decoded)로만 한다(휴리스틱 없음).

mock 사유:
- "env_mock"        : AI_MOCK_MODE=true 환경변수 강제
- "weights_missing" : ONNX 가중치 파일 부재
- "load_failed"     : onnxruntime 미설치 또는 세션 로드 중 예외
- None              : 정상 로드됨 (실제 모델)

Critical:
- onnxruntime/numpy 무거운 연산은 **반드시 lazy import** (모듈 최상단 import 금지).
  → `import app.models.yolox_loader` 시 onnxruntime 이 로드되면 안 된다.
- import/로드 실패 시 크래시 금지 → load_failed mock 사유로 None 반환.
- 가중치 경로는 설정값(yolox_weights_path)만 사용 — 사용자 입력 reflection 금지.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from collections import OrderedDict
from typing import Any

from app.config import get_settings
from app.models.bytetrack_util import _apply_bytetrack, _new_bytetrack_tracker
from app.models.detector_backend import (
    DetectionResult,
    InferenceParams,
    coco_label_from_id,
)

logger = logging.getLogger(__name__)

_yolox_backend: Any | None = None
_loaded: bool = False
_mock_reason: str | None = None
# 싱글톤 초기화 보호 — double-checked locking 용 (멀티스레드 중복 세션 빌드 방지).
_MODEL_LOCK = threading.Lock()

# YOLOX 표준 입력 해상도 (정사각 letterbox). 공식 yolox_s 기본은 640x640.
_DEFAULT_INPUT_SIZE: tuple[int, int] = (640, 640)
# YOLOX 표준 letterbox padding 값 (114) — 전처리/역보정 공통.
_PAD_VALUE: int = 114

# ───────────────── 트래커 캐시 (영상 단위 격리, yolo_loader 와 동일 정책) ─────────────────
# clip_id → (handle, last_access_ts)
_TRACKERS: "OrderedDict[str, tuple[Any, float]]" = OrderedDict()
_TRACKERS_LOCK = threading.Lock()
_MAX_TRACKERS: int = 10
_TRACKER_TTL_SEC: float = 300.0  # 5분


# ────────────────────────────────────────────────────────────────────
# 순수 후처리 함수 — onnxruntime 비의존, numpy 만 사용 (단위 테스트 대상)
# ────────────────────────────────────────────────────────────────────

def _nms(boxes: Any, scores: Any, iou_thr: float) -> list[int]:
    """단일 클래스 NMS — keep 할 박스 인덱스 목록을 반환한다 (numpy 순수 구현).

    boxes: (N, 4) [x1,y1,x2,y2], scores: (N,). IoU > iou_thr 인 중복 박스를
    점수 높은 순으로 억제한다. 빈 입력은 빈 리스트.
    """
    import numpy as np  # noqa: WPS433 (lazy)

    if boxes is None or len(boxes) == 0:
        return []
    boxes = np.asarray(boxes, dtype=float)
    scores = np.asarray(scores, dtype=float)

    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    order = scores.argsort()[::-1]

    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(x1[i], x1[rest])
        yy1 = np.maximum(y1[i], y1[rest])
        xx2 = np.minimum(x2[i], x2[rest])
        yy2 = np.minimum(y2[i], y2[rest])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[i] + areas[rest] - inter
        iou = np.where(union > 0, inter / union, 0.0)
        order = rest[iou <= iou_thr]
    return keep


def _decode_grid_if_needed(predictions: Any, input_size: tuple[int, int]) -> Any:
    """raw grid 출력(decode_in_inference=False)을 grid+stride 로 절대좌표 복원.

    공식 YOLOX onnx_inference.demo_postprocess 규약과 동일하게, 각 stride(8,16,32)별로
    (wsize x hsize) grid 를 만들어 anchor 순서대로 concat 한 뒤:
      cx = (raw_x + grid_x) * stride,  cy = (raw_y + grid_y) * stride
      w  = exp(raw_w) * stride,        h  = exp(raw_h) * stride
    로 [cx,cy,w,h] 절대좌표를 in-place 복원한다(predictions[..., 0:4]).

    anchor 개수가 grid 총합과 일치할 때만 적용하고, 불일치(예상치 못한 shape)면
    원본을 그대로 반환한다(방어 — IndexError/broadcast 오류 회피). 호출자(_yolox_postprocess)
    는 decoded=False 일 때만 이 함수를 거친다.
    """
    import numpy as np  # noqa: WPS433 (lazy)

    grids = []
    expanded_strides = []
    h, w = input_size
    strides = [8, 16, 32]
    hsizes = [h // s for s in strides]
    wsizes = [w // s for s in strides]
    for hsize, wsize, stride in zip(hsizes, wsizes, strides):
        xv, yv = np.meshgrid(np.arange(wsize), np.arange(hsize))
        grid = np.stack((xv, yv), 2).reshape(1, -1, 2)
        grids.append(grid)
        expanded_strides.append(np.full((1, grid.shape[1], 1), stride))
    grids = np.concatenate(grids, 1)
    expanded_strides = np.concatenate(expanded_strides, 1)

    predictions = np.asarray(predictions, dtype=float)
    # anchor 개수 방어 — grid 총합과 다르면(예상 밖 모델/해상도) 복원 생략하고 원본 반환.
    if predictions.ndim != 3 or predictions.shape[1] != grids.shape[1]:
        return predictions
    predictions[..., :2] = (predictions[..., :2] + grids) * expanded_strides
    predictions[..., 2:4] = np.exp(predictions[..., 2:4]) * expanded_strides
    return predictions


def _yolox_postprocess(
    outputs: list[Any],
    ratio: float,
    conf_threshold: float,
    nms_iou: float,
    input_size: tuple[int, int],
    decoded: bool = False,
) -> list[DetectionResult]:
    """YOLOX ONNX 출력 → DetectionResult 목록 (순수 함수, numpy 만 사용).

    1. 출력 shape (1, N, 85) = [box(4),obj_conf, 80 class_probs] 기준.
       - decoded=False (표준 경로): box 가 raw grid → _decode_grid_if_needed 로 grid+stride
         복원([cx,cy,w,h] 절대좌표). 공식 YOLOX onnx_inference.demo_postprocess 규약.
       - decoded=True: 이미 [cx,cy,w,h] 절대좌표(decode_in_inference=True export) → 복원 생략.
    2. [cx,cy,w,h] → [x1,y1,x2,y2] 변환 후 ratio 로 나눠 원본 좌표 역보정.
    3. score = obj_conf * class_prob.max(), cls = argmax. score < conf 필터.
    4. 클래스별 NMS(_nms) 적용.
    예상치 못한 shape 는 IndexError 대신 graceful 빈 리스트(방어).
    """
    import numpy as np  # noqa: WPS433 (lazy)

    try:
        preds = np.asarray(outputs[0], dtype=float)
        # 표준 경로(raw grid): grid decode 는 (1, N, C) 3D 입력에서 수행한다.
        if not decoded:
            if preds.ndim == 2:
                preds = preds[np.newaxis, ...]  # (N, C) → (1, N, C)
            if preds.ndim == 3:
                preds = _decode_grid_if_needed(preds, input_size)
        if preds.ndim == 3:
            preds = preds[0]  # (N, 85)
        if preds.ndim != 2 or preds.shape[0] == 0:
            return []
        if preds.shape[1] < 6:  # 최소 [cx,cy,w,h,obj, >=1 class]
            return []

        boxes_cxcywh = preds[:, 0:4]
        obj_conf = preds[:, 4]
        class_probs = preds[:, 5:]

        # [cx,cy,w,h] → [x1,y1,x2,y2]
        cx, cy, w, h = (
            boxes_cxcywh[:, 0],
            boxes_cxcywh[:, 1],
            boxes_cxcywh[:, 2],
            boxes_cxcywh[:, 3],
        )
        x1 = cx - w / 2.0
        y1 = cy - h / 2.0
        x2 = cx + w / 2.0
        y2 = cy + h / 2.0
        boxes_xyxy = np.stack([x1, y1, x2, y2], axis=1)

        # 입력 letterbox 좌표 → 원본 이미지 좌표 역보정 (ratio = input/original)
        if ratio and ratio != 0:
            boxes_xyxy = boxes_xyxy / ratio

        cls_ids = class_probs.argmax(axis=1)
        cls_scores = class_probs.max(axis=1)
        scores = obj_conf * cls_scores

        # score 임계값 필터
        keep_mask = scores >= conf_threshold
        boxes_xyxy = boxes_xyxy[keep_mask]
        scores = scores[keep_mask]
        cls_ids = cls_ids[keep_mask]
        if boxes_xyxy.shape[0] == 0:
            return []

        # 클래스별 NMS
        results: list[DetectionResult] = []
        for c in np.unique(cls_ids):
            cls_mask = cls_ids == c
            c_boxes = boxes_xyxy[cls_mask]
            c_scores = scores[cls_mask]
            keep = _nms(c_boxes, c_scores, nms_iou)
            for idx in keep:
                box = c_boxes[idx]
                results.append(
                    DetectionResult(
                        label=coco_label_from_id(int(c), None),
                        points=[float(box[0]), float(box[1]), float(box[2]), float(box[3])],
                        score=float(c_scores[idx]),
                        track_id=None,
                    )
                )
        return results
    except Exception as exc:  # noqa: BLE001 — 디코딩 실패도 크래시 금지(빈 결과)
        logger.warning("[YOLOX] postprocess failed type=%s — 빈 결과", type(exc).__name__)
        return []


# ────────────────────────────────────────────────────────────────────
# 가중치 경로 해석
# ────────────────────────────────────────────────────────────────────

def _resolve_yolox_weights() -> tuple[str, str | None]:
    """YOLOX ONNX 가중치 경로 해석.

    - settings.yolox_weights_path 가 존재하면 (path, None)
    - 부재면 (path, "weights_missing")  (호출자가 mock 처리)
    경로는 설정값만 사용 — 사용자 입력 reflection 아님.
    """
    settings = get_settings()
    path = settings.yolox_weights_path
    if os.path.isfile(path):
        return path, None
    return path, "weights_missing"


# ────────────────────────────────────────────────────────────────────
# YOLOX 백엔드 구현 (DetectorBackend Protocol) — predict / track
# ────────────────────────────────────────────────────────────────────

class _YoloxBackend:
    """YOLOX ONNX Runtime 추론 백엔드 (싱글톤 공유 세션).

    - predict: PIL 이미지 → letterbox 전처리 → onnxruntime 세션 추론 → _yolox_postprocess
    - track: 자체 predict 후 인스턴스-로컬 트래커로 track_id 부여(직접 호출/테스트 호환).
      운영 경로(라우터, Phase 2)는 싱글톤 세션 + clip 별 _YoloxTrackerHandle 을 사용한다.
    """

    def __init__(
        self,
        session: Any,
        input_size: tuple[int, int] = _DEFAULT_INPUT_SIZE,
        device: str = "cpu",
    ):
        self._session = session
        self._input_size = input_size
        self._device = device
        self._tracker: Any | None = None  # lazy — track 최초 호출 시 생성

    def _preprocess(self, img: object) -> tuple[Any, float]:
        """PIL 이미지 → YOLOX 전처리 텐서 + ratio(역보정용).

        YOLOX 표준: letterbox resize(비율 유지) + padding=114, BGR, 0~255 무정규화.
        반환 ratio = min(input/원본) (xyxy 를 ratio 로 나눠 원본 좌표 복원).
        """
        import numpy as np  # noqa: WPS433 (lazy)

        rgb = np.asarray(img.convert("RGB"))  # type: ignore[attr-defined]  (H, W, 3) RGB
        ih, iw = self._input_size
        h0, w0 = rgb.shape[0], rgb.shape[1]
        ratio = min(ih / h0, iw / w0)
        nw, nh = int(round(w0 * ratio)), int(round(h0 * ratio))

        # PIL 로 리사이즈(추가 deps 없이) 후 numpy 변환
        resized = img.resize((nw, nh))  # type: ignore[attr-defined]
        resized_arr = np.asarray(resized.convert("RGB"))

        padded = np.full((ih, iw, 3), _PAD_VALUE, dtype=np.uint8)
        padded[:nh, :nw, :] = resized_arr
        # RGB → BGR (YOLOX 공식 export 는 BGR 입력)
        bgr = padded[:, :, ::-1]
        # (H, W, C) → (1, C, H, W) float32, 무정규화(0~255)
        tensor = bgr.transpose(2, 0, 1)[np.newaxis, ...].astype(np.float32)
        return np.ascontiguousarray(tensor), ratio

    def predict(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        tensor, ratio = self._preprocess(img)
        input_name = self._session.get_inputs()[0].name
        outputs = self._session.run(None, {input_name: tensor})
        return _yolox_postprocess(
            outputs,
            ratio=ratio,
            conf_threshold=params.conf_threshold,
            nms_iou=params.iou,
            input_size=self._input_size,
            # 공식 YOLOX export(tools/export_onnx.py)의 표준 출력은 raw grid →
            # decoded=False 로 grid+stride decode 를 거친다.
            decoded=False,
        )

    def track(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        """직접 호출/테스트 호환 경로 — 자체 탐지 + 인스턴스-로컬 트래커.

        운영 라우터(Phase 2)는 싱글톤 세션 + clip 별 _YoloxTrackerHandle 을 쓴다.
        """
        dets = self.predict(img, params)
        tracker = self._ensure_tracker(reset=(params.frame_index == 0))
        if tracker is None or not dets:
            return dets
        _apply_bytetrack(dets, tracker)
        return dets

    def _ensure_tracker(self, reset: bool) -> Any | None:
        if reset or self._tracker is None:
            self._tracker = _new_bytetrack_tracker()
        return self._tracker


class _YoloxTrackerHandle:
    """clip_id 별 경량 트래커 상태 핸들.

    무거운 YOLOX 세션은 보유하지 않고 **싱글톤 모델을 공유**하며, 자신은 clip 전용
    ByteTrackTracker 상태만 보유한다. track() 시 공유 세션으로 탐지 후 자신의 트래커로
    track_id 를 부여한다.
    """

    def __init__(self) -> None:
        self._tracker: Any | None = _new_bytetrack_tracker()

    def track(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        model = get_yolox_model()
        if model is None:
            return []
        dets = model.predict(img, params)
        if self._tracker is None or not dets:
            return dets
        _apply_bytetrack(dets, self._tracker)
        return dets


# ────────────────────────────────────────────────────────────────────
# 싱글톤 로더
# ────────────────────────────────────────────────────────────────────

def _build_yolox_backend(weights_path: str) -> _YoloxBackend:
    """onnxruntime 를 lazy import 하여 InferenceSession 백엔드를 구성한다.

    호출자는 예외를 catch 하여 load_failed mock 처리한다.
    providers 는 화이트리스트(CUDA/CPU)만 사용 — 사용자 입력 reflection 아님.
    """
    import onnxruntime as ort  # noqa: WPS433 (lazy)

    settings = get_settings()
    device = settings.ai_device
    if device == "cuda":
        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
    else:
        providers = ["CPUExecutionProvider"]
    logger.info("[YOLOX] loading onnx session device=%s", device)
    session = ort.InferenceSession(weights_path, providers=providers)
    return _YoloxBackend(session, input_size=_DEFAULT_INPUT_SIZE, device=device)


def get_yolox_model() -> Any | None:
    """YOLOX 백엔드 싱글톤. mock 모드/가중치 부재/로드 실패 시 None 반환.

    double-checked locking — 정상 호출은 락 없이 빠른 경로 통과, 초기화는 1회만.
    """
    global _yolox_backend, _loaded, _mock_reason
    if _loaded:
        return _yolox_backend

    with _MODEL_LOCK:
        if _loaded:
            return _yolox_backend

        settings = get_settings()
        if settings.ai_mock_mode:
            logger.info("[YOLOX] mock mode (env) — model load skipped")
            _mock_reason = "env_mock"
            _loaded = True
            return None

        weights_path, reason = _resolve_yolox_weights()
        if reason == "weights_missing":
            logger.warning("[YOLOX] weights not found — fallback to mock")
            _mock_reason = "weights_missing"
            _loaded = True
            return None

        try:
            _yolox_backend = _build_yolox_backend(weights_path)
            _mock_reason = None
            _loaded = True
            return _yolox_backend
        except Exception as exc:  # noqa: BLE001 — 미설치/로드실패 모두 크래시 금지
            logger.exception(
                "[YOLOX] load failed type=%s — fallback to mock", type(exc).__name__
            )
            _mock_reason = "load_failed"
            _loaded = True
            return None


def get_yolox_mock_reason() -> str | None:
    """현재 YOLOX 로드 상태의 mock 사유. None 이면 실제 모델."""
    return _mock_reason


def reset_yolox_model() -> None:
    """테스트용 — 싱글톤 상태 초기화."""
    global _yolox_backend, _loaded, _mock_reason
    _yolox_backend = None
    _loaded = False
    _mock_reason = None


# ────────────────────────────────────────────────────────────────────
# 트래커 캐시 (yolo_loader 와 동일 LRU/TTL 정책)
# ────────────────────────────────────────────────────────────────────

def _create_fresh_yolox_tracker() -> Any:
    """clip 전용 경량 트래커 핸들을 생성한다 (테스트는 monkeypatch 로 대체).

    무거운 YOLOX 세션은 싱글톤(get_yolox_model)으로 공유하므로 여기서는 다시 로드하지
    않는다. 각 핸들은 clip 전용 ByteTrackTracker 상태만 보유하여 영상 단위로 격리된다.
    """
    return _YoloxTrackerHandle()


def _evict_expired_locked(now: float) -> None:
    """TTL 만료 entry 를 lazy expiration 으로 제거 (lock held)."""
    expired = [k for k, (_, ts) in _TRACKERS.items() if now - ts > _TRACKER_TTL_SEC]
    for k in expired:
        _TRACKERS.pop(k, None)
        logger.info("[YOLOX] tracker evicted (ttl) clip_id=%s", k)


def _evict_lru_locked() -> None:
    """LRU max 초과 시 가장 오래된 entry 제거 (lock held)."""
    while len(_TRACKERS) > _MAX_TRACKERS:
        oldest_key, _ = _TRACKERS.popitem(last=False)
        logger.info("[YOLOX] tracker evicted (lru) clip_id=%s", oldest_key)


def get_yolox_tracker(clip_id: str, reset: bool) -> Any | None:
    """clip_id 별 격리 YOLOX 트래커 핸들. mock 모드면 None 반환 (호출자가 mock 처리).

    트래커 핸들 계약:
    - reset=True 또는 clip_id 미캐시 → 새 핸들
    - 호출 시 TTL 만료 lazy expiration + LRU max 초과 제거
    - threading.Lock 으로 동시성 보호
    """
    if _mock_reason is None and not _loaded:
        get_yolox_model()
    reason = get_yolox_mock_reason()
    if reason is not None:
        return None

    now = time.time()
    with _TRACKERS_LOCK:
        _evict_expired_locked(now)

        if reset or clip_id not in _TRACKERS:
            instance = _create_fresh_yolox_tracker()
            _TRACKERS[clip_id] = (instance, now)
            _TRACKERS.move_to_end(clip_id)
            _evict_lru_locked()
            logger.info(
                "[YOLOX] tracker created clip_id=%s reset=%s cache_size=%d",
                clip_id,
                reset,
                len(_TRACKERS),
            )
            return instance

        instance, _ = _TRACKERS[clip_id]
        _TRACKERS[clip_id] = (instance, now)
        _TRACKERS.move_to_end(clip_id)
        return instance


def reset_yolox_trackers() -> None:
    """테스트용 — 트래커 캐시 전체 초기화."""
    with _TRACKERS_LOCK:
        _TRACKERS.clear()
