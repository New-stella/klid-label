"""미디어 길이(duration) 조회 — ffprobe 안전 호출 래퍼.

VLM describe 목이 "영상 실제 길이 전체를 덮는" 더미 시계열을 만들려면 대상 영상의 길이를 알아야
한다. 여기서는 ffprobe 로 초 단위 길이를 조회하되, **실패는 예외가 아니라 ``None``** 으로 알려
호출측(vlm_sim)이 고정 폴백으로 graceful degrade 하게 한다 — 목의 콜백 발사는 어떤 경우에도
멈추면 안 된다.

보안(``deid_sim`` 워터마크 subprocess 패턴과 동일 골격 — CWE-78/CWE-22):
- ``shell=False`` + **고정 리스트 인자** + ``stdin=DEVNULL`` + ``timeout`` 으로 호출한다. 사용자
  입력이 닿는 값은 미디어 경로 하나뿐이며, ``-`` 로 시작하는 값(ffprobe 가 **옵션**으로 오인식 =
  argument injection)과 상대경로는 실행 전에 거부한다.
- 절대경로를 ``resolve()`` 한 뒤 허용 루트(``MOCK_INPUT_BASE``, 미설정 시 ``MOCK_OUTPUT_BASE``
  각 항목의 상위) 하위인지 검증한다. 루트 판정은 **정책 단일 원천**
  (``path_policy.resolve_readable_dir``)에만 위임한다 — fail-closed 까지 그 안에 있다(F-7).
- **fail-closed**: 허용 루트가 하나도 설정돼 있지 않으면 조회 자체를 하지 않는다. 목 서버는
  인증이 없어 임의 절대경로를 넣을 수 있으므로, 루트가 없으면 "임의 파일 존재/길이 확인" 통로가
  열린다.
- **자원 상한(F-5, CWE-400/770)**: describe 는 무인증이고 접수 제한이 없어 요청마다 ffprobe 가
  뜬다. ffmpeg 와 **동형의 전역 세마포어 + 대기 상한**으로 동시 실행 수를 묶고, 자리를 얻지
  못하면 조회를 포기해 호출측이 폴백 길이를 쓰게 한다(콜백 발사는 멈추지 않는다).
- ``stderr`` 는 로그에 싣지 않는다(내부 경로/빌드 정보 노출 방지 — CWE-209/532).
"""

from __future__ import annotations

import logging
import math
import os
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Optional

from app.config import get_settings
from app.services import path_policy
from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

# ffprobe 실행 상한(초) — 무응답/손상 파일에 매달리지 않도록 짧게 둔다.
FFPROBE_TIMEOUT_SEC: int = 10

#: 동시에 실행할 수 있는 ffprobe 프로세스 수(용도별) — ffmpeg 상한과 동형(F-5).
FFPROBE_MAX_CONCURRENCY: int = 2

#: 세마포어 대기 상한(초, 용도별). 초과하면 조회를 포기하고 None 을 반환한다.
#:   - VLM describe: 콜백 발사가 멈추면 안 되므로 짧게 기다리고 폴백 길이를 쓴다.
#:   - KPST deid: <b>절단 검증</b>의 입력이라 실패하면 워터마킹을 포기해야 한다(#4). 비동기
#:     산출이라 서두를 이유가 없으므로 넉넉히 기다린다.
FFPROBE_ACQUIRE_TIMEOUT_SEC: float = 2.0
FFPROBE_DEID_ACQUIRE_TIMEOUT_SEC: float = 60.0

#: ★ 용도별 <b>독립</b> 세마포어 (#4, CWE-400 자원 격리).
#: 하나를 공유하면 무인증 describe 를 다발로 던져 자리를 포화시키는 것만으로 deid 의 길이 검증을
#: 외부에서 무력화할 수 있다(그 검증은 조용한 절단을 막는 유일한 게이트다). 용도를 분리해
#: 한쪽의 혼잡이 다른 쪽 판정에 영향을 주지 못하게 한다.
PURPOSE_VLM: str = "vlm"
PURPOSE_DEID: str = "deid"

