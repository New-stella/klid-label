"""ByteTrack(roboflow `trackers`) 공용 헬퍼 — YOLOX 탐지 백엔드 트래킹.

모듈 레벨 private 헬퍼(`_new_bytetrack_tracker`, `_apply_bytetrack`)를
YOLOX 백엔드의 트래킹(track_id 부여)에서 사용한다.

성능/마이그레이션 배경:
- supervision 0.30 에서 제거 예정인 sv.ByteTrack(update_with_detections) 대신
  roboflow `trackers` 패키지(Apache-2.0)의 ByteTrackTracker.update() 를 사용한다.
- 입력 detection 변환에는 여전히 supervision 의 sv.Detections 를 사용한다(trackers 호환).
- ByteTrackTracker.update() 는 sv.Detections 를 그대로 반환하며 tracker_id 의 -1 은
  '미확정 트랙'을 의미하므로 track_id=None 으로 매핑한다.

Critical:
- supervision/trackers 는 무거우므로 **반드시 lazy import** (모듈 최상단 import 금지).
- import/로드 실패 시 크래시 금지 → None 반환(track_id 미부여) graceful fallback.
- `_tracker_unavailable_warned` WARN-once: 미설치 환경에서 clip 마다 반복 경고하지 않고
  프로세스당 1회만 출력한다.
- **추적 결과를 위치(zip)로 짝짓지 않는다.** ByteTrackTracker.update() 는 반환 순서가
  입력 순서와 다르다(라이브러리 docstring 명시: "Detection order may differ from input").
  구현상 출력은 `고신뢰 매칭 → 저신뢰 매칭 → 미매칭 저신뢰 → 신규 트랙` 순으로 재정렬되고
  활성화 임계 미달 검출은 아예 빠진다. 따라서 원본 순서와 `zip` 하면 **서로 다른 객체의
  track_id 가 맞바뀐다**(실측: 입력 [A,B,C,D] → 출력 [A,C,B] + tracker_id [0,1,-1] 이라
  B 가 C 의 ID 를 받는다). 아래 `_apply_bytetrack` 은 순서에 의존하지 않고 "무엇인지"로
  매칭한다 — 순서가 바뀌지 않는 트래커에서도 결과는 동일하다.
"""

from __future__ import annotations

import logging
from typing import Any

from app.models.detector_backend import (
    DetectionResult,
    coco_id_from_label,
)

logger = logging.getLogger(__name__)

# ByteTrackTracker import 실패 WARN-once.
# trackers 미설치 등으로 생성 실패 시 매 호출 WARN 하지 않고 프로세스당 1회만 출력한다.
_tracker_unavailable_warned: bool = False

# 원본 검출 인덱스 태그 키 — sv.Detections.data 에 실어 보내면 트래커가 행을 재정렬·탈락시켜도
# 어느 원본 검출인지 정확히(허용오차 없이) 복원된다. supervision 의 Detections 슬라이싱이
# data 배열을 함께 슬라이싱하므로 성립한다(실측 확인).
_ORIG_INDEX_KEY = "_klid_orig_index"

# 기하 폴백 매칭 허용오차 — 트래커가 data 를 버리는 경우에만 사용한다.
# 좌표는 부동소수이고 트래커가 박스를 미세 보정해 돌려줄 수 있으므로 완전일치를 전제하지 않는다.
# 절대 하한(px) + 박스 크기 비례 중 큰 값을 쓴다.
_MATCH_ABS_TOL: float = 1.0
_MATCH_REL_TOL: float = 0.02


