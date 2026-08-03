"""
생성형 AI 벤더 목 — 작업 진행 시뮬레이션 + 결과 파일 생성 + webhook 발신.

「생성형 AI API 연동명세서 v1.1」 §3.2 상태머신을 백그라운드로 진행한다:

    RECEIVED ─[내부 큐 대기]→ (슬롯 확보) ─(단계 지연)→ RUNNING(전처리/추론/후처리)
                                                            └→ SUCCEEDED | FAILED
                                     └─ 취소 시 CANCELED 로 종결(재전이 없음)

**내부 큐(벤더 동작 모사)**: 실제 벤더는 요청을 접수만 하고 내부 큐에서 동시 처리 슬롯 수만큼만
실행한다. 목도 ``JobScheduler`` 로 같은 동작을 한다 — 접수는 **언제나 즉시 202 RECEIVED** 이고
(§4.1 계약), 슬롯이 없으면 FIFO 대기열에서 ``RECEIVED`` 로 머물다 슬롯이 나면 RUNNING 으로
전이한다. 대기 중 취소된 작업은 큐에서 제거되어 **슬롯을 소모하지 않는다**.

각 단계 전이마다 요청의 ``callback_url`` 로 ② 진행·결과 Webhook 을 POST 한다.
③ status-sync 는 **자동 발신하지 않고** 목 전용 수동 트리거로만 보낸다(대상은 환경변수).

파일 교환(§5.3): 파일 본문은 API 로 주고받지 않는다. 목은 입력 NAS 경로의 파일을
결과 경로로 **실제 복사**해 산출물을 만들고 그 절대경로만 통보한다.

보안:
- CWE-22 경로 탈출 — 입력 경로는 입력 base 하위만 허용(접수 시 400, base 미설정이면
  fail-closed), 출력은 항상 ``{output_base}/genai/{job_id}/`` 하위. 출력 base 미설정이면 fail-closed.
- CWE-367/59 TOCTOU·심볼릭링크 — 접수 시 통과한 경로라도 **처리 시점에 다시 검증**하고,
  입력은 ``O_NOFOLLOW`` 로 열어 fd 기준(``fstat``)으로 정규파일·크기를 재확인한다.
- CWE-918 SSRF — callback_url / status-sync 대상은 스킴(http|https) + ``host[:port]`` allowlist
  + (선택) 경로 접두사 검증. 목 서버 자신을 가리키는 대상은 allowlist 에 있어도 거부한다.
- CWE-117 로그 인젝션 — 사용자 입력은 ``sanitize_for_log`` 경유 후 로깅.
- CWE-400 자원 고갈 — 복사 바이트 수를 입력 상한으로 제한하고, 종결(취소) 확정 시 생성된
  산출물을 정리해 고아 파일을 남기지 않는다.
- 무한 재시도 금지 — webhook 전송은 시도 횟수 상한(설정) 안에서만 재시도하고 실패해도
  작업 진행/서버 안정성에 영향을 주지 않는다.
- CWE-770 동시 처리 상한 — 내부 큐가 동시 실행(파일 복사·해시 등 threadpool I/O) 수를
  ``MOCK_GENAI_MAX_CONCURRENT_JOBS`` 로 제한한다. 대기분은 태스크가 future 하나에 매달려
  있을 뿐이라 큐 도입 전(접수 즉시 전 작업 병렬 실행)보다 자원 사용이 늘지 않는다.
  대기열 자체도 잡 저장소 상한(``MOCK_GENAI_MAX_JOBS``)이 만료시킨 작업은 배정 시점에
  버려지므로 무한히 자라지 않는다.
"""

from __future__ import annotations

import asyncio
import contextlib
import errno
import hashlib
import logging
import os
import stat
import uuid
from collections import deque
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse

import httpx
from starlette.concurrency import run_in_threadpool

from app.config import get_settings
from app.schemas.genai import ErrorCode, GenerationMode, JobStatus, MediaType
from app.state import GenAiJob, GenAiJobStore, get_genai_store, sanitize_for_log

logger = logging.getLogger(__name__)

# webhook HTTP 타임아웃(초) — 대상 무응답 대비 상한.
_WEBHOOK_TIMEOUT_SEC = 5.0

# 심볼릭링크 추종 금지 플래그 — POSIX 전용. 비POSIX(Windows)에서는 0 으로 축약되지만
# 목 서버 배포 대상은 리눅스/도커라 실효 방어는 유지된다.
_O_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)

