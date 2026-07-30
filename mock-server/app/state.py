"""
스레드세이프 인메모리 상태 저장소.

목 서버는 DB가 없으므로 프로젝트/데이터셋/작업로그를 프로세스 메모리에만 보관한다.
모든 변경은 단일 ``threading.Lock`` 으로 보호한다 (CWE-362 race condition 방어).

핵심 설계 — **상태ful 진행률 시뮬레이션**:
- 진행률(progressRate)은 저장하지 않는다.
- 프로젝트 생성 시각을 ``time.monotonic()`` 기준 경과 계산용으로만 저장한다.
- 조회 시점에 ``elapsed_progress(created_monotonic, now_monotonic, speed)`` 로
  경과초 → progressRate/state 를 계산한다. 벽시계(time.time)가 아닌 monotonic 을
  써서 시스템 시간 변경에 영향받지 않는다.

★ **비동기 산출 축(production_state)** — 구속 원칙 "외부연동은 모두 비동기":
``POST /project`` 는 접수만 하고 즉시 반환하며, 실제 산출(ffmpeg 인코딩·복사)은 백그라운드
태스크가 수행한다. 그래서 진행률에는 **두 축**이 있다:
  ① 경과초(위 시뮬레이션) ② 실제 산출 진행(``Project.production_state``).
완료(progressRate=100 → procState=2)는 **둘 다 끝났을 때만** 보고한다. 경과초만으로 완료를
보고하면 BE 가 아직 만들어지지 않은 산출물을 회수하러 가서 무결성 실패 → 거짓 'F' 가 된다.

Phase 2/3 가 이 저장소를 확장(데이터셋/작업로그 로직 추가)해 사용한다.
"""

from __future__ import annotations

import copy
import re
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional

from app.schemas.genai import TERMINAL_STATUSES, JobStatus

# 진행률 시뮬레이션 상수 — 배속 1.0 기준 초당 증가 퍼센트.
# 예: 10%/s → 배속 1.0 에서 10초면 100% 도달.
PROGRESS_PERCENT_PER_SECOND: float = 10.0
PROGRESS_MAX: float = 100.0

#: 산출(백그라운드)이 끝나기 전 보고할 수 있는 진행률 상한 — 100 미만이어야 한다.
#: 100 을 보고하면 그 자체가 "완료"라 BE 가 아직 없는 산출물을 회수하러 간다.
PROGRESS_INCOMPLETE_CAP: float = 99.0

# ── 백그라운드 산출 상태 (구속 원칙: 외부연동은 모두 비동기) ────────
PRODUCTION_PENDING: str = "PENDING"
PRODUCTION_RUNNING: str = "RUNNING"
PRODUCTION_SUCCEEDED: str = "SUCCEEDED"
PRODUCTION_FAILED: str = "FAILED"
#: 더 이상 변하지 않는 산출 상태 — 대기 헬퍼/폴링 판정의 종료 조건.
PRODUCTION_TERMINAL: frozenset[str] = frozenset(
    {PRODUCTION_SUCCEEDED, PRODUCTION_FAILED}
)


#: 로그 인젝션에 쓰이는 제어문자 — C0(\x00~\x1f, ANSI ESC \x1b 포함) · DEL(\x7f) ·
#: 유니코드 줄/문단 구분자(U+2028/U+2029). 모두 공백으로 치환한다.
_LOG_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f  ]")


def sanitize_for_log(value: object) -> str:
    """로그 인젝션(CWE-117) 방어 — 제어문자 일괄 제거 + 길이 제한.

    개행(\\r\\n)·탭뿐 아니라 ANSI 이스케이프(\\x1b)·수직탭(\\x0b)·폼피드(\\x0c)·NUL·
    유니코드 줄구분자(U+2028/U+2029)까지 모두 제거한다(F-9). 이 함수는 KPST/VLM/생성형 AI
    목이 공유하므로 사용자 입력을 로그에 넣기 전 반드시 거친다.
    """
    text = _LOG_CONTROL_CHARS.sub(" ", str(value))
    if len(text) > 200:
        text = text[:200] + "…(truncated)"
    return text


