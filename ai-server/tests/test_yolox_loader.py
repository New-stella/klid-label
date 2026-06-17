"""YOLOX(ONNX Runtime) 탐지 백엔드 로더 + 순수 후처리 함수 테스트 (Phase 1).

YOLOX 공식 ONNX 모델은 ultralytics 와 달리 raw 출력을 내므로 직접 후처리한다.
onnxruntime 은 무겁고 본 환경에 미설치이므로:
- onnxruntime/numpy 무거운 연산은 lazy import + monkeypatch 로 deps 없이 검증
- 순수 후처리 함수(_yolox_postprocess / _nms)는 합성 numpy 텐서로 직접 단언

불변 제약 (yolo_loader / rtdetr_loader 와 동형 계약):
- mock 사유(env_mock/weights_missing/load_failed)
- 트래커 LRU(10) + TTL(300s) + threading.Lock
- import 시 onnxruntime 미로드 (lazy import)
"""

from __future__ import annotations

import sys
import types
from typing import Any

import numpy as np
import pytest

from app.config import reload_settings
from app.models import bytetrack_util, yolox_loader
from app.models.detector_backend import DetectionResult


@pytest.fixture(autouse=True)
def _reset_state():
    yolox_loader.reset_yolox_model()
    yolox_loader.reset_yolox_trackers()
    # 공용 bytetrack_util 의 WARN-once 플래그도 리셋 — rtdetr↔yolox 테스트 순서 의존(flaky) 방지.
    bytetrack_util.reset_tracker_unavailable_warned()
    yield
    yolox_loader.reset_yolox_model()
    yolox_loader.reset_yolox_trackers()
    bytetrack_util.reset_tracker_unavailable_warned()
    reload_settings()


# ────────────────────────────────────────────────────────────────────
# 순수 후처리 — 디코드된 [cx,cy,w,h] 절대좌표 출력 처리
# ────────────────────────────────────────────────────────────────────

def _make_decoded_output(boxes: list[list[float]]) -> np.ndarray:
    """[cx, cy, w, h, obj_conf, class0_prob, class1_prob, ...] 형태의 합성 출력 생성.

    boxes: 각 항목 = [cx, cy, w, h, obj_conf, cls_idx, cls_prob]
    → 80 class one-hot(해당 cls_prob) 로 확장한 (1, N, 85) 텐서.
    """
    rows = []
    for cx, cy, w, h, obj, cls_idx, cls_prob in boxes:
        rows.append([cx, cy, w, h, obj] + _one_hot(int(cls_idx), cls_prob))
    return np.array([rows], dtype=np.float32)  # (1, N, 85)


def _one_hot(cls_idx: int, cls_prob: float) -> list[float]:
    """80-class one-hot 확률 벡터 — 지정 클래스에 cls_prob, 나머지 0."""
    cls_vec = [0.0] * 80
    cls_vec[cls_idx] = cls_prob
    return cls_vec


def test_YOLOX_이미_디코드된_출력이_정확한_xyxy_박스로_변환되고_ratio_역보정됨() -> None:
    """이미 디코드된 [cx,cy,w,h] 절대좌표 출력(decoded=True) → [x1,y1,x2,y2] + ratio 역보정.

    공식 YOLOX export 의 표준 출력은 raw grid 이지만, decode_in_inference=True 로 export 한
    모델은 이미 디코드된 절대좌표를 낸다. 그 입력은 decoded=True 로 grid decode 를 건너뛴다.
    """
    # cx=100, cy=100, w=40, h=20 → xyxy(입력좌표)=[80,90,120,110]
    # ratio=0.5 → 원본좌표 = xyxy / 0.5 = [160,180,240,220]
    output = _make_decoded_output([[100.0, 100.0, 40.0, 20.0, 0.9, 0, 0.95]])
    dets = yolox_loader._yolox_postprocess(
        [output], ratio=0.5, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    )
    assert len(dets) == 1
    d = dets[0]
    assert d.label == "person"
    assert d.points == pytest.approx([160.0, 180.0, 240.0, 220.0])
    assert d.score == pytest.approx(0.9 * 0.95)
    assert d.track_id is None


