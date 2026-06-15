"""RT-DETR 실제 추론 통합 테스트.

transformers/supervision 미설치 또는 모델 가중치 미존재 시 자동 skip.
(test_yolo_real.py 의 skipif 패턴 동일 적용)
"""

from __future__ import annotations

import base64
import importlib.util
import io

import pytest
from fastapi.testclient import TestClient

_HAS_TRANSFORMERS = importlib.util.find_spec("transformers") is not None
_HAS_SUPERVISION = importlib.util.find_spec("supervision") is not None

pytestmark = pytest.mark.skipif(
    not (_HAS_TRANSFORMERS and _HAS_SUPERVISION),
    reason="transformers/supervision 미설치 — RT-DETR 실제 추론 skip",
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
