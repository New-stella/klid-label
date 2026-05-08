"""
YOLO 오토라벨링 추론 엔드포인트.

POST /infer/yolo/predict
- 입력: image_b64 (base64) + conf_threshold
- 출력: detections [{label, points: [x1,y1,x2,y2], score}]
- AI_MOCK_MODE=true 또는 가중치 부재 시 mock 응답 반환
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64, decode_image_b64_pil
from app.models.yolo_loader import get_yolo_model
from app.schemas import Detection, YoloRequest, YoloResponse

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/predict", response_model=YoloResponse)
async def predict(req: YoloRequest) -> YoloResponse:
    """YOLO 객체 감지."""
    model = get_yolo_model()

    if model is None:
        # mock mode 또는 가중치 미존재
        width, height = decode_image_b64(req.image_b64)
        logger.info(
            "[YOLO] mock predict conf_threshold=%.2f image_size=%dx%d",
            req.conf_threshold,
            width,
            height,
        )
        return _mock_predict(width, height, req.conf_threshold)

    # 실제 추론
    img = decode_image_b64_pil(req.image_b64)
    try:
        logger.info(
            "[YOLO] real predict conf_threshold=%.2f image_size=%dx%d",
            req.conf_threshold,
            img.width,
            img.height,
        )
        return _run_inference(model, img, req.conf_threshold)
    finally:
        img.close()


def _run_inference(model: object, img: object, conf_threshold: float) -> YoloResponse:
    """ultralytics YOLO 모델로 실제 추론을 수행한다."""
    results = model(img, conf=conf_threshold, verbose=False)  # type: ignore[operator]

    detections: list[Detection] = []
    for result in results:
        if result.boxes is None:
            continue
        boxes = result.boxes
        names = result.names  # {class_id: class_name}

        for i in range(len(boxes)):
            xyxy = boxes.xyxy[i].tolist()   # [x1, y1, x2, y2] (float)
            score = float(boxes.conf[i])
            cls_id = int(boxes.cls[i])
            label = names.get(cls_id, str(cls_id))

            detections.append(
                Detection(
                    label=label,
                    points=[xyxy[0], xyxy[1], xyxy[2], xyxy[3]],
                    score=score,
                )
            )

    logger.debug("[YOLO] real inference detections=%d", len(detections))
    return YoloResponse(detections=detections)


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
