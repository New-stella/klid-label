"""KPST 비식별화 목 — 산출 영상 '비식별 완료' 워터마크 굽기 테스트.

목 서버는 지금까지 원본을 <b>바이트 그대로 복사</b>만 해서, 라벨링 화면에서 재생해도
"이게 비식별 처리된 영상인가"를 육안으로 구분할 수 없었다. 이 모듈은 산출물의 모든 프레임
우측하단에 고정 문구(``비식별 완료``)를 ffmpeg drawtext 로 실제로 굽는 동작을 검증한다.

핵심 규약:
- **성공 경로**: 실제 영상 원본이면 워터마크가 구워진 <b>새 영상</b>이 산출된다(원본과 바이트 상이).
- **무손실**: 산출물은 원본과 <b>같은 재생 길이</b>여야 한다. 길이가 다르면(절단) 승격하지 않고
  원본을 그대로 복사한다 — "잘라서 내보내기" 금지(#1/#3, CWE-345/754).
- **승격 게이트**: 우리 BE(``DeidentArtifactIntegrity``)와 <b>같은 기준</b>(≥512B + 컨테이너
  시그니처)을 통과한 산출물만 최종 배치한다(#4 비대칭 제거).
- **폴백**: ffmpeg 부재/폰트 부재/실행 실패/타임아웃/게이트 탈락 → ``_copy_no_overwrite`` 로 자동
  전환하고 WARN 을 남긴다. 워터마킹 실패가 ``POST /project`` 실패로 번지면 안 된다.
- **원자성**: 임시파일(uuid 포함)에 출력 후 성공 시에만 최종 경로에 배치. 실패 시 잔여물 없음.
  <b>복사 폴백도 같은 은닉 임시경로 → 원자 배치</b>를 거친다(#3 — SIGKILL 에서도 잘린 파일이
  최종 경로에 남지 않는다).
- **자원**: 요청당 워터마킹 <b>건수</b> 상한 + 복사 바이트 예산. <b>시간 예산 축은 없다</b>
  (#1/#2 — 접수가 비동기가 되어 45초 HTTP 예산이라는 전제가 사라졌다).
- **보안(CWE-78)**: ``subprocess.run`` 리스트 인자 + ``shell=False``, drawtext 텍스트는 고정 문자열.
- **보안(CWE-22/59/TOCTOU)**: subprocess 호출 직전 허용 입출력 루트 재검증, sweep 은 심링크 미추종.

ffmpeg 바이너리가 없는 환경에서는 실제 인코딩 테스트만 skip 되고, 폴백/보안 테스트는 모두 실행된다.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import time
from pathlib import Path

import pytest

from app.services import deid_sim


@pytest.fixture(autouse=True)
def _watermark_on(monkeypatch: pytest.MonkeyPatch):
    """이 파일은 <b>워터마크 경로 자체</b>를 검증하므로 그 기능을 켜고 돈다.

    ★ 운영 기본값은 <b>꺼짐</b>이다(2026-09-05 사용자 확정) — 실제 비식별 엔진이 산출물을
    만들게 된 뒤로 'MOCK 비식별 완료' 문구는 필요 없어졌다. 그래도 이 테스트들을 남기는 이유는
    폴백 경로의 <b>보안·경계 계약</b>(shell 미사용 · 경로순회 차단 · 임시파일 격리 · 길이 검증)이
    여전히 유효하고, 설정을 다시 켤 때 그것들이 지켜지는지 확인할 근거가 필요하기 때문이다.
    """
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_DEID_WATERMARK_ENABLED", "true")
    reload_settings()
    yield
    monkeypatch.delenv("MOCK_DEID_WATERMARK_ENABLED", raising=False)
    reload_settings()

_FFMPEG = shutil.which("ffmpeg")


def _has_drawtext() -> bool:
    """설치된 ffmpeg 빌드가 drawtext(libfreetype)를 포함하는지."""
    if _FFMPEG is None:
        return False
    res = subprocess.run(
        [_FFMPEG, "-hide_banner", "-loglevel", "error", "-filters"],
        capture_output=True, timeout=30, check=False,
    )
    return res.returncode == 0 and b" drawtext " in res.stdout


def _has_korean_font() -> bool:
    return any(os.path.isfile(p) for p in deid_sim.WATERMARK_FONT_CANDIDATES)


# 실제 인코딩 검증은 drawtext 를 포함한 ffmpeg + 한글 폰트가 있어야 성립한다.
# (컨테이너 이미지에는 둘 다 설치된다 — Dockerfile 의 ffmpeg + fonts-nanum)
requires_ffmpeg = pytest.mark.skipif(
    not (_has_drawtext() and _has_korean_font()),
    reason="drawtext 지원 ffmpeg + 한글 폰트가 없는 환경 — 실제 인코딩 검증 skip",
)

_FAKE_FFMPEG = "/usr/bin/ffmpeg"


@pytest.fixture(autouse=True)
def _reset_capability_cache():
    """ffmpeg 기능 탐지 캐시를 테스트 간 격리한다."""
    deid_sim.reset_ffmpeg_capability_cache()
    yield
    deid_sim.reset_ffmpeg_capability_cache()


_REAL_WHICH = shutil.which


def _fake_ffmpeg_available(monkeypatch: pytest.MonkeyPatch) -> None:
    """ffmpeg 존재 + drawtext 지원을 가정한다(기능 탐지 subprocess 호출 없이).

    ⚠ ``deid_sim.shutil`` 과 ``media_probe.shutil`` 은 <b>같은 모듈 객체</b>다. ``which`` 를
    무조건 가짜 ffmpeg 로 바꾸면 길이 검증의 ``which("ffprobe")`` 까지 가짜 경로를 받아
    검증 경로가 통째로 무력화된다 — ffmpeg 조회만 바꾼다.
    """
    monkeypatch.setattr(
        deid_sim.shutil,
        "which",
        lambda name: _FAKE_FFMPEG if name == "ffmpeg" else _REAL_WHICH(name),
    )
    monkeypatch.setitem(deid_sim._DRAWTEXT_SUPPORT, _FAKE_FFMPEG, True)


def _fake_durations(monkeypatch: pytest.MonkeyPatch, seconds: float = 10.0) -> None:
    """길이 조회를 고정값으로 대체한다(합성 산출물로 <b>승격 경로</b>를 태우기 위함).

    #4 이후 길이 검증은 <b>fail-closed</b> 다 — 원본/산출물 길이를 확인할 수 없으면 승격하지
    않고 원본 복사로 폴백한다. 그래서 ``ftyp`` 헤더만 흉내낸 합성 바이트로 인코딩 성공 경로를
    검증하려면 길이 조회를 명시적으로 스텁해야 한다. 실영상 테스트(``requires_ffmpeg``)는
    이 스텁 없이 실제 ffprobe 로 검증한다.
    """
    monkeypatch.setattr(deid_sim, "_duration_of", lambda *_a, **_k: seconds)


def _await_production(prj_id: int, timeout: float = 60.0) -> str:
    """★ 비동기 접수 모델 — 라우터 경유 테스트는 <b>백그라운드 산출 종료</b>를 기다려야 한다.

    ``POST /project`` 는 접수만 하고 즉시 반환하므로(구속 원칙), 응답 직후에는 산출물이 아직
    없는 것이 정상이다. 산출 종결을 기다린 뒤 파일을 단정한다.
    """
    state = deid_sim.wait_for_production(prj_id, timeout=timeout)
    assert state in ("SUCCEEDED", "FAILED"), f"산출이 종결되지 않았다(state={state})"
    return state


# 워터마크가 그려지는 영역(우측하단) / 그려지지 않아야 하는 영역(좌측상단) 마진(px)
_CORNER_W = 200
_CORNER_H = 60


# ── 테스트 헬퍼 ───────────────────────────────────────────────────
def _make_black_video(target: Path, *, width: int = 320, height: int = 240,
                      with_audio: bool = False, duration: int = 1,
                      fps: int = 5) -> Path:
    """검정 화면 테스트 영상을 만든다 — 워터마크 픽셀만 0이 아니게 되어 검증이 결정적이다.

    ``duration`` 을 길게 잡아도 ``fps`` 를 낮추면 프레임 수가 적어 인코딩이 빠르다
    (600초 × 1fps = 600프레임, 64x64 기준 1초 미만). 절단 결함은 <b>재생 길이</b>로만
    재현되므로 이 조합이 필요하다 — 1초짜리 테스트 영상으로는 절단 경로에 도달조차 못 한다.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        _FFMPEG, "-v", "error", "-n",
        "-f", "lavfi", "-i", f"color=c=black:s={width}x{height}:d={duration}:r={fps}",
    ]
    if with_audio:
        cmd += ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-shortest",
                "-c:a", "aac"]
    cmd += ["-c:v", "libx264", "-pix_fmt", "yuv420p", str(target)]
    subprocess.run(cmd, capture_output=True, timeout=180, check=True)
    return target


