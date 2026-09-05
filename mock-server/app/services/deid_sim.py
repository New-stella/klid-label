"""
KPST 비식별화 진행/리포트 시뮬레이션 + **비동기 산출 러너**.

★ 구속 원칙 — <b>외부연동은 모두 비동기다</b>(목 서버 전체 아키텍처 원칙).
HTTP 요청 처리는 외부 작업(ffmpeg 인코딩·파일 복사·ffprobe)의 완료를 <b>기다리지 않는다</b>.
``POST /project`` 는 접수만 하고 즉시 반환하며, 실제 산출은 ``spawn_production`` 이 띄운
백그라운드 태스크가 수행한다. 판단 기준은 하나다 — "HTTP 요청 처리가 외부 작업의 완료를
기다리는가". 백그라운드 잡 <b>안에서</b> 블로킹 I/O 를 스레드로 오프로드하는 것은 정상이다.

<b>동기로 되돌리지 말 것</b>: 구 구현은 ``POST /project`` 안에서 인코딩까지 마쳤다. 그러면 우리 BE
``KpstDeidentifyClient`` 의 45초 타임아웃 안에 끝내야 하므로 자원 제한이 반드시 둘 중 하나로
귀결된다 — ①산출물을 잘라 빠르게 끝내기(조용한 절단 = 학습데이터 오염) ②45초 초과(위탁 실패 'F'
+ 재시도는 같은 projectName 이라 409 로 영구 차단). 실제 KPST 도 비동기이며
(``KpstDeidentPollJob`` 이 ``retrieve_progress`` 를 폴링), 이 목은 그 모델을 그대로 흉내낸다.

경과초 기반 진행률(progressRate)을 KPST 코드값(prjState/procState)으로 매핑하고,
리포트용 얼굴/번호판 검출 수, 프레임 조회 mock 데이터, 시간 파싱을 담당한다.
"""

from __future__ import annotations

import asyncio
import contextlib
import errno
import logging
import os
import re
import shutil
import stat
import subprocess
import threading
import time
import uuid
import weakref
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Optional

from starlette.concurrency import run_in_threadpool

from app.services import deid_engine, media_probe, path_policy
from app.state import (
    PRODUCTION_FAILED,
    PRODUCTION_RUNNING,
    PRODUCTION_SUCCEEDED,
    PRODUCTION_TERMINAL,
    ProgressSnapshot,
    get_store,
    sanitize_for_log,
)

logger = logging.getLogger(__name__)

# 프로젝트 상태 코드 (prjState): 0=생성,1=대기,2=실행중,3=완료,4=중지,5=오류,6=정지
PRJ_STATE_WAITING = 1
PRJ_STATE_RUNNING = 2
PRJ_STATE_DONE = 3
PRJ_STATE_ERROR = 5

# 데이터셋 처리 상태 코드 (procState): 0=대기,1=실행중,2=완료,3=중지,4=삭제중,99=오류
PROC_STATE_WAITING = 0
PROC_STATE_RUNNING = 1
PROC_STATE_DONE = 2
#: 오류 sentinel — 우리 BE 의 ``PROC_STATE_TERMINAL_FAILED`` 에 포함돼 즉시 'F' 종결을 유발한다.
PROC_STATE_ERROR = 99

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


def prj_state_for(snapshot: ProgressSnapshot) -> int:
    """진행 스냅샷 → 프로젝트 상태 코드(산출 실패를 오류로 구분해 보고).

    라우터는 rate 만 보는 구 함수 대신 이 함수를 쓴다 — 산출이 실패했는데 "실행중"으로 계속
    보고하면 BE 가 폴링 타임아웃(기본 180분)까지 매달린다.
    """
    if snapshot.state == "FAILED":
        return PRJ_STATE_ERROR
    return prj_state_from_rate(snapshot.progress_rate)


def proc_state_for(snapshot: ProgressSnapshot) -> int:
    """진행 스냅샷 → 데이터셋 처리 상태 코드(산출 실패 = 오류 sentinel 99)."""
    if snapshot.state == "FAILED":
        return PROC_STATE_ERROR
    return proc_state_from_rate(snapshot.progress_rate)


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
# 아니라 <b>'비식별 완료' 워터마크를 구운 원본</b>(ffmpeg 가용 시) 또는 원본 복사본(폴백)이다.
# 워터마크는 개발/QA 가 라벨링 화면에서 "이게 비식별 처리된 영상인가"를 육안으로 구분하기 위한 것이다.
#
# ★ #3 — <b>placeholder 산출물은 폐기됐다</b>. 원본을 읽지 못하는 경우(부재/권한/허용 루트 밖/
#   입력 마운트 불일치) 구 구현은 18바이트 스텁을 <b>최종 경로</b>에 쓰고 완료(procState=2)로
#   보고했다. 그 파일은 BE 무결성(≥512B + 컨테이너 시그니처)에서 탈락해 'F' 가 되는데,
#   no-overwrite 라 그 이름은 이후 어떤 재시도로도 대체되지 않는다(영구 고착).
#   지금은 <b>산출 실패(procState=99)</b> 로 종결한다 — "완료 보고 = BE 가 회수 가능한 산출물".
#   유효 크기의 가짜 영상으로 대체하는 안은 채택하지 않았다: 읽지도 못한 원본을 '비식별 완료'로
#   승인시키는 위장 산출물이 되기 때문이다(CWE-345).

# 마스킹 출력 파일명 규칙 — {원본stem}-mask{확장자} (실서버 실측 계약, 2026-07-21 curl/ll 확정).
# 예: 001.mp4 → 001-mask.mp4. 타임스탬프 세그먼트는 없다(구 목업 규칙 `{stem}_{ts}_mask{ext}` 폐기 —
# 실계약과 달라 BE 의 1차 회수 경로(toMaskName)가 로컬에서 한 번도 검증되지 않는 공백을 만들었다).
MASK_SUFFIX: str = "-mask"

# MOCK_OUTPUT_BASE 미설정 경고를 1회만 남기기 위한 플래그(로그 스팸 방지).
_base_unset_warned: bool = False


def reset_base_warning() -> None:
    """허용 루트 미설정 경고 플래그를 초기화한다(테스트용).

    읽기 허용 루트 경고 플래그는 판정 단일 원천(``path_policy``)이 소유하므로 함께 초기화한다.
    """
    global _base_unset_warned
    _base_unset_warned = False
    path_policy.reset_warning()


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


def plan_outputs_detailed(raw_names: list[str]) -> tuple[list[tuple[str, str]], list[str]]:
    """``plan_outputs`` + <b>버려진 항목</b>을 함께 돌려준다(#4).

    정화(``safe_basename``) 실패 항목을 <b>조용히</b> 버리면, 요청에 파일이 있었는데 계획이
    0건이 되는 상황(예: ``files:["/"]`` — 비어있지 않아 400 검증을 통과한다)이 데이터셋 0개
    프로젝트를 만들고, 그 프로젝트는 산출물이 없는데도 완료로 보고된다. BE 는 ``firstDataset``
    이 null 이라 완료를 인지하지 못한 채 폴링 예산을 소진해 'F' 로 끝난다.
    그래서 호출측이 "요청 N건 → 계획 0건"을 <b>인지</b>할 수 있게 사유를 반환한다.

    Returns:
        (계획 목록, 제외된 원본명 목록) — 제외 사유는 전부 "basename 정화 실패"다.
    """
    plans: list[tuple[str, str]] = []
    dropped: list[str] = []
    for raw in raw_names:
        base = safe_basename(raw)
        if base is None:
            # 조용히 버리지 않는다 — 항목별 사유 로그 + 반환값으로 호출측에 전달.
            logger.warning(
                "[MOCK][KPST] deid output skip unsafe name=%s (basename 정화 실패)",
                sanitize_for_log(raw),
            )
            dropped.append(raw if isinstance(raw, str) else "")
            continue
        stem, ext = os.path.splitext(base)
        plans.append((base, f"{stem}{MASK_SUFFIX}{ext}"))
    return plans, dropped


def plan_outputs(raw_names: list[str]) -> list[tuple[str, str]]:
    """원본명 목록을 (원본 basename, 마스킹명) 쌍 목록으로 변환한다.

    라우터가 이 결과로 ①데이터셋 등록(fileName = 원본 입력 경로) ②실제 파일 쓰기(마스킹명)를
    수행한다. 정화 실패 항목은 제외한다(제외 사유까지 필요하면 ``plan_outputs_detailed``).
    """
    return plan_outputs_detailed(raw_names)[0]


# ── 경로 경계 판정 (F-7 — 단일 원천은 ``app.services.path_policy``) ──
# 이 모듈은 아래 이름들을 <b>재노출</b>만 한다. 판정 로직을 여기서 다시 구현하지 않는다 —
# 같은 정책이 여러 모듈에 인라인 중복되면 그중 하나가 반드시 fail-open 으로 샌다(F-7 근거).
#
# ⚠ ``path_policy.resolve_input_dir`` 은 <b>재노출하지 않는다</b>(#6). 그 함수는 "base 가 비면
# 제한 없음"이라는 fail-open 저수준 헬퍼라, 공개 이름으로 노출해 두면 새 소비자가 정책 진입점
# (``path_policy.resolve_readable_dir``)을 건너뛰고 그것을 집어 쓰는 순간 조용히 fail-open 이 된다.
# 이 레포에는 "게이트를 호출처마다 배선하면 반드시 샌다"는 재발 이력이 있다.
_is_within = path_policy.is_within
base_tokens = path_policy.base_tokens
resolve_output_dir = path_policy.resolve_output_dir
input_root_configured = path_policy.read_root_configured


def _safe_source_path(input_path: str, base_name: str, input_base: str = "") -> Optional[Path]:
    """{input_path}/{base_name} 복사 소스 경로를 정화 후 반환한다.

    입력 디렉터리 밖으로 탈출하는 경로, 그리고 허용 루트(``input_base``) 밖의 입력
    디렉터리는 None 을 반환해 복사를 막는다(호출측은 대체 산출물을 남기지 않고 산출
    실패로 종결한다 — 읽지도 못한 원본을 '비식별 완료'로 승격시키지 않기 위해, CWE-345).
    허용 루트가 아예 설정돼 있지 않아도 None 이다(fail-closed).

    판정은 정책 단일 진입점(``path_policy.resolve_readable_dir``)에만 위임한다.
    """
    in_dir = path_policy.resolve_readable_dir(input_path, input_base)
    if in_dir is None:
        return None
    try:
        src = (in_dir / base_name).resolve()
    except (OSError, ValueError):
        return None
    if not _is_within(src, in_dir):
        return None
    return src


# ── 복사 폴백 자원 상한 (F-4, CWE-400/CWE-770) ────────────────────
# 목은 무인증이고 ``export_path`` 만 바꿔 같은 원본을 반복 요청할 수 있다. 상한이 없으면 허용 루트
# 안의 원본 전량이 요청마다 복제되어 <b>BE 와 공유하는 볼륨</b>이 고갈된다.
#: 복사 폴백 1건의 바이트 상한. 초과 원본은 복사하지 않고 산출 실패로 종결한다(최종 이름을
#: 선점하지 않는다 — 선점하면 no-overwrite 라 재시도로 회복 불가).
COPY_MAX_BYTES: int = 2 * 1024 * 1024 * 1024  # 2GiB
#: 요청 1건이 산출할 수 있는 총 바이트 상한(복사 폴백 합계).
REQUEST_MAX_OUTPUT_BYTES: int = 8 * 1024 * 1024 * 1024  # 8GiB

#: 복사 청크 크기 — 상한 검사를 위해 스트리밍 복사한다.
_COPY_CHUNK_BYTES: int = 1024 * 1024

# ``O_NOFOLLOW`` 는 POSIX 전용이라 플랫폼에 없으면 0(무효)으로 둔다(genai_sim 과 동일 패턴).
_O_NOFOLLOW: int = getattr(os, "O_NOFOLLOW", 0)


class OutputBudgetExceeded(Exception):
    """복사 산출 바이트 상한 초과 — 호출측이 아무것도 쓰지 않고 산출 실패로 종결한다(F-4)."""


class OutputWriteResult(Enum):
    """산출 1건의 결과 — <b>``bool`` 하나로 뭉개지 않는다</b>(HIGH-1).

    ★ 결함의 실체: 구 구현에서 ``_copy_no_overwrite`` 의 ``False`` 는 "<b>최종 경로가 이미
    존재</b>" 단 하나였다(O_EXCL 로 최종 경로를 직접 열었으므로). 그래서 호출측이 그것을
    "성공(멱등 skip)"으로 해석하는 것이 옳았다. 그런데 ``.mock-tmp`` 경유(#3)로 바꾸면서
    같은 ``False`` 가 네 가지를 뜻하게 됐는데 <b>호출측 해석은 그대로 뒀다</b>:
      ①타깃 존재 ②임시 디렉터리 사용 불가(점유·EACCES·EROFS·ENOSPC) ③임시파일 선점 실패
      ④원자 배치 실패(선점한 타깃을 unlink 하고 False)
    ③④ 는 <b>아무 파일도 남기지 않는데</b> 성공으로 보고돼 ``production=SUCCEEDED`` /
    ``files=0`` / ``procState=2``(진행률 100) 라는 <b>거짓 완료</b>가 나왔다 — BE 는 존재하지도
    않는 산출물을 회수하러 가고, 로그에는 "output exists — skip" 이라는 <b>거짓 진단</b>만 남았다.
    (같은 장애가 형제 경로 ``_create_exclusive(temp)`` 의 EACCES 에서는 정상적으로 FAILED →
    ``procState=99`` 로 보고돼, 원인이 같은데 상황에 따라 99/2 로 갈리는 비일관도 있었다.)

    그래서 결과를 <b>사유로 구분</b>한다. 성공 판정의 축은 단 하나 — "최종 경로에 산출물이
    실재하는가"(:attr:`is_output_present`).
    """

    #: 이번 호출이 최종 경로에 산출물을 배치했다.
    PLACED = "PLACED"
    #: 최종 경로에 이미 파일이 있어 덮어쓰지 않았다(멱등 재실행 — 산출물은 실재한다).
    TARGET_EXISTS = "TARGET_EXISTS"
    #: 아무것도 남기지 못했다. <b>성공으로 해석 금지</b> — 산출 실패(procState=99)로 종결한다.
    FAILED = "FAILED"

    @property
    def is_output_present(self) -> bool:
        """최종 경로에 산출물이 실재하는가(= 이 건을 성공으로 볼 수 있는가)."""
        return self in (OutputWriteResult.PLACED, OutputWriteResult.TARGET_EXISTS)