class DuplicateProjectError(Exception):
    """동일 project_name 프로젝트가 이미 존재할 때."""


@dataclass
class ProgressSnapshot:
    """진행률 스냅샷 — 경과초 축과 산출 축을 합친 결과."""

    progress_rate: float
    state: str  # PENDING | PROCESSING | COMPLETED | FAILED


def elapsed_progress(
    created_monotonic: float,
    now_monotonic: float,
    speed: float,
) -> ProgressSnapshot:
    """경과초 기반 진행률/상태를 계산한다.

    경과초가 커질수록 progressRate 는 단조 증가하며 상한 100 에서 멈춘다.

    Args:
        created_monotonic: 생성 시점의 ``time.monotonic()`` 값.
        now_monotonic: 조회 시점의 ``time.monotonic()`` 값.
        speed: 시뮬레이션 배속(>0). 클수록 빨리 100 에 도달.

    Returns:
        ProgressSnapshot(progress_rate 0.0~100.0, state).
    """
    # 방어 — 음수 경과/비정상 배속은 안전 기본값으로 클램프
    effective_speed = speed if speed > 0 else 1.0
    elapsed = now_monotonic - created_monotonic
    if elapsed <= 0:
        return ProgressSnapshot(progress_rate=0.0, state="PENDING")

    rate = elapsed * effective_speed * PROGRESS_PERCENT_PER_SECOND
    rate = min(PROGRESS_MAX, round(rate, 4))

    if rate <= 0.0:
        state = "PENDING"
    elif rate >= PROGRESS_MAX:
        state = "COMPLETED"
    else:
        state = "PROCESSING"
    return ProgressSnapshot(progress_rate=float(rate), state=state)


def combined_progress(
    elapsed: ProgressSnapshot, production_state: str
) -> ProgressSnapshot:
    """경과초 진행률에 **실제 산출 진행**을 반영한 최종 스냅샷을 만든다.

    - 산출 실패(``PRODUCTION_FAILED``) → ``FAILED``. 완료로 위장하지 않는다. 라우터가 이 상태를
      KPST 오류 sentinel(``procState=99``)로 내보내면 BE 는 타임아웃을 기다리지 않고 즉시
      terminal 'F' 로 종결한다(``KpstDeidentService.PROC_STATE_TERMINAL_FAILED``).
    - 산출 미완료 → 진행률을 ``PROGRESS_INCOMPLETE_CAP`` 으로 눌러 완료 보고를 막는다.
    - 산출 성공 → 경과초 스냅샷 그대로(둘 다 끝나야 완료).
    """
    if production_state == PRODUCTION_FAILED:
        return ProgressSnapshot(
            progress_rate=min(elapsed.progress_rate, PROGRESS_INCOMPLETE_CAP),
            state="FAILED",
        )
    if production_state != PRODUCTION_SUCCEEDED:
        rate = min(elapsed.progress_rate, PROGRESS_INCOMPLETE_CAP)
        return ProgressSnapshot(
            progress_rate=rate, state="PROCESSING" if rate > 0.0 else "PENDING"
        )
    return elapsed


@dataclass
class Project:
    """목 서버가 관리하는 프로젝트(작업) 단위."""

    prj_id: int
    project_name: str
    created_monotonic: float
    created_epoch: float
    # Phase 2 — KPST 규격 필드
    creator: str = ""
    export_path: str = ""
    input_path: str = ""
    is_img: int = 0
    db_save: int = 0
    #: 백그라운드 산출 진행 상태(PENDING→RUNNING→SUCCEEDED|FAILED). 완료 보고의 두 번째 축이다.
    production_state: str = PRODUCTION_PENDING
    # Phase 2/3 확장용 — 데이터셋 식별자 목록, 임의 메타데이터
    dataset_ids: list[int] = field(default_factory=list)
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass
class Dataset:
    """프로젝트에 속한 데이터셋 (Phase 2 확장용 골격)."""

    dataset_id: int
    prj_id: int
    name: str
    created_monotonic: float
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass
class JobLog:
    """작업 로그 항목 (Phase 3 콜백/처리 이력 확장용 골격)."""

    seq: int
    prj_id: int
    event: str
    detail: str = ""
    created_epoch: float = 0.0