def _probe_duration(video: Path) -> float:
    """ffprobe 로 재생 길이(초)를 읽는다(테스트 검증용 — 프로덕션 경로와 독립)."""
    res = subprocess.run(
        [shutil.which("ffprobe") or "ffprobe", "-v", "error",
         "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
        capture_output=True, timeout=60, check=True,
    )
    return float(res.stdout.strip())


def _valid_artifact_bytes(size: int = 1024) -> bytes:
    """BE 무결성 게이트(≥512B + ``ftyp``)를 통과하는 <b>산출물 흉내</b> 바이트.

    실제 인코딩 없이 프로덕션의 승격 경로를 태우기 위한 최소 형태다. 24바이트 같은 값을 쓰면
    승격 게이트에서 걸려 폴백으로 빠지므로(그게 #4 결함의 실체다) 테스트가 프로덕션이 아니라
    헬퍼 동작을 검증하게 된다.
    """
    body = b"\x00\x00\x00\x18ftypmp42"
    return body + b"\x00" * max(0, size - len(body))


def _first_frame_gray(video: Path, width: int, height: int) -> bytes:
    """영상의 첫 프레임을 8bit gray 원시 픽셀로 뽑는다."""
    res = subprocess.run(
        [_FFMPEG, "-v", "error", "-i", str(video), "-frames:v", "1",
         "-pix_fmt", "gray", "-f", "rawvideo", "-"],
        capture_output=True, timeout=60, check=True,
    )
    assert len(res.stdout) >= width * height
    return res.stdout


def _region_max(pixels: bytes, width: int, x0: int, y0: int, x1: int, y1: int) -> int:
    """[x0,x1)×[y0,y1) 영역의 최대 밝기."""
    best = 0
    for y in range(y0, y1):
        row = pixels[y * width : y * width + width]
        best = max(best, max(row[x0:x1]))
    return best


def _leftover(out_dir: Path) -> list[str]:
    """출력 디렉터리의 파일 잔여물 목록 — 은닉 임시 디렉터리(.mock-tmp) 내부까지 포함한다.

    임시 산출물은 BE 스캔 사정권 밖(은닉 서브디렉터리)에 만들어지므로, "잔여물 없음"을
    단언하려면 그 안까지 봐야 한다. 빈 은닉 디렉터리 자체는 잔여물이 아니다(BE 는 정규 파일만 본다).
    """
    names = [p.name for p in out_dir.iterdir() if p.is_file()]
    temp_dir = out_dir / deid_sim.TEMP_DIR_NAME
    if temp_dir.is_dir():
        names += [f"{deid_sim.TEMP_DIR_NAME}/{p.name}" for p in temp_dir.iterdir()]
    return sorted(names)


def _burn(src: Path, target: Path, *, in_dir: Path, out_dir: Path) -> bool:
    """워터마크 굽기 직접 호출(단위 테스트용 얇은 래퍼).

    프로덕션 경로(``write_deid_outputs``)와 동일하게 출력 측 재검증 입력(``export_path`` /
    ``output_base``)까지 넘겨 LOW-1 대칭 재검증이 실제로 실행되게 한다.
    """
    return deid_sim._burn_deid_watermark(
        src, target, input_path=str(in_dir), input_base=str(in_dir.parent),
        output_dir=out_dir, export_path=str(out_dir), output_base=str(out_dir.parent),
    )


# ── 성공 경로 (실제 인코딩) ───────────────────────────────────────
@requires_ffmpeg
def test_영상원본은_워터마크가_구워져_원본과_다른_영상이_산출된다(tmp_path) -> None:
    # given — 허용 루트 안의 실제 영상 원본
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "a.mp4")
    target = out_dir / "a-mask.mp4"

    # when
    ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 산출물이 생기고, 원본 바이트와 다르며, 재생 가능한 영상이다
    assert ok is True
    assert target.is_file()
    assert target.read_bytes() != src.read_bytes()
    _first_frame_gray(target, 320, 240)
    # 그리고 BE 무결성 판정(DeidentArtifactIntegrity: 512바이트 이상 + 컨테이너 시그니처)을 통과한다
    head = target.read_bytes()[:12]
    assert target.stat().st_size >= 512
    assert head[4:8] == b"ftyp"


@requires_ffmpeg
def test_워터마크는_프레임_우측하단에_그려진다(tmp_path) -> None:
    # given — 전체가 검정인 원본(워터마크 외에는 밝은 픽셀이 없다)
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "a.mp4")
    target = out_dir / "a-mask.mp4"

    # when
    assert _burn(src, target, in_dir=in_dir, out_dir=out_dir) is True

    # then — 우측하단에는 밝은 픽셀(텍스트/박스)이 있고 좌측상단은 검정 그대로다
    pixels = _first_frame_gray(target, 320, 240)
    bottom_right = _region_max(pixels, 320, 320 - _CORNER_W, 240 - _CORNER_H, 320, 240)
    top_left = _region_max(pixels, 320, 0, 0, _CORNER_W, _CORNER_H)
    assert bottom_right > 40, f"우측하단 워터마크 미검출(max={bottom_right})"
    assert top_left < 20, f"좌측상단이 오염됨(max={top_left})"


@requires_ffmpeg
def test_오디오가_없는_영상도_워터마킹에_성공한다(tmp_path) -> None:
    # given — 오디오 스트림이 없는 영상(-map 0:a? 가 optional 이어야 한다)
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "noaudio.mp4", with_audio=False)
    target = out_dir / "noaudio-mask.mp4"

    # when / then
    assert _burn(src, target, in_dir=in_dir, out_dir=out_dir) is True
    assert target.stat().st_size > 0


@requires_ffmpeg
def test_초저해상도_영상도_워터마킹에_성공한다(tmp_path) -> None:
    # given — 경계값: 폰트/여백이 프레임보다 크면 실패할 수 있는 크기
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "tiny.mp4", width=128, height=96)
    target = out_dir / "tiny-mask.mp4"

    # when / then — 실패하더라도 예외 없이 False 로 폴백 신호를 주어야 한다
    ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)
    assert ok is True
    assert target.stat().st_size > 0


@requires_ffmpeg
def test_라우터경유_영상원본은_워터마크가_구워진_산출물이_된다(
    client, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — POST /project e2e (허용 루트 안 실제 영상)
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    src = _make_black_video(raw / "a.mp4")
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    res = client.post(
        "/project",
        json={
            "project_name": "wm1", "creator": "w1",
            "export_path": f"{export}/", "input_path": f"{raw}/", "files": ["a.mp4"],
        },
    )

    # then — 접수 후 백그라운드 산출이 끝나면 워터마크가 구워진 새 영상이 남는다
    assert res.status_code == 200
    _await_production(res.json()["prj_id"])
    out = export / "a-mask.mp4"
    assert out.is_file()
    assert out.read_bytes() != src.read_bytes()
    pixels = _first_frame_gray(out, 320, 240)
    assert _region_max(pixels, 320, 320 - _CORNER_W, 240 - _CORNER_H, 320, 240) > 40
    reload_settings()


# ── 폴백 (외부 의존성 장애) ───────────────────────────────────────
def test_ffmpeg_바이너리가_없으면_원본복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — ffmpeg 미설치 환경 모사
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    target = out_dir / "a-mask.mp4"
    monkeypatch.setattr(deid_sim.shutil, "which", lambda _name: None)

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 산출은 실패(False)를 반환하고 폴백 <b>사유</b>가 WARN 으로 남는다.
    #   ⚠ 로그 문구가 아니라 <b>사유(ffmpeg 부재)</b>를 검증한다. ffmpeg 는 워터마크뿐 아니라
    #     실제 비식별 엔진의 인코딩에도 쓰이므로, 이 폴백은 더 이상 워터마크 전용이 아니다
    #     (문구도 'watermark skip' → 'deid render skip' 으로 바뀌었다).
    assert ok is False
    assert not target.exists()
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("ffmpeg" in m for m in warns)


def test_폰트파일이_없으면_원본복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — 후보 폰트가 모두 없는 환경
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(
        deid_sim, "WATERMARK_FONT_CANDIDATES", ("/nonexistent/None.ttf",)
    )

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then
    assert ok is False
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("font" in m for m in warns)


def test_ffmpeg_실패시_폴백하고_임시파일이_남지_않는다(
    client, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 컨테이너 시그니처만 흉내낸 깨진 mp4(ffmpeg 가 반드시 실패)
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    broken = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64
    (raw / "a.mp4").write_bytes(broken)
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    res = client.post(
        "/project",
        json={
            "project_name": "wm2", "creator": "w1",
            "export_path": f"{export}/", "input_path": f"{raw}/", "files": ["a.mp4"],
        },
    )

    # then — 기존 계약(원본 복사) 유지 + 임시 산출물 잔여 없음
    assert res.status_code == 200
    _await_production(res.json()["prj_id"])
    out = export / "a-mask.mp4"
    assert out.read_bytes() == broken
    assert _leftover(export) == ["a-mask.mp4"]
    reload_settings()


def test_타임아웃이면_폴백하고_임시파일이_정리된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — ffmpeg 가 타임아웃되며 불완전 임시파일을 남긴 상황
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)

    def _timeout(cmd, **kwargs):  # noqa: ANN001
        Path(cmd[-1]).write_bytes(b"PARTIAL")  # 불완전 산출물
        raise subprocess.TimeoutExpired(cmd, kwargs.get("timeout", 30))

    _patch_encode_run(monkeypatch, _timeout)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then — False 폴백 + 디렉터리에 잔여 임시파일 없음(은닉 디렉터리 내부까지)
    assert ok is False
    assert _leftover(out_dir) == []
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("timeout" in m for m in warns)


def test_ffmpeg가_0을_반환해도_산출물이_비어있으면_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — rc=0 이지만 빈 파일만 남는 이상 상황
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)

    def _empty(cmd, **kwargs):  # noqa: ANN001
        Path(cmd[-1]).write_bytes(b"")
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    _patch_encode_run(monkeypatch, _empty)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then
    assert ok is False
    assert _leftover(out_dir) == []


