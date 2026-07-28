"""
KPST 비식별화 진행/리포트 시뮬레이션 — 순수 로직.

경과초 기반 진행률(progressRate)을 KPST 코드값(prjState/procState)으로 매핑하고,
리포트용 얼굴/번호판 검출 수, 프레임 조회 mock 데이터, 시간 파싱을 담당한다.
DB/IO 없이 결정적(deterministic)으로 계산해 테스트가 용이하다.
"""

from __future__ import annotations

import logging
import os
import shutil
from datetime import datetime
from pathlib import Path
from typing import Optional

from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

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


# ── 더미 비식별 출력 파일 생성 ────────────────────────────────────
# 우리 BE(KpstDeidentService)는 완료 폴링 후 응답 fileName(=원본 입력 절대경로)의 basename 을
# {stem}-mask{ext} 로 바꿔 {export_path} 아래에서 회수한다(no-copy). 산출물은 실제 비식별 결과가
# 아니라 원본 복사본 또는 placeholder 다.
#
# ⚠ placeholder(18바이트)는 BE 무결성 검증(DeidentArtifactIntegrity: 크기 하한 + 컨테이너 시그니처)을
#   <b>통과하지 못한다</b>. 이는 의도된 동작이다 — 원본이 없는데 '비식별 완료'로 승인되면 안 된다.
#   정상 e2e 에서는 input_path 에 실제 원본이 있어 그 복사본(유효 영상)이 산출되고, 원본이 없는 경우는
#   BE 위탁 단계의 원본 실재 가드가 애초에 위탁을 거부한다.
PLACEHOLDER_BYTES: bytes = b"MOCK_DEIDENTIFIED\n"

# 마스킹 출력 파일명 규칙 — {원본stem}-mask{확장자} (실서버 실측 계약, 2026-07-21 curl/ll 확정).
# 예: 001.mp4 → 001-mask.mp4. 타임스탬프 세그먼트는 없다(구 목업 규칙 `{stem}_{ts}_mask{ext}` 폐기 —
# 실계약과 달라 BE 의 1차 회수 경로(toMaskName)가 로컬에서 한 번도 검증되지 않는 공백을 만들었다).
MASK_SUFFIX: str = "-mask"

# MOCK_OUTPUT_BASE 미설정 경고를 1회만 남기기 위한 플래그(로그 스팸 방지).
_base_unset_warned: bool = False


def reset_base_warning() -> None:
    """MOCK_OUTPUT_BASE 미설정 경고 플래그를 초기화한다(테스트용)."""
    global _base_unset_warned
    _base_unset_warned = False


def safe_basename(file_name: object) -> Optional[str]:
    """fileName 을 plain basename 으로 정화한다(CWE-22 경로 순회 방어).

    ``os.path.basename`` 으로 마지막 경로 요소만 취해 상위 탈출(``..``)/구분자를 제거하고,
    빈값/``.``/``..`` 이 남으면 None 을 반환해 호출측이 스킵하도록 한다. 끝의 슬래시는
    제거해 폴더 경로(이미지 폴더 모드의 input_path)도 basename 을 얻을 수 있게 한다.
    우리 BE 도 plain filename 만 허용(sanitizeFileName)하므로 정상 흐름에서는 값이 보존된다.
    """
    if not isinstance(file_name, str):
        return None
    base = os.path.basename(file_name.strip().rstrip("/\\"))
    if not base or base in (".", "..") or "/" in base or "\\" in base:
        return None
    return base


def mask_name_from(raw: object) -> Optional[str]:
    """원본 파일/폴더명을 basename 정화 후 ``{stem}-mask{ext}`` 로 조립한다(실서버 계약).

    basename 정화(CWE-22)를 마스킹명 조립보다 먼저 수행하므로 ``..``/구분자 입력은
    상위 요소만 남는다. 정화 실패(빈값/``.``/``..``)면 None.
    확장자가 없으면 ``-mask`` 만 붙는다(예: 폴더명 → ``imgfolder-mask``).

    우리 BE 의 ``KpstDeidentService.toMaskName`` 과 같은 규칙이다 — 목이 이 규칙을 따라야
    BE 의 1차 회수 경로가 로컬에서 실제로 검증된다.
    """
    base = safe_basename(raw)
    if base is None:
        return None
    stem, ext = os.path.splitext(base)
    return f"{stem}{MASK_SUFFIX}{ext}"