# 결과 파일 접미사 + 생성 결과 placeholder 바이트(T2I/T2V 처럼 입력 파일이 없는 경우).
_OUTPUT_SUFFIX = "_genai"
PLACEHOLDER_BYTES: bytes = b"MOCK_GENAI_GENERATED\n"

# 미디어 유형별 확장자 — 입력 확장자가 유형과 맞지 않으면 기본 확장자를 쓴다.
_IMAGE_EXTS = frozenset({".png", ".jpg", ".jpeg", ".webp", ".bmp"})
_VIDEO_EXTS = frozenset({".mp4", ".mov", ".avi", ".mkv", ".webm"})
_DEFAULT_EXT = {MediaType.IMAGE: ".png", MediaType.VIDEO: ".mp4"}
_MIME_BY_EXT = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
    ".avi": "video/x-msvideo",
    ".mkv": "video/x-matroska",
    ".webm": "video/webm",
}

# 진행 단계 — (progress, current_step). 마지막 SUCCEEDED 전이는 별도.
PROGRESS_STEPS: tuple[tuple[int, str], ...] = (
    (10, "PREPROCESS"),
    (50, "INFERENCE"),
    (90, "POSTPROCESS"),
)
COMPLETED_STEP = "COMPLETED"


class JobExecutionError(Exception):
    """작업 처리 실패 — FAILED 전이에 사용할 명세서 오류코드를 함께 전달한다."""

    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message


def get_job_store() -> GenAiJobStore:
    """전역 생성형 AI 작업 저장소."""
    return get_genai_store()


# ── 내부 큐 스케줄러 (벤더 동작 모사) ─────────────────────────────
class JobScheduler:
    """FIFO 대기열 + 동시 처리 슬롯 상한.

    실제 증강 벤더는 요청을 접수만 하고 내부 큐에서 순차 처리한다. 목도 접수(202)와 **처리 시작**을
    분리해 슬롯이 빌 때까지 ``RECEIVED`` 로 대기시킨다.

    동시성 — 이 객체는 **단일 이벤트 루프 안에서만** 다뤄진다(라우터 핸들러 + 잡 태스크). 상태를
    바꾸는 메서드는 모두 동기이고 내부에 await 지점이 없어 원자적이다. 그래서 별도 락이 없다.
    (저장소 ``GenAiJobStore`` 는 threadpool 에서도 접근하므로 거기엔 락이 있다.)
    """

    def __init__(self, max_concurrency: Optional[int] = None) -> None:
        #: None 이면 설정값(``MOCK_GENAI_MAX_CONCURRENT_JOBS``)을 매번 읽는다(테스트에서 변경 가능).
        self._max_concurrency = max_concurrency
        self._waiting: deque[str] = deque()
        self._grants: dict[str, asyncio.Future[bool]] = {}
        self._running: set[str] = set()

    def limit(self) -> int:
        """동시 처리 슬롯 수 — 생성자 지정값 우선, 없으면 설정값."""
        if self._max_concurrency is not None:
            return self._max_concurrency
        return get_settings().genai_max_concurrent_jobs

    def enqueue(self, job_id: str) -> None:
        """접수 시점에 대기열 **맨 뒤**에 등록한다(FIFO 기준 = 접수 순서).

        태스크 실행 순서에 기대지 않도록 큐 등록은 접수 핸들러에서 동기적으로 수행한다.
        슬롯 여유가 있으면 곧바로 배정되므로 대기 없이 진행한다.
        """
        if job_id in self._grants or job_id in self._running:
            return  # 멱등 — 중복 등록 방지
        self._grants[job_id] = asyncio.get_running_loop().create_future()
        self._waiting.append(job_id)
        self._pump()

    async def wait_for_slot(self, job_id: str) -> bool:
        """슬롯이 배정될 때까지 대기한다.

        Returns:
            True 면 슬롯 확보(처리 시작), False 면 대기 중 제거됨(취소/리셋) — 진행하지 않는다.
        """
        future = self._grants.get(job_id)
        if future is None:
            # 큐를 거치지 않은 직접 실행 — 슬롯만 점유하고 즉시 진행한다(하위호환).
            self._running.add(job_id)
            return True
        try:
            return await future
        except asyncio.CancelledError:
            self._forget(job_id)
            raise

    def release(self, job_id: str) -> None:
        """슬롯을 반납하고 대기 선두에 배정한다(처리 종료 시 반드시 호출)."""
        self._running.discard(job_id)
        self._forget(job_id)
        self._pump()

    def drop(self, job_id: str) -> bool:
        """**대기 중인** 작업을 큐에서 제거한다(취소).

        슬롯을 점유한 적이 없으므로 반납할 것도 없다 — 취소분이 슬롯을 소모하지 않는다.

        Returns:
            큐에서 제거했으면 True. 이미 처리 중이거나 큐에 없으면 False(진행 태스크가 종결
            상태를 만나 스스로 멈춘다).
        """
        if job_id in self._running:
            return False
        future = self._grants.get(job_id)
        if future is None:
            return False
        self._forget(job_id)
        if not future.done():
            future.set_result(False)
        return True

    def stats(self) -> dict[str, Any]:
        """[목 전용] 큐 관측 지표."""
        return {
            "max_concurrency": self.limit(),
            "running": len(self._running),
            "waiting": len(self._waiting),
            "waiting_job_ids": list(self._waiting),
        }

    def reset(self) -> None:
        """대기·실행 상태를 모두 비운다(shutdown / 목 reset).

        정리 실패가 종료 경로를 막으면 안 되므로(fail-safe), 이미 닫힌 이벤트 루프에 매달린
        future 처럼 되살릴 수 없는 항목은 그냥 버린다.
        """
        for future in self._grants.values():
            if not future.done():
                with contextlib.suppress(RuntimeError, asyncio.InvalidStateError):
                    future.set_result(False)
        self._grants.clear()
        self._waiting.clear()
        self._running.clear()

    def _forget(self, job_id: str) -> None:
        """대기열/배정 색인에서 작업 흔적을 지운다."""
        self._grants.pop(job_id, None)
        try:
            self._waiting.remove(job_id)
        except ValueError:
            pass

    def _pump(self) -> None:
        """슬롯 여유만큼 대기 선두부터 배정한다."""
        limit = self.limit()
        while self._waiting and len(self._running) < limit:
            job_id = self._waiting.popleft()
            future = self._grants.get(job_id)
            if future is None or future.done():
                continue  # 이미 취소·제거된 대기분 — 슬롯을 쓰지 않는다
            self._running.add(job_id)
            future.set_result(True)


