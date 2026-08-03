"""
Pydantic v2 Request/Response 스키마.

Spring Boot AiServerClient 호출 정합성을 위해 다음 경로/형식을 따른다:
- POST /infer/yolo/predict   → YoloRequest      → YoloResponse
- POST /infer/yolo/track     → YoloTrackRequest → YoloTrackResponse
- POST /infer/sam2/segment   → Sam2SegmentRequest → Sam2SegmentResponse
- POST /infer/sam2/track     → Sam2TrackRequest   → Sam2TrackResponse
- POST /infer/vlm/verify-objects → VlmVerifyRequest → VlmVerifyResponse
"""

from __future__ import annotations

from typing import Annotated, Optional

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
    conf_threshold: float = Field(default=0.4, ge=0.0, le=1.0)
    imgsz: int = Field(default=1280, ge=320, le=1920, description="추론 입력 해상도(px)")
    iou: float = Field(default=0.5, ge=0.0, le=1.0, description="NMS IoU 임계값")
    classes: Optional[list[str]] = Field(
        default=None,
        max_length=100,
        description=(
            "검출 대상 클래스 라벨 화이트리스트(COCO 영문명). None=전체(미필터). "
            "빈 리스트도 전체로 처리. 지정 시 detection.label 이 목록에 포함된 결과만 반환"
        ),
    )


class Detection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    label: str
    points: list[float] = Field(..., min_length=4, max_length=4, description="[x1, y1, x2, y2]")
    score: float = Field(..., ge=0.0, le=1.0)
    track_id: Optional[int] = Field(
        default=None,
        description=(
            "트래커가 부여한 객체 ID. /predict 응답에서는 항상 None, "
            "/track 응답에서만 의미 있음. 저신뢰 detection 은 트래커가 ID 를 부여하지 않을 수 있음"
        ),
    )


class YoloResponse(BaseModel):
    """YOLO 추론 응답.

    - mock: True 이면 ai-server 가 mock 응답을 반환했음을 의미한다.
      운영에서 mock 응답이 흘러나가면 데이터 품질이 떨어지므로 BE 가 감지해 경고를 남긴다.
    - source: "mock" | "model" — mock 의 사유까지 문자열로 표시.
      ("weights_missing" 등 세부 사유는 mock_reason 에 별도 표기)
    """

    model_config = ConfigDict(extra="forbid")

    detections: list[Detection]
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(
        default=None,
        description='mock 응답인 경우 사유. "env_mock" | "weights_missing" | "load_failed"',
    )
    success: bool = Field(default=True, description="성공 여부")
    message: str = Field(default="성공", description="응답 메시지")
    error_code: str | None = Field(default=None, description="에러 코드(성공 시 null)")


class YoloTrackRequest(BaseModel):
    """YOLO 객체 트래킹 요청.

    - clip_id: 영상 식별자. ai-server 가 이 키로 트래커 인스턴스를 격리/캐시한다.
    - frame_index: 영상 내 프레임 순서. 0 이면 트래커 리셋 (새 인스턴스), 그 외 persist=True.
    """

    model_config = ConfigDict(extra="forbid")

    image_b64: str = Field(..., min_length=1, description="base64 인코딩 이미지(jpeg/png)")
    clip_id: str = Field(
        ...,
        min_length=1,
        max_length=128,
        description="영상 식별자 (BE 의 rawSn 등). 트래커 인스턴스 격리 키",
    )
    frame_index: int = Field(
        ..., ge=0, description="영상 내 프레임 순서. 0 이면 트래커 리셋"
    )
    conf_threshold: float = Field(default=0.4, ge=0.0, le=1.0)
    imgsz: int = Field(default=1280, ge=320, le=1920, description="추론 입력 해상도(px)")
    iou: float = Field(default=0.5, ge=0.0, le=1.0, description="NMS IoU 임계값")
    classes: Optional[list[str]] = Field(
        default=None,
        max_length=100,
        description=(
            "검출 대상 클래스 라벨 화이트리스트(COCO 영문명). None=전체(미필터). "
            "빈 리스트도 전체로 처리. 지정 시 detection.label 이 목록에 포함된 결과만 반환"
        ),
    )


class YoloTrackResponse(BaseModel):
    """YOLO 트래킹 응답.

    /predict 와 동일한 필드 구성. detections 의 track_id 필드가 트래커 부여 ID.
    저신뢰 detection 의 경우 track_id 는 None 일 수 있다.
    """

    model_config = ConfigDict(extra="forbid")

    detections: list[Detection]
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(
        default=None,
        description='mock 응답인 경우 사유. "env_mock" | "weights_missing" | "load_failed"',
    )
    success: bool = Field(default=True, description="성공 여부")
    message: str = Field(default="성공", description="응답 메시지")
    error_code: str | None = Field(default=None, description="에러 코드(성공 시 null)")