def _create_exclusive(target: Path) -> Optional[int]:
    """O_EXCL 로 파일을 원자적으로 생성한다(HIGH-2 덮어쓰기 방지).

    이미 존재하면 FileExistsError → None 반환(호출측 skip). 성공 시 fd 반환.
    ``O_NOFOLLOW`` 로 심링크 타깃으로의 쓰기도 차단한다(CWE-59).
    """
    try:
        return os.open(
            target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | _O_NOFOLLOW, 0o644
        )
    except FileExistsError:
        return None


def _copy_limited(source, dst, max_bytes: int) -> int:
    """복사 바이트 수를 상한으로 제한하며 스트리밍 복사한다.

    Raises:
        OutputBudgetExceeded: 상한 초과(복사 도중 원본이 커지는 경우 포함).
    """
    copied = 0
    while True:
        chunk = source.read(_COPY_CHUNK_BYTES)
        if not chunk:
            return copied
        copied += len(chunk)
        if copied > max_bytes:
            raise OutputBudgetExceeded("copy budget exceeded")
        dst.write(chunk)


def _copy_no_overwrite(
    src: Path, target: Path, *, max_bytes: int = COPY_MAX_BYTES
) -> OutputWriteResult:
    """원본을 target 으로 복사하되 기존 파일을 덮어쓰지 않는다.

    ★ HIGH-1 — 반환은 ``bool`` 이 아니라 :class:`OutputWriteResult` 다. "이미 있어서 안 썼다
    (성공)"와 "쓰지 못했다(실패)"를 같은 ``False`` 로 돌려주면 호출측이 산출물 0건을 완료로
    보고한다. 세부 사유와 배경은 :class:`OutputWriteResult` 참조.

    ★ <b>은닉 임시경로 → 원자 배치</b>(#3 — 인코딩 경로와 <b>대칭</b>). 구 구현은 O_EXCL 로
    <b>최종 경로</b>를 직접 열고 스트리밍하면서 ``completed`` 플래그 + ``finally`` 로 정리했다.
    그러나 ``finally`` 는 <b>SIGKILL/OOM/``compose down`` 에서 실행되지 않는다</b> — GB급 복사
    도중 강제 종료되면 ``ftyp`` 헤더 + 512바이트 이상인 <b>잘린 파일</b>이 최종 산출물로 남고,
    BE 무결성 검증(``DeidentArtifactIntegrity``)은 그것을 유효 비식별본으로 인정해 거짓 ``'Y'``
    를 만든다(CWE-345/CWE-754). 게다가 그 잔재는 임시파일 패턴이 아니라 sweep 대상도 아니고,
    no-overwrite 라 재시도로도 지워지지 않는다(영구 고착).
    그래서 인코딩과 똑같이 ``{export}/.mock-tmp/`` 안에 쓰고 완주했을 때만 원자 배치한다 —
    강제 종료 시 남는 잔재는 BE 스캔 사정권 밖의 임시파일이고 sweep 이 회수한다.

    ``max_bytes`` 로 1건 복사 바이트를 제한한다(무인증 반복 요청에 의한 볼륨 고갈 차단).

    Raises:
        OutputBudgetExceeded: 복사 바이트 상한 초과(부분 파일은 제거된 뒤 전파된다).
    """
    if target.exists():
        return OutputWriteResult.TARGET_EXISTS
    temp = _temp_path_for(target)
    if temp is None:
        # 임시 디렉터리를 쓸 수 없다(점유·권한·읽기전용·용량). 산출물은 <b>하나도 없다</b> —
        # 사유 로그는 _temp_path_for 가 남긴다. 여기서 성공으로 돌려주면 거짓 완료가 된다.
        return OutputWriteResult.FAILED
    try:
        fd = _create_exclusive(temp)
        if fd is None:  # uuid 충돌(사실상 불가) — 아무것도 쓰지 못했으므로 실패다
            logger.warning(
                "[MOCK][KPST] deid 임시파일 선점 실패(uuid 충돌) — 산출 실패 file=%s",
                sanitize_for_log(target.name),
            )
            return OutputWriteResult.FAILED
        with os.fdopen(fd, "wb") as dst, open(src, "rb") as source:
            _copy_limited(source, dst, max_bytes)
        return _place_atomically(temp, target)
    finally:
        # 성공 시엔 하드링크 원본이라, 실패 시엔 부분 산출물이라 어느 쪽이든 임시파일을 지운다.
        with contextlib.suppress(OSError):
            temp.unlink(missing_ok=True)


# ── '비식별 완료' 워터마크 굽기 ───────────────────────────────────
# 원본을 바이트 그대로 복사하면 라벨링 화면에서 재생해도 비식별 처리 여부를 육안으로 구분할 수
# 없다. 그래서 실제 영상 원본이면 ffmpeg drawtext 로 모든 프레임 우측하단에 고정 문구를 굽는다.
# 실패(ffmpeg/폰트 부재·인코딩 실패·타임아웃)하면 기존 복사 동작으로 폴백해 목의 기존 계약을
# 그대로 유지한다 — 워터마킹은 '있으면 좋은' 가시화이지 파이프라인 성립 조건이 아니다.
# ⚠ 문구에 <b>MOCK</b> 을 반드시 남긴다 — 목이 굽는 대상은 실제로 비식별된 영상이 아니라 <b>원본
# 복사본</b>이다. "비식별 완료"만 새기면 PII 가 그대로 남은 영상에 보증 문구가 인코딩되어 개발/QA 가
# "마스킹된 영상"으로 오인한다(2026-07-30 사용자 확정 문구).
WATERMARK_TEXT: str = "MOCK 비식별 완료"

# 폰트는 fontconfig 탐색에 맡기지 않고 <b>절대경로</b>로 지정한다(컨테이너에서 탐색 실패 시
# drawtext 가 통째로 실패하므로). 앞에서부터 실제 존재하는 첫 항목을 사용한다.
#   - /usr/share/fonts/truetype/nanum/... : Debian/Ubuntu `fonts-nanum`(Dockerfile 설치분)
#   - /System/Library/Fonts/...           : macOS 개발 환경
WATERMARK_FONT_CANDIDATES: tuple[str, ...] = (
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/usr/share/fonts/truetype/nanum/NanumBarunGothic.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
)

# 워터마킹 대상 확장자(그 외는 복사) — 이미지 폴더 모드/비영상 산출물은 대상이 아니다.
WATERMARK_VIDEO_SUFFIXES: frozenset[str] = frozenset(
    {".mp4", ".mov", ".m4v", ".mkv", ".avi"}
)
# moov 를 앞으로 보내 브라우저 스트리밍 재생을 빠르게 하는 mp4 계열 muxer 옵션.
_FASTSTART_SUFFIXES: frozenset[str] = frozenset({".mp4", ".mov", ".m4v"})

WATERMARK_FONT_SIZE: int = 24
WATERMARK_MARGIN_PX: int = 10

# ── 자원 상한 (CWE-400/CWE-770 · OWASP API4:2023) ─────────────────
# 목 서버는 <b>무인증</b>이고 ``files`` 는 요청당 최대 1000건이다. 상한이 없으면 ffmpeg 프로세스가
# 증식해 CPU 를 고갈시켜 <b>다른 목 엔드포인트까지</b> 마비된다.
#
# ★ 상한의 축 — <b>시간이 아니라 자원</b>(#1/#2 재설계):
#   구 구현에는 "요청당 시간 예산(45초 - α)"이 있었다. 그 예산은 <b>동기 접수 모델의 산물</b>이며,
#   접수를 비동기로 되돌린 지금은 전제 자체가 사라졌다(BE 는 접수 응답을 45초 안에 받고, 산출은
#   폴링이 기다린다 — ``poll-max-attempts`` 240 × 30초 / ``poll-timeout-minutes`` 180). 게다가 그
#   예산 산식은 자기가 추가한 길이검증 ffprobe·초회 능력탐지·복사 시간을 계상하지 않아 실제
#   최악값과 어긋나 있었다. 그래서 <b>시간 예산 장치를 제거</b>하고 시간과 무관한 축만 남긴다:
#     ①subprocess 타임아웃(kill → rc≠0 → 복사 폴백) ②전역 동시 실행 세마포어
#     ③요청당 인코딩 건수 상한 ④복사 산출 바이트 상한.
#   ``-t``(길이)/``-fs``(크기) 절단 인자는 여전히 <b>넣지 않는다</b> — 절단본은 rc=0 으로 끝나
#   성공과 구분되지 않고(CWE-345/754) BE 무결성도 통과해 "원본 후반이 사라진 영상"이 정상
#   비식별 산출물로 승격된다. 자원 초과는 "잘라서 내보내기"가 아니라 "포기하고 온전히 복사하기".

#: 요청 1건에서 실제로 인코딩할 최대 파일 수. 초과분은 워터마킹 없이 원본 복사로 산출한다.
WATERMARK_MAX_FILES_PER_REQUEST: int = 20

#: 동시에 실행할 수 있는 ffmpeg 프로세스 수(프로세스 전역).
FFMPEG_MAX_CONCURRENCY: int = 2

#: 위 세마포어 대기 상한(초). 비동기 산출이라 HTTP 타임아웃에 쫓기지 않으므로 <b>넉넉히</b> 둔다.
#: 짧게 두면(구 2초) 동시 산출이 두 건만 겹쳐도 정상 영상이 워터마킹 없이 복사돼 버린다.
FFMPEG_ACQUIRE_TIMEOUT_SEC: float = 120.0

#: ffmpeg 1건 실행 상한(초). 초과 시 프로세스를 kill 하고 원본 복사로 폴백한다(절단본 아님).
#: BE 폴링 예산(240회 × 30초 = 120분) 안에서 실영상 1건을 끝낼 수 있도록 잡는다 — 구 20초는
#: 45초 HTTP 예산에 맞춰 조인 값이라 조금만 긴 영상이면 워터마킹이 매번 실패했다.
FFMPEG_TIMEOUT_SEC: int = 600

#: ffmpeg 인코딩 스레드 수 — 미지정 시 전 코어를 점유한다.
FFMPEG_THREADS: int = 1

#: 하드웨어(NVENC) 인코더 이름. GPU 가 노출된 호스트에서만 쓰이며, 아니면 CPU 로 폴백한다.
#:
#: 왜 필요한가(2026-07-31 cudo_246 실측): 4K 영상의 CPU(libx264) 워터마크 인코딩은 <b>0.05x</b> 라
#: 80초 영상 하나에 27분이 걸려 {@code FFMPEG_TIMEOUT_SEC}(600초)를 넘고, 그 전에 컨테이너 메모리
#: 한도에서 OOM-kill 된다(32코어 호스트라 x264 가 코어당 4K 프레임 버퍼를 잡는다). 결과적으로
#: 실제 자산(전부 4K)에서는 워터마킹이 <b>항상 복사 폴백</b>해 "목 산출물 육안 확인" 목적이
#: 달성되지 않았다. NVENC(Tesla T4) 로는 같은 영상이 <b>1.87x</b> — 80초 영상 약 43초다.
HW_ENCODER: str = "h264_nvenc"

#: NVENC 가용성 탐지용 최소 인코딩(합성 입력 → null 출력). 인코더 목록에 이름이 있어도
#: {@code libcuda.so.1}/{@code libnvidia-encode.so.1} 이 없으면 <b>실행 시점에</b> 실패하므로,
#: 목록 조회가 아니라 실제 1프레임 인코딩으로 판정한다.
HW_ENCODER_PROBE_TIMEOUT_SEC: int = 15

#: 동시에 진행할 수 있는 <b>백그라운드 산출 태스크</b> 수. 각 태스크는 실행 중 스레드풀 워커를
#: 1개 점유하므로, 접수가 몰려도 anyio 워커 풀을 고갈시키지 않도록 묶는다(CWE-770).
PRODUCTION_MAX_CONCURRENCY: int = 2

# ── BE 폴링 예산 가정 (접수 상한 파생의 입력값) ────────────────────
# 목은 BE 설정을 읽을 수 없으므로 상수로 <b>명시</b>한다. 출처는 우리 BE 의
# ``backend/src/main/resources/application.yml`` → ``kpst.deid`` (2026-07-30 실측):
#   poll-interval-sec 30 · poll-max-attempts 240 · poll-timeout-minutes 180
# 두 타임아웃(시도 횟수 · 경과 시간) 중 <b>먼저 걸리는 쪽</b>이 실제 예산이다
#   → min(240×30초=7200초, 180분=10800초) = 7200초(120분).
# ⚠ BE 가 이 값을 바꾸면 여기도 함께 바꿔야 한다 — 어긋나면 접수 상한이 다시 배출률과
#   따로 놀아 "상한 안에서 정상 접수된 건이 폴링 예산을 소진해 'F'" 가 재발한다.
#   그 드리프트는 ``test_deid_async`` 의 상한 파생 가드 테스트가 붙잡는다.
BE_POLL_INTERVAL_SEC: int = 30
BE_POLL_MAX_ATTEMPTS: int = 240
BE_POLL_TIMEOUT_MIN: int = 180
BE_POLL_BUDGET_SEC: int = min(
    BE_POLL_INTERVAL_SEC * BE_POLL_MAX_ATTEMPTS, BE_POLL_TIMEOUT_MIN * 60
)

