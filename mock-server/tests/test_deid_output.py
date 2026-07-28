"""KPST 비식별화 목 — 더미 비식별 출력 파일 생성 테스트.

목 서버가 `POST /project` 시 `{export_path}/{마스킹명}` 에 더미 파일을 생성해 우리 BE 의
회수(no-copy)가 성립하는지 검증한다.

핵심 규약(실서버 실측 계약 — 2026-07-21 curl/ll):
- 마스킹 파일명 = ``{원본stem}-mask{확장자}`` (하이픈, 타임스탬프 없음. 확장자 없으면 ``-mask`` 만).
- **fileName = 원본 입력파일 경로**: 진행/리포트 응답 dsStatus[].fileName 은 산출물명이 아니라
  ``{input_path}/{원본basename}`` 이다. BE 가 그 basename 을 ``{stem}-mask{ext}`` 로 바꿔 회수한다.
- **fail-closed(HIGH-1)**: MOCK_OUTPUT_BASE 미설정이면 어떤 파일도 생성하지 않는다.
- **no-overwrite(HIGH-2)**: 기존 파일은 덮어쓰지 않는다.
- 경로 순회(CWE-22) 방어 + 견고성(쓰기 실패가 200 응답에 영향 없음).

- export_path / input_path 는 pytest tmp_path 로 실제 임시 디렉터리를 사용한다.
- 설정(write_output_files / output_base)은 env override + reload_settings 로 제어한다.
- 기본 fixture 는 MOCK_OUTPUT_BASE 를 tmp_path 로 설정해 파일 생성이 열리게 한다.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.services import deid_sim

# 마스킹명 형식: <stem>-mask[.<ext>] (실서버 계약 — 타임스탬프 세그먼트 없음)
_MASK_RE = re.compile(r"^.+-mask(\.[^./\\]+)?$")


@pytest.fixture(autouse=True)
def _clean_store_and_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> Iterator[None]:
    """전역 저장소 + Settings 캐시를 매 테스트마다 초기화(격리).

    기본으로 MOCK_OUTPUT_BASE 를 tmp_path 로 설정한다 — export/input 이 tmp_path 하위이므로
    fail-closed 정책 하에서도 파일 생성이 허용된다. 개별 테스트가 필요 시 override 한다.
    """
    from app.config import reload_settings
    from app.state import get_store

    get_store().clear()
    deid_sim.reset_base_warning()
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(tmp_path))
    reload_settings()
    yield
    get_store().clear()
    deid_sim.reset_base_warning()
    reload_settings()


def _create(
    client: TestClient,
    *,
    name: str,
    export_path: str,
    input_path: str,
    files: list[str],
    is_img: int = 0,
) -> dict:
    res = client.post(
        "/project",
        json={
            "project_name": name,
            "creator": "w1",
            "export_path": export_path,
            "input_path": input_path,
            "files": files,
            "is_img": is_img,
        },
    )
    assert res.status_code == 200
    return res.json()


def _progress_file_names(client: TestClient, prj_id: int) -> list[str]:
    payload = json.dumps({"reqUserId": "w1", "prjId": prj_id}).encode("utf-8")
    res = client.request(
        "GET",
        "/retrieve_progress",
        content=payload,
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 200
    ds = res.json()["data"]["prjStatus"][0]["dsStatus"]
    return [d["fileName"] for d in ds]


def _force_complete(prj_id: int) -> None:
    """프로젝트 created_monotonic 을 과거로 밀어 진행률 100·완료로 전이시킨다(리포트 노출 조건)."""
    import time

    from app.state import get_store

    project = get_store().get_project(prj_id)
    assert project is not None
    project.created_monotonic = time.monotonic() - 100000.0


def _derived_output_names(client: TestClient, prj_id: int) -> list[str]:
    """진행조회 fileName(원본 입력 경로) → BE 와 동일한 규칙으로 산출물명을 파생한다.

    우리 BE(KpstDeidentService.toMaskName)가 하는 것과 같은 변환이다 — 이 파생 경로에 실제
    파일이 있어야 BE 의 **1차 회수 경로**가 성립한다(폴백 스캔 의존 금지).
    """
    return [deid_sim.mask_name_from(n) for n in _progress_file_names(client, prj_id)]


# ── 마스킹 파일명 규칙 ────────────────────────────────────────────
def test_영상모드_생성파일명이_마스킹규칙을_따른다(client: TestClient, tmp_path) -> None:
    # given
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then — export 하위 유일 파일이 {stem}-mask{ext} 형식이고 크기>0
    outputs = list(export.iterdir())
    assert len(outputs) == 1
    out = outputs[0]
    assert _MASK_RE.match(out.name), out.name
    assert out.name == "a-mask.mp4"
    assert out.stat().st_size > 0


def test_확장자없는_원본명은_mask만_붙는다() -> None:
    # given / when
    name = deid_sim.mask_name_from("videofolder")
    # then
    assert name == "videofolder-mask"


# ── 계약 교차검증: progress fileName = 원본 입력 경로, 산출물 = {stem}-mask{ext} ──
def test_영상모드_progress_fileName이_원본입력경로다(
    client: TestClient, tmp_path
) -> None:
    # given
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["cam01.mp4", "cam02.mp4"],
    )
    # when
    file_names = _progress_file_names(client, body["prj_id"])
    # then — fileName 은 <원본 입력파일 경로>다(산출물명 아님).
    assert len(file_names) == 2
    assert sorted(file_names) == sorted([f"{inp}/cam01.mp4", f"{inp}/cam02.mp4"])
    # 그리고 BE 가 파생하는 {stem}-mask{ext} 경로에 실제 산출물이 있다(1차 회수 경로 성립).
    for out_name in _derived_output_names(client, body["prj_id"]):
        assert _MASK_RE.match(out_name), out_name
        assert (export / out_name).is_file()


def test_슬래시포함_fileName도_progress와_실제파일명이_일치(
    client: TestClient, tmp_path
) -> None:
    # given — 슬래시 포함 입력(basename 정화 대상)
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["sub/a.mp4"],
    )
    # when
    file_names = _progress_file_names(client, body["prj_id"])
    # then — basename(a.mp4) 기준으로 정화되어 fileName 은 {input_path}/a.mp4
    assert len(file_names) == 1
    assert file_names[0] == f"{inp}/a.mp4"
    # 산출물은 a-mask.mp4 (하위 디렉터리 흔적 없음)
    out_name = _derived_output_names(client, body["prj_id"])[0]
    assert out_name == "a-mask.mp4"
    assert (export / out_name).is_file()


def test_이미지폴더모드_progress_fileName과_산출물명(
    client: TestClient, tmp_path
) -> None:
    # given — is_img=1, 폴더 basename = imgfolder
    export = tmp_path / "export"
    inp = tmp_path / "imgfolder"
    inp.mkdir()
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=[],
        is_img=1,
    )
    # when
    file_names = _progress_file_names(client, body["prj_id"])
    # then — fileName 은 폴더 경로, 산출물은 {folderbase}-mask (확장자 없음)
    assert len(file_names) == 1
    out_name = _derived_output_names(client, body["prj_id"])[0]
    assert out_name == "imgfolder-mask"
    assert _MASK_RE.match(out_name), out_name
    out = export / out_name
    assert out.is_file()
    assert out.stat().st_size > 0


# ── B-ISSUE-84: 실서버 계약 정합 ──────────────────────────────────
def test_mock_server_의_fileName_이_원본_입력_절대경로다(
    client: TestClient, tmp_path
) -> None:
    """진행조회/리포트의 fileName 은 산출물명이 아니라 원본 입력파일 경로여야 한다."""
    # given
    export = tmp_path / "export"
    inp = tmp_path / "raw" / "seed"
    inp.mkdir(parents=True)
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["clip-9101.mp4"],
    )
    # when — 진행조회 + 리포트(완료 데이터셋만 나오므로 완료로 밀어둔다)
    progress_names = _progress_file_names(client, body["prj_id"])
    _force_complete(body["prj_id"])
    payload = json.dumps({"reqUserId": "w1", "prjId": body["prj_id"]}).encode("utf-8")
    report = client.request(
        "GET",
        "/retrieve_report",
        content=payload,
        headers={"Content-Type": "application/json"},
    )
    # then — 두 응답 모두 원본 입력파일 절대경로(= input_path + 원본 basename)
    expected = f"{inp}/clip-9101.mp4"
    assert progress_names == [expected]
    assert report.status_code == 200
    report_names = [
        d["fileName"]
        for prj in report.json()["data"]["prjStatus"]
        for d in prj["dsStatus"]
    ]
    assert report_names == [expected]
    # 산출물명(마스킹명)은 fileName 과 <다르다>.
    assert (export / "clip-9101-mask.mp4").is_file()
    assert not (export / "clip-9101.mp4").exists()


def test_mock_server_산출물_파일명이_stem_hyphen_mask_ext_다(
    client: TestClient, tmp_path
) -> None:
    """산출물명은 실서버 실측대로 {stem}-mask{ext} — 언더스코어·타임스탬프 금지."""
    # given
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["001.mp4"],
    )
    # then
    outputs = [p.name for p in export.iterdir()]
    assert outputs == ["001-mask.mp4"]
    assert not re.search(r"_\d{12}_mask", outputs[0])


# ── 복사 vs placeholder ───────────────────────────────────────────
def test_input_path에_원본이_있으면_마스킹명으로_복사된다(
    client: TestClient, tmp_path
) -> None:
    # given — input_path 에 원본 파일 배치(원본명)
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    (inp / "a.mp4").write_bytes(b"ORIGINAL_VIDEO_BYTES_123")
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # when
    out_name = _derived_output_names(client, body["prj_id"])[0]
    # then — 마스킹명으로 원본 내용 그대로 복사
    assert out_name == "a-mask.mp4"
    assert (export / out_name).read_bytes() == b"ORIGINAL_VIDEO_BYTES_123"


def test_input_path에_원본이_없으면_마스킹명_placeholder로_생성(
    client: TestClient, tmp_path
) -> None:
    # given — input 디렉터리에 원본 없음
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then — 마스킹명 placeholder(비어있지 않음)로 생성.
    #   ⚠ 이 placeholder 는 BE 무결성(크기 하한 + 컨테이너 시그니처)을 <통과하지 못한다>(의도된 동작).
    out_name = _derived_output_names(client, body["prj_id"])[0]
    out = export / out_name
    assert out.is_file()
    assert out.read_bytes() == deid_sim.PLACEHOLDER_BYTES


# ── HIGH-1 fail-closed ────────────────────────────────────────────
def test_output_base_미설정이면_파일이_생기지않는다_failclosed(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — MOCK_OUTPUT_BASE 미설정(fail-closed)
    from app.config import reload_settings

    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.delenv("MOCK_OUTPUT_BASE", raising=False)
    reload_settings()
    # when — write_output_files 는 True 이지만 base 미설정 → no-op
    res = client.post(
        "/project",
        json={
            "project_name": "p1",
            "creator": "w1",
            "export_path": f"{export}/",
            "input_path": f"{inp}/",
            "files": ["a.mp4"],
        },
    )
    # then — 200 유지하되 파일/디렉터리 미생성
    assert res.status_code == 200
    assert not export.exists()


# ── HIGH-1 base 경계 검증 ─────────────────────────────────────────
def test_output_base_설정시_base밖_export_path는_쓰기스킵(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — base 로 허용 디렉터리 지정, export 는 base 밖
    from app.config import reload_settings

    base = tmp_path / "allowed"
    base.mkdir()
    export = tmp_path / "elsewhere" / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()
    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then — base 밖이므로 쓰기 스킵(파일/디렉터리 미생성)
    assert not export.exists()


def test_output_base_설정시_base안_export_path는_쓰기허용(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — export 가 base 하위
    from app.config import reload_settings

    base = tmp_path / "allowed"
    base.mkdir()
    export = base / "videos" / "10"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()
    # when
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then
    out_name = _derived_output_names(client, body["prj_id"])[0]
    assert (export / out_name).is_file()


def test_콤마구분_다중base_중_하나의_하위면_co_locate_export_path에_쓰기허용(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A-2 — BE co-locate 산출 경로({dirname(원본)}/{rawSn}/deid/)에 더미가 생성돼야 한다.

    BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값(원본 마운트 루트 + 비식별 저장소)을 base 로 주면,
    비식별 저장소가 아니라 <원본이 놓인 루트> 하위인 export_path 도 허용된다.
    """
    # given — raw/deid 두 루트를 콤마로 지정. export_path 는 raw 루트 하위(co-locate 구조).
    from app.config import reload_settings

    raw_root = tmp_path / "raw"
    deid_root = tmp_path / "deidentified"
    raw_root.mkdir()
    deid_root.mkdir()
    inp = raw_root / "seed"
    inp.mkdir()
    export = inp / "77" / "deid"  # dirname(원본)/{rawSn}/deid
    monkeypatch.setenv("MOCK_OUTPUT_BASE", f"{raw_root},{deid_root}")
    reload_settings()

    # when
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["clip-9101.mp4"],
    )

    # then — 더미 산출물이 co-locate 경로의 {stem}-mask{ext} 로 실제 생성된다(BE 1차 회수 경로).
    out_name = _derived_output_names(client, body["prj_id"])[0]
    assert out_name == "clip-9101-mask.mp4"
    assert (export / out_name).is_file()
    assert (export / out_name).stat().st_size > 0


