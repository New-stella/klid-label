"""전환 가능한 탐지 백엔드(RT-DETR) 테스트.

detector_backend = "yolo" | "rtdetr" 설정으로 탐지/트래킹 백엔드를 선택한다.

- yolo (기본): ultralytics YOLOv8 + BoT-SORT (기존 코드 그대로 보존)
- rtdetr (신규): RT-DETRv2 (transformers) + ByteTrack (supervision)

transformers/supervision 는 무겁고 본 환경에 미설치이므로:
- lazy import + monkeypatch 로 deps 없이도 dispatch/fallback/캐시/스키마를 검증
- 실제 추론은 deps/가중치 부재 시 자동 skip (test_rtdetr_real.py)

불변 제약:
- schemas 4종(필드/타입)·HTTP 경로(/infer/yolo/predict, /infer/yolo/track) 변경 금지
- mock 사유(env_mock/weights_missing/load_failed)·WARN-once·LRU(10)+TTL(300s) 두 백엔드 공통 유지
"""

from __future__ import annotations

import sys
import types
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import reload_settings
from app.main import app
from app.models import rtdetr_loader

client = TestClient(app)


@pytest.fixture(autouse=True)
def _reset_state():
    rtdetr_loader.reset_rtdetr_model()
    rtdetr_loader.reset_rtdetr_trackers()
    yield
    rtdetr_loader.reset_rtdetr_model()
    rtdetr_loader.reset_rtdetr_trackers()
    reload_settings()


# ────────────────────────────────────────────────────────────────────
# 설정값
# ────────────────────────────────────────────────────────────────────

def test_config_detector_backend_기본값은_yolo() -> None:
    """기본 backend 는 yolo — 현행 동작 비파괴 유지."""
    from app.config import Settings

    settings = Settings(_env_file=None)
    assert settings.detector_backend == "yolo"
    assert settings.rtdetr_model_id == "PekingU/rtdetr_v2_r50vd"


def test_config_detector_backend_잘못된_값이면_yolo로_폴백(monkeypatch) -> None:
    """MEDIUM: 잘못된 detector_backend 값은 안전하게 yolo 로 정규화 (서버 기동 안전)."""
    monkeypatch.setenv("DETECTOR_BACKEND", "garbage")
    settings = reload_settings()
    # 정규화된 접근자는 항상 알려진 백엔드만 반환
    assert settings.resolved_detector_backend() == "yolo"


# ────────────────────────────────────────────────────────────────────
# 기본 yolo 백엔드 — 기존 동작 유지 (회귀 0)
# ────────────────────────────────────────────────────────────────────

def test_기본_backend_yolo면_기존_predict_동작_유지된다(small_png_b64: str, monkeypatch) -> None:
    """detector_backend 미설정(기본 yolo) + AI_MOCK_MODE=true → 기존 env_mock 응답."""
    monkeypatch.delenv("DETECTOR_BACKEND", raising=False)
    reload_settings()

    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["mock_reason"] == "env_mock"
    assert set(body["detections"][0].keys()) == {"label", "points", "score", "track_id"}


def test_기본_backend_yolo는_transformers를_import하지_않는다(monkeypatch, small_png_b64: str) -> None:
    """yolo 경로는 transformers 를 절대 import 하지 않아야 한다 (무거운 deps 회피)."""
    monkeypatch.delenv("DETECTOR_BACKEND", raising=False)
    reload_settings()
    # transformers import 를 폭발하는 가짜로 막아둔다 — yolo 경로가 건드리면 즉시 실패
    boom = types.ModuleType("transformers")

    def _explode(*_a, **_k):
        raise AssertionError("yolo 경로에서 transformers 를 import 하면 안 된다")

    boom.__getattr__ = _explode  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "transformers", boom)

    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200


# ────────────────────────────────────────────────────────────────────
# rtdetr dispatch
# ────────────────────────────────────────────────────────────────────

