"""
SAM2 모델 싱글톤 로더.

AI_MOCK_MODE=false + 가중치 파일 존재 시에만 실제 ultralytics SAM 모델 로드.
가중치 없거나 mock 모드이면 None 반환 → 라우터에서 mock fallback.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_sam2_model: Any | None = None
_loaded: bool = False


def get_sam2_model() -> Any | None:
    global _sam2_model, _loaded
    if _loaded:
        return _sam2_model

    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[SAM2] mock mode — model load skipped")
        _loaded = True
        return None

    weights_path = settings.sam2_weights_path
    if not os.path.isfile(weights_path):
        logger.warning("[SAM2] weights not found path=%s — fallback to mock", weights_path)
        _loaded = True
        return None

    try:
        from ultralytics import SAM  # noqa: WPS433 (lazy import — GPU 선택적 로드)

        model = SAM(weights_path)
        _sam2_model = model
        _loaded = True
        logger.info("[SAM2] model loaded path=%s device=%s", weights_path, settings.ai_device)
    except Exception:
        logger.exception("[SAM2] model load failed — fallback to mock")
        _loaded = True

    return _sam2_model


def reset_sam2_model() -> None:
    global _sam2_model, _loaded
    _sam2_model = None
    _loaded = False