# 프로세스 전역 스케줄러 — 라우터/러너가 공유한다.
_scheduler: JobScheduler = JobScheduler()


def get_scheduler() -> JobScheduler:
    """전역 작업 스케줄러(내부 큐)."""
    return _scheduler


def on_job_canceled(job_id: str) -> bool:
    """취소가 확정된 작업을 대기열에서 제거한다. 대기 중이 아니었으면 False."""
    dropped = get_scheduler().drop(job_id)
    if dropped:
        logger.info(
            "[MOCK][GENAI] queued job removed by cancel job_id=%s", sanitize_for_log(job_id)
        )
    return dropped


# ── 백그라운드 태스크 레지스트리 (HIGH-1) ─────────────────────────
# asyncio 는 태스크에 대한 강한 참조가 없으면 GC 로 사라질 수 있으므로 참조를 보관하고,
# 앱 종료(lifespan shutdown) 시 모두 취소해 누수를 막는다.
_tasks: set[asyncio.Task] = set()


def spawn_job_task(job_id: str) -> asyncio.Task:
    """작업을 내부 큐에 등록하고 진행 태스크를 생성해 레지스트리에 보관한다.

    큐 등록(``enqueue``)은 **접수 순서를 그대로 보존**하기 위해 태스크 생성 전에 동기 수행한다.
    """
    get_scheduler().enqueue(job_id)
    task = asyncio.create_task(run_job(job_id), name=f"genai-job-{job_id}")
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return task


def active_task_count() -> int:
    """진행 중(미완료) 태스크 수 — 목 전용 관찰 지점."""
    return len([t for t in _tasks if not t.done()])


async def cancel_all_tasks() -> None:
    """진행 중 태스크를 모두 취소하고 정리한다(shutdown / 목 reset)."""
    pending = [t for t in _tasks if not t.done()]
    for task in pending:
        task.cancel()
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)
    _tasks.clear()
    # 대기열/슬롯도 함께 비운다 — 태스크가 사라진 뒤 남은 배정은 모두 고아다.
    get_scheduler().reset()
    if pending:
        logger.info("[MOCK][GENAI] background tasks cancelled count=%d", len(pending))