#: 산출 태스크 <b>1건</b>의 최악 소요(초) — ffmpeg 실행 상한 + ffmpeg 세마포어 대기 상한.
#: (인코딩이 타임아웃으로 kill 되면 복사 폴백이 이어지므로 이 값은 하한 추정이 아니라 지배항이다.)
PRODUCTION_WORST_CASE_SEC: float = float(FFMPEG_TIMEOUT_SEC) + FFMPEG_ACQUIRE_TIMEOUT_SEC

#: 위 최악값에 계상되지 않은 구간(길이검증 ffprobe·원자 배치·스케줄 지연)과, BE 가 완료를
#: 감지한 뒤 산출물을 회수·무결성 검증하는 데 쓰는 시간을 위해 예산에서 떼어두는 비율.
PRODUCTION_QUEUE_SAFETY_RATIO: float = 0.8

#: 미완료(대기+실행) 산출 태스크 <b>총량</b> 상한 — MEDIUM-3(CWE-770 · OWASP API4:2023).
#: 배출은 ``PRODUCTION_MAX_CONCURRENCY`` 건씩이므로 상한이 없으면 무인증 ``POST /project`` 를
#: 고유 이름으로 N회 던지는 것만으로 태스크가 무제한 누적되고, 큐 뒤쪽은 ``procState=1`` 에
#: 고정된다 → BE 폴링 예산 소진 → ``'F'`` + 이름 점유(``raw{rawSn}``)로 재위탁 409.
#: 그래서 <b>조용한 고착 대신 명시적 거부</b>(503)를 택한다 — 라우터가 접수 <b>전에</b>
#: 용량을 확인하므로 프로젝트 이름이 점유되지 않는다.
#:
#: ★ #1 — 이 값은 <b>매직넘버가 아니라 배출률에서 파생</b>된다. 구 상수 50 은 배출률과 어긋나
#: 있었다: 50번째 잡의 대기 = ⌈49/2⌉ × 720초 ≈ 5시간 > BE 폴링 예산. 즉 <b>상한 안에서 정상
#: 접수된 건</b>이 큐에서 기다리다 폴링 예산을 소진해 'F' 로 끝났다 — 상한이 막겠다던 바로 그
#: 조용한 고착이다. "거부되지 않았으면 예산 안에 끝난다"가 성립해야 상한이 의미를 가진다.
#:
#: 산식(마지막으로 접수되는 N번째 잡 기준):
#:   대기 = ⌈(N-1)/동시성⌉ × 최악소요,  요구 = 대기 + 최악소요 ≤ 예산×마진
#:   ⇒ N ≤ 동시성 × (예산×마진/최악소요 − 1) + 1
PRODUCTION_MAX_INFLIGHT: int = max(
    1,
    int(
        PRODUCTION_MAX_CONCURRENCY
        * (
            (BE_POLL_BUDGET_SEC * PRODUCTION_QUEUE_SAFETY_RATIO)
            / PRODUCTION_WORST_CASE_SEC
            - 1.0
        )
    )
    + 1,
)

#: 전역 ffmpeg 동시 실행 제한 세마포어(모듈 상태).
_FFMPEG_SEMAPHORE = threading.BoundedSemaphore(FFMPEG_MAX_CONCURRENCY)

#: 산출물이 원본과 같은 길이인지 판정할 때 허용하는 오차 — max(절대 1초, 원본의 2%).
#: 컨테이너/프레임 경계 반올림 차이는 흡수하되, 절단(구 ``-t`` 상한)은 반드시 걸러낸다.
DURATION_TOLERANCE_SEC: float = 1.0
DURATION_TOLERANCE_RATIO: float = 0.02

#: 승격(최종 배치) 최소 크기(바이트) — 우리 BE ``DeidentArtifactIntegrity.MIN_VIDEO_BYTES`` 와 동일.
MIN_ARTIFACT_BYTES: int = 512

# ── 임시 산출물 격리 (MEDIUM-1, CWE-345/CWE-754) ───────────────────
# 임시 산출물을 export 디렉터리 <b>직속</b>에 ``.mp4`` 확장자로 두면, 인코딩 도중 컨테이너가
# SIGKILL/OOM 으로 죽어 ``finally`` 정리가 실행되지 않았을 때 <b>잘린 부분 산출물</b>이 남는다.
# 우리 BE 의 폴백 스캔(``KpstDeidentService.scanSingleUsable`` → ``Files.list`` 비재귀 +
# ``DeidentArtifactIntegrity``: 512B 이상 + 컨테이너 시그니처)은 그것을 <b>유효한 비식별본</b>으로
# 판정해 원본을 '비식별 완료(Y)'로 승인하고, 고아가 2개 이상이면 ``INVALID_INPUT`` terminal 실패로
# 파이프라인이 영구 정지한다.
#
# 그래서 임시파일을 <b>은닉 서브디렉터리</b>에 둔다:
#   - ``Files.list`` 는 <b>비재귀</b>라 하위 디렉터리 <b>안</b>의 파일을 보지 않는다.
#   - 서브디렉터리 엔트리 자체는 ``DeidentArtifactIntegrity.isValidVideoArtifact`` 의
#     ``Files.isRegularFile(NOFOLLOW_LINKS)`` 검사에서 탈락한다(디렉터리는 정규 파일이 아님).
#   - 같은 파일시스템이므로 ``os.link`` 원자적 배치(no-overwrite)는 그대로 성립한다.
TEMP_DIR_NAME: str = ".mock-tmp"

#: 임시 산출물 파일명에 포함되는 표식 — 정리(sweep) 대상 식별에 쓴다.
TEMP_MARK: str = ".tmp"

#: 나이 판정에 얹는 안전 마진(초) — 길이검증(ffprobe)·원자 배치·스케줄 지연 등 인코딩 밖 구간.
TEMP_ORPHAN_SAFETY_MARGIN_SEC: float = 600.0

#: 고아 임시파일로 간주하는 최소 나이(초).
#:
#: ★ MEDIUM-2 — <b>인코딩 타임아웃보다 반드시 커야 한다</b>. 구 구현은 상수 600.0 이라
#: ``FFMPEG_TIMEOUT_SEC``(20→600 상향) 와 <b>같은 값</b>이 되어 안전 마진이 0 이었다. 같은 export
#: 디렉터리에 후속 산출이 들어오면 ``produce_deid_outputs`` 의 lazy sweep 이 <b>살아있는 temp</b>
#: 를 지워 진행 중인 인코딩을 깨뜨릴 수 있었다(ffmpeg ENOENT → 불필요한 복사 폴백).
#: 그래서 상수로 두지 않고 <b>실제 상한들에서 파생</b>시킨다 — 타임아웃을 조정해도 마진이 함께
#: 따라오므로 두 값이 다시 같아지는 드리프트가 구조적으로 불가능하다.
#: 산식: ffmpeg 실행 상한 + 세마포어 대기 상한 + 안전 마진(길이검증·배치·스케줄 지연).
TEMP_ORPHAN_MAX_AGE_SEC: float = (
    float(FFMPEG_TIMEOUT_SEC) + FFMPEG_ACQUIRE_TIMEOUT_SEC + TEMP_ORPHAN_SAFETY_MARGIN_SEC
)

#: startup sweep 이 훑는 디렉터리 수 상한(무제한 walk 로 기동이 지연되지 않게).
TEMP_SWEEP_MAX_DIRS: int = 20_000


#: ISO-BMFF/QuickTime 선두 박스 타입 — 우리 BE ``DeidentArtifactIntegrity.ISO_BOX_TYPES`` 와 동일.
_ISO_BOX_TYPES: frozenset[bytes] = frozenset(
    {b"ftyp", b"moov", b"mdat", b"free", b"skip", b"wide", b"pnot", b"styp"}
)


def _has_container_signature(head: bytes) -> bool:
    """선두 바이트가 알려진 영상 컨테이너 시그니처인지(BE 판정과 동일 축).

    우리 BE 의 ``DeidentArtifactIntegrity.hasKnownContainerSignature`` 중, 목이 실제로 산출할 수
    있는 muxer(ISO-BMFF · Matroska · RIFF)만 인정한다. BE 보다 <b>느슨하지 않게</b> 두는 것이
    핵심이다 — 목이 BE 보다 관대하면 BE 가 거부할 산출물을 목이 승격시켜 복사 폴백 기회를
    빼앗는다(#4 비대칭).
    """
    if len(head) < 12:
        return False
    if head[4:8] in _ISO_BOX_TYPES:  # mp4 / mov / m4v (ISO BMFF)
        return True
    if head[:4] == b"\x1a\x45\xdf\xa3":  # matroska / webm (EBML)
        return True
    return head[:4] == b"RIFF"  # avi 등 RIFF 컨테이너


def _read_head(path: Path, size: int = 12) -> bytes:
    """파일 선두 바이트를 읽는다(실패는 빈 바이트)."""
    try:
        with open(path, "rb") as f:
            return f.read(size)
    except OSError:
        return b""


def _looks_like_video(src: Path) -> bool:
    """확장자 + 컨테이너 시그니처로 '실제 영상 파일'인지 얕게 판별한다.

    확장자만 영상인 텍스트/플레이스홀더에 ffmpeg 를 띄우는 낭비와 불필요한 WARN 을 막는다.
    엄밀한 검증은 아니며(어차피 실패하면 복사로 폴백한다) 값싼 사전 선별이다.
    """
    if src.suffix.lower() not in WATERMARK_VIDEO_SUFFIXES:
        return False
    return _has_container_signature(_read_head(src))


def is_promotable_artifact(path: Path) -> bool:
    """산출물을 <b>최종 경로로 승격</b>해도 되는지 — BE 무결성 게이트와 동일 기준(#4).

    BE(``DeidentArtifactIntegrity``)는 "정규 파일 + ≥512바이트 + 컨테이너 시그니처"를 요구한다.
    목이 ``크기 > 0`` 만 확인하면, ``0 < size < 512`` 이거나 컨테이너가 아닌 출력을 승격시켜
    <b>복사 폴백을 막아버린다</b> — 그 결과 BE 쪽에서 비식별 실패로 끝나고 폴백 기회는 이미 없다.
    """
    try:
        if not path.is_file() or path.is_symlink():
            return False
        if path.stat().st_size < MIN_ARTIFACT_BYTES:
            return False
    except OSError:
        return False
    return _has_container_signature(_read_head(path))


# ffmpeg 빌드별 drawtext(libfreetype) 지원 여부 캐시 — 바이너리 경로별 1회만 조회한다.
_DRAWTEXT_SUPPORT: dict[str, bool] = {}
#: 위 캐시 갱신을 직렬화하는 락 — 없으면 워밍업 시 같은 탐지 subprocess 가 동시에 뜬다(F-5 부수).
_DRAWTEXT_LOCK = threading.Lock()

#: 기능 탐지(``ffmpeg -filters``) 실행 상한(초).
FFMPEG_CAPABILITY_TIMEOUT_SEC: int = 10


# NVENC(하드웨어 인코더) 가용성 캐시 — drawtext 캐시와 동일 규약(바이너리 경로별 1회).
_HW_ENCODER_SUPPORT: dict[str, bool] = {}
_HW_ENCODER_LOCK = threading.Lock()


def reset_ffmpeg_capability_cache() -> None:
    """ffmpeg 기능 탐지 캐시를 비운다(테스트용)."""
    with _DRAWTEXT_LOCK:
        _DRAWTEXT_SUPPORT.clear()
    with _HW_ENCODER_LOCK:
        _HW_ENCODER_SUPPORT.clear()


def _ffmpeg_has_hw_encoder(ffmpeg: str) -> bool:
    """NVENC 인코더를 <b>실제로 실행할 수 있는지</b> 확인한다(결과 캐시).

    <b>인코더 목록 조회로는 부족하다</b>: 이미지에 {@code h264_nvenc} 가 빌드돼 있어도 컨테이너에
    GPU 가 노출되지 않으면 목록에는 보이고 실행 시점에 {@code Cannot load libcuda.so.1} /
    {@code libnvidia-encode.so.1} 로 죽는다(실측). 그래서 합성 입력 1프레임을 null 로 인코딩해
    <b>런타임 가용성</b>을 판정한다.

    ⚠ NVENC 은 GPU 노출뿐 아니라 <b>{@code video} 드라이버 capability</b> 가 필요하다
    ({@code NVIDIA_DRIVER_CAPABILITIES=compute,video,utility}). 기본값({@code compute,utility})
    만으로는 libcuda 는 로드되고 인코더 라이브러리만 없어 여기서 False 로 떨어진다.

    drawtext 탐지와 같은 이유로 전역 세마포어 안에서 실행하고 캐시 갱신을 락으로 직렬화한다.
    자리를 못 얻으면 캐시에 남기지 않는다(일시적 혼잡을 "미지원"으로 영구 기록하지 않는다).
    """
    with _HW_ENCODER_LOCK:
        cached = _HW_ENCODER_SUPPORT.get(ffmpeg)
        if cached is not None:
            return cached
        if not _FFMPEG_SEMAPHORE.acquire(timeout=FFMPEG_ACQUIRE_TIMEOUT_SEC):
            logger.warning("[MOCK][KPST] 하드웨어 인코더 탐지 대기 초과 — 이번 요청은 CPU 인코딩")
            return False
        try:
            completed = subprocess.run(  # noqa: S603 — 고정 인자 리스트, shell 미사용
                [
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                    # ⚠ 크기를 작게 잡으면 NVENC 이 "Frame Dimension less than the minimum
                    #   supported value" 로 거부해 <GPU 가 멀쩡한데도> 미지원으로 오판한다
                    #   (64x64 로 실측·확인). 최소 지원 치(H.264 145x49)보다 넉넉히 잡는다.
                    "-f", "lavfi", "-i", "color=c=black:s=320x240:d=0.04",
                    "-c:v", HW_ENCODER, "-f", "null", "-",
                ],
                shell=False,
                capture_output=True,
                stdin=subprocess.DEVNULL,
                timeout=HW_ENCODER_PROBE_TIMEOUT_SEC,
                check=False,
            )
            supported = completed.returncode == 0
        except (OSError, subprocess.SubprocessError):
            supported = False
        finally:
            _FFMPEG_SEMAPHORE.release()
        _HW_ENCODER_SUPPORT[ffmpeg] = supported
        logger.info(
            "[MOCK][KPST] 워터마크 인코더 = %s", HW_ENCODER if supported else "libx264(CPU)"
        )
        return supported