# ── 보안: CWE-78 커맨드 인젝션 / 인자 인젝션 ──────────────────────
_REAL_RUN = subprocess.run


def _patch_encode_run(monkeypatch: pytest.MonkeyPatch, handler) -> None:  # noqa: ANN001
    """<b>ffmpeg 인코딩 호출만</b> 가로채고 나머지(ffprobe 등)는 실제로 실행한다.

    ⚠ ``deid_sim.subprocess`` 와 ``media_probe.subprocess`` 는 <b>같은 모듈 객체</b>다. 그래서
    ``subprocess.run`` 을 통째로 스텁하면 승격 직전의 길이 검증(ffprobe)까지 스텁이 응답해,
    테스트가 프로덕션이 아니라 스텁의 동작을 검증하게 된다. 인코딩 호출(``-vf`` 포함)만
    분기하고 나머지는 원본 함수로 흘려보낸다.
    """

    def _dispatch(cmd, **kwargs):  # noqa: ANN001
        if isinstance(cmd, (list, tuple)) and "-vf" in cmd:
            return handler(list(cmd), **kwargs)
        return _REAL_RUN(cmd, **kwargs)

    monkeypatch.setattr(deid_sim.subprocess, "run", _dispatch)


def _capture_cmd(monkeypatch: pytest.MonkeyPatch, calls: list) -> None:
    """ffmpeg 인코딩 호출의 (cmd, kwargs) 를 기록하고 성공을 흉내낸다.

    산출물은 <b>승격 게이트를 통과하는 형태</b>(≥512B + ``ftyp``)로 쓴다 — 그래야 이후 단정이
    프로덕션의 승격 경로를 실제로 검증한다.
    """

    def _fake(cmd, **kwargs):  # noqa: ANN001
        calls.append((cmd, kwargs))
        Path(cmd[-1]).write_bytes(_valid_artifact_bytes())
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    _patch_encode_run(monkeypatch, _fake)


def test_ffmpeg는_shell없이_리스트인자로_실행된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    assert _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir) is True

    # then — 리스트 인자 + shell 미사용 + 타임아웃 지정
    assert len(calls) == 1
    cmd, kwargs = calls[0]
    assert isinstance(cmd, list)
    assert all(isinstance(a, str) for a in cmd)
    assert kwargs.get("shell", False) is False
    assert kwargs.get("timeout") is not None


def test_drawtext_텍스트는_고정문자열이며_경로는_대시로_시작하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 인자 인젝션(대시 시작 경로)과 필터 인젝션(요청 입력 삽입) 방어 검증
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then
    cmd = calls[0][0]
    filter_arg = cmd[cmd.index("-vf") + 1]
    assert f"text={deid_sim.WATERMARK_TEXT}" in filter_arg
    # LOW-4 — 목이 굽는 대상은 실제 비식별본이 아니라 원본 복사본이므로 MOCK 표식이 필수다.
    assert deid_sim.WATERMARK_TEXT == "MOCK 비식별 완료"
    assert "MOCK" in filter_arg
    # 파일 경로 인자는 절대경로(옵션 오인식 방지)
    for flag in ("-i",):
        assert cmd[cmd.index(flag) + 1].startswith(os.sep)
    assert cmd[-1].startswith(os.sep)


def test_대시로_시작하는_경로는_ffmpeg를_실행하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 상대경로/대시 시작 인자는 옵션으로 오인식될 수 있어 실행 자체를 거부한다
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)

    # when — 절대경로가 아닌 인자
    ok = deid_sim._burn_deid_watermark(
        Path("-evil.mp4"), Path("out.mp4"),
        input_path=".", input_base="", output_dir=tmp_path,
    )

    # then
    assert ok is False
    assert calls == []


# ── 보안: CWE-22 경로 순회 / TOCTOU 재검증 ────────────────────────
def test_허용_입력루트_밖_원본은_ffmpeg를_실행하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 허용 입력 루트(storage) 밖의 원본
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "secret.mp4"
    secret.write_bytes(b"\x00\x00\x00\x18ftypmp42SECRET")
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)

    # when — TOCTOU: 이미 검증을 통과한 것처럼 src 를 직접 넘겨도 재검증에 막혀야 한다
    ok = deid_sim._burn_deid_watermark(
        secret, out_dir / "secret-mask.mp4",
        input_path=str(in_dir), input_base=str(storage), output_dir=out_dir,
    )

    # then
    assert ok is False
    assert calls == []


def test_허용_출력루트_밖_타깃은_ffmpeg를_실행하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)

    # when — 출력 디렉터리를 벗어나는 타깃
    ok = deid_sim._burn_deid_watermark(
        src, out_dir / ".." / "escaped.mp4",
        input_path=str(in_dir), input_base=str(storage), output_dir=out_dir,
    )

    # then
    assert ok is False
    assert calls == []
    assert not (storage / "escaped.mp4").exists()


# ── 원자성 / 동시성 ───────────────────────────────────────────────
def test_임시파일명에_uuid가_포함되어_동시요청_충돌을_피한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when — 같은 타깃명으로 두 번(사이에 산출물 제거 — 임시경로가 겹치는지 확인)
    target = out_dir / "a-mask.mp4"
    _burn(src, target, in_dir=in_dir, out_dir=out_dir)
    target.unlink()
    _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 임시 출력 경로가 매번 다르고 확장자를 보존한다
    tmp_names = [Path(c[0][-1]).name for c in calls]
    assert len(set(tmp_names)) == 2
    assert all(".tmp" in n and n.endswith(".mp4") for n in tmp_names)


def test_기존_산출물이_있으면_워터마킹해도_덮어쓰지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 타깃이 이미 존재(HIGH-2 no-overwrite 보존)
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    target = out_dir / "a-mask.mp4"
    target.write_bytes(b"PRESERVE_ME")
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 기존 파일 보존 + 임시파일 잔여 없음
    assert ok is False
    assert target.read_bytes() == b"PRESERVE_ME"
    assert _leftover(out_dir) == ["a-mask.mp4"]