# ── 보안 가드 ─────────────────────────────────────────────────────
_DEFAULT_PORT_BY_SCHEME = {"http": 80, "https": 443}


def _split_allow_entry(entry: str) -> tuple[str, Optional[int]]:
    """allowlist 항목을 ``(host, port|None)`` 로 분해한다.

    지원 형태:
        - ``backend``          → ("backend", None)  : 모든 포트 허용(하위호환)
        - ``backend:8080``     → ("backend", 8080)
        - ``[::1]:8080``       → ("::1", 8080)
        - ``::1``              → ("::1", None)      : 대괄호 없는 IPv6 는 호스트로만 해석
    """
    item = entry.strip().lower()
    if not item:
        return "", None
    if item.startswith("["):  # [IPv6] 또는 [IPv6]:port
        host, _, rest = item[1:].partition("]")
        if rest.startswith(":") and rest[1:].isdigit():
            return host, int(rest[1:])
        return host, None
    head, sep, tail = item.rpartition(":")
    if sep and head and ":" not in head and tail.isdigit():
        return head, int(tail)
    return item, None


def _is_self_target(host: str, port: Optional[int]) -> bool:
    """대상이 목 서버 자신인지 판정한다(자기 SSRF 차단 — `_mock/*` 상태 파괴 방지)."""
    settings = get_settings()
    if port is not None and port != settings.port:
        return False
    return host in settings.genai_self_host_aliases_set()


def is_allowed_url(
    url: str,
    allow_hosts: list[str],
    *,
    path_prefixes: Optional[list[str]] = None,
    check_self: bool = True,
) -> bool:
    """SSRF(CWE-918) 가드 — 스킴 http|https + ``host[:port]`` allowlist + 경로 접두사 검사.

    - allowlist 가 비어 있으면 fail-closed(모두 차단).
    - ``*`` 가 포함되면 호스트/포트 검사를 생략한다(로컬 실험용 — README 에 위험 명시).
    - allowlist 항목에 포트를 적으면 **그 포트만** 허용한다. 포트를 생략한 항목은 모든 포트를
      허용한다(하위호환 — README/.env.example 에 명시).
    - ``check_self`` 가 참이면 목 서버 자신을 가리키는 대상은 allowlist 에 있어도 거부한다.
    """
    if not url:
        return False
    try:
        parsed = urlparse(url)
        host = (parsed.hostname or "").lower()
        port = parsed.port
    except ValueError:
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    if not host:
        return False

    effective_port = port if port is not None else _DEFAULT_PORT_BY_SCHEME[parsed.scheme]
    if check_self and _is_self_target(host, effective_port):
        return False

    prefixes = path_prefixes if path_prefixes is not None else _callback_path_prefixes()
    if prefixes and not any((parsed.path or "/").startswith(p) for p in prefixes):
        return False

    if not allow_hosts:
        return False
    if "*" in allow_hosts:
        return True
    for entry in allow_hosts:
        allow_host, allow_port = _split_allow_entry(entry)
        if allow_host != host:
            continue
        if allow_port is None or allow_port == effective_port:
            return True
    return False


def _callback_path_prefixes() -> list[str]:
    """설정된 콜백 경로 접두사 목록(빈 리스트면 제한 없음)."""
    return get_settings().genai_callback_path_prefixes_list()


def resolve_input_path(file_path: str, input_base: str) -> Optional[Path]:
    """입력 경로를 정규화하고 입력 base 하위인지 검증한다(CWE-22).

    입력 base 가 설정되지 않으면 **fail-closed** 로 거부한다(F-6). 예전에는 임의 절대경로를
    통과시켜 파일 존재·크기 오라클(413 vs 202)이 되고, 폴백 로직이 바뀌면 즉시 임의 파일
    읽기로 승격될 수 있었다.

    Returns:
        검증을 통과한 절대경로. 상대경로/base 밖/base 미설정/정규화 실패면 None
        (→ 400 INVALID_PARAMETER).
    """
    if not file_path:
        return None
    if not input_base:
        return None
    candidate = Path(file_path)
    if not candidate.is_absolute():
        return None
    try:
        resolved = candidate.resolve()
    except (OSError, ValueError):
        return None
    try:
        base_resolved = Path(input_base).resolve()
        resolved.relative_to(base_resolved)
    except (OSError, ValueError):
        return None
    return resolved


