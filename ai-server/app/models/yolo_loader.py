"""
YOLO 모델 싱글톤 로더 + 영상 단위 트래커 LRU 캐시.

predict 용 싱글톤:
- get_yolo_model() — 한 번 로드하면 프로세스 수명 동안 재사용
- AI_MOCK_MODE=true (env 강제) 또는 가중치 파일 부재 / 로드 실패 시 None 반환
- 실제 모델 로드는 ultralytics YOLO를 lazy import

track 용 캐시 (Phase 2):
- get_yolo_tracker(clip_id, reset) — clip_id 별 격리된 YOLO 인스턴스
- LRU max 10 + TTL 5분 (lazy expiration)
- 동시성 보호: threading.Lock — FastAPI 단일 워커 가정이지만 안전 우선
- reset=True (frame_index=0) 시 새 인스턴스 강제 생성

mock 사유는 ``get_yolo_mock_reason()`` 으로 조회 가능:
- "env_mock"        : AI_MOCK_MODE=true 환경변수 강제
- "weights_missing" : 가중치 파일 없음
- "load_failed"     : ultralytics 로드 중 예외 발생
- None              : 정상 로드됨 (실제 모델)
"""

from __future__ import annotations

import logging
import os
import threading
import time
from collections import OrderedDict
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_yolo_model: Any | None = None
_loaded: bool = False
_mock_reason: str | None = None

# ───────────────── 트래커 캐시 (영상 단위 격리) ─────────────────
# clip_id → (model, last_access_ts)
_TRACKERS: "OrderedDict[str, tuple[Any, float]]" = OrderedDict()
_TRACKERS_LOCK = threading.Lock()
_MAX_TRACKERS: int = 10
_TRACKER_TTL_SEC: float = 300.0  # 5분


def get_yolo_model() -> Any | None:
    """YOLO 모델 싱글톤. MOCK 모드이거나 가중치 미존재 시 None 반환."""
    global _yolo_model, _loaded, _mock_reason
    if _loaded:
        return _yolo_model

    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[YOLO] mock mode (env) — model load skipped")
        _mock_reason = "env_mock"
        _loaded = True
        return None

    weights_path = settings.yolo_weights_path
    if not os.path.isfile(weights_path):
        logger.warning("[YOLO] weights not found path=%s — fallback to mock", weights_path)
        _mock_reason = "weights_missing"
        _loaded = True
        return None

    try:
        from ultralytics import YOLO  # noqa: WPS433 (lazy import)

        logger.info("[YOLO] loading weights path=%s device=%s", weights_path, settings.ai_device)
        _yolo_model = YOLO(weights_path)
        _mock_reason = None
        _loaded = True
        return _yolo_model
    except Exception as exc:  # noqa: BLE001
        logger.exception("[YOLO] load failed type=%s — fallback to mock", type(exc).__name__)
        _mock_reason = "load_failed"
        _loaded = True
        return None


def get_yolo_mock_reason() -> str | None:
    """현재 모델 로드 상태의 mock 사유. None 이면 실제 모델."""
    return _mock_reason


def reset_yolo_model() -> None:
    """테스트용 — 싱글톤 상태 초기화."""
    global _yolo_model, _loaded, _mock_reason
    _yolo_model = None
    _loaded = False
    _mock_reason = None


# ────────────────────────────────────────────────────────────────────
# 트래커 캐시 (Phase 2)
# ────────────────────────────────────────────────────────────────────

def _create_fresh_tracker() -> Any:
    """새 ultralytics YOLO 인스턴스를 로드한다 (테스트는 monkeypatch 로 대체).

    호출 전 ``get_yolo_mock_reason()`` 이 None 임을 보장해야 한다.
    """
    from ultralytics import YOLO  # noqa: WPS433 (lazy import)

    weights_path = get_settings().yolo_weights_path
    logger.info("[YOLO] tracker fresh instance loading path=%s", weights_path)
    return YOLO(weights_path)


def _evict_expired_locked(now: float) -> None:
    """TTL 만료 entry 를 lazy expiration 으로 제거 (lock held)."""
    expired = [k for k, (_, ts) in _TRACKERS.items() if now - ts > _TRACKER_TTL_SEC]
    for k in expired:
        _TRACKERS.pop(k, None)
        logger.info("[YOLO] tracker evicted (ttl) clip_id=%s", k)


def _evict_lru_locked() -> None:
    """LRU max 초과 시 가장 오래된 entry 제거 (lock held)."""
    while len(_TRACKERS) > _MAX_TRACKERS:
        oldest_key, _ = _TRACKERS.popitem(last=False)
        logger.info("[YOLO] tracker evicted (lru) clip_id=%s", oldest_key)


def get_yolo_tracker(clip_id: str, reset: bool) -> Any | None:
    """clip_id 별 격리 트래커. mock 모드면 None 반환 (호출자가 mock 응답 처리).

    - reset=True 또는 clip_id 가 캐시에 없으면 새 인스턴스 로드
    - 호출 시 TTL 만료 항목 lazy expiration
    - LRU max 초과 시 가장 오래된 entry 제거
    - threading.Lock 으로 동시성 보호
    """
    # mock 모드면 매번 None — 싱글톤 _loaded 가 아직 False 일 수 있으므로 강제 평가
    # (predict 경로가 호출된 적 없으면 _mock_reason 이 미설정. get_yolo_model() 로 결정 유도)
    if _mock_reason is None and not _loaded:
        get_yolo_model()
    reason = get_yolo_mock_reason()
    if reason is not None:
        return None

    now = time.time()
    with _TRACKERS_LOCK:
        _evict_expired_locked(now)

        if reset or clip_id not in _TRACKERS:
            instance = _create_fresh_tracker()
            _TRACKERS[clip_id] = (instance, now)
            _TRACKERS.move_to_end(clip_id)
            _evict_lru_locked()
            logger.info(
                "[YOLO] tracker created clip_id=%s reset=%s cache_size=%d",
                clip_id,
                reset,
                len(_TRACKERS),
            )
            return instance

        # 기존 인스턴스 재사용 — 타임스탬프 갱신 + LRU 끝으로 이동
        instance, _ = _TRACKERS[clip_id]
        _TRACKERS[clip_id] = (instance, now)
        _TRACKERS.move_to_end(clip_id)
        return instance


def reset_yolo_trackers() -> None:
    """테스트용 — 트래커 캐시 전체 초기화."""
    with _TRACKERS_LOCK:
        _TRACKERS.clear()
