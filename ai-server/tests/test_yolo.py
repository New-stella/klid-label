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
    # Phase 2: track_id 필드 추가. predict 응답에선 항상 None
    assert set(det.keys()) == {"label", "points", "score", "track_id"}
    assert len(det["points"]) == 4
    assert 0.0 <= det["score"] <= 1.0
    assert det["track_id"] is None


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


def test_predict_imgsz_iou_을_수용(small_png_b64: str) -> None:
    """Phase 1: imgsz/iou 명시 전송 시 200 + detections 응답."""
    res = client.post(
        "/infer/yolo/predict",
        json={
            "image_b64": small_png_b64,
            "conf_threshold": 0.4,
            "imgsz": 960,
            "iou": 0.6,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body
    assert isinstance(body["detections"], list)


def test_predict_imgsz_iou_미전송시_기본값_사용(small_png_b64: str) -> None:
    """Phase 1: imgsz/iou 미전송 시에도 200 응답 — 기본값(1280/0.5) 적용."""
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64},
    )
    assert res.status_code == 200
    body = res.json()
    assert "detections" in body


def test_predict_conf_threshold_기본값은_0_4(small_png_b64: str) -> None:
    """Phase 1: conf_threshold 기본값이 0.4 로 변경되어 mock score(0.9)와 호환."""
    from app.schemas import YoloRequest

    req = YoloRequest(image_b64=small_png_b64)
    assert req.conf_threshold == 0.4
    assert req.imgsz == 1280
    assert req.iou == 0.5
