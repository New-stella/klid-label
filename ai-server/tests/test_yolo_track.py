"""YOLO Track 라우터 테스트 (Phase 2).

POST /infer/yolo/track:
- ultralytics model.track() 기반 객체 트래킹
- clip_id 별 트래커 상태 격리 (LRU 캐시 10개 + TTL 5분)
- frame_index=0 이면 트래커 리셋, 그 외 persist=True
- mock 모드: env_mock → 결정적 track_id=1 person 박스 / weights_missing → 빈 detections

테스트는 ultralytics 호출 비용을 피하기 위해 mock 모드(conftest 의 AI_MOCK_MODE=true)
+ monkeypatch 로 실제 트래커 인스턴스를 가짜 객체로 대체한다.
"""

from __future__ import annotations

import time
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import yolo_loader

client = TestClient(app)


# ────────────────────────────────────────────────────────────────────
# 헬퍼: 가짜 트래커 인스턴스 (ultralytics YOLO.track 의 응답 형태를 흉내)
# ────────────────────────────────────────────────────────────────────

class _FakeBoxes:
    """ultralytics Results.boxes 의 최소 인터페이스."""

    def __init__(self, xyxy: list[list[float]], cls: list[int], conf: list[float], ids: list[int] | None):
        # tensor-like: list of list[float] / list[int]
        self.xyxy = xyxy
        self.cls = cls
        self.conf = conf
        self.id = ids  # None 또는 list[int]

    def __len__(self) -> int:
        return len(self.xyxy)


class _Tensor1D(list):
    """torch.Tensor 처럼 tolist() 지원 + 인덱싱 가능한 1D 시퀀스."""

    def tolist(self):
        return list(self)


class _FakeResult:
    def __init__(self, xyxy: list[list[float]], cls: list[int], conf: list[float], ids: list[int] | None, names: dict[int, str]):
        # xyxy 각 요소는 .tolist() 를 지원해야 함
        wrapped_xyxy = [_Tensor1D(b) for b in xyxy]
        self.boxes = _FakeBoxes(wrapped_xyxy, cls, conf, ids)
        self.names = names


class _FakeTracker:
    """ultralytics YOLO 인스턴스의 .track(...) 만 흉내내는 가짜 객체.

    각 호출의 frame_index 에 따라 결정적인 응답을 반환하도록 init 시 콜백 주입.
    """

    def __init__(self, response_fn):
        self._response_fn = response_fn
        self.call_count = 0
        self.last_persist: bool | None = None
        self.last_tracker_arg: str | None = None

    def track(self, img, conf, imgsz, iou, persist, tracker, verbose):  # noqa: D401, PLR0913
        self.call_count += 1
        self.last_persist = persist
        self.last_tracker_arg = tracker
        # ultralytics .track() 은 Results 객체의 리스트를 반환
        return [self._response_fn(self.call_count)]


def _person_one_box_with_id(track_id: int) -> _FakeResult:
    return _FakeResult(
        xyxy=[[10.0, 20.0, 30.0, 40.0]],
        cls=[0],
        conf=[0.85],
        ids=[track_id],
        names={0: "person"},
    )


# ────────────────────────────────────────────────────────────────────
# Mock 응답 케이스 (env_mock — conftest 가 AI_MOCK_MODE=true 적용)
# ────────────────────────────────────────────────────────────────────

