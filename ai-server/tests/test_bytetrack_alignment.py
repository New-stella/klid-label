"""추적 ID ↔ 원본 검출 정렬 회귀 가드 (`bytetrack_util._apply_bytetrack`).

배경(실측):
ByteTrackTracker.update() 는 반환 순서가 입력 순서와 다르다 — 라이브러리 docstring 이
"Detection order may differ from input" 이라고 명시하고, 구현도
`고신뢰 매칭 → 저신뢰 매칭 → 미매칭 저신뢰 → 신규 트랙` 순으로 재정렬해 돌려준다.
따라서 원본 순서와 `zip` 으로 짝지으면 **서로 다른 객체의 track_id 가 맞바뀐다**.

이 파일은 "순서에 의존하지 않는 매칭"을 고정한다. 순서가 바뀌지 않는 트래커에서도
결과가 동일해야 하므로, 정상 순서 케이스도 함께 단언한다.
"""

from __future__ import annotations

import logging
from typing import Any

import pytest

from app.models.bytetrack_util import _apply_bytetrack
from app.models.detector_backend import DetectionResult

np = pytest.importorskip("numpy")
sv = pytest.importorskip("supervision")


# ────────────────────────────────────────────────────────────────────
# 가짜 트래커 — 출력 순서·탈락·좌표보정·태그유실을 재현한다
# ────────────────────────────────────────────────────────────────────

class _FakeTracker:
    """update() 가 지정한 순서(order)로 행을 돌려주는 가짜 트래커.

    - order: 반환할 원본 인덱스 순서 (일부만 담으면 나머지는 탈락)
    - ids: 그 순서에 대응하는 tracker_id
    - drop_data: True 면 sv.Detections 를 새로 만들어 data 태그를 버린다(기하 폴백 유도)
    - jitter: 반환 좌표에 더할 미세 보정치(px)
    """

    def __init__(
        self,
        order: list[int],
        ids: list[int],
        *,
        drop_data: bool = False,
        jitter: float = 0.0,
    ) -> None:
        self.order = order
        self.ids = ids
        self.drop_data = drop_data
        self.jitter = jitter
        self.last_input: Any = None

    def update(self, detections: Any) -> Any:
        self.last_input = detections
        idx = np.array(self.order, dtype=int)
        if self.drop_data:
            out = sv.Detections(
                xyxy=detections.xyxy[idx].copy(),
                confidence=detections.confidence[idx].copy(),
                class_id=detections.class_id[idx].copy(),
            )
        else:
            out = detections[idx]
        if self.jitter:
            out.xyxy = out.xyxy + self.jitter
        out.tracker_id = np.array(self.ids, dtype=int)
        return out


def _dets() -> list[DetectionResult]:
    """서로 멀리 떨어진 검출 3건 (person / car / person)."""
    return [
        DetectionResult(label="person", points=[0.0, 0.0, 10.0, 10.0], score=0.9),
        DetectionResult(label="car", points=[100.0, 100.0, 140.0, 140.0], score=0.8),
        DetectionResult(label="person", points=[300.0, 300.0, 310.0, 310.0], score=0.7),
    ]


# ────────────────────────────────────────────────────────────────────
# 핵심 — 순서가 바뀌어도 각 검출이 자기 ID 를 받는다
# ────────────────────────────────────────────────────────────────────

def test_추적기가_순서를_뒤집어_돌려줘도_각_검출이_자기_track_id를_받는다() -> None:
    """출력 순서 [2,0,1] + ids [7,5,6] → 원본 0→5, 1→6, 2→7."""
    dets = _dets()
    _apply_bytetrack(dets, _FakeTracker(order=[2, 0, 1], ids=[7, 5, 6]))

    assert [d.track_id for d in dets] == [5, 6, 7]


def test_순서가_바뀌지_않는_트래커에서도_결과가_동일하다() -> None:
    """정렬 로직 도입이 기존 정상 경로의 결과를 바꾸지 않는다(무해성)."""
    dets = _dets()
    _apply_bytetrack(dets, _FakeTracker(order=[0, 1, 2], ids=[5, 6, 7]))

    assert [d.track_id for d in dets] == [5, 6, 7]


def test_태그를_버리는_트래커에서도_좌표로_매칭해_자기_track_id를_받는다() -> None:
    """data 태그가 유실되면 좌표+클래스 기하 매칭으로 폴백한다."""
    dets = _dets()
    _apply_bytetrack(
        dets, _FakeTracker(order=[2, 0, 1], ids=[7, 5, 6], drop_data=True)
    )

    assert [d.track_id for d in dets] == [5, 6, 7]


def test_좌표가_미세_보정돼_돌아와도_기하_매칭된다() -> None:
    """트래커가 박스를 소폭 보정해 돌려줘도 허용오차 안이면 매칭된다."""
    dets = _dets()
    _apply_bytetrack(
        dets,
        _FakeTracker(order=[2, 0, 1], ids=[7, 5, 6], drop_data=True, jitter=0.4),
    )

    assert [d.track_id for d in dets] == [5, 6, 7]