def test_콤마구분_다중base_모두의_밖이면_쓰기스킵(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 두 base 를 지정하되 export 는 둘 다의 밖
    from app.config import reload_settings

    raw_root = tmp_path / "raw"
    deid_root = tmp_path / "deidentified"
    raw_root.mkdir()
    deid_root.mkdir()
    inp = raw_root / "seed"
    inp.mkdir()
    export = tmp_path / "elsewhere" / "77" / "deid"
    monkeypatch.setenv("MOCK_OUTPUT_BASE", f"{raw_root},{deid_root}")
    reload_settings()

    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["clip-9101.mp4"],
    )

    # then
    assert not export.exists()


def test_콤마만_있는_output_base는_failclosed(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 유효 토큰이 없는 base(콤마/공백뿐) → 미설정과 동일하게 fail-closed
    from app.config import reload_settings

    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.setenv("MOCK_OUTPUT_BASE", " , ")
    reload_settings()

    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )

    # then
    assert not export.exists()


# ── HIGH-2 no-overwrite ───────────────────────────────────────────
def test_기존파일이_있으면_덮어쓰지_않는다(client: TestClient, tmp_path) -> None:
    # given — 마스킹명과 동일한 파일이 export 에 이미 존재
    export = tmp_path / "export"
    export.mkdir()
    inp = tmp_path / "input"
    inp.mkdir()
    (inp / "a.mp4").write_bytes(b"NEW_CONTENT_SHOULD_NOT_APPEAR")
    existing_name = deid_sim.mask_name_from("a.mp4")
    existing = export / existing_name
    existing.write_bytes(b"PRESERVE_ME")
    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then — 기존 내용 보존(덮어쓰지 않음)
    assert existing.read_bytes() == b"PRESERVE_ME"