# ── 대상 선별 ─────────────────────────────────────────────────────
def test_영상컨테이너가_아닌_원본은_워터마킹하지않고_그대로_복사된다(
    client, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 확장자만 mp4 인 텍스트 바이트(실제 영상 아님)
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    (raw / "a.mp4").write_bytes(b"ORIGINAL_VIDEO_BYTES_123")
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    res = client.post(
        "/project",
        json={
            "project_name": "wm3", "creator": "w1",
            "export_path": f"{export}/", "input_path": f"{raw}/", "files": ["a.mp4"],
        },
    )

    # then — 기존 계약(바이트 복사) 그대로
    assert res.status_code == 200
    _await_production(res.json()["prj_id"])
    assert (export / "a-mask.mp4").read_bytes() == b"ORIGINAL_VIDEO_BYTES_123"
    reload_settings()


def test_원본이_없으면_워터마킹도_산출도_하지_않고_실패로_종결한다(
    client, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """#3 — 원본 부재는 워터마킹 대상이 아닐 뿐 아니라 <b>산출물 자체를 만들지 않는다</b>."""
    # given — 원본 부재
    from app.config import reload_settings

    storage = tmp_path / "storage"
    base = storage / "deidentified"
    base.mkdir(parents=True)
    export = base / "videos" / "10"
    raw = storage / "raw" / "videos" / "10"
    raw.mkdir(parents=True)
    monkeypatch.setenv("MOCK_WRITE_OUTPUT_FILES", "true")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", str(base))
    reload_settings()

    # when
    res = client.post(
        "/project",
        json={
            "project_name": "wm4", "creator": "w1",
            "export_path": f"{export}/", "input_path": f"{raw}/", "files": ["a.mp4"],
        },
    )

    # then — 최종 경로 미생성(18B placeholder 선점 폐기) + 명시적 실패
    prj_id = res.json()["prj_id"]
    _await_production(prj_id)
    assert not (export / "a-mask.mp4").exists()
    assert deid_sim.production_state_of(prj_id) == "FAILED"
    reload_settings()


# ── MEDIUM-1: 임시 산출물이 BE 무결성 게이트 사정권 밖에 있는가 ────
# BE 폴백 스캔(KpstDeidentService.scanSingleUsable)은 export 디렉터리를 <b>비재귀</b>로 훑어
# "정규파일 + 512바이트 이상 + 컨테이너 시그니처"를 유효 비식별본으로 인정한다. 임시 산출물이
# export 직속에 .mp4 로 있으면, 인코딩 중 프로세스가 SIGKILL 되어 정리(finally)가 실행되지 않았을 때
# <b>잘린 부분 산출물</b>이 '비식별 완료'로 승인되고(2개 이상이면 terminal 실패로 파이프라인 영구정지).
_BE_MIN_BYTES = 512


def _be_scan_usable(directory: Path) -> list[Path]:
    """BE 의 폴백 스캔 판정을 그대로 모사한다(Files.list 비재귀 + DeidentArtifactIntegrity)."""
    usable: list[Path] = []
    for entry in directory.iterdir():
        # Files.isRegularFile(NOFOLLOW_LINKS) — 디렉터리/심링크는 탈락
        if entry.is_symlink() or not entry.is_file():
            continue
        if entry.stat().st_size < _BE_MIN_BYTES:
            continue
        if entry.read_bytes()[4:8] == b"ftyp":
            usable.append(entry)
    return usable


def _partial_mp4(size: int = 4096) -> bytes:
    """ftyp 시그니처를 가진 '잘린 부분 산출물' 바이트(BE 판정을 통과하는 형태)."""
    return b"\x00\x00\x00\x18ftypmp42" + b"\x00" * (size - 12)


def test_임시_산출물은_export_직속이_아니라_은닉_디렉터리에_생성된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    assert _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir) is True

    # then — ffmpeg 출력 경로가 {export}/.mock-tmp/ 안이다(export 직속 아님)
    temp_path = Path(calls[0][0][-1])
    assert temp_path.parent.name == deid_sim.TEMP_DIR_NAME
    assert temp_path.parent.parent == out_dir
    assert temp_path.suffix == ".mp4" and deid_sim.TEMP_MARK in temp_path.name


def test_인코딩_중_강제종료로_고아가_남아도_BE_스캔에_잡히지_않는다(
    tmp_path,
) -> None:
    # given — export 디렉터리 + 정상 산출물 1건
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    target = out_dir / "a-mask.mp4"
    target.write_bytes(_partial_mp4())

    # when — "인코딩 도중 SIGKILL" 재현: 프로덕션과 같은 경로 규칙으로 임시파일을 만들고
    #        finally 정리가 실행되지 않은 상태(고아 2건)를 남긴다.
    orphans = []
    for _ in range(2):
        temp = deid_sim._temp_path_for(target)
        assert temp is not None
        temp.write_bytes(_partial_mp4())
        orphans.append(temp)

    # then — 고아는 실재하지만 export 직속에는 .mp4 가 산출물 1건뿐이고,
    #        BE 스캔(비재귀)도 정확히 그 1건만 본다(모호 실패/거짓 'Y' 없음).
    assert all(o.is_file() for o in orphans)
    assert [p.name for p in out_dir.iterdir() if p.is_file()] == ["a-mask.mp4"]
    assert _be_scan_usable(out_dir) == [target]

    # and — sweep 으로 고아가 정리된다(기동 sweep / 재요청 시 lazy sweep 경로)
    assert deid_sim.sweep_temp_dir(out_dir / deid_sim.TEMP_DIR_NAME, max_age_sec=0.0) == 2
    assert not any(o.exists() for o in orphans)


def test_기동_sweep은_허용_출력루트_하위_고아_임시파일을_정리한다(tmp_path) -> None:
    # given — 출력 루트 하위 깊은 곳의 .mock-tmp 에 고아가 남아있다
    base = tmp_path / "storage"
    out_dir = base / "videos" / "10" / "deid"
    out_dir.mkdir(parents=True)
    target = out_dir / "a-mask.mp4"
    temp = deid_sim._temp_path_for(target)
    assert temp is not None
    temp.write_bytes(_partial_mp4())
    keep = out_dir / "keep-mask.mp4"
    keep.write_bytes(_partial_mp4())

    # when
    removed = deid_sim.sweep_orphan_temp_files(str(base))

    # then — 고아만 제거되고 정상 산출물은 보존
    assert removed == 1
    assert not temp.exists()
    assert keep.is_file()


def test_실패_폴백시_은닉_임시_디렉터리도_남기지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — ffmpeg 가 실패하는 상황
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)

    def _fail(cmd, **kwargs):  # noqa: ANN001
        Path(cmd[-1]).write_bytes(_partial_mp4())  # 부분 산출물 남김
        return subprocess.CompletedProcess(cmd, 1, b"", b"")

    _patch_encode_run(monkeypatch, _fail)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    assert _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir) is False

    # then — 임시 산출물이 남지 않는다(은닉 디렉터리 내부까지 확인)
    assert _leftover(out_dir) == []


# ── #1/#3: 조용한 절단 금지 (CWE-345/CWE-754) ─────────────────────
# 구 구현은 ffmpeg 인자에 ``-t 300``(길이)/``-fs 256MiB``(크기) 상한을 걸어 <b>산출물을 절단</b>했다.
# 절단은 rc=0 으로 끝나 성공과 구분되지 않았고, ``st_size != 0`` 도 BE 무결성(512B + ftyp)도
# 통과해 "원본 후반이 사라진 영상"이 정상 비식별 산출물로 승격됐다(600초 → 300초, 실측).
# 자원 초과는 절단이 아니라 "인코딩 포기 + 원본 온전 복사"로 처리해야 한다.
def test_ffmpeg_인자에_길이_크기_절단상한이_없다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    assert _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir) is True

    # then — CPU 상한은 유지하되, 산출물을 조용히 잘라내는 인자는 없어야 한다
    cmd = calls[0][0]
    assert cmd[cmd.index("-threads") + 1] == str(deid_sim.FFMPEG_THREADS)
    assert "-t" not in cmd, "길이 절단 인자(-t)가 다시 들어왔다 — 절단본이 승격된다"
    assert "-fs" not in cmd, "크기 절단 인자(-fs)가 다시 들어왔다 — 절단본이 승격된다"
    # 자원 보호는 subprocess 타임아웃(kill → 복사 폴백)이 담당한다
    assert calls[0][1].get("timeout") == deid_sim.FFMPEG_TIMEOUT_SEC