def test_detector_backend_rtdetr_설정시_rtdetr_경로로_dispatch된다(
    small_png_b64: str, monkeypatch
) -> None:
    """detector_backend=rtdetr 면 rtdetr 모델 로더를 통해 추론한다."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    called: dict[str, Any] = {}

    class _FakeRtdetr:
        def predict(self, img, params):
            called["predict"] = True
            from app.models.detector_backend import DetectionResult

            return [DetectionResult(label="car", points=[1.0, 2.0, 3.0, 4.0], score=0.77, track_id=None)]

    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_model", lambda: _FakeRtdetr())
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)

    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert called.get("predict") is True
    assert body["mock"] is False
    assert body["source"] == "model"
    assert body["detections"][0]["label"] == "car"
    assert body["detections"][0]["score"] == pytest.approx(0.77)


def test_rtdetr_transformers_미설치시_load_failed_mock_응답을_반환한다(monkeypatch) -> None:
    """HIGH: transformers 미설치(ImportError) → 크래시 금지 → load_failed mock 사유."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    monkeypatch.setenv("RTDETR_MODEL_ID", "PekingU/rtdetr_v2_r50vd")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    # transformers import 자체를 ImportError 로 강제
    monkeypatch.setitem(sys.modules, "transformers", None)

    model = rtdetr_loader.get_rtdetr_model()
    assert model is None
    assert rtdetr_loader.get_rtdetr_mock_reason() == "load_failed"


