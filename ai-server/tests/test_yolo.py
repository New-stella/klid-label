"""YOLO 라우터 테스트 (MOCK 모드)."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_yolo_predict_정상_이미지_입력시_detections_반환(small_png_b64: str) -> None:
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body
    assert isinstance(body["detections"], list)
    # mock 응답은 기본적으로 1건 반환
    assert len(body["detections"]) >= 1
    det = body["detections"][0]
    assert set(det.keys()) == {"label", "points", "score"}
    assert len(det["points"]) == 4
    assert 0.0 <= det["score"] <= 1.0


def test_yolo_predict_invalid_base64_입력시_400() -> None:
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": "!!! not base64 !!!"},
    )
    assert res.status_code == 400
    body = res.json()
    assert body["error_code"] in {"INVALID_IMAGE", "VALIDATION_ERROR"}


def test_yolo_predict_image_size_초과시_413(large_png_b64: str) -> None:
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": large_png_b64},
    )
    assert res.status_code == 413
    body = res.json()
    assert body["error_code"] == "IMAGE_TOO_LARGE"


def test_yolo_predict_conf_threshold_상한_초과시_validation_error(small_png_b64: str) -> None:
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 1.5},
    )
    assert res.status_code == 400
