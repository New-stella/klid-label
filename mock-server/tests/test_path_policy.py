"""경로 읽기·쓰기 경계 판정의 <b>단일 원천</b> 검증 (F-7).

구 구조는 같은 정책이 세 곳(``deid_sim.resolve_input_dir`` · ``deid_sim.input_root_configured`` ·
``media_probe.resolve_probe_target``)에 인라인 중복돼 있었고, 그중 저수준 헬퍼는
"base 가 비면 제한 없음"이라는 <b>fail-open</b> 계약이었다. 세 번째 소비자가 저수준 헬퍼만
쓰는 순간 조용히 fail-open 이 된다 — 이 프로젝트에는 "상태 게이트를 호출처마다 배선하면
반드시 샌다"는 재발 이력이 있다.

여기서는 ①정책 진입점이 fail-closed 인가 ②소비자들이 그 진입점을 실제로 쓰는가(재구현하지
않는가)를 검증한다.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.services import deid_sim, media_probe, path_policy


# ── 정책 진입점: fail-closed ──────────────────────────────────────
def test_허용루트_미설정이면_읽기_판정은_거부된다(tmp_path: Path) -> None:
    # given / when / then — 저수준 헬퍼는 '제한 없음'(기존 계약), 정책 진입점은 fail-closed
    path_policy.reset_warning()
    assert path_policy.resolve_input_dir(str(tmp_path), "") is not None
    assert path_policy.resolve_readable_dir(str(tmp_path), "") is None
    path_policy.reset_warning()


def test_허용루트_밖은_거부되고_안은_허용된다(tmp_path: Path) -> None:
    # given
    storage = tmp_path / "storage"
    inside = storage / "raw"
    inside.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()

    # when / then
    assert path_policy.resolve_readable_dir(str(inside), str(storage)) is not None
    assert path_policy.resolve_readable_dir(str(outside), str(storage)) is None


def test_경로순회로_허용루트를_탈출할_수_없다(tmp_path: Path) -> None:
    # given
    storage = tmp_path / "storage"
    (storage / "raw").mkdir(parents=True)
    (tmp_path / "outside").mkdir()

    # when / then — resolve 후 판정하므로 ../ 는 흡수된다(CWE-22)
    escaped = storage / "raw" / ".." / ".." / "outside"
    assert path_policy.resolve_readable_dir(str(escaped), str(storage)) is None


def test_쓰기_허용루트_미설정은_fail_closed다(tmp_path: Path) -> None:
    # given / when / then — 임의 절대경로 쓰기 차단(HIGH-1)
    assert path_policy.resolve_output_dir(str(tmp_path), "") is None
    assert path_policy.resolve_output_dir(str(tmp_path / "x"), str(tmp_path)) is not None


def test_콤마_구분_다중_루트를_모두_허용한다(tmp_path: Path) -> None:
    # given — co-locate 산출(Phase 5A) 형상
    a = tmp_path / "a"
    b = tmp_path / "b"
    (a / "in").mkdir(parents=True)
    (b / "in").mkdir(parents=True)
    bases = f"{a},{b}"

    # when / then
    assert path_policy.resolve_readable_dir(str(a / "in"), bases) is not None
    assert path_policy.resolve_readable_dir(str(b / "in"), bases) is not None
    assert path_policy.resolve_readable_dir(str(tmp_path / "c"), bases) is None


# ── 소비자가 단일 원천을 실제로 쓰는가 ────────────────────────────
def test_deid_sim은_경계_판정을_재구현하지_않는다() -> None:
    # given / when / then — 재노출일 뿐 별도 구현이 아니다(드리프트 원천 차단)
    assert deid_sim.resolve_output_dir is path_policy.resolve_output_dir
    assert deid_sim.input_root_configured is path_policy.read_root_configured
    assert deid_sim.base_tokens is path_policy.base_tokens
    # ★ 단, fail-open 저수준 헬퍼(``resolve_input_dir``)는 <b>재노출하지 않는다</b>(#6).
    #   공개 이름으로 남아 있으면 새 소비자가 정책 진입점(``resolve_readable_dir``)을 건너뛰고
    #   그것을 집어 쓰는 순간 조용히 fail-open 이 된다.
    assert not hasattr(deid_sim, "resolve_input_dir")


def test_원본_복사_경로도_정책_진입점을_통과한다(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given — 정책 진입점을 '항상 거부'로 바꾸면 복사 소스 해석도 함께 막혀야 한다
    storage = tmp_path / "storage"
    in_dir = storage / "raw"
    in_dir.mkdir(parents=True)
    (in_dir / "a.mp4").write_bytes(b"x")
    assert deid_sim._safe_source_path(str(in_dir), "a.mp4", str(storage)) is not None

    monkeypatch.setattr(path_policy, "resolve_readable_dir", lambda *a, **k: None)

    # when / then
    assert deid_sim._safe_source_path(str(in_dir), "a.mp4", str(storage)) is None


def test_길이조회_경로도_정책_진입점을_통과한다(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    video = tmp_path / "a.mp4"
    video.write_bytes(b"x")
    assert media_probe.resolve_probe_target(str(video), str(tmp_path)) is not None

    monkeypatch.setattr(path_policy, "resolve_readable_dir", lambda *a, **k: None)

    # when / then — media_probe 가 자체 판정을 들고 있지 않다는 증거
    assert media_probe.resolve_probe_target(str(video), str(tmp_path)) is None


def test_경고_플래그_초기화는_두_소비자에서_동일_원천을_리셋한다() -> None:
    # given — 어느 모듈의 reset 을 호출하든 같은 플래그가 초기화된다
    path_policy.reset_warning()
    assert path_policy.read_root_configured("") is False  # 1회 경고 소비
    assert path_policy._read_root_unset_warned is True

    # when
    deid_sim.reset_base_warning()
    # then
    assert path_policy._read_root_unset_warned is False

    path_policy.read_root_configured("")
    media_probe.reset_base_warning()
    assert path_policy._read_root_unset_warned is False
