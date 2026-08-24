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

    ★ 검증 대상은 **추가 질문 창구**다. 묘사 창구의 서술은 영상 길이에서 나오는 구간 서술이라
    event_type 에 따라 갈리지 않는다 — 그 창구로 이 성질을 확인하면 늘 통과하는 헛 단정이 된다.
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


def test_uniform_mode를_수락한다_신규(client: TestClient) -> None:
    """규격 §2.5 가 정의한 세 mode 중 ``uniform`` — 전 구간 균등 추출."""
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/v.mp4",
        "frame_policy": {"mode": "uniform"},
    }
    res = client.post(DESCRIBE_URL, json=_request_body(media=media))
    assert res.status_code == 202


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
