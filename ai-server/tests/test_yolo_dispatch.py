"""YOLO 라우터 dispatch 테스트 (Phase 12 — YOLOX 단일 백엔드).

탐지/트래킹은 YOLOX (ONNX Runtime) + ByteTrack 단일 백엔드로 일원화됨
(구 RT-DETR 백엔드는 torch↔torchaudio ABI 불일치로 제거).

HTTP 경로·응답 스키마(YoloResponse/YoloTrackResponse/Detection)는 불변(AC5).
모든 검증은 mock 모드(conftest AI_MOCK_MODE=true)에서 수행한다.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import app.routers.yolo as yolo_router
from app.main import app
from app.models import yolox_loader

client = TestClient(app)


@pytest.fixture(autouse=True)
def _reset_yolox():
    """매 테스트마다 YOLOX 싱글톤/트래커 상태 초기화."""
    yolox_loader.reset_yolox_model()
    yolox_loader.reset_yolox_trackers()
    yield


# ────────────────────────────────────────────────────────────────────
# YOLOX dispatch
# ────────────────────────────────────────────────────────────────────

def test_detector_backend_기본값에서_predict가_yolox_경로로_dispatch됨(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """기본 백엔드(yolox)에서 predict 가 yolox_loader.get_yolox_mock_reason 을 거친다."""
    yolox_loader.reset_yolox_model()
    called: dict[str, int] = {"reason": 0}

    real_reason = yolox_loader.get_yolox_mock_reason

    def _spy_reason() -> str | None:
        called["reason"] += 1
        return real_reason()

    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", _spy_reason)
    yolo_router.reset_mock_warn_flag()

    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    # mock 모드 → env_mock person 박스 응답
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "env_mock"
    assert len(body["detections"]) >= 1
    assert body["detections"][0]["label"] == "person"
    # yolox dispatch 경로를 탔다는 증거 — get_yolox_mock_reason 이 호출됨
    assert called["reason"] >= 1


def test_predict_응답이_기존_YoloResponse_스키마_필드와_동일함(small_png_b64: str) -> None:
    """predict 응답 최상위 필드 + detection 필드 계약 불변 (AC5)."""
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert set(body.keys()) == {
        "detections",
        "mock",
        "source",
        "mock_reason",
        "success",
        "message",
        "error_code",
    }
    det = body["detections"][0]
    assert set(det.keys()) == {"label", "points", "score", "track_id"}


def test_track_응답이_기존_YoloTrackResponse_스키마_필드와_동일함(small_png_b64: str) -> None:
    """track 응답 최상위 필드 계약 불변 (AC5)."""
    res = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-s", "frame_index": 0},
    )
    assert res.status_code == 200
    body = res.json()
    assert set(body.keys()) == {
        "detections",
        "mock",
        "source",
        "mock_reason",
        "success",
        "message",
        "error_code",
    }


def test_mock_모드_predict_track_정상_응답(small_png_b64: str) -> None:
    """mock 모드에서 predict/track 양쪽 정상(200 + env_mock) 응답."""
    p = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    t = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-mt", "frame_index": 0},
    )
    assert p.status_code == 200 and t.status_code == 200
    assert p.json()["mock"] is True and t.json()["mock"] is True
    assert p.json()["mock_reason"] == "env_mock"
    assert t.json()["mock_reason"] == "env_mock"


# ────────────────────────────────────────────────────────────────────
# ultralytics 제거 검증
# ────────────────────────────────────────────────────────────────────

def test_app_routers_yolo에_ultralytics_import가_없음() -> None:
    """yolo.py 소스에 ultralytics / yolo_loader 잔존이 0건이어야 한다."""
    from pathlib import Path

    src = Path(yolo_router.__file__).read_text(encoding="utf-8")
    assert "ultralytics" not in src
    assert "yolo_loader" not in src
    assert "get_yolo_model" not in src
    assert "get_yolo_tracker" not in src


def test_yolo_loader_모듈이_삭제됨() -> None:
    """yolo_loader.py 가 더 이상 존재하지 않는다 (import 실패해야 함)."""
    with pytest.raises(ModuleNotFoundError):
        import app.models.yolo_loader  # noqa: F401


# ────────────────────────────────────────────────────────────────────
# RT-DETR 제거 검증 (YOLOX 단일화)
# ────────────────────────────────────────────────────────────────────

def test_rtdetr_loader_모듈이_삭제됨() -> None:
    """rtdetr_loader.py 가 더 이상 존재하지 않는다 (import 실패해야 함)."""
    with pytest.raises(ModuleNotFoundError):
        import app.models.rtdetr_loader  # noqa: F401


def test_app_routers_yolo에_rtdetr_import가_없음() -> None:
    """yolo.py 소스에 rtdetr / detector_backend 분기 잔존이 0건이어야 한다."""
    from pathlib import Path

    src = Path(yolo_router.__file__).read_text(encoding="utf-8")
    assert "rtdetr" not in src.lower()
    assert "resolved_detector_backend" not in src


def test_config에_detector_backend_설정이_없음() -> None:
    """config.Settings 에서 detector_backend/rtdetr 설정이 제거됨."""
    from app.config import Settings

    fields = Settings.model_fields
    assert "detector_backend" not in fields
    assert "rtdetr_model_id" not in fields
    assert not hasattr(Settings, "resolved_detector_backend")
