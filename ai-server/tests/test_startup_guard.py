"""배포 환경(stg/prd) mock 모드 fail-closed 기동 가드 (G-ISSUE-02 후속).

배경 — `AI_MOCK_MODE=true` 는 **가중치가 없어도 무조건 mock 사유를 `env_mock` 으로 보고**하고
(`yolox_loader.get_yolox_model` 이 가중치 확인보다 먼저 설정을 본다), `env_mock` mock 응답은
빈 detections 가 아니라 **합성 person 박스**(중앙, score=0.9)를 만든다. 이 조합이 배포 환경에
들어오면 가짜 라벨이 학습데이터로 저장된다.

BE 쪽 게이트(`YoloAutolabelStep`)가 사유와 무관하게 차단하도록 바뀌었지만, 그것만으로는
"배포 환경인데 mock 모드로 떠 있는" 설정 사고 자체가 남는다(SAM2/VLM 경로 등). 그래서
백엔드의 `QuartzClusteringGuard`·`VlmUrlPolicy` 와 **동일한 정신**으로 위험한 설정 조합은
기동 자체를 실패시킨다 — 경고는 배포 로그에 묻힌다.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import reload_settings
from app.startup_guard import (
    DEPLOYED_ENV_MARKERS,
    deployed_env_marker,
    verify_deployment_settings,
)


# ────────────────────────────────────────────────────────────────────
# 순수 판정 — 컨테이너 없이 검증
# ────────────────────────────────────────────────────────────────────

def test_배포표식_판정은_stg_prd만_인정하고_공백_대소문자를_정규화한다() -> None:
    assert deployed_env_marker("prd") == "prd"
    assert deployed_env_marker(" STG ") == "stg"
    # 미지 라벨(qa 등)·미설정은 배포 표식이 아니다 — 사내 임시 환경을 막지 않는다(BE 가드와 동일 강도).
    assert deployed_env_marker("qa") is None
    assert deployed_env_marker("dev") is None
    assert deployed_env_marker("") is None
    assert deployed_env_marker(None) is None
    assert DEPLOYED_ENV_MARKERS == frozenset({"stg", "prd"})


def test_AI_MOCK_MODE가_true이고_ENV가_stg_prd이면_기동이_실패한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for env_name in ("stg", "prd", "PRD", " stg "):
        # given — 배포 표식이 있는데 mock 모드
        monkeypatch.setenv("AI_MOCK_MODE", "true")
        monkeypatch.setenv("ENV", env_name)
        settings = reload_settings()

        # when / then — 조용히 뜨지 않고 기동을 거부한다
        with pytest.raises(RuntimeError) as exc:
            verify_deployment_settings(settings)
        assert "AI_MOCK_MODE" in str(exc.value)


def test_AI_MOCK_MODE가_true여도_ENV가_local_dev면_정상_기동한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """회귀 — 개발 편의(모델 없이 기동)는 그대로 유지한다."""
    for env_name in ("", "local", "dev", "qa"):
        # given
        monkeypatch.setenv("AI_MOCK_MODE", "true")
        monkeypatch.setenv("ENV", env_name)
        settings = reload_settings()

        # when / then
        verify_deployment_settings(settings)


def test_ENV가_prd여도_AI_MOCK_MODE가_false면_정상_기동한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given — 정상 운영 형상
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("ENV", "prd")
    settings = reload_settings()

    # when / then
    verify_deployment_settings(settings)


# ────────────────────────────────────────────────────────────────────
# 실배선 — lifespan 이 실제로 가드를 호출한다
# ────────────────────────────────────────────────────────────────────

def test_배포표식_mock모드_조합에서는_앱_lifespan이_예외로_중단된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    monkeypatch.setenv("ENV", "prd")
    reload_settings()
    from app.main import app

    # when / then — uvicorn 기동 시 startup 이 실패한다
    with pytest.raises(RuntimeError):
        with TestClient(app):
            pass


def test_dev환경에서는_mock모드여도_앱이_정상_기동한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    monkeypatch.setenv("ENV", "dev")
    reload_settings()
    from app.main import app

    # when / then
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
