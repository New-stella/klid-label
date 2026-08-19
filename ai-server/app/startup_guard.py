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


def refuse_mock_in_deployed_env(feature: str, reason: str) -> None:
    """배포 환경(stg/prd)에서는 mock 응답을 **내보내지 않고 실패**시킨다.

    왜 기동 가드만으로 부족한가
    ---------------------------
    :func:`verify_deployment_settings` 는 ``AI_MOCK_MODE`` 라는 *설정*만 본다. 그런데 mock 은
    설정 없이도 발생한다 — 모델 파일이 반입되지 않았거나 로드가 실패하면 로더가 예외를 삼키고
    ``None`` 을 돌려주고, 라우터는 그것을 mock 응답으로 처리한다(``weights_missing`` /
    ``load_failed``).

    폐쇄망에서는 이 경로가 특히 조용하다. 모델을 내려받을 수 없으므로 <반입 누락 = 영구 mock>
    인데, 서버는 정상 기동하고 헬스체크도 API 도 200 을 돌려준다. 즉 **아무 신호가 없다.**

    왜 응답을 실패시키는가
    ----------------------
    이 서버의 응답은 **학습데이터의 라벨이 된다.** 가짜 좌표가 정상 응답으로 흘러가면 그대로
    데이터셋에 적재된다. 자동 적용을 막는 화면 쪽 방어가 있으나 그것은 <우리 화면>의 동작이고,
    이 API 의 소비자가 그것뿐이라는 보장이 없다. 틀린 라벨이 조용히 쌓이는 것보다 **호출이
    실패하는 편이 낫다** — 실패는 관측되고 고칠 수 있지만, 오염된 데이터셋은 되돌리기 어렵다.

    개발 환경(local/dev·미설정)에서는 그대로 mock 을 쓴다. 모델 없이 화면을 붙여 보는 것이
    개발 편의이기 때문이며, 그 환경의 산출물은 학습데이터가 아니다.

    :param feature: 실패를 알릴 기능명(로그·응답 문구용). 사용자 입력을 넣지 않는다.
    :param reason: 로더가 판정한 mock 사유(``env_mock`` | ``weights_missing`` | ``load_failed``)
    :raises HTTPException: 배포 표식(stg/prd)일 때 503
    """
    from fastapi import HTTPException  # 지연 import — 이 모듈은 기동 가드에서도 쓰인다

    from app.config import get_settings

    marker = deployed_env_marker(get_settings().env)
    if marker is None:
        return
    logger.error(
        "[AI] %s 를 mock 으로 응답할 뻔했습니다 — 배포 환경(%s)이라 거부합니다. reason=%s",
        feature,
        marker,
        reason,
    )
    raise HTTPException(
        status_code=503,
        detail=(
            f"{feature} 모델을 사용할 수 없습니다(reason={reason}). 배포 환경에서는 "
            "가짜 응답을 반환하지 않습니다. 모델 파일이 반입·설치됐는지 확인하세요."
        ),
    )


def _hf_cache_dir_for(model_id: str) -> str:
    """HF 캐시에서 이 모델이 놓이는 디렉터리명. ``a/b`` → ``models--a--b``."""
    return "models--" + model_id.strip().replace("/", "--")


def verify_models_available(settings: Settings) -> None:
    """배포 환경(stg/prd)에서 **모델 파일이 없으면 기동을 거부**한다.

    왜 요청 시점 거부만으로 부족한가
    --------------------------------
    :func:`refuse_mock_in_deployed_env` 는 호출이 들어와야 동작한다. 그때는 이미 배포가 끝나고
    사람이 화면을 쓰고 있는 시점이라, 반입 누락이 <운영 중에> 드러난다. 모델이 없다는 것은
    설치 시점에 이미 확정된 사실이므로 그때 막는 편이 맞다.

    이 프로젝트는 로컬·개발조차 외부 시스템을 <별도 목 서버>로 세워 실제 HTTP 로 호출한다 —
    애플리케이션이 스스로 결과를 지어내는 경로를 두지 않기 위해서다. 그 원칙이 배포본에서만
    무너지지 않도록, 배포 환경에서는 가짜 응답을 만들 수 있는 형상 자체를 기동 단계에서 없앤다.

    파일 존재만 확인하고 **모델을 적재하지는 않는다**. 무거운 의존(torch·onnxruntime)은 첫
    요청까지 미루는 것이 의도된 설계이므로 그 성질을 깨지 않는다.

    :raises RuntimeError: 배포 표식(stg/prd)인데 모델 파일이 없을 때
    """
    import os
    from pathlib import Path

    marker = deployed_env_marker(settings.env)
    if marker is None:
        return

    missing: list[str] = []

    if not Path(settings.yolox_weights_path).is_file():
        missing.append(f"탐지 가중치 파일이 없습니다: {settings.yolox_weights_path}")

    hf_home = os.environ.get("HF_HOME", "").strip()
    if not hf_home:
        missing.append("HF_HOME 이 설정되지 않아 분할·추적 모델 캐시 위치를 알 수 없습니다.")
    elif not (Path(hf_home) / "hub" / _hf_cache_dir_for(settings.sam2_model_id)).is_dir():
        missing.append(
            "분할·추적 모델 캐시가 없습니다: "
            f"{Path(hf_home) / 'hub' / _hf_cache_dir_for(settings.sam2_model_id)}"
        )

    if not missing:
        return

    raise RuntimeError(
        "[AI] 배포 환경(" + marker + ")인데 모델 파일이 없어 기동을 거부합니다.\n  - "
        + "\n  - ".join(missing)
        + "\n\n이대로 기동하면 해당 기능이 <가짜 응답>으로 동작하고, 서버는 정상 기동하고"
        " API 도 200 을 돌려주므로 운영 중에 드러나지 않습니다. 이 서버의 응답은 학습데이터의"
        " 라벨이 되므로 오염된 채 쌓이는 것보다 기동을 막는 편이 낫습니다.\n"
        "조치: 폐쇄망이라면 빌드머신에서 패키징을 다시 수행해 모델을 번들에 담고 재설치하세요."
    )