def test_원본보다_짧은_산출물은_승격되지_않고_원본복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    """★ 회귀 가드 — 절단본이 '정상 산출물'로 승격되던 결함(#1/#3)의 재현 테스트.

    ffmpeg 가 rc=0 으로 끝나면서 <b>원본보다 짧은</b> 산출물을 남기는 상황을 만든다.
    구 구현은 ``st_size != 0`` 만 확인했으므로 이 산출물을 그대로 최종 배치했다.
    """
    if _FFMPEG is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe 미설치 환경 — 길이 검증 경로 확인 불가")

    # given — 20초 원본, 그러나 인코더는 5초짜리만 내놓는다(절단 재현)
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "a.mp4", width=64, height=64, duration=20, fps=1)

    def _truncating(cmd, **kwargs):  # noqa: ANN001
        subprocess.run(
            [_FFMPEG, "-v", "error", "-y", "-i", str(src), "-t", "5",
             "-c:v", "libx264", "-pix_fmt", "yuv420p", cmd[-1]],
            capture_output=True, timeout=60, check=True,
        )
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    _patch_encode_run(monkeypatch, _truncating)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))
    target = out_dir / "a-mask.mp4"

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 절단본은 승격되지 않고(폴백 신호) 임시파일도 남지 않는다
    assert ok is False, "원본보다 짧은 산출물이 승격됐다(조용한 절단)"
    assert not target.exists()
    assert _leftover(out_dir) == []
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("길이가 원본과 다름" in m for m in warns), "절단 사유가 감사 로그에 없다"
    # 그리고 성공 로그는 남지 않는다(절단이 'burned' 로 기록되면 안 된다)
    assert not any("watermark burned" in r.getMessage() for r in caplog.records)


@requires_ffmpeg
def test_긴_영상도_절단없이_원본_길이_그대로_산출된다(tmp_path) -> None:
    """★ 회귀 가드 — 구 ``-t 300`` 상한이 되살아나면 이 테스트가 깨진다.

    600초(구 상한의 2배) 원본을 실제로 인코딩해 산출물 길이가 원본과 같은지 확인한다.
    1fps/64x64 라 프레임 수가 적어 인코딩은 1초 안팎이다(테스트 스위트 지연 없음).
    """
    # given — 600초 원본
    in_dir = tmp_path / "raw"
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = _make_black_video(in_dir / "long.mp4", width=64, height=64, duration=600, fps=1)
    assert _probe_duration(src) > 500
    target = out_dir / "long-mask.mp4"

    # when
    assert _burn(src, target, in_dir=in_dir, out_dir=out_dir) is True

    # then — 산출물이 원본과 같은 길이다(구 구현은 300초로 잘렸다)
    assert abs(_probe_duration(target) - _probe_duration(src)) <= 1.0


# ── #4: 승격 게이트가 BE 무결성 기준과 동일한가 ────────────────────
@pytest.mark.parametrize(
    "payload",
    [
        pytest.param(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 100, id="512바이트_미만"),
        pytest.param(b"NOT_A_CONTAINER" + b"\x00" * 1000, id="컨테이너_시그니처_없음"),
        pytest.param(b"", id="빈_파일"),
    ],
)
def test_BE무결성_기준에_미달하는_산출물은_승격되지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, payload: bytes
) -> None:
    """목이 BE 보다 관대하면 BE 가 거부할 산출물을 승격시켜 <b>복사 폴백 기회를 뺏는다</b>(#4)."""
    # given
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    body = b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8
    src.write_bytes(body)

    def _weak_output(cmd, **kwargs):  # noqa: ANN001
        Path(cmd[-1]).write_bytes(payload)
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    _patch_encode_run(monkeypatch, _weak_output)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))
    target = out_dir / "a-mask.mp4"

    # when
    ok = _burn(src, target, in_dir=in_dir, out_dir=out_dir)

    # then — 승격 거부 + 잔여물 없음(호출측은 원본 복사로 폴백한다)
    assert ok is False
    assert not target.exists()
    assert _leftover(out_dir) == []


def test_승격_판정은_BE_무결성_하한과_같은_값을_쓴다(tmp_path) -> None:
    # given / when / then — 경계값 자체를 고정한다(BE MIN_VIDEO_BYTES = 512)
    assert deid_sim.MIN_ARTIFACT_BYTES == 512
    ok = tmp_path / "ok.mp4"
    ok.write_bytes(_valid_artifact_bytes(512))
    small = tmp_path / "small.mp4"
    small.write_bytes(_valid_artifact_bytes(511))
    assert deid_sim.is_promotable_artifact(ok) is True
    assert deid_sim.is_promotable_artifact(small) is False


# ── 요청당 자원 예산(건수·바이트) ─────────────────────────────────
# ⚠ <b>시간 예산 축은 삭제됐다</b>(#1/#2). 그 축은 동기 접수 모델(45초 HTTP 타임아웃 안에
#    인코딩까지 마쳐야 함)의 산물이었고, 접수가 비동기가 되면서 전제가 사라졌다. 게다가 예산
#    산식이 자기가 추가한 ffprobe·능력탐지·복사 시간을 계상하지 않아 실제 최악값과 어긋나 있었고,
#    그 가드 테스트는 <b>프로덕션과 같은 산식을 복사</b>해 단언하는 동어반복이라 결함이 있어도
#    영원히 통과했다(#7). 그래서 예산 클래스와 함께 그 테스트들을 제거했다 — 지금 남은 것은
#    시간과 무관한 자원 축(건수·바이트·세마포어)뿐이며, "제거 유지" 가드는
#    ``test_deid_async.py::test_HTTP_타임아웃에_맞춘_시간예산_장치는_존재하지_않는다`` 가 담당한다.
def test_요청당_워터마킹_건수_상한을_넘으면_복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 상한 2, 대상 4건(요청 1건이 스레드풀 워커를 장시간 점유하지 못하게)
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    body = b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8
    names = [f"v{i}.mp4" for i in range(4)]
    for name in names:
        (in_dir / name).write_bytes(body)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(in_dir / names[0]),))
    monkeypatch.setattr(deid_sim, "WATERMARK_MAX_FILES_PER_REQUEST", 2)

    # when
    written = deid_sim.write_deid_outputs(
        export_path=str(out_dir),
        input_path=str(in_dir),
        outputs=[(n, n.replace(".mp4", "-mask.mp4")) for n in names],
        output_base=str(storage),
        input_base=str(storage),
    )

    # then — ffmpeg 는 상한만큼만 실행되고, 초과분은 원본 복사로 산출된다(전량 산출은 유지)
    assert len(calls) == 2
    assert len(written) == 4
    # 상한 안: 인코딩 산출물이 승격됐다(= 원본 바이트가 아니며 BE 무결성 기준을 통과)
    watermarked = out_dir / "v0-mask.mp4"
    assert watermarked.read_bytes() != body
    assert deid_sim.is_promotable_artifact(watermarked) is True
    # 상한 밖: 원본이 <b>온전히</b> 복사됐다(절단·placeholder 아님)
    assert (out_dir / "v3-mask.mp4").read_bytes() == body


def test_동시_ffmpeg_상한을_넘으면_대기_후_복사로_폴백한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — 세마포어를 전부 선점해 "동시 실행 상한 도달" 상태를 만든다
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))
    # 비동기 산출이라 프로덕션 대기 상한은 넉넉하다 — 테스트에서만 짧게 줄여 포화를 재현한다.
    monkeypatch.setattr(deid_sim, "FFMPEG_ACQUIRE_TIMEOUT_SEC", 0.05)
    held = [deid_sim._FFMPEG_SEMAPHORE.acquire() for _ in range(deid_sim.FFMPEG_MAX_CONCURRENCY)]

    # when
    try:
        with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
            ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)
    finally:
        for _ in held:
            deid_sim._FFMPEG_SEMAPHORE.release()

    # then — ffmpeg 를 아예 띄우지 않고 복사 폴백 신호(False) + 사유 WARN
    assert ok is False
    assert calls == []
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("동시 실행 상한" in m for m in warns)
    # 그리고 상한이 풀리면 다시 정상 인코딩된다(영구 차단 아님)
    assert _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir) is True


def test_세마포어는_동시_ffmpeg_수를_상한_이하로_유지한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 여러 스레드가 동시에 워터마킹을 시도한다
    import threading

    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))
    monkeypatch.setattr(deid_sim, "FFMPEG_ACQUIRE_TIMEOUT_SEC", 10.0)

    lock = threading.Lock()
    state = {"now": 0, "peak": 0}

    def _slow(cmd, **kwargs):  # noqa: ANN001
        with lock:
            state["now"] += 1
            state["peak"] = max(state["peak"], state["now"])
        time.sleep(0.05)
        with lock:
            state["now"] -= 1
        Path(cmd[-1]).write_bytes(_valid_artifact_bytes())
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    _patch_encode_run(monkeypatch, _slow)

    # when — 6개 동시 요청
    threads = [
        threading.Thread(
            target=_burn, args=(src, out_dir / f"t{i}-mask.mp4"),
            kwargs={"in_dir": in_dir, "out_dir": out_dir},
        )
        for i in range(6)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)

    # then — 동시 실행 피크가 상한을 넘지 않는다
    assert state["peak"] <= deid_sim.FFMPEG_MAX_CONCURRENCY
    assert len(list(out_dir.glob("t*-mask.mp4"))) == 6


