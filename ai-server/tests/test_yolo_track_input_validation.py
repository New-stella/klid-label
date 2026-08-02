"""YOLO /track 입력 검증 통일 (G-ISSUE-01 — CWE-20 / CWE-770).

`/infer/yolo/predict` 는 mock 사유와 무관하게 `decode_image_b64` 로 입력을 검증한 뒤
mock 응답을 만든다. 반면 `/infer/yolo/track` 은 사유가 `env_mock` 일 때만 디코드했기 때문에
**운영 형상(weights_missing / load_failed)에서 입력 검증이 통째로 사라졌다**:

  - invalid base64 → 400 이어야 하는데 200(빈 detections)
  - 크기/픽셀 상한(CWE-770) 게이트도 함께 우회

두 엔드포인트가 같은 입력에 다르게 반응하면 BE 는 "track 은 뭘 보내도 200" 이라는
잘못된 계약을 학습한다. 본 테스트는 사유 3종 전체에서 `/predict` 와 동일 동작임을 고정한다.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import yolox_loader
from app.routers import yolo as yolo_router

client = TestClient(app)

INVALID_B64 = "!!!notb64"


@pytest.fixture(autouse=True)
def _reset_yolox():
    """매 테스트마다 YOLOX 싱글톤/트래커 상태 초기화."""
    yolox_loader.reset_yolox_model()
    yolox_loader.reset_yolox_trackers()
    yolo_router.reset_mock_warn_flag()
    yield


def _track(image_b64: str, clip_id: str = "vid-v", frame_index: int = 0):
    return client.post(
        "/infer/yolo/track",
        json={"image_b64": image_b64, "clip_id": clip_id, "frame_index": frame_index},
    )


def _force_reason(monkeypatch: pytest.MonkeyPatch, reason: str) -> None:
    """가중치 부재/로드 실패 등 운영 형상의 mock 사유를 강제한다."""
    monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: reason)


# ────────────────────────────────────────────────────────────────────
# RED — 운영 형상 사유에서도 입력 검증이 살아 있어야 한다
# ────────────────────────────────────────────────────────────────────

def test_weights_missing_사유일_때_invalid_base64_요청은_400을_반환한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given — 가중치 미배포 운영 형상
    _force_reason(monkeypatch, "weights_missing")

    # when
    res = _track(INVALID_B64)

    # then — /predict 와 동일하게 입력 검증에서 거부
    assert res.status_code == 400
    assert res.json()["error_code"] in {"INVALID_IMAGE", "VALIDATION_ERROR"}


def test_load_failed_사유일_때도_invalid_base64는_400을_반환한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # given
    _force_reason(monkeypatch, "load_failed")

    # when
    res = _track(INVALID_B64)

    # then
    assert res.status_code == 400
    assert res.json()["error_code"] in {"INVALID_IMAGE", "VALIDATION_ERROR"}


def test_weights_missing_사유일_때_크기초과_이미지는_413을_반환한다(
    large_png_b64: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """CWE-770 — 크기 게이트도 사유와 무관하게 적용되어야 한다."""
    # given
    _force_reason(monkeypatch, "weights_missing")

    # when
    res = _track(large_png_b64)

    # then
    assert res.status_code == 413
    assert res.json()["error_code"] == "IMAGE_TOO_LARGE"


def test_predict와_track이_같은_입력에_같은_상태코드를_반환한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """엔드포인트 간 입력 검증 비대칭 회귀 가드."""
    # given
    _force_reason(monkeypatch, "weights_missing")

    # when
    predict_res = client.post("/infer/yolo/predict", json={"image_b64": INVALID_B64})
    track_res = _track(INVALID_B64, clip_id="vid-sym")

    # then
    assert predict_res.status_code == track_res.status_code == 400


# ────────────────────────────────────────────────────────────────────
# 회귀 — 정상 입력의 기존 동작은 사유별로 그대로 유지된다
# ────────────────────────────────────────────────────────────────────

def test_weights_missing_사유일_때_정상_요청은_기존과_동일하게_mock으로_동작한다(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    # given
    _force_reason(monkeypatch, "weights_missing")

    # when
    res = _track(small_png_b64, clip_id="vid-wm")

    # then — 운영 오염 방지 위해 빈 detections (기존 계약 유지)
    assert res.status_code == 200
    body = res.json()
    assert body["mock"] is True
    assert body["source"] == "mock"
    assert body["mock_reason"] == "weights_missing"
    assert body["detections"] == []


def test_env_mock_사유일_때_동작은_기존과_동일하다(small_png_b64: str) -> None:
    # given — conftest 가 AI_MOCK_MODE=true 를 적용(사유 env_mock)

    # when
    res = _track(small_png_b64, clip_id="vid-em")

    # then — 결정적 person 박스 + track_id=1
    assert res.status_code == 200
    body = res.json()
    assert body["mock_reason"] == "env_mock"
    det = body["detections"][0]
    assert det["label"] == "person"
    assert det["track_id"] == 1
    # 64x64 이미지의 중앙 박스 — 디코드된 실제 크기가 좌표에 반영된다
    assert det["points"] == [19.2, 19.2, 44.8, 44.8]


def test_env_mock_경로에서_이미지_디코드는_요청당_1회만_수행된다(
    small_png_b64: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """이중 디코드 방지 — 검증을 앞으로 옮기며 mock 생성이 다시 디코드하면 안 된다.

    ※ 계수 대상이 `decode_image_b64`(w,h 만 반환) → `decode_image_b64_pil` 로 바뀐 것은
      **검증을 트래커 획득보다 앞으로 옮기면서**(아래 상태변경 순서 테스트 참조) mock/실모델
      두 경로가 <b>같은 디코드 1회</b>를 공유하게 됐기 때문이다. "요청당 정확히 1회" 라는
      의도는 그대로다.
    """
    # given
    calls: list[str] = []
    original = yolo_router.decode_image_b64_pil

    def _counting(image_b64: str):
        calls.append(image_b64)
        return original(image_b64)

    monkeypatch.setattr(yolo_router, "decode_image_b64_pil", _counting)

    # when
    res = _track(small_png_b64, clip_id="vid-once")

    # then
    assert res.status_code == 200
    assert len(calls) == 1


# ────────────────────────────────────────────────────────────────────
# 검증 순서 — 트래커 상태 변경보다 먼저 (G-ISSUE-03)
# ────────────────────────────────────────────────────────────────────

def test_실모델_형상에서_깨진_base64는_트래커_상태변경_전에_400을_반환한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`get_yolox_tracker` 는 <b>상태 변경</b>(생성·리셋·LRU 축출)이라 검증보다 뒤에 와야 한다.

    구 구현은 트래커를 먼저 획득해서 ①깨진 입력 한 번에 진행 중이던 clip 의 트래커가 리셋되어
    `track_id` 연속성이 파괴되고 ②서로 다른 `clip_id` 를 검증 없이 반복 전송하면 LRU(최대 10개)
    캐시가 축출됐다(CWE-770).
    """
    # given — 실모델이 로드된 형상(트래커 획득이 실제로 상태를 만드는 경로)
    acquired: list[tuple[str, bool]] = []

    class _FakeBackend:
        def track(self, img, params):  # pragma: no cover — 도달하면 안 되는 경로
            return []

    def _spy(clip_id: str, reset: bool):
        acquired.append((clip_id, reset))
        return _FakeBackend()

    monkeypatch.setattr(yolox_loader, "get_yolox_tracker", _spy)

    # when
    res = _track(INVALID_B64, clip_id="vid-order", frame_index=0)

    # then — 400 이면서 트래커는 건드리지 않았다
    assert res.status_code == 400
    assert acquired == []

