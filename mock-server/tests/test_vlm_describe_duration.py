"""VLM describe 목 — 영상 실제 길이 기반 동적 시계열 생성 테스트 (Phase B).

기존 목은 영상 길이와 무관하게 16초 고정(8초 window × 2구간)만 만들었다. 짧은 영상엔 과하고
긴 영상엔 대부분 구간이 비어, 라벨링 화면 시계열 패널에서 실서비스처럼 확인할 수 없었다.
여기서는 ①요청 duration 힌트 → ②ffprobe 조회 → ③고정 폴백(16초) 3단 체인으로 길이를 정하고,
그 길이 <b>전체</b>를 덮는 구간 배열을 만드는 동작을 검증한다.

검증 축:
- 구간 계획(plan): 짧은/긴/초단편/초장편, 겹침·빈틈·역전 없음, BE @Size(max=500) 정합 상한.
- 폴백: ffprobe 미설치/실패/타임아웃/이상값 → 16초 graceful degrade(콜백 자체는 계속 발사).
- 보안: ffprobe 는 shell 없이 리스트 인자로만 호출하고(CWE-78), 절대경로 + 허용 루트 안의
  파일만 조회한다(CWE-22). 허용 루트 미설정이면 조회하지 않는다(fail-closed).
- 콘텐츠: 구간마다 다른 한글 설명(결정적 순환), BE @Size(max=2000) 이내.
"""

from __future__ import annotations

import re

import math
import os
import shutil
import subprocess
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

DESCRIBE_URL = "/v1/videovlm-klid/describe"
CALLBACK_URL = "http://klid-backend:8080/api/v1/vlm/callback"


@pytest.fixture(autouse=True)
def _fast_callback() -> Iterator[None]:
    """콜백 지연을 0 으로 만들어 BackgroundTasks 가 즉시 완료되게 한다."""
    import os

    from app.config import reload_settings

    prev = os.environ.get("MOCK_CALLBACK_DELAY_SECONDS")
    os.environ["MOCK_CALLBACK_DELAY_SECONDS"] = "0"
    reload_settings()
    yield
    if prev is None:
        os.environ.pop("MOCK_CALLBACK_DELAY_SECONDS", None)
    else:
        os.environ["MOCK_CALLBACK_DELAY_SECONDS"] = prev
    reload_settings()


@pytest.fixture(autouse=True)
def _reset_probe_warning() -> Iterator[None]:
    """probe 경고 1회 플래그를 테스트마다 초기화한다."""
    from app.services import media_probe

    media_probe.reset_base_warning()
    yield
    media_probe.reset_base_warning()


def _describe_body(**over: object) -> dict:
    body = {
        "request_id": "d0000001",
        "media": {
            "type": "video",
            "source_type": "path",
            "path": "/app/storage/deidentified/videos/1/deidentified.mp4",
            "frame_policy": {"mode": "frame_interval", "framerate": 25},
        },
        "callback_url": CALLBACK_URL,
    }
    body.update(over)
    return body


def _windows_of(payload: dict) -> list[tuple[int, int]]:
    """콜백 서술에서 구간 목록을 되읽는다.

    ★ 콜백의 ``results`` 는 KLID 규격 §2.8 상 **단일 객체**이고 항목은 ``description`` 하나다.
    구 규격의 구간 배열은 폐기됐으므로, 목이 만드는 서술에서 구간을 파싱해 기존 커버리지 단정을
    그대로 유지한다. 구간 계획 자체는 ``mock_describe_results`` 를 직접 부르는 단위 테스트가
    따로 고정한다.

    ⚠ 목의 묘사 전문은 이제 <영상 1건당 한 벌>인 규격 형식(``- 라벨: 값``)이고, 구간 서술은
    그 안의 **「상황」 한 줄**에 이어 붙는다. 구 형식("- 0~8초: ...")은 구간마다 5항목 블록을
    반복하며 첫 줄을 뭉갰던 것이라 폐기됐다.
    """
    description = payload["results"]["description"]
    situation_lines = [
        line for line in description.splitlines() if line.lstrip().startswith("- 상황:")
    ]
    if not situation_lines:
        return []
    return [
        (int(m.group(1)), int(m.group(2)))
        for m in re.finditer(r"(\d+)~(\d+)초 구간에서", situation_lines[0])
    ]


