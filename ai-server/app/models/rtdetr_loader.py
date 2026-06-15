"""RT-DETRv2 탐지 백엔드 로더 + ByteTrack 트래커 LRU/TTL 캐시.

yolo_loader 와 동일한 구조(싱글톤 + LRU max10 + TTL 300s + mock 사유 + WARN-once)를
RT-DETR(transformers) + ByteTrack(roboflow `trackers`) 백엔드로 구현한다.

성능 구조 (싱글톤 모델 공유 + 경량 트래커 분리):
- RT-DETR 전체 모델은 **싱글톤으로 1회 로드해 공유**한다(get_rtdetr_model). 무거운 모델을
  clip 마다 재로드하지 않는다.
- clip_id 별로는 **가벼운 ByteTrackTracker 상태만** LRU/TTL 캐시에 담는다(_RtdetrTrackerHandle).
  탐지(predict)는 공유 싱글톤 모델로 1회 수행하고, track ID 부여만 clip 별 트래커로 한다.

트래커 마이그레이션 (deprecated 제거):
- supervision 0.30 에서 제거 예정인 sv.ByteTrack(update_with_detections) 대신
  roboflow `trackers` 패키지(Apache-2.0)의 ByteTrackTracker.update() 를 사용한다.
- 입력 detection 변환에는 여전히 supervision 의 sv.Detections 를 사용한다(trackers 호환).
- ByteTrackTracker.update() 는 sv.Detections 를 그대로 반환하며 tracker_id 의 -1 은
  '미확정 트랙'을 의미하므로 track_id=None 으로 매핑한다.

mock 사유 (yolo 와 동일 체계):
- "env_mock"        : AI_MOCK_MODE=true 환경변수 강제
- "weights_missing" : (RT-DETR 은 HF Hub 자동 다운로드라 거의 없음 — 호환 위해 보유)
- "load_failed"     : transformers/trackers 미설치 또는 모델 로드 중 예외
- None              : 정상 로드됨 (실제 모델)

Critical:
- transformers/supervision/trackers 는 무거우므로 **반드시 lazy import** (모듈 최상단 import 금지).
- 기본 backend=yolo 경로는 이 모듈을 import 하지만, get_rtdetr_model() 을 호출하지
  않는 한 transformers/trackers 를 import 하지 않는다.
- import/로드 실패 시 크래시 금지 → load_failed mock 사유로 None 반환.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import OrderedDict
from typing import Any

from app.config import get_settings
from app.models.detector_backend import (
    DetectionResult,
    InferenceParams,
    coco_id_from_label,
    coco_label_from_id,
)

logger = logging.getLogger(__name__)

_rtdetr_backend: Any | None = None
_loaded: bool = False
_mock_reason: str | None = None
# 싱글톤 초기화 보호 — get_rtdetr_model 의 double-checked locking 용.
# 멀티스레드 첫 호출 시 무거운 모델이 중복 빌드되지 않도록 한다.
_MODEL_LOCK = threading.Lock()

# ───────────────── 트래커 캐시 (영상 단위 격리, yolo_loader 와 동일 정책) ─────────────────
# clip_id → (backend, last_access_ts)
_TRACKERS: "OrderedDict[str, tuple[Any, float]]" = OrderedDict()
_TRACKERS_LOCK = threading.Lock()
_MAX_TRACKERS: int = 10
_TRACKER_TTL_SEC: float = 300.0  # 5분

# ByteTrackTracker import 실패 WARN-once (yolo router 의 _mock_warned 패턴과 일관).
# trackers 미설치 등으로 생성 실패 시 매 호출 WARN 하지 않고 프로세스당 1회만 출력한다.
_tracker_unavailable_warned: bool = False


# ────────────────────────────────────────────────────────────────────
# RT-DETR 백엔드 구현 (실제 추론) — predict/track
# ────────────────────────────────────────────────────────────────────

class _RtdetrBackend:
    """transformers RT-DETRv2 추론 (싱글톤 공유 모델).

    - predict: AutoImageProcessor + RTDetrV2ForObjectDetection
      → post_process_object_detection(threshold=conf) → DetectionResult 목록
    - track: 자체 탐지 후 인스턴스-로컬 ByteTrackTracker 로 track_id 부여(레거시 호환 경로).
      운영 경로(라우터)는 싱글톤 모델 + clip 별 _RtdetrTrackerHandle 을 사용하므로 이 메서드는
      더 이상 호출하지 않지만, 직접 사용/테스트 호환을 위해 유지한다.

    무거운 모델 인스턴스라 clip 마다 재로드하지 않고 싱글톤으로 1회만 생성해 공유한다.
    """

    def __init__(
        self,
        processor: Any,
        model: Any,
        id2label: dict[int, str] | None,
        device: str = "cpu",
    ):
        self._processor = processor
        self._model = model
        self._id2label = id2label
        self._device = device  # "cuda" | "cpu" — inputs/target_sizes 이동 대상
        self._tracker: Any | None = None  # lazy — track 최초 호출 시 생성

    def _infer_raw(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        import torch  # noqa: WPS433 (lazy)

        # imgsz 는 processor resize 힌트로만 사용(RT-DETR 의미 변경). iou 는 NMS-free 라 미사용.
        size = {"height": params.imgsz, "width": params.imgsz}
        inputs = self._processor(images=img, size=size, return_tensors="pt")
        # cuda 설정 시 모든 입력 텐서를 모델과 동일 device 로 이동 (cpu 면 사실상 no-op)
        inputs = {k: v.to(self._device) for k, v in inputs.items()}
        with torch.no_grad():
            outputs = self._model(**inputs)

        target_sizes = torch.tensor(  # type: ignore[attr-defined]
            [[img.height, img.width]]
        ).to(self._device)
        results = self._processor.post_process_object_detection(
            outputs, target_sizes=target_sizes, threshold=params.conf_threshold
        )[0]

        dets: list[DetectionResult] = []
        for score, label_id, box in zip(
            results["scores"], results["labels"], results["boxes"]
        ):
            x1, y1, x2, y2 = (float(v) for v in box.tolist())
            dets.append(
                DetectionResult(
                    label=coco_label_from_id(int(label_id), self._id2label),
                    points=[x1, y1, x2, y2],
                    score=float(score),
                    track_id=None,
                )
            )
        return dets

    def predict(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        return self._infer_raw(img, params)

    def track(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        """레거시/직접 호출 호환 경로 — 자체 탐지 + 인스턴스-로컬 트래커.

        운영 라우터는 싱글톤 모델 + clip 별 _RtdetrTrackerHandle 을 쓰므로 이 메서드는
        직접 사용/하위 호환 시에만 동작한다.
        """
        dets = self._infer_raw(img, params)
        tracker = self._ensure_tracker(reset=(params.frame_index == 0))
        if tracker is None or not dets:
            return dets
        _apply_bytetrack(dets, tracker)
        return dets

    def _ensure_tracker(self, reset: bool) -> Any | None:
        if reset or self._tracker is None:
            self._tracker = _new_bytetrack_tracker()
        return self._tracker


# ────────────────────────────────────────────────────────────────────
# ByteTrack (roboflow trackers) 헬퍼 — lazy import, graceful fallback
# ────────────────────────────────────────────────────────────────────

def _new_bytetrack_tracker() -> Any | None:
    """roboflow `trackers` 의 ByteTrackTracker 인스턴스 1개를 생성.

    미설치/로드실패 시 None 반환(graceful — track_id 미부여). lazy import 라
    기본 yolo 경로는 trackers 를 import 하지 않는다.
    """
    global _tracker_unavailable_warned
    try:
        from trackers import ByteTrackTracker  # noqa: WPS433 (lazy)

        return ByteTrackTracker()
    except Exception as exc:  # noqa: BLE001 — trackers 미설치 시 graceful fallback
        # WARN-once: 미설치 환경에서 clip 마다 반복 경고하지 않고 프로세스당 1회만.
        if not _tracker_unavailable_warned:
            logger.warning(
                "[RTDETR] trackers(ByteTrackTracker) unavailable type=%s — track_id 미부여",
                type(exc).__name__,
            )
            _tracker_unavailable_warned = True
        return None


def _apply_bytetrack(dets: list[DetectionResult], tracker: Any) -> None:
    """dets 를 sv.Detections 로 변환 → tracker.update() → track_id 를 in-place 부여.

    - 입력 변환에는 supervision sv.Detections 를 계속 사용(trackers 호환).
    - ByteTrackTracker.update() 는 sv.Detections 를 반환하며 tracker_id 의 -1 은
      '미확정 트랙'이므로 None 으로 매핑한다.
    - 길이 불일치(필터링 등)는 WARN + 누락분 track_id=None 보존(조용한 truncate 방지).
    - 실패는 graceful (track_id 미부여) — 예외를 밖으로 던지지 않는다.
    """
    if not dets:
        return
    try:
        import numpy as np  # noqa: WPS433 (lazy)
        import supervision as sv  # noqa: WPS433 (lazy)

        # class_id 를 라벨 기반 안정 정수로 매핑 — ByteTrackTracker 가 클래스별 트랙 공간을
        # 분리하는 경우 person/car 등 다른 클래스의 track ID 가 충돌하지 않도록 한다(CCTV 다중 클래스).
        sv_dets = sv.Detections(
            xyxy=np.array([d.points for d in dets], dtype=float),
            confidence=np.array([d.score for d in dets], dtype=float),
            class_id=np.array([coco_id_from_label(d.label) for d in dets], dtype=int),
        )
        tracked = tracker.update(sv_dets)
        tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
        if len(tracker_ids) != len(dets):
            logger.warning(
                "[RTDETR] bytetrack tracker_id 길이 불일치 dets=%d tracker_ids=%d "
                "— 누락분 track_id=None 유지",
                len(dets),
                len(tracker_ids),
            )
        for det, tid in zip(dets, tracker_ids):  # 짧은 쪽 길이만큼만 매핑
            # ByteTrackTracker 는 미확정 트랙에 -1 을 부여한다 → None 으로 정규화
            det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None
    except Exception as exc:  # noqa: BLE001 — 트래킹 실패는 graceful (track_id 미부여)
        logger.warning("[RTDETR] bytetrack update failed type=%s", type(exc).__name__)


class _RtdetrTrackerHandle:
    """clip_id 별 경량 트래커 상태 핸들.

    무거운 RT-DETR 모델은 보유하지 않고 **싱글톤 모델을 공유**하며, 자신은 clip 전용
    ByteTrackTracker 상태만 보유한다(캐시에 담기는 가벼운 객체). track() 호출 시
    공유 모델로 탐지 후 자신의 트래커로 track_id 를 부여한다.
    """

    def __init__(self) -> None:
        self._tracker: Any | None = _new_bytetrack_tracker()

    # 주: 운영 경로(get_rtdetr_tracker)는 reset=True(frame_index=0) 시 핸들을 통째로
    # 새로 생성해 교체하므로(테스트 계약: 매 reset 마다 새 핸들), 이 핸들 내부에는 별도
    # reset() 메서드를 두지 않는다. 트래커 상태 초기화 = 새 핸들 생성으로 일원화한다.

    def track(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        """공유 싱글톤 모델로 탐지 → 이 핸들의 트래커로 track_id 부여."""
        model = get_rtdetr_model()
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

def _build_rtdetr_backend() -> _RtdetrBackend:
    """transformers RT-DETRv2 를 lazy import 하여 백엔드를 구성한다.

    호출자는 예외를 catch 하여 load_failed mock 처리한다.
    모델 ID 는 설정값(화이트리스트성)만 사용 — 사용자 입력 reflection 로드 아님.
    """
    settings = get_settings()
    # AutoImageProcessor 와 모델 클래스 import 를 같은 try 블록 안에 두어
    # transformers 미설치/구버전 시 ImportError 가 명시적으로 load_failed 경로로 가게 한다.
    try:
        from transformers import AutoImageProcessor  # noqa: WPS433 (lazy)

        try:
            from transformers import RTDetrV2ForObjectDetection as _RtdetrModel  # noqa: WPS433
        except ImportError:  # 구버전 transformers 호환 (RtDetr)
            from transformers import RTDetrForObjectDetection as _RtdetrModel  # noqa: WPS433
    except ImportError as exc:  # transformers 미설치/심볼 부재 → 명시적 전파(호출자가 load_failed 처리)
        raise ImportError(
            "transformers(RT-DETR) import 실패 — 미설치 또는 구버전"
        ) from exc

    device = settings.ai_device
    model_id = settings.rtdetr_model_id
    logger.info("[RTDETR] loading model_id=%s device=%s", model_id, device)
    processor = AutoImageProcessor.from_pretrained(model_id)
    model = _RtdetrModel.from_pretrained(model_id)
    model.eval()
    model.to(device)  # cuda 설정 시 GPU 이동 (cpu 면 no-op)

    id2label = getattr(getattr(model, "config", None), "id2label", None)
    # transformers id2label 키가 문자열일 수 있으므로 int 정규화
    if isinstance(id2label, dict):
        id2label = {int(k): v for k, v in id2label.items()}
    return _RtdetrBackend(processor, model, id2label, device)


def get_rtdetr_model() -> Any | None:
    """RT-DETR 백엔드 싱글톤. mock 모드/로드 실패 시 None 반환.

    double-checked locking: 락 밖 빠른 경로(_loaded)로 정상 호출은 락 비용 없이 통과하고,
    초기화는 _MODEL_LOCK 안에서 _loaded 재확인 후 1회만 수행한다(멀티스레드 중복 빌드 방지).
    """
    global _rtdetr_backend, _loaded, _mock_reason
    # 빠른 경로 — 이미 로드 완료면 락 없이 즉시 반환
    if _loaded:
        return _rtdetr_backend

    with _MODEL_LOCK:
        # 락 진입 사이 다른 스레드가 초기화했을 수 있으므로 재확인
        if _loaded:
            return _rtdetr_backend

        settings = get_settings()
        if settings.ai_mock_mode:
            logger.info("[RTDETR] mock mode (env) — model load skipped")
            _mock_reason = "env_mock"
            _loaded = True
            return None

        try:
            _rtdetr_backend = _build_rtdetr_backend()
            _mock_reason = None
            _loaded = True
            return _rtdetr_backend
        except Exception as exc:  # noqa: BLE001 — 미설치/로드실패 모두 크래시 금지
            logger.exception(
                "[RTDETR] load failed type=%s — fallback to mock", type(exc).__name__
            )
            _mock_reason = "load_failed"
            _loaded = True
            return None


def get_rtdetr_mock_reason() -> str | None:
    """현재 RT-DETR 로드 상태의 mock 사유. None 이면 실제 모델."""
    return _mock_reason


def reset_rtdetr_model() -> None:
    """테스트용 — 싱글톤 상태 초기화."""
    global _rtdetr_backend, _loaded, _mock_reason
    _rtdetr_backend = None
    _loaded = False
    _mock_reason = None


# ────────────────────────────────────────────────────────────────────
# 트래커 캐시 (yolo_loader 와 동일 LRU/TTL 정책)
# ────────────────────────────────────────────────────────────────────

def _create_fresh_rtdetr_tracker() -> Any:
    """clip 전용 경량 트래커 핸들을 생성한다 (테스트는 monkeypatch 로 대체).

    무거운 RT-DETR 모델은 싱글톤(get_rtdetr_model)으로 공유하므로 여기서는 다시 로드하지
    않는다. 각 핸들은 clip 전용 ByteTrackTracker 상태만 보유하여 영상 단위로 격리된다.
    호출 전 get_rtdetr_mock_reason() 이 None 임(=싱글톤 모델 사용 가능)을 보장해야 한다.
    """
    return _RtdetrTrackerHandle()


def _evict_expired_locked(now: float) -> None:
    """TTL 만료 entry 를 lazy expiration 으로 제거 (lock held)."""
    expired = [k for k, (_, ts) in _TRACKERS.items() if now - ts > _TRACKER_TTL_SEC]
    for k in expired:
        _TRACKERS.pop(k, None)
        logger.info("[RTDETR] tracker evicted (ttl) clip_id=%s", k)


def _evict_lru_locked() -> None:
    """LRU max 초과 시 가장 오래된 entry 제거 (lock held)."""
    while len(_TRACKERS) > _MAX_TRACKERS:
        oldest_key, _ = _TRACKERS.popitem(last=False)
        logger.info("[RTDETR] tracker evicted (lru) clip_id=%s", oldest_key)


def get_rtdetr_tracker(clip_id: str, reset: bool) -> Any | None:
    """clip_id 별 격리 RT-DETR 백엔드. mock 모드면 None 반환 (호출자가 mock 응답 처리).

    yolo_loader.get_yolo_tracker 와 동일 계약:
    - reset=True 또는 clip_id 미캐시 → 새 인스턴스
    - 호출 시 TTL 만료 lazy expiration + LRU max 초과 제거
    - threading.Lock 으로 동시성 보호
    """
    if _mock_reason is None and not _loaded:
        get_rtdetr_model()
    reason = get_rtdetr_mock_reason()
    if reason is not None:
        return None

    now = time.time()
    with _TRACKERS_LOCK:
        _evict_expired_locked(now)

        if reset or clip_id not in _TRACKERS:
            instance = _create_fresh_rtdetr_tracker()
            _TRACKERS[clip_id] = (instance, now)
            _TRACKERS.move_to_end(clip_id)
            _evict_lru_locked()
            logger.info(
                "[RTDETR] tracker created clip_id=%s reset=%s cache_size=%d",
                clip_id,
                reset,
                len(_TRACKERS),
            )
            return instance

        instance, _ = _TRACKERS[clip_id]
        _TRACKERS[clip_id] = (instance, now)
        _TRACKERS.move_to_end(clip_id)
        return instance


def reset_rtdetr_trackers() -> None:
    """테스트용 — 트래커 캐시 전체 초기화."""
    global _tracker_unavailable_warned
    with _TRACKERS_LOCK:
        _TRACKERS.clear()
    _tracker_unavailable_warned = False