# ── MEDIUM files[] 자원 상한 ──────────────────────────────────────
def test_files_개수_상한초과_거부(client: TestClient, tmp_path) -> None:
    # given — 1001개(상한 1000 초과)
    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    # when
    res = client.post(
        "/project",
        json={
            "project_name": "p1",
            "creator": "w1",
            "export_path": f"{export}/",
            "input_path": f"{inp}/",
            "files": [f"v{i}.mp4" for i in range(1001)],
        },
    )
    # then — pydantic max_length 위반. 이 목 서버는 검증오류를 400 으로 표준화한다.
    assert res.status_code == 400


# ── 경로 순회 방어 (CWE-22) ───────────────────────────────────────
def test_fileName에_traversal이_들어와도_export_path_밖에는_안생긴다(
    client: TestClient, tmp_path
) -> None:
    # given — export_path 상위에 침범 시도
    root = tmp_path / "root"
    export = root / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    outside = root / "evil.mp4"
    # when — traversal 파일명(마스킹명 조립 전에 basename 정화됨)
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["../evil.mp4", "../../evil2.mp4", "ok.mp4"],
    )
    # then — export_path 밖(상위)에는 어떤 파일도 생기지 않음
    assert not outside.exists()
    assert not (tmp_path / "evil2.mp4").exists()
    # 정상/정화된 파일명은 모두 export 내부에 마스킹명으로 생성
    out_names = _derived_output_names(client, body["prj_id"])
    for out_name in out_names:
        assert _MASK_RE.match(out_name), out_name
        assert (export / out_name).is_file()
    # ok.mp4 는 ok-mask.mp4 로 존재
    assert "ok-mask.mp4" in out_names


