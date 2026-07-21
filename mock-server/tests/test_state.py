"""인메모리 상태 저장소 단위 테스트."""

from __future__ import annotations

import pytest

from app.state import DuplicateProjectError, InMemoryStore, elapsed_progress


def test_create_project가_자동증가_prj_id를_발급(store: InMemoryStore) -> None:
    # given / when
    p1 = store.create_project("alpha")
    p2 = store.create_project("beta")
    # then — 1부터 순차 증가
    assert p1.prj_id == 1
    assert p2.prj_id == 2
    assert p1.project_name == "alpha"


def test_동일_project_name_중복생성시_예외(store: InMemoryStore) -> None:
    # given
    store.create_project("dup")
    # when / then
    with pytest.raises(DuplicateProjectError):
        store.create_project("dup")


def test_삭제_by_id_후_조회하면_없다(store: InMemoryStore) -> None:
    # given
    p = store.create_project("gamma")
    # when
    deleted = store.delete_project(p.prj_id)
    # then
    assert deleted is True
    assert store.get_project(p.prj_id) is None


def test_삭제_by_name으로도_제거된다(store: InMemoryStore) -> None:
    # given
    store.create_project("delta")
    # when
    deleted = store.delete_project_by_name("delta")
    # then
    assert deleted is True
    assert store.get_project_by_name("delta") is None


def test_없는_id_삭제는_False를_반환(store: InMemoryStore) -> None:
    # given / when / then — 예외 없이 안전 기본값
    assert store.delete_project(9999) is False


def test_list_projects는_생성순서를_반환(store: InMemoryStore) -> None:
    # given
    store.create_project("a")
    store.create_project("b")
    # when
    names = [p.project_name for p in store.list_projects()]
    # then
    assert names == ["a", "b"]


def test_삭제후_동일이름_재생성_가능(store: InMemoryStore) -> None:
    # given
    p1 = store.create_project("reuse")
    store.delete_project(p1.prj_id)
    # when — 이름 인덱스가 갱신되어 재생성 허용
    p2 = store.create_project("reuse")
    # then
    assert p2.project_name == "reuse"
    assert p2.prj_id != p1.prj_id


def test_project_name에_CRLF가_있어도_안전하게_저장(store: InMemoryStore) -> None:
    # given / when — 로그 인젝션 방지용 sanitize는 로깅 경로에서만, 저장은 원문 유지하되 예외 없이
    p = store.create_project("evil\r\ninjected")
    # then — 생성은 예외 없이 성공하고 조회 가능
    assert store.get_project(p.prj_id) is not None


def test_elapsed_progress는_경과초가_커질수록_단조증가하고_상한100(store: InMemoryStore) -> None:
    # given
    created = 100.0
    speed = 1.0
    # when
    r0 = elapsed_progress(created, created, speed)
    r_mid = elapsed_progress(created, created + 3.0, speed)
    r_more = elapsed_progress(created, created + 6.0, speed)
    r_max = elapsed_progress(created, created + 100000.0, speed)
    # then — 단조 증가 + 상한 100
    assert r0.progress_rate == 0.0
    assert 0.0 < r_mid.progress_rate < r_more.progress_rate
    assert r_max.progress_rate == 100.0


def test_elapsed_progress_상태전이_PENDING_PROCESSING_COMPLETED() -> None:
    # given
    created = 0.0
    # when
    at_start = elapsed_progress(created, 0.0, 1.0)
    mid = elapsed_progress(created, 1.0, 1.0)
    done = elapsed_progress(created, 99999.0, 1.0)
    # then
    assert at_start.state == "PENDING"
    assert mid.state == "PROCESSING"
    assert done.state == "COMPLETED"


def test_elapsed_progress_음수_경과는_0으로_클램프() -> None:
    # given / when — monotonic 특성상 발생하지 않지만 방어
    r = elapsed_progress(100.0, 50.0, 1.0)
    # then
    assert r.progress_rate == 0.0
    assert r.state == "PENDING"


def test_speed_배속이_클수록_같은시간에_더_진행() -> None:
    # given / when
    slow = elapsed_progress(0.0, 2.0, 1.0)
    fast = elapsed_progress(0.0, 2.0, 5.0)
    # then
    assert fast.progress_rate > slow.progress_rate