def _ffmpeg_has_drawtext(ffmpeg: str) -> bool:
    """설치된 ffmpeg 빌드가 ``drawtext`` 필터를 포함하는지 확인한다(결과 캐시).

    libfreetype 없이 빌드된 ffmpeg(일부 macOS/최소 빌드)는 drawtext 가 없어 인코딩이 ``rc=8`` 로
    실패한다. 미리 확인해 <b>원인이 드러나는 WARN</b>을 남기고 즉시 복사로 폴백한다.

    F-5 부수 — 이 탐지도 ffmpeg 프로세스를 띄우므로 <b>전역 세마포어 안에서</b> 실행하고,
    캐시 갱신을 락으로 직렬화한다. 구 구현은 둘 다 없어 기동 직후 동시 요청이 몰리면 탐지
    프로세스가 세마포어 밖에서 중복 기동됐다.
    """
    with _DRAWTEXT_LOCK:
        cached = _DRAWTEXT_SUPPORT.get(ffmpeg)
        if cached is not None:
            return cached
        if not _FFMPEG_SEMAPHORE.acquire(timeout=FFMPEG_ACQUIRE_TIMEOUT_SEC):
            # 자리를 못 얻으면 캐시에 남기지 않는다(다음 요청에서 다시 시도) — 일시적 혼잡을
            # "drawtext 미지원"으로 영구 기록하면 워터마킹이 영영 꺼진다.
            logger.warning(
                "[MOCK][KPST] watermark 기능 탐지 대기 초과 — 이번 요청은 원본 복사로 폴백"
            )
            return False
        try:
            completed = subprocess.run(  # noqa: S603 — 고정 인자 리스트, shell 미사용
                [ffmpeg, "-hide_banner", "-loglevel", "error", "-filters"],
                shell=False,
                capture_output=True,
                stdin=subprocess.DEVNULL,
                timeout=FFMPEG_CAPABILITY_TIMEOUT_SEC,
                check=False,
            )
            supported = completed.returncode == 0 and b" drawtext " in completed.stdout
        except (OSError, subprocess.SubprocessError):
            supported = False
        finally:
            _FFMPEG_SEMAPHORE.release()
        _DRAWTEXT_SUPPORT[ffmpeg] = supported
        return supported


def _find_watermark_font() -> Optional[str]:
    """존재하는 첫 폰트 절대경로를 반환한다. 없으면 None(→ 복사 폴백)."""
    for candidate in WATERMARK_FONT_CANDIDATES:
        if os.path.isfile(candidate):
            return candidate
    return None


#: 필터그래프에서 의미를 갖는 문자 — 값 안에 그대로 두면 구조가 깨지거나 다른 필터가 주입된다.
#: ``\`` 는 이스케이프 문자 자신이라 <b>가장 먼저</b> 치환해야 한다(순서 의존).
_FILTER_META_CHARS: tuple[str, ...] = (":", "'", ",", ";", "[", "]")


def _escape_filter_value(value: str) -> str:
    """ffmpeg <b>필터그래프</b> 값을 이스케이프한다(LOW-3 견고화).

    대상: ``\\`` (이스케이프 문자 자신) · ``:`` (필터 옵션 구분) · ``'`` (인용) ·
    ``,`` (필터 구분) · ``;`` (필터체인 구분) · ``[`` ``]`` (스트림 라벨). 개행(``\\r`` ``\\n``)은
    이스케이프 대상이 아니라 <b>제거</b>한다(필터그래프에 개행을 담을 안전한 표현이 없다).

    현재 이 함수를 통과하는 값은 폰트 절대경로와 워터마크 문구(둘 다 코드 상수/고정 후보)뿐이라
    <b>인젝션이 도달하지 못한다</b>. 그럼에도 범용 새니타이저처럼 보이는 이름을 가진 이상,
    누군가 나중에 사용자 값을 통과시켰을 때 곧바로 필터 인젝션이 되지 않도록 견고화해 둔다
    (CWE-77 심층 방어).
    """
    escaped = value.replace("\\", "\\\\")
    for char in _FILTER_META_CHARS:
        escaped = escaped.replace(char, "\\" + char)
    return escaped.replace("\r", "").replace("\n", "")


def _build_drawtext_filter(font_path: str) -> str:
    """우측하단 워터마크 drawtext 필터 문자열을 만든다(고정 사양).

    ``fontfile`` 과 ``text`` <b>둘 다</b> 동일 이스케이프를 거친다 — 문구는 향후 변경될 수 있고,
    한쪽만 통과시키면 그때 조용히 필터 인젝션 표면이 열린다.
    """
    return (
        f"drawtext=fontfile={_escape_filter_value(font_path)}"
        f":text={_escape_filter_value(WATERMARK_TEXT)}"
        # F-8 — drawtext 는 기본적으로 text 안의 ``%{...}`` 를 <b>확장</b>한다(``expansion=normal``).
        # 현재 문구는 코드 상수라 도달 불가하지만, 누군가 사용자 값을 통과시키는 순간
        # ``%{pts}``/``%{metadata:...}`` 같은 확장이 평가된다. 심층방어로 확장을 끈다.
        f":expansion=none"
        f":fontcolor=white:fontsize={WATERMARK_FONT_SIZE}"
        f":box=1:boxcolor=black@0.5:boxborderw=6"
        f":x=w-tw-{WATERMARK_MARGIN_PX}:y=h-th-{WATERMARK_MARGIN_PX}"
    )


def _is_safe_cli_path(path: Path) -> bool:
    """CLI 인자로 넘겨도 안전한 경로인지 — 절대경로이며 ``-`` 로 시작하지 않아야 한다.

    ``-`` 로 시작하는 인자는 ffmpeg 가 <b>옵션</b>으로 오인식한다(argument injection).
    """
    text = os.fspath(path)
    return os.path.isabs(text) and not text.startswith("-")


def _revalidate_roots(
    src: Path,
    target: Path,
    input_path: str,
    input_base: str,
    output_dir: Path,
    export_path: str = "",
    output_base: str = "",
) -> Optional[tuple[Path, Path]]:
    """subprocess 호출 <b>직전</b>에 입출력 허용 루트를 독립적으로 재검증한다(TOCTOU, CWE-367).

    앞선 검증 이후 심볼릭 링크 교체 등으로 경로 의미가 바뀌었을 수 있으므로, <b>원시 입력</b>에서
    루트를 다시 도출해 ``resolve()`` 기준으로 재확인한다.

    LOW-1 — 출력 측도 입력 측과 <b>대칭</b>으로 재도출한다. 구 구현은 ``target.parent ==
    output_dir.resolve()`` 동일성만 봤는데, 그 둘은 <b>같은 조상</b>을 공유하므로 조상이 심링크로
    교체되면 두 값이 함께 이동해 검사를 통과했다(``/base/a`` → ``/evil`` 심링크면 양쪽 모두
    ``/evil/b``). 그래서 허용 출력 루트(``output_base``)로부터 ``export_path`` 를 다시 해석해
    현재 타깃이 여전히 그 안에 있는지 확인한다.

    Args:
        export_path/output_base: 출력 측 재도출 입력. 생략되면(내부 단위 호출) 구 동일성 검사만
            수행한다 — 프로덕션 경로(``write_deid_outputs``)는 항상 둘을 넘긴다.

    #6 — 입력 측 판정은 <b>정책 진입점</b>(``path_policy.resolve_readable_dir``)에만 위임한다.
    구 구현은 fail-open 저수준 헬퍼(``resolve_input_dir``)를 직접 불러 "허용 루트 미설정 = 제한
    없음"으로 통과시켰다. 지금은 상류(``_safe_source_path``)가 fail-closed 라 도달하지 않지만,
    같은 정책을 두 곳에서 다르게 판정하는 구조 자체가 새는 원인이다.

    Returns:
        (정규화된 src, 정규화된 target). 경계 밖/정규화 실패/허용 루트 미설정이면 None.
    """
    in_dir = path_policy.resolve_readable_dir(input_path, input_base)
    if in_dir is None:
        return None
    try:
        src_resolved = src.resolve()
        out_resolved = output_dir.resolve()
        target_resolved = target.resolve()
    except (OSError, ValueError):
        return None
    if not _is_within(src_resolved, in_dir):
        return None
    if target_resolved.parent != out_resolved:
        return None
    if base_tokens(output_base):
        # 입력 측과 대칭 — 허용 루트에서 출력 디렉터리를 <b>다시 도출</b>해 재검증한다.
        revalidated_out = resolve_output_dir(export_path, output_base)
        if revalidated_out is None or target_resolved.parent != revalidated_out:
            return None
    return src_resolved, target_resolved


def _place_atomically(temp: Path, target: Path) -> OutputWriteResult:
    """임시 산출물을 최종 경로에 배치한다 — 원자적이며 기존 파일을 덮어쓰지 않는다.

    HIGH-1 — 반환은 사유 구분 결과다: ``PLACED``(배치함) / ``TARGET_EXISTS``(이미 있어 배치
    안 함 = 산출물은 실재) / ``FAILED``(선점만 하고 교체 실패 → 빈 파일도 남기지 않음).
    구 ``bool`` 은 뒤 둘을 같은 ``False`` 로 뭉개 호출측이 "이미 있음(성공)"으로 오해했다.

    ``os.link`` 는 타깃이 있으면 ``FileExistsError`` 라 no-overwrite(HIGH-2)가 원자적으로
    보장된다. 하드링크 미지원 파일시스템(EXDEV/EPERM)에서만 rename 으로 폴백한다.

    F-6 — 폴백 분기의 check-then-act 를 제거했다. 구 구현은 ``target.exists()`` 로 확인한 뒤
    ``os.replace`` 했기에 그 사이에 만들어진 파일을 덮어썼다. 지금은 ``O_EXCL`` 로 타깃을
    <b>원자적으로 선점</b>하고(선점 실패 = 이미 존재 = False), 선점한 자기 파일 위로만 replace 한다.
    """
    try:
        os.link(temp, target)
        return OutputWriteResult.PLACED
    except FileExistsError:
        return OutputWriteResult.TARGET_EXISTS
    except OSError:
        pass
    # 하드링크 미지원 — O_EXCL 로 선점한 뒤 그 자리를 원자적으로 교체한다.
    fd = _create_exclusive(target)
    if fd is None:
        return OutputWriteResult.TARGET_EXISTS
    os.close(fd)
    try:
        os.replace(temp, target)
    except OSError:
        with contextlib.suppress(OSError):
            os.unlink(target)  # 선점만 하고 실패 — 빈 파일을 남기지 않는다
        return OutputWriteResult.FAILED
    return OutputWriteResult.PLACED


