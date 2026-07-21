"""KPST 비식별화 벤더 목 — Phase 2 엔드포인트 11종 테스트.

상태ful 진행 시뮬레이션은 전역 InMemoryStore(get_store) 를 공유하므로 매 테스트
전후로 초기화한다. 완료 상태 전이는 프로젝트의 created_monotonic 을 과거로 조작해
경과초를 강제(진행률 100·완료)로 만들어 검증한다.
"""

from __future__ import annotations

import json
import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient


@pytest.fixture(autouse=True)
def _clean_store() -> Iterator[None]:
    """전역 저장소를 매 테스트마다 초기화(격리)."""
    from app.state import get_store

    get_store().clear()
    yield
    get_store().clear()


def _get_with_body(client: TestClient, path: str, body: dict) -> "object":
    """우리 BE 호출 방식 재현 — GET + JSON 바디(Content-Length 프레이밍)."""
    payload = json.dumps(body).encode("utf-8")
    return client.request(
        "GET", path, content=payload, headers={"Content-Type": "application/json"}
    )


def _force_complete(prj_id: int) -> None:
    """프로젝트 created_monotonic 을 과거로 밀어 진행률 100·완료로 전이시킨다."""
    from app.state import get_store

    project = get_store().get_project(prj_id)
    assert project is not None
    project.created_monotonic = time.monotonic() - 100000.0


def _create_video_project(client: TestClient, name: str = "prj1", **over: object) -> dict:
    body = {
        "project_name": name,
        "creator": "worker1",
        "export_path": "/nas/export/",
        "input_path": "/nas/input/",
        "files": ["a.mp4", "b.mp4"],
        "masking_type": 0,
        "is_img": 0,
    }
    body.update(over)
    res = client.post("/project", json=body)
    return res.json()


# ── GET / ────────────────────────────────────────────────────────
def test_connect_는_Connect_텍스트를_200으로_반환(client: TestClient) -> None:
    # given / when
    res = client.get("/")
    # then
    assert res.status_code == 200
    assert res.text == "Connect"


# ── POST /project ────────────────────────────────────────────────
def test_project_생성은_prj_id_발급하고_success(client: TestClient) -> None:
    # given / when
    res = client.post(
        "/project",
        json={
            "project_name": "p1",
            "creator": "w1",
            "export_path": "/e/",
            "input_path": "/i/",
            "files": ["a.mp4"],
        },
    )
    # then
    assert res.status_code == 200
    body = res.json()
    assert body["result"] == "success"
    assert isinstance(body["prj_id"], int)
    assert body["prj_id"] >= 1


def test_project_필수필드_project_name_누락시_400(client: TestClient) -> None:
    # given / when — project_name 없음
    res = client.post(
        "/project",
        json={"creator": "w1", "export_path": "/e/", "input_path": "/i/", "files": ["a.mp4"]},
    )
    # then
    assert res.status_code == 400


def test_동일_project_name_재생성시_409(client: TestClient) -> None:
    # given
    _create_video_project(client, "dup")
    # when
    res = client.post(
        "/project",
        json={
            "project_name": "dup",
            "creator": "w1",
            "export_path": "/e/",
            "input_path": "/i/",
            "files": ["a.mp4"],
        },
    )
    # then
    assert res.status_code == 409


def test_영상모드_files_3개면_데이터셋_3개_등록(client: TestClient) -> None:
    # given
    body = _create_video_project(
        client, "vid3", files=["a.mp4", "b.mp4", "c.mp4"]
    )
    prj_id = body["prj_id"]
    from app.state import get_store

    # when
    datasets = get_store().datasets_of(prj_id)
    # then
    assert len(datasets) == 3


def test_영상모드_files_없으면_400(client: TestClient) -> None:
    # given / when — is_img=0 인데 files 비어있음
    res = client.post(
        "/project",
        json={
            "project_name": "novid",
            "creator": "w1",
            "export_path": "/e/",
            "input_path": "/i/",
            "files": [],
            "is_img": 0,
        },
    )
    # then
    assert res.status_code == 400