def is_failure_trigger(request_id: str) -> bool:
    """결정적 FAILED 트리거 — ``request_id`` 가 "fail"(대소문자 무시)로 시작하면 실패 처리.

    VLM 목(``vlm_sim.is_failure_trigger``)과 동일한 관례다. 접수(202)는 규격대로 성공하고
    실패는 상태/Webhook 으로만 전달된다.
    """
    return (request_id or "").lower().startswith("fail")


# ── 결과 파일 생성 (§5.3 — 경로만 통보, 파일은 공유 스토리지에) ──
def media_type_of(generation_mode: str) -> MediaType:
    """생성 모드 → 결과 미디어 유형(T2I·I2I=IMAGE, T2V·I2V=VIDEO)."""
    if generation_mode in (GenerationMode.T2V.value, GenerationMode.I2V.value):
        return MediaType.VIDEO
    return MediaType.IMAGE


def _output_extension(source: Optional[Path], media_type: MediaType) -> str:
    """결과 확장자 — 입력 확장자가 미디어 유형과 맞으면 유지, 아니면 유형 기본값."""
    allowed = _IMAGE_EXTS if media_type is MediaType.IMAGE else _VIDEO_EXTS
    if source is not None:
        ext = source.suffix.lower()
        if ext in allowed:
            return ext
    return _DEFAULT_EXT[media_type]


def _sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _open_source_nofollow(source: Path, max_bytes: int) -> int:
    """입력 파일을 심볼릭링크 추종 없이 열고 fd 기준으로 재검증한다(F-1/F-7).

    ``O_NOFOLLOW`` 로 마지막 경로 요소가 심볼릭링크면 열지 않고(ELOOP), 열린 뒤에는
    **경로가 아니라 fd** 를 기준으로 ``fstat`` 해 정규파일 여부와 크기를 다시 확인한다.
    접수 시점 검사(TOCTOU 창)를 신뢰하지 않는다.

    Raises:
        OSError: 링크/부재/비정규파일/크기 초과.
    """
    fd = os.open(source, os.O_RDONLY | _O_NOFOLLOW)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise OSError(errno.EINVAL, "입력이 정규 파일이 아닙니다")
        if st.st_size > max_bytes:
            raise OSError(errno.EFBIG, "입력 파일 크기가 허용 한도를 초과했습니다")
    except BaseException:
        os.close(fd)
        raise
    return fd


def _copy_limited(src: Any, dst: Any, max_bytes: int) -> int:
    """복사 바이트 수를 상한으로 제한하며 스트림을 복사한다(CWE-400).

    Raises:
        OSError: 상한 초과(처리 중 파일이 커지는 경우 포함).
    """
    chunk_size = 1024 * 1024
    copied = 0
    while True:
        chunk = src.read(chunk_size)
        if not chunk:
            return copied
        copied += len(chunk)
        if copied > max_bytes:
            raise OSError(errno.EFBIG, "입력 파일 크기가 허용 한도를 초과했습니다")
        dst.write(chunk)


class _SourceReadError(Exception):
    """입력 파일 읽기 실패 — MODEL_EXECUTION_FAILED 로 매핑한다."""


def _write_output(target: Path, source: Optional[Path], *, max_bytes: int) -> None:
    """결과 파일을 생성한다 — 원본이 있으면 복사, 없으면 placeholder(덮어쓰기 금지).

    입력은 ``O_NOFOLLOW`` + ``fstat`` 로 재검증하고, 실패하면 부분 산출물을 남기지 않는다.
    """
    try:
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | _O_NOFOLLOW, 0o644)
    except FileExistsError:
        return  # 멱등 재실행 — 기존 산출물 유지
    completed = False
    try:
        with os.fdopen(fd, "wb") as dst:
            if source is None:
                dst.write(PLACEHOLDER_BYTES)
            else:
                try:
                    src_fd = _open_source_nofollow(source, max_bytes)
                except OSError as exc:
                    raise _SourceReadError(str(exc)) from exc
                with os.fdopen(src_fd, "rb") as src:
                    try:
                        _copy_limited(src, dst, max_bytes)
                    except OSError as exc:
                        raise _SourceReadError(str(exc)) from exc
        completed = True
    finally:
        if not completed:  # 부분 산출물 제거 — 실패 시 디스크에 잔재를 남기지 않는다
            with contextlib.suppress(OSError):
                os.unlink(target)


