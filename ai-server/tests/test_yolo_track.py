"""YOLO Track 라우터 테스트 (Phase 2 — YOLOX dispatch).

POST /infer/yolo/track:
- 기본 백엔드 YOLOX(ONNX Runtime) + ByteTrack 기반 객체 트래킹
- clip_id 별 트래커 상태 격리 (yolox_loader LRU 캐시 + TTL)
- frame_index=0 이면 트래커 리셋
- mock 모드(AI_MOCK_MODE=true): env_mock → 결정적 track_id=1 person 박스 /
  weights_missing → 빈 detections

ultralytics 트래커(FakeBoxes/FakeYOLO.track) 흉내는 제거됐다. yolox dispatch 경로의
track 계약을 mock 모드에서 검증한다.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import reload_settings
from app.main import app
from app.models import yolox_loader

client = TestClient(app)


@pytest.fixture(autouse=True)
def _force_yolox_backend(monkeypatch: pytest.MonkeyPatch):
    """로컬 .env 가 DETECTOR_BACKEND=rtdetr 를 줄 수 있으므로 yolox 경로를 명시 선택한다."""
    monkeypatch.setenv("DETECTOR_BACKEND", "yolox")
    reload_settings()
    yolox_loader.reset_yolox_model()
    yolox_loader.reset_yolox_trackers()
    yield
    monkeypatch.delenv("DETECTOR_BACKEND", raising=False)
    reload_settings()


# ────────────────────────────────────────────────────────────────────
# Mock 응답 케이스 (env_mock — conftest 가 AI_MOCK_MODE=true 적용)
# ────────────────────────────────────────────────────────────────────

def test_track_mock_모드_결정적_track_id_1_반환(small_png_b64: str) -> None:
    """AI_MOCK_MODE=true 환경에서는 결정적 track_id=1 person 박스 반환."""
    res = client.post(
        "/infer/yolo/track",
        json={
            "image_b64": small_png_b64,
            "clip_id": "video-A",
            "frame_index": 0,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "env_mock"
    assert len(body["detections"]) == 1
    det = body["detections"][0]
    assert det["label"] == "person"
    assert det["track_id"] == 1
    assert 0.0 <= det["score"] <= 1.0
    assert len(det["points"]) == 4


def test_track_weights_missing_빈_detections(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """weights_missing 사유 mock 응답은 detections 빈 배열 + track_id 없음."""
    # get_yolox_tracker() 가 weights_missing 사유로 None 반환하도록 패치
    yolox_loader.reset_yolox_trackers()
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: "weights_missing")

    res = client.post(
        "/infer/yolo/track",
        json={
            "image_b64": small_png_b64,
            "clip_id": "video-B",
            "frame_index": 0,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "weights_missing"
    assert body["detections"] == []


def test_track_같은_clip_id_연속_프레임_정상_응답(small_png_b64: str) -> None:
    """mock 모드에서 같은 clip_id 의 frame_index=0,1,2 연속 호출이 모두 정상 응답."""
    yolox_loader.reset_yolox_trackers()
    res0 = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 0},
    )
    res1 = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 1},
    )
    res2 = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 2},
    )

    for r in (res0, res1, res2):
        assert r.status_code == 200
        body = r.json()
        assert body["mock"] is True
        assert body["source"] == "mock"
        # env_mock 결정적 응답 — track_id=1 person 박스
        assert body["detections"][0]["track_id"] == 1
        assert body["detections"][0]["label"] == "person"


def test_track_응답에_track_id_필드_계약_유지됨(small_png_b64: str) -> None:
    """track 응답의 detection 은 항상 track_id 필드를 포함한다 (스키마 계약)."""
    res = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-contract", "frame_index": 0},
    )
    assert res.status_code == 200
    det = res.json()["detections"][0]
    assert set(det.keys()) == {"label", "points", "score", "track_id"}
    assert det["track_id"] is not None  # env_mock 은 track_id=1


def test_track_invalid_base64_입력시_400() -> None:
    """잘못된 base64 입력은 400 (입력 검증 — graceful 거부)."""
    res = client.post(
        "/infer/yolo/track",
        json={"image_b64": "!!! not base64 !!!", "clip_id": "vid-x", "frame_index": 0},
    )
    assert res.status_code == 400
    body = res.json()
    assert body["error_code"] in {"INVALID_IMAGE", "VALIDATION_ERROR"}