def test_YOLOX_raw_grid_출력이_grid_stride_복원으로_정확한_xyxy_박스로_decode됨() -> None:
    """AC4 핵심: 공식 YOLOX onnx_inference.py 규약대로 raw grid → 절대좌표 decode 단언.

    공식 export(tools/export_onnx.py)의 표준 출력은 raw(미디코드)이고, 공식
    onnx_inference.demo_postprocess 는 각 stride(8,16,32)별 grid 를 만들어:
      cx = (raw_x + grid_x) * stride,  cy = (raw_y + grid_y) * stride
      w  = exp(raw_w) * stride,        h  = exp(raw_h) * stride
    로 [cx,cy,w,h] 절대좌표를 복원한다.

    입력 size=(64,64) → grid 개수 = 8x8(stride8) + 4x4(stride16) + 2x2(stride32) = 84.
    아래 손계산:
    - anchor #0: stride=8, grid=(0,0), raw=[0.5,0.5,0,0]
      cx=(0.5+0)*8=4, cy=4, w=exp(0)*8=8, h=8 → xyxy(input)=[0,0,8,8], ratio=1 → [0,0,8,8]
    - anchor #64: stride=16 그룹 첫 anchor, grid=(0,0), raw=[1.0,0.5,ln(2),0]
      cx=(1.0+0)*16=16, cy=(0.5)*16=8, w=exp(ln2)*16=32, h=exp(0)*16=16
      → xyxy(input)=[0,0,32,16], ratio=1 → [0,0,32,16]
    """
    import math

    input_size = (64, 64)
    n_anchors = 8 * 8 + 4 * 4 + 2 * 2  # 84
    rows = []
    for _ in range(n_anchors):
        # 기본은 score 0 (obj=0) 으로 필터되도록 둔다.
        cls_vec = [0.0] * 80
        rows.append([0.0, 0.0, 0.0, 0.0, 0.0] + cls_vec)

    # anchor #0 (stride8, grid 0,0) → 기대 xyxy [0,0,8,8]
    rows[0] = [0.5, 0.5, 0.0, 0.0, 0.9] + _one_hot(0, 0.95)
    # anchor #64 (stride16 첫 anchor, grid 0,0) → 기대 xyxy [0,0,32,16]
    rows[64] = [1.0, 0.5, math.log(2.0), 0.0, 0.9] + _one_hot(2, 0.9)

    raw = np.array([rows], dtype=np.float32)  # (1, 84, 85) raw grid 출력

    dets = yolox_loader._yolox_postprocess(
        [raw], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=input_size,
        decoded=False,
    )
    by_label = {d.label: d for d in dets}
    assert set(by_label) == {"person", "car"}
    # exp(0)*stride 등 부동소수점 연산 오차 허용(abs=1e-4).
    assert by_label["person"].points == pytest.approx([0.0, 0.0, 8.0, 8.0], abs=1e-4)
    assert by_label["car"].points == pytest.approx([0.0, 0.0, 32.0, 16.0], abs=1e-4)


def test_score_obj_conf_곱_class_prob가_conf_threshold_미만이면_필터링됨() -> None:
    """score = obj_conf * class_prob.max() < conf_threshold → 결과에서 제외."""
    # obj=0.5, class_prob=0.4 → score=0.2 < 0.3 → 필터
    output = _make_decoded_output([[50.0, 50.0, 20.0, 20.0, 0.5, 2, 0.4]])
    dets = yolox_loader._yolox_postprocess(
        [output], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    )
    assert dets == []


