"""배포 환경(stg/prd) 위험 설정 조합 **fail-closed 기동 가드**.

백엔드의 ``QuartzClusteringGuard`` / ``VlmUrlPolicy`` / ``GenAiIntegrationWiringGuard`` 와
동일한 정신이다 — *배포 환경에서 위험한 설정 조합은 경고가 아니라 기동 자체를 막는다*.
WARN 은 배포 로그에 묻히고, 묻힌 사이에 산출물이 오염된다.

왜 ``AI_MOCK_MODE`` 인가
-----------------------
``AI_MOCK_MODE=true`` 는 단순히 "모델을 안 띄운다" 가 아니다:

1. :func:`app.models.yolox_loader.get_yolox_model` 이 **가중치 존재 확인보다 먼저** 이 설정을
   보므로, 실제로 가중치가 없어도 mock 사유가 무조건 ``env_mock`` 으로 보고된다.
2. ``env_mock`` 사유의 mock 응답은 빈 detections 가 아니라 **합성 person 박스**(중앙,
   score=0.9)다(:func:`app.routers.yolo._mock_predict`).

즉 배포 환경에서 이 플래그가 켜지면 *실제 객체와 무관한 가짜 라벨*이 정상 응답처럼 흘러간다.
BE 게이트(``YoloAutolabelStep``)가 사유와 무관하게 차단하도록 바뀌었지만, 그 게이트가 없는
경로(SAM2 분할·VLM)까지 덮으려면 mock 형상 자체가 배포 환경에 존재하지 않아야 한다.

판정 축
-------
배포 여부는 백엔드와 **같은 신호**(``ENV`` 환경변수 = ``stg`` | ``prd``)로 판정한다
(새 환경변수 발명 금지). 미지 라벨(``qa`` 등)·미설정은 배포 표식이 아니다 — 사내 임시 환경의
정상 기동을 막지 않는다(백엔드 ``DevProfileGuard`` 와 동일 강도).
"""

from __future__ import annotations

import logging

from app.config import Settings

logger = logging.getLogger(__name__)

#: 배포 환경 표식(``ENV``). 백엔드 ``DeployedEnvironmentDetector.DEPLOYED_ENV_MARKERS`` 와 동일 기준.
DEPLOYED_ENV_MARKERS: frozenset[str] = frozenset({"stg", "prd"})


def deployed_env_marker(env_name: str | None) -> str | None:
    """``ENV`` 가 배포 표식이면 정규화된 값을, 아니면 ``None`` 을 돌려준다."""
    if env_name is None:
        return None
    normalized = env_name.strip().lower()
    return normalized if normalized in DEPLOYED_ENV_MARKERS else None


def verify_deployment_settings(settings: Settings) -> None:
    """배포 환경(stg/prd)에서 mock 모드가 켜져 있으면 기동을 거부한다.

    :raises RuntimeError: 배포 표식 + ``AI_MOCK_MODE=true`` 조합일 때
    """
    if not settings.ai_mock_mode:
        return
    marker = deployed_env_marker(settings.env)
    if marker is None:
        logger.warning(
            "[AI] AI_MOCK_MODE=true — 합성 mock 응답이 반환됩니다. 개발 환경 전용 설정입니다."
        )
        return
    raise RuntimeError(
        "AI_MOCK_MODE=true 는 배포 환경(ENV=stg|prd)에서 허용되지 않습니다"
        f" (현재 ENV={marker})."
        " mock 모드는 실제 객체와 무관한 합성 라벨(person, score=0.9)을 정상 응답처럼 반환하므로"
        " 학습데이터가 오염됩니다. AI_MOCK_MODE=false 로 두고 가중치를 배포하세요."
    )
