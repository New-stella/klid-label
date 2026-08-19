"""배포 환경에서는 mock 응답 형상 자체가 존재하면 안 된다.

이 프로젝트는 로컬·개발조차 외부 시스템을 **별도 목 서버**로 세워 실제 HTTP 로 호출한다 —
애플리케이션이 스스로 결과를 지어내는 경로를 두지 않기 위해서다. 그런데 AI 추론은 모델이
없거나 로드에 실패하면 로더가 예외를 삼키고 ``None`` 을 돌려주고, 라우터가 그것을 mock 응답으로
처리해 왔다. 즉 **설정을 아무리 옳게 해도** 모델 파일이 빠지면 가짜 라벨이 정상 응답으로 나간다.

폐쇄망에서는 이 경로가 특히 조용하다. 모델을 내려받을 수 없으므로 반입 누락이 곧 영구 mock 인데,
서버는 정상 기동하고 헬스체크도 API 도 200 을 돌려준다.

방어는 두 겹이다.
  1. 기동 — 배포 표식이면 모델 파일 존재를 확인하고 없으면 기동을 거부한다(설치 시점에 드러난다).
  2. 요청 — 그래도 mock 이 될 상황이면 응답을 실패시킨다(503). 이 서버의 응답은 학습데이터의
     라벨이 되므로, 오염된 채 쌓이는 것보다 호출이 실패하는 편이 낫다.

개발 환경(local/dev·미설정)은 종전대로 mock 을 쓴다 — 그 환경의 산출물은 학습데이터가 아니다.
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.startup_guard import (
    _hf_cache_dir_for,
    refuse_mock_in_deployed_env,
    verify_models_available,
)


class _S:
    """검증에 필요한 필드만 가진 설정 스텁."""

    def __init__(self, env, weights="/definitely/missing.onnx", model_id="facebook/sam2-hiera-tiny"):
        self.env = env
        self.yolox_weights_path = weights
        self.sam2_model_id = model_id


# ────────────────────────────── 기동 가드 ──────────────────────────────

@pytest.mark.parametrize("marker", ["stg", "prd", "PRD", " stg "])
def test_배포표식이면_모델이_없을_때_기동을_거부한다(marker):
    with pytest.raises(RuntimeError) as e:
        verify_models_available(_S(marker))
    # 무엇이 없는지와 왜 막는지가 메시지에 있어야 조치할 수 있다.
    assert "모델 파일이 없어" in str(e.value)
    assert "가짜 응답" in str(e.value)


@pytest.mark.parametrize("env", [None, "", "local", "dev", "qa"])
def test_개발환경은_모델이_없어도_기동한다(env):
    # 모델 없이 화면을 붙여 보는 것이 개발 편의다. 그 환경의 산출물은 학습데이터가 아니다.
    verify_models_available(_S(env))


def test_탐지가중치가_있어도_분할모델이_없으면_거부한다(tmp_path, monkeypatch):
    w = tmp_path / "yolox_s.onnx"
    w.write_bytes(b"x")
    monkeypatch.setenv("HF_HOME", str(tmp_path / "hf"))
    with pytest.raises(RuntimeError) as e:
        verify_models_available(_S("prd", weights=str(w)))
    assert "분할·추적 모델 캐시가 없습니다" in str(e.value)
    # 탐지 가중치는 있으므로 그 항목은 지적하지 않는다.
    assert "탐지 가중치 파일이 없습니다" not in str(e.value)


def test_둘_다_있으면_기동한다(tmp_path, monkeypatch):
    w = tmp_path / "yolox_s.onnx"
    w.write_bytes(b"x")
    hf = tmp_path / "hf"
    (hf / "hub" / _hf_cache_dir_for("facebook/sam2-hiera-tiny")).mkdir(parents=True)
    monkeypatch.setenv("HF_HOME", str(hf))
    verify_models_available(_S("prd", weights=str(w)))


def test_HF_HOME_미설정은_거부_사유다(tmp_path, monkeypatch):
    w = tmp_path / "yolox_s.onnx"
    w.write_bytes(b"x")
    monkeypatch.delenv("HF_HOME", raising=False)
    with pytest.raises(RuntimeError) as e:
        verify_models_available(_S("prd", weights=str(w)))
    assert "HF_HOME" in str(e.value)


def test_HF_캐시_디렉터리명_규칙():
    # HF 캐시는 repo id 의 / 를 -- 로 바꿔 models--<org>--<name> 으로 둔다.
    assert _hf_cache_dir_for("facebook/sam2-hiera-tiny") == "models--facebook--sam2-hiera-tiny"


# ────────────────────────────── 요청 시점 백스톱 ──────────────────────────────

@pytest.mark.parametrize("marker", ["stg", "prd"])
def test_배포환경에서_mock_응답을_거부한다(marker, monkeypatch):
    monkeypatch.setenv("ENV", marker)
    from app.config import reload_settings

    reload_settings()
    with pytest.raises(HTTPException) as e:
        refuse_mock_in_deployed_env("SAM2 분할", "load_failed")
    assert e.value.status_code == 503
    # 사유가 응답에 있어야 운영자가 원인을 안다.
    assert "load_failed" in e.value.detail


@pytest.mark.parametrize("env", ["local", "dev", ""])
def test_개발환경은_mock_응답을_그대로_쓴다(env, monkeypatch):
    monkeypatch.setenv("ENV", env)
    from app.config import reload_settings

    reload_settings()
    # 예외 없이 통과해야 한다 — 호출자가 mock 응답을 만든다.
    refuse_mock_in_deployed_env("SAM2 분할", "load_failed")