class InMemoryStore:
    """스레드세이프 인메모리 저장소.

    프로젝트/데이터셋/작업로그를 단일 락으로 보호한다. 재진입이 필요한
    복합 연산은 없으므로 표준 ``Lock`` 을 사용한다.

    자원 상한(MEDIUM-3, CWE-770 · OWASP API4:2023) — **``GenAiJobStore`` 와 같은 패턴**:
    보관 프로젝트 수가 ``max_projects`` 를 넘으면 가장 오래된 것부터 만료(FIFO)하고 그
    프로젝트에 딸린 데이터셋·작업로그·이름 색인까지 함께 정리한다. 목은 무인증이라
    ``POST /project`` 를 고유 이름으로 반복하면 프로젝트/데이터셋/로그가 무제한 상주했다
    (같은 프로세스의 생성형 AI 저장소만 상한을 갖고 있던 비대칭).
    만료된 프로젝트의 산출 태스크는 ``set_production_state`` 가 False 를 돌려주므로
    ``run_production`` 이 "project gone" 으로 스스로 중단한다.
    """

    def __init__(self, max_projects: Optional[int] = None) -> None:
        self._lock = threading.Lock()
        self._projects: dict[int, Project] = {}
        self._name_index: dict[str, int] = {}
        self._datasets: dict[int, Dataset] = {}
        self._job_logs: list[JobLog] = []
        self._prj_seq: int = 0
        self._dataset_seq: int = 0
        self._job_seq: int = 0
        self._max_projects = max_projects

    def _limit(self) -> int:
        """보관 상한 — 생성자 지정값 우선, 없으면 설정값(``GenAiJobStore._limit`` 과 동형)."""
        if self._max_projects is not None:
            return self._max_projects
        from app.config import get_settings

        return get_settings().deid_max_projects

    def _evict_locked(self) -> None:
        """상한 초과분을 오래된 순서로 제거한다(락 보유 상태에서 호출).

        dict 는 삽입 순서를 유지하므로 선두가 가장 오래된 프로젝트다. 프로젝트만 지우면
        데이터셋/로그가 고아로 남아 상한이 무의미해지므로 함께 정리한다.
        """
        limit = self._limit()
        while len(self._projects) > limit:
            oldest_id = next(iter(self._projects))
            evicted = self._projects.pop(oldest_id, None)
            if evicted is None:
                continue
            if self._name_index.get(evicted.project_name) == oldest_id:
                self._name_index.pop(evicted.project_name, None)
            for dataset_id in evicted.dataset_ids:
                self._datasets.pop(dataset_id, None)
            self._job_logs = [log for log in self._job_logs if log.prj_id != oldest_id]

    # ── 프로젝트 ────────────────────────────────────────────────
    def create_project(
        self,
        project_name: str,
        *,
        creator: str = "",
        export_path: str = "",
        input_path: str = "",
        is_img: int = 0,
        db_save: int = 0,
        **meta: Any,
    ) -> Project:
        """프로젝트를 생성하고 자동증가 prj_id 를 발급한다.

        Raises:
            DuplicateProjectError: 동일 project_name 이 이미 존재할 때.
        """
        with self._lock:
            if project_name in self._name_index:
                raise DuplicateProjectError(project_name)
            self._prj_seq += 1
            prj_id = self._prj_seq
            now_mono = time.monotonic()
            project = Project(
                prj_id=prj_id,
                project_name=project_name,
                created_monotonic=now_mono,
                created_epoch=time.time(),
                creator=creator,
                export_path=export_path,
                input_path=input_path,
                is_img=is_img,
                db_save=db_save,
                meta=dict(meta),
            )
            self._projects[prj_id] = project
            self._name_index[project_name] = prj_id
            self._evict_locked()
            return project

    def get_project(self, prj_id: int) -> Optional[Project]:
        """id 로 프로젝트를 조회한다. 없으면 None."""
        with self._lock:
            return self._projects.get(prj_id)

    def get_project_by_name(self, project_name: str) -> Optional[Project]:
        """이름으로 프로젝트를 조회한다. 없으면 None."""
        with self._lock:
            prj_id = self._name_index.get(project_name)
            return self._projects.get(prj_id) if prj_id is not None else None

    def list_projects(self, name_contains: Optional[str] = None) -> list[Project]:
        """생성 순서(prj_id 오름차순)로 프로젝트 목록을 반환한다.

        Args:
            name_contains: 지정 시 project_name 부분일치 필터.
        """
        with self._lock:
            items = [self._projects[k] for k in sorted(self._projects)]
        if name_contains:
            items = [p for p in items if name_contains in p.project_name]
        return items

    def delete_project(self, prj_id: int) -> bool:
        """id 로 삭제한다. 삭제 성공 시 True, 대상 없으면 False."""
        with self._lock:
            project = self._projects.pop(prj_id, None)
            if project is None:
                return False
            self._name_index.pop(project.project_name, None)
            return True

    def delete_project_by_name(self, project_name: str) -> bool:
        """이름으로 삭제한다. 삭제 성공 시 True, 대상 없으면 False."""
        with self._lock:
            prj_id = self._name_index.pop(project_name, None)
            if prj_id is None:
                return False
            self._projects.pop(prj_id, None)
            return True

    def progress_of(self, prj_id: int) -> Optional[ProgressSnapshot]:
        """프로젝트의 현재 진행률 스냅샷을 계산한다. 대상 없으면 None.

        경과초 축과 **백그라운드 산출 축**을 합쳐서 판정한다(``combined_progress``) — 완료 판정을
        여기 한 곳에서만 내려야 소비자(progress/report/job_logs)가 서로 다른 답을 내지 않는다.
        """
        from app.config import get_settings

        with self._lock:
            project = self._projects.get(prj_id)
            created = project.created_monotonic if project else None
            production = project.production_state if project else None
        if created is None or production is None:
            return None
        elapsed = elapsed_progress(
            created, time.monotonic(), get_settings().sim_speed_factor
        )
        return combined_progress(elapsed, production)

    def set_production_state(self, prj_id: int, state: str) -> bool:
        """백그라운드 산출 상태를 기록한다. 대상 프로젝트가 없으면 False(삭제됨)."""
        with self._lock:
            project = self._projects.get(prj_id)
            if project is None:
                return False
            project.production_state = state
            return True

    def production_state_of(self, prj_id: int) -> Optional[str]:
        """백그라운드 산출 상태를 조회한다. 대상 없으면 None."""
        with self._lock:
            project = self._projects.get(prj_id)
            return project.production_state if project is not None else None

    # ── 데이터셋 (Phase 2 확장 골격) ─────────────────────────────
    def add_dataset(self, prj_id: int, name: str, **meta: Any) -> Optional[Dataset]:
        """프로젝트에 데이터셋을 추가한다. 프로젝트 없으면 None (안전 기본값)."""
        with self._lock:
            project = self._projects.get(prj_id)
            if project is None:
                return None
            self._dataset_seq += 1
            dataset = Dataset(
                dataset_id=self._dataset_seq,
                prj_id=prj_id,
                name=name,
                created_monotonic=time.monotonic(),
                meta=dict(meta),
            )
            self._datasets[dataset.dataset_id] = dataset
            project.dataset_ids.append(dataset.dataset_id)
            return dataset

    def get_dataset(self, dataset_id: int) -> Optional[Dataset]:
        """id 로 데이터셋을 조회한다. 없으면 None."""
        with self._lock:
            return self._datasets.get(dataset_id)

    def datasets_of(self, prj_id: int) -> list[Dataset]:
        """프로젝트에 속한 데이터셋 목록을 등록 순서로 반환한다."""
        with self._lock:
            project = self._projects.get(prj_id)
            if project is None:
                return []
            return [self._datasets[d] for d in project.dataset_ids if d in self._datasets]

    # ── 작업 로그 (Phase 3 확장 골격) ────────────────────────────
    def append_job_log(self, prj_id: int, event: str, detail: str = "") -> JobLog:
        """작업 로그를 추가한다. event/detail 은 sanitize 후 저장한다(CWE-117)."""
        with self._lock:
            self._job_seq += 1
            log = JobLog(
                seq=self._job_seq,
                prj_id=prj_id,
                event=sanitize_for_log(event),
                detail=sanitize_for_log(detail),
                created_epoch=time.time(),
            )
            self._job_logs.append(log)
            return log

    def job_logs(self, prj_id: Optional[int] = None) -> list[JobLog]:
        """작업 로그를 반환한다. prj_id 지정 시 해당 프로젝트만."""
        with self._lock:
            logs = list(self._job_logs)
        if prj_id is not None:
            logs = [log for log in logs if log.prj_id == prj_id]
        return logs

    def clear(self) -> None:
        """전체 상태 초기화 (테스트용)."""
        with self._lock:
            self._projects.clear()
            self._name_index.clear()
            self._datasets.clear()
            self._job_logs.clear()
            self._prj_seq = 0
            self._dataset_seq = 0
            self._job_seq = 0


