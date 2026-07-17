"""YOLO 오토라벨 클래스 필터 테스트 (Phase 4 — R3 AC3).

원하는 객체종류(클래스)만 선택해 검출한다. mock 모드(conftest AI_MOCK_MODE=true)에서
mock 응답은 person 박스를 반환하므로, classes 필터가 person 포함/제외 여부로 결정적으로 검증된다.

- classes 지정(해당 클래스만 통과) / None(전체) / 빈 리스트(전체 = 미필터, 설계 판단)
- predict / track 양쪽 경로 모두 필터 적용 (온라인 오토라벨은 track 경로 사용)
- 응답 스키마·HTTP 경로 불변 (AC5 회귀)
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from app.schemas import YoloRequest, YoloTrackRequest

client = TestClient(app)


# ────────────────────────────────────────────────────────────────────
# schema — classes 필드 (기본 None, 하위호환)
# ────────────────────────────────────────────────────────────────────

def test_yolo_request_classes_기본값은_None(small_png_b64: str) -> None:
    """classes 미전송 시 None — 하위호환(무회귀)."""
    req = YoloRequest(image_b64=small_png_b64)
    assert req.classes is None
    treq = YoloTrackRequest(image_b64=small_png_b64, clip_id="c", frame_index=0)
    assert treq.classes is None


def test_yolo_request_classes_과대_리스트_거부(small_png_b64: str) -> None:
    """입력 검증(CWE-20) — classes 리스트가 과대(>100)면 pydantic 검증 실패로 400."""
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "classes": [f"c{i}" for i in range(101)]},
    )
    assert res.status_code == 400


# ────────────────────────────────────────────────────────────────────
# predict — 클래스 필터
# ────────────────────────────────────────────────────────────────────

def test_yolo_classes_지정시_해당클래스만_반환(small_png_b64: str) -> None:
    """classes=["person"] → mock person 박스 통과. classes=["car"] → person 제외 → 빈 결과."""
    keep = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25, "classes": ["person"]},
    )
    assert keep.status_code == 200
    assert [d["label"] for d in keep.json()["detections"]] == ["person"]

    drop = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25, "classes": ["car"]},
    )
    assert drop.status_code == 200
    assert drop.json()["detections"] == []


def test_yolo_classes_None이면_전체_검출(small_png_b64: str) -> None:
    """classes 미전송(None) → 필터 미적용 → 전체(person 포함) 반환 (무회귀)."""
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    assert len(res.json()["detections"]) >= 1


def test_yolo_classes_빈리스트면_전체_검출(small_png_b64: str) -> None:
    """빈 리스트는 None 과 동일하게 '전체(미필터)'로 처리 (설계 판단 — 실수로 전부 제외되는 footgun 방지)."""
    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25, "classes": []},
    )
    assert res.status_code == 200
    assert len(res.json()["detections"]) >= 1


# ────────────────────────────────────────────────────────────────────
# track — 클래스 필터 (온라인 오토라벨 경로)
# ────────────────────────────────────────────────────────────────────

def test_yolo_track_classes_지정시_해당클래스만_반환(small_png_b64: str) -> None:
    """track 경로도 동일 필터. classes=["car"] → mock person 제외 → 빈 결과."""
    res = client.post(
        "/infer/yolo/track",
        json={
            "image_b64": small_png_b64,
            "clip_id": "vid-cf",
            "frame_index": 0,
            "conf_threshold": 0.25,
            "classes": ["car"],
        },
    )
    assert res.status_code == 200
    assert res.json()["detections"] == []


def test_yolo_track_classes_None이면_전체_검출(small_png_b64: str) -> None:
    res = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "vid-cf2", "frame_index": 0,
              "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    assert len(res.json()["detections"]) >= 1
