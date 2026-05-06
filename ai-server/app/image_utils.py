"""
이미지 입력 검증 유틸.

OWASP A08 (Data Validation) — base64 디코드 + PIL.Image 형식 검증 (jpeg/png allowlist).
크기 한도 초과는 ImageTooLargeError, 형식 오류는 InvalidImageError.
"""

from __future__ import annotations

import base64
import binascii
import io

from app.config import get_settings
from app.exceptions import ImageTooLargeError, InvalidImageError

ALLOWED_FORMATS: frozenset[str] = frozenset({"JPEG", "PNG"})

# CWE-770 방어 — Pillow DecompressionBomb 상한 (defense in depth)
# FullHD ≈ 2M, 4K ≈ 8M, 8K ≈ 33M 픽셀이므로 50M는 안전한 상한
MAX_IMAGE_PIXELS: int = 50_000_000


def decode_image_b64(image_b64: str) -> tuple[int, int]:
    """
    base64 이미지를 디코드하고 (width, height)만 반환한다.

    실제 픽셀 데이터는 본 Phase에서 사용하지 않으므로 메타데이터만 검증.
    """
    settings = get_settings()
    max_bytes = settings.max_image_size_mb * 1024 * 1024

    # base64 입력 길이로 1차 추정 (4/3 비율) — 빠른 거부
    if len(image_b64) > int(max_bytes * 4 / 3) + 64:
        raise ImageTooLargeError(f"이미지 크기 한도 초과 (max {settings.max_image_size_mb}MB)")

    try:
        raw = base64.b64decode(image_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidImageError("base64 디코드 실패") from exc

    if len(raw) > max_bytes:
        raise ImageTooLargeError(f"이미지 크기 한도 초과 (max {settings.max_image_size_mb}MB)")

    try:
        from PIL import Image  # noqa: WPS433 (lazy import — 테스트 환경 최소 의존)
    except ImportError as exc:
        raise InvalidImageError("이미지 처리 라이브러리 미설치") from exc

    # CWE-770 방어 — Pillow의 DecompressionBomb 자동 차단 임계값 설정
    # (Image.open 호출 전에 모듈 전역 설정)
    Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS

    try:
        with Image.open(io.BytesIO(raw)) as img:
            fmt = (img.format or "").upper()
            if fmt not in ALLOWED_FORMATS:
                raise InvalidImageError(f"지원하지 않는 형식: {fmt or 'unknown'}")
            # 명시적 픽셀 수 검사 (Pillow 내부 경고와 별개로 강제 거부)
            if img.width * img.height > MAX_IMAGE_PIXELS:
                raise ImageTooLargeError(
                    f"이미지 픽셀 수 한도 초과 (max {MAX_IMAGE_PIXELS} pixels)"
                )
            return img.width, img.height
    except (InvalidImageError, ImageTooLargeError):
        raise
    except Image.DecompressionBombError as exc:
        raise ImageTooLargeError("이미지 픽셀 수 한도 초과") from exc
    except Exception as exc:  # noqa: BLE001
        raise InvalidImageError("이미지 디코드 실패") from exc