def test_rtdetr_가중치_로드실패시_mock_응답_사유가_설정된다(monkeypatch) -> None:
    """HIGH: 모델 로드 중 예외 → load_failed mock 사유 (크래시 금지)."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    fake_transformers = types.ModuleType("transformers")

    class _BoomProcessor:
        @staticmethod
        def from_pretrained(*_a, **_k):
            raise RuntimeError("가중치 다운로드 실패")

    class _BoomModel:
        @staticmethod
        def from_pretrained(*_a, **_k):
            raise RuntimeError("가중치 다운로드 실패")

    fake_transformers.AutoImageProcessor = _BoomProcessor  # type: ignore[attr-defined]
    fake_transformers.RTDetrV2ForObjectDetection = _BoomModel  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "transformers", fake_transformers)

    model = rtdetr_loader.get_rtdetr_model()
    assert model is None
    assert rtdetr_loader.get_rtdetr_mock_reason() == "load_failed"


def test_rtdetr_mock_모드_env_mock은_백엔드_무관하게_mock_응답(small_png_b64: str, monkeypatch) -> None:
    """env_mock 모드는 백엔드(rtdetr)와 무관하게 mock 응답을 반환한다."""
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    model = rtdetr_loader.get_rtdetr_model()
    assert model is None
    assert rtdetr_loader.get_rtdetr_mock_reason() == "env_mock"

    res = client.post(
        "/infer/yolo/predict",
        json={"image_b64": small_png_b64, "conf_threshold": 0.25},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["mock_reason"] == "env_mock"


# ────────────────────────────────────────────────────────────────────
# rtdetr track — clip_id 격리 + frame_index=0 리셋 (LRU/TTL 재사용)
# ────────────────────────────────────────────────────────────────────

class _FakeBackend:
    """detector_backend.DetectorBackend 호환 가짜 — track_id 격리 검증용."""

    def __init__(self, track_id: int):
        self._track_id = track_id
        self.track_calls = 0

    def predict(self, img, params):
        from app.models.detector_backend import DetectionResult

        return [DetectionResult(label="person", points=[10.0, 20.0, 30.0, 40.0], score=0.85, track_id=None)]

    def track(self, img, params):
        from app.models.detector_backend import DetectionResult

        self.track_calls += 1
        return [DetectionResult(label="person", points=[10.0, 20.0, 30.0, 40.0], score=0.85, track_id=self._track_id)]


def test_rtdetr_track_clip_id별_트래커_상태가_격리된다(monkeypatch) -> None:
    """MEDIUM: clip_id 가 다르면 ByteTrack 인스턴스가 격리된다 (yolo track 과 동일 계약)."""
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)
    created: list[_FakeBackend] = []

    def _factory():
        b = _FakeBackend(track_id=len(created) + 1)
        created.append(b)
        return b

    monkeypatch.setattr(rtdetr_loader, "_create_fresh_rtdetr_tracker", _factory)

    a = rtdetr_loader.get_rtdetr_tracker("clip-A", reset=True)
    b = rtdetr_loader.get_rtdetr_tracker("clip-B", reset=True)
    a2 = rtdetr_loader.get_rtdetr_tracker("clip-A", reset=False)

    assert a is a2
    assert a is not b
    assert len(created) == 2


def test_rtdetr_frame_index_0이면_트래커가_리셋된다(monkeypatch) -> None:
    """MEDIUM: reset=True (frame_index=0) → 새 인스턴스 강제 생성."""
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)
    created: list[_FakeBackend] = []

    def _factory():
        b = _FakeBackend(track_id=1)
        created.append(b)
        return b

    monkeypatch.setattr(rtdetr_loader, "_create_fresh_rtdetr_tracker", _factory)

    first = rtdetr_loader.get_rtdetr_tracker("clip-Y", reset=True)
    second = rtdetr_loader.get_rtdetr_tracker("clip-Y", reset=True)

    assert first is not second
    assert len(created) == 2


def test_rtdetr_track_LRU_max_초과시_가장_오래된_제거(monkeypatch) -> None:
    """LRU 캐시 구조가 rtdetr 백엔드에서도 동일하게 동작."""
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)
    monkeypatch.setattr(rtdetr_loader, "_MAX_TRACKERS", 3)
    monkeypatch.setattr(rtdetr_loader, "_create_fresh_rtdetr_tracker", lambda: _FakeBackend(1))

    rtdetr_loader.get_rtdetr_tracker("A", reset=True)
    rtdetr_loader.get_rtdetr_tracker("B", reset=True)
    rtdetr_loader.get_rtdetr_tracker("C", reset=True)
    rtdetr_loader.get_rtdetr_tracker("D", reset=True)

    keys = list(rtdetr_loader._TRACKERS.keys())
    assert "A" not in keys
    assert set(keys) == {"B", "C", "D"}


def test_rtdetr_track_mock_사유시_None_반환(monkeypatch) -> None:
    """mock 사유면 get_rtdetr_tracker 는 None 반환 (호출자가 mock 처리)."""
    rtdetr_loader.reset_rtdetr_trackers()
    for reason in ("env_mock", "weights_missing", "load_failed"):
        monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda r=reason: r)
        assert rtdetr_loader.get_rtdetr_tracker("clip-Z", reset=True) is None


def test_rtdetr_track_엔드포인트_같은_clip_id_track_id_유지(small_png_b64: str, monkeypatch) -> None:
    """엔드포인트가 rtdetr 백엔드에서 frame_index 0,1,2 호출 시 동일 track_id 보존."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    reload_settings()
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)
    fake = _FakeBackend(track_id=42)
    monkeypatch.setattr(rtdetr_loader, "_create_fresh_rtdetr_tracker", lambda: fake)

    for fi in (0, 1, 2):
        res = client.post(
            "/infer/yolo/track",
            json={"image_b64": small_png_b64, "clip_id": "vid-rt", "frame_index": fi},
        )
        assert res.status_code == 200
        body = res.json()
        assert body["mock"] is False
        assert body["source"] == "model"
        assert body["detections"][0]["track_id"] == 42

    assert fake.track_calls == 3


# ────────────────────────────────────────────────────────────────────
# 계약 가드 — 스키마/경로 불변
# ────────────────────────────────────────────────────────────────────