def _patch_capture(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, dict]]:
    from app.services import vlm_sim

    captured: list[tuple[str, dict]] = []

    async def _recorder(url: str, payload: dict) -> None:
        captured.append((url, payload))

    monkeypatch.setattr(vlm_sim, "fire_callback", _recorder)
    return captured


def _expected_total(duration: float) -> int:
    """구간이 덮어야 할 총 초 — 영상 <b>꼬리를 포함</b>하도록 올림한다(#9).

    구 헬퍼는 ``max(1, int(duration))``(내림)이라 100.9초 영상의 마지막 0.9초 미커버를
    '정답'으로 고정해 경계 결함을 영원히 잡지 못했다.
    """
    return max(1, math.ceil(min(duration, 86_400)))


def _assert_contiguous(windows: list[tuple[int, int]], total: int) -> None:
    """구간이 0 부터 total 까지 겹침·빈틈·역전 없이 이어지는지 단정한다."""
    assert windows, "최소 1구간은 있어야 한다"
    assert windows[0][0] == 0
    for start, end in windows:
        assert isinstance(start, int) and isinstance(end, int)
        assert end > start, "0 길이 구간 금지"
    for (_, prev_end), (next_start, _) in zip(windows, windows[1:]):
        assert next_start == prev_end, "겹침/빈틈 없이 이어져야 한다"
    assert windows[-1][1] == total, "마지막 구간은 영상 끝까지 덮어야 한다"


# ── 구간 계획 ────────────────────────────────────────────────────
def test_짧은_영상은_한_구간만_생성되고_영상길이를_넘지_않는다() -> None:
    # given — window(8초)보다 짧은 3초 영상
    from app.services import vlm_sim

    # when
    windows = vlm_sim.plan_describe_windows(3)
    # then
    assert windows == [(0, 3)]


def test_1초_미만_영상도_0길이_구간_없이_최소_한_구간을_보장한다() -> None:
    # given — 0.4초(초단편)
    from app.services import vlm_sim

    # when
    windows = vlm_sim.plan_describe_windows(0.4)
    # then — start==end 인 0 길이 구간은 BE META_KEY 중복/무의미 구간이라 금지
    assert len(windows) == 1
    start, end = windows[0]
    assert start == 0 and end == 1


def test_긴_영상은_8초_window로_전체_길이를_모두_커버한다() -> None:
    # given — 120초
    from app.services import vlm_sim

    # when
    windows = vlm_sim.plan_describe_windows(120)
    # then
    assert len(windows) == 15
    _assert_contiguous(windows, 120)


@pytest.mark.parametrize("duration", [1, 2, 7, 8, 9, 16, 31.9, 60, 137, 3600])
def test_구간은_겹치지_않고_빈틈없이_이어진다(duration: float) -> None:
    # given / when
    from app.services import vlm_sim

    windows = vlm_sim.plan_describe_windows(duration)
    # then — 꼬리까지(ceil) 끝까지 연속
    _assert_contiguous(windows, _expected_total(duration))


@pytest.mark.parametrize(
    ("duration", "expected_end"),
    [(100.9, 101), (100.0, 100), (0.4, 1), (8.1, 9), (23.5, 24)],
)
def test_소수점_길이의_꼬리도_구간에_포함된다(duration: float, expected_end: int) -> None:
    """#9 — 구 구현(floor)은 100.9초 영상의 마지막 0.9초를 <b>미커버</b>로 남겼다.

    구간 메타데이터에서 미커버는 '그 시간대 정보 없음'이라는 결손이고, 1초 미만 초과는
    소비 측(BE 는 구간을 그대로 저장·표시)에 무해하다 — 결손보다 미세 초과를 택한다.
    """
    # given / when
    from app.services import vlm_sim

    windows = vlm_sim.plan_describe_windows(duration)

    # then — 마지막 구간의 끝이 영상 길이 이상이다(꼬리 포함)
    assert windows[-1][1] == expected_end
    assert windows[-1][1] >= duration
    _assert_contiguous(windows, expected_end)


def test_초장편_영상도_세그먼트_상한을_넘지_않고_끝까지_커버한다() -> None:
    # given — 24시간을 넘는 값(BE Segment @Max(86400) 상한으로 clamp)
    from app.services import vlm_sim

    # when
    windows = vlm_sim.plan_describe_windows(100_000)
    # then — BE VlmResultRequest.results @Size(max=500) 아래(450)이면서 truncate 없이 전체 커버
    assert len(windows) <= vlm_sim.MAX_DESCRIBE_SEGMENTS
    assert vlm_sim.MAX_DESCRIBE_SEGMENTS < 500
    _assert_contiguous(windows, vlm_sim.MAX_DURATION_SEC)