# ── LOW-1: 출력 루트 재검증(비대칭 TOCTOU) ────────────────────────
def test_출력디렉터리_조상이_심링크로_교체되면_인코딩하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 허용 출력 루트 안의 정상 출력 디렉터리
    storage = tmp_path / "storage"
    allowed = storage / "out"
    real = allowed / "a"
    out_dir = real / "b"
    out_dir.mkdir(parents=True)
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    def _try_burn() -> bool:
        return deid_sim._burn_deid_watermark(
            src, out_dir / "a-mask.mp4",
            input_path=str(in_dir), input_base=str(storage),
            output_dir=out_dir, export_path=str(out_dir), output_base=str(allowed),
        )

    # 대조군 — 정상 상태에서는 인코딩된다(테스트가 공회전하지 않음을 보장)
    assert _try_burn() is True
    assert len(calls) == 1
    (out_dir / "a-mask.mp4").unlink()
    calls.clear()

    # when — 출력 디렉터리의 <조상>을 허용 루트 밖으로 향하는 심링크로 교체(TOCTOU)
    evil = tmp_path / "evil"
    (evil / "b").mkdir(parents=True)
    shutil.rmtree(real)
    real.symlink_to(evil, target_is_directory=True)

    ok = _try_burn()

    # then — target.parent 와 output_dir 가 <함께> 이동해도, 허용 루트에서 재도출한
    #        출력 경계에 막혀 ffmpeg 가 실행되지 않는다
    assert ok is False
    assert calls == []
    assert not (evil / "b" / "a-mask.mp4").exists()


def test_심링크_원본이_허용루트_밖을_가리키면_인코딩하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 허용 입력 루트 <안>에 있지만 실체는 밖을 가리키는 심링크 원본
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "secret.mp4"
    secret.write_bytes(b"\x00\x00\x00\x18ftypmp42SECRET_BYTES")
    link = in_dir / "a.mp4"
    link.symlink_to(secret)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)

    # when
    ok = deid_sim._burn_deid_watermark(
        link, out_dir / "a-mask.mp4",
        input_path=str(in_dir), input_base=str(storage),
        output_dir=out_dir, export_path=str(out_dir), output_base=str(storage),
    )

    # then — resolve 후 경계 밖이므로 실행 자체를 거부(심링크 경유 임의 파일 노출 차단)
    assert ok is False
    assert calls == []
    assert not (out_dir / "a-mask.mp4").exists()


def test_심링크_입력디렉터리로_허용루트를_우회할_수_없다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — input_path 자체가 허용 루트 밖을 가리키는 디렉터리 심링크
    storage = tmp_path / "storage"
    storage.mkdir()
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "a.mp4").write_bytes(b"\x00\x00\x00\x18ftypmp42SECRET_BYTES")
    link_dir = storage / "raw"
    link_dir.symlink_to(outside, target_is_directory=True)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)

    # when
    written = deid_sim.write_deid_outputs(
        export_path=str(out_dir),
        input_path=str(link_dir),
        outputs=[("a.mp4", "a-mask.mp4")],
        output_base=str(storage),
        input_base=str(storage),
    )

    # then — 원본 내용이 새지 않는다. #3 이후에는 placeholder 로 대체하지도 않는다
    #        (산출물 0건 → 호출측이 산출 실패로 종결).
    assert calls == []
    assert written == []
    assert not (out_dir / "a-mask.mp4").exists()


# ── LOW-3: 필터그래프 값 이스케이프 ───────────────────────────────
def test_필터값_이스케이프가_필터그래프_메타문자를_모두_처리한다() -> None:
    # given / when — 필터 구분(,) · 체인 구분(;) · 라벨([]) · 옵션 구분(:) · 인용(') · 역슬래시 · 개행
    escaped = deid_sim._escape_filter_value("a,b;c[d]e:f'g\\h\ni")

    # then — 메타문자는 모두 이스케이프되고 개행은 제거된다
    assert escaped == "a\\,b\\;c\\[d\\]e\\:f\\'g\\\\hi"
    assert "\n" not in escaped


def test_워터마크_문구도_동일_이스케이프를_거친다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given — 문구가 바뀌어 메타문자를 포함하게 되는 미래 상황
    monkeypatch.setattr(deid_sim, "WATERMARK_TEXT", "a:b,c")

    # when
    built = deid_sim._build_drawtext_filter("/fonts/x.ttf")

    # then — text= 값도 이스케이프되어 다른 필터가 주입되지 않는다
    assert ":text=a\\:b\\,c:" in built
    assert built.count("drawtext=") == 1


# ── F-8: drawtext 텍스트 확장(%{...}) 비활성 ──────────────────────
def test_drawtext는_텍스트_확장을_비활성화한다() -> None:
    """``expansion=none`` 이 없으면 text 안의 ``%{...}`` 가 평가된다(심층 방어)."""
    # given / when
    built = deid_sim._build_drawtext_filter("/fonts/x.ttf")

    # then
    assert ":expansion=none:" in built


# ── 민감정보 노출 ─────────────────────────────────────────────────
def test_ffmpeg_stderr는_로그에_그대로_남기지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — stderr 에 내부 경로/시스템 정보가 섞인 실패
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    leak = b"Error opening /etc/shadow: build config --enable-gpl SECRET_TOKEN"

    def _fail(cmd, **kwargs):  # noqa: ANN001
        return subprocess.CompletedProcess(cmd, 1, b"", leak)

    _patch_encode_run(monkeypatch, _fail)
    _fake_ffmpeg_available(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then — 폴백은 하되 stderr 원문은 WARN/INFO 로그에 실리지 않는다
    assert ok is False
    for record in caplog.records:
        assert "SECRET_TOKEN" not in record.getMessage()
        assert "/etc/shadow" not in record.getMessage()


def test_배치_실패시_예외문자열의_전체경로가_로그에_남지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — OSError 문자열에 전체 경로가 담기는 상황(예: 디스크 부족)
    in_dir = tmp_path / "raw"
    in_dir.mkdir(parents=True)
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 8)
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    _fake_durations(monkeypatch)
    monkeypatch.setattr(deid_sim, "WATERMARK_FONT_CANDIDATES", (str(src),))

    leak_path = "/app/storage/raw/1/deid/a-mask.mp4"

    def _boom(_temp, _target):  # noqa: ANN001
        raise OSError(28, "No space left on device", leak_path)

    monkeypatch.setattr(deid_sim, "_place_atomically", _boom)

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        ok = _burn(src, out_dir / "a-mask.mp4", in_dir=in_dir, out_dir=out_dir)

    # then — 폴백은 하되 로그에는 예외 종류/errno 만 남고 전체 경로는 없다(CWE-532/209)
    assert ok is False
    messages = [r.getMessage() for r in caplog.records]
    assert all(leak_path not in m for m in messages)
    assert any("errno=28" in m and "OSError" in m for m in messages)


# ── MEDIUM-3: 읽기 허용 루트 미설정 = fail-closed ─────────────────
def test_허용_입력루트가_미설정이면_원본을_읽지않고_산출실패로_종결한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    # given — 출력 루트는 있으나 읽기 허용 루트가 도출되지 않은 상태(루트 붕괴 방지 결과)
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    (in_dir / "a.mp4").write_bytes(b"\x00\x00\x00\x18ftypmp42SECRET_BYTES")
    calls: list = []
    _capture_cmd(monkeypatch, calls)
    _fake_ffmpeg_available(monkeypatch)
    deid_sim.reset_base_warning()

    # when — input_base 가 빈 문자열(= 허용 루트 없음)
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        deid_sim.write_deid_outputs(
            export_path=str(out_dir),
            input_path=str(in_dir),
            outputs=[("a.mp4", "a-mask.mp4")],
            output_base=str(storage),
            input_base="",
        )

    # then — "제한 없음"이 아니라 "아무것도 읽지 않음"(fail-closed).
    #        #3 이후에는 placeholder 로 최종 이름을 선점하지도 않는다.
    assert calls == []
    assert not (out_dir / "a-mask.mp4").exists()
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("MOCK_INPUT_BASE" in m for m in warns)
    deid_sim.reset_base_warning()