def test_predict_track_응답_스키마_필드가_변경되지_않는다(small_png_b64: str, monkeypatch) -> None:
    """불변 제약: 응답 필드 집합이 그대로여야 한다 (BE DTO 계약)."""
    monkeypatch.delenv("DETECTOR_BACKEND", raising=False)
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    reload_settings()

    expected_top = {"detections", "mock", "source", "mock_reason"}
    expected_det = {"label", "points", "score", "track_id"}

    pred = client.post(
        "/infer/yolo/predict", json={"image_b64": small_png_b64, "conf_threshold": 0.25}
    ).json()
    assert set(pred.keys()) == expected_top
    assert set(pred["detections"][0].keys()) == expected_det

    trk = client.post(
        "/infer/yolo/track",
        json={"image_b64": small_png_b64, "clip_id": "c1", "frame_index": 0},
    ).json()
    assert set(trk.keys()) == expected_top
    assert set(trk["detections"][0].keys()) == expected_det


def test_schemas_불변_필드와_타입_확인() -> None:
    """schemas.py 4종 스키마의 필드/타입이 변경되지 않았음을 가드한다."""
    from app.schemas import (
        Detection,
        YoloRequest,
        YoloResponse,
        YoloTrackRequest,
        YoloTrackResponse,
    )

    assert set(YoloRequest.model_fields) == {"image_b64", "conf_threshold", "imgsz", "iou"}
    assert set(Detection.model_fields) == {"label", "points", "score", "track_id"}
    assert set(YoloResponse.model_fields) == {"detections", "mock", "source", "mock_reason"}
    assert set(YoloTrackRequest.model_fields) == {
        "image_b64",
        "clip_id",
        "frame_index",
        "conf_threshold",
        "imgsz",
        "iou",
    }
    assert set(YoloTrackResponse.model_fields) == {"detections", "mock", "source", "mock_reason"}


# ────────────────────────────────────────────────────────────────────
# device 이동 (cuda 안전) — Issue #1
# ────────────────────────────────────────────────────────────────────

def _install_fake_transformers(monkeypatch, captured: dict[str, Any]) -> None:
    """transformers 미설치 환경에서 _build_rtdetr_backend 가 돌도록 가짜 주입.

    model.to(device) 호출 인자를 captured 에 기록한다(실제 cuda 불필요).
    """

    class _FakeModel:
        def __init__(self):
            self.config = types.SimpleNamespace(id2label={0: "person"})

        def eval(self):
            return self

        def to(self, device):
            captured["model_to"] = device
            return self

    class _FakeProcessor:
        @staticmethod
        def from_pretrained(*_a, **_k):
            return _FakeProcessor()

    fake = types.ModuleType("transformers")
    fake.AutoImageProcessor = _FakeProcessor  # type: ignore[attr-defined]

    class _FakeModelCls:
        @staticmethod
        def from_pretrained(*_a, **_k):
            return _FakeModel()

    fake.RTDetrV2ForObjectDetection = _FakeModelCls  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "transformers", fake)


def test_rtdetr_빌드시_모델을_설정된_device로_이동한다_cpu(monkeypatch) -> None:
    """Issue #1: ai_device 가 cpu 면 정상 빌드 + model.to('cpu') 호출."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    monkeypatch.setenv("AI_DEVICE", "cpu")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    captured: dict[str, Any] = {}
    _install_fake_transformers(monkeypatch, captured)

    backend = rtdetr_loader.get_rtdetr_model()
    assert backend is not None
    assert captured["model_to"] == "cpu"
    assert backend._device == "cpu"


def test_rtdetr_빌드시_cuda_설정이면_model을_cuda로_이동한다(monkeypatch) -> None:
    """Issue #1: ai_device='cuda' 면 model.to('cuda') 가 호출된다(실제 GPU 불필요)."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    monkeypatch.setenv("AI_DEVICE", "cuda")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()

    captured: dict[str, Any] = {}
    _install_fake_transformers(monkeypatch, captured)

    backend = rtdetr_loader.get_rtdetr_model()
    assert backend is not None
    assert captured["model_to"] == "cuda"
    assert backend._device == "cuda"