def _new_bytetrack_tracker() -> Any | None:
    """roboflow `trackers` 의 ByteTrackTracker 인스턴스 1개를 생성.

    미설치/로드실패 시 None 반환(graceful — track_id 미부여). lazy import 라
    기본 yolo 경로는 trackers 를 import 하지 않는다.
    """
    global _tracker_unavailable_warned
    try:
        from trackers import ByteTrackTracker  # noqa: WPS433 (lazy)

        return ByteTrackTracker()
    except Exception as exc:  # noqa: BLE001 — trackers 미설치 시 graceful fallback
        # WARN-once: 미설치 환경에서 clip 마다 반복 경고하지 않고 프로세스당 1회만.
        if not _tracker_unavailable_warned:
            logger.warning(
                "[ByteTrack] trackers(ByteTrackTracker) unavailable type=%s — track_id 미부여",
                type(exc).__name__,
            )
            _tracker_unavailable_warned = True
        return None


def _match_tolerance(box: list[float]) -> float:
    """기하 폴백 매칭의 좌표 허용오차 — 절대 하한 + 박스 크기 비례.

    트래커가 좌표를 미세 보정해 돌려주는 경우를 흡수하되, 전혀 다른 객체까지 끌어오지
    않도록 박스 크기의 소수 비율로 제한한다.
    """
    size = max(abs(box[2] - box[0]), abs(box[3] - box[1]))
    return max(_MATCH_ABS_TOL, _MATCH_REL_TOL * size)


def _orig_indices_from_tag(tracked: Any, rows: int, n_dets: int) -> list[int] | None:
    """추적 결과의 원본 인덱스 태그(`_ORIG_INDEX_KEY`)로 정확 매핑을 복원한다.

    태그가 없거나(트래커가 data 를 버림) 값이 유효하지 않으면 None 을 돌려
    호출자가 기하 폴백으로 넘어가게 한다. 검증: 길이 일치 · 범위 내 정수 · 중복 없음(1:1).
    """
    data = getattr(tracked, "data", None)
    if not isinstance(data, dict):
        return None
    raw = data.get(_ORIG_INDEX_KEY)
    if raw is None:
        return None
    try:
        tagged = [int(value) for value in raw]
    except (TypeError, ValueError):
        return None
    if len(tagged) != rows:
        return None
    seen: set[int] = set()
    for idx in tagged:
        if idx < 0 or idx >= n_dets or idx in seen:
            return None
        seen.add(idx)
    return tagged


def _orig_indices_from_geometry(
    tracked: Any, dets: list[DetectionResult], rows: int
) -> list[int | None]:
    """좌표(+클래스)로 추적 결과 행을 원본 검출에 1:1 매칭한다 (태그 유실 시 폴백).

    - 클래스가 다르면 후보에서 제외한다(클래스별 트랙 공간 분리 의도와 동일 축).
    - 후보 중 좌표 편차가 가장 작은 것 하나만 고르고, 허용오차를 넘으면 매칭 실패로 둔다
      (틀린 ID 를 붙이느니 미부여가 낫다 — fail-closed).
    - 이미 매칭된 원본 검출은 재사용하지 않는다(1:1).
    """
    boxes = getattr(tracked, "xyxy", None)
    if boxes is None:
        return [None] * rows
    class_ids = getattr(tracked, "class_id", None)
    det_classes = [coco_id_from_label(det.label) for det in dets]

    used: set[int] = set()
    resolved: list[int | None] = []
    for row in range(rows):
        try:
            box = [float(value) for value in boxes[row]]
        except (IndexError, TypeError, ValueError):
            resolved.append(None)
            continue
        if len(box) != 4:
            resolved.append(None)
            continue
        want_class: int | None = None
        if class_ids is not None:
            try:
                want_class = int(class_ids[row])
            except (IndexError, TypeError, ValueError):
                want_class = None

        best: int | None = None
        best_dev = float("inf")
        for j, det in enumerate(dets):
            if j in used:
                continue
            if want_class is not None and det_classes[j] != want_class:
                continue
            points = det.points
            if points is None or len(points) != 4:
                continue
            try:
                dev = max(abs(float(points[k]) - box[k]) for k in range(4))
            except (TypeError, ValueError):
                continue
            if dev < best_dev:
                best_dev = dev
                best = j

        if best is not None and best_dev <= _match_tolerance(box):
            used.add(best)
            resolved.append(best)
        else:
            resolved.append(None)
    return resolved


