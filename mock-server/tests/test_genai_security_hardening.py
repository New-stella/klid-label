"""생성형 AI(증강) 목 — 보안 리뷰 지적사항(F-1/2/3/6/7/9/10/11) 재현·회귀 테스트.

security-reviewer 가 실측 재현한 결함을 **먼저 실패(RED)하는 테스트**로 고정한 뒤 수정한다.

- F-1  심볼릭링크 TOCTOU 임의 파일 읽기 (CWE-367 + CWE-59)
- F-2  SSRF 잔여 — allowlist 가 호스트만 검사(포트/경로 무제한, 자기 자신 호출 가능) (CWE-918)
- F-3  요청 바디·잡·멱등키·prompt 무제한 (CWE-770/400)
- F-6  input_base 미설정 시 경로 가드 fail-open (CWE-22)
- F-7  파일 크기 상한이 접수 시 stat() 1회뿐 (CWE-400)
- F-9  sanitize_for_log 제어문자 누락 (CWE-117)
- F-10 심층 중첩 JSON 이 400 핸들러를 탈출해 500 (CWE-755)
- F-11 취소된 작업도 결과 파일을 씀

스코프 밖(별도 판단): F-4(멱등키 호출자 스코프)·F-5(CORS/바인딩/TrustedHost)·F-8(_mock EP 보호).
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

JOBS_URL = "/api/genai/jobs"
MOCK_JOBS_URL = "/api/genai/_mock/jobs"
CALLBACK_URL = "http://localhost:8080/api/genai/jobs/x/webhook"

SECRET = b"-----BEGIN OPENSSH PRIVATE KEY-----\nSUPER_SECRET_KEY_MATERIAL\n"


@pytest.fixture(autouse=True)
def _genai_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Iterator[None]:
    """입출력 base 를 tmp_path 로 격리하고 잡 저장소를 초기화한다."""
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


@pytest.fixture(autouse=True)
def _no_real_webhook(monkeypatch: pytest.MonkeyPatch) -> None:
    """실제 outbound webhook 을 차단한다(보안 테스트는 동기 계약만 본다)."""
    from app.services import genai_sim

    async def _noop(url: str, payload: dict) -> bool:  # noqa: ARG001
        return True

    monkeypatch.setattr(genai_sim, "send_webhook", _noop)


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
    """v1.3 §4.1 표준 요청 본문."""
    body: dict = {
        "request_id": "req-sec",
        "request_channel": "AUTHORING",
        "evnt_type": "FLOOD",
        "evnt_subtype": "ROAD_FLOOD",
        # 기본은 입력 파일이 필요 없는 GENERATE × T2I — 입력이 필요한 케이스만 개별 override.
        "operation_type": "GENERATE",
        "generation_mode": "T2I",
        "input_files": [],
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


def _wait_terminal(client: TestClient, job_id: str, timeout: float = 10.0) -> dict:
    """작업이 종결 상태(SUCCEEDED/FAILED/CANCELED)에 도달할 때까지 폴링한다."""
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        res = client.get(f"{JOBS_URL}/{job_id}")
        if res.status_code == 200:
            last = res.json()
            if last["status"] in ("SUCCEEDED", "FAILED", "CANCELED"):
                return last
        time.sleep(0.02)
    pytest.fail(f"job {job_id} 가 종결되지 않음 (last={last})")


def _out_files(tmp_path) -> list[Path]:
    out = tmp_path / "out"
    return [p for p in out.rglob("*") if p.is_file()] if out.exists() else []


# ── F-1 : 심볼릭링크 TOCTOU 임의 파일 읽기 ────────────────────────
def test_F1_접수후_입력파일이_base밖_심볼릭링크로_바뀌면_유출되지_않고_FAILED(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 접수 시점엔 정상 파일, 처리 전 base 밖 비밀 파일로의 심볼릭링크로 교체한다
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "0.2")
    reload_settings()

    secret = tmp_path / "outside" / "id_rsa"
    secret.parent.mkdir(parents=True, exist_ok=True)
    secret.write_bytes(SECRET)

    victim = tmp_path / "in" / "input.png"
    victim.write_bytes(b"BENIGN_IMAGE")

    files = [{"sequence": 1, "file_path": str(victim)}]
    # when — 접수(202) 직후 공격 창에서 교체
    res = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="AUGMENT", generation_mode="I2I", input_files=files
        ),
    )
    assert res.status_code == 202
    job_id = res.json()["job_id"]
    victim.unlink()
    victim.symlink_to(secret)

    body = _wait_terminal(client, job_id)
    # then — 유출 없음 + 실패 종결
    assert body["status"] == "FAILED", body
    for out in _out_files(tmp_path):
        assert SECRET not in out.read_bytes(), f"비밀이 유출됨: {out}"


def test_F1_build_results는_심볼릭링크_입력을_거부한다(tmp_path, monkeypatch) -> None:
    # given — 저장된 입력 경로가 base 밖 파일을 가리키는 심볼릭링크
    from app.config import reload_settings
    from app.services import genai_sim
    from app.state import GenAiJob

    monkeypatch.setenv("MOCK_GENAI_OUTPUT_BASE", str(tmp_path / "out"))
    monkeypatch.setenv("MOCK_GENAI_INPUT_BASE", str(tmp_path / "in"))
    reload_settings()

    secret = tmp_path / "outside" / "id_rsa"
    secret.parent.mkdir(parents=True, exist_ok=True)
    secret.write_bytes(SECRET)
    link = tmp_path / "in" / "linked.png"
    link.symlink_to(secret)

    job = GenAiJob(
        job_id="job-symlink",
        request_id="r",
        generation_mode="I2I",
        input_files=[(1, str(link))],
    )
    # when / then
    with pytest.raises(genai_sim.JobExecutionError):
        genai_sim.build_results(job, str(tmp_path / "out"))
    for out in _out_files(tmp_path):
        assert SECRET not in out.read_bytes()


# ── F-2 : SSRF — 포트/경로/자기참조 ───────────────────────────────
def test_F2_allowlist는_host_port_형태를_구분한다() -> None:
    # given
    from app.services import genai_sim

    hosts = ["backend:8080"]
    # when / then — 포트까지 일치할 때만 허용
    assert genai_sim.is_allowed_url("http://backend:8080/hook", hosts) is True
    assert genai_sim.is_allowed_url("http://backend:6379/hook", hosts) is False
    assert genai_sim.is_allowed_url("http://backend/hook", hosts) is False
    # 포트 미기재 항목은 기존처럼 호스트만 매칭(하위호환)
    assert genai_sim.is_allowed_url("http://backend:6379/hook", ["backend"]) is True


def test_F2_비허용_포트_callback_url은_400(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — allowlist 에 8080 만 허용
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_CALLBACK_ALLOW_HOSTS", "127.0.0.1:8080,localhost:8080")
    reload_settings()
    # when — redis 포트로 유도
    res = client.post(JOBS_URL, json=_body(tmp_path, callback_url="http://127.0.0.1:6379/"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"
    # 허용된 host:port 는 통과
    ok = client.post(
        JOBS_URL, json=_body(tmp_path, callback_url="http://127.0.0.1:8080/hook")
    )
    assert ok.status_code == 202


def test_F2_목_자신을_가리키는_callback_url은_거부된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — allowlist 에 들어 있어도 목 자신(자기 SSRF)은 차단해야 한다
    from app.config import get_settings, reload_settings

    monkeypatch.setenv("MOCK_GENAI_CALLBACK_ALLOW_HOSTS", "localhost:9400,localhost:8080")
    reload_settings()
    self_port = get_settings().port
    evil = f"http://localhost:{self_port}/api/genai/_mock/reset"
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, callback_url=evil))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_F2_경로_접두사_제약이_설정되면_그_밖은_거부(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_CALLBACK_ALLOW_HOSTS", "localhost:8080")
    monkeypatch.setenv("MOCK_GENAI_CALLBACK_PATH_PREFIXES", "/api/genai/")
    reload_settings()
    # when / then
    bad = client.post(
        JOBS_URL, json=_body(tmp_path, callback_url="http://localhost:8080/v1/vlm/callback")
    )
    assert bad.status_code == 400
    good = client.post(
        JOBS_URL, json=_body(tmp_path, callback_url="http://localhost:8080/api/genai/hook")
    )
    assert good.status_code == 202


def test_F2_기존_SSRF_방어는_유지된다() -> None:
    # given
    from app.services import genai_sim

    hosts = ["localhost:8080", "127.0.0.1:8080"]
    # when / then — 호스트 치환/스킴 우회 계열은 계속 차단
    assert genai_sim.is_allowed_url("http://evil.example.com:8080/cb", hosts) is False
    assert genai_sim.is_allowed_url("file:///etc/passwd", hosts) is False
    assert genai_sim.is_allowed_url("http://169.254.169.254:8080/", hosts) is False
    assert genai_sim.is_allowed_url("http://localhost:8080/cb", []) is False  # fail-closed


# ── F-3 : 요청 바디·잡·prompt 상한 ───────────────────────────────
def test_F3_상한_초과_바디는_413으로_거부된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 바디 상한 2KB
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_BODY_BYTES", "2048")
    reload_settings()
    body = _body(tmp_path, prompt={"pad": "A" * 8192})
    # when
    res = client.post(JOBS_URL, json=body)
    # then
    assert res.status_code == 413
    assert res.json()["code"] == "GA-MEDIA-001"


def test_F3_상한_이하_바디는_정상_접수(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_BODY_BYTES", "8192")
    reload_settings()
    # when / then
    assert client.post(JOBS_URL, json=_body(tmp_path)).status_code == 202


def test_F3_잡_수_상한을_넘으면_오래된_작업부터_만료된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 잡 상한 3건
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_JOBS", "3")
    reload_settings()
    # when — 6건 접수
    ids = [client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"] for _ in range(6)]
    # then — 저장소는 상한을 넘지 않고 최신 3건만 남는다
    jobs = client.get(MOCK_JOBS_URL).json()["jobs"]
    assert len(jobs) == 3
    assert {j["job_id"] for j in jobs} == set(ids[-3:])


def test_F3_과대_prompt는_400_INVALID_PARAMETER(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — prompt 바이트 상한 200(계약 길이 1000자와 별개인 목 자원 보호 축)
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_BODY_BYTES", "1048576")
    monkeypatch.setenv("MOCK_GENAI_MAX_PROMPT_BYTES", "200")
    reload_settings()
    # when — 1000자 이내라 pydantic 은 통과하지만 UTF-8 로는 상한 초과
    res = client.post(JOBS_URL, json=_body(tmp_path, prompt="가" * 300))
    # then — v1.3 §3.3 "prompt 제한 초과" 는 INVALID_PARAMETER
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


# ── F-6 : input_base 미설정 fail-open ────────────────────────────
def test_F6_input_base_미설정이면_임의_절대경로는_거부된다(monkeypatch) -> None:
    # given
    from app.services import genai_sim

    # when / then — fail-closed
    assert genai_sim.resolve_input_path("/etc/passwd", "") is None


def test_F6_base_미설정_상태의_접수는_400(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 입력·출력 base 모두 미설정
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_INPUT_BASE", "")
    monkeypatch.setenv("MOCK_GENAI_OUTPUT_BASE", "")
    reload_settings()
    files = [{"sequence": 1, "file_path": "/etc/passwd"}]
    # when
    res = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="AUGMENT", generation_mode="I2I", input_files=files
        ),
    )
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


# ── F-7 : 처리 시점 크기 재검증 ──────────────────────────────────
def test_F7_접수후_입력파일이_커지면_복사되지_않고_FAILED(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 상한 64바이트, 접수 시엔 12바이트
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_INPUT_BYTES", "64")
    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "0.2")
    reload_settings()
    src = tmp_path / "in" / "grow.png"
    src.write_bytes(b"SMALL_INPUT!")
    files = [{"sequence": 1, "file_path": str(src)}]
    # when — 접수 직후 파일을 상한 위로 키운다
    job_id = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="AUGMENT", generation_mode="I2I", input_files=files
        ),
    ).json()["job_id"]
    src.write_bytes(b"X" * 10_000)

    body = _wait_terminal(client, job_id)
    # then
    assert body["status"] == "FAILED", body
    for out in _out_files(tmp_path):
        assert out.stat().st_size <= 64


# ── F-9 : 제어문자 로그 인젝션 ───────────────────────────────────
def test_F9_sanitize_for_log는_제어문자를_모두_제거한다() -> None:
    # given
    from app.state import sanitize_for_log

    raw = "a\x1b[31mRED\x1b[0m b\x0bV c\x0cF d L e P f\x00N g\r\n\th"
    # when
    out = sanitize_for_log(raw)
    # then — ANSI 이스케이프·수직탭·폼피드·줄구분자·NUL 모두 제거
    for bad in ("\x1b", "\x0b", "\x0c", " ", " ", "\x00", "\r", "\n", "\t"):
        assert bad not in out
    assert "RED" in out  # 본문은 남는다


def test_F9_길이_절단은_유지된다() -> None:
    # given
    from app.state import sanitize_for_log

    # when
    out = sanitize_for_log("A" * 500)
    # then
    assert out.startswith("A" * 200)
    assert "truncated" in out


# ── F-10 : 심층 중첩 JSON ────────────────────────────────────────
def test_F10_심층_중첩_JSON은_400이다(client: TestClient) -> None:
    # given — 재귀 한계를 넘기는 중첩
    payload = b"[" * 5000 + b"]" * 5000
    # when
    res = client.post(
        JOBS_URL, content=payload, headers={"Content-Type": "application/json"}
    )
    # then — 500 이 아니라 400
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_F10_cancel도_심층_중첩_JSON에_500을_내지_않는다(
    client: TestClient, tmp_path
) -> None:
    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    payload = b"[" * 5000 + b"]" * 5000
    # when
    res = client.post(
        f"{JOBS_URL}/{job_id}/cancel",
        content=payload,
        headers={"Content-Type": "application/json"},
    )
    # then
    assert res.status_code == 400


# ── F-11 : 취소된 작업의 고아 산출물 ─────────────────────────────
def test_F11_취소된_작업의_산출물은_남지_않는다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 결과 파일 생성 직후 취소가 확정되는 상황을 결정적으로 재현
    from app.services import genai_sim

    real_build = genai_sim.build_results

    def _build_then_cancel(job, output_base):  # noqa: ANN001
        results = real_build(job, output_base)
        genai_sim.get_job_store().transition(job.job_id, "CANCELED")
        return results

    monkeypatch.setattr(genai_sim, "build_results", _build_then_cancel)
    src = tmp_path / "in" / "a.mp4"
    src.write_bytes(b"ORIGINAL")
    files = [{"sequence": 1, "file_path": str(src)}]
    # when
    job_id = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="AUGMENT", generation_mode="I2I", input_files=files
        ),
    ).json()["job_id"]
    body = _wait_terminal(client, job_id)
    # then — 상태는 CANCELED 이고 디스크에 고아 산출물이 없다
    assert body["status"] == "CANCELED"
    assert _out_files(tmp_path) == []