# 프로세스 전역 저장소 — 라우터에서 공유. 테스트는 별도 인스턴스를 만들어 격리한다.
_store: InMemoryStore = InMemoryStore()


def get_store() -> InMemoryStore:
    """전역 인메모리 저장소 인스턴스를 반환한다."""
    return _store


# ── 생성형 AI(증강) 작업 저장소 ────────────────────────────────────
def utc_now_iso() -> str:
    """명세서 date-time 필드용 ISO-8601(UTC) 타임스탬프."""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class GenAiJob:
    """「생성형 AI API 연동명세서 v1.1」 작업 1건의 인메모리 상태."""

    job_id: str
    request_id: str
    status: str = JobStatus.RECEIVED.value
    # 요청 스냅샷 — 진행/결과 생성에 필요한 최소 정보만 보관한다.
    request_channel: str = ""
    request_user_id: Optional[str] = None
    evnt_type: str = ""
    operation_type: str = ""
    generation_mode: str = ""
    callback_url: Optional[str] = None
    # (sequence, file_path) 목록. sequence 오름차순.
    input_files: list[tuple[int, str]] = field(default_factory=list)
    idempotency_key: Optional[str] = None
    # 진행 상태
    progress: int = 0
    current_step: Optional[str] = None
    received_at: str = field(default_factory=utc_now_iso)
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    canceled_at: Optional[str] = None
    updated_at: str = field(default_factory=utc_now_iso)
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    results: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class TransitionResult:
    """상태 전이 시도 결과.

    outcome:
        - ``OK``        : 전이 성공(job 은 전이 후 스냅샷)
        - ``NOT_FOUND`` : 대상 job 없음 → 404 JOB_NOT_FOUND
        - ``TERMINAL``  : 이미 종결 상태라 전이 거부 → 409 STATE_CONFLICT
    """

    outcome: str
    job: Optional[GenAiJob] = None

    @property
    def ok(self) -> bool:
        return self.outcome == "OK"