def test_track_mock_모드_결정적_track_id_1_반환(small_png_b64: str) -> None:
    """AI_MOCK_MODE=true 환경에서는 결정적 track_id=1 person 박스 반환."""
    res = client.post(
        "/infer/yolo/track",
        json={
            "image_b64": small_png_b64,
            "clip_id": "video-A",
            "frame_index": 0,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "env_mock"
    assert len(body["detections"]) == 1
    det = body["detections"][0]
    assert det["label"] == "person"
    assert det["track_id"] == 1
    assert 0.0 <= det["score"] <= 1.0
    assert len(det["points"]) == 4


def test_track_weights_missing_빈_detections(small_png_b64: str, monkeypatch: pytest.MonkeyPatch) -> None:
    """weights_missing 사유 mock 응답은 detections 빈 배열 + track_id 없음."""
    # get_yolo_tracker() 가 weights_missing 사유로 None 반환하도록 패치
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: "weights_missing")

    res = client.post(
        "/infer/yolo/track",
        json={
            "image_b64": small_png_b64,
            "clip_id": "video-B",
            "frame_index": 0,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "weights_missing"
    assert body["detections"] == []


# ────────────────────────────────────────────────────────────────────
# LRU 캐시 / TTL / 격리 동작 (yolo_loader 단위 테스트 — 가짜 인스턴스 주입)
# ────────────────────────────────────────────────────────────────────

def test_track_같은_clip_id_의_연속_프레임은_같은_트래커_인스턴스_재사용(monkeypatch: pytest.MonkeyPatch) -> None:
    """frame_index>0 호출 시 같은 clip_id 의 트래커 인스턴스가 재사용된다."""
    yolo_loader.reset_yolo_trackers()
    # 정상 모델 로드를 흉내내기 위해 mock 사유를 None 으로
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    created: list[_FakeTracker] = []

    def _factory():
        t = _FakeTracker(lambda n: _person_one_box_with_id(7))
        created.append(t)
        return t

    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", _factory)

    m0 = yolo_loader.get_yolo_tracker("video-X", reset=True)
    m1 = yolo_loader.get_yolo_tracker("video-X", reset=False)
    m2 = yolo_loader.get_yolo_tracker("video-X", reset=False)

    assert m0 is m1 is m2
    assert len(created) == 1


def test_track_frame_index_0_이면_트래커_재초기화(monkeypatch: pytest.MonkeyPatch) -> None:
    """reset=True (frame_index=0) 호출은 새 인스턴스를 생성한다."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    created: list[_FakeTracker] = []

    def _factory():
        t = _FakeTracker(lambda n: _person_one_box_with_id(1))
        created.append(t)
        return t

    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", _factory)

    m0 = yolo_loader.get_yolo_tracker("video-Y", reset=True)
    m1 = yolo_loader.get_yolo_tracker("video-Y", reset=True)  # 재초기화

    assert m0 is not m1
    assert len(created) == 2


def test_track_다른_clip_id_는_트래커_인스턴스_독립(monkeypatch: pytest.MonkeyPatch) -> None:
    """clip_id 가 다르면 트래커 인스턴스가 격리된다."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    created: list[_FakeTracker] = []

    def _factory():
        t = _FakeTracker(lambda n: _person_one_box_with_id(1))
        created.append(t)
        return t

    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", _factory)

    a = yolo_loader.get_yolo_tracker("video-A", reset=True)
    b = yolo_loader.get_yolo_tracker("video-B", reset=True)
    a2 = yolo_loader.get_yolo_tracker("video-A", reset=False)

    assert a is a2
    assert a is not b
    assert len(created) == 2


def test_track_LRU_캐시_max_초과시_가장_오래된_clip_제거(monkeypatch: pytest.MonkeyPatch) -> None:
    """_MAX_TRACKERS(=10) 초과 시 가장 오래된 entry 가 제거된다."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    monkeypatch.setattr(yolo_loader, "_MAX_TRACKERS", 3)

    def _factory():
        return _FakeTracker(lambda n: _person_one_box_with_id(1))

    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", _factory)

    a = yolo_loader.get_yolo_tracker("A", reset=True)
    b = yolo_loader.get_yolo_tracker("B", reset=True)
    c = yolo_loader.get_yolo_tracker("C", reset=True)
    # max=3 시점: A,B,C 모두 캐시 in
    d = yolo_loader.get_yolo_tracker("D", reset=True)  # 가장 오래된 A 제거

    cache_keys = list(yolo_loader._TRACKERS.keys())
    assert "A" not in cache_keys
    assert set(cache_keys) == {"B", "C", "D"}

    # A 재호출은 새 인스턴스
    a2 = yolo_loader.get_yolo_tracker("A", reset=False)
    assert a2 is not a


def test_track_TTL_만료_clip_id_는_재호출_시_새_트래커(monkeypatch: pytest.MonkeyPatch) -> None:
    """_TRACKER_TTL_SEC 초과 entry 는 lazy expiration 으로 제거되고 새로 생성된다."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    monkeypatch.setattr(yolo_loader, "_TRACKER_TTL_SEC", 1)  # 1초로 단축

    def _factory():
        return _FakeTracker(lambda n: _person_one_box_with_id(1))

    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", _factory)

    first = yolo_loader.get_yolo_tracker("video-T", reset=True)
    # 캐시 timestamp 를 강제로 과거로 이동
    with yolo_loader._TRACKERS_LOCK:
        inst, _ts = yolo_loader._TRACKERS["video-T"]
        yolo_loader._TRACKERS["video-T"] = (inst, time.time() - 10.0)

    second = yolo_loader.get_yolo_tracker("video-T", reset=False)

    assert second is not first


def test_track_mock_사유시_get_yolo_tracker_는_None_반환(monkeypatch: pytest.MonkeyPatch) -> None:
    """mock 모드 (env_mock / weights_missing / load_failed) 에서는 None 반환."""
    yolo_loader.reset_yolo_trackers()

    for reason in ("env_mock", "weights_missing", "load_failed"):
        monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda r=reason: r)
        assert yolo_loader.get_yolo_tracker("video-Z", reset=True) is None


# ────────────────────────────────────────────────────────────────────
# 엔드포인트 — 실제 트래커 인스턴스 응답 흐름 (가짜 인스턴스 주입)
# ────────────────────────────────────────────────────────────────────

def test_track_엔드포인트_같은_clip_id_연속_프레임_같은_track_id_유지(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """엔드포인트가 frame_index=0,1,2 호출에서 동일 track_id 를 보존한다."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)
    fake = _FakeTracker(lambda n: _person_one_box_with_id(42))
    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", lambda: fake)

    res0 = client.post("/infer/yolo/track", json={
        "image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 0,
    })
    res1 = client.post("/infer/yolo/track", json={
        "image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 1,
    })
    res2 = client.post("/infer/yolo/track", json={
        "image_b64": small_png_b64, "clip_id": "vid-1", "frame_index": 2,
    })

    for r in (res0, res1, res2):
        assert r.status_code == 200
        body = r.json()
        assert body["mock"] is False
        assert body["source"] == "model"
        assert body["detections"][0]["track_id"] == 42

    # frame_index>0 호출은 persist=True
    # 0번 호출은 persist=False 였어야 하지만 fake 는 마지막 호출만 기록 — call_count 로만 확인
    assert fake.call_count == 3
    assert fake.last_persist is True  # 마지막 호출 (frame_index=2)
    assert fake.last_tracker_arg == "botsort.yaml"


def test_track_엔드포인트_track_id_None_도_허용(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """boxes.id is None 인 경우 track_id=None 으로 직렬화."""
    yolo_loader.reset_yolo_trackers()
    monkeypatch.setattr(yolo_loader, "get_yolo_mock_reason", lambda: None)

    def _result_no_id(_n: int) -> _FakeResult:
        return _FakeResult(
            xyxy=[[1.0, 2.0, 3.0, 4.0]],
            cls=[0],
            conf=[0.4],
            ids=None,  # 저신뢰 detection — 트래커가 ID 부여 X
            names={0: "person"},
        )

    fake = _FakeTracker(_result_no_id)
    monkeypatch.setattr(yolo_loader, "_create_fresh_tracker", lambda: fake)

    res = client.post("/infer/yolo/track", json={
        "image_b64": small_png_b64, "clip_id": "vid-noid", "frame_index": 0,
    })
    assert res.status_code == 200
    body = res.json()
    assert body["detections"][0]["track_id"] is None
