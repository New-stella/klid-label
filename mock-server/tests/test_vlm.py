"""IntelliVIX Video VLM 벤더 목 — Phase 3 엔드포인트(verify/describe/status) + 비동기 콜백 테스트.

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

VERIFY_URL = "/v1/videovlm/verify"
DESCRIBE_URL = "/v1/videovlm/describe"
STATUS_URL = "/v1/videovlm/status"


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


def _verify_body(**over: object) -> dict:
    body = {
        "request_id": "00000001",
        "event_type": "fall",
        "media": {
            "type": "video",
            "source_type": "path",
            "path": "/data/videos/sample.mp4",
            "frame_policy": {"mode": "frame_interval", "framerate": 25},
        },
        "callback_url": "http://client-server/api/vlm/callback",
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
        "callback_url": "http://client-server/api/vlm/callback",
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


# ── verify 동기 응답 ─────────────────────────────────────────────
def test_verify_는_request_id_echo와_accepted를_200으로_반환(client: TestClient) -> None:
    # given / when
    res = client.post(VERIFY_URL, json=_verify_body())
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["request_id"] == "00000001"
    assert body["status"] == "accepted"


# ── describe 동기 응답 (우리 BE VlmClient.validateResponse 형식) ──
def test_describe_는_request_id_echo와_accepted를_반환(client: TestClient) -> None:
    # given / when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["request_id"] == "d0000001"
    assert body["status"] == "accepted"


# ── 입력 검증 ────────────────────────────────────────────────────
def test_유효하지않은_event_type은_422(client: TestClient) -> None:
    # given / when — 규격 밖 event_type
    res = client.post(VERIFY_URL, json=_verify_body(event_type="earthquake"))
    # then
    assert res.status_code == 422


def test_selected_frames_9개면_422(client: TestClient) -> None:
    # given — selected_frames 8 초과(9개)
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
    res = client.post(VERIFY_URL, json=_verify_body(media=media))
    # then
    assert res.status_code == 422


def test_media_누락은_400(client: TestClient) -> None:
    # given / when — 필수 media 없음(구조 오류)
    res = client.post(
        VERIFY_URL,
        json={"request_id": "x", "event_type": "fire", "callback_url": "http://c/cb"},
    )
    # then
    assert res.status_code == 400


def test_request_id_blank면_방어적으로_UUID발급하고_accepted(client: TestClient) -> None:
    # given / when — request_id 공백
    res = client.post(DESCRIBE_URL, json=_describe_body(request_id="   "))
    # then — 400 이 아니라 서버가 발급한 request_id 를 echo (echo 일관성)
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "accepted"
    assert isinstance(body["request_id"], str) and body["request_id"].strip() != ""


def test_frame_policy_frame_interval_정상처리(client: TestClient) -> None:
    # given / when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 200
    assert res.json()["status"] == "accepted"


# ── 비동기 콜백 발사 ─────────────────────────────────────────────
def test_verify_접수후_completed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(VERIFY_URL, json=_verify_body())
    # then — 콜백이 1회 발사되고 completed + results.accuracy/description 포함
    assert res.status_code == 200
    assert len(captured) == 1
    url, payload = captured[0]
    assert url == "http://client-server/api/vlm/callback"
    assert payload["request_id"] == "00000001"
    assert payload["status"] == "completed"
    assert "accuracy" in payload["results"]
    assert "description" in payload["results"]


def test_describe_콜백은_results가_배열이고_start_end_description보유(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 200
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "completed"
    results = payload["results"]
    assert isinstance(results, list) and len(results) >= 1
    for seg in results:
        assert "start_sec" in seg and "end_sec" in seg and "description" in seg


# ── 결정적 실패 트리거 → failed 콜백 (규격: 접수는 accepted, 실패는 콜백으로만) ──
def test_실패트리거_요청은_동기응답_accepted이고_failed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — request_id 가 "fail" 로 시작하는 결정적 실패 트리거
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(VERIFY_URL, json=_verify_body(request_id="fail-0001"))
    # then — 동기 응답은 규격상 여전히 accepted, 콜백은 failed(error{code,message})
    assert res.status_code == 200
    assert res.json()["status"] == "accepted"
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["request_id"] == "fail-0001"
    assert payload["status"] == "failed"
    assert payload["error"]["code"]
    assert payload["error"]["message"]


def test_describe_실패트리거도_동기_accepted이고_failed콜백_발사(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — media.path 에 "fail" 포함(경로 기반 트리거)
    captured = _patch_capture(monkeypatch)
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/data/videos/fail-case.mp4",
        "frame_policy": {"mode": "frame_interval", "framerate": 25},
    }
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body(media=media))
    # then
    assert res.status_code == 200
    assert res.json()["status"] == "accepted"
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "failed"
    assert payload["error"]["code"] and payload["error"]["message"]


# ── callback_url 스킴/형식 검증 (HttpUrl) ────────────────────────
def test_스킴없는_callback_url은_422(client: TestClient) -> None:
    # given / when — 스킴 없는(무효) callback_url
    res = client.post(VERIFY_URL, json=_verify_body(callback_url="client-server"))
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
def test_status_는_200을_반환(client: TestClient) -> None:
    # given / when
    res = client.get(STATUS_URL)
    # then
    assert res.status_code == 200


# ── multipart ────────────────────────────────────────────────────
def test_multipart_요청도_accepted를_반환(client: TestClient) -> None:
    # given — image 파일 + request(json) 파트, media.source_type="upload"
    request_json = {
        "request_id": "m0000001",
        "event_type": "fire",
        "media": {"type": "image", "source_type": "upload"},
        "callback_url": "http://client-server/api/vlm/callback",
    }
    # when
    res = client.post(
        VERIFY_URL,
        data={"request": json.dumps(request_json)},
        files={"image": ("x.jpg", b"\xff\xd8\xff\xd9", "image/jpeg")},
    )
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["request_id"] == "m0000001"
    assert body["status"] == "accepted"