def source_path_of(input_path: str, base_name: str) -> str:
    """원본 입력파일의 <b>절대(경로형) 이름</b>을 만든다 — 진행/리포트 응답 ``fileName`` 값.

    실서버 계약(2026-07-21 curl/ll 실측): ``retrieve_progress`` 응답의 ``fileName`` 은 산출물명이
    아니라 **원본 입력파일 경로**(``input_path`` + 원본 basename)다. 산출물은 ``export_path`` 에
    ``{stem}-mask{ext}`` 로 생성된다.
    """
    return os.path.join(input_path, base_name)


def plan_outputs(raw_names: list[str]) -> list[tuple[str, str]]:
    """원본명 목록을 (원본 basename, 마스킹명) 쌍 목록으로 변환한다.

    라우터가 이 결과로 ①데이터셋 등록(fileName = 원본 입력 경로) ②실제 파일 쓰기(마스킹명)를
    수행한다. 정화 실패 항목은 제외한다.
    """
    plans: list[tuple[str, str]] = []
    for raw in raw_names:
        base = safe_basename(raw)
        if base is None:
            logger.warning(
                "[MOCK][KPST] deid output skip unsafe name=%s", sanitize_for_log(raw)
            )
            continue
        stem, ext = os.path.splitext(base)
        plans.append((base, f"{stem}{MASK_SUFFIX}{ext}"))
    return plans


def _is_within(path: Path, base: Path) -> bool:
    """path 가 base 하위(또는 동일)인지 검사한다."""
    try:
        path.relative_to(base)
        return True
    except ValueError:
        return False


def resolve_output_dir(export_path: str, output_base: str = "") -> Optional[Path]:
    """export_path 를 정규화하고 output_base 하위인지 검증한다(CWE-22 심층 방어).

    ``output_base`` 는 **콤마 구분 다중 base** 를 허용한다 — BE 의 co-locate 산출(Phase 5A)에서
    비식별 export_path 가 ``dirname(원본)/{rawSn}/deid/`` 라, 원본이 놓인 마운트 루트와 비식별
    저장소를 모두 허용해야 목이 산출물을 쓸 수 있다. BE 의 ``STORAGE_RAW_MOUNT_ROOTS`` 와
    **같은 값**으로 맞추는 것이 규약이다.

    **fail-closed**: output_base 가 비어있으면 임의 절대경로 쓰기를 막기 위해 None 을
    반환한다(HIGH-1). base 가 설정된 경우에만, resolve 후 그 base 중 하나의 하위인 export_path 만 허용.

    Returns:
        정규화된 출력 디렉터리 Path. base 미설정/정규화 실패/모든 base 밖이면 None.
    """
    bases = [b.strip() for b in output_base.split(",") if b.strip()]
    if not bases:
        return None
    try:
        export_resolved = Path(export_path).resolve()
    except (OSError, ValueError):
        return None
    for base in bases:
        try:
            base_resolved = Path(base).resolve()
        except (OSError, ValueError):
            continue
        if _is_within(export_resolved, base_resolved):
            return export_resolved
    return None


def _safe_source_path(input_path: str, base_name: str) -> Optional[Path]:
    """{input_path}/{base_name} 복사 소스 경로를 정화 후 반환한다.

    입력 디렉터리 밖으로 탈출하는 경로는 None 을 반환해 복사를 막는다.
    """
    try:
        in_dir = Path(input_path).resolve()
        src = (in_dir / base_name).resolve()
    except (OSError, ValueError):
        return None
    if not _is_within(src, in_dir):
        return None
    return src


def _create_exclusive(target: Path) -> Optional[int]:
    """O_EXCL 로 파일을 원자적으로 생성한다(HIGH-2 덮어쓰기 방지).

    이미 존재하면 FileExistsError → None 반환(호출측 skip). 성공 시 fd 반환.
    """
    try:
        return os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    except FileExistsError:
        return None


def _write_placeholder(target: Path) -> bool:
    """비어있지 않은 placeholder 파일을 O_EXCL 로 쓴다. 이미 있으면 False(skip)."""
    fd = _create_exclusive(target)
    if fd is None:
        return False
    with os.fdopen(fd, "wb") as f:
        f.write(PLACEHOLDER_BYTES)
    return True


