"""
SAM2 실제 추론 통합 테스트.

weights/sam2_t.pt 가 없으면 자동 skip.
AI_MOCK_MODE=false 로 실행하므로 conftest autouse fixture를 재정의한다.
"""

from __future__ import annotations

import base64
import io
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

WEIGHTS_PATH = Path(__file__).parent.parent / "weights" / "sam2_t.pt"

pytestmark = pytest.mark.skipif(
    not WEIGHTS_PATH.exists(),
    reason="weights/sam2_t.pt 없음 — scripts/download-sam2-weights.sh 먼저 실행",
)


@pytest.fixture()
def real_model_client(monkeypatch):
    """mock mode 비활성화 + 모델 싱글톤 초기화 → 실제 추론 클라이언트."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("SAM2_WEIGHTS_PATH", str(WEIGHTS_PATH))

    from app.config import reload_settings
    from app.models.sam2_loader import reset_sam2_model

    reload_settings()
    reset_sam2_model()

    from app.main import app

    client = TestClient(app)
    yield client

    reset_sam2_model()
    from app.config import reload_settings as _rs

    _rs()


def _make_jpeg_b64(width: int = 320, height: int = 240) -> str:
    """테스트용 JPEG base64 이미지 생성."""
    from PIL import Image

    img = Image.new("RGB", (width, height), color=(150, 180, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def test_real_sam2_segment_포인트_입력_응답형식_정상(real_model_client):
    """포인트 프롬프트로 segment 호출 시 응답 형식 검증."""
    image_b64 = _make_jpeg_b64()
    res = real_model_client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": image_b64,
            "points": [[160.0, 120.0]],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert "polygon" in body
    assert isinstance(body["polygon"], list)
    assert len(body["polygon"]) >= 3
    for pt in body["polygon"]:
        assert len(pt) == 2
    assert 0.0 <= body["score"] <= 1.0
    print(f"\n[real SAM2] segment points → polygon_pts={len(body['polygon'])} score={body['score']:.3f}")


def test_real_sam2_segment_박스_입력_응답형식_정상(real_model_client):
    """박스 프롬프트로 segment 호출 시 응답 형식 검증."""
    image_b64 = _make_jpeg_b64()
    res = real_model_client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": image_b64,
            "box": [60.0, 40.0, 260.0, 200.0],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert len(body["polygon"]) >= 3
    assert 0.0 <= body["score"] <= 1.0
    print(f"\n[real SAM2] segment box → polygon_pts={len(body['polygon'])} score={body['score']:.3f}")


def test_real_sam2_track_prev_polygon_전파(real_model_client):
    """track 호출 시 동일 track_id + 폴리곤 반환 검증."""
    image_b64 = _make_jpeg_b64()
    track_id = "real-track-001"
    prev_polygon = [[60.0, 40.0], [260.0, 40.0], [260.0, 200.0], [60.0, 200.0]]
    res = real_model_client.post(
        "/infer/sam2/track",
        json={
            "track_id": track_id,
            "prev_image_b64": image_b64,
            "next_image_b64": image_b64,
            "prev_polygon": prev_polygon,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["track_id"] == track_id
    assert isinstance(body["polygon"], list)
    assert len(body["polygon"]) >= 3
    assert 0.0 <= body["score"] <= 1.0
    print(f"\n[real SAM2] track → polygon_pts={len(body['polygon'])} score={body['score']:.3f}")
