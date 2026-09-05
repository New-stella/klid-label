"""실제 비식별 엔진(``app.services.deid_engine``) 회귀.

이 파일의 <b>가장 중요한 계약</b>은 "모델이 없으면 목의 기존 동작이 그대로다" 이다. 검출 모델은
git 에 없으므로 CI 는 항상 그 경로를 탄다 — 즉 아래 폴백 시험들이 실제로 도는 시험이고, 실제
마스킹 시험은 모델이 있는 개발자 환경에서만 돈다(skip).
"""

from __future__ import annotations

import numpy as np
import pytest

from app.services import deid_engine as E


# ── 모델 부재 = 기존 동작 보존 (CI 가 실제로 타는 경로) ───────────
def test_모델이_없으면_엔진은_비활성이다(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(E, "MODELS_DIR", tmp_path)
    ok, missing = E.models_available()
    assert ok is False
    assert set(missing) == set(E.MODEL_FILES)
    assert E.engine_available() is False


def test_엔진이_비활성이면_렌더는_시도조차_하지_않는다(monkeypatch, tmp_path) -> None:
    """호출측은 ``None`` 을 받고 워터마크로 폴백한다 — 예외가 밖으로 나오면 안 된다."""
    monkeypatch.setattr(E, "MODELS_DIR", tmp_path)
    src = tmp_path / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"X" * 600)
    out = tmp_path / "a-mask.mp4"
    # ffprobe 가 영상으로 인식하지 못하므로 None 이어야 한다(예외 아님).
    assert E.render_masked(src, out, ffmpeg="ffmpeg") is None


def test_원본이_영상이_아니면_None_이고_예외를_던지지_않는다(tmp_path) -> None:
    src = tmp_path / "not-video.mp4"
    src.write_text("이것은 영상이 아니다")
    assert E.render_masked(src, tmp_path / "o.mp4", ffmpeg="ffmpeg") is None


def test_존재하지_않는_ffmpeg_는_None_으로_흡수된다(tmp_path) -> None:
    src = tmp_path / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"X" * 600)
    assert E.render_masked(src, tmp_path / "o.mp4", ffmpeg="/nonexistent/ffmpeg") is None


# ── 마스킹 적용 — 개인정보 관점의 핵심 계약 ───────────────────────
def _flat_region(w: int, h: int) -> np.ndarray:
    """가로로 긴 '간판' 모사 — 세로가 짧은 영역에서 마스킹이 약해지는지 본다."""
    rng = np.random.default_rng(0)
    return rng.integers(0, 255, (h, w, 3), dtype=np.uint8)


@pytest.mark.parametrize("mask_type", [E.MASK_COLOR, E.MASK_MOSAIC, E.MASK_BLUR])
def test_가로로_긴_텍스트영역도_실제로_뭉개진다(mask_type: int) -> None:
    """★회귀 — 구 모자이크는 블록 <b>크기</b>를 ``min(w,h)//8`` 로 잡아 200x20 같은 간판 영역에서
    2배 축소밖에 하지 않았고, 산출 영상에서 상호명이 그대로 읽혔다(육안 확인으로 발견).
    지금은 블록 <b>개수</b>를 고정하므로 납작한 영역도 확실히 지워진다.
    """
    cv2 = pytest.importorskip("cv2")
    w, h = 200, 20
    frame = np.zeros((60, 300, 3), dtype=np.uint8)
    frame[10 : 10 + h, 20 : 20 + w] = _flat_region(w, h)
    before = frame[10 : 10 + h, 20 : 20 + w].copy()

    E._apply_mask(cv2, frame, (20, 10, w, h), mask_type)
    after = frame[10 : 10 + h, 20 : 20 + w]

    # 인접 픽셀 간 변화량(고주파 성분)이 크게 줄어야 '뭉갰다'고 할 수 있다.
    def detail(a: np.ndarray) -> float:
        return float(np.abs(np.diff(a.astype(np.int16), axis=1)).mean())

    assert detail(after) < detail(before) * 0.5, (
        f"masking_type={mask_type} 이 납작한 영역을 충분히 뭉개지 못했다"
    )