def test_좌표가_크게_어긋나면_매칭하지_않고_track_id_미부여(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """허용오차를 넘으면 추측해서 붙이지 않는다(fail-closed) + 손실을 WARN 한다."""
    dets = _dets()
    with caplog.at_level(logging.WARNING):
        _apply_bytetrack(
            dets,
            _FakeTracker(order=[0, 1, 2], ids=[5, 6, 7], drop_data=True, jitter=80.0),
        )

    assert [d.track_id for d in dets] == [None, None, None]
    assert any("매칭하지 못함" in rec.getMessage() for rec in caplog.records)


# ────────────────────────────────────────────────────────────────────
# 클래스 / 부분 반환 / 정규화
# ────────────────────────────────────────────────────────────────────

def test_기하_매칭은_다른_클래스_검출에_track_id를_붙이지_않는다() -> None:
    """같은 자리에 겹쳐 있어도 클래스가 다르면 후보가 아니다."""
    dets = [
        DetectionResult(label="person", points=[0.0, 0.0, 10.0, 10.0], score=0.9),
        DetectionResult(label="car", points=[0.0, 0.0, 10.0, 10.0], score=0.8),
    ]
    # 트래커가 car 행 하나만 돌려준다 → person 에 붙으면 안 된다
    _apply_bytetrack(dets, _FakeTracker(order=[1], ids=[42], drop_data=True))

    assert dets[0].track_id is None
    assert dets[1].track_id == 42


def test_추적기가_일부만_돌려주면_나머지_검출은_track_id가_None이다(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """활성화 임계 미달 등으로 탈락한 검출은 지어내지 않고 None 으로 남긴다."""
    dets = _dets()
    with caplog.at_level(logging.WARNING):
        _apply_bytetrack(dets, _FakeTracker(order=[2], ids=[9]))

    assert [d.track_id for d in dets] == [None, None, 9]
    assert any("길이 불일치" in rec.getMessage() for rec in caplog.records)


def test_미확정_트랙_음수_id는_None으로_정규화된다() -> None:
    """ByteTrack 의 -1(미확정)은 track_id=None 이며, 그 정규화가 재정렬 뒤에도 유지된다."""
    dets = _dets()
    _apply_bytetrack(dets, _FakeTracker(order=[1, 2, 0], ids=[-1, 4, -1]))

    assert [d.track_id for d in dets] == [None, None, 4]


def test_트래커_예외는_밖으로_던지지_않는다() -> None:
    """graceful 계약 유지 — 실패해도 예외 전파 없이 track_id 미부여."""

    class _Boom:
        def update(self, detections: Any) -> Any:
            raise RuntimeError("boom")

    dets = _dets()
    _apply_bytetrack(dets, _Boom())  # 예외가 새면 여기서 실패

    assert [d.track_id for d in dets] == [None, None, None]


def test_빈_검출은_트래커를_호출하지_않는다() -> None:
    class _NeverCalled:
        def update(self, detections: Any) -> Any:  # pragma: no cover - 호출되면 실패
            raise AssertionError("빈 dets 에서 tracker.update 가 호출되면 안 된다")

    _apply_bytetrack([], _NeverCalled())


# ────────────────────────────────────────────────────────────────────
# 실제 ByteTrackTracker end-to-end (라이브러리 있을 때만)
# ────────────────────────────────────────────────────────────────────

def test_실제_ByteTrackTracker에서_다른_객체의_ID가_맞바뀌지_않는다() -> None:
    """실측 재현 시나리오.

    입력 [A(고신뢰) B(저신뢰) C(고신뢰)] 를 두 프레임 넣으면 2프레임째 반환 순서가
    [A, C, B] / tracker_id [n, n+1, -1] 로 온다. 위치 zip 이면 B 가 C 의 ID 를 받는다.
    """
    trackers_mod = pytest.importorskip("trackers")
    tracker = trackers_mod.ByteTrackTracker()

    for _ in range(2):
        dets = [
            DetectionResult(label="person", points=[0.0, 0.0, 10.0, 10.0], score=0.95),
            DetectionResult(label="person", points=[100.0, 100.0, 110.0, 110.0], score=0.30),
            DetectionResult(label="person", points=[200.0, 200.0, 210.0, 210.0], score=0.90),
        ]
        _apply_bytetrack(dets, tracker)

    a, b, c = dets
    # A·C 는 확정 트랙 → 서로 다른 ID / B 는 저신뢰 미확정 → None
    assert a.track_id is not None
    assert c.track_id is not None
    assert a.track_id != c.track_id
    assert b.track_id is None, "저신뢰 미확정 검출이 다른 객체의 track_id 를 받았다"