def test_상한_직전_길이도_window를_늘려_전체를_커버한다() -> None:
    # given — 8초 window 로는 450구간을 넘는 길이(3601초 → 451구간)
    from app.services import vlm_sim

    # when
    windows = vlm_sim.plan_describe_windows(3608)
    # then — 뒷부분을 잘라내지 않고 window 를 늘려 균등 재분배
    assert len(windows) <= vlm_sim.MAX_DESCRIBE_SEGMENTS
    _assert_contiguous(windows, 3608)


@pytest.mark.parametrize("bad", [None, 0, -5, "abc", float("nan"), float("inf"), object()])
def test_duration_이상값은_폴백_16초로_치환된다(bad: object) -> None:
    # given / when
    from app.services import vlm_sim

    windows = vlm_sim.plan_describe_windows(bad)
    # then — 구 고정 동작(8초 × 2구간)과 동일
    assert windows == [(0, 8), (8, 16)]


def test_동일_입력은_항상_동일_결과를_만든다() -> None:
    # given / when
    from app.services import vlm_sim

    first = vlm_sim.mock_describe_results(45)
    second = vlm_sim.mock_describe_results(45)
    # then — 랜덤 금지(테스트 재현성)
    assert first == second


# ── 더미 콘텐츠 품질 ─────────────────────────────────────────────
def test_구간마다_서로_다른_한글_설명이_생성된다() -> None:
    # given / when — 10구간(80초)
    from app.services import vlm_sim

    results = vlm_sim.mock_describe_results(80)
    # then — 인접 구간 설명이 모두 달라야 하고, 전체적으로도 다양해야 한다
    descriptions = [r["description"] for r in results]
    assert len(results) == 10
    for prev, cur in zip(descriptions, descriptions[1:]):
        assert prev != cur
    assert len(set(descriptions)) == len(descriptions)


def test_설명은_BE_상한_2000자_이내이고_구간초를_담는다() -> None:
    # given / when
    from app.services import vlm_sim

    results = vlm_sim.mock_describe_results(60)
    # then
    for seg in results:
        assert 0 < len(seg["description"]) <= 2000
        assert f"{seg['start_sec']}~{seg['end_sec']}초" in seg["description"]


# ── duration 3단 폴백 체인 ───────────────────────────────────────
def test_요청_duration_힌트가_있으면_ffprobe를_조회하지_않는다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given — 힌트 우선(①)
    from app.services import media_probe, vlm_sim

    calls: list[object] = []
    monkeypatch.setattr(
        media_probe, "probe_duration_sec", lambda *a, **k: calls.append(a) or 999.0
    )
    # when
    duration = vlm_sim.resolve_describe_duration("/app/storage/x.mp4", 40)
    # then
    assert duration == 40
    assert calls == []


def test_힌트가_없으면_ffprobe_조회값을_사용한다(monkeypatch: pytest.MonkeyPatch) -> None:
    # given — ②ffprobe
    from app.services import media_probe, vlm_sim

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: 24.0)
    # when
    duration = vlm_sim.resolve_describe_duration("/app/storage/x.mp4", None)
    # then
    assert duration == 24.0


def test_힌트도_ffprobe도_없으면_폴백_16초를_사용한다(monkeypatch: pytest.MonkeyPatch) -> None:
    # given — ③폴백
    from app.services import media_probe, vlm_sim

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: None)
    # when
    duration = vlm_sim.resolve_describe_duration(None, None)
    # then
    assert duration == vlm_sim.FALLBACK_DURATION_SEC == 16.0


def test_힌트가_이상값이면_ffprobe_조회로_넘어간다(monkeypatch: pytest.MonkeyPatch) -> None:
    # given — 힌트가 음수(신뢰 불가)
    from app.services import media_probe, vlm_sim

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: 30.0)
    # when
    duration = vlm_sim.resolve_describe_duration("/app/storage/x.mp4", -3)
    # then
    assert duration == 30.0