def _apply_bytetrack(dets: list[DetectionResult], tracker: Any) -> None:
    """dets 를 sv.Detections 로 변환 → tracker.update() → track_id 를 in-place 부여.

    - 입력 변환에는 supervision sv.Detections 를 계속 사용(trackers 호환).
    - ByteTrackTracker.update() 는 sv.Detections 를 반환하며 tracker_id 의 -1 은
      '미확정 트랙'이므로 None 으로 매핑한다.
    - **위치(zip)로 짝짓지 않는다.** 추적 결과 행 → 원본 검출 매핑은
      ① 원본 인덱스 태그(정확) ② 좌표+클래스 기하 매칭(허용오차, 태그 유실 시 폴백)
      순으로 해석한다. 순서가 바뀌지 않는 트래커에서도 결과는 동일하다.
    - 길이 불일치(필터링 등) · 매칭 실패는 WARN + 해당 검출 track_id=None 유지
      (조용한 오배정/손실 방지).
    - 실패는 graceful (track_id 미부여) — 예외를 밖으로 던지지 않는다.
    """
    if not dets:
        return
    try:
        import numpy as np  # noqa: WPS433 (lazy)
        import supervision as sv  # noqa: WPS433 (lazy)

        # class_id 를 라벨 기반 안정 정수로 매핑 — ByteTrackTracker 가 클래스별 트랙 공간을
        # 분리하는 경우 person/car 등 다른 클래스의 track ID 가 충돌하지 않도록 한다(CCTV 다중 클래스).
        sv_dets = sv.Detections(
            xyxy=np.array([d.points for d in dets], dtype=float),
            confidence=np.array([d.score for d in dets], dtype=float),
            class_id=np.array([coco_id_from_label(d.label) for d in dets], dtype=int),
            # 원본 인덱스 태그 — 트래커가 행을 재정렬·탈락시켜도 정확 복원이 가능하다.
            data={_ORIG_INDEX_KEY: np.arange(len(dets))},
        )
        tracked = tracker.update(sv_dets)
        tracker_ids = (
            list(tracked.tracker_id)
            if getattr(tracked, "tracker_id", None) is not None
            else []
        )
        rows = len(tracker_ids)
        if rows != len(dets):
            logger.warning(
                "[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d "
                "— 누락분 track_id=None 유지",
                len(dets),
                rows,
            )

        tagged = _orig_indices_from_tag(tracked, rows, len(dets))
        indices: list[int | None]
        if tagged is None:
            logger.debug("[ByteTrack] 원본 인덱스 태그 유실 — 좌표 기반 매칭으로 폴백")
            indices = _orig_indices_from_geometry(tracked, dets, rows)
        else:
            indices = list(tagged)

        assigned = 0
        for det_idx, tid in zip(indices, tracker_ids):
            if det_idx is None:  # 원본 검출을 특정하지 못함 → 부여하지 않는다
                continue
            # ByteTrackTracker 는 미확정 트랙에 -1 을 부여한다 → None 으로 정규화
            dets[det_idx].track_id = int(tid) if tid is not None and int(tid) >= 0 else None
            assigned += 1

        unresolved = rows - assigned
        if unresolved > 0:
            logger.warning(
                "[ByteTrack] 추적 결과 %d/%d 건을 원본 검출에 매칭하지 못함 — track_id 미부여",
                unresolved,
                rows,
            )
    except Exception as exc:  # noqa: BLE001 — 트래킹 실패는 graceful (track_id 미부여)
        logger.warning("[ByteTrack] bytetrack update failed type=%s", type(exc).__name__)


def reset_tracker_unavailable_warned() -> None:
    """테스트/리셋용 — WARN-once 플래그 초기화."""
    global _tracker_unavailable_warned
    _tracker_unavailable_warned = False
