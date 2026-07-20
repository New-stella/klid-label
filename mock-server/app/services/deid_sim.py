"""
KPST 비식별화 진행/리포트 시뮬레이션 — 순수 로직.

경과초 기반 진행률(progressRate)을 KPST 코드값(prjState/procState)으로 매핑하고,
리포트용 얼굴/번호판 검출 수, 프레임 조회 mock 데이터, 시간 파싱을 담당한다.
DB/IO 없이 결정적(deterministic)으로 계산해 테스트가 용이하다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

# 프로젝트 상태 코드 (prjState): 0=생성,1=대기,2=실행중,3=완료,4=중지,5=오류,6=정지
PRJ_STATE_WAITING = 1
PRJ_STATE_RUNNING = 2
PRJ_STATE_DONE = 3

# 데이터셋 처리 상태 코드 (procState): 0=대기,1=실행중,2=완료,3=중지,4=삭제중,99=오류
PROC_STATE_WAITING = 0
PROC_STATE_RUNNING = 1
PROC_STATE_DONE = 2

# mock 마스킹 이미지 — 1x1 투명 PNG (base64). 프레임마다 이미지/null 교대.
MOCK_PNG_1X1 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
    "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)

DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"


def format_dt(epoch: Optional[float]) -> Optional[str]:
    """epoch(초)을 'YYYY-MM-DD HH:MM:SS' 문자열로 변환한다. None 은 그대로 None."""
    if epoch is None:
        return None
    return datetime.fromtimestamp(epoch).strftime(DATETIME_FORMAT)


def prj_state_from_rate(rate: float) -> int:
    """진행률 → 프로젝트 상태 코드."""
    if rate >= 100.0:
        return PRJ_STATE_DONE
    if rate <= 0.0:
        return PRJ_STATE_WAITING
    return PRJ_STATE_RUNNING


def proc_state_from_rate(rate: float) -> int:
    """진행률 → 데이터셋 처리 상태 코드."""
    if rate >= 100.0:
        return PROC_STATE_DONE
    if rate <= 0.0:
        return PROC_STATE_WAITING
    return PROC_STATE_RUNNING


def total_frame_for(dataset_id: int) -> int:
    """데이터셋의 총 프레임 수(결정적 mock 값)."""
    return 300 + (dataset_id * 60) % 900


def face_count_for(dataset_id: int, total_frame: int) -> int:
    """리포트용 검출 얼굴 수(결정적 mock 집계)."""
    return (dataset_id * 7 + total_frame) % 50


def lp_count_for(dataset_id: int, total_frame: int) -> int:
    """리포트용 검출 번호판(license plate) 수(결정적 mock 집계)."""
    return (dataset_id * 3 + total_frame) % 20


def parse_frame_no(frame_no: Optional[str], total_frame: int) -> tuple[int, int]:
    """frame_no 필터를 [start, end] 범위로 파싱한다.

    - None/빈문자열 → 전체(1..total_frame)
    - 단일 '120' → (120, 120)
    - 범위 '100-200' → (100, 200)

    Raises:
        ValueError: 형식이 잘못됐거나 start > end 인 경우.
    """
    if frame_no is None or frame_no.strip() == "":
        return (1, max(1, total_frame))

    text = frame_no.strip()
    if "-" in text:
        parts = text.split("-")
        if len(parts) != 2:
            raise ValueError("invalid frame_no range")
        start_s, end_s = parts[0].strip(), parts[1].strip()
        if not (start_s.isdigit() and end_s.isdigit()):
            raise ValueError("invalid frame_no range")
        start, end = int(start_s), int(end_s)
        if start > end:
            raise ValueError("invalid frame_no range")
        return (start, end)

    if not text.isdigit():
        raise ValueError("invalid frame_no")
    single = int(text)
    return (single, single)


def build_frames(dataset_id: int, total_frame: int, frame_no: Optional[str]) -> list[dict]:
    """dataset_frames 응답용 mock 프레임 리스트를 생성한다.

    이미지는 프레임마다 base64 PNG / null 을 교대로 채워 '마스킹 이미지 없음' 케이스도
    표현한다. bbox 는 mock 좌표 문자열.

    Raises:
        ValueError: frame_no 형식 오류(parse_frame_no 전파).
    """
    start, end = parse_frame_no(frame_no, total_frame)
    # 데이터셋 범위로 클램프 — 과도한 크기 응답 방지(CWE-400)
    start = max(1, start)
    end = min(end, max(1, total_frame))
    if start > end:
        return []

    frames: list[dict] = []
    for n in range(start, end + 1):
        has_image = (n % 2 == 0)
        frames.append(
            {
                "frame_no": n,
                "image": MOCK_PNG_1X1 if has_image else None,
                "bbox": f"[{n % 100},{(n * 2) % 100},30,40]",
            }
        )
    return frames


def parse_log_time(value: str) -> datetime:
    """작업 로그 시간 문자열('YYYY-MM-DD HH:MM:SS')을 파싱한다.

    Raises:
        ValueError: 형식 오류.
    """
    return datetime.strptime(value.strip(), DATETIME_FORMAT)