# ── ffprobe 실패 격리 (graceful degrade) ─────────────────────────
def _allow_root(monkeypatch: pytest.MonkeyPatch, root: Path) -> None:
    """probe 허용 루트를 지정한다."""
    from app.config import reload_settings

    monkeypatch.setenv("MOCK_INPUT_BASE", str(root))
    reload_settings()


def test_ffprobe_미설치면_조회하지_않고_None(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    monkeypatch.setattr(media_probe.shutil, "which", lambda name: None)
    # when
    assert media_probe.probe_duration_sec(str(video)) is None


def test_ffprobe_실행실패는_예외없이_None(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    monkeypatch.setattr(media_probe.shutil, "which", lambda name: "/usr/bin/ffprobe")

    def _boom(*args: object, **kwargs: object) -> None:
        raise OSError(13, "denied")

    monkeypatch.setattr(media_probe.subprocess, "run", _boom)
    # when / then — 예외가 전파되면 콜백 발사가 통째로 죽는다
    assert media_probe.probe_duration_sec(str(video)) is None


def test_ffprobe_타임아웃은_예외없이_None(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    monkeypatch.setattr(media_probe.shutil, "which", lambda name: "/usr/bin/ffprobe")

    def _timeout(*args: object, **kwargs: object) -> None:
        raise subprocess.TimeoutExpired(cmd="ffprobe", timeout=1)

    monkeypatch.setattr(media_probe.subprocess, "run", _timeout)
    # when / then
    assert media_probe.probe_duration_sec(str(video)) is None


@pytest.mark.parametrize("stdout", [b"", b"N/A\n", b"0\n", b"-1\n", b"nan\n", b"inf\n"])
def test_ffprobe_출력이_이상값이면_None(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, stdout: bytes
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    _stub_ffprobe(monkeypatch, stdout=stdout)
    # when / then
    assert media_probe.probe_duration_sec(str(video)) is None


def test_ffprobe_비정상_종료코드는_None(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    _stub_ffprobe(monkeypatch, stdout=b"12.0\n", returncode=1)
    # when / then
    assert media_probe.probe_duration_sec(str(video)) is None


def test_ffprobe_정상_출력은_초단위_실수로_파싱된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    _stub_ffprobe(monkeypatch, stdout=b"32.533000\n")
    # when
    duration = media_probe.probe_duration_sec(str(video))
    # then
    assert duration is not None and math.isclose(duration, 32.533)


# ── ffprobe 보안 (CWE-78 / CWE-22) ───────────────────────────────
def _stub_ffprobe(
    monkeypatch: pytest.MonkeyPatch, *, stdout: bytes = b"10.0\n", returncode: int = 0
) -> list[dict]:
    """subprocess.run 을 가로채 호출 인자를 수집한다(실제 실행 없음)."""
    from app.services import media_probe

    calls: list[dict] = []

    class _Completed:
        def __init__(self) -> None:
            self.returncode = returncode
            self.stdout = stdout
            self.stderr = b""

    def _run(cmd: object, **kwargs: object) -> _Completed:
        calls.append({"cmd": cmd, **kwargs})
        return _Completed()

    monkeypatch.setattr(media_probe.shutil, "which", lambda name: "/usr/bin/ffprobe")
    monkeypatch.setattr(media_probe.subprocess, "run", _run)
    return calls


def test_ffprobe는_shell없이_리스트인자로_호출된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    calls = _stub_ffprobe(monkeypatch)
    # when
    media_probe.probe_duration_sec(str(video))
    # then — CWE-78: shell=False + 리스트 인자 + stdin 차단 + 타임아웃 상한
    assert len(calls) == 1
    call = calls[0]
    assert isinstance(call["cmd"], list)
    assert call["shell"] is False
    assert call["stdin"] == subprocess.DEVNULL
    assert call["timeout"] == media_probe.FFPROBE_TIMEOUT_SEC
    assert call["cmd"][-1] == str(video.resolve())


@pytest.mark.parametrize(
    "bad_path",
    ["relative/a.mp4", "-nostdin.mp4", "", "   ", None, 12345],
)
def test_안전하지_않은_경로는_ffprobe를_실행하지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, bad_path: object
) -> None:
    # given — 상대경로/옵션 오인식(-) /빈값/비문자열
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    calls = _stub_ffprobe(monkeypatch)
    # when
    result = media_probe.probe_duration_sec(bad_path)
    # then
    assert result is None
    assert calls == []


def test_허용루트_밖의_경로는_거부된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given — 허용 루트는 tmp_path/allowed 인데 밖의 파일을 조회 시도
    from app.services import media_probe

    allowed = tmp_path / "allowed"
    allowed.mkdir()
    outside = tmp_path / "outside.mp4"
    outside.write_bytes(b"x")
    _allow_root(monkeypatch, allowed)
    calls = _stub_ffprobe(monkeypatch)
    # when
    result = media_probe.probe_duration_sec(str(outside))
    # then — CWE-22
    assert result is None
    assert calls == []


def test_경로순회로_허용루트를_탈출하면_거부된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    allowed = tmp_path / "allowed"
    allowed.mkdir()
    outside = tmp_path / "secret.mp4"
    outside.write_bytes(b"x")
    _allow_root(monkeypatch, allowed)
    calls = _stub_ffprobe(monkeypatch)
    # when — allowed/../secret.mp4
    result = media_probe.probe_duration_sec(str(allowed / ".." / "secret.mp4"))
    # then
    assert result is None
    assert calls == []


def test_허용루트_미설정이면_조회하지_않는다_fail_closed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given — MOCK_INPUT_BASE / MOCK_OUTPUT_BASE 둘 다 미설정
    from app.config import reload_settings
    from app.services import media_probe

    monkeypatch.setenv("MOCK_INPUT_BASE", "")
    monkeypatch.setenv("MOCK_OUTPUT_BASE", "")
    reload_settings()
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    calls = _stub_ffprobe(monkeypatch)
    # when
    result = media_probe.probe_duration_sec(str(video))
    # then
    assert result is None
    assert calls == []


def test_디렉터리_경로는_조회하지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    folder = tmp_path / "dir"
    folder.mkdir()
    calls = _stub_ffprobe(monkeypatch)
    # when / then
    assert media_probe.probe_duration_sec(str(folder)) is None
    assert calls == []


# ── 실제 ffprobe 연동 (도구 있을 때만) ───────────────────────────
def test_실제_영상_길이를_조회해_구간을_생성한다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """ffmpeg/ffprobe 가 있는 환경에서 <b>실제</b> 영상으로 조회~구간생성까지 확인한다.

    stub 만으로는 ffprobe 인자 조합(-show_entries/-of)이 실제로 duration 을 뽑는지 검증되지 않는다.
    """
    from app.services import media_probe, vlm_sim

    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe 미설치 환경")

    # given — 5초짜리 테스트 영상 생성
    video = tmp_path / "sample.mp4"
    subprocess.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=64x64:d=5",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", os.fspath(video),
        ],
        shell=False,
        capture_output=True,
        stdin=subprocess.DEVNULL,
        timeout=60,
        check=False,
    )
    if not video.is_file() or video.stat().st_size == 0:
        pytest.skip("테스트 영상 생성 실패(인코더 부재)")
    _allow_root(monkeypatch, tmp_path)

    # when
    duration = media_probe.probe_duration_sec(str(video))

    # then — 약 5초 → 8초 window 1구간. 끝값은 실제 조회 길이의 올림(꼬리 포함, #9)이다.
    assert duration is not None and 4.5 <= duration <= 5.6
    assert vlm_sim.plan_describe_windows(duration) == [(0, math.ceil(duration))]


# ── describe 콜백 e2e ────────────────────────────────────────────
def test_describe_콜백은_영상_길이_전체를_커버하는_구간을_담는다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 40초 영상
    from app.services import media_probe

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: 40.0)
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then — 5구간(8초 × 5)이 0~40초를 연속 커버
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "completed"
    windows = _windows_of(payload)
    assert len(windows) == 5
    _assert_contiguous(windows, 40)


def test_describe_콜백_서술은_BE계약_길이_안에_들고_구간이_정수_초다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    from app.services import media_probe

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: 33.0)
    captured = _patch_capture(monkeypatch)
    # when
    client.post(DESCRIBE_URL, json=_describe_body())
    # then — BE VlmResultRequest.Results.description @Size(max=2000) 안에 들어야 한다.
    #   구 계약(구간 배열의 정수 start/end)은 폐기됐으나, 목이 만드는 서술 안의 구간 표기는
    #   여전히 정수 초여야 한다(서술이 사람에게 읽히는 형태이므로).
    _, payload = captured[0]
    description = payload["results"]["description"]
    assert description.strip() and len(description) <= 2000
    windows = _windows_of(payload)
    assert windows, "서술에서 구간을 하나도 읽지 못했다"
    for start, end in windows:
        assert 0 <= start < end <= 86_400


