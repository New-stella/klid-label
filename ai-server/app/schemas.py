"""
Pydantic v2 Request/Response 스키마.

Spring Boot AiServerClient 호출 정합성을 위해 다음 경로/형식을 따른다:
- POST /infer/yolo/predict   → YoloRequest  → YoloResponse
- POST /infer/sam2/segment   → Sam2SegmentRequest → Sam2SegmentResponse
- POST /infer/sam2/track     → Sam2TrackRequest   → Sam2TrackResponse
- POST /infer/vlm/verify-objects → VlmVerifyRequest → VlmVerifyResponse
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


# ────────────────────────────────────────────────────────────────────
# 공통
# ────────────────────────────────────────────────────────────────────

class ErrorResponse(BaseModel):
    """공통 에러 응답."""

    model_config = ConfigDict(extra="forbid")

    error_code: str
    message: str


# ────────────────────────────────────────────────────────────────────
# YOLO
# ────────────────────────────────────────────────────────────────────

class YoloRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    image_b64: str = Field(..., min_length=1, description="base64 인코딩 이미지(jpeg/png)")
    conf_threshold: float = Field(default=0.25, ge=0.0, le=1.0)


class Detection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    label: str
    points: list[float] = Field(..., min_length=4, max_length=4, description="[x1, y1, x2, y2]")
    score: float = Field(..., ge=0.0, le=1.0)


class YoloResponse(BaseModel):
    """YOLO 추론 응답.

    - mock: True 이면 ai-server 가 mock 응답을 반환했음을 의미한다.
      운영에서 mock 응답이 흘러나가면 데이터 품질이 떨어지므로 BE 가 감지해 경고를 남긴다.
    - source: "mock" | "model" — mock 의 사유까지 문자열로 표시.
      ("weights_missing" 등 세부 사유는 mock_reason 에 별도 표기)
    """

    model_config = ConfigDict(extra="forbid")

    detections: list[Detection]
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(
        default=None,
        description='mock 응답인 경우 사유. "env_mock" | "weights_missing" | "load_failed"',
    )


# ────────────────────────────────────────────────────────────────────
# SAM2
# ────────────────────────────────────────────────────────────────────

class Sam2SegmentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    image_b64: str = Field(..., min_length=1)
    points: list[list[float]] | None = Field(default=None, description="[[x, y], ...] 클릭 좌표")
    box: list[float] | None = Field(default=None, description="[x1, y1, x2, y2]")


class Sam2SegmentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    polygon: list[list[float]] = Field(..., description="[[x, y], ...] 폐곡선 좌표")
    score: float = Field(..., ge=0.0, le=1.0)
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)


class Sam2TrackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str = Field(..., description="이전 프레임에서 부여된 트랙 ID")
    prev_image_b64: str = Field(default="", description="이전 프레임 base64 — mock 모드에서는 미사용")
    next_image_b64: str = Field(default="", description="다음 프레임 base64 — mock 모드에서는 미사용")
    prev_polygon: list[list[float]] = Field(..., min_length=3, description="이전 프레임 폴리곤")


class Sam2TrackResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str
    polygon: list[list[float]]
    score: float = Field(..., ge=0.0, le=1.0)
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)


# ────────────────────────────────────────────────────────────────────
# VLM (V1.7 — 객체 검증 한정)
# ────────────────────────────────────────────────────────────────────

class ObjectToVerify(BaseModel):
    model_config = ConfigDict(extra="forbid")

    obj_id: str
    expected_label: str
    bbox: list[float] = Field(..., min_length=4, max_length=4, description="[x1, y1, x2, y2]")


class VlmVerifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    image_b64: str = Field(..., min_length=1)
    objects: list[ObjectToVerify] = Field(..., min_length=1)


class ObjectVerification(BaseModel):
    model_config = ConfigDict(extra="forbid")

    obj_id: str
    expected_label: str
    verified: bool
    confidence: float = Field(..., ge=0.0, le=1.0)


class VlmVerifyResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    results: list[ObjectVerification]
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)
