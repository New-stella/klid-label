"""관제지원시스템 inbound 통지 목 라우터 테스트 (API-251 / API-285).

우리 BE 의 자기치유 규칙(409 completed→updated / 404 updated→completed)이 실제 HTTP 왕복으로
검증되려면 목이 아래를 정확히 지켜야 한다:
- notify-completed 최초 202 + dataset_version_id
- 동일 job_id 재호출 409 (관제 datasets.job_id UNIQUE 모사)
- notify-updated 는 선행 completed 없으면 404
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

BASE = "/api/data-set/v2/jobs"


@pytest.fixture(autouse=True)
def _reset_registry() -> None:
    """테스트 간 등록 상태 격리."""
    from app.routers.control import get_registry

    get_registry().clear()


def completed_url(job_id: str) -> str:
    return f"{BASE}/{job_id}/notify-completed"


def updated_url(job_id: str) -> str:
    return f"{BASE}/{job_id}/notify-updated"


def completed_body(job_id: str = "26") -> dict:
    return {
        "job_id": job_id,
        "event_type_cd": "FIRE",
        "lclgv_cd": "11680",
        "lclgv_nm": "서울특별시 강남구",
        "duration_sec": 30,
        "image_count": 16,
    }


def updated_body(job_id: str = "26") -> dict:
    return {
        "job_id": job_id,
        "changed_items": {"images": ["0000.jpg"], "jsons": ["0000.json"]},
    }


def test_notify_completed_최초등록은_202와_dataset_version_id를_반환(client: TestClient) -> None:
    # given / when
    res = client.post(completed_url("26"), json=completed_body())

    # then
    assert res.status_code == 202
    assert res.json()["dataset_version_id"]


def test_동일_job_id_로_notify_completed_재호출시_409(client: TestClient) -> None:
    # given — 이미 등록된 job
    assert client.post(completed_url("26"), json=completed_body()).status_code == 202

    # when — 재승인으로 같은 job_id 가 다시 온다
    res = client.post(completed_url("26"), json=completed_body())

    # then — 관제 datasets.job_id UNIQUE 제약 모사
    assert res.status_code == 409
    assert res.json()["error_code"] == "DUPLICATE_JOB"


def test_선행_completed_없이_notify_updated_는_404(client: TestClient) -> None:
    # given / when — 완료 통지가 없는 job 에 수정 통지
    res = client.post(updated_url("999"), json=updated_body("999"))

    # then
    assert res.status_code == 404
    assert res.json()["error_code"] == "JOB_NOT_FOUND"


def test_선행_completed_후_notify_updated_는_202(client: TestClient) -> None:
    # given
    assert client.post(completed_url("26"), json=completed_body()).status_code == 202

    # when
    res = client.post(updated_url("26"), json=updated_body())

    # then
    assert res.status_code == 202
    assert res.json()["dataset_version_id"]


def test_notify_updated_는_여러번_호출해도_202(client: TestClient) -> None:
    # given
    client.post(completed_url("26"), json=completed_body())

    # when / then — 수정 통지는 멱등하게 계속 수용된다(마지막 상태로 갱신)
    for _ in range(3):
        assert client.post(updated_url("26"), json=updated_body()).status_code == 202


def test_job_id_가_숫자가_아니면_400(client: TestClient) -> None:
    # given / when — 경로 세그먼트 조작 시도(CWE-20/22)
    res = client.post(completed_url("26abc"), json=completed_body())

    # then
    assert res.status_code == 400
    assert res.json()["error_code"] == "INVALID_JOB_ID"
    assert "Traceback" not in res.json()["message"]


def test_reset_후에는_같은_job_id_가_다시_202(client: TestClient) -> None:
    # given
    client.post(completed_url("26"), json=completed_body())

    # when
    assert client.post(f"{BASE}/_reset").status_code == 200

    # then
    assert client.post(completed_url("26"), json=completed_body()).status_code == 202


def test_등록현황_조회로_통지_도달을_확인할_수_있다(client: TestClient) -> None:
    # given
    client.post(completed_url("26"), json=completed_body())
    client.post(updated_url("26"), json=updated_body())

    # when
    res = client.get(BASE)

    # then
    assert res.status_code == 200
    jobs = res.json()["jobs"]
    assert jobs["26"]["update_count"] == 1


def test_본문이_없어도_통지는_수용된다(client: TestClient) -> None:
    # given / when — 목은 스키마를 강제하지 않는다(계약 검증은 BE 테스트 소관)
    res = client.post(completed_url("26"))

    # then
    assert res.status_code == 202