def test_빈_출력이거나_예상치_못한_shape면_빈_리스트_반환() -> None:
    """HIGH: 방어 — 박스 0개/이상 shape 에 IndexError 대신 graceful 빈 리스트."""
    empty = np.zeros((1, 0, 85), dtype=np.float32)
    assert yolox_loader._yolox_postprocess(
        [empty], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    ) == []
    # 완전히 망가진 shape 도 크래시 금지
    bogus = np.zeros((3, 3), dtype=np.float32)
    assert yolox_loader._yolox_postprocess(
        [bogus], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    ) == []


# ────────────────────────────────────────────────────────────────────
# NMS 순수 함수
# ────────────────────────────────────────────────────────────────────

def test__nms가_IoU_임계값_초과_중복박스를_제거하고_최고점수만_남김() -> None:
    """겹치는 2박스(높은 IoU) → 최고 점수 1개만 keep."""
    boxes = np.array([[0.0, 0.0, 10.0, 10.0], [1.0, 1.0, 11.0, 11.0]], dtype=float)
    scores = np.array([0.9, 0.8], dtype=float)
    keep = yolox_loader._nms(boxes, scores, iou_thr=0.5)
    assert keep == [0]


def test_NMS가_겹치지_않는_박스는_둘_다_유지() -> None:
    boxes = np.array([[0.0, 0.0, 10.0, 10.0], [100.0, 100.0, 110.0, 110.0]], dtype=float)
    scores = np.array([0.9, 0.8], dtype=float)
    keep = sorted(yolox_loader._nms(boxes, scores, iou_thr=0.5))
    assert keep == [0, 1]


def test_클래스가_다르면_겹쳐도_NMS가_제거하지_않음() -> None:
    """클래스별 NMS — 같은 위치라도 클래스가 다르면 둘 다 생존."""
    # 동일 위치 박스 2개, 클래스만 다름(person=0, car=2)
    output = _make_decoded_output([
        [100.0, 100.0, 40.0, 40.0, 0.9, 0, 0.95],
        [100.0, 100.0, 40.0, 40.0, 0.9, 2, 0.90],
    ])
    dets = yolox_loader._yolox_postprocess(
        [output], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    )
    labels = sorted(d.label for d in dets)
    assert labels == ["car", "person"]


# ────────────────────────────────────────────────────────────────────
# 싱글톤 로더 — mock 사유
# ────────────────────────────────────────────────────────────────────

def test_가중치_onnx_파일_부재시_get_yolox_model이_weights_missing_None_반환(
    tmp_path, monkeypatch
) -> None:
    missing = tmp_path / "weights" / "yolox_s.onnx"  # 미생성
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLOX_WEIGHTS_PATH", str(missing))
    reload_settings()
    yolox_loader.reset_yolox_model()

    model = yolox_loader.get_yolox_model()
    assert model is None
    assert yolox_loader.get_yolox_mock_reason() == "weights_missing"


def test_AI_MOCK_MODE_true시_get_yolox_model이_env_mock_None_반환(monkeypatch) -> None:
    monkeypatch.setenv("AI_MOCK_MODE", "true")
    reload_settings()
    yolox_loader.reset_yolox_model()

    model = yolox_loader.get_yolox_model()
    assert model is None
    assert yolox_loader.get_yolox_mock_reason() == "env_mock"


def test_onnxruntime_미설치_시뮬레이션시_load_failed_None_반환(tmp_path, monkeypatch) -> None:
    """HIGH: onnxruntime 미설치(ImportError) → 크래시 금지 → load_failed mock 사유."""
    weights = tmp_path / "yolox_s.onnx"
    weights.write_bytes(b"\x00\x00\x00\x00")  # 파일 존재 시뮬레이션

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLOX_WEIGHTS_PATH", str(weights))
    reload_settings()
    yolox_loader.reset_yolox_model()

    # onnxruntime import 자체를 ImportError 로 강제
    monkeypatch.setitem(sys.modules, "onnxruntime", None)

    model = yolox_loader.get_yolox_model()
    assert model is None
    assert yolox_loader.get_yolox_mock_reason() == "load_failed"


