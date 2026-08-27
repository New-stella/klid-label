"""생성형 AI 벤더 목 — 「생성형 AI API 연동명세서 v1.3」 계약 테스트 (동기 4종).

대상: 목 서버가 **제공**하는 4종
- ① POST /api/genai/jobs            (작업 요청, 202 RECEIVED)
- ④ GET  /api/genai/jobs/{job_id}   (상태 조회)
- ⑤ GET  /api/genai/jobs/{job_id}/results (결과 조회, SUCCEEDED 에서만)
- ⑥ POST /api/genai/jobs/{job_id}/cancel  (취소, RECEIVED/RUNNING 에서만)

상태머신: RECEIVED → RUNNING → SUCCEEDED | FAILED | CANCELED.
상태 규칙 위반은 409 STATE_CONFLICT.

인증은 스코프 제외 — 401/403(UNAUTHENTICATED/FORBIDDEN)은 의도적으로 구현하지 않는다.

v1.3 요청 본문 계약(§4.1): 최상위 ``mtdt``(필수 객체) + 최상위 ``prompt``(선택 문자열 1000자).
``Idempotency-Key`` 는 ① 작업 요청에서 필수이며, 구 형태(최상위 ``condition`` · 객체형
``prompt``)와 미지원 값(I2V·V2V·TRANSFORM)은 400 으로 거부된다.
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
MOCK_RESET_URL = "/api/genai/_mock/reset"

CALLBACK_URL = "http://localhost:8080/api/genai/jobs/x/webhook"


@pytest.fixture(autouse=True)
def _genai_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Iterator[None]:
    """지연 0초 + tmp_path 를 입출력 base 로 지정하고 잡 저장소를 초기화한다."""
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
    """이 파일은 동기 계약만 검증하므로 실제 outbound webhook 을 차단한다."""
    from app.services import genai_sim

    async def _noop(url: str, payload: dict) -> bool:  # noqa: ARG001
        return True

    monkeypatch.setattr(genai_sim, "send_webhook", _noop)


@pytest.fixture(autouse=True)
def _auto_idempotency_key(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    """① 작업 요청의 ``Idempotency-Key``(v1.3 §3.1 필수)를 호출마다 자동 부여한다.

    실호출자는 항상 키를 실으므로 매 테스트가 헤더를 반복해 적을 이유가 없다. 헤더를
    **명시한** 호출(멱등 재요청 검증 · 누락 검증)은 그대로 두므로 계약 자체는 가려지지 않는다.
    """
    original_post = client.post

    def _post(url: str, *args: object, **kwargs: object):  # noqa: ANN202
        if url == JOBS_URL and "headers" not in kwargs:
            kwargs["headers"] = {"Idempotency-Key": uuid.uuid4().hex}
        return original_post(url, *args, **kwargs)

    monkeypatch.setattr(client, "post", _post)


def _input_file(tmp_path, name: str = "src.mp4", content: bytes = b"ORIGINAL") -> str:
    """입력 base 하위에 실제 원본 파일을 만들고 절대경로를 반환한다."""
    path = tmp_path / "in" / name
    path.write_bytes(content)
    return str(path)


def _body(tmp_path, **over: object) -> dict:
    """v1.3 §4.1 표준 요청 본문(AUGMENT × I2I — 저작도구 실사용 조합)."""
    body: dict = {
        "request_id": "req-0001",
        "request_channel": "AUTHORING",
        "request_user_id": "worker1",
        "evnt_type": "FLOOD",
        "evnt_subtype": "ROAD_FLOOD",
        "operation_type": "AUGMENT",
        "generation_mode": "I2I",
        "input_files": [
            {"sequence": 1, "file_path": _input_file(tmp_path), "checksum": "abc"}
        ],
        "mtdt": {
            "time": "NIGHT",
            "season": "WINTER",
            "weather": "RAIN",
            "terrain": "ROAD",
            "severity": "HIGH",
        },
        "prompt": "원본 카메라 시점과 도로 구조를 유지하고 야간 도로 침수 장면으로 변경해줘.",
        "callback_url": CALLBACK_URL,
    }
    body.update(over)
    return body


def _wait_status(client: TestClient, job_id: str, target: str, timeout: float = 5.0) -> dict:
    """job 이 target 상태가 될 때까지 폴링한다(백그라운드 진행 대기)."""
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


# ── 수용기준 1 : 작업 요청 202 ───────────────────────────────────
def test_수용1_유효요청은_202와_RECEIVED를_반환(client: TestClient, tmp_path) -> None:
    # given / when
    res = client.post(JOBS_URL, json=_body(tmp_path))
    # then
    assert res.status_code == 202
    body = res.json()
    assert body["request_id"] == "req-0001"
    assert body["job_id"]
    assert body["status"] == "RECEIVED"
    assert body["received_at"]


# ── 수용기준 2 : I2I 인데 input_files 없음 → 400 ─────────────────
def test_수용2_I2I인데_input_files없으면_400_REQUIRED_FIELD_MISSING(
    client: TestClient, tmp_path
) -> None:
    # given / when
    res = client.post(JOBS_URL, json=_body(tmp_path, generation_mode="I2I", input_files=[]))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "REQUIRED_FIELD_MISSING"


def test_v13_미지원_생성모드_I2V는_400(client: TestClient, tmp_path) -> None:
    # given / when — v1.2 까지 있던 I2V 는 v1.3 V0 지원 조합에서 빠졌다
    res = client.post(JOBS_URL, json=_body(tmp_path, generation_mode="I2V"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_T2I는_input_files없어도_202(client: TestClient, tmp_path) -> None:
    # given / when — 텍스트→이미지는 입력 파일이 필요 없다(GENERATE × T2I)
    res = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="GENERATE", generation_mode="T2I", input_files=[]
        ),
    )
    # then
    assert res.status_code == 202


# ── 수용기준 3 : sequence 중복 → 400 ─────────────────────────────
def test_수용3_input_files_sequence_중복이면_400(client: TestClient, tmp_path) -> None:
    # given — sequence 가 1 로 중복
    path = _input_file(tmp_path)
    files = [
        {"sequence": 1, "file_path": path},
        {"sequence": 1, "file_path": path},
    ]
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, input_files=files))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


# ── 수용기준 4 : 완료 후 상태조회 ────────────────────────────────
def test_수용4_지연0에서_완료되면_SUCCEEDED와_completed_at(
    client: TestClient, tmp_path
) -> None:
    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    # when
    body = _wait_status(client, job_id, "SUCCEEDED")
    # then
    assert body["status"] == "SUCCEEDED"
    assert body["completed_at"]
    assert body["started_at"]
    assert body["progress"] == 100
    assert body["updated_at"]


# ── 수용기준 5·6 : 결과 조회 + 실제 파일 존재 ────────────────────
def test_수용5_결과조회는_results배열과_필수필드를_반환(
    client: TestClient, tmp_path
) -> None:
    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # when
    res = client.get(f"{JOBS_URL}/{job_id}/results")
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "SUCCEEDED"
    assert body["request_id"] == "req-0001"
    results = body["results"]
    assert isinstance(results, list) and len(results) >= 1
    for item in results:
        assert item["generated_data_id"]
        assert item["media_type"] in ("IMAGE", "VIDEO")
        assert item["output_file_path"]


def test_수용6_output_file_path가_가리키는_파일이_실제로_존재(
    client: TestClient, tmp_path
) -> None:
    from pathlib import Path

    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # when
    results = client.get(f"{JOBS_URL}/{job_id}/results").json()["results"]
    # then — 경로 문자열이 아니라 실제 파일이어야 한다
    for item in results:
        out = Path(item["output_file_path"])
        assert out.is_file(), item["output_file_path"]
        assert out.stat().st_size > 0
        # 출력은 반드시 출력 base 하위
        assert str(out).startswith(str(tmp_path / "out"))


def test_T2V는_media_type이_VIDEO_T2I는_IMAGE(client: TestClient, tmp_path) -> None:
    # given — T2V (v1.3 의 유일한 영상 생성 모드, 입력 없음)
    v_id = client.post(
        JOBS_URL,
        json=_body(
            tmp_path, operation_type="GENERATE", generation_mode="T2V", input_files=[]
        ),
    ).json()["job_id"]
    _wait_status(client, v_id, "SUCCEEDED")
    # given — T2I (입력 없음)
    i_id = client.post(
        JOBS_URL,
        json=_body(
            tmp_path,
            request_id="req-0002",
            operation_type="GENERATE",
            generation_mode="T2I",
            input_files=[],
        ),
    ).json()["job_id"]
    _wait_status(client, i_id, "SUCCEEDED")
    # then
    v_results = client.get(f"{JOBS_URL}/{v_id}/results").json()["results"]
    i_results = client.get(f"{JOBS_URL}/{i_id}/results").json()["results"]
    assert {r["media_type"] for r in v_results} == {"VIDEO"}
    assert {r["media_type"] for r in i_results} == {"IMAGE"}


# ── 수용기준 7 : SUCCEEDED 아닌 상태의 결과조회 → 409 ────────────
def test_수용7_진행중_결과조회는_409_STATE_CONFLICT(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 단계 지연을 크게 줘서 진행 중 상태를 유지
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "5")
    reload_settings()
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    # when — 아직 RECEIVED/RUNNING
    res = client.get(f"{JOBS_URL}/{job_id}/results")
    # then
    assert res.status_code == 409
    assert res.json()["code"] == "STATE_CONFLICT"


# ── 수용기준 8 : 취소 ────────────────────────────────────────────
def test_수용8_RECEIVED에서_취소되고_재취소는_409(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 진행이 끝나기 전에 취소할 수 있도록 지연을 크게
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_STEP_DELAY_SEC", "5")
    reload_settings()
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    # when
    res = client.post(
        f"{JOBS_URL}/{job_id}/cancel", json={"reason": "사용자 취소", "requested_by": "w1"}
    )
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "CANCELED"
    assert body["job_id"] == job_id
    assert body["canceled_at"]
    # when — 종결 상태에서 재취소
    again = client.post(f"{JOBS_URL}/{job_id}/cancel", json={"requested_by": "w1"})
    # then
    assert again.status_code == 409
    assert again.json()["code"] == "STATE_CONFLICT"


def test_SUCCEEDED된_작업_취소는_409(client: TestClient, tmp_path) -> None:
    # given — 지연 0 이라 즉시 완료
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    _wait_status(client, job_id, "SUCCEEDED")
    # when
    res = client.post(f"{JOBS_URL}/{job_id}/cancel", json={"requested_by": "w1"})
    # then
    assert res.status_code == 409
    assert res.json()["code"] == "STATE_CONFLICT"


# ── 수용기준 9 : cancel requested_by 누락 → 400 ──────────────────
def test_수용9_취소요청에_requested_by없으면_400(client: TestClient, tmp_path) -> None:
    # given
    job_id = client.post(JOBS_URL, json=_body(tmp_path)).json()["job_id"]
    # when
    res = client.post(f"{JOBS_URL}/{job_id}/cancel", json={"reason": "no requester"})
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "REQUIRED_FIELD_MISSING"


# ── 수용기준 10 : 없는 job → 404 ─────────────────────────────────
def test_수용10_없는_job_id_조회는_404_JOB_NOT_FOUND(client: TestClient) -> None:
    # given / when
    res = client.get(f"{JOBS_URL}/does-not-exist")
    # then
    assert res.status_code == 404
    assert res.json()["code"] == "JOB_NOT_FOUND"


def test_없는_job의_결과조회와_취소도_404(client: TestClient) -> None:
    # given / when
    results = client.get(f"{JOBS_URL}/nope/results")
    cancel = client.post(f"{JOBS_URL}/nope/cancel", json={"requested_by": "w1"})
    # then
    assert results.status_code == 404 and results.json()["code"] == "JOB_NOT_FOUND"
    assert cancel.status_code == 404 and cancel.json()["code"] == "JOB_NOT_FOUND"


# ── 수용기준 11 : Idempotency-Key ────────────────────────────────
def test_수용11_동일_Idempotency_Key는_같은_job_id를_반환(
    client: TestClient, tmp_path
) -> None:
    # given
    headers = {"Idempotency-Key": "idem-0001"}
    body = _body(tmp_path)
    # when
    first = client.post(JOBS_URL, json=body, headers=headers)
    second = client.post(JOBS_URL, json=body, headers=headers)
    # then — 같은 job_id, job 은 1건만 생성
    assert first.status_code == 202 and second.status_code == 202
    assert first.json()["job_id"] == second.json()["job_id"]
    jobs = client.get(MOCK_JOBS_URL).json()["jobs"]
    assert len([j for j in jobs if j["job_id"] == first.json()["job_id"]]) == 1
    assert len(jobs) == 1


def test_다른_Idempotency_Key는_매번_새_job(client: TestClient, tmp_path) -> None:
    # given / when — 픽스처가 호출마다 새 키를 부여한다
    first = client.post(JOBS_URL, json=_body(tmp_path))
    second = client.post(JOBS_URL, json=_body(tmp_path))
    # then
    assert first.json()["job_id"] != second.json()["job_id"]


def test_Idempotency_Key가_없으면_400_REQUIRED_FIELD_MISSING(
    client: TestClient, tmp_path
) -> None:
    # given / when — v1.3 §3.1 에서 ① 작업 요청 한정 필수가 됐다
    res = client.post(JOBS_URL, json=_body(tmp_path), headers={})
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "REQUIRED_FIELD_MISSING"


def test_동시_동일_Idempotency_Key_요청도_job은_1건(client: TestClient, tmp_path) -> None:
    # given — 저장소 레벨 check-then-act 직렬화(CWE-362) 검증
    from app.services import genai_sim
    from app.state import GenAiJob

    store = genai_sim.get_job_store()
    store.clear()
    barrier = threading.Barrier(2)
    created_flags: list[bool] = []
    job_ids: list[str] = []
    lock = threading.Lock()

    def _register(seq: int) -> None:
        job = GenAiJob(job_id=f"job-{seq}", request_id="req-same")
        barrier.wait()
        stored, created = store.create_or_get(job, "same-key")
        with lock:
            created_flags.append(created)
            job_ids.append(stored.job_id)

    threads = [threading.Thread(target=_register, args=(i,)) for i in range(2)]
    # when
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    # then — 정확히 1건만 생성되고 둘 다 같은 job_id 를 본다
    assert sum(created_flags) == 1
    assert len(set(job_ids)) == 1
    assert len(store.list_jobs()) == 1


# ── 명세서 §3.3 오류코드 (인증 계열 제외) ────────────────────────
def test_필수필드_누락은_REQUIRED_FIELD_MISSING(client: TestClient, tmp_path) -> None:
    # given — request_id 누락
    body = _body(tmp_path)
    body.pop("request_id")
    # when
    res = client.post(JOBS_URL, json=body)
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "REQUIRED_FIELD_MISSING"


def test_잘못된_enum값은_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given / when — 규격 밖 generation_mode(V2V 는 v1.3 에서도 미지원)
    res = client.post(JOBS_URL, json=_body(tmp_path, generation_mode="V2V"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


# ── v1.3 §4.1 prompt : 최상위 문자열(선택), 객체·배열 금지 ───────
def test_prompt에_객체를_실으면_400_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given / when — v1.2 의 prompt.condition / prompt.text 객체 구조는 v1.3 표준에서 제외됐다
    res = client.post(
        JOBS_URL,
        json=_body(tmp_path, prompt={"condition": {"season": "WINTER"}, "text": "겨울로"}),
    )
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_prompt에_배열을_실으면_400_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given / when
    res = client.post(JOBS_URL, json=_body(tmp_path, prompt=["겨울로", "야간으로"]))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_prompt는_선택값이라_없어도_202(client: TestClient, tmp_path) -> None:
    # given
    body = _body(tmp_path)
    body.pop("prompt")
    # when / then
    assert client.post(JOBS_URL, json=body).status_code == 202


def test_prompt가_1000자를_넘으면_400_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given / when — 임의로 자르지 않고 거부한다(§4.1 prompt 적용 규칙)
    ok = client.post(JOBS_URL, json=_body(tmp_path, prompt="가" * 1000))
    over = client.post(JOBS_URL, json=_body(tmp_path, prompt="가" * 1001))
    # then
    assert ok.status_code == 202
    assert over.status_code == 400
    assert over.json()["code"] == "INVALID_PARAMETER"


# ── v1.3 §4.1 mtdt : 최상위 필수 객체 ────────────────────────────
def test_mtdt가_없으면_400_REQUIRED_FIELD_MISSING(client: TestClient, tmp_path) -> None:
    # given — §3.3 "Idempotency-Key, mtdt 또는 기타 필수 Body 누락"
    body = _body(tmp_path)
    body.pop("mtdt")
    # when
    res = client.post(JOBS_URL, json=body)
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "REQUIRED_FIELD_MISSING"


def test_mtdt_항목값이_허용코드_밖이면_400_INVALID_PARAMETER(
    client: TestClient, tmp_path
) -> None:
    # given / when — weather 는 CLEAR|CLOUDY|RAIN|SNOW|FOG|WINDY
    res = client.post(
        JOBS_URL, json=_body(tmp_path, mtdt={"weather": "TORNADO", "time": "NIGHT"})
    )
    # then
    assert res.status_code == 400
    body = res.json()
    assert body["code"] == "INVALID_PARAMETER"
    assert "mtdt" in body["message"]


def test_mtdt가_비어있으면_400_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given / when — 유효한 조건값이 최소 1개는 있어야 한다
    empty = client.post(JOBS_URL, json=_body(tmp_path, mtdt={}))
    all_null = client.post(
        JOBS_URL,
        json=_body(
            tmp_path,
            mtdt={
                "time": None,
                "season": None,
                "weather": None,
                "terrain": None,
                "severity": None,
            },
        ),
    )
    # then
    assert empty.status_code == 400 and empty.json()["code"] == "INVALID_PARAMETER"
    assert all_null.status_code == 400 and all_null.json()["code"] == "INVALID_PARAMETER"


def test_mtdt는_한_항목만_채워도_202(client: TestClient, tmp_path) -> None:
    # given / when — 벤더 계약은 "최소 1개". 저작도구가 5개를 다 보내는 것과 별개 축이다
    res = client.post(JOBS_URL, json=_body(tmp_path, mtdt={"severity": "LOW"}))
    # then
    assert res.status_code == 202


# ── v1.3 구 형태 거부 ────────────────────────────────────────────
def test_최상위_condition은_400_INVALID_PARAMETER(client: TestClient, tmp_path) -> None:
    # given — v1.3 표준에서 제외된 구 최상위 필드
    body = _body(tmp_path)
    body["condition"] = {"season": "WINTER"}
    # when
    res = client.post(JOBS_URL, json=body)
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_미지원_operation_type_TRANSFORM은_400(client: TestClient, tmp_path) -> None:
    # given / when — V0 지원값은 GENERATE | AUGMENT
    res = client.post(JOBS_URL, json=_body(tmp_path, operation_type="TRANSFORM"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_미지원_조합_AUGMENT_T2I는_400(client: TestClient, tmp_path) -> None:
    # given / when — §4.1 V0 지원 조합은 GENERATE×T2I / AUGMENT×I2I / GENERATE×T2V
    res = client.post(
        JOBS_URL, json=_body(tmp_path, operation_type="AUGMENT", generation_mode="T2I",
                             input_files=[])
    )
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


# ── v1.3 §4.1 evnt_type / evnt_subtype ───────────────────────────
def test_WILDFIRE도_접수되고_evnt_subtype은_FLOOD전용(
    client: TestClient, tmp_path
) -> None:
    # given / when — WILDFIRE 는 세부 코드가 없어 evnt_subtype 을 생략한다
    body = _body(tmp_path, evnt_type="WILDFIRE")
    body.pop("evnt_subtype")
    ok = client.post(JOBS_URL, json=body)
    # when — WILDFIRE 에 FLOOD 세부 유형을 실으면 거부
    bad = client.post(
        JOBS_URL, json=_body(tmp_path, evnt_type="WILDFIRE", evnt_subtype="ROAD_FLOOD")
    )
    # then
    assert ok.status_code == 202
    assert bad.status_code == 400 and bad.json()["code"] == "INVALID_PARAMETER"


def test_허용코드_밖_evnt_subtype은_400(client: TestClient, tmp_path) -> None:
    # given / when
    res = client.post(JOBS_URL, json=_body(tmp_path, evnt_subtype="SEA_FLOOD"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "INVALID_PARAMETER"


def test_기본_허용목록_밖_evnt_type은_UNSUPPORTED_EVENT_TYPE(
    client: TestClient, tmp_path
) -> None:
    # given / when — v1.3 §4.1 계약은 FLOOD | WILDFIRE 2종(설정 없이 기본 강제)
    res = client.post(JOBS_URL, json=_body(tmp_path, evnt_type="FIRE"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "UNSUPPORTED_EVENT_TYPE"


def test_evnt_type_허용목록을_비워도_계약목록으로_fail_closed(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 설정을 빈값으로 둬도 "전체 허용" 으로 열리지 않아야 한다
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_EVENT_TYPES", "")
    reload_settings()
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, evnt_type="FIRE"))
    # then
    assert res.status_code == 400
    assert res.json()["code"] == "UNSUPPORTED_EVENT_TYPE"


def test_입력파일_크기초과는_413_GA_MEDIA_001(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 상한을 8바이트로 낮추고 그보다 큰 입력 파일 사용
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_GENAI_MAX_INPUT_BYTES", "8")
    reload_settings()
    big = _input_file(tmp_path, name="big.mp4", content=b"0123456789")
    files = [{"sequence": 1, "file_path": big}]
    # when
    res = client.post(JOBS_URL, json=_body(tmp_path, input_files=files))
    # then
    assert res.status_code == 413
    assert res.json()["code"] == "GA-MEDIA-001"


def test_오류응답에_스택트레이스나_내부경로가_없다(client: TestClient) -> None:
    # given / when
    res = client.get(f"{JOBS_URL}/none-such")
    # then — CWE-209
    payload = res.json()
    assert "Traceback" not in payload["message"]
    assert "/Users/" not in payload["message"]
    # 공통 규격(error_code)과 명세서 코드(code)를 함께 노출
    assert payload["error_code"] == payload["code"]


# ── 목 전용 보조 EP ──────────────────────────────────────────────
def test_mock_reset은_잡_저장소를_비운다(client: TestClient, tmp_path) -> None:
    # given
    client.post(JOBS_URL, json=_body(tmp_path))
    assert len(client.get(MOCK_JOBS_URL).json()["jobs"]) == 1
    # when
    res = client.post(MOCK_RESET_URL)
    # then
    assert res.status_code == 200
    assert client.get(MOCK_JOBS_URL).json()["jobs"] == []


# ── 구 placeholder 경로 제거 ─────────────────────────────────────
def test_구_augment_placeholder_경로는_제거되었다(client: TestClient) -> None:
    # given / when — 구 계약(/v1/augment)은 명세서 경로로 대체됨
    post = client.post("/v1/augment", json={})
    status_ = client.get("/v1/augment/status")
    # then
    assert post.status_code == 404
    assert status_.status_code == 404


def test_세_벤더_라우터가_모두_등록되어_있다(client: TestClient) -> None:
    # given / when — deid(루트) / vlm(status) / genai(목록)
    deid = client.get("/")
    vlm = client.get("/v1/videovlm-klid/status")
    genai = client.get(MOCK_JOBS_URL)
    # then
    assert deid.status_code == 200
    assert vlm.status_code == 200
    assert genai.status_code == 200