# ── F-1: sweep 이 심링크를 추종하지 않는다 (CWE-59/CWE-22) ─────────
def test_sweep은_심링크_임시디렉터리를_따라가_남의_파일을_지우지_않는다(tmp_path) -> None:
    """``.mock-tmp`` 이름의 심링크가 있으면 구 구현은 링크를 따라가 대상 파일을 삭제했다.

    기동 sweep 은 ``max_age_sec=0`` 이라 <b>나이 무관 전량 삭제</b>였다(허용 루트 밖 파괴).
    """
    # given — 허용 출력 루트 안에 '.mock-tmp' 라는 이름의 심링크가 밖을 가리킨다
    base = tmp_path / "storage"
    sub = base / "videos" / "10"
    sub.mkdir(parents=True)
    victim_dir = tmp_path / "victim"
    victim_dir.mkdir()
    victim = victim_dir / f"secret.{'a' * 32}.tmp.mp4"  # 임시파일 패턴과 동일한 이름
    victim.write_bytes(_partial_mp4())
    (sub / deid_sim.TEMP_DIR_NAME).symlink_to(victim_dir, target_is_directory=True)

    # when — 기동 sweep(나이 무관)
    removed = deid_sim.sweep_orphan_temp_files(str(base))

    # then — 링크 너머 파일은 건드리지 않는다
    assert removed == 0
    assert victim.is_file(), "심링크를 추종해 허용 루트 밖 파일을 삭제했다"


def test_sweep_temp_dir_자체도_심링크면_아무것도_지우지_않는다(tmp_path) -> None:
    # given
    victim_dir = tmp_path / "victim"
    victim_dir.mkdir()
    victim = victim_dir / f"x.{'b' * 32}.tmp.mp4"
    victim.write_bytes(_partial_mp4())
    link = tmp_path / "link"
    link.symlink_to(victim_dir, target_is_directory=True)

    # when / then
    assert deid_sim.sweep_temp_dir(link, max_age_sec=0.0) == 0
    assert victim.is_file()


@pytest.mark.parametrize(
    "name",
    ["x.tmpfile", "nginx.tmpl", "a.tmp.bak", "report.tmp.txt", ".tmp", "a.tmp"],
)
def test_임시파일_식별은_부분일치로_남의_파일까지_잡지_않는다(name: str) -> None:
    # given / when / then — 우리 규칙은 ``{stem}.{uuid32}.tmp{ext}`` 하나뿐이다
    assert deid_sim._is_temp_artifact(name) is False


def test_우리가_만든_임시파일명은_정리대상으로_식별된다(tmp_path) -> None:
    # given — 프로덕션 경로 규칙으로 생성
    target = tmp_path / "a-mask.mp4"
    temp = deid_sim._temp_path_for(target)
    assert temp is not None

    # when / then
    assert deid_sim._is_temp_artifact(temp.name) is True


def test_임시디렉터리가_심링크면_인코딩하지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — F-6: mkdir(exist_ok=True) 는 심링크-투-디렉터리를 통과시킨다
    out_dir = tmp_path / "deid"
    out_dir.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    (out_dir / deid_sim.TEMP_DIR_NAME).symlink_to(outside, target_is_directory=True)

    # when
    temp = deid_sim._temp_path_for(out_dir / "a-mask.mp4")

    # then — 링크 너머로 쓰기를 유도하지 못한다
    assert temp is None
    assert list(outside.iterdir()) == []


# ── F-3/F-4: 복사 폴백의 원자성과 바이트 상한 ─────────────────────
def test_복사_도중_실패하면_부분파일을_최종경로에_남기지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """부분 복사본은 ``ftyp`` + 512B 를 만족해 BE 무결성을 통과한다 → 거짓 'Y'(F-3)."""
    # given — 복사 도중 IO 오류
    src = tmp_path / "a.mp4"
    src.write_bytes(_partial_mp4(4096))
    target = tmp_path / "a-mask.mp4"

    original_copy = deid_sim._copy_limited

    def _boom(source, dst, max_bytes):  # noqa: ANN001
        dst.write(_partial_mp4(1024))  # 부분 산출물 기록 후 실패
        raise OSError(5, "I/O error")

    monkeypatch.setattr(deid_sim, "_copy_limited", _boom)

    # when
    with pytest.raises(OSError):
        deid_sim._copy_no_overwrite(src, target)

    # then — 최종 경로에 잔재가 없다
    assert not target.exists()
    assert deid_sim._copy_limited is not original_copy  # 패치 확인(테스트 자체 무결성)


def test_복사_바이트_상한을_넘으면_부분파일_없이_예외로_알린다(tmp_path) -> None:
    # given — 상한보다 큰 원본
    src = tmp_path / "big.mp4"
    src.write_bytes(_partial_mp4(4096))
    target = tmp_path / "big-mask.mp4"

    # when / then
    with pytest.raises(deid_sim.OutputBudgetExceeded):
        deid_sim._copy_no_overwrite(src, target, max_bytes=1024)
    assert not target.exists(), "상한 초과 복사의 부분 파일이 남았다"


def test_원본이_복사예산을_넘으면_최종이름을_선점하지_않고_실패로_종결한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch, caplog
) -> None:
    """#5 — 구 동작(18B placeholder 로 최종 이름 선점)은 <b>영구 고착</b>을 만들었다.

    placeholder 는 BE 무결성(512B)을 탈락시켜 'F' 가 되는데, no-overwrite 때문에 이후 어떤
    재시도도 그 자리를 대체하지 못했다(상한 도입 전에는 성공하던 흐름이다).
    """
    # given — 복사 상한을 아주 작게(무인증 반복 요청에 의한 볼륨 고갈 차단)
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    (in_dir / "a.mp4").write_bytes(b"ORIGINAL_NOT_A_VIDEO" * 100)
    monkeypatch.setattr(deid_sim, "COPY_MAX_BYTES", 64)

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        outcome = deid_sim.produce_deid_outputs(
            export_path=str(out_dir),
            input_path=str(in_dir),
            outputs=[("a.mp4", "a-mask.mp4")],
            output_base=str(storage),
            input_base=str(storage),
        )

    # then — 아무것도 쓰지 않고(최종 이름 미선점) 산출 실패로 알린다
    assert outcome.failed is True
    assert outcome.written == []
    assert not (out_dir / "a-mask.mp4").exists()
    warns = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("복사 예산" in m for m in warns)


def test_요청_총_산출바이트_예산을_넘으면_이후_파일은_실패로_종결한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 총량 예산이 1건분만 허용
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    body = b"X" * 200
    for name in ("a.mp4", "b.mp4"):
        (in_dir / name).write_bytes(body)
    monkeypatch.setattr(deid_sim, "REQUEST_MAX_OUTPUT_BYTES", 200)

    # when
    outcome = deid_sim.produce_deid_outputs(
        export_path=str(out_dir),
        input_path=str(in_dir),
        outputs=[("a.mp4", "a-mask.mp4"), ("b.mp4", "b-mask.mp4")],
        output_base=str(storage),
        input_base=str(storage),
    )

    # then — 첫 건은 온전히 복사, 예산 소진분은 최종 이름을 만들지 않고 산출 실패로 종결
    assert (out_dir / "a-mask.mp4").read_bytes() == body
    assert not (out_dir / "b-mask.mp4").exists()
    assert outcome.failed is True


# ── F-6: 하드링크 미지원 폴백도 덮어쓰지 않는다 ───────────────────
def test_하드링크_미지원_폴백에서도_기존파일을_덮어쓰지_않는다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — os.link 가 EXDEV 로 실패하고, 타깃은 이미 존재한다
    temp = tmp_path / "temp.mp4"
    temp.write_bytes(_valid_artifact_bytes())
    target = tmp_path / "target.mp4"
    target.write_bytes(b"PRESERVE_ME")

    def _exdev(_src, _dst):  # noqa: ANN001
        raise OSError(18, "Invalid cross-device link")

    monkeypatch.setattr(deid_sim.os, "link", _exdev)

    # when
    placed = deid_sim._place_atomically(temp, target)

    # then — O_EXCL 선점 실패로 덮어쓰지 않는다(사유는 "이미 존재" — 산출물은 실재한다)
    assert placed is deid_sim.OutputWriteResult.TARGET_EXISTS
    assert placed.is_output_present is True
    assert target.read_bytes() == b"PRESERVE_ME"


def test_하드링크_미지원_폴백은_타깃이_없을_때만_배치한다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    temp = tmp_path / "temp.mp4"
    payload = _valid_artifact_bytes()
    temp.write_bytes(payload)
    target = tmp_path / "target.mp4"

    def _exdev(_src, _dst):  # noqa: ANN001
        raise OSError(18, "Invalid cross-device link")

    monkeypatch.setattr(deid_sim.os, "link", _exdev)

    # when / then
    assert deid_sim._place_atomically(temp, target) is deid_sim.OutputWriteResult.PLACED
    assert target.read_bytes() == payload


