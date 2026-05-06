"""SAM2 라우터 테스트 (MOCK 모드)."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_sam2_segment_포인트_입력시_polygon_반환(small_png_b64: str) -> None:
    res = client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": small_png_b64,
            "points": [[32.0, 32.0]],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert "polygon" in body
    assert isinstance(body["polygon"], list)
    assert len(body["polygon"]) >= 3  # 최소 폴리곤
    for pt in body["polygon"]:
        assert len(pt) == 2
    assert 0.0 <= body["score"] <= 1.0


def test_sam2_segment_box_입력시_polygon_반환(small_png_b64: str) -> None:
    res = client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": small_png_b64,
            "box": [10.0, 10.0, 50.0, 50.0],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert len(body["polygon"]) >= 3


def test_sam2_track_연속_프레임_동일_트랙_ID_유지(small_png_b64: str) -> None:
    track_id = "track-001"
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": track_id,
            "prev_image_b64": small_png_b64,
            "next_image_b64": small_png_b64,
            "prev_polygon": prev_polygon,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["track_id"] == track_id
    assert isinstance(body["polygon"], list)
    assert len(body["polygon"]) == len(prev_polygon)
    assert 0.0 <= body["score"] <= 1.0