def discard_results(results: list[dict[str, Any]]) -> None:
    """생성된 산출물을 정리한다(취소 확정 등 — 고아 파일 방지, F-11)."""
    parents: set[Path] = set()
    for item in results:
        path_value = item.get("output_file_path")
        if not path_value:
            continue
        path = Path(str(path_value))
        parents.add(path.parent)
        with contextlib.suppress(OSError):
            os.unlink(path)
    for parent in parents:
        with contextlib.suppress(OSError):
            os.rmdir(parent)  # 비어 있을 때만 성공


def build_results(
    job: GenAiJob,
    output_base: str,
    *,
    input_base: Optional[str] = None,
    max_input_bytes: Optional[int] = None,
) -> list[dict[str, Any]]:
    """결과 파일을 실제로 생성하고 §4.2 results[] 항목 목록을 만든다(동기 I/O).

    접수 시점의 경로 검증을 **다시 수행**하고(TOCTOU 방어, F-1) 입력은 심볼릭링크를 추종하지
    않고 연다. 크기 상한도 fd 기준으로 재확인한다(F-7).

    Raises:
        JobExecutionError: 출력 base 미설정(RESULT_SAVE_FAILED), 입력 경로 재검증 실패/파일
            부재/링크/크기 초과(MODEL_EXECUTION_FAILED), 쓰기 실패(RESULT_SAVE_FAILED).
    """
    if not output_base:
        raise JobExecutionError(
            ErrorCode.RESULT_SAVE_FAILED.value,
            "결과 저장 경로가 설정되지 않았습니다(MOCK_GENAI_OUTPUT_BASE)",
        )

    settings = get_settings()
    effective_input_base = (
        input_base if input_base is not None else settings.genai_effective_input_base()
    )
    max_bytes = max_input_bytes if max_input_bytes is not None else settings.genai_max_input_bytes

    try:
        base = Path(output_base).resolve()
        out_dir = (base / "genai" / job.job_id).resolve()
        out_dir.relative_to(base)  # 심층 방어 — 출력은 반드시 base 하위
        out_dir.mkdir(parents=True, exist_ok=True)
    except (OSError, ValueError) as exc:
        raise JobExecutionError(
            ErrorCode.RESULT_SAVE_FAILED.value, "결과 저장 경로를 준비하지 못했습니다"
        ) from exc

    media_type = media_type_of(job.generation_mode)
    # 입력이 없는 생성 모드(T2I/T2V)는 산출물 1건을 새로 만든다.
    sources: list[tuple[int, Optional[Path]]] = [
        (seq, _revalidate_source(path, effective_input_base)) for seq, path in job.input_files
    ] or [(1, None)]

    results: list[dict[str, Any]] = []
    for sequence, source in sources:
        stem = source.stem if source is not None else "generated"
        # basename 기준 조립 — 경로 구분자/상위 탈출이 파일명에 남지 않는다(CWE-22).
        safe_stem = os.path.basename(stem) or "generated"
        ext = _output_extension(source, media_type)
        target = out_dir / f"{sequence:03d}_{safe_stem}{_OUTPUT_SUFFIX}{ext}"
        try:
            target.resolve().relative_to(out_dir)
            _write_output(target, source, max_bytes=max_bytes)
        except _SourceReadError as exc:
            discard_results(results)
            raise JobExecutionError(
                ErrorCode.MODEL_EXECUTION_FAILED.value,
                "입력 파일을 읽을 수 없습니다",
            ) from exc
        except (OSError, ValueError) as exc:
            discard_results(results)
            raise JobExecutionError(
                ErrorCode.RESULT_SAVE_FAILED.value, "결과 파일을 저장하지 못했습니다"
            ) from exc

        results.append(
            {
                "generated_data_id": uuid.uuid4().hex,
                "media_type": media_type.value,
                "output_file_path": str(target),
                "checksum": _sha256_of(target),
                "media_metadata": {
                    "mime_type": _MIME_BY_EXT.get(ext, "application/octet-stream"),
                    "size_bytes": target.stat().st_size,
                },
            }
        )
    return results


