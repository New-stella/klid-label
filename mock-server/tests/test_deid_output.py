"""KPST 비식별화 목 — 더미 비식별 출력 파일 생성 테스트.

목 서버가 `POST /project` 시 `{export_path}/{마스킹명}` 에 "비어있지 않은" 더미 파일을
생성해, 우리 BE 의 무결성 검증(파일 존재 + 크기>0바이트, isUsableDeidFile)을 통과시키는지
검증한다.

핵심 규약:
- 마스킹 파일명 = ``{원본stem}_{yyyyMMddHHmm}_mask{확장자}`` (확장자 없으면 ``_mask`` 만).
- **단일 소스**: 데이터셋명 = 진행률 dsStatus[].fileName = 실제 생성 파일명(마스킹명)이 모두 동일.
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

# 마스킹명 형식: <stem>_<12자리 타임스탬프>_mask[.<ext>]
_MASK_RE = re.compile(r"^.+_\d{12}_mask(\.[^./\\]+)?$")


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
    # then — export 하위 유일 파일이 {stem}_{ts}_mask{ext} 형식이고 크기>0
    outputs = list(export.iterdir())
    assert len(outputs) == 1
    out = outputs[0]
    assert _MASK_RE.match(out.name), out.name
    assert out.name.startswith("a_") and out.name.endswith("_mask.mp4")
    assert out.stat().st_size > 0


def test_확장자없는_원본명은_mask만_붙는다() -> None:
    # given / when
    name = deid_sim.mask_name_from("videofolder", "202607211530")
    # then
    assert name == "videofolder_202607211530_mask"


# ── 단일 소스 교차검증: 데이터셋명 = progress fileName = 실제 생성 파일명 ──
def test_영상모드_progress_fileName이_실제생성파일명과_일치(
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
    # then — progress fileName 이 마스킹명이고 실제 파일과 정확히 일치
    assert len(file_names) == 2
    for name in file_names:
        assert _MASK_RE.match(name), name
        assert (export / name).is_file()
    stems = {n.split("_")[0] for n in file_names}
    assert stems == {"cam01", "cam02"}


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
    # then — basename(a.mp4) 기준 마스킹명, progress 와 실제 파일이 일치(어긋남 없음)
    assert len(file_names) == 1
    name = file_names[0]
    assert name.startswith("a_") and name.endswith("_mask.mp4")
    assert (export / name).is_file()
    # 슬래시 흔적이 파일명에 남지 않음
    assert "sub" not in name and "/" not in name


def test_이미지폴더모드_progress와_placeholder파일명이_일치(
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
    # then — 폴더명 기준 마스킹명(확장자 없음), progress == 실제 placeholder 파일명
    assert len(file_names) == 1
    name = file_names[0]
    assert name.startswith("imgfolder_") and name.endswith("_mask")
    assert _MASK_RE.match(name), name
    out = export / name
    assert out.is_file()
    assert out.stat().st_size > 0


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
    name = _progress_file_names(client, body["prj_id"])[0]
    # then — 마스킹명으로 원본 내용 그대로 복사
    assert name.startswith("a_") and name.endswith("_mask.mp4")
    assert (export / name).read_bytes() == b"ORIGINAL_VIDEO_BYTES_123"


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
    # then — 마스킹명 placeholder(비어있지 않음)로 생성
    name = _progress_file_names(client, body["prj_id"])[0]
    out = export / name
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
    name = _progress_file_names(client, body["prj_id"])[0]
    assert (export / name).is_file()


# ── HIGH-2 no-overwrite ───────────────────────────────────────────
def test_기존파일이_있으면_덮어쓰지_않는다(client: TestClient, tmp_path) -> None:
    # given — 마스킹명과 동일한 파일이 export 에 이미 존재
    export = tmp_path / "export"
    export.mkdir()
    inp = tmp_path / "input"
    inp.mkdir()
    (inp / "a.mp4").write_bytes(b"NEW_CONTENT_SHOULD_NOT_APPEAR")
    ts = deid_sim.mask_timestamp()
    existing_name = deid_sim.mask_name_from("a.mp4", ts)
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
    file_names = _progress_file_names(client, body["prj_id"])
    for name in file_names:
        assert _MASK_RE.match(name), name
        assert (export / name).is_file()
    # ok.mp4 는 ok_ 로 시작하는 마스킹명으로 존재
    assert any(n.startswith("ok_") for n in file_names)


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
        ("a.mp4", "a_202607211530_mask.mp4"),
        ("dir/a.mp4", "a_202607211530_mask.mp4"),
        ("../evil.mp4", "evil_202607211530_mask.mp4"),
        ("imgfolder", "imgfolder_202607211530_mask"),
        ("..", None),
        ("", None),
    ],
)
def test_mask_name_from_조립(raw, expected) -> None:
    assert deid_sim.mask_name_from(raw, "202607211530") == expected


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
