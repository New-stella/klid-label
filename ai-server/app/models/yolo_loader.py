"""
YOLO 모델 싱글톤 로더.

AI_MOCK_MODE=true (env 강제) 또는 가중치 파일 부재 / 로드 실패 시 None 반환 (mock 응답 분기).
실제 모델 로드는 ultralytics YOLO를 lazy import.

mock 사유는 ``get_yolo_mock_reason()`` 으로 조회 가능:
- "env_mock"        : AI_MOCK_MODE=true 환경변수 강제
- "weights_missing" : 가중치 파일 없음
- "load_failed"     : ultralytics 로드 중 예외 발생
- None              : 정상 로드됨 (실제 모델)
"""

from __future__ import annotations

import logging
import os
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_yolo_model: Any | None = None
_loaded: bool = False
_mock_reason: str | None = None


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
