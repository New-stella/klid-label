"""
용도별 실행 슬롯(ADR-056) 회귀 테스트.

지키는 것 넷:

1. 배치 슬롯이 포화돼도 **화면 요청이 그 뒤에 줄서지 않는다**(슬롯 비차단성).
2. ``X-Workload`` 판정은 **정확히 ``batch``** 하나다 — 미지정·빈값·오타·대소문자 불일치는
   전부 화면으로 떨어진다(fail-safe 방향).
3. 부하 경로 ``GET /internal/load`` 는 **슬롯을 거치지 않아** 포화 중에도 즉시 답한다.
4. ``GET /health`` 응답 본문은 **바뀌지 않는다** — 살아있음과 여유는 다른 축이다.
"""

from __future__ import annotations

import asyncio
import sys
import threading
import time
import types

import pytest
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient

from app.config import reload_settings
from app.main import app
from app.models import sam2_loader
from app.routers import yolo as yolo_router
from app.slots import (
    BATCH,
    INTERACTIVE,
    SLOT_MAX_WORKERS,
    SLOT_NAMES,
    ExecutionSlot,
    get_slot_registry,
    resolve_slot_name,
    run_in_slot,
)

client = TestClient(app)


# ────────────────────────────────────────────────────────────────────
# 수용기준 2 — 헤더 판정은 「정확히 batch」 하나다
# ────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "header_value",
    [
        None,           # 미지정 — 구 버전 호출자
        "",             # 빈값
        " ",            # 공백
        "batch ",       # 뒤 공백 — 느슨하게 strip 하면 배치로 샌다
        " batch",       # 앞 공백
        "Batch",        # 대소문자 불일치
        "BATCH",
        "bacth",        # 오타
        "batches",
        "interactive",  # 화면을 명시
        "online",
        "true",
    ],
)
def test_batch가_아닌_모든_헤더값은_화면_슬롯으로_간다(header_value) -> None:
    """fail-safe 방향은 화면이다 — 표시를 빠뜨려도 사람이 쓰는 쪽이 보호된다."""
    assert resolve_slot_name(header_value) == INTERACTIVE


def test_정확히_batch일_때만_배치_슬롯이다() -> None:
    assert resolve_slot_name("batch") == BATCH


def test_슬롯은_둘이고_각_동시처리수는_1이다() -> None:
    """1보다 크게 하면 SAM2 인스턴스 붕괴·트래커 뒤섞임이 슬롯 안에서 재현된다."""
    assert SLOT_NAMES == (BATCH, INTERACTIVE)
    assert SLOT_MAX_WORKERS == 1


def test_모르는_슬롯이름은_화면_슬롯으로_떨어진다() -> None:
    registry = get_slot_registry()
    assert registry.get("nope") is registry.get(INTERACTIVE)


def _thread_names_for(header: str | None) -> str:
    """헤더를 실어 실제 요청을 보내고, 추론이 돈 **스레드 이름**을 돌려준다.

    슬롯 배선이 실제로 갈리는지는 이름 규약(``ai-slot-{슬롯}``)으로만 확증할 수 있다 —
    응답 본문에는 슬롯이 드러나지 않기 때문이다.
    """
    seen: dict[str, str] = {}
    original = yolo_router._predict_yolox

    def _spy(req):
        seen["thread"] = threading.current_thread().name
        return original(req)

    yolo_router._predict_yolox = _spy  # type: ignore[assignment]
    try:
        headers = {} if header is None else {"X-Workload": header}
        res = client.post(
            "/infer/yolo/predict",
            json={"image_b64": _tiny_png_b64()},
            headers=headers,
        )
        assert res.status_code == 200, res.text
    finally:
        yolo_router._predict_yolox = original  # type: ignore[assignment]
    return seen["thread"]


def _tiny_png_b64() -> str:
    import base64
    import io

    from PIL import Image

    buf = io.BytesIO()
    Image.new("RGB", (32, 32), color=(10, 20, 30)).save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def test_추론이_이벤트루프가_아니라_슬롯_스레드에서_돈다() -> None:
    """오프로드 자체의 증거 — 이 배선이 없으면 모든 요청이 도착 순서로 직렬화된다."""
    assert _thread_names_for(None).startswith(f"ai-slot-{INTERACTIVE}")


def test_배치_헤더는_배치_슬롯_스레드로_간다() -> None:
    assert _thread_names_for("batch").startswith(f"ai-slot-{BATCH}")


