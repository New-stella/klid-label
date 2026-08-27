"""생성형 AI 벤더 목 — **내부 큐 처리**(동시 처리 슬롯 제한) 계약 테스트.

실제 증강 벤더는 요청을 접수(202)한 뒤 **내부 큐**에 넣고 동시 처리 슬롯 수만큼만 실행한다.
목도 같은 동작을 모사한다:

    접수(즉시 202 RECEIVED) → [FIFO 대기열] → 슬롯 확보 시 RUNNING → SUCCEEDED|FAILED
                                    └─ 대기 중 취소되면 슬롯을 점유하지 않고 CANCELED 로 종결

검증 포인트:
- 슬롯 초과분은 ``RECEIVED`` 로 대기하고 큐가 빌 때 순서대로 실행된다(FIFO).
- 큐 대기는 **접수 응답을 지연시키지 않는다**(명세서 §4.1 계약 유지).
- 대기 중(RECEIVED) 작업도 취소할 수 있고(§3.2), 취소분은 슬롯을 소모하지 않는다.
- FAILED 재현 트리거(``request_id`` 가 "fail" 로 시작)는 큐를 거쳐도 그대로 동작한다.

결정적 실행을 위해 ``build_results`` 를 게이트로 붙잡아 **첫 작업이 슬롯을 쥔 상태**를 만든다.
(``build_results`` 는 threadpool 에서 실행되므로 이벤트 루프를 막지 않는다.)
"""

from __future__ import annotations

import threading
import time
import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

JOBS_URL = "/api/genai/jobs"
MOCK_JOBS_URL = "/api/genai/_mock/jobs"
CALLBACK_URL = "http://localhost:8080/api/genai/jobs/x/webhook"

#: 게이트 대기 상한 — 테스트가 실패해도 스레드가 영원히 붙잡히지 않도록.
_GATE_TIMEOUT_SEC = 5.0


@pytest.fixture(autouse=True)
def _genai_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Iterator[None]:
    """지연 0초 + 동시 처리 슬롯 1개로 큐 동작을 결정적으로 관측한다."""
    from app.config import reload_settings
    from app.services import genai_sim

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "0")
    monkeypatch.setenv("MOCK_GENAI_MAX_CONCURRENT_JOBS", "1")
    monkeypatch.setenv("MOCK_GENAI_OUTPUT_BASE", str(tmp_path / "out"))
    monkeypatch.setenv("MOCK_GENAI_INPUT_BASE", str(tmp_path / "in"))
    monkeypatch.setenv("MOCK_GENAI_WEBHOOK_RETRY_DELAY_SEC", "0")
    (tmp_path / "in").mkdir(parents=True, exist_ok=True)
    reload_settings()
    genai_sim.get_job_store().clear()
    yield
    genai_sim.get_job_store().clear()
    reload_settings()


@pytest.fixture(autouse=True)
def webhooks(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, dict]]:
    """실제 outbound 를 차단하고 (url, payload) 발신 순서를 기록한다."""
    from app.services import genai_sim

    captured: list[tuple[str, dict]] = []

    async def _recorder(url: str, payload: dict) -> bool:
        captured.append((url, payload))
        return True

    monkeypatch.setattr(genai_sim, "send_webhook", _recorder)
    return captured