def _run_ffmpeg_watermark(
    ffmpeg: str, src: Path, temp: Path, font_path: str
) -> bool:
    """ffmpeg 를 실행해 워터마크가 구워진 임시 산출물을 만든다.

    보안(CWE-78): ``shell=False`` + <b>리스트 인자</b>만 사용하고, 필터 문자열에는 고정 상수와
    폰트 경로 외 어떤 값도 넣지 않는다. stderr 는 로그에 싣지 않는다(내부 경로 노출 방지 —
    CWE-209/532). 실패는 예외 대신 False 로 알려 호출측이 복사로 폴백하게 한다.

    자원 상한(CWE-400/770): 전역 세마포어로 동시 ffmpeg 수를 제한하고 ``-threads`` 로 코어 점유를
    묶은 뒤, <b>subprocess 타임아웃</b>으로 실행 시간을 끊는다. 자리를 얻지 못하거나 시간이
    초과되면 인코딩을 포기하고 복사로 폴백한다.

    ⚠ <b>``-t``(길이)/``-fs``(크기) 상한은 의도적으로 넣지 않는다</b>. 그 둘은 산출물을 조용히
    <b>절단</b>하면서 rc=0 으로 끝나 성공과 구분되지 않고(CWE-345/754), ``-fs`` 는 하드캡도 아니다.
    자원 초과는 "잘라서 내보내기"가 아니라 "포기하고 원본을 온전히 복사하기"로 처리한다.
    """
    # 인코더 선택 — GPU(NVENC) 가 실행 가능하면 그걸 쓰고, 아니면 CPU(libx264) 로 폴백한다.
    # 4K 실자산에서 CPU 경로는 0.05x(80초 영상 27분)라 타임아웃·OOM 으로 사실상 항상 복사 폴백된다.
    use_hw = _ffmpeg_has_hw_encoder(ffmpeg)
    if use_hw:
        # NVENC 은 CRF 대신 CQ(-cq) 를 쓴다. preset p1=최속(육안 확인용이라 화질보다 속도).
        codec_args = ["-c:v", HW_ENCODER, "-preset", "p1", "-cq", "28"]
    else:
        codec_args = ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "28"]

    cmd = [
        ffmpeg,
        "-nostdin",
        "-hide_banner",
        "-loglevel", "error",
        "-n",  # 임시 산출물이 이미 있으면 덮어쓰지 않고 실패(비대화형)
        "-i", os.fspath(src),
        "-vf", _build_drawtext_filter(font_path),
        "-map", "0:v:0",
        "-map", "0:a?",  # 오디오는 있으면 그대로, 없으면 무시
        *codec_args,
        "-pix_fmt", "yuv420p",
        "-c:a", "copy",
        # ── 자원 상한 — CPU 만 묶는다(길이·크기 절단 인자 없음) ──
        # NVENC 경로에서도 디코딩은 CPU 라 스레드 상한을 유지한다(32코어 호스트에서 x264 가 코어당
        # 4K 프레임 버퍼를 잡아 컨테이너 메모리 한도를 넘겨 OOM-kill 되던 실측 사고 방지).
        "-threads", str(FFMPEG_THREADS),          # 전 코어 점유 방지
    ]
    if temp.suffix.lower() in _FASTSTART_SUFFIXES:
        cmd += ["-movflags", "+faststart"]
    cmd.append(os.fspath(temp))

    if not _FFMPEG_SEMAPHORE.acquire(timeout=FFMPEG_ACQUIRE_TIMEOUT_SEC):
        logger.warning(
            "[MOCK][KPST] watermark 동시 실행 상한(%d) 대기 초과 — 원본 복사로 폴백 file=%s",
            FFMPEG_MAX_CONCURRENCY,
            sanitize_for_log(src.name),
        )
        return False
    try:
        completed = subprocess.run(  # noqa: S603 — 고정 인자 리스트, shell 미사용
            cmd,
            shell=False,
            capture_output=True,
            stdin=subprocess.DEVNULL,
            timeout=FFMPEG_TIMEOUT_SEC,
            check=False,
        )
    except subprocess.TimeoutExpired:
        logger.warning(
            "[MOCK][KPST] watermark timeout(%ds) — 원본 복사로 폴백 file=%s",
            FFMPEG_TIMEOUT_SEC,
            sanitize_for_log(src.name),
        )
        return False
    except OSError as exc:
        logger.warning(
            "[MOCK][KPST] watermark ffmpeg 실행 실패 — 원본 복사로 폴백 file=%s errno=%s",
            sanitize_for_log(src.name),
            exc.errno,
        )
        return False
    finally:
        _FFMPEG_SEMAPHORE.release()

    if completed.returncode != 0:
        # stderr 원문은 남기지 않는다 — 내부 경로/빌드 정보가 섞여 나온다(CWE-209).
        logger.warning(
            "[MOCK][KPST] watermark 인코딩 실패(rc=%d) — 원본 복사로 폴백 file=%s",
            completed.returncode,
            sanitize_for_log(src.name),
        )
        return False
    return True


def _resolve_watermark_tools(file_hint: str) -> Optional[tuple[str, str]]:
    """워터마킹에 필요한 외부 도구를 확인한다 — (ffmpeg 경로, 폰트 절대경로).

    ffmpeg 바이너리 / drawtext 필터 / 한글 폰트 중 하나라도 없으면 <b>원인이 드러나는</b> WARN 을
    남기고 None 을 반환한다(호출측은 워터마크 없는 원본 복사로 폴백).
    """
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        logger.warning(
            "[MOCK][KPST] watermark skip — ffmpeg 바이너리 없음. 워터마크 없는 원본 복사로 "
            "폴백 file=%s",
            sanitize_for_log(file_hint),
        )
        return None
    if not _ffmpeg_has_drawtext(ffmpeg):
        logger.warning(
            "[MOCK][KPST] watermark skip — 설치된 ffmpeg 빌드에 drawtext(libfreetype) 필터가 "
            "없음. 워터마크 없는 원본 복사로 폴백 file=%s",
            sanitize_for_log(file_hint),
        )
        return None
    font_path = _find_watermark_font()
    if font_path is None:
        logger.warning(
            "[MOCK][KPST] watermark skip — 한글 font 파일 없음(fonts-nanum 미설치). "
            "워터마크 없는 원본 복사로 폴백 file=%s",
            sanitize_for_log(file_hint),
        )
        return None
    return ffmpeg, font_path


#: 이 목이 만드는 임시 산출물 파일명 규칙 — ``{stem}.{uuid4.hex}.tmp{ext}``.
#: 부분 일치(``".tmp" in name``)로 넓히면 ``x.tmpfile`` · ``nginx.tmpl`` · ``a.tmp.bak`` 같은
#: <b>남의 파일</b>까지 정리 대상이 된다(F-1). uuid32 + 확장자 형태까지 정확히 요구한다.
_TEMP_NAME_RE = re.compile(r".+\.[0-9a-f]{32}\.tmp(\.[A-Za-z0-9]{1,8})?$")


def _is_temp_artifact(name: str) -> bool:
    """이 목이 만든 임시 산출물 파일명인지(정리 대상 식별용) — 정확 패턴 매칭."""
    return _TEMP_NAME_RE.fullmatch(name) is not None


def _is_real_dir(path: Path) -> bool:
    """심볼릭 링크가 아닌 <b>실제 디렉터리</b>인지(CWE-59 — 링크 추종 차단)."""
    try:
        st = os.stat(path, follow_symlinks=False)
    except OSError:
        return False
    return stat.S_ISDIR(st.st_mode)


def _temp_path_for(target_resolved: Path) -> Optional[Path]:
    """임시 산출물 경로를 export 디렉터리 <b>하위 은닉 디렉터리</b> 안에 만든다(MEDIUM-1).

    ``{export}/.mock-tmp/{stem}.{uuid}.tmp{ext}`` — BE 폴백 스캔(``Files.list``, 비재귀)의
    사정권 밖이라 프로세스가 강제 종료돼 정리가 되지 않아도 고아 임시파일이 "유효한 비식별본"으로
    오판되지 않는다. 같은 파일시스템이므로 ``os.link`` 원자적 배치는 그대로 성립한다.

    Returns:
        임시 파일 경로. 디렉터리 준비 실패(권한/점유/심링크 치환)면 None(→ 복사 폴백).
    """
    temp_dir = target_resolved.parent / TEMP_DIR_NAME
    try:
        # F-6 — check-then-act 제거. ``mkdir(exist_ok=True)`` 는 대상이 <b>디렉터리를 가리키는
        # 심링크</b>여도 ``is_dir()==True`` 라 통과한다. 그래서 exist_ok 없이 생성을 시도하고,
        # 이미 있으면 ``lstat`` 로 실디렉터리임을 직접 확인한다(링크면 사용하지 않는다).
        try:
            temp_dir.mkdir(mode=0o700)
        except FileExistsError:
            if not _is_real_dir(temp_dir):
                logger.warning(
                    "[MOCK][KPST] watermark skip — 임시 디렉터리가 실제 디렉터리가 아니라 "
                    "사용하지 않는다(심링크 치환 의심) file=%s",
                    sanitize_for_log(target_resolved.name),
                )
                return None
    except OSError as exc:
        logger.warning(
            "[MOCK][KPST] watermark 임시 디렉터리 준비 실패 — 원본 복사로 폴백 file=%s "
            "type=%s errno=%s",
            sanitize_for_log(target_resolved.name),
            type(exc).__name__,
            exc.errno,
        )
        return None
    # 확장자를 보존해야 ffmpeg 가 muxer 를 추론한다. uuid 로 동시 요청 충돌을 피한다.
    return temp_dir / (
        f"{target_resolved.stem}.{uuid.uuid4().hex}{TEMP_MARK}{target_resolved.suffix}"
    )


def sweep_temp_dir(temp_dir: Path, *, max_age_sec: Optional[float] = None) -> int:
    """은닉 임시 디렉터리 1곳의 <b>고아 임시파일</b>을 제거한다(MEDIUM-1 후속 정리).

    프로세스가 SIGKILL/OOM 으로 죽으면 ``finally`` 정리가 실행되지 않아 임시파일이 남는다.
    ``max_age_sec`` 보다 오래된 것만 지워 진행 중인 인코딩을 건드리지 않는다.
    기본값(None)은 <b>호출 시점</b>의 ``TEMP_ORPHAN_MAX_AGE_SEC`` 로 해석한다 — 구 구현은
    기본 인자에 상수를 직접 써서 <b>def 시점에 고정</b>됐고, 그러면 파생 상수를 재조정해도
    sweep 만 옛 값으로 남는 드리프트가 생긴다(#1·M2 의 파생 상수 정책과 같은 이유).

    ★ [R] 나이 계산은 <b>0 으로 클램프</b>한다(``age = max(0, now - mtime)``). 구 구현은
    ``now - mtime < max_age_sec`` 를 그대로 비교해서, mtime 이 <b>현재보다 미래</b>이면
    (age 가 음수) 기동 sweep(``max_age_sec=0``)에서도 ``-0.001 < 0.0`` 이 참이 되어
    <b>영원히 건너뛰었다</b> — 고아가 하나도 정리되지 않는다. mtime 이 미래가 되는 상황은
    드물지 않다: 공유 스토리지(NAS/bind mount·virtiofs·FUSE)의 시각 반올림, 컨테이너와
    파일시스템 사이의 시계 차이, NTP 스텝 보정. 이 sweep 은 M-1 방어(임시파일이 BE 무결성
    스캔에 잡혀 <b>잘린 영상이 '비식별 완료'로 승인</b>되는 것을 막는 장치)의 회수 경로이므로
    시계 신뢰에 의존해선 안 된다. 클램프하면 "미래 mtime = 나이 0" 이라 기동 sweep(0초)에서는
    지워지고, 운영 중 lazy sweep(``TEMP_ORPHAN_MAX_AGE_SEC``)에서는 살아있는 temp 로 간주돼
    보호된다 — 양쪽 다 안전한 방향이다.

    F-1 (CWE-59/CWE-22) — <b>심링크를 추종하지 않는다</b>. ``.mock-tmp`` 라는 이름의 심링크가
    놓여 있으면 구 구현은 ``os.scandir`` 로 링크를 따라가 <b>허용 루트 밖 디렉터리</b>를 순회하며
    파일을 지웠다(기동 sweep 은 ``max_age_sec=0`` 이라 나이 무관 전량 삭제). 그래서 순회 전에
    ``lstat`` 로 실디렉터리임을 확인하고, 삭제 대상도 정확한 임시파일 패턴만 인정한다.

    Returns:
        제거한 파일 수. 디렉터리 미존재/심링크/입출력 오류는 0(예외 전파 없음).
    """
    threshold = TEMP_ORPHAN_MAX_AGE_SEC if max_age_sec is None else max_age_sec
    removed = 0
    now = time.time()
    if not _is_real_dir(temp_dir):
        return 0
    try:
        entries = list(os.scandir(temp_dir))
    except OSError:
        return 0
    for entry in entries:
        try:
            if not entry.is_file(follow_symlinks=False) or not _is_temp_artifact(entry.name):
                continue
            # [R] 음수 나이(미래 mtime)는 0 으로 클램프한다 — 그대로 비교하면 기동 sweep 이
            # 고아를 영원히 건너뛴다(공유 스토리지 시각 반올림·시계 차이에서 실제로 발생).
            age = max(0.0, now - entry.stat().st_mtime)
            if age < threshold:
                continue
            os.unlink(entry.path)
            removed += 1
        except OSError:
            continue
    return removed


def sweep_orphan_temp_files(output_base: str, *, max_age_sec: float = 0.0) -> int:
    """허용 출력 루트 아래 모든 ``.mock-tmp`` 디렉터리의 고아 임시파일을 정리한다.

    프로세스 <b>기동 시 1회</b> 호출한다 — 이 시점에는 우리 인코딩이 하나도 진행 중이 아니므로
    남아 있는 임시파일은 전부 이전 기동의 고아다(기본 ``max_age_sec=0``).

    walk 는 심링크를 따라가지 않고(``followlinks=False``), 방문 디렉터리 수를
    ``TEMP_SWEEP_MAX_DIRS`` 로 제한해 대용량 NAS 에서 기동이 지연되지 않게 한다.

    ⚠ F-1 — ``os.walk(followlinks=False)`` 는 심링크-디렉터리를 <b>재귀에서만</b> 제외할 뿐
    ``dir_names`` 목록에는 그대로 담는다. 따라서 ``.mock-tmp`` 후보가 심링크면 여기서 걸러야
    하며, 실제 방어는 ``sweep_temp_dir`` 선두의 ``lstat`` 검사가 담당한다(중복 방어).

    Returns:
        제거한 파일 수(어떤 오류도 전파하지 않는다).
    """
    removed = 0
    visited = 0
    for base in base_tokens(output_base):
        try:
            root = Path(base).resolve()
        except (OSError, ValueError):
            continue
        for dir_path, dir_names, _files in os.walk(root, followlinks=False):
            visited += 1
            if visited > TEMP_SWEEP_MAX_DIRS:
                logger.warning(
                    "[MOCK][KPST] 임시파일 정리 중단 — 탐색 디렉터리 상한(%d) 초과",
                    TEMP_SWEEP_MAX_DIRS,
                )
                return removed
            if TEMP_DIR_NAME in dir_names:
                removed += sweep_temp_dir(
                    Path(dir_path) / TEMP_DIR_NAME, max_age_sec=max_age_sec
                )
                dir_names.remove(TEMP_DIR_NAME)  # 임시 디렉터리 내부로는 더 내려가지 않는다
    if removed:
        logger.info("[MOCK][KPST] 고아 임시 산출물 정리 완료 count=%d", removed)
    return removed


