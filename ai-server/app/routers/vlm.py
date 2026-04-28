"""VLM 시계열 메타데이터 생성."""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class VlmRequest(BaseModel):
    frame_paths: list[str]
    prompt: str | None = None


class FrameMeta(BaseModel):
    frame_index: int
    description: str
    objects: list[str]
    environment: dict[str, str]  # weather, time, location
    event_type: str | None = None


class VlmResponse(BaseModel):
    frames: list[FrameMeta]


@router.post("", response_model=VlmResponse)
async def describe(req: VlmRequest) -> VlmResponse:
    # TODO: VLM 호출 구현 (Phase 3)
    return VlmResponse(frames=[])