def test_describe_요청의_duration_힌트를_우선_사용한다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — media.duration_sec 힌트(목 전용 확장)
    from app.services import media_probe

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: 16.0)
    captured = _patch_capture(monkeypatch)
    media = {
        "type": "video",
        "source_type": "path",
        "path": "/app/storage/deidentified/videos/1/deidentified.mp4",
        "duration_sec": 64,
    }
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body(media=media))
    # then
    assert res.status_code == 202
    _, payload = captured[0]
    assert len(_windows_of(payload)) == 8


def test_ffprobe가_실패해도_describe_콜백은_폴백_구간으로_발사된다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — probe 가 실패(None)해도 콜백은 죽지 않아야 한다
    from app.services import media_probe

    monkeypatch.setattr(media_probe, "probe_duration_sec", lambda *a, **k: None)
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then — 폴백 16초 → 2구간
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    assert payload["status"] == "completed"
    assert _windows_of(payload) == [(0, 8), (8, 16)]


def test_probe가_예외를_던져도_describe_콜백은_발사된다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 예상 못 한 예외(방어적 격리)
    from app.services import media_probe

    def _boom(*args: object, **kwargs: object) -> float:
        raise RuntimeError("unexpected")

    monkeypatch.setattr(media_probe, "probe_duration_sec", _boom)
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body())
    # then
    assert res.status_code == 202
    assert len(captured) == 1
    assert captured[0][1]["status"] == "completed"


