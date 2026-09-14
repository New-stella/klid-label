"""IntelliVIX Video VLM 벤더 목 — KLID 연동 API v1.1.0 엔드포인트 + 비동기 콜백 테스트.

콜백은 실제 네트워크 없이 검증한다:
- 발사 여부/페이로드 형식은 ``vlm_sim.fire_callback`` 을 monkeypatch 로 가로채 단정한다.
- 콜백 지연은 ``MOCK_CALLBACK_DELAY_SECONDS=0`` 으로 즉시화(_fast_callback fixture).
  FastAPI BackgroundTasks 는 TestClient 응답 반환 전에 완료되므로 캡처가 결정적이다.
- 도달 불가 URL 격리는 ``fire_callback`` 을 직접 호출해 예외 미전파를 확인한다.
"""

from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

# ★ 판정(verify) 라우트는 두지 않는다 — 저작도구가 연동하지 않는 창구다. 따라서 요청 형식·콜백
#   URL 가드 등 <b>창구 공통 규약</b>의 검증 대상은 묘사 창구로 옮겼다(그 규약은 창구와 무관하다).
DESCRIBE_URL = "/v1/videovlm-klid/describe"
DESCRIBE_SUB_URL = "/v1/videovlm-klid/describe-sub"
EVENTS_URL = "/v1/videovlm-klid/events"
STATUS_URL = "/v1/videovlm-klid/status"


@pytest.fixture(autouse=True)
def _fast_callback() -> Iterator[None]:
    """콜백 지연을 0 으로 만들어 BackgroundTasks 가 즉시 완료되게 한다."""
    import os

    from app.config import reload_settings

    prev = os.environ.get("MOCK_CALLBACK_DELAY_SECONDS")
    os.environ["MOCK_CALLBACK_DELAY_SECONDS"] = "0"
    reload_settings()
    yield
    if prev is None:
        os.environ.pop("MOCK_CALLBACK_DELAY_SECONDS", None)
    else:
        os.environ["MOCK_CALLBACK_DELAY_SECONDS"] = prev
    reload_settings()


def _request_body(**over: object) -> dict:
    body = {
        "request_id": "00000001",
        "event_type": "fall",
        "media": {
            "type": "video",
            "source_type": "path",
            "path": "/data/videos/sample.mp4",
            # framerate 는 규격에 없다 — mode 만 지정한다(§2.5).
            "frame_policy": {"mode": "frame_interval"},
        },
        "callback_url": "http://klid-backend:8080/api/v1/vlm/callback",
    }
    body.update(over)
    return body


def _describe_body(**over: object) -> dict:
    body = {
        "request_id": "d0000001",
        "media": {
            "type": "video",
            "source_type": "path",
            "path": "/data/videos/deid.mp4",
            "frame_policy": {"mode": "frame_interval", "framerate": 25},
        },
        "callback_url": "http://klid-backend:8080/api/v1/vlm/callback",
    }
    body.update(over)
    return body


def _patch_capture(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, dict]]:
    """fire_callback 을 가로채 (url, payload) 를 수집하는 리스트를 반환한다."""
    from app.services import vlm_sim

    captured: list[tuple[str, dict]] = []

    async def _recorder(url: str, payload: dict) -> None:
        captured.append((url, payload))

    monkeypatch.setattr(vlm_sim, "fire_callback", _recorder)
    return captured


# ── 접수 응답 ────────────────────────────────────────────────────
def test_접수응답은_202이고_request_id_echo와_accepted를_담는다(client: TestClient) -> None:
    """규격 §2.6 — 정상 접수는 HTTP **202**. 구 규격(v2.0.1)의 200 은 폐기됐다."""
    # given / when
    res = client.post(DESCRIBE_URL, json=_request_body())
    # then
    assert res.status_code == 202
    body = res.json()
    assert body["request_id"] == "00000001"
    assert body["status"] == "accepted"


# ── describe 동기 응답 (우리 BE VlmClient.validateResponse 형식) ──
def test_describe_는_request_id_echo와_accepted를_반환(client: TestClient) -> None:
    # given / when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 202
    body = res.json()
    assert body["request_id"] == "d0000001"
    assert body["status"] == "accepted"


