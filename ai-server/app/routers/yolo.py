"""
YOLO 오토라벨링 추론 엔드포인트.

POST /infer/yolo/predict
- 입력: image_b64 (base64) + conf_threshold/imgsz/iou
- 출력: detections [{label, points: [x1,y1,x2,y2], score, track_id=None}]
        + mock (bool) / source ("mock"|"model") / mock_reason

POST /infer/yolo/track (Phase 2)
- 입력: image_b64 + clip_id + frame_index + conf_threshold/imgsz/iou
- 출력: predict 와 동일 + detections[*].track_id (트래커 부여 객체 ID)
- clip_id 별 트래커 인스턴스 격리 + frame_index=0 시 리셋

- AI_MOCK_MODE=true 또는 가중치 부재 시 mock 응답 반환
  운영에서 mock 응답이 흘러나가면 BE 가 mock=true 를 감지해 WARN 로그를 남긴다.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.config import get_settings
from app.image_utils import decode_image_b64, decode_image_b64_pil
from app.models import rtdetr_loader, yolo_loader
from app.models.detector_backend import DetectionResult, InferenceParams
from app.models.yolo_loader import get_yolo_mock_reason, get_yolo_model
from app.schemas import (
    Detection,
    YoloRequest,
    YoloResponse,
    YoloTrackRequest,
    YoloTrackResponse,
)

router = APIRouter()
logger = logging.getLogger(__name__)

# 운영에서 mock 응답이 첫 호출 시 1회 WARN 출력하기 위한 플래그
_mock_warned: bool = False
_track_mock_warned: bool = False


@router.post("/predict", response_model=YoloResponse)
async def predict(req: YoloRequest) -> YoloResponse:
    """객체 감지 — detector_backend 설정에 따라 yolo/rtdetr 로 dispatch."""
    if get_settings().resolved_detector_backend() == "rtdetr":
        return _predict_rtdetr(req)

    model = get_yolo_model()

    if model is None:
        # mock mode 또는 가중치 미존재 / 로드 실패
        width, height = decode_image_b64(req.image_b64)
        reason = get_yolo_mock_reason() or _fallback_reason()
        _warn_mock_once(reason)
        logger.info(
            "[YOLO][MOCK] predict reason=%s conf_threshold=%.2f imgsz=%d iou=%.2f image_size=%dx%d",
            reason,
            req.conf_threshold,
            req.imgsz,
            req.iou,
            width,
            height,
        )
        return _mock_predict(width, height, req.conf_threshold, reason)

    # 실제 추론
    img = decode_image_b64_pil(req.image_b64)
    try:
        logger.info(
            "[YOLO] real predict conf_threshold=%.2f imgsz=%d iou=%.2f image_size=%dx%d",
            req.conf_threshold,
            req.imgsz,
            req.iou,
            img.width,
            img.height,
        )
        return _run_inference(model, img, req.conf_threshold, req.imgsz, req.iou)
    finally:
        img.close()


def _fallback_reason() -> str:
    """싱글톤이 아직 초기화되지 않은 경우 등 사유를 결정한다."""
    return "env_mock" if get_settings().ai_mock_mode else "weights_missing"


def _warn_mock_once(reason: str, backend: str = "yolo") -> None:
    """프로세스 수명 동안 mock 응답이 처음 발생할 때 1회만 WARN 로그.

    backend 식별자를 접두사로 반영하여 RT-DETR mock 도 로그에서 구분되도록 한다.
    """
    global _mock_warned
    if not _mock_warned:
        logger.warning(
            "[DETECT:%s][MOCK] returning mock prediction "
            "(model not loaded or AI_MOCK_MODE=true) reason=%s",
            backend,
            reason,
        )
        _mock_warned = True


def _run_inference(
    model: object, img: object, conf_threshold: float, imgsz: int, iou: float
) -> YoloResponse:
    """ultralytics YOLO 모델로 실제 추론을 수행한다."""
    results = model(  # type: ignore[operator]
        img, conf=conf_threshold, imgsz=imgsz, iou=iou, verbose=False
    )

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
    global _mock_warned, _track_mock_warned
    _mock_warned = False
    _track_mock_warned = False


# ────────────────────────────────────────────────────────────────────
# RT-DETR 백엔드 dispatch (transformers + ByteTrack)
# ────────────────────────────────────────────────────────────────────

def _to_detection(d: DetectionResult) -> Detection:
    """백엔드 중립 DetectionResult → schemas.Detection (응답 계약 유지)."""
    return Detection(
        label=d.label,
        points=[d.points[0], d.points[1], d.points[2], d.points[3]],
        score=d.score,
        track_id=d.track_id,
    )


def _predict_rtdetr(req: YoloRequest) -> YoloResponse:
    """RT-DETR predict dispatch — 미설치/로드실패 시 mock 응답(yolo 와 동일 사유 체계)."""
    backend = rtdetr_loader.get_rtdetr_model()
    if backend is None:
        width, height = decode_image_b64(req.image_b64)
        reason = rtdetr_loader.get_rtdetr_mock_reason() or _fallback_reason()
        _warn_mock_once(reason, backend="rtdetr")
        logger.info(
            "[RTDETR][MOCK] predict reason=%s conf_threshold=%.2f imgsz=%d image_size=%dx%d",
            reason, req.conf_threshold, req.imgsz, width, height,
        )
        return _mock_predict(width, height, req.conf_threshold, reason)

    img = decode_image_b64_pil(req.image_b64)
    try:
        # iou 는 RT-DETR(NMS-free)에서 미사용, imgsz 는 processor resize 힌트로만 사용
        params = InferenceParams(conf_threshold=req.conf_threshold, imgsz=req.imgsz, iou=req.iou)
        logger.info(
            "[RTDETR] real predict conf_threshold=%.2f imgsz=%d image_size=%dx%d",
            req.conf_threshold, req.imgsz, img.width, img.height,
        )
        detections = [_to_detection(d) for d in backend.predict(img, params)]
        return YoloResponse(detections=detections, mock=False, source="model", mock_reason=None)
    finally:
        img.close()


def _track_rtdetr(req: YoloTrackRequest) -> YoloTrackResponse:
    """RT-DETR track dispatch — clip_id 격리 + frame_index=0 리셋(yolo track 동일 계약)."""
    backend = rtdetr_loader.get_rtdetr_tracker(req.clip_id, reset=(req.frame_index == 0))
    if backend is None:
        reason = rtdetr_loader.get_rtdetr_mock_reason() or _fallback_reason()
        _warn_track_mock_once(reason, backend="rtdetr")
        logger.info(
            "[RTDETR][MOCK] track reason=%s clip_id=%s frame_index=%d",
            reason, req.clip_id, req.frame_index,
        )
        return _mock_track(req, reason)

    img = decode_image_b64_pil(req.image_b64)
    try:
        params = InferenceParams(
            conf_threshold=req.conf_threshold,
            imgsz=req.imgsz,
            iou=req.iou,
            clip_id=req.clip_id,
            frame_index=req.frame_index,
        )
        logger.info(
            "[RTDETR] real track clip_id=%s frame_index=%d conf_threshold=%.2f image_size=%dx%d",
            req.clip_id, req.frame_index, req.conf_threshold, img.width, img.height,
        )
        detections = [_to_detection(d) for d in backend.track(img, params)]
        return YoloTrackResponse(detections=detections, mock=False, source="model", mock_reason=None)
    finally:
        img.close()


# ────────────────────────────────────────────────────────────────────
# /track 엔드포인트 + YOLO(BoT-SORT) track 핸들러 (Phase 2)
#   - 백엔드 설정에 따라 위 RT-DETR dispatch 또는 아래 YOLO model.track() 으로 분기
# ────────────────────────────────────────────────────────────────────

@router.post("/track", response_model=YoloTrackResponse)
async def track(req: YoloTrackRequest) -> YoloTrackResponse:
    """clip_id 단위로 트래커 상태를 격리하며 같은 객체에 같은 track_id 부여.

    detector_backend 설정에 따라 yolo(BoT-SORT)/rtdetr(ByteTrack) 로 dispatch.
    """
    if get_settings().resolved_detector_backend() == "rtdetr":
        return _track_rtdetr(req)

    model = yolo_loader.get_yolo_tracker(req.clip_id, reset=(req.frame_index == 0))

    if model is None:
        reason = yolo_loader.get_yolo_mock_reason() or _fallback_reason()
        _warn_track_mock_once(reason)
        logger.info(
            "[YOLO][MOCK] track reason=%s clip_id=%s frame_index=%d "
            "conf_threshold=%.2f imgsz=%d iou=%.2f",
            reason,
            req.clip_id,
            req.frame_index,
            req.conf_threshold,
            req.imgsz,
            req.iou,
        )
        return _mock_track(req, reason)

    img = decode_image_b64_pil(req.image_b64)
    try:
        logger.info(
            "[YOLO] real track clip_id=%s frame_index=%d "
            "conf_threshold=%.2f imgsz=%d iou=%.2f image_size=%dx%d",
            req.clip_id,
            req.frame_index,
            req.conf_threshold,
            req.imgsz,
            img.width,
            img.height,
        )
        return _run_track_inference(model, img, req)
    finally:
        img.close()


def _warn_track_mock_once(reason: str, backend: str = "yolo") -> None:
    """프로세스 수명 동안 track mock 응답이 처음 발생할 때 1회만 WARN 로그.

    backend 식별자를 접두사로 반영하여 RT-DETR mock 도 로그에서 구분되도록 한다.
    """
    global _track_mock_warned
    if not _track_mock_warned:
        logger.warning(
            "[DETECT:%s][MOCK] returning mock track "
            "(model not loaded or AI_MOCK_MODE=true) reason=%s",
            backend,
            reason,
        )
        _track_mock_warned = True


def _run_track_inference(
    model: object, img: object, req: YoloTrackRequest
) -> YoloTrackResponse:
    """ultralytics YOLO.track() 으로 실제 추론.

    - persist=False: frame_index=0 (트래커 리셋 후 첫 프레임)
    - persist=True : frame_index>0 (이전 호출의 트래커 상태 유지)
    - tracker="botsort.yaml": ultralytics 패키지 동봉 BoT-SORT 기본 설정
    """
    results = model.track(  # type: ignore[attr-defined]
        img,
        conf=req.conf_threshold,
        imgsz=req.imgsz,
        iou=req.iou,
        persist=(req.frame_index > 0),
        tracker="botsort.yaml",
        verbose=False,
    )

    detections: list[Detection] = []
    for result in results:
        if result.boxes is None:
            continue
        boxes = result.boxes
        names = result.names
        ids = boxes.id  # torch.Tensor | None — 저신뢰 detection 은 None 가능

        for i in range(len(boxes)):
            xyxy = boxes.xyxy[i].tolist()
            score = float(boxes.conf[i])
            cls_id = int(boxes.cls[i])
            label = names.get(cls_id, str(cls_id))

            # ids 자체가 None 이거나, 인덱스 i 의 값이 None/NaN 일 수 있음
            track_id: int | None = None
            if ids is not None:
                try:
                    track_id = int(ids[i])
                except (TypeError, ValueError):
                    track_id = None

            detections.append(
                Detection(
                    label=label,
                    points=[xyxy[0], xyxy[1], xyxy[2], xyxy[3]],
                    score=score,
                    track_id=track_id,
                )
            )

    logger.debug(
        "[YOLO] real track clip_id=%s frame_index=%d detections=%d",
        req.clip_id,
        req.frame_index,
        len(detections),
    )
    return YoloTrackResponse(
        detections=detections, mock=False, source="model", mock_reason=None
    )


def _mock_track(req: YoloTrackRequest, reason: str) -> YoloTrackResponse:
    """track 엔드포인트 mock 응답.

    - reason == "env_mock"          : 결정적 1개 person 박스 + track_id=1
    - 그 외 (weights_missing 등)    : 빈 detections (운영 오염 방지)
    """
    detections: list[Detection] = []
    if reason == "env_mock":
        width, height = decode_image_b64(req.image_b64)
        cx, cy = width / 2.0, height / 2.0
        half = min(width, height) * 0.2
        score = 0.9
        if score >= req.conf_threshold:
            detections.append(
                Detection(
                    label="person",
                    points=[cx - half, cy - half, cx + half, cy + half],
                    score=score,
                    track_id=1,
                )
            )
    return YoloTrackResponse(
        detections=detections, mock=True, source="mock", mock_reason=reason
    )