# ── 토글 / 견고성 ─────────────────────────────────────────────────
def test_write_output_files_false면_파일이_생기지않는다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.config import reload_settings

    export = tmp_path / "export"
    inp = tmp_path / "input"
    inp.mkdir()
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "false")
    reload_settings()
    # when
    _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{inp}/",
        files=["a.mp4"],
    )
    # then
    assert not export.exists()


def test_export_path를_파일이_점유해도_project는_200(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — export_path 위치에 파일이 이미 존재(디렉터리 생성 불가)
    from app.config import reload_settings

    inp = tmp_path / "input"
    inp.mkdir()
    export_as_file = tmp_path / "export"
    export_as_file.write_bytes(b"occupied")
    # base 를 export 부모(tmp_path)로 유지 — 경계는 통과하지만 mkdir 이 실패
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(tmp_path))
    reload_settings()
    # when — mkdir 실패해도 예외를 삼키고 정상 응답
    res = client.post(
        "/project",
        json={
            "project_name": "p1",
            "creator": "w1",
            "export_path": f"{export_as_file}/",
            "input_path": f"{inp}/",
            "files": ["a.mp4"],
        },
    )
    # then
    assert res.status_code == 200
    assert res.json()["result"] == "success"
    # 파일은 그대로(디렉터리로 바뀌지 않음)
    assert export_as_file.is_file()