def _duration_of(path: Path, allowed_base: str) -> Optional[float]:
    """미디어 길이(초)를 조회한다 — Phase B 의 ffprobe 헬퍼를 그대로 재사용한다.

    새 subprocess 경로를 또 만들지 않는다(보안 검증·자원 상한이 그 모듈에 이미 있다).
    조회 실패는 예외가 아니라 ``None`` 이며, 예상 밖 예외까지 삼켜 워터마킹 판정이 죽지 않게 한다.

    #4 — <b>deid 전용 세마포어</b>로 조회한다. VLM describe 와 세마포어를 공유하면 describe 를
    다발로 던져 자리를 포화시키는 것만으로 이 검증을 외부에서 무력화할 수 있었다.
    """
    try:
        return media_probe.probe_duration_sec(
            path, allowed_base, purpose=media_probe.PURPOSE_DEID
        )
    except Exception as exc:  # noqa: BLE001 — 길이 조회 실패가 산출을 죽이면 안 된다
        logger.warning(
            "[MOCK][KPST] 산출물 길이 조회 예외 — 검증 생략 type=%s", type(exc).__name__
        )
        return None


def _is_duration_preserved(src: Path, temp: Path, src_base: str, out_base: str) -> bool:
    """산출물이 원본과 <b>같은 길이</b>인지 확인한다(#1/#3 — 조용한 절단 차단).

    ``-t``/``-fs`` 를 제거했으므로 정상 경로에서 절단은 생기지 않지만, ffmpeg 가 입력 손상·디스크
    부족 등으로 <b>일부만 mux 하고 rc=0</b> 으로 끝나는 경우가 여전히 가능하다. 그런 산출물은
    ``st_size != 0`` 도, BE 무결성(512B + ``ftyp``)도 통과해 "원본 후반이 사라진 영상"이 정상
    비식별 산출물로 승격된다(CWE-345/754). 그래서 승격 직전에 길이를 대조한다.

    판정 (#4 — <b>fail-open 제거</b>):
    - 원본 길이를 알 수 없으면 <b>거부</b>한다(False). 구 구현은 "비교 기준이 없으니 검증 생략
      (True)"이었는데, 그러면 <b>길이 조회를 실패시키는 것만으로</b> 절단 방어를 통째로 끌 수
      있었다(외부에서 ffprobe 자리를 포화시키는 방식). 비동기 산출이라 서두를 이유가 없으므로,
      확인할 수 없으면 워터마킹을 포기하고 <b>원본을 온전히 복사</b>한다 — 안전한 실패다.
    - 산출물 길이를 못 구해도 거부한다(방금 만든 파일을 못 읽는 것은 정상이 아니다).
    - 차이가 허용 오차(max(1초, 원본의 2%)) 밖이면 거부한다.
    """
    src_duration = _duration_of(src, src_base)
    if src_duration is None:
        logger.warning(
            "[MOCK][KPST] watermark 원본 길이를 확인할 수 없음 — 절단 여부를 검증할 수 없어 "
            "승격하지 않고 원본 복사로 폴백 file=%s",
            sanitize_for_log(src.name),
        )
        return False
    out_duration = _duration_of(temp, out_base)
    if out_duration is None:
        logger.warning(
            "[MOCK][KPST] watermark 산출물 길이를 확인할 수 없음 — 승격하지 않고 원본 복사로 "
            "폴백 file=%s",
            sanitize_for_log(src.name),
        )
        return False
    tolerance = max(DURATION_TOLERANCE_SEC, src_duration * DURATION_TOLERANCE_RATIO)
    if abs(out_duration - src_duration) > tolerance:
        # 감사 흔적(OWASP A09) — 절단/연장은 성공 로그와 반드시 구분되어야 한다.
        logger.warning(
            "[MOCK][KPST] watermark 산출물 길이가 원본과 다름(src=%.3fs out=%.3fs tol=%.3fs) — "
            "승격하지 않고 원본 복사로 폴백 file=%s",
            src_duration,
            out_duration,
            tolerance,
            sanitize_for_log(src.name),
        )
        return False
    return True


def _engine_codec_args(ffmpeg: str) -> list[str]:
    """비식별 엔진의 비디오 인코딩 옵션 — 워터마크 경로와 동일한 인코더 선택 규약을 쓴다.

    HW 인코더가 있으면 그걸 쓰고 없으면 libx264 로 내려간다. 다만 화질 파라미터는 워터마크보다
    한 단계 좋게 잡는다(crf 23) — 워터마크는 "글자가 보이면 그만"이지만 비식별본은 <b>라벨링
    대상 영상</b>이라 압축 잡음이 객체 경계를 뭉개면 검수 판단을 방해한다.
    """
    if _ffmpeg_has_hw_encoder(ffmpeg):
        return ["-c:v", HW_ENCODER, "-preset", "p4", "-cq", "23"]
    return ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23"]


def _burn_deid_watermark(
    src: Path,
    target: Path,
    *,
    input_path: str,
    input_base: str = "",
    output_dir: Path,
    export_path: str = "",
    output_base: str = "",
    masking_type: int = deid_engine.MASK_BLUR,
    masking_range: float = 1.0,
    summary_out: Optional[list] = None,
) -> bool:
    """원본 영상을 비식별해 ``target`` 으로 산출한다.

    <b>실제 마스킹이 1순위, 워터마크가 2순위다.</b> 검출 모델이 갖춰져 있으면
    ``deid_engine`` 이 얼굴·사람·텍스트를 실제로 가린 영상을 만들고, 모델이 없거나 엔진이
    실패하면 기존 '비식별 완료' 워터마크를 굽는다. 둘 다 안 되면 False 를 돌려 호출측이
    원본 복사로 폴백한다. 즉 <b>모델이 없는 환경에서 목의 기존 동작은 그대로다</b>.

    ``summary_out`` 이 주어지면 엔진이 만든 ``DeidSummary`` 를 여기에 append 한다
    (리포트의 실제 검출 수 원천). 워터마크 폴백 경로에서는 아무것도 넣지 않는다.

    성공하면 True. 아래 어느 경우든 <b>예외 없이</b> False 를 반환해 호출측이 기존
    ``_copy_no_overwrite`` 로 폴백하게 한다(워터마킹 실패가 요청 실패로 번지면 안 된다):
    비영상/경계 위반/ffmpeg·폰트 부재/인코딩 실패·타임아웃/타깃 선점/<b>승격 게이트 탈락</b>.

    승격(최종 배치) 조건 — 셋 다 충족해야 한다:
      ① ffmpeg rc=0 ② BE 무결성과 동일한 산출물 판정(≥512B + 컨테이너 시그니처, #4)
      ③ 원본과 같은 재생 길이(#1/#3 — 조용한 절단 차단)
    하나라도 어긋나면 임시파일을 폐기하고 <b>원본 그대로의 온전한 복사본</b>으로 폴백한다.

    부분 실패 방지: 임시파일(``{export}/.mock-tmp/{stem}.{uuid}.tmp{ext}``)에 출력한 뒤 성공 시에만
    원자적으로 배치하고, 어떤 경로로 빠져나가든 임시파일을 정리한다. 임시파일을 <b>은닉
    서브디렉터리</b>에 두는 이유는 MEDIUM-1 — export 디렉터리 직속에 두면 강제 종료 시 남은
    부분 산출물이 BE 폴백 스캔에서 유효한 비식별본으로 오판된다.
    """
    if target.exists():
        return False
    if not (_is_safe_cli_path(src) and _is_safe_cli_path(target)):
        logger.warning(
            "[MOCK][KPST] watermark skip — 안전하지 않은 CLI 경로 file=%s",
            sanitize_for_log(target.name),
        )
        return False

    revalidated = _revalidate_roots(
        src, target, input_path, input_base, output_dir, export_path, output_base
    )
    if revalidated is None:
        logger.warning(
            "[MOCK][KPST] watermark skip(boundary) — 허용 입출력 루트 재검증 실패 file=%s",
            sanitize_for_log(target.name),
        )
        return False
    src_resolved, target_resolved = revalidated

    if not _looks_like_video(src_resolved):
        logger.info(
            "[MOCK][KPST] watermark skip — 영상 컨테이너가 아니라 원본을 그대로 복사 file=%s",
            sanitize_for_log(src.name),
        )
        return False

    # ⚠ 워터마크 도구(drawtext·폰트) 확인을 여기서 하지 <b>않는다</b>. 그러면 폰트가 없는
    #   빌드에서 <b>실제 마스킹 엔진까지 함께 막힌다</b> — 엔진은 폰트가 필요 없다.
    #   폰트 검사는 워터마크로 폴백하는 시점으로 미룬다.
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        logger.warning(
            "[MOCK][KPST] deid render skip — ffmpeg 바이너리 없음. 원본 복사로 폴백 file=%s",
            sanitize_for_log(src.name),
        )
        return False

    temp = _temp_path_for(target_resolved)
    if temp is None:
        return False
    # 길이 조회의 허용 루트 — 이미 경계 검증을 통과한 실제 디렉터리를 그대로 쓴다.
    src_probe_base = input_base or os.fspath(src_resolved.parent)
    out_probe_base = os.fspath(target_resolved.parent)

    rendered_by_engine = False
    try:
        # ① 실제 비식별 엔진 — 모델이 갖춰져 있을 때만. 실패는 예외 없이 None 이다.
        if deid_engine.engine_available():
            summary = deid_engine.render_masked(
                src_resolved,
                temp,
                ffmpeg=ffmpeg,
                ffprobe=shutil.which("ffprobe") or "ffprobe",
                masking_type=masking_type,
                masking_range=masking_range,
                codec_args=_engine_codec_args(ffmpeg),
                threads=FFMPEG_THREADS,
                faststart=temp.suffix.lower() in _FASTSTART_SUFFIXES,
            )
            if summary is not None:
                rendered_by_engine = True
                if summary_out is not None:
                    summary_out.append(summary)

        # ② 워터마크 폴백 — 엔진이 없거나 실패했을 때. 여기서만 폰트를 요구한다.
        if not rendered_by_engine:
            # 엔진이 남긴 부분 산출물을 먼저 치운다(ffmpeg -n 은 기존 파일이 있으면 실패한다).
            try:
                temp.unlink(missing_ok=True)
            except OSError:
                pass
            tools = _resolve_watermark_tools(src.name)
            if tools is None:
                return False
            if not _run_ffmpeg_watermark(ffmpeg, src_resolved, temp, tools[1]):
                return False
        # #4 — 승격 판정을 BE 무결성 게이트(≥512B + 컨테이너 시그니처)와 동일 기준으로 맞춘다.
        if not is_promotable_artifact(temp):
            logger.warning(
                "[MOCK][KPST] watermark 산출물이 BE 무결성 기준(%dB + 컨테이너 시그니처) 미달 — "
                "원본 복사로 폴백 file=%s",
                MIN_ARTIFACT_BYTES,
                sanitize_for_log(src.name),
            )
            return False
        # #1/#3 — 조용한 절단 차단. 원본과 길이가 다르면 승격하지 않는다.
        if not _is_duration_preserved(
            src_resolved, temp, src_probe_base, out_probe_base
        ):
            return False
        placement = _place_atomically(temp, target_resolved)
        if placement is OutputWriteResult.TARGET_EXISTS:
            logger.info(
                "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
                sanitize_for_log(target.name),
            )
            return False
        if placement is not OutputWriteResult.PLACED:
            # HIGH-1 — 배치 실패를 "이미 있음"으로 기록하면 거짓 진단이 된다(구 동작).
            # 여기서 False 는 "복사로 폴백하라"는 뜻이며, 복사도 실패하면 산출 실패로 종결된다.
            logger.warning(
                "[MOCK][KPST] watermark 산출물 원자 배치 실패(최종 경로 미생성) — 원본 복사로 "
                "폴백 file=%s",
                sanitize_for_log(target.name),
            )
            return False
    except OSError as exc:  # 디스크/권한 등 — 격리 후 폴백
        # LOW-2: str(exc) 는 "[Errno 28] ...: '/app/storage/raw/1/deid/001-mask.mp4'" 처럼
        # <b>전체 경로</b>를 담는다(sanitize_for_log 는 제어문자 제거·절단만 한다). 원인 식별에
        # 필요한 것은 예외 종류와 errno 뿐이다(CWE-532/209).
        logger.warning(
            "[MOCK][KPST] watermark 배치 실패 — 원본 복사로 폴백 file=%s type=%s errno=%s",
            sanitize_for_log(target.name),
            type(exc).__name__,
            exc.errno,
        )
        return False
    finally:
        # 부분 실패 잔여물 제거(성공 시엔 하드링크 원본이라 정리 대상).
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
        # 은닉 디렉터리 자체는 <b>지우지 않는다</b>. 비었다고 rmdir 하면, 같은 export 디렉터리에
        # 동시 인코딩이 진행 중일 때 아직 출력 파일을 만들지 않은 다른 워커의 임시 경로가 사라져
        # ffmpeg 가 ENOENT 로 죽는다(불필요한 폴백). 빈 디렉터리는 BE 스캔에서 정규 파일이 아니라
        # 무시되므로 남겨도 무해하고, 다음 요청/기동 sweep 이 내부를 정리한다.

    # 성공 로그는 <b>승격된 산출물만</b> 남긴다 — 폴백 사유는 위 WARN 들로 구분된다(OWASP A09).
    if rendered_by_engine:
        logger.info(
            "[MOCK][KPST] deid rendered — 실제 마스킹(엔진), 길이·무결성 검증 통과 file=%s",
            sanitize_for_log(target.name),
        )
    else:
        logger.info(
            "[MOCK][KPST] watermark burned — '%s' 우측하단, 길이·무결성 검증 통과 file=%s",
            WATERMARK_TEXT,
            sanitize_for_log(target.name),
        )
    return True