# ────────────────────────────────────────────────────────────────────
# ByteTrack 결과 길이 불일치 무방비 — Issue #2
# ────────────────────────────────────────────────────────────────────

def test_rtdetr_track_tracker_id가_적게_반환되면_WARN하고_누락분은_None유지(
    monkeypatch, caplog
) -> None:
    """Issue #2: ByteTrack 가 입력보다 적은 tracker_id 를 반환 → 크래시 없이 WARN + 누락 det track_id=None."""
    import logging

    import numpy as np
    from app.models.detector_backend import DetectionResult, InferenceParams

    # 2건 detection 반환하는 백엔드 — _infer_raw 를 직접 대체
    backend = rtdetr_loader._RtdetrBackend(
        processor=object(), model=object(), id2label=None, device="cpu"
    )
    dets_fixture = [
        DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None),
        DetectionResult(label="car", points=[2.0, 2.0, 3.0, 3.0], score=0.8, track_id=None),
    ]
    monkeypatch.setattr(backend, "_infer_raw", lambda img, params: dets_fixture)

    # supervision 미설치 환경 — sv.Detections 생성을 위한 가짜 모듈 주입
    fake_sv = types.ModuleType("supervision")
    fake_sv.Detections = lambda **kwargs: types.SimpleNamespace(**kwargs)  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "supervision", fake_sv)

    # 입력 2건인데 tracker_id 1건만 반환하는 가짜 ByteTrackTracker (신규 update() API)
    class _TruncTracker:
        def update(self, sv_dets):
            return types.SimpleNamespace(tracker_id=np.array([7], dtype=int))

    monkeypatch.setattr(backend, "_ensure_tracker", lambda reset: _TruncTracker())

    with caplog.at_level(logging.WARNING, logger=rtdetr_loader.logger.name):
        result = backend.track(object(), InferenceParams(frame_index=0))

    assert len(result) == 2
    assert result[0].track_id == 7  # 매핑된 첫 det
    assert result[1].track_id is None  # 누락분 None 유지
    assert any("길이 불일치" in r.message for r in caplog.records)


# ────────────────────────────────────────────────────────────────────
# 로그 접두사 — RT-DETR mock 은 backend 식별자가 로그에 남는다 (Issue #3)
# ────────────────────────────────────────────────────────────────────

def test_rtdetr_mock_로그에_yolo가_아닌_backend_식별자가_남는다(
    small_png_b64: str, monkeypatch, caplog
) -> None:
    """Issue #3: RT-DETR mock WARN 로그에 yolo 가 아닌 식별자(rtdetr)가 남아야 한다."""
    import logging

    from app.routers import yolo as yolo_router

    monkeypatch.setenv("AI_MOCK_MODE", "true")
    monkeypatch.setenv("DETECTOR_BACKEND", "rtdetr")
    reload_settings()
    rtdetr_loader.reset_rtdetr_model()
    yolo_router.reset_mock_warn_flag()

    with caplog.at_level(logging.WARNING, logger=yolo_router.logger.name):
        res = client.post(
            "/infer/yolo/predict",
            json={"image_b64": small_png_b64, "conf_threshold": 0.25},
        )
    assert res.status_code == 200
    warn_msgs = [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING]
    assert any("rtdetr" in m for m in warn_msgs), warn_msgs
    assert all("[YOLO]" not in m for m in warn_msgs), warn_msgs


# ────────────────────────────────────────────────────────────────────
# COCO id2label 매핑
# ────────────────────────────────────────────────────────────────────

def test_rtdetr_coco_id2label_person_car_매핑(monkeypatch) -> None:
    """MEDIUM: RT-DETR COCO id2label → ultralytics 와 동일 의미 라벨(person/car)."""
    from app.models.detector_backend import coco_label_from_id

    # transformers id2label 이 주어지면 그대로, 없으면 COCO 기본 매핑 사용
    assert coco_label_from_id(0, None) == "person"
    assert coco_label_from_id(2, None) == "car"
    # transformers 가 제공한 id2label 우선
    assert coco_label_from_id(0, {0: "person"}) == "person"
    assert coco_label_from_id(7, {7: "truck"}) == "truck"