# ── 입력 검증 ────────────────────────────────────────────────────
def test_표준_6종_밖의_event_type도_수락한다_구_422_폐기(client: TestClient) -> None:
    """구 동작(규격 밖 event_type → 422)은 2026-08-06 사용자 확정으로 폐기됐다.

    BE 가 사전 차단을 폐기하고 "조달값을 그대로 실어 항상 위탁"으로 반전했으므로, 목서버가
    구 enum 계약을 들고 있으면 로컬·dev 에서 파이프라인을 완주시킬 수 없다.
    ⚠ 목서버 한정 완화이며 **실벤더가 관대하다는 근거가 아니다**(규격상 required + enum 6종).
    """
    res = client.post(DESCRIBE_URL, json=_request_body(event_type="earthquake"))
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"


def test_event_type이_없어도_수락한다_구_400_폐기(client: TestClient) -> None:
    """BE 는 조달값이 없으면 event_type 을 지어내지 않고 null 을 그대로 보낸다.

    구 동작은 `400 Field required` 라 관제 값이 채워지기 전 영상은 한 건도 완주하지 못했다.
    """
    body = _request_body()
    body.pop("event_type")
    res = client.post(DESCRIBE_URL, json=body)
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"


def test_미지의_event_type은_폴백_서술로_콜백한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """값을 지어내 이벤트별 서술을 만들지 않는다 — 표준 7종만 전용 서술을 갖는다.

    ★ 검증 대상은 **추가 질문 창구**다. 묘사 창구의 이벤트별 서술은 아래 「서술 형식」 절이 따로
    고정한다.
    """
    captured = _patch_capture(monkeypatch)
    res = client.post(DESCRIBE_SUB_URL, json=_request_body(event_type="earthquake"))
    assert res.status_code == 202
    assert len(captured) == 1
    description = captured[0][1]["results"]["description"]
    assert "근거는 확인되지 않습니다" in description
    # 표준 7종의 전용 서술이 잘못 붙지 않는다(폴백이지 임의 매핑이 아니다).
    assert "불꽃" not in description


def test_표준_이벤트는_전용_서술로_콜백한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """폴백과 대조군 — 아는 값이면 그 이벤트 전용 문장이 온다."""
    captured = _patch_capture(monkeypatch)
    client.post(DESCRIBE_SUB_URL, json=_request_body(event_type="fire"))
    assert "불꽃" in captured[0][1]["results"]["description"]


def test_selected_frames_9개는_수락한다_구_상한8_폐기(client: TestClient) -> None:
    """구 verify 규격의 상한 8 은 폐기됐다 — KLID 규격 §2.5 의 상한은 600 이다."""
    # given — 구 상한(8)을 넘는 9개
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {
            "mode": "frame_selected",
            "selected_frames": [0, 1, 2, 3, 4, 5, 6, 7, 8],
        },
    }
    # when
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    # then — 수락(구 동작은 422)
    assert res.status_code == 202


def test_selected_frames_601개면_거부한다(client: TestClient) -> None:
    """규격 §2.9 — selected_frames 개수 초과(600)는 거부된다."""
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {"mode": "frame_selected", "selected_frames": list(range(601))},
    }
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    assert res.status_code in (400, 422)


def test_framerate를_보내도_무시하고_수락한다_필드_폐기(client: TestClient) -> None:
    """★ ``framerate`` 는 KLID 규격의 frame_policy 에 **없는 필드**다(§2.5).

    mode 만 연동 시스템이 지정하고 간격·장수 세부값은 서버가 관리한다. 구 규격(v2.0.1)에서
    넘어온 클라이언트가 이 필드를 보내도 접수는 되어야 한다(``extra="ignore"``) — 그래야
    전환 구간에 위탁이 죽지 않는다. 다만 값은 아무 영향도 주지 않는다.
    """
    # given — 구 규격 필드를 그대로 실은 요청(값의 크고 작음은 무관하다)
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {"mode": "frame_interval", "framerate": 0},
    }
    # when
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    # then — 값 검증 자체가 사라졌으므로 0 이어도 수락된다(구 동작은 422)
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"