# ── 순수 헬퍼 단위 테스트 ─────────────────────────────────────────
@pytest.mark.parametrize(
    "raw,expected",
    [
        ("a.mp4", "a.mp4"),
        (" a.mp4 ", "a.mp4"),
        ("../a.mp4", "a.mp4"),  # basename 정화로 상위 탈출 제거
        ("dir/a.mp4", "a.mp4"),
        ("/foo/imgfolder/", "imgfolder"),  # 폴더 경로(끝 슬래시) → basename
        ("", None),
        ("..", None),
        (".", None),
        ("/", None),
        (None, None),
    ],
)
def test_safe_basename_정화(raw, expected) -> None:
    assert deid_sim.safe_basename(raw) == expected


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("a.mp4", "a-mask.mp4"),
        ("/nas/raw/seed/001.mp4", "001-mask.mp4"),   # 실서버 fileName(절대경로) 형태
        ("dir/a.mp4", "a-mask.mp4"),
        ("../evil.mp4", "evil-mask.mp4"),
        ("imgfolder", "imgfolder-mask"),
        ("..", None),
        ("", None),
    ],
)
def test_mask_name_from_조립(raw, expected) -> None:
    assert deid_sim.mask_name_from(raw) == expected


def test_resolve_output_dir_base미설정이면_None_failclosed(tmp_path) -> None:
    # given / when / then — base 빈 문자열이면 항상 None(fail-closed)
    assert deid_sim.resolve_output_dir(str(tmp_path / "x"), "") is None


def test_resolve_output_dir_base밖이면_None(tmp_path) -> None:
    # given
    base = tmp_path / "base"
    outside = tmp_path / "outside" / "x"
    # when / then
    assert deid_sim.resolve_output_dir(str(outside), str(base)) is None
    inside = base / "x"
    assert deid_sim.resolve_output_dir(str(inside), str(base)) is not None


