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

Phase 2/3 가 이 저장소를 확장(데이터셋/작업로그 로직 추가)해 사용한다.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Optional

# 진행률 시뮬레이션 상수 — 배속 1.0 기준 초당 증가 퍼센트.
# 예: 10%/s → 배속 1.0 에서 10초면 100% 도달.
PROGRESS_PERCENT_PER_SECOND: float = 10.0
PROGRESS_MAX: float = 100.0


def sanitize_for_log(value: object) -> str:
    """로그 인젝션(CWE-117) 방어 — 개행/캐리지리턴 제거 + 길이 제한.

    사용자 입력을 로그에 넣기 전 반드시 이 함수를 거친다.
    """
    text = str(value)
    text = text.replace("\r", " ").replace("\n", " ").replace("\t", " ")
    if len(text) > 200:
        text = text[:200] + "…(truncated)"
    return text


class DuplicateProjectError(Exception):
    """동일 project_name 프로젝트가 이미 존재할 때."""


@dataclass
class ProgressSnapshot:
    """경과초로 계산한 진행률 스냅샷."""

    progress_rate: float
    state: str  # PENDING | PROCESSING | COMPLETED


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
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._projects: dict[int, Project] = {}
        self._name_index: dict[str, int] = {}
        self._datasets: dict[int, Dataset] = {}
        self._job_logs: list[JobLog] = []
        self._prj_seq: int = 0
        self._dataset_seq: int = 0
        self._job_seq: int = 0

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
        """프로젝트의 현재 진행률 스냅샷을 계산한다. 대상 없으면 None."""
        from app.config import get_settings

        with self._lock:
            project = self._projects.get(prj_id)
            created = project.created_monotonic if project else None
        if created is None:
            return None
        return elapsed_progress(created, time.monotonic(), get_settings().sim_speed_factor)

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
