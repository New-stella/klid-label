"""image_utils 단위 테스트 — DecompressionBomb 방어 (CWE-770)."""

from __future__ import annotations

import base64
import io

import pytest

from app.exceptions import ImageTooLargeError
from app.image_utils import MAX_IMAGE_PIXELS, decode_image_b64


def _make_huge_pixel_png_b64() -> str:
    """
    MAX_IMAGE_PIXELS(50_000_000)를 초과하는 가상 PNG의 base64.

    실제로 그런 이미지를 메모리에 생성하면 OOM이 나므로,
    기존 한도(1MB)를 통과할 만큼 작지만 헤더상 폭/높이가 큰 PNG를 만든다.

    PIL은 헤더의 width/height만으로 DecompressionBombError를 발생시키므로
    실제 이미지 데이터는 작게 유지하면서 헤더만 위조해 검증한다.
    그러나 PNG 무결성 검증을 통과해야 하므로 단순 위조는 어렵다 →
    대신 실제로 작은 이미지를 만들고 MAX_IMAGE_PIXELS를 임시로 낮춰 테스트한다.
    """
    from PIL import Image

    img = Image.new("RGB", (200, 200), color=(0, 0, 0))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def test_decode_image_b64_픽셀수_한도_초과시_ImageTooLargeError(monkeypatch) -> None:
    """MAX_IMAGE_PIXELS를 임시로 낮춰 픽셀 수 검사가 작동하는지 검증."""
    # 200x200 = 40_000 픽셀이므로 한도를 10_000으로 낮춰 실패시킨다
    monkeypatch.setattr("app.image_utils.MAX_IMAGE_PIXELS", 10_000)

    image_b64 = _make_huge_pixel_png_b64()

    with pytest.raises(ImageTooLargeError):
        decode_image_b64(image_b64)


def test_decode_image_b64_정상_이미지는_통과() -> None:
    """기본 한도(50M 픽셀) 내 이미지는 정상 디코드."""
    image_b64 = _make_huge_pixel_png_b64()  # 200x200 = 40K 픽셀
    width, height = decode_image_b64(image_b64)
    assert width == 200
    assert height == 200


def test_MAX_IMAGE_PIXELS_상수가_50M로_정의됨() -> None:
    """안전한 상한값 회귀 방지 — 8K 영상(33M)도 통과해야 함."""
    assert MAX_IMAGE_PIXELS == 50_000_000
