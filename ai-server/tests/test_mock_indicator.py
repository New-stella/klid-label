"""Mock 응답 표시 회귀 테스트.

AI_MOCK_MODE=true 일 때 모든 추론 라우터 응답에 mock=true, source="mock" 가
포함되는지 검증한다. (BE 가 mock 응답을 감지해 WARN 로그를 남기는 가드의 전제 조건)
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_yolo_predict_mock_모드시_응답에_mock_true_포함(small_png_b64: str) -> None:
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    # AI_MOCK_MODE=true (conftest 강제) → env_mock 사유
    assert body["mock_reason"] == "env_mock"
    # 연동정의서 표준 래퍼 필드 (성공 응답)
    assert body["success"] is True
    assert body["message"] == "성공"
    assert body["error_code"] is None


def test_sam2_segment_mock_모드시_응답에_mock_true_포함(small_png_b64: str) -> None:
    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": small_png_b64, "box": [10.0, 10.0, 50.0, 50.0]},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "env_mock"
    # 연동정의서 표준 래퍼 필드 (성공 응답)
    assert body["success"] is True
    assert body["message"] == "성공"
    assert body["error_code"] is None


def test_sam2_track_mock_모드시_응답에_mock_true_포함(small_png_b64: str) -> None:
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "track-001",
            "prev_image_b64": small_png_b64,
            "next_image_b64": small_png_b64,
            "prev_polygon": [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"


def test_vlm_verify_mock_모드시_응답에_mock_true_포함(small_png_b64: str) -> None:
    res = client.post(
        "/infer/vlm/verify-objects",
        json={
            "image_b64": small_png_b64,
            "objects": [
                {
                    "obj_id": "obj-1",
                    "expected_label": "person",
                    "bbox": [10.0, 10.0, 50.0, 50.0],
                }
            ],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"


def test_config_default_ai_mock_mode_is_false(monkeypatch) -> None:
    """기본값은 False — 명시적 활성화가 필요함을 보장."""
    monkeypatch.delenv("AI_MOCK_MODE", raising=False)

    from app.config import Settings

    # env_file 미사용 + 환경변수 없음 → 순수 기본값 확인
    settings = Settings(_env_file=None)
    assert settings.ai_mock_mode is False


def test_sam2_segment_가중치_없음시_mock_reason은_weights_missing(
    small_png_b64: str, monkeypatch
) -> None:
    """MEDIUM-4 회귀 테스트.

    ai_mock_mode=False + sam2 모델 인스턴스가 None (가중치 부재) 인 경우,
    응답의 ``mock_reason`` 은 yolo_loader 패턴과 동일하게 ``weights_missing``
    이어야 한다. 이전 sam2.py 구현은 이 경로에서 ``env_mock`` 을 반환했다.
    """
    import app.routers.sam2 as sam2_router
    from app.config import reload_settings

    # AI_MOCK_MODE 끄고 Settings 재로드 → ai_mock_mode=False
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()

    # 모델 로더가 항상 None 반환하도록 패치 (가중치 부재 시뮬레이션)
    # 슬롯 도입(ADR-056)으로 로더가 슬롯 인자를 받는다 — 인자를 흘려 받아 항상 None.
    monkeypatch.setattr(sam2_router, "get_sam2_model", lambda *_a, **_kw: None)
    sam2_router.reset_mock_warn_flag()

    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": small_png_b64, "box": [10.0, 10.0, 50.0, 50.0]},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "weights_missing"


def test_sam2_track_가중치_없음시_mock_reason은_weights_missing(
    small_png_b64: str, monkeypatch
) -> None:
    """MEDIUM-4 회귀 테스트 — track 엔드포인트도 동일하게 동작해야 한다."""
    import app.routers.sam2 as sam2_router
    from app.config import reload_settings

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    # 슬롯 도입(ADR-056)으로 로더가 슬롯 인자를 받는다 — 인자를 흘려 받아 항상 None.
    monkeypatch.setattr(sam2_router, "get_sam2_model", lambda *_a, **_kw: None)
    sam2_router.reset_mock_warn_flag()

    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "track-001",
            "prev_image_b64": small_png_b64,
            "next_image_b64": small_png_b64,
            "prev_polygon": [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["mock_reason"] == "weights_missing"