def test_사람은_전신이_아니라_상단_머리영역만_가린다() -> None:
    """전신을 덮으면 행동 라벨링 대상인 사람 자체가 사라져 학습데이터 가치가 없다."""
    x, y, w, h = 10, 20, 40, 200
    nx, ny, nw, nh = E._expand((x, y, w, h), 1.0, 1000, 1000, E.KIND_PERSON)
    assert nh < h * 0.5
    assert ny < y + h * 0.5  # 상단에 위치


def test_마스킹영역_배율은_안전범위로_죈다() -> None:
    """계약 밖 값이 와도 프레임을 통째로 덮거나 0 이 되지 않는다."""
    box = (100, 100, 40, 40)
    tiny = E._expand(box, 0.001, 1000, 1000, E.KIND_FACE)
    huge = E._expand(box, 999.0, 1000, 1000, E.KIND_FACE)
    # _expand 자체는 배율을 죄지 않는다 — 죄는 곳은 render_masked 다.
    # 여기서는 프레임 밖으로 나가지 않는 것만 확인한다.
    for nx, ny, nw, nh in (tiny, huge):
        assert nx >= 0 and ny >= 0
        assert nx + nw <= 1000 and ny + nh <= 1000


def test_마스킹은_프레임_경계를_넘지_않는다() -> None:
    """경계 밖 좌표가 와도 예외 없이 흡수돼야 한다(외부 모델 출력은 신뢰하지 않는다)."""
    cv2 = pytest.importorskip("cv2")
    frame = np.zeros((50, 50, 3), dtype=np.uint8)
    for box in ((-10, -10, 20, 20), (45, 45, 100, 100), (0, 0, 0, 0)):
        E._apply_mask(cv2, frame, box, E.MASK_BLUR)  # 예외가 나면 실패


# ── 실제 렌더 (모델이 있는 환경에서만) ────────────────────────────
_FIXTURE = (
    "../backend/src/test/resources/fixtures/marking-upload/sample-marking-640.mp4"
)


@pytest.mark.skipif(not E.engine_available(), reason="검출 모델 미조달 — 폴백 경로만 검증")
def test_실제_마스킹은_프레임수를_보존한다(tmp_path) -> None:
    """길이가 달라지면 호출측 길이 검증에서 탈락해 승격되지 않는다(조용한 절단 차단)."""
    from pathlib import Path

    src = Path(__file__).resolve().parents[1] / _FIXTURE
    if not src.is_file():
        pytest.skip("픽스처 영상 없음")
    out = tmp_path / "o.mp4"
    summary = E.render_masked(src, out, ffmpeg="ffmpeg", masking_range=1.2)
    assert summary is not None
    assert out.is_file() and out.stat().st_size > 512
    assert summary.frames > 0

# ── 번호판 fail-safe (실 CCTV 실측 근거) ──────────────────────────
def test_차량은_전체가_아니라_하단_번호판_자리만_가린다() -> None:
    """★실측 근거 — 실 CCTV 에서 번호판은 텍스트 검출기(0건)로도 전용 LPD 모델(진짜 번호판
    0건 · 표지판 오검출)로도 잡히지 않았고, 같은 프레임에서 차량은 15대가 정확히 검출됐다.
    그래서 차량 하단을 가린다. <b>차량 전체를 덮으면</b> 라벨링 대상인 차량이 사라진다.
    """
    x, y, w, h = 100, 100, 80, 60
    nx, ny, nw, nh = E._expand((x, y, w, h), 1.0, 1000, 1000, E.KIND_VEHICLE)
    assert nh < h * 0.5, "차량 높이 전체를 덮으면 안 된다"
    assert nw < w, "차량 폭 전체를 덮으면 안 된다"
    assert ny + nh <= y + h + 1, "번호판 띠는 차량 <b>하단</b>에 있어야 한다"
    assert ny > y + h * 0.5, "번호판 띠가 차량 상단에 걸리면 안 된다"