class _EncodeCountBudget:
    """요청 1건의 워터마킹 <b>건수</b> 예산.

    ★ 시간 축은 없다(#1/#2). 구 구현에는 "요청당 시간 예산"이 있었지만 그것은 <b>동기 접수</b>의
    산물이었다 — 45초 HTTP 타임아웃 안에 인코딩까지 끝내야 했기 때문. 접수가 비동기가 된 지금은
    산출이 얼마나 걸리든 폴링이 기다리므로, 시간으로 인코딩을 포기시킬 이유가 없다(포기하면
    워터마크 없는 복사본이 나가 목의 목적 자체가 훼손된다).
    남은 것은 시간과 무관한 자원 축뿐이다 — 요청당 인코딩 건수 상한(+ 전역 동시 실행 세마포어).
    """

    def __init__(self, *, max_files: Optional[int] = None) -> None:
        # 기본값은 <b>호출 시점</b>의 모듈 상수를 읽는다(기본 인자로 묶으면 정의 시점 값에 고정돼
        # 운영 튜닝·테스트 조정이 반영되지 않는다).
        self.max_files = (
            WATERMARK_MAX_FILES_PER_REQUEST if max_files is None else max_files
        )
        self.used_files = 0
        self.exhausted_reason: Optional[str] = None

    def can_start(self) -> bool:
        """새 인코딩을 착수해도 되는지 — 건수 예산만 판정한다."""
        if self.used_files >= self.max_files:
            self.exhausted_reason = f"건수 상한({self.max_files})"
            return False
        return True

    def consume(self) -> None:
        """인코딩 착수 1건을 기록한다."""
        self.used_files += 1


class _OutputBytesBudget:
    """요청 1건의 <b>복사 산출 바이트 예산</b>(F-4).

    ``export_path`` 만 바꿔 반복 요청하면 허용 루트 안의 원본이 매번 통째로 복제되어 BE 와
    공유하는 볼륨이 고갈된다(무인증 + 상한 없음 = CWE-400/770). 파일 1건 상한과 요청 총량
    상한을 함께 둔다. 초과분은 복사하지 않고 산출 실패로 종결한다(#5 — 최종 이름을 선점하는
    대체 산출물은 폐기됐다: no-overwrite 라 그 이름이 영구 고착된다).
    """

    def __init__(
        self,
        *,
        per_file: Optional[int] = None,
        total: Optional[int] = None,
    ) -> None:
        # 기본값은 호출 시점의 모듈 상수를 읽는다(정의 시점 고정 방지 — 위 예산 클래스와 동일).
        self.per_file = COPY_MAX_BYTES if per_file is None else per_file
        self.remaining = REQUEST_MAX_OUTPUT_BYTES if total is None else total

    def allowance(self) -> int:
        """이번 파일에 허용할 복사 바이트."""
        return max(0, min(self.per_file, self.remaining))

    def charge(self, written: int) -> None:
        """실제 산출 바이트를 차감한다."""
        self.remaining = max(0, self.remaining - written)


def _write_one_output(
    target: Path,
    source_base: str,
    input_path: str,
    input_base: str = "",
    *,
    output_dir: Path,
    export_path: str = "",
    output_base: str = "",
    allow_watermark: bool = True,
    bytes_budget: Optional["_OutputBytesBudget"] = None,
    masking_type: int = deid_engine.MASK_BLUR,
    masking_range: float = 1.0,
    summary_out: Optional[list] = None,
) -> bool:
    """단일 출력 파일을 생성한다 — 허용 루트 안의 원본이 있으면 워터마크 굽기/복사, 없으면 실패.

    원본이 실제 영상이면 워터마크를 구운 새 영상을 산출하고(육안 확인용), 워터마킹이 불가능하거나
    ``allow_watermark=False``(요청당 인코딩 건수 예산 소진)면 기존 동작(바이트 복사)으로
    폴백한다. 원본을 읽지 못하면 <b>아무 파일도 쓰지 않고</b> False 를 반환한다 — 대체 산출물을
    최종 경로에 남기는 안은 폐기됐다(아래 #3/#5 참조).

    HIGH-2: target 이 이미 존재하면 덮어쓰지 않고 skip + 로그(멱등 재실행 안전).

    #5 — <b>복사 바이트 예산 초과는 최종 이름을 선점하지 않는다</b>. 구 구현은 18바이트
    placeholder 를 <b>최종 경로</b>에 써서 BE 무결성(512B)을 탈락시켰고, no-overwrite 라 이후
    어떤 재시도도 그 자리를 대체하지 못했다(영구 고착 — 상한 도입 전엔 성공하던 흐름이다).
    지금은 아무것도 쓰지 않고 <b>실패(False)</b>를 반환해 산출 전체를 실패로 종결시킨다.
    BE 는 ``procState=99`` 로 명확한 실패를 인지하고, 최종 이름이 비어 있어 회복도 가능하다.

    Returns:
        산출 성공 여부. False 면 호출측이 프로젝트 산출을 FAILED 로 종결한다.
    """
    budget = bytes_budget if bytes_budget is not None else _OutputBytesBudget()
    if target.exists():
        logger.info(
            "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
            sanitize_for_log(target.name),
        )
        return True
    try:
        src = _safe_source_path(input_path, source_base, input_base)
        if src is None:
            # 경계 위반(허용 입력 루트 밖/정규화 실패) = 보안 이벤트 — "원본이 그냥 없다"와
            # 로그로 구분되어야 한다(OWASP A09). 전체 경로는 남기지 않는다(CWE-532).
            logger.warning(
                "[MOCK][KPST] deid source rejected(boundary) — 허용 입력 루트 밖 요청이라 원본을 "
                "읽지 않고 산출 실패로 종결 file=%s",
                sanitize_for_log(source_base),
            )
        readable = src is not None and src.is_file() and os.access(src, os.R_OK)
        if src is not None and not readable:
            logger.info(
                "[MOCK][KPST] deid source not readable — 산출 실패로 종결 file=%s",
                sanitize_for_log(source_base),
            )
        if readable:
            if allow_watermark and _burn_deid_watermark(
                src,
                target,
                input_path=input_path,
                input_base=input_base,
                output_dir=output_dir,
                export_path=export_path,
                output_base=output_base,
                masking_type=masking_type,
                masking_range=masking_range,
                summary_out=summary_out,
            ):
                return True
            allowance = budget.allowance()
            try:
                copied = _copy_no_overwrite(src, target, max_bytes=allowance)
                if copied is OutputWriteResult.TARGET_EXISTS:
                    logger.info(
                        "[MOCK][KPST] deid output exists — skip(no-overwrite) file=%s",
                        sanitize_for_log(target.name),
                    )
                    return True
                if copied is not OutputWriteResult.PLACED:
                    # ★ HIGH-1 — 구 구현은 이 경우도 "exists — skip" 으로 <b>성공</b> 처리해
                    # 산출물 0건인 프로젝트가 procState=2(완료)로 보고됐다.
                    logger.warning(
                        "[MOCK][KPST] deid 원본 복사 실패 — 최종 경로에 산출물이 생성되지 "
                        "않아 산출 실패로 종결(임시 디렉터리 사용 불가/원자 배치 실패) file=%s",
                        sanitize_for_log(target.name),
                    )
                    return False
            except OutputBudgetExceeded:
                # #5 — 부분 파일은 _copy_no_overwrite 가 이미 제거했고, placeholder 로 최종
                # 이름을 선점하지도 않는다(선점하면 재시도로 회복 불가). 산출 실패로 알린다.
                logger.warning(
                    "[MOCK][KPST] deid 원본이 복사 예산(%dB)을 초과 — 산출 실패로 종결(최종 "
                    "이름을 선점하지 않는다) file=%s",
                    allowance,
                    sanitize_for_log(target.name),
                )
                return False
            try:
                budget.charge(target.stat().st_size)
            except OSError:
                budget.charge(allowance)
        else:
            # ★ #3 — 원본을 읽을 수 없으면 <b>산출 실패로 종결</b>한다. 구 구현은 18바이트
            # placeholder 를 <b>최종 경로</b>에 써놓고 성공(True)을 돌려줬고, 그 결과
            # ``production=SUCCEEDED`` → ``procState=2``(완료) 로 보고됐다. BE 는 그 파일을
            # 회수해 무결성(512B + 컨테이너 시그니처)에서 탈락시켜 'F' 를 남기고, no-overwrite
            # 때문에 이후 어떤 재시도도 그 이름을 대체하지 못한다(영구 고착) — 예산 초과 분기
            # (#5)에서 이미 제거한 패턴이 이 분기에만 남아 있던 <b>비대칭</b>이었다.
            #
            # 이 분기는 드문 케이스가 아니다: 목 컨테이너에 원본 볼륨이 미마운트되거나
            # ``MOCK_INPUT_BASE`` 가 BE 마운트와 어긋나면, BE 는 자기 마운트로 원본 실재를
            # 검증해 위탁하는데 목만 원본을 못 본다(시야 분기). 그때 "완료했다"고 답하면
            # 원인이 드러나지 않는 'F' 가 되므로, 명시적 실패(procState=99)로 즉시 드러낸다.
            #
            # ⛔ 되돌리지 말 것 — 최종 경로에 무언가를 남기려면 BE 무결성 기준(≥512B + 컨테이너
            #    시그니처)을 만족해야 하는데, 그것은 <b>읽지도 못한 원본</b>을 '비식별 완료'로
            #    승인시키는 위장 산출물이다(CWE-345). 원본이 없으면 완료는 없다.
            logger.warning(
                "[MOCK][KPST] deid 원본을 읽지 못해 산출 실패로 종결(placeholder 로 최종 이름을 "
                "선점하지 않는다 — 목의 입력 마운트/MOCK_INPUT_BASE 를 확인하세요) file=%s",
                sanitize_for_log(target.name),
            )
            return False
    except OSError as exc:  # 권한/디스크/경로 문제 등 — 격리
        # LOW-2: 예외 문자열에는 전체 경로가 실린다 — 종류/errno 만 남긴다(CWE-532/209).
        logger.warning(
            "[MOCK][KPST] deid output write failed file=%s type=%s errno=%s",
            sanitize_for_log(target.name),
            type(exc).__name__,
            exc.errno,
        )
        return False
    return True


@dataclass
class ProductionOutcome:
    """백그라운드 산출 1회의 결과.

    ``failed=True`` 는 "산출을 완료하지 못했다"는 뜻이며, 라우터가 이를 ``procState=99``
    (KPST 오류 sentinel)로 보고해 BE 가 폴링 타임아웃을 기다리지 않고 종결하게 한다.
    """

    written: list[Path] = field(default_factory=list)
    failed: bool = False
    reason: Optional[str] = None
    #: 실제 비식별 엔진이 만든 산출물별 검출 요약(``deid_engine.DeidSummary``).
    #: 엔진이 돌지 않은(모델 부재·워터마크 폴백) 산출물은 <b>여기 들어오지 않는다</b> —
    #: 리포트는 이 목록이 비면 기존 mock 카운트를 쓴다.
    summaries: list = field(default_factory=list)