# ────────────────────────────────────────────────────────────────────
# 성능 리팩토링 — 싱글톤 모델 공유 + clip 별 경량 트래커 분리
# ────────────────────────────────────────────────────────────────────

class _CountingSingletonModel:
    """공유 싱글톤 모델 stub — predict 호출 시 detection 1건 반환."""

    def predict(self, img, params):
        from app.models.detector_backend import DetectionResult

        return [DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None)]


def test_rtdetr_여러_clip_track시_모델_build는_1회만_일어난다(monkeypatch) -> None:
    """리팩토링 핵심: clip 이 여러 개여도 무거운 RT-DETR 모델 build 는 1회만(싱글톤 공유)."""
    rtdetr_loader.reset_rtdetr_model()
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()

    build_calls = {"n": 0}

    def _fake_build():
        build_calls["n"] += 1
        return _CountingSingletonModel()

    # 싱글톤 모델 build 카운트
    monkeypatch.setattr(rtdetr_loader, "_build_rtdetr_backend", _fake_build)
    # 트래커는 실제 trackers 의존 없이 동작하도록 None(=track_id 미부여) 으로 graceful
    monkeypatch.setattr(rtdetr_loader, "_new_bytetrack_tracker", lambda: None)

    from app.models.detector_backend import InferenceParams

    for clip in ("clip-A", "clip-B", "clip-C"):
        handle = rtdetr_loader.get_rtdetr_tracker(clip, reset=True)
        assert handle is not None
        dets = handle.track(object(), InferenceParams(clip_id=clip, frame_index=0))
        assert dets and dets[0].label == "person"

    # 3개 clip 을 트래킹했지만 무거운 모델 build 는 1회만
    assert build_calls["n"] == 1


def test_rtdetr_clip별_경량_트래커가_분리된다(monkeypatch) -> None:
    """clip 별 트래커 핸들이 각자 독립된 ByteTrackTracker 상태를 보유한다."""
    rtdetr_loader.reset_rtdetr_model()
    rtdetr_loader.reset_rtdetr_trackers()
    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_mock_reason", lambda: None)

    created_trackers: list[object] = []

    def _fake_new_tracker():
        t = object()
        created_trackers.append(t)
        return t

    monkeypatch.setattr(rtdetr_loader, "_new_bytetrack_tracker", _fake_new_tracker)

    a = rtdetr_loader.get_rtdetr_tracker("clip-A", reset=True)
    b = rtdetr_loader.get_rtdetr_tracker("clip-B", reset=True)

    assert isinstance(a, rtdetr_loader._RtdetrTrackerHandle)
    assert a is not b
    # 핸들마다 자체 트래커 상태를 보유 → 분리됨
    assert a._tracker is not b._tracker
    assert len(created_trackers) == 2


def test_rtdetr_handle_track가_싱글톤_모델로_탐지하고_바이트트랙으로_id부여(monkeypatch) -> None:
    """핸들.track() 은 공유 모델로 탐지 후 ByteTrackTracker.update() 로 track_id 부여."""
    rtdetr_loader.reset_rtdetr_model()

    # 공유 싱글톤 모델 stub (2건 detection)
    from app.models.detector_backend import DetectionResult, InferenceParams

    class _Model:
        def predict(self, img, params):
            return [
                DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None),
                DetectionResult(label="car", points=[2.0, 2.0, 3.0, 3.0], score=0.8, track_id=None),
            ]

    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_model", lambda: _Model())

    # supervision sv.Detections 변환용 가짜
    fake_sv = types.ModuleType("supervision")
    fake_sv.Detections = lambda **kwargs: types.SimpleNamespace(**kwargs)  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "supervision", fake_sv)

    import numpy as np

    # 신규 update() API + 미확정 트랙(-1)은 None 으로 정규화되는지 검증
    class _FakeByteTrackTracker:
        def update(self, sv_dets):
            return types.SimpleNamespace(tracker_id=np.array([5, -1], dtype=int))

    monkeypatch.setattr(rtdetr_loader, "_new_bytetrack_tracker", lambda: _FakeByteTrackTracker())

    handle = rtdetr_loader._RtdetrTrackerHandle()
    result = handle.track(object(), InferenceParams(frame_index=0))

    assert len(result) == 2
    assert result[0].track_id == 5      # 확정 트랙 → 정수 부여
    assert result[1].track_id is None   # 미확정(-1) → None 정규화


