"""SAM2 좌표 입력 원소 길이 검증 + fallback 방어 테스트 (G-ISSUE-41 / G-ISSUE-42).

두 결함 모두 "좌표 리스트의 *원소 개수*" 를 아무도 검증하지 않아 발생했다.

- G-ISSUE-41: ``points=[[5]]`` → 실추론 IndexError → mock fallback(``_mock_segment``)이
  같은 이유로 재폭발 → 500
- G-ISSUE-42: ``prev_polygon=[[1],[2],[3]]`` → bbox 유도가 try 블록 *밖* 이라
  어떤 fallback 도 타지 못하고 500

방어는 2계층이다(defense in depth):
1) 스키마 — 원소는 정확히 2개([x, y]) → 위반 시 400 VALIDATION_ERROR (CWE-20)
2) fallback 함수 — 스키마를 우회한 호출(내부 직접 호출)에도 절대 예외를 던지지 않음 (CWE-755)
"""

from __future__ import annotations

import base64
import io

import pytest
from fastapi.testclient import TestClient

from app.config import reload_settings
from app.models import sam2_loader
from app.routers import sam2 as sam2_router
from app.schemas import Sam2SegmentRequest, Sam2TrackRequest


@pytest.fixture(autouse=True)
def _reset_state():
    sam2_loader.reset_sam2_model()
    sam2_router.reset_mock_warn_flag()
    yield
    sam2_loader.reset_sam2_model()
    sam2_router.reset_mock_warn_flag()
    reload_settings()


def _make_png_b64(width: int = 64, height: int = 64) -> str:
    from PIL import Image

    img = Image.new("RGB", (width, height), color=(128, 128, 128))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


@pytest.fixture
def client() -> TestClient:
    from app.main import app

    return TestClient(app)


# ────────────────────────────────────────────────────────────────────
# 1계층 — 스키마 검증 (400 VALIDATION_ERROR)
# ────────────────────────────────────────────────────────────────────

def test_points_원소가_1개면_400_검증오류(client: TestClient) -> None:
    """G-ISSUE-41: points=[[5]] 는 500 이 아니라 400 으로 앞단 차단."""
    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": _make_png_b64(), "points": [[5.0]]},
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_points_원소가_3개면_400_검증오류(client: TestClient) -> None:
    """좌표는 정확히 [x, y] 2개 — 3개도 거부한다."""
    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": _make_png_b64(), "points": [[1.0, 2.0, 3.0]]},
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_prev_polygon_원소가_1개면_400_검증오류(client: TestClient) -> None:
    """G-ISSUE-42: prev_polygon=[[1],[2],[3]] 는 500 이 아니라 400 으로 앞단 차단."""
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t-bad",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": [[1.0], [2.0], [3.0]],
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_prev_polygon_원소가_3개이상이면_400_검증오류(client: TestClient) -> None:
    """폴리곤 좌표도 정확히 [x, y] 2개 — 3개는 거부한다."""
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t-bad3",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": [[1.0, 2.0, 3.0], [2.0, 3.0, 4.0], [3.0, 4.0, 5.0]],
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


# ────────────────────────────────────────────────────────────────────
# 2계층 — fallback 자체 방어 (스키마 우회 경로에서도 500 금지)
# ────────────────────────────────────────────────────────────────────

def test_mock_segment는_원소부족_points에도_예외없이_폴리곤_반환() -> None:
    """G-ISSUE-41 핵심: fallback 은 어떤 입력에도 예외를 던지지 않는다 (CWE-755).

    model_construct 로 스키마 검증을 우회해 fallback 함수 자체의 방어를 검증한다.
    """
    req = Sam2SegmentRequest.model_construct(
        image_b64=_make_png_b64(), points=[[5.0]], box=None
    )

    resp = sam2_router._mock_segment(64, 64, req, "empty_mask")

    assert resp.mock is True
    assert len(resp.polygon) >= 3
    for pt in resp.polygon:
        assert len(pt) == 2
    assert 0.0 <= resp.score <= 1.0


def test_mock_segment는_빈_points_원소에도_예외없이_폴리곤_반환() -> None:
    """원소가 0개인 극단 입력도 중앙 기본 폴리곤으로 처리."""
    req = Sam2SegmentRequest.model_construct(
        image_b64=_make_png_b64(), points=[[]], box=None
    )

    resp = sam2_router._mock_segment(64, 64, req, "empty_mask")

    assert resp.mock is True
    assert len(resp.polygon) >= 3


def test_real_track은_원소부족_prev_polygon에도_이전폴리곤_fallback() -> None:
    """G-ISSUE-42 핵심: bbox 유도 실패도 보호 구간 안이라 500 대신 prev polygon fallback."""
    prev_polygon = [[1.0], [2.0], [3.0]]
    req = Sam2TrackRequest.model_construct(
        track_id="t-bbox",
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=prev_polygon,
    )

    resp = sam2_router._real_track(_AlwaysOkPredictor(), req)

    assert resp.track_id == "t-bbox"
    assert resp.mock is True
    assert resp.source == "mock"
    assert resp.mock_reason == "empty_mask"
    assert len(resp.polygon) == len(prev_polygon)
    assert 0.0 <= resp.score <= 1.0


class _AlwaysOkPredictor:
    """정상 마스크를 돌려주는 fake — bbox 유도 실패가 predict 이전 단계임을 분리 검증."""

    def set_image(self, image) -> None:  # noqa: ANN001 — fake
        return None

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        import numpy as np

        mask = np.zeros((1, 64, 64), dtype=np.float32)
        mask[0, 10:40, 10:40] = 1.0
        return mask, np.array([0.9], dtype=np.float32), None


# ────────────────────────────────────────────────────────────────────
# 회귀 — 정상 입력은 기존과 동일하게 동작
# ────────────────────────────────────────────────────────────────────

def test_정상_2개좌표_segment_요청은_200_mock(client: TestClient) -> None:
    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": _make_png_b64(), "points": [[32.0, 32.0]]},
    )
    assert res.status_code == 200
    body = res.json()
    assert len(body["polygon"]) >= 3
    for pt in body["polygon"]:
        assert len(pt) == 2


def test_정상_polygon_track_요청은_200(client: TestClient) -> None:
    img = _make_png_b64()
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t-ok",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": prev_polygon,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["track_id"] == "t-ok"
    assert len(body["polygon"]) == len(prev_polygon)


def test_퇴화_폴리곤_track_요청은_200(client: TestClient) -> None:
    """모든 점이 동일한 퇴화 폴리곤도 기존과 동일하게 200 (회귀)."""
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t-degenerate",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": [[5.0, 5.0], [5.0, 5.0], [5.0, 5.0]],
        },
    )
    assert res.status_code == 200
    assert res.json()["track_id"] == "t-degenerate"
