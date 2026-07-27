"""헬스체크 + 3개 벤더 라우터 등록 검증."""

from __future__ import annotations

from fastapi.testclient import TestClient


def test_health가_200과_ok를_반환(client: TestClient) -> None:
    # given / when
    res = client.get("/health")
    # then
    assert res.status_code == 200
    assert res.json() == {"status": "ok"}


def test_deid_라우터가_등록되어_루트가_Connect를_반환(client: TestClient) -> None:
    # given / when — KPST 규격은 루트 경로를 쓰므로 prefix 없이 등록됨
    res = client.get("/")
    # then
    assert res.status_code == 200
    assert res.text == "Connect"


def test_vlm_라우터가_등록되어_status가_200(client: TestClient) -> None:
    # given / when
    res = client.get("/v1/videovlm/status")
    # then
    assert res.status_code == 200
    assert res.json()["status"] == "ok"


def test_genai_라우터가_등록되어_목록조회가_200(client: TestClient) -> None:
    # given / when — 생성형 AI(증강) 목: 명세서 경로 /api/genai/* + 목 전용 보조 EP
    res = client.get("/api/genai/_mock/jobs")
    # then
    assert res.status_code == 200
    assert "jobs" in res.json()


def test_request_id_헤더가_응답에_포함(client: TestClient) -> None:
    # given / when
    res = client.get("/health")
    # then
    assert res.status_code == 200
    assert "X-Request-Id" in res.headers
    assert len(res.headers["X-Request-Id"]) > 0
