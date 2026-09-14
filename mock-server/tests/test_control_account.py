"""관제 계정 세션 창구 목 테스트 (INT-015 — 갱신·로그아웃).

저작도구 서버의 세션 중계가 로컬에서 실동작하려면 목이 아래를 지켜야 한다:
- 갱신: 헤더 x-access-token 의 refresh 토큰 검증 → 새 토큰 쌍(저작도구가 검증하는 JWT) / 무효면 401 900205
- 로그아웃: 200 {"error": 0}

서명 검증은 이 테스트 파일 안의 **독립 HMAC 구현**으로 한다 — 목의 서명 함수로 목의 결과를 검증하면
둘이 같이 틀려도 통과한다.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

REFRESH_URL = "/api/account/auth/refresh"
LOGOUT_URL = "/api/account/auth/logout"
# 시험 실행마다 새로 만든다 — 커밋되는 고정 비밀값을 두지 않는다(64바이트 = HS512 키 하한).
SECRET = secrets.token_hex(32)
OTHER_KEY = secrets.token_hex(32)

CLAIMS = {
    "sub": "admin",
    "userId": "admin",
    "userNm": "관리자",
    "authority": "SYSTEM_ADMIN",
    "sessionId": "sess-1",
    "sessionExpAlarm": 5,
    "sessionTime": 30,
}


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _make_token(payload: dict, secret: str = SECRET, alg: str = "HS512") -> str:
    digest = {"HS256": hashlib.sha256, "HS512": hashlib.sha512}[alg]
    head = _b64(json.dumps({"alg": alg}).encode())
    body = _b64(json.dumps(payload).encode())
    sig = hmac.new(secret.encode(), f"{head}.{body}".encode(), digest).digest()
    return f"{head}.{body}.{_b64(sig)}"


def _decode_verified(token: str, secret: str = SECRET) -> tuple[dict, dict]:
    head, body, sig = token.split(".")
    header = json.loads(_unb64(head))
    digest = {"HS256": hashlib.sha256, "HS512": hashlib.sha512}[header["alg"]]
    expected = hmac.new(secret.encode(), f"{head}.{body}".encode(), digest).digest()
    assert hmac.compare_digest(expected, _unb64(sig)), "새 토큰이 같은 비밀키로 서명되지 않았다"
    return header, json.loads(_unb64(body))


def _refresh_token(**over: object) -> str:
    now = int(time.time())
    payload = {**CLAIMS, "type": "refresh", "iat": now, "exp": now + 3600, **over}
    return _make_token(payload)


def _set_secret(value: str | None) -> None:
    from app.config import reload_settings

    if value is None:
        os.environ.pop("MOCK_CONTROL_JWT_SECRET", None)
    else:
        os.environ["MOCK_CONTROL_JWT_SECRET"] = value
    reload_settings()


@pytest.fixture(autouse=True)
def _secret() -> Iterator[None]:
    prev = os.environ.get("MOCK_CONTROL_JWT_SECRET")
    _set_secret(SECRET)
    yield
    _set_secret(prev)


def test_유효한_refresh_토큰이면_200과_새_토큰쌍을_관제_실측_형태로_돌려준다(client: TestClient) -> None:
    before = int(time.time())
    res = client.post(REFRESH_URL, headers={"x-access-token": _refresh_token()})

    assert res.status_code == 200
    body = res.json()
    assert body["error"] == 0
    assert body["message"] == "success"
    session_token = body["data"]["session_token"]
    refresh_token = body["data"]["refresh_token"]

    a_header, access = _decode_verified(session_token)
    r_header, refresh = _decode_verified(refresh_token)
    assert a_header == {"alg": "HS512"}
    assert r_header == {"alg": "HS512"}
    assert access["type"] == "access"
    assert refresh["type"] == "refresh"
    for key, value in CLAIMS.items():
        assert access[key] == value, f"access 토큰이 {key} 클레임을 복사하지 않았다"
        assert refresh[key] == value, f"refresh 토큰이 {key} 클레임을 복사하지 않았다"
    assert access["iat"] >= before
    assert access["exp"] - access["iat"] == 30 * 60
    assert refresh["exp"] - refresh["iat"] == 7 * 24 * 60 * 60


def test_들어온_토큰의_서명_알고리즘을_따른다(client: TestClient) -> None:
    now = int(time.time())
    token = _make_token({**CLAIMS, "type": "refresh", "iat": now, "exp": now + 60}, alg="HS256")

    res = client.post(REFRESH_URL, headers={"x-access-token": token})

    assert res.status_code == 200
    header, _ = _decode_verified(res.json()["data"]["session_token"])
    assert header == {"alg": "HS256"}


def test_세션_임계_클레임이_없으면_관제_실측_기본값을_채운다(client: TestClient) -> None:
    now = int(time.time())
    bare = {"sub": "admin", "sessionId": "s", "type": "refresh", "iat": now, "exp": now + 60}

    res = client.post(REFRESH_URL, headers={"x-access-token": _make_token(bare)})

    assert res.status_code == 200
    _, access = _decode_verified(res.json()["data"]["session_token"])
    assert access["sessionExpAlarm"] == 5
    assert access["sessionTime"] == 30


def _assert_rejected(res) -> None:  # noqa: ANN001 — httpx Response
    assert res.status_code == 401
    assert res.json() == {"error": 900205, "message": "리프레시 토큰이 없습니다."}


def test_헤더가_없으면_401_900205(client: TestClient) -> None:
    _assert_rejected(client.post(REFRESH_URL))


def test_다른_비밀키로_서명된_토큰은_401(client: TestClient) -> None:
    now = int(time.time())
    forged = _make_token({**CLAIMS, "type": "refresh", "iat": now, "exp": now + 60}, OTHER_KEY)
    _assert_rejected(client.post(REFRESH_URL, headers={"x-access-token": forged}))


def test_access_토큰으로_갱신하면_401(client: TestClient) -> None:
    _assert_rejected(client.post(REFRESH_URL, headers={"x-access-token": _refresh_token(type="access")}))


def test_만료된_refresh_토큰은_401(client: TestClient) -> None:
    now = int(time.time())
    _assert_rejected(
        client.post(REFRESH_URL, headers={"x-access-token": _refresh_token(iat=now - 120, exp=now - 60)})
    )


def test_형식이_깨진_토큰은_401(client: TestClient) -> None:
    _assert_rejected(client.post(REFRESH_URL, headers={"x-access-token": "not-a-jwt"}))


def test_비밀키가_없으면_토큰을_지어내지_않고_503(client: TestClient) -> None:
    _set_secret("")
    res = client.post(REFRESH_URL, headers={"x-access-token": _refresh_token()})

    assert res.status_code == 503
    assert "session_token" not in res.text


def test_로그아웃은_200_error_0(client: TestClient) -> None:
    res = client.post(LOGOUT_URL, headers={"x-access-token": "access-token"})

    assert res.status_code == 200
    assert res.json() == {"error": 0, "message": "success"}


def test_로그에_토큰_원문이_실리지_않는다(client: TestClient, caplog: pytest.LogCaptureFixture) -> None:
    token = _refresh_token()
    caplog.set_level(logging.INFO)

    res = client.post(REFRESH_URL, headers={"x-access-token": token})
    client.post(LOGOUT_URL, headers={"x-access-token": token})

    assert res.status_code == 200
    assert token not in caplog.text
    assert res.json()["data"]["session_token"] not in caplog.text