# ────────────────────────────────────────────────────────────────────
# SAM2
# ────────────────────────────────────────────────────────────────────

#: 좌표 1개 스칼라 — **유한한 수만** 허용한다 (CWE-20).
#: ``allow_inf_nan=False`` 가 없으면 ``NaN``/``Infinity``/``1e400`` 이 스키마를 통과해
#: 추론·mock fallback 을 거쳐 200 응답의 ``null`` 좌표로 새어나간다(JSON 은 NaN 을 표현 못 함).
Coord = Annotated[float, Field(allow_inf_nan=False)]

#: 좌표 1점 — 정확히 ``[x, y]`` 2원소. 원소 개수를 스키마에서 강제해(CWE-20)
#: 원소 부족/과다 입력이 추론·mock fallback 로직에 도달하지 못하게 앞단 차단한다.
Point2D = Annotated[list[Coord], Field(min_length=2, max_length=2)]

#: 클릭 좌표 개수 상한 — BE ``Sam2SegmentRequest.points`` 의 ``@Size(max = 100)`` 과 일치(CWE-770).
MAX_SEGMENT_POINTS = 100

#: 폴리곤 정점 상한 — BE ``Sam2TrackRequest.prevPolygon`` 의 ``@Size(min = 3, max = 1000)`` 과 일치.
MAX_POLYGON_POINTS = 1000

#: 트랙 ID 상한 — BE ``TRACK_ID VARCHAR(64)`` 및 ``@Size(max = 64)`` 와 일치.
MAX_TRACK_ID_LENGTH = 64

#: 트랙 ID 허용 문자 — 영숫자 + ``. _ : -``. 개행·제어문자를 앞단에서 배제해
#: 로그 위조(CWE-117)의 입력원 자체를 없앤다.
TRACK_ID_PATTERN = r"^[A-Za-z0-9._:-]+$"


class Sam2SegmentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    image_b64: str = Field(..., min_length=1)
    points: list[Point2D] | None = Field(
        default=None,
        max_length=MAX_SEGMENT_POINTS,
        description="[[x, y], ...] 클릭 좌표",
    )
    box: list[Coord] | None = Field(
        default=None, min_length=4, max_length=4, description="[x1, y1, x2, y2]"
    )


class Sam2SegmentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # 하한 1 — 요청은 3점 이상을 요구하는데 응답에 하한이 없으면 빈 폴리곤이 200 으로 나간다.
    # (segment 응답 생산자는 mock=4점 / real=contour 3점 이상이라 하한 위반이 발생하지 않는다.)
    polygon: list[list[float]] = Field(
        ..., min_length=1, description="[[x, y], ...] 폐곡선 좌표"
    )
    score: float = Field(..., ge=0.0, le=1.0)
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)
    success: bool = Field(default=True, description="성공 여부")
    message: str = Field(default="성공", description="응답 메시지")
    error_code: str | None = Field(default=None, description="에러 코드(성공 시 null)")


class Sam2TrackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str = Field(
        ...,
        min_length=1,
        max_length=MAX_TRACK_ID_LENGTH,
        pattern=TRACK_ID_PATTERN,
        description="이전 프레임에서 부여된 트랙 ID (영숫자 + . _ : - 만 허용 — CWE-117)",
    )
    prev_image_b64: str = Field(default="", description="이전 프레임 base64 — mock 모드에서는 미사용")
    next_image_b64: str = Field(default="", description="다음 프레임 base64 — mock 모드에서는 미사용")
    prev_polygon: list[Point2D] = Field(
        ..., min_length=3, max_length=MAX_POLYGON_POINTS, description="이전 프레임 폴리곤"
    )


class Sam2TrackResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_id: str
    # ⚠ segment 응답과 달리 **하한을 두지 않는다**. track 의 마지막 방어선인 이전 폴리곤 fallback
    # (``_prev_polygon_fallback``)은 어떤 입력에도 예외를 던지지 않아야 하는데(CWE-755),
    # 이전 폴리곤 전체가 해석 불가면 돌려줄 좌표가 0개다. 여기에 min_length 를 걸면 그 순간
    # fallback 이 ValidationError(=500)로 폭발해 방어선 자체가 무너진다. 빈 폴리곤은 "추적 실패"를
    # 뜻하는 정직한 응답이며, BE(Sam2TrackService.validatePolygon)가 빈 값을 별도로 차단한다.
    polygon: list[list[float]]
    score: float = Field(..., ge=0.0, le=1.0)
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)


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
    mock: bool = Field(default=False, description="mock 응답이면 True")
    source: str = Field(default="model", description='"mock" | "model"')
    mock_reason: str | None = Field(default=None)