# ── HIGH-1 input_path 읽기 경계 (CWE-22 / 자원 증폭) ──────────────
# 목 서버는 인증이 없어 input_path 를 임의로 지정할 수 있다. 허용 루트 밖 파일을 복사하면
# 임의 파일 노출 + GB급 반복 복사로 디스크를 고갈시킬 수 있으므로 복사 소스를 제한한다.
def test_허용루트_밖_input_path의_원본은_복사하지_않는다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — output base 는 storage 하위, 원본은 storage 밖
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "a.mp4").write_bytes(b"SECRET_OUTSIDE_BYTES")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()
    # when
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{outside}/",
        files=["a.mp4"],
    )
    # then — 복사 대신 placeholder (원본 내용이 새지 않는다)
    name = _progress_file_names(client, body["prj_id"])[0]
    assert (export / name).read_bytes() == deid_sim.PLACEHOLDER_BYTES


def test_허용루트_안_input_path의_원본은_복사된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — BE 정상 경로: raw(입력)와 deidentified(출력)가 같은 storage 루트 하위
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    (raw / "a.mp4").write_bytes(b"ORIGINAL_VIDEO_BYTES_123")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()
    # when
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{raw}/",
        files=["a.mp4"],
    )
    # then — 정상 경로는 그대로 복사돼야 한다(방어가 BE 흐름을 깨지 않음)
    name = _progress_file_names(client, body["prj_id"])[0]
    assert (export / name).read_bytes() == b"ORIGINAL_VIDEO_BYTES_123"


def test_input_base를_환경변수로_직접_지정할_수_있다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 출력 base 와 무관한 위치를 입력 루트로 명시 허용
    from app.config import reload_settings

    base = tmp_path / "out"
    base.mkdir()
    export = base / "videos" / "10"
    src_root = tmp_path / "nas"
    src_root.mkdir()
    (src_root / "a.mp4").write_bytes(b"NAS_BYTES")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    monkeypatch.setenv("MOCK_INPUT_BASE", str(src_root))
    reload_settings()
    # when
    body = _create(
        client,
        name="p1",
        export_path=f"{export}/",
        input_path=f"{src_root}/",
        files=["a.mp4"],
    )
    # then
    name = _progress_file_names(client, body["prj_id"])[0]
    assert (export / name).read_bytes() == b"NAS_BYTES"
    reload_settings()


def test_허용루트_밖_input_path는_WARN으로_구분로깅된다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — 경계 위반(보안 이벤트)과 "원본이 원래 없음"이 로그로 구분되어야 한다(OWASP A09)
    import logging

    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "a.mp4").write_bytes(b"SECRET_OUTSIDE_BYTES")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        _create(
            client,
            name="p1",
            export_path=f"{export}/",
            input_path=f"{outside}/",
            files=["a.mp4"],
        )

    # then — 경계 위반은 WARN 으로 남고, 전체 경로 평문은 남기지 않는다
    warns = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert any("boundary" in r.getMessage() for r in warns)
    assert all(str(outside) not in r.getMessage() for r in caplog.records)


def test_원본이_없을뿐이면_WARN이_아니라_INFO로_남는다(
    client: TestClient, tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — 허용 루트 안이지만 원본 파일만 없는 경우(정상 목 시나리오)
    import logging

    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        body = _create(
            client,
            name="p1",
            export_path=f"{export}/",
            input_path=f"{raw}/",
            files=["a.mp4"],
        )

    # then — placeholder 는 그대로 생성되지만 보안 이벤트(WARN)로 오인되면 안 된다
    name = _progress_file_names(client, body["prj_id"])[0]
    assert (export / name).read_bytes() == deid_sim.PLACEHOLDER_BYTES
    assert not [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert any("not readable" in r.getMessage() for r in caplog.records)


def test_resolve_input_dir_경계(tmp_path) -> None:
    # given
    storage = tmp_path / "storage"
    inside = storage / "raw"
    inside.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    # when / then
    assert deid_sim.resolve_input_dir(str(inside), str(storage)) is not None
    assert deid_sim.resolve_input_dir(str(outside), str(storage)) is None
    # base 미설정이면 제한하지 않는다(출력 base 도 없으면 애초에 파일을 쓰지 않는다)
    assert deid_sim.resolve_input_dir(str(outside), "") is not None
