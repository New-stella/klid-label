"""
YOLO 오토라벨링 추론 엔드포인트.

POST /infer/yolo/predict
- 입력: image_b64 (base64) + conf_threshold
- 출력: detections [{label, points: [x1,y1,x2,y2], score}]
- MOCK 모드 또는 가중치 부재 시 mock 응답 반환
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64
from app.models.yolo_loader import get_yolo_model
from app.schemas import Detection, YoloRequest, YoloResponse

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/predict", response_model=YoloResponse)
async def predict(req: YoloRequest) -> YoloResponse:
    """YOLO 객체 감지."""
    width, height = decode_image_b64(req.image_b64)
    logger.info(
        "[YOLO] predict received conf_threshold=%.2f image_size=%dx%d",
        req.conf_threshold,
        width,
        height,
    )
    logger.debug("[YOLO] predict b64_len=%d", len(req.image_b64))

    if _should_mock():
        return _mock_predict(width, height, req.conf_threshold)

    # 실제 추론은 후속 Phase. 현재는 안전한 mock fallback
    return _mock_predict(width, height, req.conf_threshold)


def _should_mock() -> bool:
    """mock 모드이거나 모델이 로드되지 않은 경우 True."""
    return get_settings().ai_mock_mode or get_yolo_model() is None


def _mock_predict(width: int, height: int, conf_threshold: float) -> YoloResponse:
    """결정적 mock 응답 — 이미지 중앙 1개 박스."""
    if not get_settings().ai_mock_mode:
        # 실제 모드인데 모델 부재 시에도 빈 결과 반환
        return YoloResponse(detections=[])

    cx, cy = width / 2.0, height / 2.0
    half = min(width, height) * 0.2
    score = 0.9
    detections: list[Detection] = []
    if score >= conf_threshold:
        detections.append(
            Detection(
                label="person",
                points=[cx - half, cy - half, cx + half, cy + half],
                score=score,
            )
        )
    return YoloResponse(detections=detections)