def test_사람과_차량은_가리는_부위가_반대다() -> None:
    """사람은 위(머리), 차량은 아래(번호판) — 이 방향이 뒤집히면 둘 다 헛되이 가린다."""
    box = (0, 0, 100, 100)
    _, py, _, ph = E._expand(box, 1.0, 500, 500, E.KIND_PERSON)
    _, vy, _, vh = E._expand(box, 1.0, 500, 500, E.KIND_VEHICLE)
    assert py < vy, "사람은 상단, 차량은 하단이어야 한다"


def test_얼굴_외_대상은_박스를_그대로_쓴다() -> None:
    """텍스트(간판)는 영역 자체가 가릴 대상이라 축소하지 않는다."""
    x, y, w, h = 10, 10, 200, 20
    nx, ny, nw, nh = E._expand((x, y, w, h), 1.0, 1000, 1000, E.KIND_TEXT)
    assert (nx, ny, nw, nh) == (x, y, w, h)


def test_YOLOX_대상에_사람과_차량이_모두_있다() -> None:
    """★한 번의 추론으로 둘 다 뽑는 것이 이 설계의 요점 — 종류별로 따로 돌리면 가장 비싼
    연산이 두 배가 된다."""
    kinds = set(E._YOLOX_WANTED.values())
    assert kinds == {E.KIND_PERSON, E.KIND_VEHICLE}
    # COCO: 0=person, 2=car, 3=motorcycle, 5=bus, 7=truck
    assert E._YOLOX_WANTED[0] == E.KIND_PERSON
    for c in (2, 3, 5, 7):
        assert E._YOLOX_WANTED[c] == E.KIND_VEHICLE


def test_얼굴_임계는_오검출을_줄이는_쪽으로_높게_잡는다() -> None:
    """놓친 얼굴은 사람 폴백이 덮지만, 오검출로 가린 영역은 그대로 품질 손실이다."""
    assert E.FACE_CONF_THRESHOLD >= 0.6

# ── 리포트 연결 (실측값 ↔ mock 폴백) ──────────────────────────────
def test_번호판_전용_모델은_조달목록에_없다() -> None:
    """★회귀 — 한때 ``MODEL_FILES`` 에 번호판 모델이 선언돼 있었는데 조달 스크립트는 그것을
    받지 않아, ``models_available()`` 이 <b>항상</b> 'plate 가 빠졌다'고 보고했다. 운영자가
    없는 문제를 쫓게 된다. 번호판은 차량 폴백으로 가리므로 전용 모델을 쓰지 않는다.
    """
    assert E.KIND_PLATE not in E.MODEL_FILES
    assert set(E.MODEL_FILES) == {E.KIND_FACE, E.KIND_TEXT, E.KIND_PERSON}


def test_모든_모델이_있으면_missing_은_비어야_한다(monkeypatch, tmp_path) -> None:
    for name in E.MODEL_FILES.values():
        (tmp_path / name).write_bytes(b"x")
    monkeypatch.setattr(E, "MODELS_DIR", tmp_path)
    ok, missing = E.models_available()
    assert ok is True
    assert missing == []


def test_요약은_대상별로_따로_센다() -> None:
    """리포트가 얼굴 수와 차량(=번호판 마스킹) 수를 구분해 실어야 한다."""
    s = E.DeidSummary()
    s.add(E.KIND_FACE, 3)
    s.add(E.KIND_VEHICLE, 5)
    s.add(E.KIND_PERSON, 2)
    s.add(E.KIND_TEXT, 7)
    assert (s.faces, s.vehicles, s.persons, s.texts) == (3, 5, 2, 7)
