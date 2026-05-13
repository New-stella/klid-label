"""
YOLO 오토라벨링 추론 엔드포인트.

POST /infer/yolo/predict
- 입력: image_b64 (base64) + conf_threshold
- 출력: detections [{label, points: [x1,y1,x2,y2], score}]
        + mock (bool) / source ("mock"|"model") / mock_reason
- AI_MOCK_MODE=true 또는 가중치 부재 시 mock 응답 반환
  운영에서 mock 응답이 흘러나가면 BE 가 mock=true 를 감지해 WARN 로그를 남긴다.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64, decode_image_b64_pil
from app.models.yolo_loader import get_yolo_mock_reason, get_yolo_model
from app.schemas import Detection, YoloRequest, YoloResponse

router = APIRouter()
logger = logging.getLogger(__name__)

# 운영에서 mock 응답이 첫 호출 시 1회 WARN 출력하기 위한 플래그
_mock_warned: bool = False


@router.post("/predict", response_model=YoloResponse)
async def predict(req: YoloRequest) -> YoloResponse:
    """YOLO 객체 감지."""
    model = get_yolo_model()

    if model is None:
        # mock mode 또는 가중치 미존재 / 로드 실패
        width, height = decode_image_b64(req.image_b64)
        reason = get_yolo_mock_reason() or _fallback_reason()
        _warn_mock_once(reason)
        logger.info(
            "[YOLO][MOCK] predict reason=%s conf_threshold=%.2f image_size=%dx%d",
            reason,
            req.conf_threshold,
            width,
            height,
        )
        return _mock_predict(width, height, req.conf_threshold, reason)

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


def _fallback_reason() -> str:
    """싱글톤이 아직 초기화되지 않은 경우 등 사유를 결정한다."""
    return "env_mock" if get_settings().ai_mock_mode else "weights_missing"


def _warn_mock_once(reason: str) -> None:
    """프로세스 수명 동안 mock 응답이 처음 발생할 때 1회만 WARN 로그."""
    global _mock_warned
    if not _mock_warned:
        logger.warning(
            "[YOLO][MOCK] returning mock prediction "
            "(model not loaded or AI_MOCK_MODE=true) reason=%s",
            reason,
        )
        _mock_warned = True


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
    return YoloResponse(detections=detections, mock=False, source="model", mock_reason=None)


def _mock_predict(
    width: int, height: int, conf_threshold: float, reason: str
) -> YoloResponse:
    """Mock 응답.

    - reason == "env_mock" : 명시적 mock 모드 → 결정적 중앙 person 박스
    - 그 외 (weights_missing / load_failed) : 운영 데이터 오염 방지 위해 빈 detections
    """
    detections: list[Detection] = []
    if reason == "env_mock":
        cx, cy = width / 2.0, height / 2.0
        half = min(width, height) * 0.2
        score = 0.9
        if score >= conf_threshold:
            detections.append(
                Detection(
                    label="person",
                    points=[cx - half, cy - half, cx + half, cy + half],
                    score=score,
                )
            )
    return YoloResponse(
        detections=detections, mock=True, source="mock", mock_reason=reason
    )


def reset_mock_warn_flag() -> None:
    """테스트용 — mock WARN 플래그 초기화."""
    global _mock_warned
    _mock_warned = False