def _revalidate_source(file_path: str, input_base: str) -> Path:
    """저장된 입력 경로를 **처리 시점에 다시** base 소속으로 검증한다(TOCTOU 방어, F-1).

    Raises:
        JobExecutionError: base 밖/부재/정규화 실패(MODEL_EXECUTION_FAILED).
    """
    resolved = resolve_input_path(file_path, input_base)
    if resolved is None or not resolved.is_file():
        logger.warning(
            "[MOCK][GENAI] input path rejected at processing time path=%s",
            sanitize_for_log(file_path),
        )
        raise JobExecutionError(
            ErrorCode.MODEL_EXECUTION_FAILED.value, "입력 파일을 찾을 수 없습니다"
        )
    return resolved


# ── ② Webhook / ③ status-sync 발신 ───────────────────────────────
def build_webhook_payload(job: GenAiJob) -> dict[str, Any]:
    """§4.2/§4.3 발신 페이로드를 만든다(FAILED 는 error_code/message 필수)."""
    payload: dict[str, Any] = {
        "request_id": job.request_id,
        "job_id": job.job_id,
        "status": job.status,
        "progress": job.progress,
        "current_step": job.current_step,
        "updated_at": job.updated_at,
    }
    if job.results:
        payload["results"] = job.results
    if job.error_code:
        payload["error_code"] = job.error_code
        payload["error_message"] = job.error_message or ""
    return payload


async def _post_once(url: str, payload: dict[str, Any]) -> int:
    """1회 POST 시도 — HTTP 상태코드를 반환한다(전송 오류는 예외 전파)."""
    async with httpx.AsyncClient(timeout=_WEBHOOK_TIMEOUT_SEC) as client:
        response = await client.post(url, json=payload)
    return response.status_code