def test_실제_영상으로_describe_요청하면_그_길이만큼_구간이_콜백된다(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """라우터→백그라운드→ffprobe→구간생성 <b>전 경로</b>를 목 없이 확인한다(도구 있을 때만)."""
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe 미설치 환경")

    # given — 20초 영상 + 허용 루트
    video = tmp_path / "deidentified.mp4"
    subprocess.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=64x64:d=20",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", os.fspath(video),
        ],
        shell=False,
        capture_output=True,
        stdin=subprocess.DEVNULL,
        timeout=60,
        check=False,
    )
    if not video.is_file() or video.stat().st_size == 0:
        pytest.skip("테스트 영상 생성 실패(인코더 부재)")
    _allow_root(monkeypatch, tmp_path)
    captured = _patch_capture(monkeypatch)
    media = {"type": "video", "source_type": "path", "path": os.fspath(video)}

    # when
    res = client.post(DESCRIBE_URL, json=_describe_body(media=media))

    # then — 20초 → 8+8+나머지 = 3구간. 마지막 끝값은 실제 길이의 올림(20 또는 21)이다.
    assert res.status_code == 202
    assert len(captured) == 1
    _, payload = captured[0]
    windows = _windows_of(payload)
    assert windows[:2] == [(0, 8), (8, 16)]
    assert len(windows) == 3
    assert windows[2][0] == 16 and windows[2][1] >= 20


# ── F-5: ffprobe / describe 자원 상한 (CWE-400/CWE-770) ──────────
def test_ffprobe_동시_실행_상한을_넘으면_조회를_포기하고_폴백한다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """describe 는 무인증 + 접수 제한이 없다 — ffprobe 도 ffmpeg 와 동형 상한이 필요하다."""
    # given — 세마포어를 전부 선점한 상태
    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    calls = _stub_ffprobe(monkeypatch)
    monkeypatch.setattr(media_probe, "FFPROBE_ACQUIRE_TIMEOUT_SEC", 0.05)
    # 세마포어는 용도별로 분리돼 있다(#4) — describe 는 VLM 것을 쓴다.
    vlm_semaphore = media_probe.semaphore_for(media_probe.PURPOSE_VLM)
    held = [
        vlm_semaphore.acquire() for _ in range(media_probe.FFPROBE_MAX_CONCURRENCY)
    ]

    # when
    try:
        result = media_probe.probe_duration_sec(str(video))
    finally:
        for _ in held:
            vlm_semaphore.release()

    # then — 프로세스를 아예 띄우지 않고 폴백(None) 신호
    assert result is None
    assert calls == []
    # 그리고 상한이 풀리면 다시 정상 조회된다(영구 차단 아님)
    assert media_probe.probe_duration_sec(str(video)) == 10.0


