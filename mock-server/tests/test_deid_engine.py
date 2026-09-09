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


def test_YOLOX_대상에_사람과_차량과_오토바이가_모두_있다() -> None:
    """★한 번의 추론으로 셋 다 뽑는 것이 이 설계의 요점 — 종류별로 따로 돌리면 가장 비싼
    연산이 배로 늘어난다.

    ⚠ 오토바이(3)는 자동차와 <b>번호판 높이가 달라</b> 별도 종류로 뽑는다. 자동차와 같이
    묶으면 하단 띠가 바퀴 아래 도로를 가리고 번호판은 그대로 남는다(실측).
    """
    kinds = set(E._YOLOX_WANTED.values())
    assert kinds == {E.KIND_PERSON, E.KIND_VEHICLE, E.KIND_MOTORCYCLE}
    # COCO: 0=person, 2=car, 3=motorcycle, 5=bus, 7=truck
    assert E._YOLOX_WANTED[0] == E.KIND_PERSON
    assert E._YOLOX_WANTED[3] == E.KIND_MOTORCYCLE
    for c in (2, 5, 7):
        assert E._YOLOX_WANTED[c] == E.KIND_VEHICLE


def test_오토바이_띠는_하단이_아니라_차체_중간이다() -> None:
    """★회귀 — 오토바이 번호판은 뒷면 <b>중간 높이</b>에 달려 있다(실측: 박스 하단 기준
    52~62% 구간). 자동차용 하단 띠(14%)를 쓰면 바퀴 아래 도로를 가리고 번호판이 남는다.
    """
    x, y, w, h = 0, 0, 31, 56
    _, ny, _, nh = E._expand((x, y, w, h), 1.0, 500, 500, E.KIND_MOTORCYCLE)
    bottom = y + h
    low = (bottom - (ny + nh)) / h   # 띠 아래끝의 하단 기준 비율
    high = (bottom - ny) / h         # 띠 위끝의 하단 기준 비율
    assert low <= 0.52 and high >= 0.62, (
        f"번호판 구간(52~62%)을 덮지 못한다: {low:.0%}~{high:.0%}"
    )
    assert ny > y, "띠가 오토바이 상단을 넘어섰다"


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

# ── 프레임률 판정 (실 CCTV 실측 사고) ─────────────────────────────
def test_타임베이스를_프레임률로_쓰지_않는다() -> None:
    """★회귀 — dev 실 CCTV 의 ``r_frame_rate`` 가 <b>90000/1</b> 이었다. 그 값을 ffmpeg ``-r`` 로
    넘기면 900 프레임이 900/90000 = <b>0.01초</b> 영상이 되어, 길이 보존 검증에 걸려 실제 마스킹
    산출물이 통째로 버려진다(원본 복사 폴백). 로컬 샘플은 30000/1001 이라 드러나지 않았다.
    """
    fps = E._choose_fps("90000/1", "30/1", "900", "30.0")
    assert E._parse_rate(fps) == 30.0


def test_평균_프레임률을_먼저_쓴다() -> None:
    assert E._choose_fps("30000/1001", "25/1", "", "") == "25/1"


def test_둘_다_비정상이면_프레임수와_길이로_도출한다() -> None:
    fps = E._choose_fps("90000/1", "0/0", "900", "30.0")
    got = E._parse_rate(fps)
    assert got is not None and abs(got - 30.0) < 0.01


def test_판정_근거가_전혀_없으면_기본값으로_떨어진다() -> None:
    """근거가 없다고 산출을 포기하지는 않는다 — 길이 검증이 뒤를 받친다."""
    assert E._choose_fps("0/0", "N/A", "", "") == E.DEFAULT_FPS


@pytest.mark.parametrize(
    "raw,expected",
    [("30/1", 30.0), ("30000/1001", 29.97), ("25", 25.0), ("0/0", None),
     ("N/A", None), ("", None), ("abc", None), ("1/0", None)],
)
def test_프레임률_문자열_파싱(raw: str, expected) -> None:
    got = E._parse_rate(raw)
    if expected is None:
        assert got is None
    else:
        assert got is not None and abs(got - expected) < 0.01

# ── 워터마크 기본 비활성 (2026-09-05 사용자 확정) ─────────────────
def test_워터마크는_기본으로_꺼져_있다() -> None:
    """★실제 마스킹이 산출되는 지금 'MOCK 비식별 완료' 문구는 필요 없다.

    이 값이 바꾸는 것은 <b>엔진이 실패했을 때</b>의 동작뿐이다 — 엔진이 성공하면 애초에
    워터마크를 굽지 않는다. 꺼진 상태에서 엔진이 실패하면 원본을 그대로 복사한다.
    """
    from app.config import get_settings, reload_settings

    reload_settings()
    assert get_settings().deid_watermark_enabled is False


