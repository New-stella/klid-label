"""
IntelliVIX Video VLM 벤더 목 — 요청/응답 스키마 (API v2.0.1 정합).

요청은 pydantic 모델로 타입/필수/값범위를 검증한다(CWE-20). 요청 스키마를 명시적으로
정의하므로 Entity 직접 바인딩(Mass Assignment) 우려가 없다. 외부 벤더 관대성을 위해
알 수 없는 필드는 무시(extra="ignore")한다.

값 검증 계약:
- event_type 은 Enum 화이트리스트(fire|fall|violence|flooding|car_accident|kidnapping) —
  범위 밖 값은 422.
- selected_frames 는 최대 8개 — Video VLM 1회 추론 프레임 상한(초과 시 sliding window).
  8 초과는 422.
- request_id 는 Optional — 누락/공백이면 라우터가 방어적으로 UUID 를 발급해 echo 일관성을
  유지한다(우리 BE VlmClient 가 request_id echo + status="accepted" 를 검증하기 때문).
"""

from __future__ import annotations

from enum import Enum
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class EventType(str, Enum):
    """검증 대상 이벤트 유형 화이트리스트."""

    FIRE = "fire"
    FALL = "fall"
    VIOLENCE = "violence"
    FLOODING = "flooding"
    CAR_ACCIDENT = "car_accident"
    KIDNAPPING = "kidnapping"


class FramePolicy(BaseModel):
    """프레임 추출 정책 — frame_interval(간격) 또는 frame_selected(지정 프레임)."""

    model_config = ConfigDict(extra="ignore")

    mode: Literal["frame_interval", "frame_selected"]
    framerate: Optional[int] = Field(default=None, ge=1, le=240, description="frame_interval 기준 fps")
    # Video VLM 1회 추론 최대 8프레임 — 초과 시 422(값 오류).
    selected_frames: Optional[list[int]] = Field(
        default=None, max_length=8, description="frame_selected 지정 프레임(최대 8)"
    )


class Media(BaseModel):
    """분석 대상 미디어 서술."""

    model_config = ConfigDict(extra="ignore")

    type: Literal["video", "image"]
    source_type: Literal["path", "upload"]
    path: Optional[str] = Field(default=None, description="source_type=path 일 때 미디어 경로")
    frame_policy: Optional[FramePolicy] = None


class VerifyRequest(BaseModel):
    """POST /v1/videovlm/verify — 이벤트 검증 요청."""

    model_config = ConfigDict(extra="ignore")

    request_id: Optional[str] = Field(default=None, description="상관키(누락 시 서버 발급)")
    event_type: EventType
    media: Media
    # HttpUrl 로 형식+http/https 스킴을 검증(무스킴/잘못된 URL 은 접수 시점 422).
    # 콜백 발사(httpx) 시에는 라우터에서 str(...) 로 캐스팅해 사용한다.
    callback_url: HttpUrl = Field(..., description="결과 수신 콜백 URL")


class DescribeRequest(BaseModel):
    """POST /v1/videovlm/describe — 상황 묘사 요청(event_type 없음)."""

    model_config = ConfigDict(extra="ignore")

    request_id: Optional[str] = Field(default=None, description="상관키(누락 시 서버 발급)")
    media: Media
    # HttpUrl 로 형식+http/https 스킴을 검증(무스킴/잘못된 URL 은 접수 시점 422).
    # 콜백 발사(httpx) 시에는 라우터에서 str(...) 로 캐스팅해 사용한다.
    callback_url: HttpUrl = Field(..., description="결과 수신 콜백 URL")


class AcceptedResponse(BaseModel):
    """verify/describe 동기 응답 — 수락(Acknowledge)만 의미.

    우리 BE VlmClient 는 request_id echo + status="accepted" 를 검증하므로
    필드명/값을 규격대로 유지한다.
    """

    request_id: str
    status: str = "accepted"
