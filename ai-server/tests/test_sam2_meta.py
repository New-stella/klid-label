"""SAM2 Meta 공식 sam2(Apache-2.0) 마이그레이션 테스트 (Phase 3).

ultralytics SAM → Meta `SAM2ImagePredictor` 로 교체하면서:
- polygon 응답 계약 불변(AC5)
- ultralytics 잔존 0건(grep, AC2)
- mask→polygon(cv2.findContours) 단위 변환 검증
- 로드 실패 graceful(load_failed → None, AC6)

sam2/torch 는 본 환경에 미설치이므로 fake predictor monkeypatch + 합성 마스크로
deps 없이 검증한다(Phase 4 에서 실제 설치).
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import numpy as np
import pytest

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
    """단색 PNG base64 (image_utils 검증 통과용)."""
    import base64
    import io

    from PIL import Image

    img = Image.new("RGB", (width, height), color=(128, 128, 128))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _square_mask(h: int = 64, w: int = 64, box=(10, 10, 40, 40)) -> np.ndarray:
    """지정 사각 영역이 1인 합성 binary mask (H, W)."""
    x1, y1, x2, y2 = box
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[y1:y2, x1:x2] = 1
    return mask


class _FakePredictor:
    """Meta SAM2ImagePredictor 의 set_image/predict 계약을 흉내내는 fake.

    predict 는 (masks(N,H,W), scores(N,), logits) 를 반환한다.
    """

    def __init__(self, mask: np.ndarray | None = None, score: float = 0.88) -> None:
        self._mask = mask if mask is not None else _square_mask()
        self._score = score
        self.set_image_called = False
        self.last_box = None
        self.last_point_coords = None
        self.last_point_labels = None

    def set_image(self, image: np.ndarray) -> None:
        # 입력은 numpy RGB 여야 한다 (계약 방어)
        assert isinstance(image, np.ndarray)
        self.set_image_called = True

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        self.last_box = box
        self.last_point_coords = point_coords
        self.last_point_labels = point_labels
        masks = np.array([self._mask], dtype=np.float32)  # (1, H, W)
        scores = np.array([self._score], dtype=np.float32)  # (1,)
        return masks, scores, None


class _EmptyPredictor(_FakePredictor):
    """마스크가 전부 0 — contour 없음 → mock fallback 유도."""

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        self.last_box = box
        empty = np.zeros((1, 64, 64), dtype=np.float32)
        scores = np.array([0.1], dtype=np.float32)
        return empty, scores, None


class _RaisingPredictor(_FakePredictor):
    """predict 가 예외를 던지는 fake — _predict_masks 예외 경로 검증용."""

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        self.last_box = box
        raise RuntimeError("predict boom")


class _NegativeScorePredictor(_FakePredictor):
    """음수 score 를 반환하는 fake — score 하한 clamp 검증용."""

    def predict(self, point_coords=None, point_labels=None, box=None, multimask_output=False):
        self.last_box = box
        masks = np.array([self._mask], dtype=np.float32)
        scores = np.array([-0.5], dtype=np.float32)
        return masks, scores, None


# ────────────────────────────────────────────────────────────────────
# mask → polygon 변환 (cv2.findContours)
# ────────────────────────────────────────────────────────────────────

def test_mask가_cv2_findContours로_polygon으로_변환됨() -> None:
    """합성 사각 binary mask → 외곽 polygon 반환 + score 동반."""
    mask = _square_mask(box=(10, 10, 40, 40))
    result = sam2_router._mask_to_polygon(mask)
    assert result is not None
    polygon, area = result
    assert isinstance(polygon, list)
    assert len(polygon) >= 3
    for pt in polygon:
        assert len(pt) == 2
        assert isinstance(pt[0], float)
        assert isinstance(pt[1], float)
    # 사각 윤곽 → 좌표가 box(10..40) 범위 안
    xs = [p[0] for p in polygon]
    ys = [p[1] for p in polygon]
    assert min(xs) >= 9 and max(xs) <= 41
    assert min(ys) >= 9 and max(ys) <= 41


def test_빈_마스크는_mask_to_polygon에서_None_반환() -> None:
    """contour 가 없으면 예외 대신 graceful None (보안 가드)."""
    empty = np.zeros((64, 64), dtype=np.uint8)
    assert sam2_router._mask_to_polygon(empty) is None


# ────────────────────────────────────────────────────────────────────
# _real_segment — fake predictor monkeypatch
# ────────────────────────────────────────────────────────────────────

def test_SAM2_box_프롬프트로_polygon_반환() -> None:
    """box 프롬프트 → predict(box=...) 호출 + 사각 윤곽 polygon 반환."""
    predictor = _FakePredictor(mask=_square_mask(box=(12, 12, 38, 38)), score=0.91)
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), box=[12.0, 12.0, 38.0, 38.0])

    resp = sam2_router._real_segment(predictor, req)

    assert predictor.set_image_called
    assert predictor.last_box is not None  # box 프롬프트 경로
    assert resp.mock is False
    assert resp.source == "model"
    assert resp.mock_reason is None
    assert len(resp.polygon) >= 3
    assert 0.0 <= resp.score <= 1.0


def test_SAM2_point_프롬프트로_polygon_반환() -> None:
    """point 프롬프트 → predict(point_coords/point_labels=...) 호출."""
    predictor = _FakePredictor(score=0.77)
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), points=[[32.0, 32.0]])

    resp = sam2_router._real_segment(predictor, req)

    assert predictor.set_image_called
    assert predictor.last_point_coords is not None
    assert predictor.last_point_labels is not None
    assert resp.mock is False
    assert len(resp.polygon) >= 3
    assert resp.score == pytest.approx(0.77, abs=1e-3)


def test_마스크_비었을때_segment_mock_polygon_fallback() -> None:
    """predict 가 빈 마스크면 contour 없음 → mock fallback(empty_mask)."""
    predictor = _EmptyPredictor()
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), box=[10.0, 10.0, 40.0, 40.0])

    resp = sam2_router._real_segment(predictor, req)

    assert resp.mock is True
    assert resp.source == "mock"
    assert resp.mock_reason == "empty_mask"
    assert len(resp.polygon) >= 3  # mock 사각 폴리곤


def test_score가_0_1로_clamp됨() -> None:
    """모델 score > 1.0 이어도 1.0 으로 clamp."""
    predictor = _FakePredictor(score=1.7)
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), box=[10.0, 10.0, 40.0, 40.0])
    resp = sam2_router._real_segment(predictor, req)
    assert resp.score == pytest.approx(1.0)


def test_음수_score는_0으로_clamp됨() -> None:
    """이슈3: SAM2 score 가 음수여도 0.0 으로 하한 clamp (pydantic ge=0 위반 방지)."""
    predictor = _NegativeScorePredictor(mask=_square_mask(box=(10, 10, 40, 40)))
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), box=[10.0, 10.0, 40.0, 40.0])
    resp = sam2_router._real_segment(predictor, req)
    assert resp.score >= 0.0


def test_predict가_예외를_던지면_segment가_mock_fallback() -> None:
    """이슈1: predict 예외 시 NameError/500 대신 mock fallback (graceful)."""
    predictor = _RaisingPredictor()
    req = Sam2SegmentRequest(image_b64=_make_png_b64(), box=[10.0, 10.0, 40.0, 40.0])

    resp = sam2_router._real_segment(predictor, req)

    assert resp.mock is True
    assert resp.source == "mock"
    assert len(resp.polygon) >= 3
    assert 0.0 <= resp.score <= 1.0


def test_predict가_예외를_던지면_track이_이전폴리곤_반환() -> None:
    """이슈1: track 의 predict 예외 시 이전 폴리곤을 mock 으로 반환 (graceful)."""
    predictor = _RaisingPredictor()
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    req = Sam2TrackRequest(
        track_id="t-err",
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=prev_polygon,
    )

    resp = sam2_router._real_track(predictor, req)

    assert resp.track_id == "t-err"
    assert resp.mock is True
    assert resp.source == "mock"
    assert len(resp.polygon) == len(prev_polygon)
    assert 0.0 <= resp.score <= 1.0


# ────────────────────────────────────────────────────────────────────
# _real_track — 이전 폴리곤 bbox → 다음 프레임 세그멘테이션
# ────────────────────────────────────────────────────────────────────

def test_SAM2_track_이전폴리곤_bbox로_다음프레임_세그멘테이션() -> None:
    """prev_polygon min/max → bbox box 프롬프트로 predict 호출, 동일 track_id 유지."""
    predictor = _FakePredictor(mask=_square_mask(box=(10, 10, 50, 50)), score=0.84)
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    req = Sam2TrackRequest(
        track_id="t-1",
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=prev_polygon,
    )

    resp = sam2_router._real_track(predictor, req)

    assert predictor.set_image_called
    assert predictor.last_box is not None  # bbox 프롬프트
    assert resp.track_id == "t-1"
    assert resp.mock is False
    assert resp.source == "model"
    assert len(resp.polygon) >= 3
    assert 0.0 <= resp.score <= 1.0


def test_track_마스크_비면_이전폴리곤_반환() -> None:
    """다음 프레임 마스크가 비면 이전 폴리곤을 mock 으로 그대로 반환."""
    predictor = _EmptyPredictor()
    prev_polygon = [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]]
    req = Sam2TrackRequest(
        track_id="t-2",
        prev_image_b64=_make_png_b64(),
        next_image_b64=_make_png_b64(),
        prev_polygon=prev_polygon,
    )

    resp = sam2_router._real_track(predictor, req)

    assert resp.track_id == "t-2"
    assert resp.mock is True
    assert resp.mock_reason == "empty_mask"
    assert len(resp.polygon) == len(prev_polygon)


# ────────────────────────────────────────────────────────────────────
# 로더 — ultralytics 제거 + load_failed graceful
# ────────────────────────────────────────────────────────────────────

def test_sam2_loader가_ultralytics를_import하지_않음() -> None:
    """AC2: sam2_loader.py / routers/sam2.py 소스에 ultralytics 잔존 0건."""
    base = Path(sam2_loader.__file__).parent.parent
    loader_src = (base / "models" / "sam2_loader.py").read_text(encoding="utf-8")
    router_src = (base / "routers" / "sam2.py").read_text(encoding="utf-8")
    assert "ultralytics" not in loader_src
    assert "ultralytics" not in router_src


def test_mock_모드에서_get_sam2_model_None_env_mock(monkeypatch) -> None:
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    reload_settings()
    sam2_loader.reset_sam2_model()
    assert sam2_loader.get_sam2_model() is None


def test_load_failed시_get_sam2_model_None_반환(monkeypatch) -> None:
    """AC6: sam2 import 실패(미설치) → 크래시 금지 → None graceful."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()

    # sam2 패키지 import 자체를 ImportError 로 강제
    monkeypatch.setitem(sys.modules, "sam2", None)
    monkeypatch.setitem(sys.modules, "sam2.sam2_image_predictor", None)

    assert sam2_loader.get_sam2_model() is None


