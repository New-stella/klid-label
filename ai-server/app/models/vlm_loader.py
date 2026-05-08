"""
VLM 모델 싱글톤 로더 (V1.7 — 객체 검증 한정).

Phase 4 시점에는 MOCK 응답으로 동작한다.
실제 통합은 후속 Phase에서 진행.
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_vlm_model: Any | None = None
_loaded: bool = False


def get_vlm_model() -> Any | None:
    global _vlm_model, _loaded
    if _loaded:
        return _vlm_model

    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[VLM] mock mode — model load skipped")
        _loaded = True
        return None

    logger.info("[VLM] real load not implemented yet — mock fallback model_name=%s", settings.vlm_model_name)
    _loaded = True
    return None


def reset_vlm_model() -> None:
    global _vlm_model, _loaded
    _vlm_model = None
    _loaded = False