_SEMAPHORES: dict[str, threading.BoundedSemaphore] = {
    PURPOSE_VLM: threading.BoundedSemaphore(FFPROBE_MAX_CONCURRENCY),
    PURPOSE_DEID: threading.BoundedSemaphore(FFPROBE_MAX_CONCURRENCY),
}


def semaphore_for(purpose: str) -> threading.BoundedSemaphore:
    """용도별 ffprobe 세마포어. 미지의 용도는 VLM(기본) 것을 쓴다."""
    return _SEMAPHORES.get(purpose, _SEMAPHORES[PURPOSE_VLM])


def _acquire_timeout_for(purpose: str) -> float:
    """용도별 세마포어 대기 상한."""
    if purpose == PURPOSE_DEID:
        return FFPROBE_DEID_ACQUIRE_TIMEOUT_SEC
    return FFPROBE_ACQUIRE_TIMEOUT_SEC


def reset_base_warning() -> None:
    """허용 루트 미설정 경고 플래그를 초기화한다(테스트용).

    플래그는 판정 단일 원천(``path_policy``)이 소유한다.
    """
    path_policy.reset_warning()


def _is_safe_cli_path(text: str) -> bool:
    """CLI 인자로 넘겨도 안전한 경로인지 — 절대경로이며 ``-`` 로 시작하지 않아야 한다.

    ``-`` 로 시작하는 인자는 ffprobe 가 **옵션**으로 오인식한다(argument injection).
    """
    return os.path.isabs(text) and not text.startswith("-")


def resolve_probe_target(media_path: object, allowed_base: str) -> Optional[Path]:
    """조회 대상 경로를 정화·검증해 정규화된 파일 경로를 반환한다(CWE-22).

    아래 중 하나라도 걸리면 ``None`` (조회 안 함):
    비문자열/빈값, 상대경로, ``-`` 로 시작, 허용 루트 미설정(fail-closed), 정규화 실패,
    허용 루트 밖, 일반 파일이 아님(디렉터리/특수 파일).
    """
    if isinstance(media_path, Path):
        text = os.fspath(media_path)
    elif isinstance(media_path, str):
        text = media_path.strip()
    else:
        return None
    if not text or not _is_safe_cli_path(text):
        return None

    try:
        resolved = Path(text).resolve()
    except (OSError, ValueError):
        return None
    # 허용 루트 판정 + fail-closed 를 정책 단일 진입점에 위임한다(F-7 — 여기서 재구현하지 않는다).
    in_dir = path_policy.resolve_readable_dir(os.fspath(resolved.parent), allowed_base)
    if in_dir is None or resolved.parent != in_dir:
        return None
    try:
        if not resolved.is_file():
            return None
    except OSError:
        return None
    return resolved


def parse_duration(raw: object) -> Optional[float]:
    """ffprobe 출력(초)을 양의 유한 실수로 파싱한다. 이상값은 ``None``.

    ``N/A``/빈값/``0``/음수/``nan``/``inf`` 는 모두 신뢰할 수 없으므로 폴백 대상이다.
    """
    if isinstance(raw, bytes):
        text = raw.decode("utf-8", errors="replace")
    elif isinstance(raw, str):
        text = raw
    else:
        return None
    try:
        duration = float(text.strip())
    except (TypeError, ValueError):
        return None
    if not math.isfinite(duration) or duration <= 0:
        return None
    return duration