def test_정상_predictor_로드시_싱글톤(monkeypatch) -> None:
    """from_pretrained 성공 → predictor 싱글톤, 1회만 빌드."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()

    built = {"n": 0}

    class _Fake:
        @classmethod
        def from_pretrained(cls, model_id, **kwargs):
            built["n"] += 1
            built["model_id"] = model_id
            built["device"] = kwargs.get("device")
            return object()

    import types as _types

    fake_mod = _types.ModuleType("sam2.sam2_image_predictor")
    fake_mod.SAM2ImagePredictor = _Fake  # type: ignore[attr-defined]
    fake_pkg = _types.ModuleType("sam2")
    monkeypatch.setitem(sys.modules, "sam2", fake_pkg)
    monkeypatch.setitem(sys.modules, "sam2.sam2_image_predictor", fake_mod)

    m1 = sam2_loader.get_sam2_model()
    m2 = sam2_loader.get_sam2_model()
    assert m1 is not None
    assert m1 is m2
    assert built["n"] == 1  # 싱글톤 — 1회만 from_pretrained
    # 설정값 model_id 사용 (사용자 입력 reflection 아님)
    assert built["model_id"] == "facebook/sam2-hiera-tiny"


@pytest.mark.parametrize("ai_device", ["cpu", "cuda"])
def test_get_sam2_model이_from_pretrained에_ai_device를_전달함(monkeypatch, ai_device) -> None:
    """버그수정: from_pretrained 가 device 기본 'cuda' 로 동작하므로 CPU 머신에서
    Assertion(Torch not compiled with CUDA enabled) → mock 폴백된다.
    settings.ai_device 를 명시 전달해 CPU 배포에서도 실모델이 로드되어야 한다.
    """
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("AI_DEVICE", ai_device)
    reload_settings()
    sam2_loader.reset_sam2_model()

    captured = {}

    class _Fake:
        @classmethod
        def from_pretrained(cls, model_id, **kwargs):
            captured["model_id"] = model_id
            captured["device"] = kwargs.get("device")
            return object()

    import types as _types

    fake_mod = _types.ModuleType("sam2.sam2_image_predictor")
    fake_mod.SAM2ImagePredictor = _Fake  # type: ignore[attr-defined]
    fake_pkg = _types.ModuleType("sam2")
    monkeypatch.setitem(sys.modules, "sam2", fake_pkg)
    monkeypatch.setitem(sys.modules, "sam2.sam2_image_predictor", fake_mod)

    model = sam2_loader.get_sam2_model()
    assert model is not None  # 실모델 로드 (mock 폴백 아님)
    assert sam2_loader.get_sam2_mock_reason() is None
    # device kwarg 가 설정값으로 전달됨 (기본 cuda 강제 방지)
    assert captured["device"] == ai_device


# ────────────────────────────────────────────────────────────────────
# 계약 유지 (라우터 mock 모드 end-to-end)
# ────────────────────────────────────────────────────────────────────

def test_mock_모드에서_segment_track_정상_polygon_응답(monkeypatch) -> None:
    """AC5: mock 모드에서도 segment/track polygon 응답 스키마 불변."""
    from fastapi.testclient import TestClient

    monkeypatch.setenv("AI_MOCK_MODE", "true")
    reload_settings()
    sam2_loader.reset_sam2_model()

    from app.main import app

    client = TestClient(app)
    img = _make_png_b64()

    seg = client.post("/infer/sam2/segment", json={"image_b64": img, "points": [[32.0, 32.0]]})
    assert seg.status_code == 200
    sbody = seg.json()
    assert set(sbody.keys()) == {
        "polygon",
        "score",
        "mock",
        "source",
        "mock_reason",
        "success",
        "message",
        "error_code",
    }
    assert len(sbody["polygon"]) >= 3
    assert 0.0 <= sbody["score"] <= 1.0

    trk = client.post(
        "/infer/sam2/track",
        json={
            "track_id": "tk",
            "prev_image_b64": img,
            "next_image_b64": img,
            "prev_polygon": [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]],
        },
    )
    assert trk.status_code == 200
    tbody = trk.json()
    assert set(tbody.keys()) == {"track_id", "polygon", "score", "mock", "source", "mock_reason"}
    assert tbody["track_id"] == "tk"


def test_load_failed시_segment_응답_mock_reason이_load_failed(monkeypatch) -> None:
    """이슈2: sam2 미설치/로드실패면 응답 mock_reason 이 load_failed (weights_missing 오표기 금지)."""
    from fastapi.testclient import TestClient

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()
    sam2_router.reset_mock_warn_flag()

    # sam2 import 실패 강제 → loader 가 load_failed 사유로 None 반환
    monkeypatch.setitem(sys.modules, "sam2", None)
    monkeypatch.setitem(sys.modules, "sam2.sam2_image_predictor", None)

    from app.main import app

    client = TestClient(app)
    seg = client.post(
        "/infer/sam2/segment",
        json={"image_b64": _make_png_b64(), "points": [[32.0, 32.0]]},
    )
    assert seg.status_code == 200
    body = seg.json()
    assert body["mock"] is True
    assert body["mock_reason"] == "load_failed"


def test_box가_3개면_검증오류(monkeypatch) -> None:
    """이슈4: Sam2SegmentRequest.box 는 정확히 4개 — 3개면 검증오류(silent fallback 차단).

    이 프로젝트는 RequestValidationError 를 400 VALIDATION_ERROR 로 변환한다.
    """
    from fastapi.testclient import TestClient

    monkeypatch.setenv("AI_MOCK_MODE", "true")
    reload_settings()
    sam2_loader.reset_sam2_model()

    from app.main import app

    client = TestClient(app)
    res = client.post(
        "/infer/sam2/segment",
        json={"image_b64": _make_png_b64(), "box": [10.0, 10.0, 40.0]},
    )
    assert res.status_code == 400
    assert res.json()["error_code"] in {"VALIDATION_ERROR", "INVALID_IMAGE"}
