"""VLM 라우터 테스트 (MOCK 모드, V1.7 객체 검증 한정)."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_vlm_verify_objects_YOLO_결과_검증_True_False_반환(small_png_b64: str) -> None:
    res = client.post(
        "/infer/vlm/verify-objects",
        json={
            "image_b64": small_png_b64,
            "objects": [
                {
                    "obj_id": "o1",
                    "expected_label": "person",  # known → verified=true
                    "bbox": [10.0, 10.0, 50.0, 50.0],
                },
                {
                    "obj_id": "o2",
                    "expected_label": "spaceship",  # unknown → verified=false
                    "bbox": [20.0, 20.0, 40.0, 40.0],
                },
            ],
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert "results" in body
    assert len(body["results"]) == 2

    by_id = {r["obj_id"]: r for r in body["results"]}
    assert by_id["o1"]["verified"] is True
    assert by_id["o1"]["expected_label"] == "person"
    assert by_id["o2"]["verified"] is False
    for r in body["results"]:
        assert 0.0 <= r["confidence"] <= 1.0


def test_vlm_verify_objects_빈_objects_배열_validation_error(small_png_b64: str) -> None:
    res = client.post(
        "/infer/vlm/verify-objects",
        json={"image_b64": small_png_b64, "objects": []},
    )
    assert res.status_code == 400


def test_vlm_router_video_meta_endpoint_removed() -> None:
    """Phase 1 (2026-05-19): 영상 단위 메타 추출은 외부 VLM 서비스로 이관.

    ai-server 측에는 객체 검증(/verify-objects) 라우트만 남아야 한다.
    영상 메타 추출 경로(/infer/vlm/meta, /infer/vlm/video-meta) 는 부재.
    """
    res_meta = client.post(
        "/infer/vlm/meta",
        json={"raw_sn": 1, "file_path": "/tmp/x.mp4"},
    )
    assert res_meta.status_code == 404

    res_video_meta = client.post(
        "/infer/vlm/video-meta",
        json={"raw_sn": 1, "file_path": "/tmp/x.mp4"},
    )
    assert res_video_meta.status_code == 404