def test_uniform_mode는_거부한다_v120에서_폐기(client: TestClient) -> None:
    """★ 구 mode ``uniform`` 은 규격 v1.2.0 §2.6 에서 **삭제**됐다 — 이제 거부돼야 한다.

    이 시험은 원래 ``uniform`` 을 **수락**하는 것을 고정하고 있었다. 규격이 그 값을 없앴으므로
    단정을 뒤집는다 — 시험을 지우지 않는 이유는, 지우면 누군가 되살렸을 때 아무도 못 잡기 때문이다.

    ⚠ 실벤더는 이 경우 **400** 을 준다(규격 §2.10). 이 목은 요청 검증 전반을 **422** 로 돌려주므로
    상태코드가 다르다 — 우리 쪽 분류(4xx 중 429 만 재시도)에서는 둘이 같게 취급되어 검증에는 지장이
    없으나, **목과 실벤더의 알려진 차이**다. 상태코드 자체를 단정하지 않고 「거부된다」만 고정한다.
    """
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {"mode": "uniform"},
    }
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    assert res.status_code >= 400, "폐기된 mode 가 수락됐다 — 실벤더는 400 으로 거부한다"
    assert res.status_code != 202


def test_정의되지_않은_mode는_거부한다(client: TestClient) -> None:
    """규격 §2.5 — 정의되지 않은 mode 는 거부된다."""
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {"mode": "every_other_frame"},
    }
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    assert res.status_code in (400, 422)


def test_media_누락은_400(client: TestClient) -> None:
    # given / when — 필수 media 없음(구조 오류)
    res = client.post(
        DESCRIBE_URL,
        json={"request_id": "x", "event_type": "fire", "callback_url": "http://c/cb"},
    )
    # then
    assert res.status_code == 400


def test_request_id_blank면_방어적으로_UUID발급하고_accepted(client: TestClient) -> None:
    # given / when — request_id 공백
    res = client.post(DESCRIBE_URL, json=_describe_body(request_id="   "))
    # then — 400 이 아니라 서버가 발급한 request_id 를 echo (echo 일관성)
    assert res.status_code == 202
    body = res.json()
    assert body["status"] == "accepted"
    assert isinstance(body["request_id"], str) and body["request_id"].strip() != ""


def test_frame_policy_frame_interval_정상처리(client: TestClient) -> None:
    # given / when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"


# ── 비동기 콜백 발사 ─────────────────────────────────────────────
def test_묘사_접수후_completed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_request_body())
    # then — 콜백이 1회 발사되고 completed + results.description 포함
    assert res.status_code == 202
    assert len(captured) == 1
    url, payload = captured[0]
    assert url == "http://klid-backend:8080/api/v1/vlm/callback"
    assert payload["request_id"] == "00000001"
    assert payload["status"] == "completed"
    assert "description" in payload["results"]


def test_콜백_results는_단일객체이고_판정항목이_없다_구_배열_폐기(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """규격 §2.8 — 우리가 쓰는 두 창구의 결과 항목은 ``description`` 하나다.

    구 규격의 구간 배열(``[{start_sec,end_sec,description}]``)은 폐기됐고, 판정 항목
    (``detected``/``accuracy``)은 판정 창구 전용이라 오지 않는다.
    """
    # given
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "completed"
    results = payload["results"]
    assert isinstance(results, dict)
    assert isinstance(results["description"], str) and results["description"]
    assert "detected" not in results
    assert "accuracy" not in results


def test_추가질문_콜백도_서술만_돌려준다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """규격 §3.3 — 발생 여부를 묻지만 응답은 서술이며 판정 항목이 없다."""
    # given
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_SUB_URL, json=_request_body(request_id="s0000001"))
    # then
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["request_id"] == "s0000001"
    assert payload["status"] == "completed"
    assert isinstance(payload["results"], dict)
    assert isinstance(payload["results"]["description"], str)
    assert "detected" not in payload["results"]
    assert "accuracy" not in payload["results"]


def test_실패콜백의_error는_객체가_아니라_문자열이다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """규격 §2.7 — 구 규격의 ``{code, message}`` 객체는 폐기됐다.

    객체로 되돌리면 우리 BE 가 실패 콜백을 전량 400 으로 거부해 실패 사실 자체를 잃는다.
    """
    captured = _patch_capture(monkeypatch)
    client.post(DESCRIBE_URL, json=_request_body(request_id="fail-0002"))
    _, payload = captured[0]
    assert payload["status"] == "failed"
    assert isinstance(payload["error"], str) and payload["error"]


