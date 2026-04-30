"""VLM 객체 검증 — YOLO/SAM2가 감지한 객체에 대한 분류 정합성 검증."""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class ObjectToVerify(BaseModel):
    """검증 대상 객체 — YOLO/SAM2가 감지한 단일 객체."""

    obj_id: str
    predicted_label: str
    bbox: list[float] | None = None  # [x, y, w, h] (선택)
    mask_path: str | None = None  # SAM2 마스크 파일 경로 (선택)


class VlmRequest(BaseModel):
    """프레임 1장 + 그 프레임에서 검증할 객체 목록."""

    frame_path: str
    objects: list[ObjectToVerify]


class ObjectVerification(BaseModel):
    """객체 단위 검증 결과."""

    obj_id: str
    predicted_label: str
    verified: bool
    confidence: float
    reason: str | None = None


class VlmResponse(BaseModel):
    """검증 결과 — 요청한 객체 목록과 1:1 대응."""

    results: list[ObjectVerification]


@router.post("", response_model=VlmResponse)
async def verify(req: VlmRequest) -> VlmResponse:
    # TODO: VLM 객체 검증 호출 구현 (Phase 3)
    return VlmResponse(results=[])