def test_onnxruntime_세션_로드실패시_load_failed_None_반환(tmp_path, monkeypatch) -> None:
    """HIGH: 세션 빌드 중 예외 → load_failed (크래시 금지)."""
    weights = tmp_path / "yolox_s.onnx"
    weights.write_bytes(b"\x00\x00\x00\x00")

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLOX_WEIGHTS_PATH", str(weights))
    reload_settings()
    yolox_loader.reset_yolox_model()

    fake_ort = types.ModuleType("onnxruntime")

    class _BoomSession:
        def __init__(self, *_a, **_k):
            raise RuntimeError("onnx 세션 로드 실패")

    fake_ort.InferenceSession = _BoomSession  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "onnxruntime", fake_ort)

    model = yolox_loader.get_yolox_model()
    assert model is None
    assert yolox_loader.get_yolox_mock_reason() == "load_failed"


def test_정상_가중치와_onnxruntime면_싱글톤_백엔드_로드(tmp_path, monkeypatch) -> None:
    """가중치 존재 + onnxruntime 정상 → _YoloxBackend 로드, mock_reason=None, 세션 1회 빌드."""
    weights = tmp_path / "yolox_s.onnx"
    weights.write_bytes(b"\x00\x00\x00\x00")

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLOX_WEIGHTS_PATH", str(weights))
    reload_settings()
    yolox_loader.reset_yolox_model()

    built: dict[str, Any] = {"n": 0}

    class _FakeSession:
        def __init__(self, path, providers=None):
            built["n"] += 1
            built["path"] = path
            built["providers"] = providers

    fake_ort = types.ModuleType("onnxruntime")
    fake_ort.InferenceSession = _FakeSession  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "onnxruntime", fake_ort)

    m1 = yolox_loader.get_yolox_model()
    m2 = yolox_loader.get_yolox_model()
    assert m1 is not None
    assert m1 is m2  # 싱글톤
    assert built["n"] == 1  # 세션은 1회만 빌드
    assert built["path"] == str(weights)
    assert yolox_loader.get_yolox_mock_reason() is None


# ────────────────────────────────────────────────────────────────────
# 트래커 캐시 LRU / TTL
# ────────────────────────────────────────────────────────────────────

class _FakeHandle:
    def __init__(self) -> None:
        self.id = object()


def test_트래커_캐시_LRU_max10_초과시_가장_오래된_clip_제거(monkeypatch) -> None:
    yolox_loader.reset_yolox_trackers()
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: None)
    monkeypatch.setattr(yolox_loader, "_MAX_TRACKERS", 3)
    monkeypatch.setattr(yolox_loader, "_create_fresh_yolox_tracker", _FakeHandle)

    yolox_loader.get_yolox_tracker("A", reset=True)
    yolox_loader.get_yolox_tracker("B", reset=True)
    yolox_loader.get_yolox_tracker("C", reset=True)
    yolox_loader.get_yolox_tracker("D", reset=True)

    keys = list(yolox_loader._TRACKERS.keys())
    assert "A" not in keys
    assert set(keys) == {"B", "C", "D"}


def test_트래커_TTL_300초_경과_항목_lazy_expiration(monkeypatch) -> None:
    yolox_loader.reset_yolox_trackers()
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: None)
    monkeypatch.setattr(yolox_loader, "_create_fresh_yolox_tracker", _FakeHandle)

    fake_time = {"t": 1000.0}
    monkeypatch.setattr(yolox_loader.time, "time", lambda: fake_time["t"])

    yolox_loader.get_yolox_tracker("old", reset=True)
    # 301초 경과 — TTL(300s) 초과
    fake_time["t"] = 1301.0
    yolox_loader.get_yolox_tracker("new", reset=True)

    keys = list(yolox_loader._TRACKERS.keys())
    assert "old" not in keys
    assert "new" in keys