def test_지원이벤트_조회는_창구별_목록을_돌려준다(client: TestClient) -> None:
    """규격 §3.4 — 창구마다 사용 가능한 event_type 이 다를 수 있어 목록을 따로 준다."""
    res = client.get(EVENTS_URL)
    assert res.status_code == 200
    body = res.json()
    assert "smoke" in body["describe_events"]
    assert "smoke" in body["describe_sub_events"]
    assert len(body["events"]) == 7


# ── 결정적 실패 트리거 → failed 콜백 (규격: 접수는 accepted, 실패는 콜백으로만) ──
def test_실패트리거_요청은_동기응답_accepted이고_failed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — request_id 가 "fail" 로 시작하는 결정적 실패 트리거
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_request_body(request_id="fail-0001"))
    # then — 동기 응답은 규격상 여전히 accepted, 콜백은 failed(error 는 문자열)
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["request_id"] == "fail-0001"
    assert payload["status"] == "failed"
    assert isinstance(payload["error"], str) and payload["error"]


def test_describe_실패트리거도_동기_accepted이고_failed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — media.path 에 "fail" 포함(경로 기반 트리거)
    captured = _patch_capture(monkeypatch)
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/videos/fail-case.mp4",
        "frame_policy": {"mode": "frame_interval"},
    }
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body(media=media))
    # then
    assert res.status_code == 202
    assert res.json()["status"] == "accepted"
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "failed"
    assert isinstance(payload["error"], str) and payload["error"]


# ── callback_url 스킴/형식 검증 (HttpUrl) ────────────────────────
def test_스킴없는_callback_url은_422(client: TestClient) -> None:
    # given / when — 스킴 없는(무효) callback_url
    res = client.post(DESCRIBE_URL, json=_request_body(callback_url="client-server"))
    # then — HttpUrl 형식/스킴 검증 실패 → 422
    assert res.status_code == 422


def test_콜백_URL_도달불가시_서버가_예외로_죽지않고_실패로깅(monkeypatch: pytest.MonkeyPatch) -> None:
    # given — 연결 거부되는 로컬 포트
    from app.services import vlm_sim

    # when — fire_callback 직접 호출은 예외를 삼켜야 한다(격리)
    result = asyncio.run(
        vlm_sim.fire_callback("http://127.0.0.1:1/nope", {"request_id": "x"})
    )
    # then — 예외 없이 None 반환
    assert result is None


# ── status ───────────────────────────────────────────────────────
def test_status_는_처리가능상태와_대기수를_돌려준다(client: TestClient) -> None:
    """규격 §3.5 — ready|busy|loading + queue/pending."""
    # given / when
    res = client.get(STATUS_URL)
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["status"] in ("ready", "busy", "loading")
    assert isinstance(body["queue"], int)
    assert isinstance(body["pending"], int)


# ── multipart ────────────────────────────────────────────────────
def test_multipart_요청도_accepted를_반환(client: TestClient) -> None:
    # given — image 파일 + request(json) 파트, media.source_type="upload"
    request_json = {
        "request_id": "m0000001",
        "event_type": "fire",
        "media": {"type": "image", "source_type": "upload"},
        "callback_url": "http://klid-backend:8080/api/v1/vlm/callback",
    }
    # when
    res = client.post(
        DESCRIBE_URL,
        data={"request": json.dumps(request_json)},
        files={"image": ("x.jpg", b"\xff\xd8\xff\xd9", "image/jpeg")},
    )
    # then
    assert res.status_code == 202
    body = res.json()
    assert body["request_id"] == "m0000001"
    assert body["status"] == "accepted"


# ── HIGH-1 콜백 SSRF 방어 (CWE-918) ──────────────────────────────
# 목 서버는 인증이 없고 callback_url 로 지정된 임의 주소에 서버측 outbound POST 를 발사한다.
# 요청자가 내부 주소를 넣으면 내부망 포트 스캔/요청 위조가 성립하므로 허용 호스트만 수락한다.
def test_허용되지않은_callback_url_호스트는_400(client: TestClient) -> None:
    # given / when — 내부망 임의 주소(SSRF 시도)
    res = client.post(
        DESCRIBE_URL, json=_request_body(callback_url="http://10.0.0.9:9300/internal")
    )
    # then
    assert res.status_code == 400
    assert res.json()["error_code"] == "VALIDATION_ERROR"