@pytest.mark.parametrize("header", ["Batch", "batch ", "bacth", ""])
def test_배치가_아닌_헤더는_HTTP경로에서도_화면_슬롯이다(header) -> None:
    """판정을 라우터가 아니라 한 곳(resolve_slot_name)에서만 한다는 것의 end-to-end 확인."""
    assert _thread_names_for(header).startswith(f"ai-slot-{INTERACTIVE}")


# ────────────────────────────────────────────────────────────────────
# 수용기준 1·4 — 포화 중 비차단성 + 부하 경로
# ────────────────────────────────────────────────────────────────────

def _run(coro):
    return asyncio.run(coro)


async def _saturate_batch_and(probe, *, queued: int = 3):
    """배치 슬롯을 실행 1 + 대기 ``queued`` 건으로 채운 뒤 ``probe()`` 를 수행한다."""
    gate = threading.Event()
    tasks = [
        asyncio.create_task(run_in_slot("batch", gate.wait, 30))
        for _ in range(queued + 1)
    ]
    try:
        # 첫 태스크가 실제로 워커를 잡을 때까지 기다린다(대기 건수가 확정되도록).
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            if get_slot_registry().get(BATCH).load_snapshot()["running"] == 1:
                break
            await asyncio.sleep(0.01)
        return await probe()
    finally:
        gate.set()
        await asyncio.gather(*tasks, return_exceptions=True)


def test_배치가_포화돼도_화면_요청이_막히지_않는다() -> None:
    """AC1 — 구 형상에서는 이 요청이 배치 큐가 다 빠질 때까지 기다렸다(실측 21.9초)."""

    async def scenario():
        async def probe():
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://slot-test"
            ) as http:
                started = time.monotonic()
                res = await http.post(
                    "/infer/yolo/predict", json={"image_b64": _tiny_png_b64()}
                )
                return res, time.monotonic() - started

        return await _saturate_batch_and(probe)

    res, elapsed = _run(scenario())
    assert res.status_code == 200, res.text
    # 배치 태스크는 게이트가 열릴 때까지 최대 30초를 붙잡는다 — 그 뒤에 줄섰다면 여기서 드러난다.
    assert elapsed < 5.0, f"화면 요청이 배치 뒤에 줄섰다 elapsed={elapsed:.3f}s"


def test_부하_경로는_배치_포화_중에도_즉시_답한다() -> None:
    """AC4 — 이 경로가 슬롯 뒤에 있으면 정확히 필요한 순간에 못 읽는다."""

    async def scenario():
        async def probe():
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://slot-test"
            ) as http:
                started = time.monotonic()
                res = await http.get("/internal/load")
                return res, time.monotonic() - started

        return await _saturate_batch_and(probe, queued=3)

    res, elapsed = _run(scenario())
    assert res.status_code == 200
    assert elapsed < 2.0, f"부하 경로가 슬롯 뒤에 있다 elapsed={elapsed:.3f}s"

    body = res.json()
    assert set(body.keys()) == {"slots", "observed_at"}
    assert set(body["slots"].keys()) == set(SLOT_NAMES)
    for stats in body["slots"].values():
        assert set(stats.keys()) == {"running", "queued", "oldest_wait_ms"}
        assert all(isinstance(v, int) for v in stats.values())

    batch = body["slots"][BATCH]
    assert batch["running"] == 1
    assert batch["queued"] == 3
    assert batch["oldest_wait_ms"] >= 0
    # 화면 슬롯은 배치 포화와 무관하게 비어 있다 — 두 축이 섞이지 않는다는 증거.
    assert body["slots"][INTERACTIVE] == {"running": 0, "queued": 0, "oldest_wait_ms": 0}


def test_대기_건수는_소진되면_0으로_돌아온다() -> None:
    """큐가 빠진 뒤에도 카운터가 부풀어 있으면 앞단이 영영 포화로 읽는다."""

    async def scenario():
        gate = threading.Event()
        tasks = [asyncio.create_task(run_in_slot("batch", gate.wait, 30)) for _ in range(4)]
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            if get_slot_registry().get(BATCH).load_snapshot()["queued"] == 3:
                break
            await asyncio.sleep(0.01)
        peak = get_slot_registry().get(BATCH).load_snapshot()
        gate.set()
        await asyncio.gather(*tasks)
        return peak, get_slot_registry().get(BATCH).load_snapshot()

    peak, drained = _run(scenario())
    assert peak["queued"] == 3
    assert drained == {"running": 0, "queued": 0, "oldest_wait_ms": 0}


