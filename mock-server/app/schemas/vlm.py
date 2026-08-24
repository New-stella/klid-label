"""
IntelliVIX Video VLM 벤더 목 — 요청/응답 스키마 (KLID 연동 API v1.1.0 정합).

요청은 pydantic 모델로 타입/필수/값범위를 검증한다(CWE-20). 요청 스키마를 명시적으로
정의하므로 Entity 직접 바인딩(Mass Assignment) 우려가 없다. 외부 벤더 관대성을 위해
알 수 없는 필드는 무시(extra="ignore")한다.

값 검증 계약:
- event_type 은 값을 좁히지 않는다 — 아래 KlidAnalysisRequest 주석 참조(목 한정 완화).
- selected_frames 는 최대 600개 — 규격 §2.5·§2.9. 초과는 400.
- frame_policy 에 framerate 는 **없다** — mode 만 연동 시스템이 지정하고 간격·장수는 서버 몫이다.
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
    SMOKE = "smoke"
    FALL = "fall"
    VIOLENCE = "violence"
    FLOODING = "flooding"
    CAR_ACCIDENT = "car_accident"
    KIDNAPPING = "kidnapping"


class FramePolicy(BaseModel):
    """프레임 선택 정책 — KLID 연동 API v1.1.0 §2.5.

    ★ ``framerate`` 필드는 **규격에 없다**. mode 만 연동 시스템이 지정하고 간격·장수 등 세부
    값은 서버가 관리한다. 구 규격(v2.0.1 verify)의 framerate 는 폐기됐으므로 되살리지 말 것.
    ``extra="ignore"`` 라 옛 클라이언트가 보내도 접수는 되지만 아무 영향이 없다.
    """

    model_config = ConfigDict(extra="ignore")

    mode: Literal["frame_interval", "uniform", "frame_selected"]
    # 규격 §2.5·§2.9 — 정수 배열, 최대 600개, 음수 불가. 초과/음수는 400.
    selected_frames: Optional[list[int]] = Field(
        default=None,
        max_length=600,
        description="frame_selected 지정 프레임(최대 600, 음수 불가)",
    )


class Media(BaseModel):
    """분석 대상 미디어 서술."""

    model_config = ConfigDict(extra="ignore")

    type: Literal["video", "image"]
    source_type: Literal["path", "upload"]
    path: Optional[str] = Field(default=None, description="source_type=path 일 때 미디어 경로")
    frame_policy: Optional[FramePolicy] = None
    # 벤더 규격 밖 <b>목 전용 확장</b> — describe 더미 구간을 만들 영상 길이를 직접 지정한다.
    #   우리 BE 는 보내지 않으며(없으면 목이 ffprobe 로 조회 → 실패 시 고정 폴백), 테스트/데모에서
    #   특정 길이를 결정적으로 재현하기 위한 힌트다. 값 제약을 걸지 않는 이유는 이상값(0/음수/nan)이
    #   422 가 되는 대신 조용히 무시되고 다음 폴백 단계로 넘어가야 하기 때문이다.
    duration_sec: Optional[float] = Field(
        default=None, description="[목 전용] 영상 길이(초) 힌트 — 생략 시 ffprobe 조회"
    )


class KlidAnalysisRequest(BaseModel):
    """묘사·추가 질문 공통 요청 — 두 창구의 요청 형식은 동일하다(규격 §3.2·§3.3)."""

    model_config = ConfigDict(extra="ignore")

    request_id: Optional[str] = Field(default=None, description="상관키(누락 시 서버 발급)")
    # ★ enum 강제·필수 폐기 (2026-08-06 사용자 확정) — **어떤 이벤트 값이든, 값이 없어도 수락**한다.
    #
    #  왜: BE 가 event_type 사전 차단을 폐기하고 "조달값을 그대로 실어 항상 위탁 → 수용 여부는
    #  벤더 응답이 정한다"로 반전했다. 그런데 목서버가 구 계약(enum + required)을 들고 있으면
    #  ①관제 값이 아직 없는 영상은 전부 400(Field required) ②우리가 모르는 값은 전부 422 라
    #  **로컬·dev 에서 파이프라인을 한 건도 완주시킬 수 없다**. 목서버의 목적은 벤더 규격 재현이
    #  아니라 그 앞뒤 배선을 돌려보는 것이므로 여기서는 열어 둔다.
    #
    #  ⚠ 이건 목서버 한정 완화이며 **실벤더가 이렇게 관대하다는 근거가 아니다**. 실벤더는 규격상
    #  required 이고 지원 이벤트도 GET /events 로 갈리므로, 벤더 확답이 오면 이 완화를 되돌리거나
    #  토글로 감싼다. 그때까지 "목서버가 받아줬으니 실연동도 된다"고 판단하지 말 것.
    #
    #  ⚠ 알 수 없는 값은 **폴백 서술**로 응답한다(값을 지어내 이벤트별 서술을 만들지 않는다).
    #  표준 7종은 종전대로 각자의 서술을 돌려준다.
    event_type: Optional[str] = Field(
        default=None,
        max_length=100,
        description="분석 대상 이벤트 유형. 표준 7종(EventType)은 전용 서술, 그 밖·미지정은 폴백 서술",
    )
    media: Media
    # HttpUrl 로 형식+http/https 스킴을 검증(무스킴/잘못된 URL 은 접수 시점 422).
    # 콜백 발사(httpx) 시에는 라우터에서 str(...) 로 캐스팅해 사용한다.
    callback_url: HttpUrl = Field(..., description="결과 수신 콜백 URL")


#: POST /v1/videovlm-klid/describe — 이벤트 묘사 요청(형식은 공통).
DescribeRequest = KlidAnalysisRequest

#: POST /v1/videovlm-klid/describe-sub — 이벤트 추가 질문 요청(형식은 공통).
#: 질문 문장은 이벤트별로 서버가 관리하며 연동 시스템이 지정하지 않는다(§3.3).
DescribeSubRequest = KlidAnalysisRequest


class AcceptedResponse(BaseModel):
    """verify/describe 동기 응답 — 수락(Acknowledge)만 의미.

    우리 BE VlmClient 는 request_id echo + status="accepted" 를 검증하므로
    필드명/값을 규격대로 유지한다.
    """

    request_id: str
    status: str = "accepted"
