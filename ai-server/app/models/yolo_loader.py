"""
YOLO 모델 싱글톤 로더.

MOCK_MODE=true 또는 가중치 파일 부재 시 None 반환 (mock 응답 분기).
실제 모델 로드는 ultralytics YOLO를 lazy import.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_yolo_model: Any | None = None
_loaded: bool = False


def get_yolo_model() -> Any | None:
    """YOLO 모델 싱글톤. MOCK 모드이거나 가중치 미존재 시 None 반환."""
    global _yolo_model, _loaded
    if _loaded:
        return _yolo_model

    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[YOLO] mock mode — model load skipped")
        _loaded = True
        return None

    weights_path = settings.yolo_weights_path
    if not os.path.isfile(weights_path):
        logger.warning("[YOLO] weights not found path=%s — fallback to mock", weights_path)
        _loaded = True
        return None

    try:
        from ultralytics import YOLO  # noqa: WPS433 (lazy import)

        logger.info("[YOLO] loading weights path=%s device=%s", weights_path, settings.ai_device)
        _yolo_model = YOLO(weights_path)
        _loaded = True
        return _yolo_model
    except Exception as exc:  # noqa: BLE001
        logger.exception("[YOLO] load failed type=%s — fallback to mock", type(exc).__name__)
        _loaded = True
        return None


def reset_yolo_model() -> None:
    """테스트용 — 싱글톤 상태 초기화."""
    global _yolo_model, _loaded
    _yolo_model = None
    _loaded = False