class GenAiJobStore:
    """생성형 AI 작업 저장소 — 단일 락으로 상태 전이/멱등 등록을 직렬화한다.

    동시성(CWE-362):
    - ``create_or_get`` 의 check-then-act(멱등키 조회→등록)를 락 안에서 원자 수행한다.
    - ``transition`` 은 **종결 상태(SUCCEEDED/FAILED/CANCELED)에서의 재전이를 거부**한다.
      취소와 완료가 동시에 들어와도 먼저 도달한 종결 상태가 유지된다(취소 레이스 방어).
    - 반환값은 항상 deepcopy 스냅샷이라 호출측 변경이 저장소에 새지 않는다.

    자원 상한(CWE-770, F-3):
    - 보관 작업 수가 ``max_jobs`` 를 넘으면 **가장 오래된 작업부터 만료(FIFO)** 한다.
      dict 는 삽입 순서를 유지하므로 별도 큐 없이 선두를 제거하면 된다. 멱등키 색인도 함께
      정리해 무제한 증가를 막는다.
    """

    def __init__(self, max_jobs: Optional[int] = None) -> None:
        self._lock = threading.Lock()
        self._jobs: dict[str, GenAiJob] = {}
        self._idempotency: dict[str, str] = {}
        self._max_jobs = max_jobs

    def _limit(self) -> int:
        """보관 상한 — 생성자 지정값 우선, 없으면 설정값."""
        if self._max_jobs is not None:
            return self._max_jobs
        from app.config import get_settings

        return get_settings().genai_max_jobs

    def _evict_locked(self) -> None:
        """상한 초과분을 오래된 순서로 제거한다(락 보유 상태에서 호출)."""
        limit = self._limit()
        while len(self._jobs) > limit:
            oldest_id = next(iter(self._jobs))
            evicted = self._jobs.pop(oldest_id, None)
            if evicted is not None and evicted.idempotency_key:
                if self._idempotency.get(evicted.idempotency_key) == oldest_id:
                    self._idempotency.pop(evicted.idempotency_key, None)

    def create_or_get(
        self, job: GenAiJob, idempotency_key: Optional[str]
    ) -> tuple[GenAiJob, bool]:
        """작업을 등록한다. 동일 멱등키가 이미 있으면 기존 작업을 반환한다.

        Returns:
            (작업 스냅샷, 신규 생성 여부). 신규 생성이 아니면 백그라운드 진행을 재시작하면 안 된다.
        """
        with self._lock:
            if idempotency_key:
                existing_id = self._idempotency.get(idempotency_key)
                if existing_id is not None and existing_id in self._jobs:
                    return copy.deepcopy(self._jobs[existing_id]), False
                job.idempotency_key = idempotency_key
                self._idempotency[idempotency_key] = job.job_id
            self._jobs[job.job_id] = job
            self._evict_locked()
            return copy.deepcopy(job), True

    def get(self, job_id: str) -> Optional[GenAiJob]:
        """작업 스냅샷을 반환한다. 없으면 None."""
        with self._lock:
            job = self._jobs.get(job_id)
            return copy.deepcopy(job) if job is not None else None

    def list_jobs(self) -> list[GenAiJob]:
        """등록된 작업 스냅샷 목록(목 전용 조회)."""
        with self._lock:
            return [copy.deepcopy(self._jobs[k]) for k in self._jobs]

    def transition(
        self,
        job_id: str,
        status: str,
        *,
        progress: Optional[int] = None,
        current_step: Optional[str] = None,
        results: Optional[list[dict[str, Any]]] = None,
        error_code: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> TransitionResult:
        """상태를 전이한다. 종결 상태에서는 거부(TERMINAL)한다."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return TransitionResult("NOT_FOUND")
            if job.status in TERMINAL_STATUSES:
                return TransitionResult("TERMINAL", copy.deepcopy(job))

            now = utc_now_iso()
            job.status = status
            job.updated_at = now
            if progress is not None:
                job.progress = progress
            if current_step is not None:
                job.current_step = current_step
            if results is not None:
                job.results = results
            if error_code is not None:
                job.error_code = error_code
            if error_message is not None:
                job.error_message = error_message
            if status == JobStatus.RUNNING.value and job.started_at is None:
                job.started_at = now
            if status in (JobStatus.SUCCEEDED.value, JobStatus.FAILED.value):
                job.completed_at = now
            if status == JobStatus.CANCELED.value:
                job.canceled_at = now
            return TransitionResult("OK", copy.deepcopy(job))

    def clear(self) -> None:
        """전체 작업 초기화 (목 전용 reset / 테스트용)."""
        with self._lock:
            self._jobs.clear()
            self._idempotency.clear()


# 프로세스 전역 생성형 AI 작업 저장소.
_genai_store: GenAiJobStore = GenAiJobStore()


def get_genai_store() -> GenAiJobStore:
    """전역 생성형 AI 작업 저장소 인스턴스를 반환한다."""
    return _genai_store
