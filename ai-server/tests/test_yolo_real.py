"""
YOLOv8 실제 추론 통합 테스트.

weights/yolov8n.pt 가 없으면 자동 skip.
AI_MOCK_MODE=false 로 실행하므로 conftest autouse fixture를 재정의한다.
"""

from __future__ import annotations

import base64
import io
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

WEIGHTS_PATH = Path(__file__).parent.parent / "weights" / "yolov8n.pt"

pytestmark = pytest.mark.skipif(
    not WEIGHTS_PATH.exists(),
    reason="weights/yolov8n.pt 없음 — scripts/download-weights.sh 먼저 실행",
)


@pytest.fixture()
def real_model_client(monkeypatch):
    """mock mode 비활성화 + 모델 싱글톤 초기화 → 실제 추론 클라이언트."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(WEIGHTS_PATH))

    from app.config import reload_settings
    from app.models.yolo_loader import reset_yolo_model

    reload_settings()
    reset_yolo_model()

    from app.main import app

    client = TestClient(app)
    yield client

    # 정리
    reset_yolo_model()
    from app.config import reload_settings as _rs

    _rs()


def _make_jpeg_b64(width: int = 320, height: int = 240) -> str:
    """간단한 JPEG base64 이미지 생성 (사람 감지용으로는 blank이지만 파이프라인 검증 충분)."""
    from PIL import Image

    img = Image.new("RGB", (width, height), color=(200, 200, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def test_real_yolo_predict_blank_이미지_응답형식_정상(real_model_client):
    """빈 이미지로도 API 응답 포맷이 유효한지 확인 (감지 0건도 정상)."""
    res = real_model_client.post(
        "/infer/yolo/predict",
        json={"image_b64": _make_jpeg_b64(), "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body
    assert isinstance(body["detections"], list)
    for det in body["detections"]:
        assert {"label", "points", "score"} == set(det.keys())
        assert len(det["points"]) == 4
        assert 0.0 <= det["score"] <= 1.0


def test_real_yolo_predict_seed_이미지_감지(real_model_client):
    """실제 시드 이미지(person 카테고리)에서 객체 감지 시도."""
    seed_dirs = [
        Path(__file__).parent.parent.parent / "storage" / "raw" / "seed" / "9001",
        Path(__file__).parent.parent.parent / "storage" / "raw" / "seed" / "9007",
    ]

    seed_image = None
    for d in seed_dirs:
        img_path = d / "frame_1.jpg"
        if img_path.exists():
            seed_image = img_path
            break

    if seed_image is None:
        pytest.skip("시드 이미지 없음 — scripts/apply-seed-images.sh 먼저 실행")

    with open(seed_image, "rb") as f:
        image_b64 = base64.b64encode(f.read()).decode("ascii")

    res = real_model_client.post(
        "/infer/yolo/predict",
        json={"image_b64": image_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body

    detections = body["detections"]
    print(f"\n[real YOLO] {seed_image.parent.name}/frame_1.jpg → {len(detections)}건 감지")
    for det in detections:
        print(f"  {det['label']} score={det['score']:.2f} bbox={[round(p) for p in det['points']]}")
