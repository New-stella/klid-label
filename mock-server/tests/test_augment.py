"""증강 AI 벤더 목 라우터(placeholder) — Phase 4 확장 지점 테스트.

증강 AI(SFR-07: WINTER/NIGHT/RAIN)는 아직 구현하지 않는다. 이 테스트는 확장 지점이
등록되어 있고(status placeholder), 아직 미구현임을 규격 응답(501)으로 명확히 알리는지만
검증한다. 실제 증강 로직/콜백은 향후 Phase 에서 구현된다.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

STATUS_URL = "/v1/augment/status"
AUGMENT_URL = "/v1/augment"


def test_augment_status가_200과_not_implemented를_반환(client: TestClient) -> None:
    # given / when
    res = client.get(STATUS_URL)
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "not_implemented"
    # 사람이 읽을 안내 메시지가 포함되어야 한다(무엇이 추후 구현인지).
    assert "message" in body
    assert body["message"]


def test_augment_post는_501_미구현_스텁을_반환(client: TestClient) -> None:
    # given — 향후 계약(원본 참조 + augment_type)을 흉내낸 요청 본문
    body = {
        "request_id": "aug-0001",
        "orgnl_raw_sn": 1001,
        "augment_type": "WINTER",
        "callback_url": "http://localhost:8080/v1/aug/callback",
    }
    # when
    res = client.post(AUGMENT_URL, json=body)
    # then — 아직 미구현이므로 501, 공통 예외 규격(error_code/message)로 반환
    assert res.status_code == 501
    payload = res.json()
    assert "error_code" in payload
    assert "message" in payload
    # 스택트레이스/내부 경로가 노출되지 않아야 한다(CWE-209).
    assert "Traceback" not in payload["message"]


def test_augment_post는_본문없이도_501을_반환(client: TestClient) -> None:
    # given / when — 미구현 스텁이므로 입력 검증 이전에 501 로 단락한다.
    res = client.post(AUGMENT_URL)
    # then
    assert res.status_code == 501


def test_세_벤더_라우터가_모두_등록되어_있다(client: TestClient) -> None:
    # given / when — deid(루트) / vlm(status) / augment(status) 각각 등록 확인
    deid = client.get("/")
    vlm = client.get("/v1/videovlm/status")
    augment = client.get(STATUS_URL)
    # then
    assert deid.status_code == 200
    assert vlm.status_code == 200
    assert augment.status_code == 200
    assert augment.json()["status"] == "not_implemented"
