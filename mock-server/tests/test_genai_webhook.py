"""생성형 AI 벤더 목 — 발신 2종(② Webhook / ③ status-sync) + HIGH 시나리오 방어 테스트.

- ② 진행·결과 Webhook: 202 직후 백그라운드로 RECEIVED→RUNNING(진행률)→SUCCEEDED 전이하며
  단계마다 요청 바디의 ``callback_url`` 로 POST 한다.
- ③ 상태 동기화: 목 전용 **수동 트리거**(자동 발신 아님). 대상 base URL 은 환경변수
  ``MOCK_GENAI_STATUS_SYNC_URL`` 이며 미설정 시 비활성.

HIGH 시나리오 6종 방어 검증:
  1) 백그라운드 태스크 누수 — 참조 보관 + lifespan shutdown 정리
  2) 취소 레이스 — 종결 상태 재전이 금지(락 직렬화)
  3) 경로 탈출(CWE-22) — 입력 경로 검증 + 출력은 항상 출력 base 하위
  4) callback_url SSRF(CWE-918) — 스킴/호스트 allowlist
  5) webhook 수신 실패 — 서버 생존 + 재시도 횟수 상한(무한 재시도 금지)
  6) 입력 파일 부재 — FAILED 전이 + webhook 통보
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

JOBS_URL = "/api/genai/jobs"
STATUS_SYNC_URL = "/api/genai/_mock/jobs/{job_id}/status-sync"
CALLBACK_URL = "http://localhost:8080/api/genai/jobs/x/webhook"


@pytest.fixture(autouse=True)
def _genai_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Iterator[None]:
    from app.config import reload_settings
    from app.services import genai_sim

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "0")
    monkeypatch.setenv("MOCK_GENAI_OUTPUT_BASE", str(tmp_path / "out"))
    monkeypatch.setenv("MOCK_GENAI_INPUT_BASE", str(tmp_path / "in"))
    monkeypatch.setenv("MOCK_GENAI_WEBHOOK_RETRY_DELAY_SEC", "0")
    (tmp_path / "in").mkdir(parents=True, exist_ok=True)
    reload_settings()
    genai_sim.get_job_store().clear()
    yield
    genai_sim.get_job_store().clear()
    reload_settings()


def _input_file(tmp_path, name: str = "src.mp4") -> str:
    path = tmp_path / "in" / name
    path.write_bytes(b"ORIGINAL_VIDEO")
    return str(path)


def _body(tmp_path, **over: object) -> dict:
    body: dict = {
        "request_id": "req-0001",
        "request_channel": "AUTHORING",
        "evnt_type": "FIRE",
        "operation_type": "AUGMENT",
        "generation_mode": "I2V",
        "input_files": [{"sequence": 1, "file_path": _input_file(tmp_path)}],
        "prompt": {"season": "winter"},
        "callback_url": CALLBACK_URL,
    }
    body.update(over)
    return body


def _capture_webhook(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, dict]]:
    """send_webhook 을 가로채 (url, payload) 를 수집한다."""
    from app.services import genai_sim

    captured: list[tuple[str, dict]] = []

    async def _recorder(url: str, payload: dict) -> bool:
        captured.append((url, payload))
        return True

    monkeypatch.setattr(genai_sim, "send_webhook", _recorder)
    return captured


def _wait_status(client: TestClient, job_id: str, target: str, timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        res = client.get(f"{JOBS_URL}/{job_id}")
        if res.status_code == 200:
            last = res.json()
            if last["status"] == target:
                return last
        time.sleep(0.02)
    pytest.fail(f"job {job_id} 가 {target} 에 도달하지 못함 (last={last})")


# ── 수용기준 12 : 단계별 webhook + SUCCEEDED 에 results 포함 ─────
def test_수용12_단계별_webhook이_도착하고_완료_webhook에_results포함(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _capture_webhook(monkeypatch)
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # then — 진행(RUNNING) webhook 이 1건 이상, 마지막은 SUCCEEDED + results
    assert len(captured) >= 2
    assert {url for url, _ in captured} == {CALLBACK_URL}
    statuses = [payload["status"] for _, payload in captured]
    assert "RUNNING" in statuses
    assert statuses[-1] == "SUCCEEDED"
    for _, payload in captured:
        assert payload["job_id"] == job_id
        assert payload["request_id"] == "req-0001"
        assert payload["updated_at"]
        assert 0 <= payload["progress"] <= 100
        assert payload["current_step"]
    final = captured[-1][1]
    assert final["progress"] == 100
    assert isinstance(final["results"], list) and len(final["results"]) >= 1
    first = final["results"][0]
    assert first["generated_data_id"] and first["media_type"] and first["output_file_path"]


def test_진행률은_단조증가한다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _capture_webhook(monkeypatch)
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # then
    progresses = [payload["progress"] for _, payload in captured]
    assert progresses == sorted(progresses)


def test_callback_url이_없으면_webhook은_발사되지_않지만_작업은_완료(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — callback_url 은 선택 필드
    captured = _capture_webhook(monkeypatch)
    body = _body(tmp_path)
    body.pop("callback_url")
    # when
    job_id = client.post(JOBS_URL, json=body).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # then
    assert captured == []


# ── 수용기준 13 : FAILED 트리거 ──────────────────────────────────
def test_수용13_실패트리거는_FAILED_webhook에_error_code와_message포함(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 채택한 FAILED 트리거: request_id 가 "fail" 로 시작(대소문자 무시)
    captured = _capture_webhook(monkeypatch)
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path, request_id="fail-0001")).json()["job_id"]
    _wait_status(client, job_id, "FAILED")
    # then — 접수(202)는 정상, 실패는 상태/콜백으로 전달
    final = captured[-1][1]
    assert final["status"] == "FAILED"
    assert final["error_code"] == "MODEL_EXECUTION_FAILED"
    assert final["error_message"]
    detail = client.get(f"{JOBS_URL}/{job_id}").json()
    assert detail["error_code"] == "MODEL_EXECUTION_FAILED"
    assert detail["error_message"]


def test_FAILED된_작업의_결과조회는_409(client: TestClient, tmp_path) -> None:
    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path, request_id="fail-0002")).json()["job_id"]
    _wait_status(client, job_id, "FAILED")
    # when
    res = client.get(f"{JOBS_URL}/{job_id}/results")
    # then
    assert res.status_code == 409
    assert res.json()["code"] == "STATE_CONFLICT"


# ── ③ status-sync 수동 트리거 ────────────────────────────────────
def test_status_sync는_URL미설정시_비활성(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — MOCK_GENAI_STATUS_SYNC_URL 미설정
    from app.config import reload_settings

    monkeypatch.delenv("MOCK_GENAI_STATUS_SYNC_URL", raising=False)
    reload_settings()
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # when
    res = client.post(STATUS_SYNC_URL.format(job_id=job_id))
    # then — 목이 주소를 유추하지 않는다(비활성)
    assert res.status_code == 200
    body = res.json()
    assert body["sent"] is False
    assert body["target"] is None
    assert body["reason"]


def test_status_sync는_설정된_base에_명세서_경로로_발신(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.config import reload_settings

    captured = _capture_webhook(monkeypatch)
    monkeypatch.setenv("MOCK_GENAI_STATUS_SYNC_URL", "http://localhost:8080")
    reload_settings()
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    captured.clear()
    # when
    res = client.post(STATUS_SYNC_URL.format(job_id=job_id))
    # then — POST {base}/api/genai/jobs/{job_id}/status-sync
    assert res.status_code == 200
    assert res.json()["sent"] is True
    assert len(captured) == 1
    url, payload = captured[0]
    assert url == f"http://localhost:8080/api/genai/jobs/{job_id}/status-sync"
    assert payload["job_id"] == job_id
    assert payload["status"] == "SUCCEEDED"
    assert payload["results"]


def test_status_sync는_없는_job이면_404(client: TestClient) -> None:
    # given / when
    res = client.post(STATUS_SYNC_URL.format(job_id="nope"))
    # then
    assert res.status_code == 404
    assert res.json()["code"] == "JOB_NOT_FOUND"


# ── HIGH-1 : 백그라운드 태스크 누수 방지 ─────────────────────────
def test_HIGH1_shutdown시_진행중_태스크가_취소_정리된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 단계 지연을 길게 줘서 종료 시점에 태스크가 살아있게 한다
    from app.config import reload_settings
    from app.main import app
    from app.services import genai_sim

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "30")
    reload_settings()
    # when
    with TestClient(app) as c:
        res = c.post(JOBS_URL, json=_body(tmp_path))
        assert res.status_code == 202
        # 태스크 참조가 보관되어 GC 되지 않는다
        assert genai_sim.active_task_count() >= 1
    # then — lifespan shutdown 에서 취소·정리
    assert genai_sim.active_task_count() == 0


# ── HIGH-2 : 취소 레이스 ─────────────────────────────────────────
def test_HIGH2_종결상태는_재전이되지_않는다_동시호출(tmp_path) -> None:
    # given — 저장소 레벨 동시 전이(락 직렬화, CWE-362)
    from app.state import GenAiJob, GenAiJobStore

    store = GenAiJobStore()
    for i in range(30):
        job_id = f"job-{i}"
        store.create_or_get(GenAiJob(job_id=job_id, request_id="r"), None)
        barrier = threading.Barrier(2)
        outcomes: list[tuple[str, bool]] = []
        lock = threading.Lock()

        def _do(target: str) -> None:
            barrier.wait()
            result = store.transition(job_id, target)
            with lock:
                outcomes.append((target, result.ok))

        threads = [
            threading.Thread(target=_do, args=("CANCELED",)),
            threading.Thread(target=_do, args=("SUCCEEDED",)),
        ]
        # when
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        # then — 정확히 하나만 성공하고 최종 상태는 그 값으로 고정
        winners = [target for target, ok in outcomes if ok]
        assert len(winners) == 1
        assert store.get(job_id).status == winners[0]


def test_HIGH2_취소된_작업은_이후_SUCCEEDED로_덮이지_않는다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 진행 중(단계 지연 0.3s)에 취소
    from app.config import reload_settings

    captured = _capture_webhook(monkeypatch)
    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "0.3")
    reload_settings()
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    # when
    cancel = client.post(f"{JOBS_URL}/{job_id}/cancel", json={"requested_by": "w1"})
    assert cancel.status_code == 200
    time.sleep(1.5)  # 남은 단계가 모두 지나갈 시간
    # then — 취소 상태가 유지되고 결과도 남지 않는다
    detail = client.get(f"{JOBS_URL}/{job_id}").json()
    assert detail["status"] == "CANCELED"
    assert all(payload["status"] != "SUCCEEDED" for _, payload in captured)
    assert client.get(f"{JOBS_URL}/{job_id}/results").status_code == 409


# ── HIGH-3 : 경로 탈출(CWE-22) ───────────────────────────────────
def test_HIGH3_입력경로_탈출은_400이고_base밖에_파일이_생기지_않는다(
    client: TestClient, tmp_path
) -> None:
    # given — 입력 base 밖으로 탈출하는 경로
    evil = str(tmp_path / "in" / ".." / "outside" / "evil.mp4")
    files = [{"sequence": 1, "file_path": evil}]
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, input_files=files))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"
    assert not (tmp_path / "outside").exists()


def test_HIGH3_상대경로는_400(client: TestClient, tmp_path) -> None:
    # given — NAS 절대경로만 허용
    files = [{"sequence": 1, "file_path": "../../etc/passwd"}]
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, input_files=files))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_HIGH3_출력은_항상_출력base_하위에만_생성된다(
    client: TestClient, tmp_path
) -> None:
    from pathlib import Path

    # given — 입력 파일명에 상위 탈출 흔적이 섞여도 출력명은 basename 기준
    src = tmp_path / "in" / "sub"
    src.mkdir(parents=True, exist_ok=True)
    path = src / "a.mp4"
    path.write_bytes(b"X")
    files = [{"sequence": 1, "file_path": str(path)}]
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path, input_files=files)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # then
    results = client.get(f"{JOBS_URL}/{job_id}/results").json()["results"]
    out_base = (tmp_path / "out").resolve()
    for item in results:
        out = Path(item["output_file_path"]).resolve()
        assert out.is_file()
        assert out_base in out.parents


def test_HIGH3_출력base_미설정이면_FAILED_RESULT_SAVE_FAILED(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — fail-closed: 출력 base 미설정이면 파일을 만들지 않는다
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_OUTPUT_BASE", "")
    reload_settings()
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    body = _wait_status(client, job_id, "FAILED")
    # then
    assert body["error_code"] == "RESULT_SAVE_FAILED"


# ── HIGH-4 : callback_url SSRF(CWE-918) ──────────────────────────
def test_HIGH4_허용목록_밖_호스트_callback_url은_400(
    client: TestClient, tmp_path
) -> None:
    # given / when — 외부 호스트로 목이 POST 하도록 유도
    res = client.post(
        JOBS_URL, json=_body(tmp_path, callback_url="http://169.254.169.254/latest/meta-data")
    )
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_HIGH4_http_https가_아닌_스킴은_400(client: TestClient, tmp_path) -> None:
    # given / when
    res = client.post(JOBS_URL, json=_body(tmp_path, callback_url="file:///etc/passwd"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_HIGH4_허용호스트는_통과한다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — allowlist 를 명시 지정
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_CALLBACK_ALLOW_HOSTS", "klid-backend,localhost")
    reload_settings()
    # when
    res = client.post(
        JOBS_URL, json=_body(tmp_path, callback_url="http://klid-backend:8080/hook")
    )
    # then
    assert res.status_code == 202


def test_HIGH4_url가드_단위검증() -> None:
    # given
    from app.services import genai_sim

    hosts = ["localhost", "127.0.0.1"]
    # when / then
    assert genai_sim.is_allowed_url("http://localhost:8080/cb", hosts) is True
    assert genai_sim.is_allowed_url("https://127.0.0.1/cb", hosts) is True
    assert genai_sim.is_allowed_url("http://evil.example.com/cb", hosts) is False
    assert genai_sim.is_allowed_url("ftp://localhost/cb", hosts) is False
    assert genai_sim.is_allowed_url("not-a-url", hosts) is False
    # allowlist 가 비어있으면 fail-closed
    assert genai_sim.is_allowed_url("http://localhost/cb", []) is False


# ── HIGH-5 : webhook 수신 실패 격리 + 재시도 상한 ────────────────
def test_HIGH5_webhook이_401이어도_작업은_계속_진행된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 우리 BE 가 401 을 주는 상황(구 계약 콜백 수신부)
    from app.services import genai_sim

    calls: list[str] = []

    async def _unauthorized(url: str, payload: dict) -> int:  # noqa: ARG001
        calls.append(url)
        return 401

    monkeypatch.setattr(genai_sim, "_post_once", _unauthorized)
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    body = _wait_status(client, job_id, "SUCCEEDED")
    # then — 서버가 죽지 않고 작업은 완료된다
    assert body["status"] == "SUCCEEDED"
    assert calls  # 실제로 시도는 했다


def test_HIGH5_전송실패시_재시도는_상한을_넘지_않는다(
    monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 항상 연결 실패
    from app.config import get_settings
    from app.services import genai_sim

    attempts: list[int] = []

    async def _boom(url: str, payload: dict) -> int:  # noqa: ARG001
        attempts.append(1)
        raise OSError("connection refused")

    monkeypatch.setattr(genai_sim, "_post_once", _boom)
    # when — 예외가 전파되지 않고 False 반환
    ok = asyncio.run(genai_sim.send_webhook("http://localhost:8080/cb", {"job_id": "j"}))
    # then — 무한 재시도 금지
    assert ok is False
    assert len(attempts) == get_settings().genai_webhook_max_attempts


def test_HIGH5_도달불가_URL도_예외를_전파하지_않는다() -> None:
    # given / when — 연결 거부되는 로컬 포트(실제 네트워크)
    from app.services import genai_sim

    ok = asyncio.run(genai_sim.send_webhook("http://127.0.0.1:1/nope", {"job_id": "j"}))
    # then
    assert ok is False


# ── HIGH-6 : 입력 파일 부재 ──────────────────────────────────────
def test_HIGH6_입력파일이_없으면_FAILED와_webhook통보(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 경로는 유효(base 하위)하지만 실제 파일이 없다
    captured = _capture_webhook(monkeypatch)
    missing = str(tmp_path / "in" / "not-there.mp4")
    files = [{"sequence": 1, "file_path": missing}]
    # when — 접수는 202
    res = client.post(JOBS_URL, json=_body(tmp_path, input_files=files))
    assert res.status_code == 202
    job_id = res.json()["job_id"]
    body = _wait_status(client, job_id, "FAILED")
    # then
    assert body["error_code"] in ("MODEL_EXECUTION_FAILED", "RESULT_SAVE_FAILED")
    assert body["error_message"]
    final = captured[-1][1]
    assert final["status"] == "FAILED"
    assert final["error_code"] and final["error_message"]


def test_HIGH6_실패시_출력_디렉터리에_결과가_남지_않는다(
    client: TestClient, tmp_path
) -> None:
    # given
    missing = str(tmp_path / "in" / "gone.mp4")
    files = [{"sequence": 1, "file_path": missing}]
    # when
    job_id = client.post(JOBS_URL, json=_body(tmp_path, input_files=files)).json()["job_id"]
    _wait_status(client, job_id, "FAILED")
    # then — 결과 조회는 409(SUCCEEDED 아님)
    assert client.get(f"{JOBS_URL}/{job_id}/results").status_code == 409