# ── ★ HIGH-1: 결과 conflation 회귀 가드 ───────────────────────────
# 구 구현은 ``_copy_no_overwrite`` 의 ``False`` 를 "이미 있음(성공)"으로 해석했다. ``.mock-tmp``
# 경유로 바뀐 뒤 그 ``False`` 는 <b>임시 디렉터리 사용 불가 / 원자 배치 실패</b>까지 뜻하게 됐고,
# 그 경우 <b>산출물이 하나도 없는데</b> production=SUCCEEDED · procState=2(완료)가 보고됐다.
def _occupy_temp_dir(out_dir: Path) -> None:
    """``.mock-tmp`` 자리를 <b>파일</b>로 점유해 임시 디렉터리 생성을 실패시킨다(실측 재현 조건)."""
    (out_dir / deid_sim.TEMP_DIR_NAME).write_bytes(b"NOT_A_DIR")


def test_임시디렉터리를_쓸수없으면_복사는_성공이_아니라_실패를_반환한다(
    tmp_path, caplog
) -> None:
    # given — export 안의 .mock-tmp 가 파일로 점유됨(mkdir EEXIST + 실디렉터리 아님)
    src = tmp_path / "a.mp4"
    src.write_bytes(_valid_artifact_bytes())
    out_dir = tmp_path / "deid"
    out_dir.mkdir()
    _occupy_temp_dir(out_dir)
    target = out_dir / "a-mask.mp4"

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        result = deid_sim._copy_no_overwrite(src, target)

    # then — 사유가 구분되고(FAILED) 최종 경로에는 아무것도 없다
    assert result is deid_sim.OutputWriteResult.FAILED
    assert result.is_output_present is False
    assert not target.exists()
    # and — 거짓 진단("이미 있어서 skip")이 남지 않는다
    assert not any(
        "output exists" in r.getMessage() for r in caplog.records
    ), "산출 실패가 '이미 존재' 로 기록됐다(거짓 진단)"


def test_원자배치_실패는_이미존재와_구분되어_실패로_반환된다(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 하드링크/rename 이 모두 실패(선점만 하고 교체 실패)
    temp = tmp_path / "temp.mp4"
    temp.write_bytes(_valid_artifact_bytes())
    target = tmp_path / "target.mp4"

    def _fail(_src, _dst):  # noqa: ANN001
        raise OSError(18, "Invalid cross-device link")

    monkeypatch.setattr(deid_sim.os, "link", _fail)
    monkeypatch.setattr(deid_sim.os, "replace", _fail)

    # when
    result = deid_sim._place_atomically(temp, target)

    # then — 빈 선점 파일도 남기지 않고 실패로 알린다
    assert result is deid_sim.OutputWriteResult.FAILED
    assert not target.exists()


def test_산출물이_한건도_없으면_성공이_아니라_실패로_종결한다(
    tmp_path, caplog
) -> None:
    """★ 재설계가 스스로 세운 계약 — 산출물 0건은 procState=99(FAILED)."""
    # given — 원본은 읽을 수 있으나 임시 디렉터리를 쓸 수 없다
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    (in_dir / "a.mp4").write_bytes(_valid_artifact_bytes())
    _occupy_temp_dir(out_dir)

    # when
    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        outcome = deid_sim.produce_deid_outputs(
            export_path=str(out_dir),
            input_path=str(in_dir),
            outputs=[("a.mp4", "a-mask.mp4")],
            output_base=str(storage),
            input_base=str(storage),
        )

    # then — 산출물 0건인데 성공으로 보고하던 결함(실측: SUCCEEDED/files=0/procState=2)
    assert outcome.failed is True
    assert outcome.written == []
    assert not (out_dir / "a-mask.mp4").exists()


def test_placeholder_산출_경로는_모듈에서_제거되었다() -> None:
    """#3 — 18B placeholder 산출물은 폐기됐다(최종 경로 선점 → BE 무결성 탈락 → 영구 고착).

    상수/헬퍼가 남아 있으면 다음 라운드에서 "원본이 없으니 일단 placeholder" 가 되살아난다.
    """
    assert not hasattr(deid_sim, "_write_placeholder")
    assert not hasattr(deid_sim, "PLACEHOLDER_BYTES")


# ── ★ [R]: sweep 이 미래 mtime 고아를 건너뛰던 결함 ─────────────────
def test_미래_mtime_고아도_기동_sweep이_정리한다(tmp_path) -> None:
    """공유 스토리지 시각 반올림/시계 차이로 mtime 이 미래면 구 구현은 <b>영원히 건너뛰었다</b>.

    ``now - mtime`` 이 음수라 ``< max_age_sec(0.0)`` 이 참이 되어 continue 했다. 그 결과
    M-1 방어의 회수 경로(sweep)가 통째로 동작하지 않아 잘린 임시파일이 계속 남는다.
    """
    # given — 허용 출력 루트 하위 .mock-tmp 에 고아 2건, mtime 은 미래
    base = tmp_path / "storage"
    out_dir = base / "videos" / "10" / "deid"
    out_dir.mkdir(parents=True)
    orphans = []
    future = time.time() + 5.0
    for _ in range(2):
        temp = deid_sim._temp_path_for(out_dir / "a-mask.mp4")
        assert temp is not None
        temp.write_bytes(_partial_mp4())
        os.utime(temp, (future, future))
        orphans.append(temp)

    # when — 기동 sweep(나이 무관)
    removed = deid_sim.sweep_orphan_temp_files(str(base))

    # then
    assert removed == 2, "미래 mtime 고아를 건너뛰었다(sweep 무력화)"
    assert not any(o.exists() for o in orphans)


def test_미래_mtime_이어도_운영중_sweep은_살아있는_temp를_지키다(tmp_path) -> None:
    # given — 나이 클램프(0) 이므로 운영 중 상한(600초+) 아래로 판정돼 보호되어야 한다
    out_dir = tmp_path / "deid"
    out_dir.mkdir()
    temp = deid_sim._temp_path_for(out_dir / "a-mask.mp4")
    assert temp is not None
    temp.write_bytes(_partial_mp4())
    future = time.time() + 5.0
    os.utime(temp, (future, future))

    # when
    removed = deid_sim.sweep_temp_dir(out_dir / deid_sim.TEMP_DIR_NAME)

    # then
    assert removed == 0
    assert temp.is_file()


# ── ★ MEDIUM-2: sweep 나이 상한과 인코딩 타임아웃의 안전 마진 ──────
def test_sweep_나이상한은_인코딩_타임아웃보다_충분히_크다() -> None:
    """같은 값(구 600 == FFMPEG_TIMEOUT_SEC)이면 마진 0 — 살아있는 temp 를 지울 수 있다."""
    # given / when / then
    assert deid_sim.TEMP_ORPHAN_MAX_AGE_SEC > (
        deid_sim.FFMPEG_TIMEOUT_SEC + deid_sim.FFMPEG_ACQUIRE_TIMEOUT_SEC
    ), "sweep 나이 상한이 인코딩 최악 소요(실행+대기)보다 작거나 같다"
    assert deid_sim.TEMP_ORPHAN_SAFETY_MARGIN_SEC > 0


def test_인코딩_타임아웃만큼_오래된_temp는_lazy_sweep이_지우지_않는다(
    tmp_path,
) -> None:
    """같은 export 디렉터리에 후속 산출이 들어와도 <b>진행 중</b> 인코딩을 깨지 않아야 한다."""
    # given — 타임아웃 직전까지 인코딩 중인 temp(= 아직 살아있다)
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    out_dir = storage / "deid"
    out_dir.mkdir(parents=True)
    (in_dir / "b.mp4").write_bytes(_valid_artifact_bytes())
    live = deid_sim._temp_path_for(out_dir / "a-mask.mp4")
    assert live is not None
    live.write_bytes(_partial_mp4())
    aged = time.time() - float(deid_sim.FFMPEG_TIMEOUT_SEC)
    os.utime(live, (aged, aged))

    # when — 후속 요청의 lazy sweep 이 도는 경로
    deid_sim.produce_deid_outputs(
        export_path=str(out_dir),
        input_path=str(in_dir),
        outputs=[("b.mp4", "b-mask.mp4")],
        output_base=str(storage),
        input_base=str(storage),
    )

    # then — 살아있는 temp 는 보존된다
    assert live.is_file(), "진행 중 인코딩의 임시파일을 sweep 이 삭제했다"
