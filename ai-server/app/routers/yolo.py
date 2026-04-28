"""YOLO 오토라벨링 추론 엔드포인트."""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class InferRequest(BaseModel):
    image_path: str
    conf_threshold: float = 0.25


class Detection(BaseModel):
    label: str
    confidence: float
    bbox: list[float]  # [x1, y1, x2, y2]


class InferResponse(BaseModel):
    detections: list[Detection]


@router.post("", response_model=InferResponse)
async def infer(req: InferRequest) -> InferResponse:
    """Phase 3에서 ultralytics 기반 실제 추론 구현."""
    # TODO: ultralytics YOLO 로드 + 추론
    return InferResponse(detections=[])
