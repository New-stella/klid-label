"""
pytest 공통 설정.

- AI_MOCK_MODE를 강제 활성화해서 실제 모델 로드 없이 모든 라우터를 테스트한다
- 모델 가중치 다운로드 금지
"""

from __future__ import annotations

import base64
import io
import os

import pytest

# import 전에 환경변수 강제 설정 — Settings 캐시보다 먼저 적용되도록
os.environ.setdefault("AI_MOCK_MODE", "true")
os.environ.setdefault("MAX_IMAGE_SIZE_MB", "1")  # 테스트 편의 — 1MB
os.environ.setdefault("CORS_ALLOW_ORIGINS", "*")


@pytest.fixture(autouse=True)
def _force_mock_settings():
    """매 테스트마다 환경변수 일관성 보장 + Settings 재로드."""
    os.environ["AI_MOCK_MODE"] = "true"
    os.environ["MAX_IMAGE_SIZE_MB"] = "1"
    from app.config import reload_settings

    reload_settings()
    yield


def _make_png_b64(width: int = 64, height: int = 64, noise: bool = False) -> str:
    from PIL import Image

    if noise:
        # 압축이 거의 안 되도록 랜덤 데이터로 채움
        import os as _os

        raw = _os.urandom(width * height * 3)
        img = Image.frombytes("RGB", (width, height), raw)
    else:
        img = Image.new("RGB", (width, height), color=(128, 128, 128))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


@pytest.fixture
def small_png_b64() -> str:
    return _make_png_b64()


@pytest.fixture
def large_png_b64() -> str:
    """1MB 한도를 초과하는 이미지 (랜덤 노이즈 PNG로 압축 회피)."""
    # 1500x1500 노이즈 PNG는 약 6MB 수준 → 1MB 한도 초과
    return _make_png_b64(1500, 1500, noise=True)
