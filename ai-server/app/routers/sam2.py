"""SAM2 세그멘테이션 + Track 추론."""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class SegmentRequest(BaseModel):
    image_path: str
    prompts: list[list[float]]  # [[x, y], ...] 클릭 좌표


class SegmentResponse(BaseModel):
    masks: list[list[list[int]]]  # 폴리곤 좌표 리스트 또는 RLE
    rle: list[str] | None = None


class TrackRequest(BaseModel):
    frame_paths: list[str]
    init_mask: list[list[int]]


class TrackResponse(BaseModel):
    masks_per_frame: list[list[list[int]]]


@router.post("/segment", response_model=SegmentResponse)
async def segment(req: SegmentRequest) -> SegmentResponse:
    # TODO: SAM2 추론 구현 (Phase 3 / 6)
    return SegmentResponse(masks=[])


@router.post("/track", response_model=TrackResponse)
async def track(req: TrackRequest) -> TrackResponse:
    # TODO: SAM2 Track 구현 (Phase 6)
    return TrackResponse(masks_per_frame=[])