def test_describe도_허용되지않은_callback_url을_거부(client: TestClient) -> None:
    # given / when
    res = client.post(
        DESCRIBE_URL, json=_describe_body(callback_url="http://klid-postgres:5432/x")
    )
    # then
    assert res.status_code == 400


def test_차단된_callback_url은_콜백을_발사하지_않는다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _patch_capture(monkeypatch)
    # when
    client.post(DESCRIBE_URL, json=_request_body(callback_url="http://10.0.0.9/x"))
    # then — 거부된 요청은 outbound 를 전혀 발사하지 않는다
    assert captured == []


def test_기본_허용호스트는_backend와_루프백(client: TestClient) -> None:
    # given / when / then — BE 가 실제로 넘기는 콜백 주소(WEBHOOK_CALLBACK_BASE_URL 기반)는 통과해야 한다
    for url in (
        "http://klid-backend:8080/api/v1/vlm/callback",
        "http://localhost:8080/api/v1/vlm/callback",
        "http://127.0.0.1:8080/api/v1/vlm/callback",
    ):
        res = client.post(DESCRIBE_URL, json=_request_body(callback_url=url))
        assert res.status_code == 202, url


def test_허용호스트는_환경변수로_확장할_수_있다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 벤더/환경 변경 시 재빌드 없이 확장 가능해야 한다
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_CALLBACK_ALLOWED_HOSTS", "klid-backend,authoring-be")
    reload_settings()
    # when
    res = client.post(
        DESCRIBE_URL, json=_request_body(callback_url="http://authoring-be:8080/cb")
    )
    # then
    assert res.status_code == 202
    reload_settings()


# ── 서술 형식 — 실제 사업자 응답 정합 ────────────────────────────
# 정본: reports/vlm-klid-integration/실응답-20260914/LIVE-*.json (2026-09-14 수신 콜백 원문).
#   - 묘사: 라벨 줄 5개(장소·날씨·상황·환경·심각성) 순서 고정, 줄 머리 「- 」는 호출마다 있기도 없기도.
#   - 사용자 프롬프트: 결론 첫 문장 → ### 근거: → 번호/글머리표 목록 → (선택) --- → 「따라서, …」.
CUSTOM_URL = "/v1/videovlm-klid/custom"
_LABEL_ORDER = ["장소", "날씨", "상황", "환경", "심각성"]
#: 저작도구의 「상황」 추출 규칙과 같은 정규식(DescriptionSituationExtractor) — 줄 단위로 적용한다.
_SITUATION_LINE = re.compile(r"^\s*(?:-\s*)?상황\s*:\s*(.*)$")
_LABEL_LINE = re.compile(r"^(- )?(장소|날씨|상황|환경|심각성): (.+)$")
_SEGMENT_SENTENCE = re.compile(r"\d+\s*~\s*\d+\s*초|초 구간")
_EVENT_TYPES = ["fire", "smoke", "fall", "violence", "flooding", "car_accident", "kidnapping"]
_QUESTION = "영상에서 '화염이 보이는 불' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?"


def _custom_body(**over: object) -> dict:
    body = {
        "request_id": "c0000001",
        "prompt": _QUESTION,
        "media": {
            "type": "video",
            "source_type": "path",
            "path": "/data/videos/deid.mp4",
            "frame_policy": {"mode": "frame_interval"},
        },
        "callback_url": "http://klid-backend:8080/api/v1/vlm/callback",
    }
    body.update(over)
    return body


def _extract_situation(description: str) -> str | None:
    """저작도구 추출기와 같은 규칙 — 줄마다 정규식을 대 첫 일치의 값을 돌려준다."""
    for line in re.split(r"\r\n|\r|\n", description):
        match = _SITUATION_LINE.match(line)
        if match:
            value = match.group(1).strip()
            return value or None
    return None


def _describe_description(client: TestClient, monkeypatch: pytest.MonkeyPatch, **over: object) -> str:
    captured = _patch_capture(monkeypatch)
    res = client.post(DESCRIBE_URL, json=_request_body(**over))
    assert res.status_code == 202
    assert len(captured) == 1
    return captured[0][1]["results"]["description"]


