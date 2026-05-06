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
    model_config = ConfigDict(extra="forbid")

    detections: list[Detection]


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


class Sam2TrackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str = Field(..., description="이전 프레임에서 부여된 트랙 ID")
    prev_image_b64: str = Field(..., min_length=1)
    next_image_b64: str = Field(..., min_length=1)
    prev_polygon: list[list[float]] = Field(..., min_length=3, description="이전 프레임 폴리곤")


class Sam2TrackResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str
    polygon: list[list[float]]
    score: float = Field(..., ge=0.0, le=1.0)


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