def test_이미지폴더모드는_데이터셋_1개(client: TestClient) -> None:
    # given / when — is_img=1, files 불필요
    body = client.post(
        "/project",
        json={
            "project_name": "imgfolder",
            "creator": "w1",
            "export_path": "/e/",
            "input_path": "/i/",
            "is_img": 1,
        },
    ).json()
    from app.state import get_store

    # then
    assert len(get_store().datasets_of(body["prj_id"])) == 1


# ── GET /retrieve_progress (GET + JSON 바디) ──────────────────────
def test_retrieve_progress_는_GET바디_JSON필터를_수용(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "poll")
    prj_id = prj["prj_id"]
    # when — 우리 BE 방식: GET + JSON 바디(reqUserId + prjId)
    res = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1", "prjId": prj_id})
    # then
    assert res.status_code == 200
    data = res.json()["data"]
    assert data["prjCount"] == 1
    assert data["prjStatus"][0]["prjId"] == prj_id
    assert 0.0 <= data["prjStatus"][0]["progressRate"] < 100.0


def test_retrieve_progress_reqUserId_누락시_400(client: TestClient) -> None:
    # given
    _create_video_project(client, "p")
    # when — reqUserId 없음
    res = _get_with_body(client, "/retrieve_progress", {"prjId": 1})
    # then
    assert res.status_code == 400


def test_retrieve_progress_필터_reqUserId뿐이면_400(client: TestClient) -> None:
    # given
    _create_video_project(client, "p")
    # when — reqUserId 외 필터 전무
    res = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1"})
    # then
    assert res.status_code == 400


def test_retrieve_progress_미존재_prjId_404(client: TestClient) -> None:
    # given
    _create_video_project(client, "p")
    # when
    res = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1", "prjId": 9999})
    # then
    assert res.status_code == 404


def test_retrieve_progress_충분경과후_완료전이(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "done")
    prj_id = prj["prj_id"]
    _force_complete(prj_id)
    # when
    res = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1", "prjId": prj_id})
    # then
    ps = res.json()["data"]["prjStatus"][0]
    assert ps["progressRate"] == 100.0
    assert ps["prjState"] == 3  # 완료
    assert ps["dsStatus"][0]["procState"] == 2  # 완료


# ── DELETE (POST) ────────────────────────────────────────────────
def test_delete_project_id_후_progress_404(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "del")
    prj_id = prj["prj_id"]
    # when
    res = client.post("/delete_project_id", json={"project_id": prj_id, "user_id": "w1"})
    # then
    assert res.status_code == 200
    assert res.json()["result"] == "success"
    poll = _get_with_body(client, "/retrieve_progress", {"reqUserId": "w1", "prjId": prj_id})
    assert poll.status_code == 404


def test_delete_project_id_필수누락_400(client: TestClient) -> None:
    # given / when — project_id 없음
    res = client.post("/delete_project_id", json={"user_id": "w1"})
    # then
    assert res.status_code == 400


def test_delete_project_name_으로_삭제(client: TestClient) -> None:
    # given
    _create_video_project(client, "delname")
    # when
    res = client.post(
        "/delete_project_name", json={"project_name": "delname", "user_id": "w1"}
    )
    # then
    assert res.status_code == 200
    assert res.json()["result"] == "success"
    from app.state import get_store

    assert get_store().get_project_by_name("delname") is None


# ── GET /retrieve_report ─────────────────────────────────────────
def test_retrieve_report_완료데이터셋만_반환하고_미완료프로젝트_제외(client: TestClient) -> None:
    # given — done 은 완료 전이, pending 은 미완료
    done = _create_video_project(client, "done", files=["x.mp4"])
    _create_video_project(client, "pending", files=["y.mp4"])
    _force_complete(done["prj_id"])
    # when
    res = _get_with_body(client, "/retrieve_report", {"reqUserId": "w1"})
    # then
    assert res.status_code == 200
    prj_status = res.json()["data"]["prjStatus"]
    assert res.json()["data"]["prjCount"] == 1
    ds = prj_status[0]["dsStatus"][0]
    assert "faceCount" in ds and "lpCount" in ds
    assert ds["faceCount"] >= 0 and ds["lpCount"] >= 0


def test_retrieve_report_reqUserId_누락_400(client: TestClient) -> None:
    # given / when
    res = _get_with_body(client, "/retrieve_report", {})
    # then
    assert res.status_code == 400


