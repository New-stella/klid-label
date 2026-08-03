"""SAM2 잔여 보안 이슈 회귀 가드 (security-reviewer CONDITIONAL PASS 후속).

- 이슈1 (HIGH, CWE-117): ``track_id`` 로그 인젝션 — CRLF 로 가짜 로그 라인 위조
- 이슈2 (MEDIUM, CWE-755): fallback 의 "예외 없음" 보증이 검증 우회 경로에서 실제로 깨짐
- 이슈3 (MEDIUM, CWE-20): NaN/Infinity 좌표가 400 이 아니라 200 + null 로 새어나감
- 이슈6 (LOW, CWE-770): 좌표 배열/track_id 상한 부재
- 이슈7 (LOW): 응답 polygon 하한 부재

방어는 2계층이다(defense in depth):
1) 스키마 — 값 도메인(유한성)·패턴·상한 위반은 400 VALIDATION_ERROR 로 앞단 차단
2) fallback 함수 — 스키마를 우회한 호출(내부 직접 호출·``model_construct``)에도 예외 금지
"""

from __future__ import annotations

import asyncio
import base64
import io
import logging
import math

import pytest
from fastapi.testclient import TestClient

from app.config import reload_settings
from app.models import sam2_loader
from app.routers import sam2 as sam2_router
from app.schemas import Sam2SegmentRequest, Sam2TrackRequest

SAM2_LOGGER = "app.routers.sam2"

#: 로그 위조 실증 페이로드 — 개행 뒤에 감사 로그처럼 보이는 라인을 붙인다.
FORGED_TRACK_ID = "t-1\r\n[AUDIT] user=admin action=APPROVED_ALL"


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


def _polygon3() -> list[list[float]]:
    return [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0]]


class _RaisingPredictor:
    """predict 단계에서 폭발하는 fake — fallback 경로를 강제로 태운다."""

    def set_image(self, image) -> None:  # noqa: ANN001 — fake
        return None

    def predict(self, **kwargs):  # noqa: ANN003 — fake
        raise RuntimeError("boom")


class _OkPredictor:
    """정상 마스크를 돌려주는 fake."""

    def set_image(self, image) -> None:  # noqa: ANN001 — fake
        return None

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        import numpy as np

        mask = np.zeros((1, 64, 64), dtype=np.float32)
        mask[0, 10:40, 10:40] = 1.0
        return mask, np.array([0.9], dtype=np.float32), None


def _all_messages(caplog: pytest.LogCaptureFixture) -> str:
    return "\n".join(r.getMessage() for r in caplog.records)


# ────────────────────────────────────────────────────────────────────
# 이슈1 — track_id 로그 인젝션 (CWE-117)
# ────────────────────────────────────────────────────────────────────

