"""전환 가능한 탐지 백엔드 공통 추상화.

두 백엔드(YOLO / RT-DETR)가 공유하는 중립 자료형과 헬퍼를 정의한다.
- DetectionResult: 백엔드 비종속 탐지 결과 (라우터가 schemas.Detection 으로 변환)
- DetectorBackend: predict/track 인터페이스 (Protocol)
- coco_label_from_id: RT-DETR id2label → ultralytics 와 동일 의미의 COCO 라벨 매핑

schemas.py 의 스키마/HTTP 경로는 절대 변경하지 않는다 — 이 모듈은 내부 표현일 뿐이고,
라우터에서 schemas.Detection 으로 매핑되어 기존 응답 계약을 그대로 유지한다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Protocol, runtime_checkable


@dataclass
class InferenceParams:
    """추론 파라미터 (백엔드 비종속).

    - conf_threshold: 신뢰도 임계값
    - imgsz: 입력 해상도 힌트. RT-DETR 에서는 processor resize 힌트로만 사용된다(의미 변경).
    - iou: NMS IoU. RT-DETR 은 NMS-free 라 미사용(호환 위해 필드 유지).
    - clip_id / frame_index: track 시 트래커 격리/리셋용
    """

    conf_threshold: float = 0.4
    imgsz: int = 1280
    iou: float = 0.5
    clip_id: str = ""
    frame_index: int = 0


@dataclass
class DetectionResult:
    """백엔드 비종속 탐지 결과 1건."""

    label: str
    points: list[float]  # [x1, y1, x2, y2]
    score: float
    track_id: Optional[int] = None
    meta: dict = field(default_factory=dict)


@runtime_checkable
class DetectorBackend(Protocol):
    """탐지 백엔드 공통 인터페이스.

    - predict(img, params) -> list[DetectionResult]
    - track(img, params)   -> list[DetectionResult]  (track_id 포함)
    """

    def predict(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        ...

    def track(self, img: object, params: InferenceParams) -> list[DetectionResult]:
        ...


# ────────────────────────────────────────────────────────────────────
# COCO id2label — RT-DETR(COCO 80 class) → ultralytics 와 동일 의미 라벨
# ────────────────────────────────────────────────────────────────────
# RT-DETR 의 transformers config 가 id2label 을 제공하면 그대로 우선 사용한다.
# 제공되지 않는(혹은 키가 없는) 경우를 위해 표준 COCO 80 라벨을 fallback 으로 둔다.
# ultralytics YOLOv8 의 COCO names 와 동일 명칭이므로 person/car 등 의미가 일치한다.
COCO_ID2LABEL: dict[int, str] = {
    0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 4: "airplane",
    5: "bus", 6: "train", 7: "truck", 8: "boat", 9: "traffic light",
    10: "fire hydrant", 11: "stop sign", 12: "parking meter", 13: "bench",
    14: "bird", 15: "cat", 16: "dog", 17: "horse", 18: "sheep", 19: "cow",
    20: "elephant", 21: "bear", 22: "zebra", 23: "giraffe", 24: "backpack",
    25: "umbrella", 26: "handbag", 27: "tie", 28: "suitcase", 29: "frisbee",
    30: "skis", 31: "snowboard", 32: "sports ball", 33: "kite",
    34: "baseball bat", 35: "baseball glove", 36: "skateboard", 37: "surfboard",
    38: "tennis racket", 39: "bottle", 40: "wine glass", 41: "cup", 42: "fork",
    43: "knife", 44: "spoon", 45: "bowl", 46: "banana", 47: "apple",
    48: "sandwich", 49: "orange", 50: "broccoli", 51: "carrot", 52: "hot dog",
    53: "pizza", 54: "donut", 55: "cake", 56: "chair", 57: "couch",
    58: "potted plant", 59: "bed", 60: "dining table", 61: "toilet", 62: "tv",
    63: "laptop", 64: "mouse", 65: "remote", 66: "keyboard", 67: "cell phone",
    68: "microwave", 69: "oven", 70: "toaster", 71: "sink", 72: "refrigerator",
    73: "book", 74: "clock", 75: "vase", 76: "scissors", 77: "teddy bear",
    78: "hair drier", 79: "toothbrush",
}


def coco_label_from_id(class_id: int, id2label: dict[int, str] | None) -> str:
    """class_id → 라벨 문자열.

    transformers 모델 config 의 id2label 이 있으면 우선 사용하고,
    없으면 표준 COCO 80 라벨로 매핑한다. 둘 다 미스면 문자열화한 id 반환.
    """
    if id2label and class_id in id2label:
        return id2label[class_id]
    return COCO_ID2LABEL.get(class_id, str(class_id))


# 역참조 캐시: 라벨 문자열 → COCO class_id (coco_label_from_id 의 역방향).
# 동일 라벨이 항상 동일 정수로 매핑되도록 1회 구성한다.
_LABEL2ID: dict[str, int] = {label: cid for cid, label in COCO_ID2LABEL.items()}
# 미지의(COCO 외) 라벨에 결정적 정수 id 를 부여하기 위한 base offset.
# COCO 80개(0~79)와 충돌하지 않도록 충분히 큰 값에서 시작한다.
_UNKNOWN_LABEL_ID_BASE: int = 10_000


def coco_id_from_label(label: str) -> int:
    """라벨 문자열 → 안정적인 정수 class_id (트래커 클래스 분리용).

    - COCO 표준 라벨(person/car 등)은 해당 COCO class_id 를 그대로 반환한다.
    - 라벨이 정수 문자열이면 그 정수를 반환한다(coco_label_from_id 의 id 미스 fallback 대칭).
    - 그 외 미지의 라벨은 라벨 문자열 해시를 기반으로 한 결정적 정수를 부여한다.
      같은 라벨은 항상 같은 정수가 되며 COCO id 범위(0~79)와 충돌하지 않는다.

    좌표/스키마는 건드리지 않고 트래커에 전달할 class_id 만 안정화한다.
    """
    cid = _LABEL2ID.get(label)
    if cid is not None:
        return cid
    # coco_label_from_id 가 id 미스 시 str(id) 를 반환하므로 그 역방향을 복원
    if label.isdigit():
        return int(label)
    # 미지의 라벨 — 결정적 fallback (해시 mod). 동일 라벨은 항상 동일 정수.
    return _UNKNOWN_LABEL_ID_BASE + (hash(label) & 0xFFFF)