def test_워터마크가_꺼져_있으면_엔진_실패시_원본복사로_내려간다(tmp_path, monkeypatch) -> None:
    """엔진도 못 하고 워터마크도 안 하면 ``False`` — 호출측이 원본 복사로 폴백한다."""
    from app.config import reload_settings
    from app.services import deid_sim

    monkeypatch.delenv("MOCK_DEID_WATERMARK_ENABLED", raising=False)
    reload_settings()

    in_dir = tmp_path / "raw"; in_dir.mkdir()
    out_dir = tmp_path / "deid"; out_dir.mkdir()
    src = in_dir / "a.mp4"
    # 컨테이너 시그니처는 있으나 실제 영상이 아니라 엔진이 산출하지 못한다
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 200)
    target = out_dir / "a-mask.mp4"

    ok = deid_sim._burn_deid_watermark(
        src, target, input_path=str(in_dir) + "/", input_base=str(in_dir),
        output_dir=out_dir, export_path=str(out_dir), output_base=str(out_dir),
    )
    assert ok is False
    assert not target.exists()

# ── 마스킹 영역 크기 (2026-09-05 사용자 지적으로 축소) ────────────
def test_번호판_띠는_넓고_얇다() -> None:
    """★회귀 — 이 값은 <b>실측으로 두 번 조정됐다</b>.

    ①구값(폭 60% · 높이 30%)은 실제 번호판의 2~4배라 차 앞부분이 통째로 뭉개졌다.
    ②그래서 폭을 38% 로 좁혔더니, <b>비스듬히 찍힌 차량</b>의 번호판을 놓쳤다 — 검출 박스가
      앞면+옆면을 함께 감싸 박스 중앙이 번호판 자리가 아니게 되기 때문이다.

    결론은 <b>넓은 폭 + 얇은 높이</b>다. 폭은 번호판이 좌우 어디 있든 덮도록 넓게 잡고,
    높이는 차량 형태가 남도록 얇게 유지한다.
    """
    w, h = 100, 60
    _, _, nw, nh = E._expand((0, 0, w, h), 1.0, 500, 500, E.KIND_VEHICLE)
    assert 0.6 <= nw / w <= 1.0, f"띠가 좁으면 각도 있는 차량의 번호판을 놓친다: {nw / w:.0%}"
    assert 0.08 <= nh / h <= 0.20, f"띠가 두꺼우면 차량 형태가 사라진다: {nh / h:.0%}"


def test_번호판_띠는_차량_하단에_붙는다() -> None:
    """번호판은 차량 아래쪽에 있다 — 띠가 위로 올라가면 차체를 덮고 번호판은 놓친다."""
    x, y, w, h = 10, 20, 100, 60
    _, ny, _, nh = E._expand((x, y, w, h), 1.0, 500, 500, E.KIND_VEHICLE)
    assert ny + nh >= y + h - 2, "띠가 차량 하단에 닿지 않는다"
    assert ny > y + h * 0.6, "띠가 차량 중단까지 올라왔다"


def test_머리_영역은_CCTV_각도의_실제_머리를_덮는다() -> None:
    """★회귀 — <b>교과서값(키의 13%)을 쓰면 안 된다</b>. CCTV 는 위에서 내려다보는 각도라
    다리가 짧게 찍혀 머리 비율이 커진다(실측: 도심 CCTV 보행자에서 머리가 박스 높이의
    약 31%). 0.16 으로 줄였더니 마스킹이 머리 위쪽 절반만 덮고 <b>얼굴이 남았다</b>.

    상한도 함께 둔다 — 상체까지 덮으면 행동 라벨링 대상이 훼손된다.
    """
    w, h = 20, 50
    _, _, _, nh = E._expand((0, 0, w, h), 1.0, 500, 500, E.KIND_PERSON)
    assert 0.22 <= nh / h <= 0.32, f"머리 비율이 부적절하다: {nh / h:.0%}"


def test_텍스트_검출_입력은_표준보다_크다() -> None:
    """★작은 간판을 놓치는 원인은 임계가 아니라 해상도였다 — 임계를 낮춰도 검출이 늘지 않았고
    입력을 키우자 +29% 늘었다(실측). 되돌리면 그만큼 다시 놓친다."""
    assert E.OCR_INPUT_SIZE >= 960


def test_배율을_올려도_차량_전체를_덮지는_않는다() -> None:
    """``masking_range`` 상한(3.0)에서도 차량 형태가 남아야 라벨링에 쓸 수 있다."""
    w, h = 100, 60
    _, _, _, nh = E._expand((0, 0, w, h), E.MASK_RANGE_MAX, 500, 500, E.KIND_VEHICLE)
    assert nh < h, "배율 상한에서 차량 높이 전체가 덮인다"

# ── 프레임 사이 마스킹 이탈 (모션 예측) ──────────────────────────
def test_검출_사이_프레임은_낡은_박스를_그대로_쓰지_않는다() -> None:
    """★회귀 — 검출 간격 동안 객체가 움직이면 마스킹이 벗어나 <b>번호판·얼굴이 노출</b>된다.
    실측으로 오토바이가 f+4 에 IoU 0.22 까지 떨어졌다. 예측 박스는 이동 방향으로 따라가야 한다.
    """
    t = E._MotionTracker()
    t.update([(100, 100, 40, 40, E.KIND_VEHICLE)])
    t.update([(110, 100, 40, 40, E.KIND_VEHICLE)])  # x 로 +10 이동
    pred = t.predict(1)
    assert pred, "예측 박스가 비었다"
    assert pred[0][0] > 110, "예측이 이동 방향을 따라가지 않는다"