def test_track_id가_패턴을_벗어나면_400을_반환한다(client: TestClient) -> None:
    """개행이 섞인 track_id 는 라우터에 도달하기 전에 400 으로 차단된다."""
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": FORGED_TRACK_ID,
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": _polygon3(),
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_track_id가_상한을_초과하면_400을_반환한다(client: TestClient) -> None:
    """BE TRACK_ID VARCHAR(64) 정합 — 65자는 거부한다 (CWE-770)."""
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t" * 65,
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": _polygon3(),
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_track_id에_개행문자가_포함되면_로그가_위조되지_않는다(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """실추론 실패 로그(CWE-117) — 스키마 우회 경로에서도 개행이 로그로 나가지 않는다."""
    req = Sam2TrackRequest.model_construct(
        track_id=FORGED_TRACK_ID,
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=_polygon3(),
    )

    with caplog.at_level(logging.DEBUG, logger=SAM2_LOGGER):
        resp = sam2_router._real_track(_RaisingPredictor(), req)

    assert resp.mock is True
    messages = _all_messages(caplog)
    assert "\r" not in messages
    # 로그 라인 자체가 위조되지 않았는지 — 감사 로그처럼 보이는 라인이 독립 줄로 생기면 안 된다.
    for line in messages.splitlines():
        assert not line.startswith("[AUDIT]")


def test_mock_track_로그에도_개행문자가_정제된다(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """mock 분기 로그(라우터 진입부)도 동일하게 정제된다."""
    req = Sam2TrackRequest.model_construct(
        track_id=FORGED_TRACK_ID,
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=_polygon3(),
    )

    with caplog.at_level(logging.DEBUG, logger=SAM2_LOGGER):
        asyncio.run(sam2_router.track(req))

    messages = _all_messages(caplog)
    assert "\r" not in messages
    for line in messages.splitlines():
        assert not line.startswith("[AUDIT]")


def test_safe_헬퍼는_제어문자를_제거하고_길이를_제한한다() -> None:
    assert "\r" not in sam2_router._safe("a\r\nb")
    assert "\n" not in sam2_router._safe("a\r\nb")
    assert "\x00" not in sam2_router._safe("a\x00b")
    assert len(sam2_router._safe("x" * 500)) <= 64


def test_track_id_정상값은_기존과_동일하게_동작한다(client: TestClient) -> None:
    """회귀 — 허용 문자만 쓴 정상 track_id 는 그대로 200."""
    img = _make_png_b64()
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "track_A.1:2-3",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": prev_polygon,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["track_id"] == "track_A.1:2-3"
    assert len(body["polygon"]) == len(prev_polygon)


# ────────────────────────────────────────────────────────────────────
# 이슈2 — fallback 은 어떤 입력에도 예외를 던지지 않는다 (CWE-755)
# ────────────────────────────────────────────────────────────────────

def _track_req(prev_polygon) -> Sam2TrackRequest:  # noqa: ANN001 — 검증 우회 입력
    return Sam2TrackRequest.model_construct(
        track_id="t-guard",
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=prev_polygon,
    )


@pytest.mark.parametrize(
    ("case", "prev_polygon"),
    [
        ("비순회", [5, 6, 7]),
        ("None", [None, None, None]),
        ("dict", [{"x": 1}, {"x": 2}, {"x": 3}]),
        ("문자열", ["ab", "cd", "ef"]),
        ("혼합", [[1.0, 2.0], [3.0, 4.0], None]),
        ("과대수치", [[10 ** 400, 1], [2, 3], [4, 5]]),
        ("NaN", [[float("nan"), 1.0], [2.0, 3.0], [4.0, 5.0]]),
    ],
)
def test_real_track에_비정상_원소가_있어도_예외없이_fallback한다(case, prev_polygon) -> None:
    """security-reviewer 실증 반례 고정 — 검증 우회 경로에서도 500 이 나지 않는다."""
    resp = sam2_router._real_track(_OkPredictor(), _track_req(prev_polygon))

    assert resp.track_id == "t-guard"
    assert 0.0 <= resp.score <= 1.0
    for pt in resp.polygon:
        assert len(pt) == 2
        assert all(math.isfinite(float(v)) for v in pt)


@pytest.mark.parametrize(
    ("case", "points"),
    [
        ("None", [None]),
        ("문자열", ["ab"]),
        ("dict", [{"x": 1}]),
        ("NaN", [[float("nan"), float("nan")]]),
        ("과대수치", [[10 ** 400, 1]]),
    ],
)
def test_mock_segment에_비정상_원소가_있어도_예외없이_처리한다(case, points) -> None:
    """마지막 방어선인 mock_segment 는 어떤 입력에도 유효한 폴리곤을 돌려준다."""
    req = Sam2SegmentRequest.model_construct(
        image_b64=_make_png_b64(), points=points, box=None
    )

    resp = sam2_router._mock_segment(64, 64, req, "empty_mask")

    assert resp.mock is True
    assert len(resp.polygon) >= 3
    for pt in resp.polygon:
        assert len(pt) == 2
        assert all(math.isfinite(float(v)) for v in pt)


def test_mock_segment는_비정상_box에도_예외없이_처리한다() -> None:
    req = Sam2SegmentRequest.model_construct(
        image_b64=_make_png_b64(), points=None, box=["a", "b", "c", "d"]
    )

    resp = sam2_router._mock_segment(64, 64, req, "empty_mask")

    assert len(resp.polygon) >= 3
    for pt in resp.polygon:
        assert all(math.isfinite(float(v)) for v in pt)


def test_sanitize_polygon은_비정상_원소를_버리고_유한좌표만_남긴다() -> None:
    out = sam2_router._sanitize_polygon(
        [[1.0, 2.0], None, "ab", {"x": 1}, [float("inf"), 1.0], [3, 4], 7]
    )
    assert out == [[1.0, 2.0], [3.0, 4.0]]
    assert sam2_router._sanitize_polygon(None) == []
    assert sam2_router._sanitize_polygon(123) == []


def test_sanitize_polygon은_진리값_판정이_터지는_입력도_처리한다() -> None:
    """``value or []`` 패턴 회귀 가드 — numpy 배열처럼 ``__bool__`` 이 예외를 던지는 입력."""
    import numpy as np

    out = sam2_router._sanitize_polygon(np.array([[1.0, 2.0], [3.0, 4.0]]))
    assert out == [[1.0, 2.0], [3.0, 4.0]]
    assert sam2_router._echo_polygon(np.array([[1.0, 2.0]])) == [[1.0, 2.0]]
    assert sam2_router._sanitize_coords(np.array([1.0, 2.0, 3.0, 4.0])) == [1.0, 2.0, 3.0, 4.0]


def test_polygon_bbox는_과대수치_원소에도_예외없이_동작한다() -> None:
    """OverflowError(float(10**400)) 도 graceful — None 또는 유한 bbox."""
    bbox = sam2_router._polygon_bbox([[10 ** 400, 1], [2, 3], [4, 5]])
    assert bbox is None or all(math.isfinite(v) for v in bbox)
    assert sam2_router._polygon_bbox([None, None]) is None


# ────────────────────────────────────────────────────────────────────
# 이슈3 — NaN/Infinity 좌표는 400 (CWE-20)
# ────────────────────────────────────────────────────────────────────

def _post_raw(client: TestClient, path: str, body: str):
    """NaN/Infinity 는 httpx JSON 인코더가 거부하므로 raw 본문으로 전송한다.

    실제 공격자도 이렇게 보낸다 — Python ``json.loads`` 는 기본적으로 이 토큰을 받아들이므로
    스키마가 값 도메인을 강제하지 않으면 그대로 서버 내부까지 도달한다.
    """
    return client.post(path, content=body, headers={"content-type": "application/json"})


def test_points에_NaN이_포함되면_400을_반환한다(client: TestClient) -> None:
    res = _post_raw(
        client,
        "/infer/sam2/segment",
        '{"image_b64": "%s", "points": [[NaN, 1.0]]}' % _make_png_b64(),
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_points에_Infinity가_포함되면_400을_반환한다(client: TestClient) -> None:
    res = _post_raw(
        client,
        "/infer/sam2/segment",
        '{"image_b64": "%s", "points": [[Infinity, 1.0]]}' % _make_png_b64(),
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_box에_NaN이_포함되면_400을_반환한다(client: TestClient) -> None:
    res = _post_raw(
        client,
        "/infer/sam2/segment",
        '{"image_b64": "%s", "box": [NaN, 1.0, 2.0, 3.0]}' % _make_png_b64(),
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_prev_polygon에_NaN이_포함되면_400을_반환한다(client: TestClient) -> None:
    img = _make_png_b64()
    res = _post_raw(
        client,
        "/infer/sam2/track",
        '{"track_id": "t-nan", "prev_image_b64": "%s", "next_image_b64": "%s",'
        ' "prev_polygon": [[NaN, 1.0], [2.0, 3.0], [4.0, 5.0]]}' % (img, img),
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


# ────────────────────────────────────────────────────────────────────
# 이슈6 — 좌표 배열 상한 (CWE-770)
# ────────────────────────────────────────────────────────────────────

def test_points가_100개를_초과하면_400을_반환한다(client: TestClient) -> None:
    res = client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": _make_png_b64(),
            "points": [[float(i), float(i)] for i in range(101)],
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_prev_polygon이_1000개를_초과하면_400을_반환한다(client: TestClient) -> None:
    img = _make_png_b64()
    res = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "t-big",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": [[float(i % 60), float(i % 60)] for i in range(1001)],
        },
    )
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_points_100개_경계값은_200으로_통과한다(client: TestClient) -> None:
    """회귀 — BE 상한(@Size(max=100))과 동일한 경계는 허용."""
    res = client.post(
        "/infer/sam2/segment",
        json={
            "image_b64": _make_png_b64(),
            "points": [[float(i % 60), float(i % 60)] for i in range(100)],
        },
    )
    assert res.status_code == 200


# ────────────────────────────────────────────────────────────────────
# 이슈7 — 응답 polygon 하한
# ────────────────────────────────────────────────────────────────────

def test_segment_응답_polygon은_빈_배열을_허용하지_않는다() -> None:
    from pydantic import ValidationError

    from app.schemas import Sam2SegmentResponse

    with pytest.raises(ValidationError):
        Sam2SegmentResponse(polygon=[], score=0.5)
