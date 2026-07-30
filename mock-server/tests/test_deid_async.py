"""KPST 비식별화 목 — **비동기 접수 모델** 계약 테스트.

★ 구속 원칙 — <b>외부연동은 모두 비동기다</b>. HTTP 요청 처리는 외부 작업(ffmpeg 인코딩·파일
복사·ffprobe)의 완료를 <b>절대 기다리지 않는다</b>. 실제 KPST 도 ``POST /project`` 로 접수만 하고
우리 BE(``KpstDeidentPollJob``)가 ``retrieve_progress`` 를 폴링해 완료(procState=2)를 감지한다.

이 모듈이 고정하는 계약:
- **접수 즉시 반환**: 산출이 아직 진행 중이어도 ``POST /project`` 는 곧바로 200 을 돌려준다.
- **완료는 산출 종료 이후**: 경과초가 아무리 커도 산출이 끝나기 전에는 ``procState`` 가 완료(2)가
  되지 않는다. 산출이 끝난 뒤에야 완료로 보고한다.
- **산출 실패는 명시적 실패**: 조용한 placeholder 로 최종 이름을 선점하지 않고 ``procState=99``
  (KPST 오류 sentinel)로 보고해 BE 가 즉시 terminal 'F' 로 종결하게 한다.

단정은 <b>실제 관측값</b>(경과 시간·산출물 실재·상태 전이)만 쓴다 — 프로덕션 상수를 그대로 베낀
산술 단정(동어반복 가드)은 두지 않는다.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import threading
import time
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.services import deid_sim, media_probe

_FFMPEG = shutil.which("ffmpeg")


def _has_drawtext() -> bool:
    if _FFMPEG is None:
        return False
    res = subprocess.run(
        [_FFMPEG, "-hide_banner", "-loglevel", "error", "-filters"],
        capture_output=True, timeout=30, check=False,
    )
    return res.returncode == 0 and b" drawtext " in res.stdout


requires_ffmpeg = pytest.mark.skipif(
    not (_has_drawtext() and any(Path(p).is_file() for p in deid_sim.WATERMARK_FONT_CANDIDATES)),
    reason="drawtext 지원 ffmpeg + 한글 폰트가 없는 환경 — 실제 인코딩 검증 skip",
)


@pytest.fixture(autouse=True)
def _clean_store() -> Iterator[None]:
    from app.state import get_store

    get_store().clear()
    yield
    get_store().clear()


def _get_with_body(client: TestClient, path: str, body: dict):
    """우리 BE 호출 방식 재현 — GET + JSON 바디."""
    return client.request(
        "GET", path, content=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )


def _poll(client: TestClient, prj_id: int) -> dict:
    res = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1", "prjId": prj_id})
    assert res.status_code == 200
    return res.json()["data"]["prjStatus"][0]


def _force_elapsed_complete(prj_id: int) -> None:
    """경과초만 100% 로 만든다(산출 완료와는 독립 축)."""
    from app.state import get_store

    project = get_store().get_project(prj_id)
    assert project is not None
    project.created_monotonic = time.monotonic() - 100000.0


def _make_black_video(target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [_FFMPEG, "-v", "error", "-n", "-f", "lavfi",
         "-i", "color=c=black:s=320x240:d=1:r=5",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", str(target)],
        capture_output=True, timeout=180, check=True,
    )
    return target


def _first_frame_gray(video: Path) -> bytes:
    res = subprocess.run(
        [_FFMPEG, "-v", "error", "-i", str(video), "-frames:v", "1",
         "-pix_fmt", "gray", "-f", "rawvideo", "-"],
        capture_output=True, timeout=60, check=True,
    )
    return res.stdout


def _region_max(pixels: bytes, width: int, x0: int, y0: int, x1: int, y1: int) -> int:
    best = 0
    for y in range(y0, y1):
        row = pixels[y * width : y * width + width]
        best = max(best, max(row[x0:x1]))
    return best


def _create(client: TestClient, name: str, **over) -> dict:
    body = {
        "project_name": name, "creator": "w1",
        "export_path": "/nas/export/", "input_path": "/nas/input/",
        "files": ["a.mp4"], "is_img": 0,
    }
    body.update(over)
    res = client.post("/project", json=body)
    assert res.status_code == 200, res.text
    return res.json()


# ── 1. 접수 즉시 반환 (구속 원칙) ─────────────────────────────────
def test_project_접수는_산출완료를_기다리지_않고_즉시_반환한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """★ 구속 원칙 — HTTP 요청 처리가 외부 작업 완료를 기다리면 안 된다."""
    # given — 산출을 인위적으로 붙잡아두는 스텁(해제 전까지 끝나지 않는다)
    release = threading.Event()
    started = threading.Event()

    def _blocking_produce(**_kwargs):
        started.set()
        assert release.wait(timeout=10.0), "테스트 산출 스텁이 해제되지 않았다"
        return deid_sim.ProductionOutcome(written=[], failed=False)

    monkeypatch.setattr(deid_sim, "produce_deid_outputs", _blocking_produce)

    # when — 접수 요청의 실제 경과 시간을 잰다
    began = time.monotonic()
    body = _create(client, "async1")
    elapsed = time.monotonic() - began

    # then — 산출은 아직 붙잡혀 있는데도 응답은 이미 돌아왔다
    assert elapsed < 2.0, f"접수가 산출 완료를 기다렸다(elapsed={elapsed:.2f}s)"
    assert started.wait(timeout=5.0), "백그라운드 산출이 시작되지 않았다"
    assert deid_sim.production_state_of(body["prj_id"]) == "RUNNING"

    # cleanup — 스텁 해제 후 종료까지 확인
    release.set()
    assert deid_sim.wait_for_production(body["prj_id"], timeout=10.0) == "SUCCEEDED"


# ── 2. 진행중 → 완료 보고 ─────────────────────────────────────────
def test_산출이_끝나기_전에는_진행중_끝난_뒤에_완료를_보고한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 산출을 붙잡아둔 채 경과초만 100% 로 만든다
    release = threading.Event()
    started = threading.Event()

    def _blocking_produce(**_kwargs):
        started.set()
        assert release.wait(timeout=10.0)
        return deid_sim.ProductionOutcome(written=[], failed=False)

    monkeypatch.setattr(deid_sim, "produce_deid_outputs", _blocking_produce)
    prj_id = _create(client, "async2")["prj_id"]
    assert started.wait(timeout=5.0)
    _force_elapsed_complete(prj_id)

    # when — 산출이 끝나기 전 폴링
    during = _poll(client, prj_id)

    # then — 경과초가 100% 여도 완료로 보고하지 않는다
    assert during["dsStatus"][0]["procState"] != 2
    assert during["progressRate"] < 100.0

    # when — 산출 완료 후 폴링
    release.set()
    assert deid_sim.wait_for_production(prj_id, timeout=10.0) == "SUCCEEDED"
    after = _poll(client, prj_id)

    # then
    assert after["progressRate"] == 100.0
    assert after["prjState"] == 3
    assert after["dsStatus"][0]["procState"] == 2


# ── 3. 완료 후 산출물 실재 + 워터마크 ─────────────────────────────
@requires_ffmpeg
def test_비동기_산출_완료후_산출물이_실재하고_워터마크가_구워져있다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    src = _make_black_video(raw / "a.mp4")
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when — 접수 → (백그라운드 산출) → 완료 대기
    prj_id = _create(
        client, "async3", export_path=f"{export}/", input_path=f"{raw}/", files=["a.mp4"]
    )["prj_id"]
    assert deid_sim.wait_for_production(prj_id, timeout=120.0) == "SUCCEEDED"

    # then — 산출물이 실재하고 워터마크가 구워져 있다
    out = export / "a-mask.mp4"
    assert out.is_file()
    assert out.read_bytes() != src.read_bytes()
    pixels = _first_frame_gray(out)
    assert _region_max(pixels, 320, 320 - 200, 240 - 60, 320, 240) > 40
    # 그리고 폴링이 완료를 보고한다
    _force_elapsed_complete(prj_id)
    assert _poll(client, prj_id)["dsStatus"][0]["procState"] == 2
    reload_settings()


# ── 4. 산출 실패는 procState=99 (BE terminal) ─────────────────────
def test_산출이_실패하면_procState_99로_보고해_BE가_즉시_종결한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """BE ``KpstDeidentService.PROC_STATE_TERMINAL_FAILED`` 에 99 가 포함된다(코드 실측)."""
    # given
    def _failing_produce(**_kwargs):
        return deid_sim.ProductionOutcome(written=[], failed=True, reason="TEST")

    monkeypatch.setattr(deid_sim, "produce_deid_outputs", _failing_produce)

    # when
    prj_id = _create(client, "async4")["prj_id"]
    assert deid_sim.wait_for_production(prj_id, timeout=10.0) == "FAILED"
    _force_elapsed_complete(prj_id)
    status = _poll(client, prj_id)

    # then — 완료(2)로 위장하지 않고 오류 sentinel 을 보고한다
    assert status["dsStatus"][0]["procState"] == 99
    assert status["progressRate"] < 100.0
    assert status["prjState"] == 5  # KPST prjState 5 = 오류


# ── 5. 복사 예산 초과가 최종 이름을 선점하지 않는다 (#5) ───────────
def test_복사예산_초과는_최종이름을_선점하지_않고_실패로_보고한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """구 동작: 18B placeholder 가 최종 이름을 선점 → no-overwrite 로 영구 고착."""
    # given — 원본보다 훨씬 작은 복사 상한
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    (in_dir / "a.mp4").write_bytes(b"ORIGINAL" * 100)
    monkeypatch.setattr(deid_sim, "COPY_MAX_BYTES", 64)

    # when
    outcome = deid_sim.produce_deid_outputs(
        export_path=str(out_dir), input_path=str(in_dir),
        outputs=[("a.mp4", "a-mask.mp4")],
        output_base=str(storage), input_base=str(storage),
    )

    # then — 최종 이름은 비어 있고(재시도 가능) 산출은 실패로 보고된다
    assert outcome.failed is True
    assert not (out_dir / "a-mask.mp4").exists()


# ── 6. 복사도 은닉 임시경로를 거쳐 원자 배치 (#3) ──────────────────
def test_복사도_최종경로에_직접_쓰지_않고_은닉_임시경로를_거친다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """SIGKILL 은 ``finally`` 를 실행하지 않는다 — 최종 경로에 직접 쓰면 잘린 파일이 남는다."""
    # given — 복사가 실제로 <b>열어서 쓰는</b> 경로를 관측한다
    src = tmp_path / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 200)
    target = tmp_path / "a-mask.mp4"
    opened: list[Path] = []
    real_open = deid_sim._create_exclusive

    def _observe(path: Path):
        opened.append(Path(path))
        return real_open(path)

    monkeypatch.setattr(deid_sim, "_create_exclusive", _observe)

    # when
    assert (
        deid_sim._copy_no_overwrite(src, target) is deid_sim.OutputWriteResult.PLACED
    )

    # then — 쓰기 대상은 은닉 임시 디렉터리 안이었고, 최종 경로는 원자 배치로만 생겼다
    assert opened, "쓰기 대상 경로를 관측하지 못했다"
    assert all(p.parent.name == deid_sim.TEMP_DIR_NAME for p in opened), opened
    assert target not in opened
    assert target.read_bytes() == src.read_bytes()
    assert list((tmp_path / deid_sim.TEMP_DIR_NAME).iterdir()) == []


# ── 7. 길이 검증 fail-open 제거 (#4) ──────────────────────────────
def test_원본_길이를_확인할_수_없으면_승격하지_않고_복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """구 동작: 원본 길이 조회 실패 → 검증 생략(True) → 외부에서 유도 가능한 fail-open."""
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    temp = out_dir / "a.tmp.mp4"
    temp.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    monkeypatch.setattr(deid_sim, "_duration_of", lambda *_a, **_k: None)

    # when / then — 길이를 모르면 승격하지 않는다(호출측은 원본을 온전히 복사한다)
    assert deid_sim._is_duration_preserved(src, temp, str(in_dir), str(out_dir)) is False


def test_deid_길이조회는_describe_세마포어_포화에_영향받지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """describe 다발로 ffprobe 세마포어를 포화시켜 deid 검증을 무력화할 수 없어야 한다(#4)."""
    # given — VLM(describe) 용 세마포어를 전부 선점한다
    media = tmp_path / "a.mp4"
    media.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    vlm_sem = media_probe.semaphore_for(media_probe.PURPOSE_VLM)
    held = 0
    while vlm_sem.acquire(blocking=False):
        held += 1
    assert held > 0

    monkeypatch.setattr(media_probe.shutil, "which", lambda name: f"/usr/bin/{name}")
    monkeypatch.setattr(
        media_probe.subprocess, "run",
        lambda *_a, **_k: subprocess.CompletedProcess([], 0, b"12.5\n", b""),
    )

    try:
        # when — deid 용도의 길이 조회
        duration = media_probe.probe_duration_sec(
            media, str(tmp_path), purpose=media_probe.PURPOSE_DEID
        )
    finally:
        for _ in range(held):
            vlm_sem.release()

    # then — 포화된 것은 describe 쪽이고 deid 는 정상 조회된다
    assert duration == pytest.approx(12.5)


# ── 8. 경계 판정 단일 원천 (#6) ───────────────────────────────────
def test_허용_읽기루트_미설정이면_워터마킹_경계_재검증이_거부한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """구 동작: 재검증이 fail-open 저수준 헬퍼를 호출해 루트 미설정이 '제한 없음'이 됐다."""
    # given — 허용 읽기 루트 미설정(input_base="")
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    monkeypatch.setattr(
        deid_sim.subprocess, "run",
        lambda cmd, **kw: calls.append(cmd) or subprocess.CompletedProcess(cmd, 1, b"", b""),
    )

    # when
    ok = deid_sim._burn_deid_watermark(
        src, out_dir / "a-mask.mp4", input_path=str(in_dir), input_base="",
        output_dir=out_dir, export_path=str(out_dir), output_base=str(tmp_path),
    )

    # then — ffmpeg 를 아예 실행하지 않는다(fail-closed)
    assert ok is False
    assert calls == []


def test_fail_open_저수준_헬퍼는_deid_sim에_재노출되지_않는다() -> None:
    """정책 진입점(``path_policy.resolve_readable_dir``) 우회 통로를 남기지 않는다(#6)."""
    assert not hasattr(deid_sim, "resolve_input_dir")


# ── 9. 산출 동시성 상한이 이벤트 루프 교체에도 살아있는가 ──────────
def test_산출_동시성_상한은_이벤트루프가_바뀌어도_동작한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``asyncio`` 동기화 객체는 처음 대기한 루프에 바인딩된다.

    전역 세마포어를 한 개만 두면 재기동/새 루프에서 대기하는 순간
    ``RuntimeError: bound to a different event loop`` 로 <b>산출이 통째로 실패</b>한다.
    상한을 1로 줄여 <b>반드시 대기가 발생</b>하게 만든 뒤, 서로 다른 루프에서 두 번 돌린다.
    """
    from fastapi.testclient import TestClient as _TestClient

    from app.main import app

    # 상한 1 + 동시에 3건 접수 → 반드시 대기가 발생한다(대기 = 루프 바인딩 시점).
    monkeypatch.setattr(deid_sim, "PRODUCTION_MAX_CONCURRENCY", 1)
    release = threading.Event()
    running = threading.Semaphore(0)
    counter_lock = threading.Lock()
    state = {"concurrent": 0, "peak": 0}

    def _blocking_produce(**_kwargs):
        with counter_lock:
            state["concurrent"] += 1
            state["peak"] = max(state["peak"], state["concurrent"])
        running.release()
        try:
            assert release.wait(timeout=15.0)
        finally:
            with counter_lock:
                state["concurrent"] -= 1
        return deid_sim.ProductionOutcome(written=[], failed=False)

    monkeypatch.setattr(deid_sim, "produce_deid_outputs", _blocking_produce)

    for loop_idx in range(2):
        release.clear()
        with _TestClient(app) as c:
            prj_ids = [_create(c, f"loopsem{loop_idx}-{n}")["prj_id"] for n in range(3)]
            # 상한 때문에 1건만 실행되고 나머지 2건은 세마포어에서 대기한다
            assert running.acquire(timeout=10.0)
            # LOW-5 — <b>실제 동시 실행 수</b>를 관측한다. 구 단정은 "1건이 시작됐다"만 봐서
            # 상한이 무시되고 3건이 동시에 돌아도 통과했다.
            time.sleep(0.3)
            with counter_lock:
                assert state["concurrent"] == 1, (
                    f"상한 1 인데 {state['concurrent']}건이 동시에 실행됐다"
                )
            release.set()
            for prj_id in prj_ids:
                assert deid_sim.wait_for_production(prj_id, timeout=15.0) == "SUCCEEDED"

    # then — 두 루프를 통틀어 동시 실행이 상한을 넘긴 적이 없다
    assert state["peak"] == 1, f"동시 실행 최대치가 상한을 초과했다(peak={state['peak']})"


# ── 10. 시간 예산 장치 제거 (#1/#2/#7) ────────────────────────────
def test_HTTP_타임아웃에_맞춘_시간예산_장치는_존재하지_않는다() -> None:
    """비동기 접수라 45초 예산이 성립하지 않는다 — 되돌리면 절단/영구차단이 재발한다."""
    assert not hasattr(deid_sim, "WATERMARK_REQUEST_BUDGET_SEC")
    assert not hasattr(deid_sim, "_WatermarkBudget")


# ── 11. ★ HIGH-1: 산출물 0건은 완료가 아니라 procState=99 ──────────
def test_임시디렉터리를_쓸수없으면_완료가_아니라_오류99로_보고한다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """★ 적대검증 실측 재현 — production=SUCCEEDED / files=0 / procState=2 였다.

    ``.mock-tmp`` 생성이 실패하면 ``_copy_no_overwrite`` 가 ``False`` 를 돌려주는데, 호출측이
    그것을 "이미 있어서 skip(성공)"으로 해석해 <b>산출물이 하나도 없는데</b> 완료(100%)로
    보고했다. BE 는 존재하지 않는 산출물을 회수하러 간다.
    """
    # given — 허용 루트 안의 정상 원본 + export 안 ``.mock-tmp`` 가 파일로 점유됨
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    export = base / "videos" / "10"
    export.mkdir(parents=True)
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    (raw / "a.mp4").write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 2048)
    (export / deid_sim.TEMP_DIR_NAME).write_bytes(b"NOT_A_DIR")
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    prj_id = _create(
        client, "tmpfail", export_path=f"{export}/", input_path=f"{raw}/", files=["a.mp4"]
    )["prj_id"]
    assert deid_sim.wait_for_production(prj_id, timeout=30.0) == "FAILED"
    _force_elapsed_complete(prj_id)
    status = _poll(client, prj_id)

    # then — 산출물은 없고, BE 가 즉시 종결할 수 있는 오류 sentinel 이 보고된다
    assert not (export / "a-mask.mp4").exists()
    assert status["dsStatus"][0]["procState"] == 99
    assert status["prjState"] == 5
    assert status["progressRate"] < 100.0
    reload_settings()


# ── 12. MEDIUM-3: 무제한 누적 차단(태스크·프로젝트) ─────────────────
def test_미완료_산출태스크_상한을_넘으면_접수를_거부한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """조용한 고착(큐 뒤쪽 procState=1 영구)보다 BE 가 인지 가능한 거부가 낫다."""
    # given — 상한 2, 산출은 붙잡아둔다
    monkeypatch.setattr(deid_sim, "PRODUCTION_MAX_INFLIGHT", 2)
    release = threading.Event()

    def _blocking_produce(**_kwargs):
        assert release.wait(timeout=15.0)
        return deid_sim.ProductionOutcome(written=[], failed=False)

    monkeypatch.setattr(deid_sim, "produce_deid_outputs", _blocking_produce)

    try:
        prj_ids = [_create(client, f"cap{n}")["prj_id"] for n in range(2)]

        # when — 상한 초과 접수
        res = client.post(
            "/project",
            json={
                "project_name": "cap-overflow", "creator": "w1",
                "export_path": "/nas/export/", "input_path": "/nas/input/",
                "files": ["a.mp4"], "is_img": 0,
            },
        )

        # then — 명시적 거부 + <b>이름을 점유하지 않는다</b>(해소 후 재위탁이 409 로 막히면 안 됨)
        assert res.status_code == 503
        assert res.json()["error_code"] == "SERVICE_BUSY"
        from app.state import get_store

        assert get_store().get_project_by_name("cap-overflow") is None
    finally:
        release.set()
    for prj_id in prj_ids:
        assert deid_sim.wait_for_production(prj_id, timeout=15.0) == "SUCCEEDED"

    # and — 용량이 비면 같은 이름으로 정상 접수된다
    assert client.post(
        "/project",
        json={
            "project_name": "cap-overflow", "creator": "w1",
            "export_path": "/nas/export/", "input_path": "/nas/input/",
            "files": ["a.mp4"], "is_img": 0,
        },
    ).status_code == 200


def test_프로젝트_보관_상한을_넘으면_오래된_것부터_만료된다() -> None:
    """``GenAiJobStore`` 와 같은 FIFO eviction — 무인증 반복 접수의 메모리 무제한 증가 차단."""
    # given — 상한 2
    from app.state import InMemoryStore

    store = InMemoryStore(max_projects=2)
    first = store.create_project("p1")
    store.add_dataset(first.prj_id, "/nas/input/a.mp4")
    store.append_job_log(first.prj_id, "project_created", "p1")
    store.create_project("p2")

    # when — 3번째 접수
    store.create_project("p3")

    # then — 가장 오래된 것이 이름 색인·데이터셋·로그까지 함께 만료된다
    assert store.get_project(first.prj_id) is None
    assert store.get_project_by_name("p1") is None
    assert store.datasets_of(first.prj_id) == []
    assert store.job_logs(first.prj_id) == []
    assert [p.project_name for p in store.list_projects()] == ["p2", "p3"]


# ── 13. ★ MEDIUM-4: 실 ffmpeg 로 정상 영상이 막히지 않는가 ─────────
@requires_ffmpeg
def test_정상_영상은_fail_closed_길이검증을_통과해_워터마킹된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """★ 길이검증(#4 fail-closed)이 <b>정상 영상을 막지 않는다</b>는 명제의 실행 검증.

    이 명제는 지금까지 ``_fake_durations`` 스텁 뒤에 가려져 있었다(스텁 자체는 정당하다 —
    합성 ftyp 바이트는 실제 ffprobe 로 길이를 못 잰다). 여기서는 <b>어떤 스텁도 없이</b>
    실제 ffmpeg/ffprobe 로 산출해, 승격 게이트 3종(rc=0 · BE 무결성 · 길이 보존)을 모두
    실제로 통과하는지 확인한다. 막히면 목의 목적(육안 확인용 워터마크)이 무력화된다.
    """
    # given — 3초짜리 실제 영상(절단이 오차 밖으로 드러나는 길이)
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    export = base / "videos" / "10"
    export.mkdir(parents=True)
    raw = storage / "raw" / "videos" / "10"
    src = raw / "a.mp4"
    raw.mkdir(parents=True)
    subprocess.run(
        [_FFMPEG, "-v", "error", "-n", "-f", "lavfi",
         "-i", "color=c=black:s=128x96:d=3:r=5",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", str(src)],
        capture_output=True, timeout=180, check=True,
    )
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when — 스텁 없이 프로덕션 산출 경로 그대로
    outcome = deid_sim.produce_deid_outputs(
        export_path=f"{export}/",
        input_path=f"{raw}/",
        outputs=[("a.mp4", "a-mask.mp4")],
        output_base=str(base),
        input_base=str(storage),
    )

    # then — 실패 없이 산출되고, 원본 복사본이 아니라 <b>재인코딩된 워터마크본</b>이다
    out = export / "a-mask.mp4"
    assert outcome.failed is False
    assert out.is_file()
    assert out.read_bytes() != src.read_bytes(), "길이검증에 막혀 원본 복사로 폴백했다"
    # and — 길이가 보존된다(실 ffprobe)
    src_dur = _probe(src)
    out_dur = _probe(out)
    assert abs(out_dur - src_dur) <= max(1.0, src_dur * 0.02)
    reload_settings()


def _probe(video: Path) -> float:
    """ffprobe 로 재생 길이(초)를 읽는다(테스트 검증용 — 프로덕션 경로와 독립)."""
    res = subprocess.run(
        [shutil.which("ffprobe") or "ffprobe", "-v", "error",
         "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
        capture_output=True, timeout=60, check=True,
    )
    return float(res.stdout.strip())


# ── ★ #1: 접수 상한은 배출률에서 파생된다 (조용한 고착 차단) ────────
def test_접수상한은_BE_폴링예산_안에_배출_가능한_양이다() -> None:
    """★ #1 회귀 가드 — 상한이 배출률과 어긋나면 <b>상한 안에서 정상 접수된 건</b>이 큐에서
    기다리다 BE 폴링 예산을 소진해 'F' 로 끝난다(상한이 막겠다던 바로 그 조용한 고착).

    구 상수 50 에서는 마지막 접수건의 대기가 ⌈49/2⌉×720 + 720 = 18,720초 로 예산(7,200초)을
    2.6배 초과했다 — 이 단정이 그때 실패한다(RED).
    """
    import math

    # given — 마지막(N번째)으로 접수되는 잡의 총 소요 = 큐 대기 + 자기 실행
    n = deid_sim.PRODUCTION_MAX_INFLIGHT
    queue_wait = (
        math.ceil((n - 1) / deid_sim.PRODUCTION_MAX_CONCURRENCY)
        * deid_sim.PRODUCTION_WORST_CASE_SEC
    )

    # when
    worst_total = queue_wait + deid_sim.PRODUCTION_WORST_CASE_SEC

    # then — BE 가 그 건을 포기(‘F’)하기 전에 끝난다
    assert n >= 1
    assert worst_total <= deid_sim.BE_POLL_BUDGET_SEC, (
        f"상한 {n} 건은 배출률로 감당 못 한다 — 최악 {worst_total:.0f}초 > "
        f"BE 폴링 예산 {deid_sim.BE_POLL_BUDGET_SEC}초"
    )


def test_접수상한은_매직넘버가_아니라_배출률_파생값이다() -> None:
    """상수를 손으로 되돌려 놓는(예: 50) 드리프트를 붙잡는다 — 파생식이 진실원이다."""
    # given / when — 산식: N = 동시성 × (예산×마진/최악소요 − 1) + 1
    expected = max(
        1,
        int(
            deid_sim.PRODUCTION_MAX_CONCURRENCY
            * (
                (deid_sim.BE_POLL_BUDGET_SEC * deid_sim.PRODUCTION_QUEUE_SAFETY_RATIO)
                / deid_sim.PRODUCTION_WORST_CASE_SEC
                - 1.0
            )
        )
        + 1,
    )

    # then
    assert deid_sim.PRODUCTION_MAX_INFLIGHT == expected
    # 최악 소요는 ffmpeg 실행 상한 + 세마포어 대기 상한에서 파생된다(별도 매직넘버 금지)
    assert deid_sim.PRODUCTION_WORST_CASE_SEC == (
        float(deid_sim.FFMPEG_TIMEOUT_SEC) + deid_sim.FFMPEG_ACQUIRE_TIMEOUT_SEC
    )
    # BE 폴링 예산은 두 타임아웃 중 <b>먼저 걸리는 쪽</b>이다(느슨한 쪽을 쓰면 과대평가)
    assert deid_sim.BE_POLL_BUDGET_SEC == min(
        deid_sim.BE_POLL_INTERVAL_SEC * deid_sim.BE_POLL_MAX_ATTEMPTS,
        deid_sim.BE_POLL_TIMEOUT_MIN * 60,
    )
    assert 0.0 < deid_sim.PRODUCTION_QUEUE_SAFETY_RATIO <= 1.0


# ── ★ #4: 요청 항목이 있는데 계획 0건이면 성공이 아니다 ─────────────
def test_files가_전부_정화탈락하면_완료가_아니라_실패로_종결한다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """★ #4 회귀 가드 — ``files:["/"]`` 는 비어있지 않아 400 을 통과하지만 계획은 0건이 된다.

    구 안전망 ``if outputs and not written`` 은 계획 0건이면 통째로 건너뛰어 <b>데이터셋 0개
    프로젝트가 완료로 보고</b>됐다. BE 는 firstDataset=null 이라 완료를 인지하지 못한 채 폴링
    예산(최장 120분)을 소진해 'F' 로 끝난다.
    """
    # given
    from app.config import reload_settings

    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(tmp_path))
    reload_settings()

    # when — basename 정화가 전부 탈락하는 요청(접수는 200)
    res = client.post(
        "/project",
        json={
            "project_name": "planzero", "creator": "w1",
            "export_path": f"{export}/", "input_path": f"{inp}/",
            "files": ["/", "..", "."], "is_img": 0,
        },
    )
    assert res.status_code == 200
    prj_id = res.json()["prj_id"]

    # then — 산출은 명시적 실패(procState=99)로 종결한다
    assert deid_sim.wait_for_production(prj_id, timeout=30.0) == "FAILED"
    _force_elapsed_complete(prj_id)
    assert _poll(client, prj_id)["prjState"] == 5
    reload_settings()


def test_정상_0건_경로는_거짓_실패로_만들지_않는다(tmp_path) -> None:
    """#4 의 반대편 — 요청 항목 자체가 0이면(래퍼/직접 호출) 실패가 아니다.

    (HTTP 경로에서는 빈 ``files`` 를 라우터가 400 으로 차단하므로 여기 도달하지 않는다.)
    """
    # given / when
    outcome = deid_sim.produce_deid_outputs(
        export_path=f"{tmp_path}/export/",
        input_path=f"{tmp_path}/input/",
        outputs=[],
        output_base=str(tmp_path),
        input_base=str(tmp_path),
        requested=0,
    )

    # then
    assert outcome.failed is False
    assert outcome.written == []


def test_정화탈락_항목은_조용히_버려지지_않고_사유가_남는다(caplog) -> None:
    """#4 — 버릴 때 사유를 남긴다(로그 + 반환값 양쪽)."""
    import logging

    # given / when
    with caplog.at_level(logging.WARNING, logger="app.services.deid_sim"):
        plans, dropped = deid_sim.plan_outputs_detailed(["/", "a.mp4"])

    # then
    assert plans == [("a.mp4", "a-mask.mp4")]
    assert dropped == ["/"]
    assert any("정화 실패" in r.getMessage() for r in caplog.records)


# ── 관찰: 파생 상수는 호출 시점에 읽는다(def 시점 고정 금지) ─────────
def test_sweep_나이상한은_호출시점의_파생상수를_읽는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """기본 인자에 상수를 박으면 def 시점에 고정돼, 파생 상수를 재조정해도 sweep 만 옛 값이다."""
    # given — 방금 만든(살아있는) temp 는 기본 상한(720초+)에서는 보호된다
    out_dir = tmp_path / "deid"
    out_dir.mkdir()
    temp = deid_sim._temp_path_for(out_dir / "a-mask.mp4")
    assert temp is not None
    temp.write_bytes(b"\x00\x00\x00\x18ftypmp42PARTIAL")
    assert deid_sim.sweep_temp_dir(out_dir / deid_sim.TEMP_DIR_NAME) == 0

    # when — 런타임에 상한을 0 으로 재조정
    monkeypatch.setattr(deid_sim, "TEMP_ORPHAN_MAX_AGE_SEC", 0.0)

    # then — 재조정이 즉시 반영된다(def 시점 고정이면 여전히 0 건이라 실패)
    assert deid_sim.sweep_temp_dir(out_dir / deid_sim.TEMP_DIR_NAME) == 1
    assert not temp.exists()