@pytest.fixture
def slot_gate(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> Iterator[threading.Event]:
    """``build_results`` 를 붙잡아 처리 슬롯을 점유시킨다. ``set()`` 하면 진행이 재개된다.

    ``client`` 에 의존시켜 **클라이언트보다 늦게 생성 → 먼저 정리**되게 한다. 그래야 lifespan
    shutdown(태스크 취소·정리)보다 먼저 게이트가 열려 threadpool 스레드가 붙잡히지 않는다.
    """
    from app.services import genai_sim

    gate = threading.Event()
    real_build = genai_sim.build_results

    def _blocking_build(job, output_base, **kwargs):  # noqa: ANN001, ANN202
        gate.wait(timeout=_GATE_TIMEOUT_SEC)
        return real_build(job, output_base, **kwargs)

    monkeypatch.setattr(genai_sim, "build_results", _blocking_build)
    yield gate
    gate.set()


# ── 헬퍼 ─────────────────────────────────────────────────────────
def _input_file(tmp_path, name: str = "src.mp4") -> str:
    path = tmp_path / "in" / name
    path.write_bytes(b"ORIGINAL_VIDEO")
    return str(path)


@pytest.fixture(autouse=True)
def _auto_idempotency_key(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    """① 작업 요청의 ``Idempotency-Key``(v1.3 §3.1 필수)를 호출마다 자동 부여한다.

    헤더를 **명시한** 호출은 그대로 두므로 멱등·누락 계약은 가려지지 않는다.
    """
    original_post = client.post

    def _post(url: str, *args: object, **kwargs: object):  # noqa: ANN202
        if url == JOBS_URL and "headers" not in kwargs:
            kwargs["headers"] = {"Idempotency-Key": uuid.uuid4().hex}
        return original_post(url, *args, **kwargs)

    monkeypatch.setattr(client, "post", _post)


def _body(tmp_path, **over: object) -> dict:
    """v1.3 §4.1 표준 요청 본문(AUGMENT × I2I)."""
    body: dict = {
        "request_id": "req-queue",
        "request_channel": "AUTHORING",
        "evnt_type": "FLOOD",
        "evnt_subtype": "ROAD_FLOOD",
        "operation_type": "AUGMENT",
        "generation_mode": "I2I",
        "input_files": [{"sequence": 1, "file_path": _input_file(tmp_path)}],
        "mtdt": {
            "time": "NIGHT",
            "season": "WINTER",
            "weather": "RAIN",
            "terrain": "ROAD",
            "severity": "HIGH",
        },
        "prompt": "야간 도로 침수 장면으로 변경해줘.",
        "callback_url": CALLBACK_URL,
    }
    body.update(over)
    return body


def _submit(client: TestClient, tmp_path, **over: object) -> str:
    res = client.post(JOBS_URL, json=_body(tmp_path, **over))
    assert res.status_code == 202, res.text
    return res.json()["job_id"]


def _status(client: TestClient, job_id: str) -> dict:
    res = client.get(f"{JOBS_URL}/{job_id}")
    assert res.status_code == 200, res.text
    return res.json()


def _queue(client: TestClient) -> dict:
    """[목 전용] 큐 상태(슬롯 수 / 실행 중 / 대기 목록)."""
    return client.get(MOCK_JOBS_URL).json()["queue"]


def _wait_status(client: TestClient, job_id: str, target: str, timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        last = _status(client, job_id)
        if last["status"] == target:
            return last
        time.sleep(0.02)
    pytest.fail(f"job {job_id} 가 {target} 에 도달하지 못함 (last={last})")


def _wait_holding_slot(client: TestClient, job_id: str, timeout: float = 5.0) -> None:
    """작업이 슬롯을 쥐고 결과 생성(게이트) 앞에서 멈출 때까지 기다린다."""
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        last = _status(client, job_id)
        if last["status"] == "RUNNING" and last["progress"] >= 90:
            return
        time.sleep(0.02)
    pytest.fail(f"job {job_id} 가 슬롯을 점유하지 못함 (last={last})")


def _succeeded_order(webhooks: list[tuple[str, dict]]) -> list[str]:
    return [payload["job_id"] for _, payload in webhooks if payload["status"] == "SUCCEEDED"]


# ── 1. 슬롯 초과분은 대기 ────────────────────────────────────────
def test_슬롯을_넘는_작업은_RECEIVED_로_대기한다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given — 슬롯 1개를 첫 작업이 점유한 상태
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    # when — 두 번째 작업 접수
    second = _submit(client, tmp_path, request_id="req-b")
    # then — 실행되지 않고 RECEIVED 로 대기(시작 시각도 없음)
    detail = _status(client, second)
    assert detail["status"] == "RECEIVED"
    assert detail["started_at"] is None
    queue = _queue(client)
    assert queue["max_concurrency"] == 1
    assert queue["running"] == 1
    assert queue["waiting_job_ids"] == [second]


# ── 2. 앞 작업이 끝나면 대기분이 실행 ────────────────────────────
def test_앞_작업이_끝나면_대기_작업이_RUNNING_으로_전이한다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    second = _submit(client, tmp_path, request_id="req-b")
    assert _status(client, second)["status"] == "RECEIVED"
    # when — 앞 작업을 끝낸다
    slot_gate.set()
    _wait_status(client, first, "SUCCEEDED")
    # then — 대기 작업이 실행되어 완료된다
    done = _wait_status(client, second, "SUCCEEDED")
    assert done["started_at"], "대기 작업이 RUNNING 으로 전이되지 않았다"
    assert done["progress"] == 100
    assert _queue(client)["waiting_job_ids"] == []


# ── 3. 대기 중 취소 ──────────────────────────────────────────────
def test_대기_중인_작업도_취소할_수_있다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given — 두 번째 작업은 아직 대기(RECEIVED)
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    second = _submit(client, tmp_path, request_id="req-b")
    assert _status(client, second)["status"] == "RECEIVED"
    # when — 대기 중인 작업을 취소
    res = client.post(f"{JOBS_URL}/{second}/cancel", json={"requested_by": "w1"})
    # then — §3.2 RECEIVED 에서 취소 가능
    assert res.status_code == 200, res.text
    assert res.json()["status"] == "CANCELED"
    assert res.json()["canceled_at"]
    assert _queue(client)["waiting_job_ids"] == []
    # 앞 작업을 끝내도 취소 상태가 유지된다(뒤늦게 실행되지 않는다)
    slot_gate.set()
    _wait_status(client, first, "SUCCEEDED")
    time.sleep(0.3)
    assert _status(client, second)["status"] == "CANCELED"
    assert client.get(f"{JOBS_URL}/{second}/results").status_code == 409


# ── 4. 접수 응답은 즉시 ──────────────────────────────────────────
def test_접수_응답은_큐_대기와_무관하게_즉시_202_다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given — 슬롯이 모두 찬 상태
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    # when — 큐가 꽉 찬 상태에서 접수
    started = time.monotonic()
    res = client.post(JOBS_URL, json=_body(tmp_path, request_id="req-b"))
    elapsed = time.monotonic() - started
    # then — 큐 대기가 접수를 막지 않는다(§4.1 계약)
    assert res.status_code == 202
    body = res.json()
    assert body["status"] == "RECEIVED"
    assert body["job_id"] and body["received_at"]
    assert elapsed < 1.0, f"접수가 큐 대기에 묶였다 elapsed={elapsed:.3f}s"


# ── 5. FIFO ──────────────────────────────────────────────────────
def test_큐는_FIFO_순서를_지킨다(
    client: TestClient, tmp_path, slot_gate: threading.Event, webhooks: list
) -> None:
    # given — 슬롯 1개를 점유하고 3건을 순서대로 접수
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    queued = [_submit(client, tmp_path, request_id=f"req-{n}") for n in ("b", "c", "d")]
    # then — 대기열은 접수 순서를 유지한다
    assert _queue(client)["waiting_job_ids"] == queued
    # when — 슬롯을 열어 전부 처리
    slot_gate.set()
    for job_id in [first, *queued]:
        _wait_status(client, job_id, "SUCCEEDED", timeout=10.0)
    # then — 완료(발신) 순서도 접수 순서와 같다
    assert _succeeded_order(webhooks) == [first, *queued]


def test_스케줄러는_슬롯이_빌_때마다_대기_선두에_배정한다() -> None:
    # given — HTTP 없이 스케줄러 단위로 FIFO 배정을 검증
    import asyncio

    from app.services.genai_sim import JobScheduler

    async def _scenario() -> list[str]:
        scheduler = JobScheduler(max_concurrency=1)
        granted: list[str] = []

        async def _worker(job_id: str) -> None:
            if await scheduler.wait_for_slot(job_id):
                granted.append(job_id)

        for job_id in ("j1", "j2", "j3"):
            scheduler.enqueue(job_id)
        tasks = [asyncio.create_task(_worker(j)) for j in ("j1", "j2", "j3")]
        await asyncio.sleep(0)
        # when / then — 슬롯이 1개라 선두만 배정된다
        assert granted == ["j1"]
        scheduler.release("j1")
        await asyncio.sleep(0)
        assert granted == ["j1", "j2"]
        scheduler.release("j2")
        await asyncio.sleep(0)
        scheduler.release("j3")
        await asyncio.gather(*tasks)
        return granted

    assert asyncio.run(_scenario()) == ["j1", "j2", "j3"]


# ── 6. 대기 중 취소분은 슬롯을 쓰지 않는다 ───────────────────────
def test_대기_중_취소된_작업은_처리_슬롯을_차지하지_않는다(
    client: TestClient, tmp_path, slot_gate: threading.Event, webhooks: list
) -> None:
    # given — 대기열 [b, c] 에서 b 를 취소
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    second = _submit(client, tmp_path, request_id="req-b")
    third = _submit(client, tmp_path, request_id="req-c")
    assert _queue(client)["waiting_job_ids"] == [second, third]
    assert client.post(f"{JOBS_URL}/{second}/cancel", json={"requested_by": "w1"}).status_code == 200
    assert _queue(client)["waiting_job_ids"] == [third]
    # when
    slot_gate.set()
    _wait_status(client, first, "SUCCEEDED")
    _wait_status(client, third, "SUCCEEDED", timeout=10.0)
    # then — 취소분은 실행되지 않았고(슬롯 미점유) 다음 작업이 바로 처리됐다
    canceled = _status(client, second)
    assert canceled["status"] == "CANCELED"
    assert canceled["started_at"] is None
    assert all(payload["job_id"] != second for _, payload in webhooks)
    assert _succeeded_order(webhooks) == [first, third]
    assert _queue(client)["running"] == 0


def test_mock_reset은_대기_중인_작업까지_정리한다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given — 실행 1건 + 대기 2건
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    _submit(client, tmp_path, request_id="req-b")
    _submit(client, tmp_path, request_id="req-c")
    assert _queue(client)["waiting"] == 2
    # when — 목 전용 reset (게이트를 열어 진행 태스크가 취소를 받아들이게 한다)
    slot_gate.set()
    res = client.post("/api/genai/_mock/reset")
    # then — 대기열·슬롯이 모두 비워져 다음 작업이 곧바로 처리된다
    assert res.status_code == 200
    queue = _queue(client)
    assert queue["running"] == 0
    assert queue["waiting_job_ids"] == []
    fresh = _submit(client, tmp_path, request_id="req-d")
    _wait_status(client, fresh, "SUCCEEDED")


# ── 7. FAILED 트리거는 큐를 거쳐도 유지 ──────────────────────────
def test_fail_접두_요청은_큐를_거쳐도_FAILED_로_끝난다(
    client: TestClient, tmp_path, slot_gate: threading.Event
) -> None:
    # given — 대기열에 실패 트리거 작업을 넣는다
    first = _submit(client, tmp_path, request_id="req-a")
    _wait_holding_slot(client, first)
    failing = _submit(client, tmp_path, request_id="fail-queued-0001")
    assert _status(client, failing)["status"] == "RECEIVED"
    # when
    slot_gate.set()
    _wait_status(client, first, "SUCCEEDED")
    # then
    body = _wait_status(client, failing, "FAILED", timeout=10.0)
    assert body["error_code"] == "MODEL_EXECUTION_FAILED"
    assert body["error_message"]
    assert _queue(client)["running"] == 0