def test_ffprobe_동시_실행수는_상한_이하로_유지된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # given — 여러 스레드가 동시에 길이 조회를 시도한다
    import threading
    import time

    from app.services import media_probe

    _allow_root(monkeypatch, tmp_path)
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    monkeypatch.setattr(media_probe, "FFPROBE_ACQUIRE_TIMEOUT_SEC", 10.0)
    monkeypatch.setattr(media_probe.shutil, "which", lambda name: "/usr/bin/ffprobe")

    lock = threading.Lock()
    state = {"now": 0, "peak": 0}

    class _Completed:
        returncode = 0
        stdout = b"10.0\n"
        stderr = b""

    def _slow(cmd: object, **kwargs: object) -> _Completed:
        with lock:
            state["now"] += 1
            state["peak"] = max(state["peak"], state["now"])
        time.sleep(0.05)
        with lock:
            state["now"] -= 1
        return _Completed()

    monkeypatch.setattr(media_probe.subprocess, "run", _slow)

    # when — 6개 동시 조회
    threads = [
        threading.Thread(target=media_probe.probe_duration_sec, args=(str(video),))
        for _ in range(6)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)

    # then
    assert state["peak"] <= media_probe.FFPROBE_MAX_CONCURRENCY


def test_describe_조회가_동시상한을_넘으면_즉시_폴백_페이로드로_콜백한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """BackgroundTask 무제한 적재 방어 — 상한 초과 요청은 조회 없이 폴백 길이로 콜백한다."""
    # given — in-flight 가 이미 상한
    import asyncio

    from app.services import media_probe, vlm_sim

    calls: list[object] = []
    monkeypatch.setattr(
        media_probe, "probe_duration_sec", lambda *a, **k: calls.append(a) or 400.0
    )
    monkeypatch.setattr(vlm_sim, "MAX_DESCRIBE_PROBE_INFLIGHT", 0)

    # when
    payload = asyncio.run(
        vlm_sim.build_describe_callback_async("r1", "/app/storage/x.mp4", None)
    )

    # then — 조회는 생략되지만 콜백 페이로드는 정상 생성된다(폴백 16초 → 2구간)
    assert calls == []
    assert payload["status"] == "completed"
    assert _windows_of(payload) == [(0, 8), (8, 16)]


def test_describe_조회_카운터는_정상경로에서_원복된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given — 조회가 예외로 끝나도 in-flight 카운터가 새면 안 된다(누수 시 영구 폴백)
    import asyncio

    from app.services import media_probe, vlm_sim

    def _boom(*args: object, **kwargs: object) -> float:
        raise RuntimeError("probe boom")

    monkeypatch.setattr(media_probe, "probe_duration_sec", _boom)
    before = vlm_sim.describe_probe_inflight()

    # when
    payload = asyncio.run(
        vlm_sim.build_describe_callback_async("r2", "/app/storage/x.mp4", None)
    )

    # then
    assert payload["status"] == "completed"
    assert vlm_sim.describe_probe_inflight() == before == 0


def test_상한_초과_길이는_경고로_드러난다(caplog) -> None:
    """24시간 clamp 로 뒤가 통째로 미커버되는 것은 의도된 상한 — 조용히 사라지면 안 된다."""
    # given / when
    import logging

    from app.services import vlm_sim

    with caplog.at_level(logging.WARNING, logger="app.services.vlm_sim"):
        windows = vlm_sim.plan_describe_windows(100_000)

    # then
    assert windows[-1][1] == vlm_sim.MAX_DURATION_SEC
    assert any("구간 상한" in r.getMessage() for r in caplog.records)


def test_실패트리거_요청은_duration을_조회하지_않고_failed콜백(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 실패 트리거는 결과 구간 자체가 없으므로 probe 도 불필요
    from app.services import media_probe

    calls: list[object] = []
    monkeypatch.setattr(
        media_probe, "probe_duration_sec", lambda *a, **k: calls.append(a) or 40.0
    )
    captured = _patch_capture(monkeypatch)
    # when
    res = client.post(DESCRIBE_URL, json=_describe_body(request_id="fail-d1"))
    # then
    assert res.status_code == 202
    assert captured[0][1]["status"] == "failed"
    assert calls == []