@pytest.mark.parametrize("event_type", [*_EVENT_TYPES, None, "earthquake"])
def test_묘사_서술은_라벨_5줄이_장소_날씨_상황_환경_심각성_순서다(event_type: str | None) -> None:
    from app.services import vlm_sim

    for index in range(12):
        text = vlm_sim.mock_describe_text(f"order-{index}", event_type, 30)
        lines = text.split("\n")
        assert len(lines) == 5, text
        matches = [_LABEL_LINE.match(line) for line in lines]
        assert all(matches), text
        assert [m.group(2) for m in matches] == _LABEL_ORDER  # type: ignore[union-attr]
        # 한 서술 안에서는 줄 머리 형식이 섞이지 않는다.
        assert len({m.group(1) for m in matches}) == 1  # type: ignore[union-attr]


def test_묘사_서술의_줄머리_대시는_요청마다_있기도_없기도_하다() -> None:
    """실응답은 car_accident 건에 「- 」가 없고 fire 건에 있었다 — 두 형식이 모두 나와야 한다."""
    from app.services import vlm_sim

    forms = {
        vlm_sim.mock_describe_text(f"LIVE-DESC-{index:04d}", "car_accident", 40).startswith("- ")
        for index in range(20)
    }
    assert forms == {True, False}


@pytest.mark.parametrize("event_type", [*_EVENT_TYPES, None])
def test_묘사_서술의_상황_값은_저작도구_추출_규칙으로_뽑힌다(event_type: str | None) -> None:
    from app.services import vlm_sim

    for index in range(10):
        text = vlm_sim.mock_describe_text(f"sit-{index}", event_type, 40)
        situation = _extract_situation(text)
        assert situation, text
        # 뽑힌 값은 「상황」 줄 한 줄뿐이다 — 다음 라벨 줄이 섞이지 않는다.
        assert "환경:" not in situation and "\n" not in situation
        assert len(situation) >= 60


def test_묘사_서술의_심각성은_N점_형식이다() -> None:
    from app.services import vlm_sim

    formats = set()
    for index in range(30):
        text = vlm_sim.mock_describe_text(f"sev-{index}", "fall", 20)
        severity_line = text.split("\n")[-1]
        match = re.match(r"^(?:- )?심각성: (\d+)점\. (이유: )?\S", severity_line)
        assert match, severity_line
        assert 1 <= int(match.group(1)) <= 10
        formats.add(match.group(2) is not None)
    # 「N점. 이유: …」와 「N점. …」 두 형식이 모두 나온다(실응답도 둘 다 있었다).
    assert formats == {True, False}


def test_묘사_서술에는_구간_문장이_없고_2000자_이내다() -> None:
    from app.services import vlm_sim

    for event_type in [*_EVENT_TYPES, None, "earthquake"]:
        for index in range(30):
            for duration in (3, 40, 3600):
                text = vlm_sim.mock_describe_text(f"len-{index}", event_type, duration)
                assert 0 < len(text) <= 2000
                assert _SEGMENT_SENTENCE.search(text) is None, text


def test_묘사_서술은_이벤트_유형에_따라_상황이_달라진다() -> None:
    """표준 유형은 그 이벤트의 발생/미발생 서술, 모르는 유형은 이벤트를 판정하지 않는 일반 서술."""
    from app.services import vlm_sim

    fire = {_extract_situation(vlm_sim.mock_describe_text(f"ev-{i}", "fire", 40)) for i in range(20)}
    assert all("화재" in s for s in fire if s)
    # 발생·미발생 두 갈래가 모두 나온다.
    assert any(s and s.startswith("화재 발생") for s in fire)
    assert any(s and s.startswith("화재는 확인되지 않음") for s in fire)

    unknown = _extract_situation(vlm_sim.mock_describe_text("ev-0", "earthquake", 40))
    assert unknown and unknown.startswith("일반적인 시내 교통 상황")
    assert unknown == _extract_situation(vlm_sim.mock_describe_text("ev-0", None, 40))


def test_묘사_콜백은_같은_request_id에_같은_서술을_돌려준다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = _describe_description(client, monkeypatch, request_id="same-0001", event_type="fire")
    second = _describe_description(client, monkeypatch, request_id="same-0001", event_type="fire")
    assert first == second
    assert _extract_situation(first)


def test_묘사_콜백은_요청의_event_type을_서술에_반영한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    description = _describe_description(client, monkeypatch, event_type="car_accident")
    situation = _extract_situation(description)
    assert situation and "교통사고" in situation