def produce_deid_outputs(
    *,
    export_path: str,
    input_path: str,
    outputs: list[tuple[str, str]],
    output_base: str = "",
    input_base: str = "",
    requested: Optional[int] = None,
    masking_type: int = deid_engine.MASK_BLUR,
    masking_range: float = 1.0,
) -> ProductionOutcome:
    """★ 실제 산출 본체 — <b>백그라운드에서만</b> 호출한다(요청 처리 안에서 부르지 말 것).

    ``outputs`` 는 ``(원본 basename, 마스킹명)`` 쌍 목록으로, ``{export_path}/{마스킹명}`` 에
    생성한다(원본이 영상이면 '비식별 완료' 워터마크를 구워 산출, 워터마킹 불가면 복사, 원본을
    읽지 못하면 산출 실패로 종결). 마스킹명은 ``{stem}-mask{ext}`` 규칙이라 BE 가 진행조회
    ``fileName``(원본 입력 경로)의 basename 을 같은 규칙으로 변환해 회수한다.

    보안:
    - HIGH-1 fail-closed: ``output_base`` 미설정 시 어떤 파일도 쓰지 않는다(1회 warn).
      이것은 <b>설정상 쓰기 비활성</b>이므로 산출 실패가 아니다(성공 no-op).
    - HIGH-2 no-overwrite: 기존 파일은 O_EXCL/원자 배치로 덮어쓰지 않는다.
    - 읽기 경계: ``input_base`` 밖의 ``input_path`` 는 읽지 않고 산출 실패로 종결한다
      (임의 파일 노출·GB급 반복 복사에 의한 디스크 고갈 차단). 대체 산출물로 최종 이름을
      선점하지 않는다 — 읽지도 못한 원본을 '비식별 완료'로 승격시키는 위장 산출물이 되고
      (CWE-345) no-overwrite 라 영구 고착되기 때문이다. ``input_base`` 가 <b>아예 비어
      있으면</b> 원본을 읽지 않는다(fail-closed — ``path_policy.resolve_readable_dir``).
    - 임시 산출물(인코딩·복사 공통)은 ``{export}/.mock-tmp/`` 안에만 만든다(BE 폴백 스캔 밖).
    - 자원 상한: 요청당 인코딩 <b>건수</b>(``_EncodeCountBudget``) + 복사 산출 <b>바이트</b>
      (``_OutputBytesBudget``). 시간 축 예산은 없다(#1/#2 — 비동기 접수라 전제가 사라졌다).

    Args:
        requested: 요청에 실제로 담겨 있던 원본 항목 수(정화 실패로 계획에서 빠진 것 포함).
            미지정이면 ``len(outputs)``. #4 — 계획이 0건이어도 요청이 있었으면 실패로 종결한다.

    Returns:
        ProductionOutcome — 산출된 경로 목록과 실패 여부.
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
        return ProductionOutcome(written=written)

    out_dir = resolve_output_dir(export_path, output_base)
    if out_dir is None:
        logger.warning(
            "[MOCK][KPST] deid output dir rejected export_path=%s output_base=%s",
            sanitize_for_log(export_path),
            sanitize_for_log(output_base),
        )
        # 경계 위반은 <b>산출 실패</b>다 — 아무것도 만들지 않았는데 완료로 보고하면 BE 가 없는
        # 산출물을 회수하러 가서 원인이 드러나지 않는 'F' 가 된다.
        return ProductionOutcome(written=written, failed=True, reason="OUTPUT_DIR_REJECTED")

    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:  # 경로가 파일로 점유됨/권한 등 — 격리
        # LOW-2: 전체 경로/예외 문자열 대신 leaf 이름 + 예외 종류 + errno 만 남긴다(CWE-532/209).
        logger.warning(
            "[MOCK][KPST] deid output mkdir failed dir=%s type=%s errno=%s",
            sanitize_for_log(out_dir.name),
            type(exc).__name__,
            exc.errno,
        )
        return ProductionOutcome(written=written, failed=True, reason="OUTPUT_DIR_UNWRITABLE")

    # 이전 기동이 강제 종료돼 남은 고아 임시파일을 이 시점에 정리한다(기동 sweep 보완).
    sweep_temp_dir(out_dir / TEMP_DIR_NAME)

    # 요청당 인코딩 건수 예산. 초과분은 워터마킹 없이 복사로 산출한다(전량 산출은 유지).
    encode_budget = _EncodeCountBudget()
    bytes_budget = _OutputBytesBudget()
    if len(outputs) > encode_budget.max_files:
        logger.info(
            "[MOCK][KPST] watermark 대상이 요청당 건수 상한(%d)을 초과 — 초과분 %d건은 "
            "원본 복사로 산출",
            encode_budget.max_files,
            len(outputs) - encode_budget.max_files,
        )

    failed_reason: Optional[str] = None
    summaries: list = []
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
        # 건수 예산이 남아 있을 때만 인코딩을 착수한다(시간 축 없음).
        allow_watermark = encode_budget.can_start()
        if allow_watermark:
            encode_budget.consume()
        elif encode_budget.used_files and encode_budget.exhausted_reason:
            logger.info(
                "[MOCK][KPST] watermark 예산 소진(%s) — 남은 파일은 원본 복사로 산출 file=%s",
                encode_budget.exhausted_reason,
                sanitize_for_log(mask_name),
            )
        ok = _write_one_output(
            target,
            source_base,
            input_path,
            input_base,
            output_dir=out_dir,
            export_path=export_path,
            output_base=output_base,
            allow_watermark=allow_watermark,
            bytes_budget=bytes_budget,
            masking_type=masking_type,
            masking_range=masking_range,
            summary_out=summaries,
        )
        if not ok and failed_reason is None:
            failed_reason = "OUTPUT_WRITE_FAILED"
        if target.is_file():
            written.append(target)

    # ★ 최종 fail-closed 계약(HIGH-1) — <b>산출물이 하나도 없으면 성공이 아니다</b>.
    # 개별 경로가 어떤 이유로든 실패를 놓쳐도(새 분기 추가·예외 삼킴), 결과물이 0건이면
    # 여기서 실패로 뒤집는다. "완료 보고 = 파일 실재"를 코드 한 곳에서 보장하는 안전망이다.
    #
    # ★ #4 — 판정 기준은 ``outputs``(계획)가 아니라 <b>``requested``(요청 항목 수)</b>다.
    # 구 조건 ``if outputs and not written`` 은 계획이 0건이면 통째로 건너뛰어, ``files:["/"]``
    # 처럼 <b>비어있지 않아 400 을 통과했지만 전부 정화 실패</b>한 요청이 데이터셋 0개 프로젝트로
    # 완료 보고됐다(BE 는 firstDataset=null 이라 완료를 인지하지 못한 채 폴링 예산 소진 → 'F').
    # ⚠ 정상 0건 경로를 거짓 실패로 만들지 않는다: is_img=1 은 항상 1건을 요청하고, 빈 ``files``
    #    는 라우터가 400 으로 차단하므로 ``requested=0`` 인 호출은 요청 축이 애초에 비어있는
    #    경우(직접 호출/래퍼)뿐이다.
    requested_count = len(outputs) if requested is None else requested
    if requested_count and not written and failed_reason is None:
        logger.warning(
            "[MOCK][KPST] deid 산출물이 한 건도 생성되지 않아 실패로 종결한다 "
            "requested=%d planned=%d",
            requested_count,
            len(outputs),
        )
        failed_reason = "NO_OUTPUT_PLANNED" if not outputs else "NO_OUTPUT_PRODUCED"
    return ProductionOutcome(
        written=written,
        failed=failed_reason is not None,
        reason=failed_reason,
        summaries=summaries,
    )


def write_deid_outputs(
    *,
    export_path: str,
    input_path: str,
    outputs: list[tuple[str, str]],
    output_base: str = "",
    input_base: str = "",
    requested: Optional[int] = None,
) -> list[Path]:
    """더미 비식별 출력 파일을 생성한다 — 기존 공개 계약 보존용 얇은 래퍼.

    동작 정본은 :func:`produce_deid_outputs` 이며 여기서는 산출 경로 목록만 돌려준다.
    ⚠ <b>요청 처리(HTTP 핸들러) 안에서 호출하지 말 것</b> — 구속 원칙(외부연동은 모두 비동기)에
    따라 산출은 ``spawn_production`` 이 띄운 백그라운드 태스크에서만 수행한다.

    Returns:
        생성/존재가 확인된 파일 경로 목록.
    """
    return produce_deid_outputs(
        export_path=export_path,
        input_path=input_path,
        outputs=outputs,
        output_base=output_base,
        input_base=input_base,
        requested=requested,
    ).written


# ── ★ 비동기 산출 러너 (구속 원칙: 외부연동은 모두 비동기) ──────────
# ``POST /project`` 는 프로젝트/데이터셋 등록(인메모리, 즉시)만 하고 여기로 넘긴다. 무거운 작업
# (ffmpeg 인코딩·파일 복사·ffprobe)은 전부 이 태스크 안에서 수행하며, 요청 처리는 그 완료를
# 기다리지 않는다. 진행 상황은 ``Project.production_state`` 로 노출되고
# ``retrieve_progress`` 가 그것을 읽어 진행중/완료/오류를 보고한다.
#
# ⛔ 되돌리지 말 것 — 요청 안에서 산출을 마치면 BE 클라이언트 타임아웃(45초) 안에 끝내야 하므로
#    ①산출물 절단(학습데이터 오염) ②위탁 실패 'F' + 같은 projectName 재요청 409 영구차단
#    둘 중 하나로 반드시 귀결된다.

#: 진행 중 산출 태스크 참조 보관 — asyncio 는 강한 참조가 없으면 태스크를 GC 할 수 있다.
_production_tasks: set["asyncio.Task[None]"] = set()

#: 동시 산출 태스크 상한 세마포어 — <b>이벤트 루프별</b>로 보관한다.
#: ``asyncio`` 동기화 객체는 처음 대기하는 순간 그 루프에 바인딩되며, 다른 루프에서 다시 대기하면
#: ``RuntimeError: bound to a different event loop`` 가 난다(루프를 새로 만드는 테스트/재기동에서
#: 산출이 통째로 죽는다). 루프를 키로 하는 약참조 맵으로 보관해 루프마다 독립 인스턴스를 쓴다.
_production_semaphores: "weakref.WeakKeyDictionary[Any, asyncio.Semaphore]" = (
    weakref.WeakKeyDictionary()
)


def _production_semaphore() -> asyncio.Semaphore:
    """현재 실행 중인 이벤트 루프의 산출 동시성 세마포어(없으면 생성)."""
    loop = asyncio.get_running_loop()
    semaphore = _production_semaphores.get(loop)
    if semaphore is None:
        semaphore = asyncio.Semaphore(PRODUCTION_MAX_CONCURRENCY)
        _production_semaphores[loop] = semaphore
    return semaphore


def spawn_production(prj_id: int, **params: Any) -> "asyncio.Task[None]":
    """산출 태스크를 띄우고 즉시 반환한다(요청 처리는 여기서 끝난다)."""
    task = asyncio.create_task(run_production(prj_id, **params), name=f"kpst-deid-{prj_id}")
    _production_tasks.add(task)
    task.add_done_callback(_production_tasks.discard)
    return task


def active_production_count() -> int:
    """진행 중(미완료) 산출 태스크 수 — 목 전용 관찰 지점."""
    return len([t for t in _production_tasks if not t.done()])


def production_capacity_available() -> bool:
    """새 산출 접수를 받아도 되는지(MEDIUM-3 — 미완료 태스크 총량 상한).

    라우터는 프로젝트를 만들기 <b>전에</b> 이 값을 확인해 초과 시 503 으로 거부한다. 접수를
    받아놓고 큐에 방치하면 BE 는 ``procState=1`` 만 계속 보다가 폴링 예산을 소진하고, 그때는
    이미 projectName 이 점유돼 재위탁이 409 로 영구 차단된다(조용한 고착).

    ★ #2 — 거부가 <b>무해한 것은 아니다</b>. 우리 BE 는 5xx 를 ``kpstDeid`` retry(3회·총 ~3초)만
    하고 소진 시 ``'F'`` 로 종결하며 자동 재위탁 큐가 없다(외부 수동). 그래서 503 은 "BE 가
    알아서 재시도하는 실패"가 아니라 <b>즉시 확정되는 실패</b>이며, 다만 이름을 점유하지 않아
    수동 재처리가 409 로 막히지 않는다는 점만 폴링 예산 소진보다 낫다.
    진짜 완화는 상한 자체를 배출률에서 파생시켜(``PRODUCTION_MAX_INFLIGHT``) 여기 도달할 일을
    줄이는 것이다.
    """
    return active_production_count() < PRODUCTION_MAX_INFLIGHT


async def cancel_all_productions() -> None:
    """진행 중 산출 태스크를 모두 취소하고 정리한다(shutdown)."""
    pending = [t for t in _production_tasks if not t.done()]
    for task in pending:
        task.cancel()
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)
    _production_tasks.clear()
    if pending:
        logger.info("[MOCK][KPST] production tasks cancelled count=%d", len(pending))


async def run_production(prj_id: int, **params: Any) -> None:
    """백그라운드 산출 1건 — 상태를 RUNNING → SUCCEEDED|FAILED 로 전이시킨다.

    블로킹 I/O(ffmpeg/복사/ffprobe)는 ``run_in_threadpool`` 로 오프로드한다. 이는 <b>백그라운드
    잡 안에서의</b> 오프로드라 비동기 원칙 위반이 아니다(요청을 붙잡지 않는다).

    프로젝트가 도중에 삭제되면(상태 기록 실패) 즉시 중단한다 — 없어진 프로젝트의 산출을 계속할
    이유가 없다.
    """
    store = get_store()
    from app.config import get_settings

    if not get_settings().write_output_files:
        # 설정상 쓰기 비활성 — 만들 것이 없으므로 성공 no-op(실패가 아니다).
        store.set_production_state(prj_id, PRODUCTION_SUCCEEDED)
        return

    try:
        async with _production_semaphore():
            if not store.set_production_state(prj_id, PRODUCTION_RUNNING):
                logger.info("[MOCK][KPST] production aborted — project gone prj_id=%d", prj_id)
                return
            outcome = await run_in_threadpool(
                lambda: produce_deid_outputs(**params)
            )
    except asyncio.CancelledError:
        logger.info("[MOCK][KPST] production cancelled prj_id=%d", prj_id)
        raise
    except Exception as exc:  # noqa: BLE001 — 어떤 실패도 서버로 전파하지 않는다
        logger.warning(
            "[MOCK][KPST] production error prj_id=%d type=%s", prj_id, type(exc).__name__
        )
        store.set_production_state(prj_id, PRODUCTION_FAILED)
        return

    if outcome.failed:
        logger.warning(
            "[MOCK][KPST] production failed prj_id=%d reason=%s",
            prj_id,
            sanitize_for_log(outcome.reason or "UNKNOWN"),
        )
        store.set_production_state(prj_id, PRODUCTION_FAILED)
        return
    # 실제 엔진이 낸 검출 요약을 프로젝트에 붙인다 — 리포트가 가짜 수 대신 이 값을 쓴다.
    # 엔진이 돌지 않았으면 빈 목록이라 리포트는 기존 mock 카운트로 폴백한다.
    if outcome.summaries:
        store.set_deid_summaries(prj_id, outcome.summaries)
    store.set_production_state(prj_id, PRODUCTION_SUCCEEDED)
    logger.info(
        "[MOCK][KPST] production completed prj_id=%d files=%d detected=%d",
        prj_id,
        len(outcome.written),
        len(outcome.summaries),
    )


def production_state_of(prj_id: int) -> Optional[str]:
    """프로젝트의 산출 상태 — 목 전용 관찰 지점(운영 로직은 store 를 직접 읽는다)."""
    return get_store().production_state_of(prj_id)


def wait_for_production(prj_id: int, timeout: float = 30.0) -> Optional[str]:
    """산출이 종결(SUCCEEDED|FAILED)될 때까지 기다린다 — <b>목 전용 관찰 지점</b>.

    산출은 이벤트 루프의 백그라운드 태스크가 수행하므로, 동기 컨텍스트(테스트/디버깅)에서
    결과를 확인하려면 폴링해야 한다. 프로덕션 요청 경로에서는 절대 호출하지 않는다 —
    호출하는 순간 그 경로가 다시 동기가 된다.

    Returns:
        종결 상태. 시간 안에 종결되지 않으면 마지막으로 관측한 상태(또는 None).
    """
    deadline = time.monotonic() + timeout
    state = production_state_of(prj_id)
    while state not in PRODUCTION_TERMINAL and time.monotonic() < deadline:
        time.sleep(0.02)
        state = production_state_of(prj_id)
    return state