def test_트래커_clip별_격리_및_재사용(monkeypatch) -> None:
    yolox_loader.reset_yolox_trackers()
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: None)
    monkeypatch.setattr(yolox_loader, "_create_fresh_yolox_tracker", _FakeHandle)

    a = yolox_loader.get_yolox_tracker("clip-A", reset=True)
    b = yolox_loader.get_yolox_tracker("clip-B", reset=True)
    a2 = yolox_loader.get_yolox_tracker("clip-A", reset=False)

    assert a is a2
    assert a is not b


def test_트래커_mock_사유시_None_반환(monkeypatch) -> None:
    yolox_loader.reset_yolox_trackers()
    for reason in ("env_mock", "weights_missing", "load_failed"):
        monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda r=reason: r)
        assert yolox_loader.get_yolox_tracker("clip-Z", reset=True) is None


def test_reset_yolox_trackers가_캐시_전체_초기화(monkeypatch) -> None:
    yolox_loader.reset_yolox_trackers()
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: None)
    monkeypatch.setattr(yolox_loader, "_create_fresh_yolox_tracker", _FakeHandle)

    yolox_loader.get_yolox_tracker("X", reset=True)
    yolox_loader.get_yolox_tracker("Y", reset=True)
    assert len(yolox_loader._TRACKERS) == 2

    yolox_loader.reset_yolox_trackers()
    assert len(yolox_loader._TRACKERS) == 0


# ────────────────────────────────────────────────────────────────────
# COCO 라벨 매핑
# ────────────────────────────────────────────────────────────────────

def test_coco_label_from_id로_class_id가_person_car_등_COCO_라벨로_매핑됨() -> None:
    output = _make_decoded_output([
        [10.0, 10.0, 4.0, 4.0, 0.9, 0, 0.99],   # person
        [50.0, 50.0, 4.0, 4.0, 0.9, 2, 0.99],   # car
        [90.0, 90.0, 4.0, 4.0, 0.9, 7, 0.99],   # truck
    ])
    dets = yolox_loader._yolox_postprocess(
        [output], ratio=1.0, conf_threshold=0.3, nms_iou=0.45, input_size=(640, 640),
        decoded=True,
    )
    labels = sorted(d.label for d in dets)
    assert labels == ["car", "person", "truck"]


# ────────────────────────────────────────────────────────────────────
# track — 자체 predict 후 bytetrack_util._apply_bytetrack 로 track_id 부여
# ────────────────────────────────────────────────────────────────────

def test_backend_track가_predict후_bytetrack으로_track_id_부여(monkeypatch) -> None:
    """_YoloxBackend.track 은 predict 결과에 _apply_bytetrack 으로 track_id 를 부여한다."""
    from app.models.detector_backend import InferenceParams

    backend = yolox_loader._YoloxBackend(session=object(), input_size=(640, 640), device="cpu")

    dets_fixture = [
        DetectionResult(label="person", points=[0.0, 0.0, 1.0, 1.0], score=0.9, track_id=None),
    ]
    monkeypatch.setattr(backend, "predict", lambda img, params: dets_fixture)

    applied: dict[str, Any] = {}

    def _fake_apply(dets, tracker):
        applied["called"] = True
        for d in dets:
            d.track_id = 11

    monkeypatch.setattr(yolox_loader, "_apply_bytetrack", _fake_apply)
    monkeypatch.setattr(yolox_loader, "_new_bytetrack_tracker", lambda: object())

    result = backend.track(object(), InferenceParams(frame_index=0))
    assert applied.get("called") is True
    assert result[0].track_id == 11


def test_lazy_import_app_models_yolox_loader_시_onnxruntime_미로드() -> None:
    """import 시 onnxruntime 이 sys.modules 에 로드되지 않아야 한다 (lazy import 가드)."""
    import importlib

    # 이미 import 되어 있을 수 있으나, 본 모듈 import 가 onnxruntime 을 끌어오면 안 된다.
    # sys.modules 에서 제거 후 재import 하여 부작용 확인.
    sys.modules.pop("onnxruntime", None)
    sys.modules.pop("app.models.yolox_loader", None)
    importlib.import_module("app.models.yolox_loader")
    assert "onnxruntime" not in sys.modules
