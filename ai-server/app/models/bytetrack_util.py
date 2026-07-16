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


def _apply_bytetrack(dets: list[DetectionResult], tracker: Any) -> None:
    """dets 를 sv.Detections 로 변환 → tracker.update() → track_id 를 in-place 부여.

    - 입력 변환에는 supervision sv.Detections 를 계속 사용(trackers 호환).
    - ByteTrackTracker.update() 는 sv.Detections 를 반환하며 tracker_id 의 -1 은
      '미확정 트랙'이므로 None 으로 매핑한다.
    - 길이 불일치(필터링 등)는 WARN + 누락분 track_id=None 보존(조용한 truncate 방지).
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
        )
        tracked = tracker.update(sv_dets)
        tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
        if len(tracker_ids) != len(dets):
            logger.warning(
                "[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d "
                "— 누락분 track_id=None 유지",
                len(dets),
                len(tracker_ids),
            )
        for det, tid in zip(dets, tracker_ids):  # 짧은 쪽 길이만큼만 매핑
            # ByteTrackTracker 는 미확정 트랙에 -1 을 부여한다 → None 으로 정규화
            det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None
    except Exception as exc:  # noqa: BLE001 — 트래킹 실패는 graceful (track_id 미부여)
        logger.warning("[ByteTrack] bytetrack update failed type=%s", type(exc).__name__)


def reset_tracker_unavailable_warned() -> None:
    """테스트/리셋용 — WARN-once 플래그 초기화."""
    global _tracker_unavailable_warned
    _tracker_unavailable_warned = False
