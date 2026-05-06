"""X-Request-Id 미들웨어 테스트."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_request_id_헤더가_응답에_포함() -> None:
    res = client.get("/health")
    assert res.status_code == 200
    assert "X-Request-Id" in res.headers
    rid = res.headers["X-Request-Id"]
    assert len(rid) > 0
    # 자동 생성된 hex id는 12자
    assert len(rid) == 12


def test_요청에_X_Request_Id_헤더_있으면_그대로_반사() -> None:
    given = "req-abc-123"
    res = client.get("/health", headers={"X-Request-Id": given})
    assert res.status_code == 200
    assert res.headers["X-Request-Id"] == given


def test_요청_X_Request_Id에_CRLF_포함시_재생성() -> None:
    # CWE-113 Header Manipulation 방어
    res = client.get("/health", headers={"X-Request-Id": "evil\r\nInjected: yes"})
    assert res.status_code == 200
    rid = res.headers["X-Request-Id"]
    assert "\r" not in rid and "\n" not in rid
    # 자동 생성된 hex id로 대체
    assert len(rid) == 12