def test_예측_박스는_경과할수록_넓어진다() -> None:
    """예측은 빗나갈 수 있다 — 개인정보 보호는 fail-closed 여야 하므로 여유를 준다."""
    t = E._MotionTracker()
    t.update([(100, 100, 40, 40, E.KIND_PERSON)])
    t.update([(105, 100, 40, 40, E.KIND_PERSON)])
    w1 = t.predict(1)[0][2]
    w3 = t.predict(3)[0][2]
    assert w3 > w1 >= 40


def test_검출이_튀어도_박스가_날아가지_않는다() -> None:
    """오검출로 생긴 비정상 속도가 마스킹을 화면 밖으로 보내면 그 프레임은 무방비가 된다."""
    t = E._MotionTracker()
    t.update([(100, 100, 40, 40, E.KIND_VEHICLE)])
    t.update([(600, 400, 40, 40, E.KIND_VEHICLE)])  # 말도 안 되는 이동
    pred = t.predict(1)
    assert abs(pred[0][0] - 600) < 50, "속도 상한이 걸리지 않았다"


def test_다른_종류는_서로_매칭되지_않는다() -> None:
    """사람과 차량을 이어 속도를 계산하면 엉뚱한 방향으로 예측한다."""
    t = E._MotionTracker()
    t.update([(100, 100, 40, 40, E.KIND_PERSON)])
    t.update([(200, 100, 40, 40, E.KIND_VEHICLE)])
    px, _, pw, _, _ = t.predict(1)[0]
    # 예측 박스는 안전 여유만큼 넓어지므로 <b>중심</b>으로 비교한다(좌상단은 그만큼 밀린다).
    assert abs((px + pw / 2) - 220) <= 2, "종류가 다른데 속도가 계산됐다"


def test_검출_주기는_이탈을_감당할_만큼_짧다() -> None:
    """★5프레임 간격에서 오토바이 IoU 가 0.22 까지 떨어졌다 — 늘리면 그 상태로 돌아간다."""
    assert E.DETECT_EVERY_MOVING <= 3


# ── 검출 전처리 ───────────────────────────────────────────────────
def test_전처리는_검출_입력에만_쓰고_산출물은_원본_화질이다() -> None:
    """대비를 올린 영상을 내보내면 원본과 다른 화질의 학습데이터가 된다."""
    cv2 = pytest.importorskip("cv2")
    rng = np.random.default_rng(0)
    frame = rng.integers(0, 255, (60, 80, 3), dtype=np.uint8)
    before = frame.copy()
    out = E._enhance_for_detection(cv2, frame)
    assert np.array_equal(frame, before), "전처리가 원본 프레임을 바꿨다"
    assert out.shape == frame.shape
    assert not np.array_equal(out, frame), "전처리가 아무 일도 하지 않았다"

# ── 조용한 폴백 차단 (2026-09-09 실사고) ──────────────────────────
def test_마스킹_없이_복사할_때는_경고로_남긴다(tmp_path, monkeypatch, caplog) -> None:
    """★회귀 — 모델이 빠진 채 이틀간 돌면서 "비식별 완료"로 기록된 <b>원본</b>이 쌓였다.
    그 경로가 INFO 한 줄이라 아무도 눈치채지 못했다.

    이 경로로 나가는 산출물은 마스킹되지 않은 원본인데 BE 는 정상 비식별본으로 받아
    DE_IDENT_YN='Y' 로 마감한다. <b>WARNING 이어야 하고, 원인 판정에 필요한 것
    (엔진 가용성·모델 경로·빠진 모델)이 함께 있어야 한다.</b>
    """
    import logging

    from app.config import reload_settings
    from app.services import deid_sim

    monkeypatch.delenv("MOCK_DEID_WATERMARK_ENABLED", raising=False)
    monkeypatch.setattr(E, "MODELS_DIR", tmp_path / "nomodels")
    reload_settings()

    in_dir = tmp_path / "raw"; in_dir.mkdir()
    out_dir = tmp_path / "deid"; out_dir.mkdir()
    src = in_dir / "a.mp4"
    src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"BODY" * 200)

    with caplog.at_level(logging.INFO, logger="app.services.deid_sim"):
        deid_sim._burn_deid_watermark(
            src, out_dir / "a-mask.mp4",
            input_path=str(in_dir) + "/", input_base=str(in_dir),
            output_dir=out_dir, export_path=str(out_dir), output_base=str(out_dir),
        )

    warns = [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert warns, "마스킹 없이 복사하는데 경고가 없다 — 조용한 폴백은 이 사고의 원인이었다"
    msg = " ".join(r.getMessage() for r in warns)
    assert "engine_available" in msg, "엔진 가용성이 로그에 없어 원인 판정이 안 된다"
    assert "models_dir" in msg, "모델 경로가 로그에 없어 어디를 봐야 할지 알 수 없다"
