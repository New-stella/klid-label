"""RT-DETR 실제 추론 통합 테스트.

transformers/trackers 미설치 또는 모델 가중치 미존재 시 자동 skip.
(test_yolo_real.py 의 skipif 패턴 동일 적용)
- 트래킹은 roboflow `trackers`(ByteTrackTracker) + supervision(sv.Detections 변환) 사용.
"""

from __future__ import annotations

import base64
import importlib.util
import io

import pytest
from fastapi.testclient import TestClient

_HAS_TRANSFORMERS = importlib.util.find_spec("transformers") is not None
_HAS_TRACKERS = importlib.util.find_spec("trackers") is not None
_HAS_SUPERVISION = importlib.util.find_spec("supervision") is not None

pytestmark = pytest.mark.skipif(
    not (_HAS_TRANSFORMERS and _HAS_TRACKERS and _HAS_SUPERVISION),
    reason="transformers/trackers/supervision 미설치 — RT-DETR 실제 추론 skip",
)


def _make_jpeg_b64(width: int = 320, height: int = 240) -> str:
    from PIL import Image

    img = Image.new("RGB", (width, height), color=(200, 200, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


@pytest.fixture()
def real_rtdetr_client(monkeypatch):
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")

    from app.config import reload_settings
    from app.models.rtdetr_loader import reset_rtdetr_model, reset_rtdetr_trackers

    reload_settings()
    reset_rtdetr_model()
    reset_rtdetr_trackers()

    from app.main import app

    client = TestClient(app)
    yield client

    reset_rtdetr_model()
    reset_rtdetr_trackers()
    from app.config import reload_settings as _rs

    _rs()


def test_real_rtdetr_predict_blank_이미지_응답형식_정상(real_rtdetr_client):
    res = real_rtdetr_client.post(
        "/infer/yolo/predict",
        json={"image_b64": _make_jpeg_b64(), "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body
    assert isinstance(body["detections"], list)
    for det in body["detections"]:
        assert set(det.keys()) == {"label", "points", "score", "track_id"}
        assert len(det["points"]) == 4
        assert 0.0 <= det["score"] <= 1.0


def test_real_rtdetr_track_bytetracktracker_응답형식_정상(real_rtdetr_client):
    """ByteTrackTracker(update) 경로 실제 동작 — frame_index 0,1,2 호출 시 응답형식 보존."""
    img = _make_jpeg_b64()
    for fi in (0, 1, 2):
        res = real_rtdetr_client.post(
            "/infer/yolo/track",
            json={"image_b64": img, "clip_id": "real-vid", "frame_index": fi},
        )
        assert res.status_code == 200
        body = res.json()
        assert isinstance(body["detections"], list)
        for det in body["detections"]:
            assert set(det.keys()) == {"label", "points", "score", "track_id"}
            # ByteTrackTracker 미확정 트랙(-1)은 None 으로 정규화되어야 한다
            assert det["track_id"] is None or det["track_id"] >= 0


def test_real_rtdetr_track_싱글톤_모델_공유_clip별_트래커_분리(real_rtdetr_client):
    """여러 clip_id track 호출 시 RT-DETR 모델은 1회만 빌드(싱글톤 공유)되는지 검증."""
    from app.models import rtdetr_loader

    build_calls = {"n": 0}
    orig = rtdetr_loader._build_rtdetr_backend

    def _counting_build():
        build_calls["n"] += 1
        return orig()

    rtdetr_loader.reset_rtdetr_model()
    rtdetr_loader._build_rtdetr_backend = _counting_build  # type: ignore[assignment]
    try:
        img = _make_jpeg_b64()
        for clip in ("c1", "c2", "c3"):
            res = real_rtdetr_client.post(
                "/infer/yolo/track",
                json={"image_b64": img, "clip_id": clip, "frame_index": 0},
            )
            assert res.status_code == 200
        # 무거운 RT-DETR 모델 빌드는 clip 수와 무관하게 1회만 일어나야 한다
        assert build_calls["n"] == 1
    finally:
        rtdetr_loader._build_rtdetr_backend = orig  # type: ignore[assignment]