def test_상황_생략_옵션을_켜면_상황_줄이_통째로_빠진다(monkeypatch: pytest.MonkeyPatch) -> None:
    """「상황 줄이 없으면 채우지 않는다」 분기를 로컬에서 재현하는 옵션 — 나머지 4줄 순서는 유지."""
    from app.config import reload_settings
    from app.services import vlm_sim

    monkeypatch.setenv("MOCK_DESCRIBE_OMIT_SITUATION", "true")
    reload_settings()
    try:
        text = vlm_sim.mock_describe_text("omit-1", "fire", 40)
        labels = [_LABEL_LINE.match(line).group(2) for line in text.split("\n")]  # type: ignore[union-attr]
        assert labels == ["장소", "날씨", "환경", "심각성"]
        assert _extract_situation(text) is None
    finally:
        monkeypatch.delenv("MOCK_DESCRIBE_OMIT_SITUATION", raising=False)
        reload_settings()
    assert _extract_situation(vlm_sim.mock_describe_text("omit-1", "fire", 40))


def test_사용자프롬프트_콜백은_마크다운_근거와_질문의_이벤트_문구를_담는다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    captured = _patch_capture(monkeypatch)
    res = client.post(CUSTOM_URL, json=_custom_body())
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "completed"
    assert set(payload["results"]) == {"description"}
    description = payload["results"]["description"]
    first_line = description.split("\n")[0]
    assert first_line.startswith("영상에서 ")
    assert "화염이 보이는 불" in first_line
    assert "발생" in first_line
    assert "\n\n### 근거:\n" in description
    assert description.rstrip().split("\n")[-1].startswith("따라서, ")
    assert 0 < len(description) <= 2000


def test_사용자프롬프트_서술은_번호목록과_글머리표목록_구분선_유무가_섞인다() -> None:
    from app.services import vlm_sim

    numbered = bullet = with_rule = without_rule = False
    for index in range(40):
        text = vlm_sim.mock_custom_text(f"LIVE-CUST-{index:04d}", _QUESTION)
        evidence = text.split("### 근거:\n", 1)[1]
        if re.match(r"1\. \*\*[^*]+\*\*:  \n   \S", evidence):
            numbered = True
            assert "\n\n2. **" in evidence
        elif evidence.startswith("- "):
            bullet = True
        else:  # pragma: no cover — 두 형식 밖이면 실패
            pytest.fail(text)
        if "\n\n---\n\n" in text:
            with_rule = True
        else:
            without_rule = True
    assert numbered and bullet and with_rule and without_rule


def test_사용자프롬프트_서술은_따옴표_문구가_없으면_질문을_요약해_싣는다() -> None:
    from app.services import vlm_sim

    text = vlm_sim.mock_custom_text("q-1", "영상 속에 쓰러진 사람이 있는지 알려줘?")
    assert text.split("\n")[0].startswith("질문하신 「영상 속에 쓰러진 사람이 있는지 알려줘」")
    assert "### 근거:" in text
    # 주제 낱말(쓰러)로 그 주제의 근거가 붙는다.
    assert "넘어" in text or "누운" in text or "누워" in text


def test_사용자프롬프트_서술은_같은_request_id면_같고_2000자_이내다() -> None:
    from app.services import vlm_sim

    long_prompt = "영상에서 '" + "가" * 200 + "' 이벤트가 있나? " + "나" * 3500
    for index in range(30):
        for prompt in (_QUESTION, long_prompt, "무엇이 보이나요"):
            first = vlm_sim.mock_custom_text(f"det-{index}", prompt)
            assert first == vlm_sim.mock_custom_text(f"det-{index}", prompt)
            assert 0 < len(first) <= 2000


def test_추가질문_서술은_네_또는_아니요로_시작하는_짧은_문장이다() -> None:
    """규격 describe-sub 예시(「네, 두 사람이 …」) 정합 — 저작도구는 쓰지 않지만 목에 남아 있다."""
    from app.services import vlm_sim

    for event_type in _EVENT_TYPES:
        starts = set()
        for index in range(20):
            text = vlm_sim.build_describe_sub_callback(f"sub-{index}", event_type)["results"]["description"]
            assert text.startswith(("네, ", "아니요, ")), text
            assert len(text) <= 200
            starts.add(text.split(",")[0])
        assert starts == {"네", "아니요"}
