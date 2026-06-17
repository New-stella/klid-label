"""
SAM2 모델 싱글톤 로더.

AI_MOCK_MODE=false 일 때만 Meta 공식 sam2(Apache-2.0) ``SAM2ImagePredictor`` 를
HuggingFace ``from_pretrained`` 로 lazy 로드한다. mock 모드이거나 로드 실패 시
None 반환 → 라우터에서 mock fallback (graceful).

- sam2/torch 는 무거우므로 **lazy import** (이 모듈 import 시 미로드).
- 모델 ID 는 설정값(``sam2_model_id``)만 사용 — 사용자 입력 reflection 금지.
- import/로드 실패 시 크래시 금지 → load_failed 사유로 None.
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_sam2_model: Any | None = None
_loaded: bool = False
_mock_reason: str | None = None


def get_sam2_model() -> Any | None:
    global _sam2_model, _loaded, _mock_reason
    if _loaded:
        return _sam2_model

    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[SAM2] mock mode — model load skipped")
        _loaded = True
        _mock_reason = "env_mock"
        return None

    model_id = settings.sam2_model_id
    try:
        # lazy import — sam2/torch 는 이 호출 시점에만 로드 (import 부작용 방지)
        from sam2.sam2_image_predictor import SAM2ImagePredictor  # noqa: WPS433

        # device 를 명시 전달 — Meta sam2 from_pretrained 기본값이 "cuda" 라
        # CPU 전용/torch CUDA 미컴파일 환경에서 AssertionError 로 load_failed 폴백되는 것을 방지.
        # device 값은 설정값(ai_device)만 — 사용자 입력 reflection 아님.
        predictor = SAM2ImagePredictor.from_pretrained(model_id, device=settings.ai_device)
        _sam2_model = predictor
        _mock_reason = None
        logger.info("[SAM2] predictor loaded model_id=%s device=%s", model_id, settings.ai_device)
    except Exception:
        # 미설치/다운로드 실패/로드 실패 모두 graceful — 서버 기동·요청 처리는 mock 으로 지속
        logger.exception("[SAM2] predictor load failed — fallback to mock")
        _sam2_model = None
        _mock_reason = "load_failed"
    finally:
        _loaded = True

    return _sam2_model


def get_sam2_mock_reason() -> str | None:
    """mock 응답 사유. None 이면 실제 predictor 사용 가능."""
    return _mock_reason


def reset_sam2_model() -> None:
    global _sam2_model, _loaded, _mock_reason
    _sam2_model = None
    _loaded = False
    _mock_reason = None