# ── GET /manual_deid_info ────────────────────────────────────────
def test_manual_deid_info_는_db_save1_프로젝트를_반환(client: TestClient) -> None:
    # given — db_save=1 하나, db_save=0 하나
    m = _create_video_project(client, "manual", db_save=1)
    _create_video_project(client, "auto", db_save=0)
    # when
    res = client.get("/manual_deid_info")
    # then
    assert res.status_code == 200
    data = res.json()["data"]
    assert len(data) == 1
    assert data[0]["project_id"] == m["prj_id"]
    assert len(data[0]["datasets"]) >= 1
    assert "masking_table_name" in data[0]["datasets"][0]


def test_manual_deid_info_대상없으면_404(client: TestClient) -> None:
    # given — db_save=1 프로젝트 없음
    _create_video_project(client, "auto", db_save=0)
    # when / then
    assert client.get("/manual_deid_info").status_code == 404


def test_manual_deid_info_id_name_목록이_정합(client: TestClient) -> None:
    # given
    m = _create_video_project(client, "manual", db_save=1)
    # when
    ids = client.get("/manual_deid_info/project_id").json()["ids"]
    names = client.get("/manual_deid_info/project_name").json()["names"]
    # then
    assert m["prj_id"] in ids
    assert "manual" in names


# ── GET /dataset_frames ──────────────────────────────────────────
def test_dataset_frames_dataset_id_누락_400(client: TestClient) -> None:
    # given / when
    res = client.get("/dataset_frames")
    # then
    assert res.status_code == 400


def test_dataset_frames_미존재_dataset_404(client: TestClient) -> None:
    # given / when
    res = client.get("/dataset_frames", params={"dataset_id": 9999})
    # then
    assert res.status_code == 404


def test_dataset_frames_정상시_data반환(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "frames", files=["a.mp4"])
    from app.state import get_store

    ds_id = get_store().datasets_of(prj["prj_id"])[0].dataset_id
    # when
    res = client.get("/dataset_frames", params={"dataset_id": ds_id})
    # then
    assert res.status_code == 200
    data = res.json()["data"]
    assert isinstance(data, list) and len(data) >= 1
    assert "frame_no" in data[0] and "bbox" in data[0]


def test_dataset_frames_범위_frame_no_파싱(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "frames2", files=["a.mp4"])
    from app.state import get_store

    ds_id = get_store().datasets_of(prj["prj_id"])[0].dataset_id
    # when — 범위 '100-105'
    res = client.get("/dataset_frames", params={"dataset_id": ds_id, "frame_no": "100-105"})
    # then
    assert res.status_code == 200
    frame_nos = [f["frame_no"] for f in res.json()["data"]]
    assert all(100 <= n <= 105 for n in frame_nos)


def test_dataset_frames_잘못된_frame_no_400(client: TestClient) -> None:
    # given
    prj = _create_video_project(client, "frames3", files=["a.mp4"])
    from app.state import get_store

    ds_id = get_store().datasets_of(prj["prj_id"])[0].dataset_id
    # when
    res = client.get("/dataset_frames", params={"dataset_id": ds_id, "frame_no": "abc"})
    # then
    assert res.status_code == 400


# ── GET /retrieve_job_logs ───────────────────────────────────────
def test_job_logs_start_time만있으면_시간필터_무시(client: TestClient) -> None:
    # given
    _create_video_project(client, "logs", files=["a.mp4"])
    # when — start_time 만 (end_time 없음) → 시간필터 무시하고 조회
    res = client.get("/retrieve_job_logs", params={"start_time": "2020-01-01 00:00:00"})
    # then
    assert res.status_code == 200
    assert len(res.json()["data"]) >= 1


def test_job_logs_잘못된_시간형식_400(client: TestClient) -> None:
    # given
    _create_video_project(client, "logs2", files=["a.mp4"])
    # when — 양쪽 시간 지정하되 형식 오류
    res = client.get(
        "/retrieve_job_logs",
        params={"start_time": "not-a-date", "end_time": "2020-01-01 00:00:00"},
    )
    # then
    assert res.status_code == 400


def test_job_logs_결과없으면_404(client: TestClient) -> None:
    # given — 프로젝트 없음
    # when
    res = client.get("/retrieve_job_logs", params={"user_id": "nobody"})
    # then
    assert res.status_code == 404