def test_rtdetr_handle_track_트래커_미설치시_graceful_track_id_None(monkeypatch) -> None:
    """trackers 미설치(_new_bytetrack_tracker=None) 시 크래시 없이 track_id 미부여."""
    rtdetr_loader.reset_rtdetr_model()
    from app.models.detector_backend import DetectionResult, InferenceParams

    class _Model:
        def predict(self, img, params):
            return [DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None)]

    monkeypatch.setattr(rtdetr_loader, "get_rtdetr_model", lambda: _Model())
    monkeypatch.setattr(rtdetr_loader, "_new_bytetrack_tracker", lambda: None)

    handle = rtdetr_loader._RtdetrTrackerHandle()
    result = handle.track(object(), InferenceParams(frame_index=0))
    assert len(result) == 1
    assert result[0].track_id is None


# ────────────────────────────────────────────────────────────────────
# class_id 라벨 기반 안정 매핑 — Issue (LOW) class_id 하드코딩 제거
# ────────────────────────────────────────────────────────────────────

def test_coco_id_from_label_person과_car는_서로_다른_안정_정수다() -> None:
    """label→class_id 매핑이 COCO 표준 id 로 안정적으로 분리된다(person=0, car=2)."""
    from app.models.detector_backend import coco_id_from_label

    assert coco_id_from_label("person") == 0
    assert coco_id_from_label("car") == 2
    assert coco_id_from_label("person") != coco_id_from_label("car")
    # 미지의 라벨도 결정적(동일 라벨 → 동일 정수) + COCO 범위와 비충돌
    unknown = coco_id_from_label("unknown_thing")
    assert unknown == coco_id_from_label("unknown_thing")
    assert unknown >= 80


def test_rtdetr_bytetrack에_서로_다른_라벨은_다른_class_id로_전달된다(monkeypatch) -> None:
    """Issue(class_id 하드코딩): person/car 가 ByteTrackTracker 에 서로 다른 class_id 로 전달된다.

    기존엔 class_id 가 전부 0 으로 하드코딩되어 클래스별 트랙 분리 시 ID 충돌 위험이 있었다.
    """
    from app.models.detector_backend import DetectionResult

    dets = [
        DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None),
        DetectionResult(label="car", points=[2.0, 2.0, 3.0, 3.0], score=0.8, track_id=None),
    ]

    # supervision sv.Detections 변환용 가짜 — class_id 를 캡처
    fake_sv = types.ModuleType("supervision")
    fake_sv.Detections = lambda **kwargs: types.SimpleNamespace(**kwargs)  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "supervision", fake_sv)

    captured: dict[str, Any] = {}

    import numpy as np

    class _CapturingTracker:
        def update(self, sv_dets):
            captured["class_id"] = list(sv_dets.class_id)
            return types.SimpleNamespace(tracker_id=np.array([1, 2], dtype=int))

    rtdetr_loader._apply_bytetrack(dets, _CapturingTracker())

    class_ids = captured["class_id"]
    assert len(class_ids) == 2
    # person 과 car 가 서로 다른 class_id 로 전달되어야 한다 (전부 0 하드코딩 회귀 방지)
    assert class_ids[0] != class_ids[1]
    assert class_ids[0] == 0  # person → COCO 0
    assert class_ids[1] == 2  # car → COCO 2