async def send_webhook(url: str, payload: dict[str, Any]) -> bool:
    """webhook 을 발신한다. 실패해도 예외를 전파하지 않고 재시도 상한 안에서만 재시도한다.

    수신측이 401/404 를 주거나 다운되어도 목 서버는 계속 진행한다(HIGH-5).
    """
    settings = get_settings()
    attempts = settings.genai_webhook_max_attempts
    retry_delay = settings.genai_webhook_retry_delay_sec
    job_id = str(payload.get("job_id", "")) if isinstance(payload, dict) else ""

    for attempt in range(1, attempts + 1):
        try:
            status_code = await _post_once(url, payload)
            if 200 <= status_code < 300:
                logger.info(
                    "[MOCK][GENAI] webhook sent url=%s status=%d job_id=%s attempt=%d",
                    sanitize_for_log(url),
                    status_code,
                    sanitize_for_log(job_id),
                    attempt,
                )
                return True
            logger.warning(
                "[MOCK][GENAI] webhook rejected url=%s status=%d job_id=%s attempt=%d",
                sanitize_for_log(url),
                status_code,
                sanitize_for_log(job_id),
                attempt,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 — 발신 실패는 목 서버에 전파 금지
            logger.warning(
                "[MOCK][GENAI] webhook failed url=%s type=%s job_id=%s attempt=%d",
                sanitize_for_log(url),
                type(exc).__name__,
                sanitize_for_log(job_id),
                attempt,
            )
        if attempt < attempts and retry_delay > 0:
            await asyncio.sleep(retry_delay)

    logger.warning(
        "[MOCK][GENAI] webhook give-up(no more retry) url=%s job_id=%s attempts=%d",
        sanitize_for_log(url),
        sanitize_for_log(job_id),
        attempts,
    )
    return False


async def emit_webhook(job: GenAiJob) -> None:
    """작업의 현재 상태를 callback_url 로 통보한다(URL 없으면 no-op)."""
    if not job.callback_url:
        return
    await send_webhook(job.callback_url, build_webhook_payload(job))


def status_sync_target(job_id: str) -> Optional[str]:
    """③ status-sync 대상 URL — 환경변수 base 에 명세서 경로를 붙인다.

    미설정이면 None(비활성). 목은 수신측 주소를 유추하지 않는다.
    """
    settings = get_settings()
    base = settings.genai_status_sync_url.strip()
    if not base:
        return None
    url = f"{base.rstrip('/')}/api/genai/jobs/{job_id}/status-sync"
    if not is_allowed_url(url, settings.genai_callback_allow_hosts_list()):
        logger.warning(
            "[MOCK][GENAI] status-sync target rejected(url guard) url=%s",
            sanitize_for_log(url),
        )
        return None
    return url


# ── 작업 진행 러너 ────────────────────────────────────────────────
async def run_job(job_id: str) -> None:
    """내부 큐에서 슬롯을 확보한 뒤 작업을 처리한다(벤더 동작 모사).

    슬롯을 기다리는 동안 작업은 ``RECEIVED`` 로 남는다 — 어떤 전이도 webhook 도 발생하지 않는다.
    대기 중 취소되면 배정 없이 종료한다(슬롯 미소모).
    """
    scheduler = get_scheduler()
    granted = await scheduler.wait_for_slot(job_id)
    if not granted:
        logger.info(
            "[MOCK][GENAI] job left queue before start job_id=%s", sanitize_for_log(job_id)
        )
        return
    try:
        await _process_job(job_id)
    finally:
        scheduler.release(job_id)


async def _process_job(job_id: str) -> None:
    """RECEIVED → RUNNING(단계별) → SUCCEEDED|FAILED 로 진행하며 단계마다 webhook 발사.

    종결 상태(취소 등)로 전이된 작업은 즉시 중단하고 이후 전이/발신을 하지 않는다(HIGH-2).
    """
    store = get_job_store()
    delay = get_settings().genai_step_delay_sec

    try:
        for progress, step in PROGRESS_STEPS:
            if delay > 0:
                await asyncio.sleep(delay)
            result = store.transition(
                job_id, JobStatus.RUNNING.value, progress=progress, current_step=step
            )
            if not result.ok or result.job is None:
                logger.info(
                    "[MOCK][GENAI] job progression stopped job_id=%s reason=%s",
                    sanitize_for_log(job_id),
                    result.outcome,
                )
                return
            await emit_webhook(result.job)

        snapshot = store.get(job_id)
        if snapshot is None or snapshot.status in (
            JobStatus.CANCELED.value,
            JobStatus.FAILED.value,
            JobStatus.SUCCEEDED.value,
        ):
            return

        if is_failure_trigger(snapshot.request_id):
            raise JobExecutionError(
                ErrorCode.MODEL_EXECUTION_FAILED.value,
                "모델 실행에 실패했습니다(목 실패 트리거)",
            )

        settings = get_settings()
        results = await run_in_threadpool(build_results, snapshot, settings.genai_output_base)

        try:
            if delay > 0:
                await asyncio.sleep(delay)
        except asyncio.CancelledError:
            # 취소로 중단 — 이미 만든 산출물을 남기지 않는다(F-11).
            discard_results(results)
            raise

        done = store.transition(
            job_id,
            JobStatus.SUCCEEDED.value,
            progress=100,
            current_step=COMPLETED_STEP,
            results=results,
        )
        if done.ok and done.job is not None:
            await emit_webhook(done.job)
        else:
            # 결과 기록 직전/직후에 취소 등 다른 종결 상태가 확정된 경우 — 고아 산출물 정리(F-11).
            logger.info(
                "[MOCK][GENAI] discard results(terminal before completion) job_id=%s reason=%s",
                sanitize_for_log(job_id),
                done.outcome,
            )
            await run_in_threadpool(discard_results, results)
    except asyncio.CancelledError:
        logger.info("[MOCK][GENAI] job task cancelled job_id=%s", sanitize_for_log(job_id))
        raise
    except JobExecutionError as exc:
        await _fail_job(job_id, exc.error_code, exc.message)
    except Exception as exc:  # noqa: BLE001 — 어떤 실패도 서버로 전파하지 않는다
        logger.warning(
            "[MOCK][GENAI] job execution error job_id=%s type=%s",
            sanitize_for_log(job_id),
            type(exc).__name__,
        )
        await _fail_job(
            job_id,
            ErrorCode.MODEL_EXECUTION_FAILED.value,
            "작업 처리 중 오류가 발생했습니다",
        )


async def _fail_job(job_id: str, error_code: str, message: str) -> None:
    """작업을 FAILED 로 종결하고 webhook 으로 통보한다(내부 상세 미노출)."""
    logger.warning(
        "[MOCK][GENAI] job failed job_id=%s error_code=%s",
        sanitize_for_log(job_id),
        sanitize_for_log(error_code),
    )
    result = get_job_store().transition(
        job_id,
        JobStatus.FAILED.value,
        current_step=COMPLETED_STEP,
        error_code=error_code,
        error_message=message,
    )
    if result.ok and result.job is not None:
        await emit_webhook(result.job)
