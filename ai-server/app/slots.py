"""
용도별 실행 슬롯 — 배치 파이프라인과 저작도구 화면 요청의 간섭을 없앤다.

추론 엔드포인트는 ``async def`` 인데 그 안에서 부르는 추론은 **블로킹 호출**이라,
오프로드하지 않으면 이벤트 루프가 통째로 잡혀 **모든 요청이 도착 순서로 완전 직렬화**된다.
배치가 프레임을 밀어 넣는 동안 화면에서 누른 요청은 그 뒤에 줄을 선다(실측: 배치 대기 120건
상태에서 화면 첫 요청 21,918.8ms → 슬롯 분리 후 268.9ms). 근거 결정은 ``ADR-056``.

구조::

    ai-server (프로세스 1 · 포트 하나)
      ├── batch        ThreadPoolExecutor(max_workers=1)   ← 파이프라인 전용
      └── interactive  ThreadPoolExecutor(max_workers=1)   ← 저작도구 화면 전용

★ 불변식 — 실측으로 확증됐다. 어기면 조용히 깨진다.

1. **각 슬롯의 동시 처리 수는 1 로 고정한다.** 슬롯 안에서 늘리면 SAM2 인스턴스 공유 붕괴와
   트래커 상태 뒤섞임이 **슬롯 내부에서 그대로 재현**된다.
2. **SAM2 predictor 는 슬롯마다 별도 인스턴스**를 쓴다(``app.models.sam2_loader``). 이미지 임베딩을
   인스턴스 필드에 보관하므로 공유하면 마스크가 어긋나고 ``TypeError`` 로 스레드가 죽는다.
   지금 안 터지는 것은 이벤트 루프가 막고 있기 때문이며, 오프로드하는 순간 열리는 문이다.
3. **YOLOX ONNX 세션은 공유한다** — 동시 실행 안전성이 실측으로 확인됐고, 슬롯마다 만들면
   가속기 메모리가 예산을 넘는다.

@design ADR-056

슬롯 선택은 요청 헤더 :data:`WORKLOAD_HEADER` 가 정하며 **정확히 ``batch`` 일 때만** 배치다.
미지정·빈값·오타·대소문자 불일치는 전부 화면으로 간다 — 표시를 빠뜨려도 **사람이 쓰는 쪽이
보호되는 방향으로 틀린다**. 느슨하게 받으면(``strip()``/``lower()`` 후 비교) 오타가 조용히 배치로
새어 화면이 느려지고 그 원인이 겉으로 드러나지 않는다.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")

#: 실행 슬롯을 가르는 요청 헤더 이름. 백엔드 ``AiWorkload.HEADER_NAME`` 과 짝이다.
WORKLOAD_HEADER = "X-Workload"

#: 배치 파이프라인 전용 슬롯.
BATCH = "batch"

#: 저작도구 화면 전용 슬롯 — **기본값**.
INTERACTIVE = "interactive"

#: 슬롯 이름 전량. 관측 응답의 키 집합이기도 하다.
SLOT_NAMES: tuple[str, ...] = (BATCH, INTERACTIVE)

#: 슬롯당 동시 처리 수. **1 고정** — 위 불변식 1 참조.
SLOT_MAX_WORKERS = 1


def resolve_slot_name(header_value: str | None) -> str:
    """헤더 값 → 슬롯 이름. **정확히 ``batch``** 일 때만 배치, 그 밖은 전부 화면이다.

    ``strip()``/``lower()`` 로 느슨하게 받지 않는다 — 오타·대소문자 불일치가 조용히 배치로
    흘러가면 화면이 느려지고 원인이 겉으로 드러나지 않는다. fail-safe 방향은 화면 쪽이다.
    """
    return BATCH if header_value == BATCH else INTERACTIVE


class ExecutionSlot:
    """단일 워커 실행 슬롯 + **잠금 없는** 부하 카운터.

    부하 관측은 슬롯을 방해하면 안 되므로 **잠그지 않는다**. 대기 건수·최장 대기 시간은
    근사값이어도 되며(소비자는 추세만 읽는다), 읽기는 큐를 순회하지 않고 **O(1)** 이다.
    ``ThreadPoolExecutor`` 의 사설 속성(``_work_queue``)에 기대지 않는다 — 파이썬 판이 바뀌면
    조용히 깨지는 자리다. 제출을 감싸는 이 자리에서 직접 센다.
    """

    def __init__(self, name: str) -> None:
        self.name = name
        self._executor = ThreadPoolExecutor(
            max_workers=SLOT_MAX_WORKERS, thread_name_prefix=f"ai-slot-{name}"
        )
        # 제출됐지만 아직 시작되지 않은 태스크의 제출 시각(monotonic). deque 의 append/popleft 는
        # 원자적이라 잠금 없이 쓸 수 있고 len()·[0] 이 O(1) 이다.
        self._waiting: deque[float] = deque()
        # 실행 중 건수. 워커 스레드가 하나뿐이라 이 값을 바꾸는 주체도 하나다.
        self._running = 0

    async def run(self, fn: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        """블로킹 함수를 이 슬롯에서 실행하고 결과를 기다린다(이벤트 루프는 놓아준다)."""
        loop = asyncio.get_running_loop()
        submitted_at = time.monotonic()
        self._waiting.append(submitted_at)
        # 태스크가 실제로 시작됐는지 — 제출 실패(종료된 슬롯 등)와 추론 실패를 구분한다.
        # 이 구분이 없으면 **정상적인 추론 예외**가 남의 대기 항목을 지워 카운터가 어긋난다.
        started = [False]

        def _task() -> T:
            started[0] = True
            # 실행이 시작됐으니 대기 목록에서 뺀다. max_workers=1 + FIFO 라 머리가 곧 이 태스크다.
            try:
                self._waiting.popleft()
            except IndexError:  # pragma: no cover - 근사 카운터의 방어선
                pass
            self._running += 1
            try:
                return fn(*args, **kwargs)
            finally:
                self._running -= 1

        try:
            return await loop.run_in_executor(self._executor, _task)
        except BaseException:
            # 제출 자체가 실패했으면 대기 항목이 영영 남아 카운터가 부푼다 — 한 건 되돌린다.
            # (근사 카운터이므로 어느 항목을 빼는지는 중요하지 않고 개수만 맞으면 된다.)
            if not started[0]:
                try:
                    self._waiting.pop()
                except IndexError:  # pragma: no cover
                    pass
            raise

    def load_snapshot(self) -> dict[str, int]:
        """이 슬롯의 부하 근사값 — ``running`` · ``queued`` · ``oldest_wait_ms``.

        잠그지 않으므로 세 값이 같은 순간의 것이 아닐 수 있다. 의도된 것이다.
        """
        queued = len(self._waiting)
        try:
            oldest = self._waiting[0]
        except IndexError:
            oldest_wait_ms = 0
        else:
            oldest_wait_ms = max(0, int((time.monotonic() - oldest) * 1000))
        return {
            "running": max(0, self._running),
            "queued": max(0, queued),
            "oldest_wait_ms": oldest_wait_ms,
        }

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)


class SlotRegistry:
    """슬롯 묶음. 이름으로 꺼내 쓰고, 전체 부하를 한 번에 스냅샷한다."""

    def __init__(self) -> None:
        self._slots = {name: ExecutionSlot(name) for name in SLOT_NAMES}

    def get(self, name: str) -> ExecutionSlot:
        """슬롯 조회. 모르는 이름은 **화면 슬롯**으로 떨어진다(fail-safe 방향)."""
        return self._slots.get(name, self._slots[INTERACTIVE])

    def for_header(self, header_value: str | None) -> ExecutionSlot:
        return self.get(resolve_slot_name(header_value))

    def load_snapshot(self) -> dict[str, dict[str, int]]:
        return {name: slot.load_snapshot() for name, slot in self._slots.items()}

    def shutdown(self) -> None:
        for slot in self._slots.values():
            slot.shutdown()


_registry: SlotRegistry | None = None


def get_slot_registry() -> SlotRegistry:
    """전역 슬롯 묶음. 첫 사용 시점에 만든다.

    lifespan 이 미리 만들어 두지만, 그것에 **의존하지는 않는다** — 라우터를 lifespan 없이
    직접 호출하는 경로(테스트 클라이언트를 컨텍스트 매니저 없이 쓰는 형태)에서도 성립해야 한다.
    """
    global _registry
    if _registry is None:
        _registry = SlotRegistry()
        logger.info(
            "[AI][SLOT] created slots=%s max_workers=%d",
            ",".join(SLOT_NAMES),
            SLOT_MAX_WORKERS,
        )
    return _registry


def shutdown_slots() -> None:
    """슬롯 정리. 앱 종료 경로에서만 부른다."""
    global _registry
    if _registry is not None:
        _registry.shutdown()
        _registry = None
        logger.info("[AI][SLOT] shutdown")


async def run_in_slot(
    header_value: str | None, fn: Callable[..., T], *args: Any, **kwargs: Any
) -> T:
    """헤더가 가리키는 슬롯에서 블로킹 함수를 실행한다 — 라우터가 쓰는 단일 진입점."""
    return await get_slot_registry().for_header(header_value).run(fn, *args, **kwargs)