def probe_duration_sec(
    media_path: object,
    allowed_base: Optional[str] = None,
    *,
    purpose: str = PURPOSE_VLM,
) -> Optional[float]:
    """미디어 파일의 재생 길이(초)를 ffprobe 로 조회한다. 실패하면 ``None``.

    Args:
        media_path: 대상 미디어 절대경로. 허용 루트 안의 일반 파일만 조회한다.
        allowed_base: 읽기 허용 루트(콤마 구분). 생략 시 설정값
            (``MOCK_INPUT_BASE`` → 없으면 ``MOCK_OUTPUT_BASE`` 각 항목의 상위)을 쓴다.
        purpose: 자원 격리 축(#4). ``PURPOSE_VLM``(describe 폴백 가능) /
            ``PURPOSE_DEID``(절단 검증 — 실패하면 워터마킹을 포기해야 한다). 용도마다
            <b>독립 세마포어</b>와 대기 상한을 쓴다.

    Returns:
        양의 유한 실수(초). 경로 거부/ffprobe 부재/실행 실패/타임아웃/비정상 종료/
        출력 파싱 실패 시 ``None`` — 호출측이 폴백한다.
    """
    tag = purpose.upper()
    base = get_settings().effective_input_base() if allowed_base is None else allowed_base
    target = resolve_probe_target(media_path, base)
    if target is None:
        return None

    ffprobe = shutil.which("ffprobe")
    if ffprobe is None:
        logger.warning(
            "[MOCK][%s] ffprobe 바이너리 없음 — 미디어 길이 조회 생략(폴백 사용) file=%s",
            tag,
            sanitize_for_log(target.name),
        )
        return None

    # TOCTOU: subprocess 호출 직전에 경계를 독립적으로 재검증한다(심볼릭 링크 교체 등).
    target = resolve_probe_target(target, base)
    if target is None:
        return None

    cmd = [
        ffprobe,
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        "-i", os.fspath(target),
    ]
    # F-5 — ffmpeg 와 동형의 동시 실행 상한. 자리를 못 얻으면 조회를 포기한다.
    # #4 — 세마포어는 <b>용도별로 분리</b>돼 있어 describe 혼잡이 deid 판정을 굶기지 못한다.
    semaphore = semaphore_for(purpose)
    if not semaphore.acquire(timeout=_acquire_timeout_for(purpose)):
        logger.warning(
            "[MOCK][%s] ffprobe 동시 실행 상한(%d) 대기 초과 — 조회 포기 file=%s",
            tag,
            FFPROBE_MAX_CONCURRENCY,
            sanitize_for_log(target.name),
        )
        return None
    try:
        completed = subprocess.run(  # noqa: S603 — 고정 인자 리스트, shell 미사용
            cmd,
            shell=False,
            capture_output=True,
            stdin=subprocess.DEVNULL,
            timeout=FFPROBE_TIMEOUT_SEC,
            check=False,
        )
    except subprocess.TimeoutExpired:
        logger.warning(
            "[MOCK][%s] ffprobe timeout(%ds) — 폴백 길이 사용 file=%s",
            tag,
            FFPROBE_TIMEOUT_SEC,
            sanitize_for_log(target.name),
        )
        return None
    except (OSError, subprocess.SubprocessError) as exc:
        logger.warning(
            "[MOCK][%s] ffprobe 실행 실패 — 폴백 길이 사용 file=%s type=%s",
            tag,
            sanitize_for_log(target.name),
            type(exc).__name__,
        )
        return None
    finally:
        semaphore.release()

    if completed.returncode != 0:
        # stderr 원문은 남기지 않는다 — 내부 경로/빌드 정보가 섞여 나온다(CWE-209).
        logger.warning(
            "[MOCK][%s] ffprobe 비정상 종료(rc=%d) — 폴백 길이 사용 file=%s",
            tag,
            completed.returncode,
            sanitize_for_log(target.name),
        )
        return None

    duration = parse_duration(completed.stdout)
    if duration is None:
        logger.warning(
            "[MOCK][%s] ffprobe duration 파싱 실패 — 폴백 길이 사용 file=%s",
            tag,
            sanitize_for_log(target.name),
        )
        return None
    logger.info(
        "[MOCK][%s] media duration probed file=%s duration=%.3fs",
        tag,
        sanitize_for_log(target.name),
        duration,
    )
    return duration