def _copy_no_overwrite(src: Path, target: Path) -> bool:
    """원본을 target 으로 복사하되 O_EXCL 로 기존 파일을 덮어쓰지 않는다. 이미 있으면 False."""
    fd = _create_exclusive(target)
    if fd is None:
        return False
    with os.fdopen(fd, "wb") as dst, open(src, "rb") as source:
        shutil.copyfileobj(source, dst)
    return True


def _write_one_output(target: Path, source_base: str, input_path: str) -> None:
    """단일 출력 파일을 생성한다 — 원본 있으면 복사, 없으면 placeholder.

    HIGH-2: target 이 이미 존재하면 덮어쓰지 않고 skip + 로그(멱등 재실행 안전).
    개별 파일 쓰기 실패는 예외를 삼켜 로그만 남긴다(POST /project 응답에 영향 금지).
    """
    if target.exists():
        logger.info(
            "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
            sanitize_for_log(target.name),
        )
        return
    try:
        src = _safe_source_path(input_path, source_base)
        if src is not None and src.is_file() and os.access(src, os.R_OK):
            if not _copy_no_overwrite(src, target):
                logger.info(
                    "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
                    sanitize_for_log(target.name),
                )
        elif not _write_placeholder(target):
            logger.info(
                "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
                sanitize_for_log(target.name),
            )
    except OSError as exc:  # 권한/디스크/경로 문제 등 — 격리
        logger.warning(
            "[MOCK][KPST] deid output write failed file=%s err=%s",
            sanitize_for_log(target.name),
            sanitize_for_log(str(exc)),
        )


def write_deid_outputs(
    *,
    export_path: str,
    input_path: str,
    outputs: list[tuple[str, str]],
    output_base: str = "",
) -> list[Path]:
    """POST /project 시 더미 비식별 출력 파일을 생성한다(best-effort).

    ``outputs`` 는 ``(원본 basename, 마스킹명)`` 쌍 목록으로, ``{export_path}/{마스킹명}`` 에
    생성한다(원본 있으면 복사, 없으면 placeholder). 마스킹명은 ``{stem}-mask{ext}`` 규칙이라
    BE 가 진행조회 ``fileName``(원본 입력 경로)의 basename 을 같은 규칙으로 변환해 회수한다.

    보안:
    - HIGH-1 fail-closed: ``output_base`` 미설정 시 어떤 파일도 쓰지 않고 1회 warn 후 반환.
    - HIGH-2 no-overwrite: 기존 파일은 O_EXCL 로 덮어쓰지 않는다.

    모든 쓰기 실패(디렉터리 생성/파일 쓰기)는 예외를 삼켜 로그만 남기며 절대 전파하지 않는다.

    Returns:
        생성/존재가 확인된 파일 경로 목록(테스트/디버깅용).
    """
    written: list[Path] = []

    if not output_base:
        global _base_unset_warned
        if not _base_unset_warned:
            logger.warning(
                "[MOCK][KPST] MOCK_OUTPUT_BASE 미설정 — 더미 비식별 출력 파일 미생성"
                "(fail-closed). e2e 시 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값(콤마 구분)으로 설정하세요."
            )
            _base_unset_warned = True
        return written

    out_dir = resolve_output_dir(export_path, output_base)
    if out_dir is None:
        logger.warning(
            "[MOCK][KPST] deid output dir rejected export_path=%s output_base=%s",
            sanitize_for_log(export_path),
            sanitize_for_log(output_base),
        )
        return written

    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:  # 경로가 파일로 점유됨/권한 등 — 격리
        logger.warning(
            "[MOCK][KPST] deid output mkdir failed dir=%s err=%s",
            sanitize_for_log(str(out_dir)),
            sanitize_for_log(str(exc)),
        )
        return written

    for source_base, mask_name in outputs:
        target = out_dir / mask_name
        # 심층 방어 — 최종 경로가 out_dir 하위인지 재확인(마스킹명 조립 이후에도 한 번 더)
        try:
            if not _is_within(target.resolve(), out_dir):
                logger.warning(
                    "[MOCK][KPST] deid output skip escaping fileName=%s",
                    sanitize_for_log(mask_name),
                )
                continue
        except (OSError, ValueError):
            continue
        _write_one_output(target, source_base, input_path)
        if target.is_file():
            written.append(target)
    return written
