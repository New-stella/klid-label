"""
SAM2 모델 로더 — **실행 슬롯마다 별도 인스턴스**.

AI_MOCK_MODE=false 일 때만 Meta 공식 sam2(Apache-2.0) ``SAM2ImagePredictor`` 를
HuggingFace ``from_pretrained`` 로 lazy 로드한다. mock 모드이거나 로드 실패 시
None 반환 → 라우터에서 mock fallback (graceful).

- sam2/torch 는 무거우므로 **lazy import** (이 모듈 import 시 미로드).
- 모델 ID 는 설정값(``sam2_model_id``)만 사용 — 사용자 입력 reflection 금지.
- import/로드 실패 시 크래시 금지 → load_failed 사유로 None.

★ **왜 슬롯마다 따로 두는가 (실측으로 확증됨 · ``ADR-056``)**

``SAM2ImagePredictor.set_image`` 가 이미지 임베딩을 **인스턴스 필드에 보관**한다
(``app/routers/sam2.py`` 의 ``_predict_masks``). 두 슬롯이 한 인스턴스를 나눠 쓰면 뒤에 온
``set_image`` 가 앞의 임베딩을 덮어써 **마스크가 어긋날 뿐 아니라 크래시한다** — 실측에서
공유 12회 중 어긋남 1건과 ``TypeError: 'NoneType' object is not subscriptable``
(``self._features["high_res_feats"]``)로 스레드가 죽었다. 분리하면 20/20 정합이다.

⚠ **지금 안 터지는 것은 이벤트 루프가 막고 있기 때문이다** — 추론을 슬롯으로 오프로드하는
순간 열리는 문이라, 오프로드와 인스턴스 분리는 반드시 함께 가야 한다.

⚠ YOLOX ONNX 세션은 반대로 **공유한다**(``yolox_loader``). 동시 실행 안전성이 실측으로
확인됐고, 슬롯마다 만들면 가속기 메모리가 예산을 넘는다. 두 로더의 정책이 다른 것은 의도다.
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings
from app.slots import INTERACTIVE, SLOT_NAMES

logger = logging.getLogger(__name__)

#: 슬롯을 명시하지 않은 호출의 기본 슬롯 — 화면 쪽이다(fail-safe 방향).
DEFAULT_SLOT = INTERACTIVE

# 슬롯 이름 → predictor. 로드 시도 여부와 mock 사유도 슬롯 단위로 기록한다.
_models: dict[str, Any] = {}
_loaded: dict[str, bool] = {}
_mock_reasons: dict[str, str | None] = {}


def _load(slot: str) -> Any | None:
    """해당 슬롯의 predictor 를 한 번만 만든다. 실패는 사유를 남기고 None."""
    settings = get_settings()
    if settings.ai_mock_mode:
        logger.info("[SAM2] mock mode — model load skipped slot=%s", slot)
        _mock_reasons[slot] = "env_mock"
        return None

    model_id = settings.sam2_model_id
    try:
        # lazy import — sam2/torch 는 이 호출 시점에만 로드 (import 부작용 방지)
        from sam2.sam2_image_predictor import SAM2ImagePredictor  # noqa: WPS433

        # device 를 명시 전달 — Meta sam2 from_pretrained 기본값이 "cuda" 라
        # CPU 전용/torch CUDA 미컴파일 환경에서 AssertionError 로 load_failed 폴백되는 것을 방지.
        # device 값은 설정값(ai_device)만 — 사용자 입력 reflection 아님.
        predictor = SAM2ImagePredictor.from_pretrained(model_id, device=settings.ai_device)
        _mock_reasons[slot] = None
        logger.info(
            "[SAM2] predictor loaded slot=%s model_id=%s device=%s",
            slot, model_id, settings.ai_device,
        )
        return predictor
    except Exception:
        # 미설치/다운로드 실패/로드 실패 모두 graceful — 서버 기동·요청 처리는 mock 으로 지속
        logger.exception("[SAM2] predictor load failed slot=%s — fallback to mock", slot)
        _mock_reasons[slot] = "load_failed"
        return None


def get_sam2_model(slot: str = DEFAULT_SLOT) -> Any | None:
    """해당 슬롯 전용 predictor. 슬롯마다 한 번만 만들고 그 뒤로는 같은 인스턴스를 돌려준다.

    ⚠ 슬롯끼리 인스턴스를 돌려쓰지 않는다 — 모듈 docstring 의 근거 참조.
    """
    if _loaded.get(slot):
        return _models.get(slot)

    model = _load(slot)
    _models[slot] = model
    _loaded[slot] = True
    return model


def get_sam2_mock_reason(slot: str = DEFAULT_SLOT) -> str | None:
    """이 슬롯의 mock 응답 사유. None 이면 실제 predictor 사용 가능.

    ⚠ **다른 슬롯의 사유를 대신 돌려주지 않는다.** 사유는 그 슬롯이 실제로 로드를 시도했을 때만
    생기며, 시도한 적이 없으면 None 이고 호출자가 자기 폴백 규칙
    (``env_mock``/``weights_missing``)을 적용한다 — 슬롯 도입 이전과 같은 판정이다.
    """
    return _mock_reasons.get(slot)


def warmup_sam2_models() -> None:
    """기동 시 모든 슬롯의 predictor 를 미리 만든다(첫 요청이 로드 비용을 물지 않도록)."""
    for slot in SLOT_NAMES:
        get_sam2_model(slot)


def reset_sam2_model() -> None:
    """모든 슬롯의 상태를 초기화한다(테스트·재로드 경로)."""
    _models.clear()
    _loaded.clear()
    _mock_reasons.clear()