def _batch_snapshot() -> dict[str, int]:
    return get_slot_registry().get(BATCH).load_snapshot()


async def _wait_until(predicate, *, timeout: float = 5.0) -> None:
    """조건이 설 때까지 짧게 기다린다 — 워커 기동 타이밍에 시험이 흔들리지 않도록."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError(f"조건이 {timeout}s 안에 서지 않았다 snapshot={_batch_snapshot()}")


def test_추론이_실패해도_카운터가_0으로_돌아온다() -> None:
    """실패가 카운터를 부풀리지 않는다 — 대기 없이 순차 실패만 보는 얕은 축이다.

    ⚠ **이 시험은 되돌림 가드를 지키지 못한다.** 대기열이 비어 있으면 여분 ``pop()`` 이
    ``IndexError`` 로 삼켜져 가드의 유무가 결과에 드러나지 않는다. 그 축은 아래
    :func:`test_대기가_밀린_상태에서_추론이_실패해도_남의_대기_항목을_지우지_않는다` 가 맡는다.
    """

    async def scenario():
        def _boom():
            raise RuntimeError("추론 실패")

        for _ in range(3):
            with pytest.raises(RuntimeError):
                await run_in_slot("batch", _boom)
        return _batch_snapshot()

    assert _run(scenario()) == {"running": 0, "queued": 0, "oldest_wait_ms": 0}


def test_대기가_밀린_상태에서_추론이_실패해도_남의_대기_항목을_지우지_않는다() -> None:
    """정상적인 추론 예외가 **남의 대기 항목**을 지우면 부하가 과소 보고된다.

    되돌림은 「제출이 실패했는가」로만 일어나야 하고 「추론이 실패했는가」로 일어나면 안 된다.
    그 구분이 없으면 실패 1건마다 대기 항목이 하나씩 사라져, 소비자(노드 선택기)가 **바쁜 노드를
    한가한 것으로 읽고 그쪽으로 더 보낸다** — 조용히, 그리고 틀린 방향으로 틀린다.

    ⚠ **대기 2건 이상**이라야 이 축이 드러난다. 대기가 0~1건이면 여분 ``pop()`` 이 큐를 비우거나
    ``IndexError`` 로 삼켜져 가드 유무가 구분되지 않는다(그래서 얕은 시험이 이 결함을 놓쳤다).
    """

    async def scenario():
        holding = threading.Event()   # 앞선 태스크를 붙잡아 대기열을 만든다
        blocking = threading.Event()  # 실패 뒤에도 대기열이 남아 있게 붙잡는다

        def _boom():
            raise RuntimeError("추론 실패")

        occupying = asyncio.create_task(run_in_slot("batch", holding.wait, 30))
        await _wait_until(lambda: _batch_snapshot()["running"] == 1)

        # 실패할 태스크 1건 + 그 뒤에 줄선 태스크 2건 — 지워지면 안 되는 것이 이 둘이다.
        failing = asyncio.create_task(run_in_slot("batch", _boom))
        queued = [
            asyncio.create_task(run_in_slot("batch", blocking.wait, 30)) for _ in range(2)
        ]
        await _wait_until(lambda: _batch_snapshot()["queued"] == 3)

        holding.set()
        with pytest.raises(RuntimeError):
            await failing
        # 실패 직후 다음 대기 태스크가 워커를 잡은 시점 — 그때의 대기 건수가 판정 대상이다.
        await _wait_until(lambda: _batch_snapshot()["running"] == 1)
        after_failure = _batch_snapshot()

        blocking.set()
        await asyncio.gather(occupying, *queued)
        return after_failure, _batch_snapshot()

    after_failure, drained = _run(scenario())
    assert after_failure["running"] == 1
    assert after_failure["queued"] >= 1, (
        f"정상적인 추론 예외가 남의 대기 항목을 지웠다 — 부하가 과소 보고된다: {after_failure}"
    )
    assert drained == {"running": 0, "queued": 0, "oldest_wait_ms": 0}


def test_oldest_wait_ms는_실제로_기다린_시간을_센다() -> None:
    async def scenario():
        gate = threading.Event()
        first = asyncio.create_task(run_in_slot("batch", gate.wait, 30))
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            if get_slot_registry().get(BATCH).load_snapshot()["running"] == 1:
                break
            await asyncio.sleep(0.01)
        waiting = asyncio.create_task(run_in_slot("batch", gate.wait, 30))
        await asyncio.sleep(0.15)
        snapshot = get_slot_registry().get(BATCH).load_snapshot()
        gate.set()
        await asyncio.gather(first, waiting)
        return snapshot

    snapshot = _run(scenario())
    assert snapshot["queued"] == 1
    assert snapshot["oldest_wait_ms"] >= 100


def test_슬롯_스냅샷_읽기는_큐를_순회하지_않는다() -> None:
    """O(1) 보증 — 대기 건수가 늘어도 읽는 비용이 따라 늘면 관측이 슬롯을 방해한다."""
    slot = ExecutionSlot("probe-only")
    try:
        for _ in range(10_000):
            slot._waiting.append(time.monotonic())
        started = time.perf_counter()
        for _ in range(1_000):
            slot.load_snapshot()
        elapsed = time.perf_counter() - started
    finally:
        slot.shutdown()
    assert elapsed < 0.5, f"스냅샷 1,000회에 {elapsed:.3f}s — 큐를 순회하고 있다"


# ────────────────────────────────────────────────────────────────────
# 수용기준 5 — /health 는 그대로다
# ────────────────────────────────────────────────────────────────────

def test_health_응답_본문은_바뀌지_않았다() -> None:
    """부하를 섞으면 포화 때 헬스가 내려가 앞단이 살아있는 노드를 이탈시킨다."""
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json() == {"status": "ok"}


# ────────────────────────────────────────────────────────────────────
# 수용기준 3 — SAM2 는 슬롯마다 별도 인스턴스
# ────────────────────────────────────────────────────────────────────

def _install_fake_sam2(monkeypatch) -> dict:
    """``from_pretrained`` 호출 횟수를 세는 가짜 sam2 패키지를 심는다."""
    built = {"n": 0}

    class _Fake:
        @classmethod
        def from_pretrained(cls, model_id, **kwargs):
            built["n"] += 1
            return object()

    fake_mod = types.ModuleType("sam2.sam2_image_predictor")
    fake_mod.SAM2ImagePredictor = _Fake  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "sam2", types.ModuleType("sam2"))
    monkeypatch.setitem(sys.modules, "sam2.sam2_image_predictor", fake_mod)
    return built


def test_SAM2_predictor는_슬롯마다_별도_인스턴스다(monkeypatch) -> None:
    """공유하면 이미지 임베딩이 서로 덮여 마스크가 어긋나고 스레드가 죽는다(실측)."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()
    built = _install_fake_sam2(monkeypatch)
    try:
        batch_model = sam2_loader.get_sam2_model(BATCH)
        interactive_model = sam2_loader.get_sam2_model(INTERACTIVE)

        assert batch_model is not None and interactive_model is not None
        assert batch_model is not interactive_model, "슬롯이 인스턴스를 나눠 쓰고 있다"
        assert built["n"] == 2
        # 슬롯 안에서는 여전히 싱글톤 — 요청마다 새로 만들지 않는다.
        assert sam2_loader.get_sam2_model(BATCH) is batch_model
        assert built["n"] == 2
    finally:
        sam2_loader.reset_sam2_model()


def test_워밍업은_슬롯_수만큼_predictor를_만든다(monkeypatch) -> None:
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()
    built = _install_fake_sam2(monkeypatch)
    try:
        sam2_loader.warmup_sam2_models()
        assert built["n"] == len(SLOT_NAMES)
        models = [sam2_loader.get_sam2_model(name) for name in SLOT_NAMES]
        assert len({id(m) for m in models}) == len(SLOT_NAMES)
    finally:
        sam2_loader.reset_sam2_model()


def test_슬롯을_명시하지_않은_로더_호출은_화면_슬롯이다(monkeypatch) -> None:
    """기본값이 배치면 화면 요청이 배치 인스턴스를 물어 임베딩이 섞인다."""
    monkeypatch.setenv("AI_MOCK_MODE", "false")
    reload_settings()
    sam2_loader.reset_sam2_model()
    _install_fake_sam2(monkeypatch)
    try:
        assert sam2_loader.get_sam2_model() is sam2_loader.get_sam2_model(INTERACTIVE)
    finally:
        sam2_loader.reset_sam2_model()
