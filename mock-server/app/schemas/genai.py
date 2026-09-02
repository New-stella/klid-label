"""
생성형 AI 벤더 목 — 요청/응답 스키마 (「생성형 AI API 연동명세서 v1.3」 정합).

명세서 §4 의 필드명·타입·길이·필수여부를 그대로 반영한다. 요청은 pydantic 모델로
타입/필수/길이/enum 을 검증하므로(CWE-20) 미선언 필드는 무시되고(extra="ignore",
Mass Assignment CWE-915 차단) 서버 내부 상태에 바인딩되지 않는다.

v1.2 → v1.3 요청 본문 구조 변경 (§1 · §4.1 「v1.3 Request Body 구조 규칙」):
- 생성 조건은 **최상위 ``mtdt`` 객체**(필수)로 전달한다. 구 ``prompt.condition`` 은 폐기.
- ``prompt`` 는 **최상위 문자열(최대 1000자, 선택)**. 객체·배열이면 400 INVALID_PARAMETER.
- **최상위 ``condition``** 은 v1.3 표준에서 제외됐다 — 라우터가 명시적으로 거부한다.
- ``evnt_type`` 은 FLOOD | WILDFIRE + 협의된 중립값 ``ETC``(아래 SUPPORTED_EVNT_TYPES 주석),
  ``evnt_subtype`` 은 FLOOD 세부 유형 5종(선택).
- V0 지원 조합은 GENERATE×T2I / AUGMENT×I2I / GENERATE×T2V 이며 I2V·V2V 는 미지원.

인증(§ 401 UNAUTHENTICATED / 403 FORBIDDEN)은 **이번 스코프에서 의도적으로 미구현**이며
관련 헤더/스키마도 정의하지 않는다.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, ConfigDict, Field


class JobStatus(str, Enum):
    """§3.2 상태머신 — RECEIVED → RUNNING → SUCCEEDED | FAILED | CANCELED."""

    RECEIVED = "RECEIVED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


#: 종결 상태 — 이 상태에서는 어떤 상태 변경도 허용하지 않는다(409 STATE_CONFLICT).
TERMINAL_STATUSES: frozenset[str] = frozenset(
    {JobStatus.SUCCEEDED.value, JobStatus.FAILED.value, JobStatus.CANCELED.value}
)

#: 취소 가능 상태 — §4.6.
CANCELABLE_STATUSES: frozenset[str] = frozenset(
    {JobStatus.RECEIVED.value, JobStatus.RUNNING.value}
)


class ErrorCode(str, Enum):
    """§3.3 공통 오류 코드. 401/403(인증 계열)은 스코프 제외라 정의하지 않는다."""

    REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING"
    UNSUPPORTED_EVENT_TYPE = "UNSUPPORTED_EVENT_TYPE"
    INVALID_METADATA = "INVALID_METADATA"
    INVALID_PARAMETER = "INVALID_PARAMETER"
    REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
    JOB_NOT_FOUND = "JOB_NOT_FOUND"
    RESULT_NOT_FOUND = "RESULT_NOT_FOUND"
    STATE_CONFLICT = "STATE_CONFLICT"
    MEDIA_TOO_LARGE = "GA-MEDIA-001"
    MODEL_EXECUTION_FAILED = "MODEL_EXECUTION_FAILED"
    RESULT_SAVE_FAILED = "RESULT_SAVE_FAILED"
    CALLBACK_FAILED = "CALLBACK_FAILED"
    INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"


class RequestChannel(str, Enum):
    """요청 채널 — CONTROL(관제) / AUTHORING(저작도구) / PORTAL(포털)."""

    CONTROL = "CONTROL"
    AUTHORING = "AUTHORING"
    PORTAL = "PORTAL"


class OperationType(str, Enum):
    """§4.1 작업 유형 — V0 지원값은 GENERATE(생성) / AUGMENT(증강) 2종.

    v1.2 의 ``TRANSFORM`` 은 v1.3 V0 지원값에서 제외돼 400 INVALID_PARAMETER 가 된다.
    """

    GENERATE = "GENERATE"
    AUGMENT = "AUGMENT"


class GenerationMode(str, Enum):
    """§4.1 생성 모드 — V0 지원값은 T2I | I2I | T2V 3종.

    ``I2V`` / ``V2V`` 는 v1.3 「V0 지원 요청 조합」에서 **미지원**이라 enum 에 두지 않는다
    (요청 시 400 INVALID_PARAMETER).
    """

    T2I = "T2I"
    T2V = "T2V"
    I2I = "I2I"


#: 입력 파일이 1건 이상 필요한 생성 모드(§4.1 V0 지원 요청 조합) — I2I 만 해당.
#: T2I·T2V 는 input_files 미전달이 정상이다.
INPUT_REQUIRED_MODES: frozenset[str] = frozenset({GenerationMode.I2I.value})

#: §4.1 「V0 지원 요청 조합」 — (operation_type, generation_mode).
#: 표 밖의 조합은 §3.3 "미지원 조합" 으로 400 INVALID_PARAMETER.
SUPPORTED_REQUEST_COMBINATIONS: frozenset[tuple[str, str]] = frozenset(
    {
        (OperationType.GENERATE.value, GenerationMode.T2I.value),
        (OperationType.AUGMENT.value, GenerationMode.I2I.value),
        (OperationType.GENERATE.value, GenerationMode.T2V.value),
    }
)


#: §4.1 evnt_type 허용 코드 — 규격서 V0 의 FLOOD | WILDFIRE 에 중립값 ``ETC`` 를 더한 3종.
#: 목록 밖 값은 400 UNSUPPORTED_EVENT_TYPE(§3.3) 이라 pydantic enum 이 아니라 라우터가 판정한다.
#:
#: ★ ``ETC`` 는 **벤더와 협의가 끝난 값**이다(2026-09-02 사용자 확정 · ADR-059). 저작도구는 증강
#:   위탁 바디의 evnt_type 을 요청자가 고르게 하지 않고 **서버가 ETC 로 고정 송신**한다 — 우리
#:   증강은 이미 이벤트가 담긴 프레임을 변환할 뿐이라 "무엇을 만들지" 를 정할 자리가 없기 때문이다.
#: ⚠ **다만 우리가 보유한 규격서 판본에는 이 값이 아직 없다** — 「생성형 AI API 연동명세서 v1.3」
#:   (갱신일 2026-08-12) §4.1 허용값은 FLOOD | WILDFIRE 뿐이고 오류표에 UNSUPPORTED_EVENT_TYPE
#:   (400)이 있다. **그 문서만 보고 "계약에 없는 값" 이라며 ETC 를 빼지 말 것** — 그때까지는
#:   문서가 아니라 합의가 근거다. 규격서 개정판을 받으면 값과 오류 코드를 대조한다.
#: ⚠ 성격이 VLM 목의 event_type 완화(2026-08-06)와 다르다 — 그건 관제 값이 **아직 안 채워져서**
#:   무엇이든 받아 준 목 한정 완화였고, 이건 **합의된 단일 값의 선반영**이다. 그래도 공통점은
#:   같다: **목이 받아 준다는 사실이 실벤더도 관대하다는 근거는 아니다.** 판정은 벤더 응답이 한다.
SUPPORTED_EVNT_TYPES: frozenset[str] = frozenset({"FLOOD", "WILDFIRE", "ETC"})

#: evnt_subtype 이 적용되는 evnt_type — WILDFIRE 는 정의된 세부 코드가 없다(§4.1).
EVNT_SUBTYPE_APPLICABLE_TYPE = "FLOOD"

#: §4.1 prompt 최대 길이(UTF-8 기준 문자 수). 초과 시 자르지 않고 400 INVALID_PARAMETER.
PROMPT_MAX_LEN = 1000


class EvntSubtype(str, Enum):
    """§4.1 evnt_subtype 허용 코드 — evnt_type=FLOOD 세부 유형."""

    ROAD_FLOOD = "ROAD_FLOOD"
    RIVER_OVERFLOW = "RIVER_OVERFLOW"
    UNDERPASS_FLOOD = "UNDERPASS_FLOOD"
    URBAN_INUNDATION = "URBAN_INUNDATION"
    OTHER = "OTHER"


class MtdtTime(str, Enum):
    """mtdt.time — 새벽 / 낮 / 황혼 / 밤."""

    DAWN = "DAWN"
    DAY = "DAY"
    DUSK = "DUSK"
    NIGHT = "NIGHT"


class MtdtSeason(str, Enum):
    """mtdt.season — 봄 / 여름 / 가을 / 겨울."""

    SPRING = "SPRING"
    SUMMER = "SUMMER"
    AUTUMN = "AUTUMN"
    WINTER = "WINTER"


class MtdtWeather(str, Enum):
    """mtdt.weather — 맑음 / 흐림 / 비 / 눈 / 안개 / 바람."""

    CLEAR = "CLEAR"
    CLOUDY = "CLOUDY"
    RAIN = "RAIN"
    SNOW = "SNOW"
    FOG = "FOG"
    WINDY = "WINDY"


class MtdtTerrain(str, Enum):
    """mtdt.terrain — 도로 / 지하차도 / 하천 / 도심 / 주거지역 / 시골 / 산지 / 숲."""

    ROAD = "ROAD"
    UNDERPASS = "UNDERPASS"
    RIVER = "RIVER"
    URBAN = "URBAN"
    RESIDENTIAL = "RESIDENTIAL"
    RURAL = "RURAL"
    MOUNTAIN = "MOUNTAIN"
    FOREST = "FOREST"


class MtdtSeverity(str, Enum):
    """mtdt.severity — 낮음 / 보통 / 높음."""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Mtdt(BaseModel):
    """§4.1 mtdt — 최상위 구조화 생성 조건(v1.2 prompt.condition 대체).

    하위 5필드는 모두 **선택 · nullable** 이며 값이 있으면 허용 코드 안이어야 한다(400).
    다만 ``mtdt`` 객체 자체는 필수이고, 명세서가 *"호출 측은 최소 1개 이상의 유효한 조건값을
    전달하는 것을 원칙으로 한다"* 고 규정하므로 **전부 비어 있으면 라우터가 400** 을 낸다.

    ⚠ 저작도구는 5필드를 **전부** 필수로 보내지만(우리 쪽이 더 엄격) 이 목은 **벤더**를 연기하는
    것이므로 벤더 계약(최소 1개)만 강제한다. 목을 우리 규칙으로 좁히면 실연동에서 통과할 요청이
    로컬에서 막히거나 그 반대가 된다.
    """

    model_config = ConfigDict(extra="ignore")

    time: Optional[MtdtTime] = None
    season: Optional[MtdtSeason] = None
    weather: Optional[MtdtWeather] = None
    terrain: Optional[MtdtTerrain] = None
    severity: Optional[MtdtSeverity] = None

    def has_any_value(self) -> bool:
        """유효한 조건값을 1개 이상 가지고 있는지."""
        return any(
            value is not None
            for value in (self.time, self.season, self.weather, self.terrain, self.severity)
        )


class MediaType(str, Enum):
    """결과 미디어 유형."""

    IMAGE = "IMAGE"
    VIDEO = "VIDEO"


class InputFile(BaseModel):
    """§4.1 input_files[] — 파일 본문이 아닌 **NAS 절대경로**만 주고받는다(§5.3)."""

    model_config = ConfigDict(extra="ignore")

    sequence: int = Field(..., ge=1, description="입력 순서(1부터, 중복 불가)")
    file_path: str = Field(..., min_length=1, max_length=500, description="AI 접근 가능 절대경로")
    checksum: Optional[str] = Field(default=None, max_length=100, description="SHA-256")
    source_file_id: Optional[str] = Field(default=None, max_length=64, description="원본 파일 ID")


class JobSubmitRequest(BaseModel):
    """§4.1 POST /api/genai/jobs 요청."""

    model_config = ConfigDict(extra="ignore")

    request_id: str = Field(..., min_length=1, max_length=64)
    request_channel: RequestChannel
    request_user_id: Optional[str] = Field(default=None, max_length=64)
    # 허용 코드 판정은 라우터(400 UNSUPPORTED_EVENT_TYPE) — enum 으로 두면 코드가 달라진다.
    evnt_type: str = Field(..., min_length=1, max_length=20)
    # FLOOD 세부 유형(선택). evnt_type 과의 정합은 라우터가 판정한다.
    evnt_subtype: Optional[EvntSubtype] = Field(default=None)
    operation_type: OperationType
    generation_mode: GenerationMode
    # I2I 는 1건 이상 필수(라우터에서 REQUIRED_FIELD_MISSING 으로 검증).
    # 상한 100 은 목 서버 자원 보호(CWE-400).
    input_files: list[InputFile] = Field(default_factory=list, max_length=100)
    # v1.3 최상위 필수 객체. 구 prompt.condition / 최상위 condition 은 표준에서 제외됐다.
    mtdt: Mtdt = Field(..., description="최상위 구조화 생성 조건(필수)")
    # v1.3 최상위 선택 문자열. 객체·배열이면 pydantic 타입 오류 → 400 INVALID_PARAMETER.
    prompt: Optional[str] = Field(
        default=None, max_length=PROMPT_MAX_LEN, description="자유 텍스트 상세 지시문"
    )
    model_version_id: Optional[str] = Field(default=None, max_length=64)
    parameter_set_id: Optional[str] = Field(default=None, max_length=64)
    callback_url: Optional[str] = Field(default=None, max_length=500)


class CancelRequest(BaseModel):
    """§4.6 취소 요청 — requested_by 필수."""

    model_config = ConfigDict(extra="ignore")

    reason: Optional[str] = Field(default=None, max_length=500)
    requested_by: str = Field(..., min_length=1, max_length=64)


class ResultItem(BaseModel):
    """§4.2/§4.5 results[] 항목."""

    generated_data_id: str
    media_type: MediaType
    output_file_path: str = Field(..., max_length=500)
    checksum: Optional[str] = Field(default=None, max_length=100)
    media_metadata: Optional[dict[str, Any]] = None


class JobAcceptedResponse(BaseModel):
    """§4.1 응답(202)."""

    request_id: str
    job_id: str
    status: JobStatus = JobStatus.RECEIVED
    received_at: str


class JobStatusResponse(BaseModel):
    """§4.4 응답(200)."""

    request_id: str
    job_id: str
    status: JobStatus
    progress: Optional[int] = None
    current_step: Optional[str] = None
    received_at: str
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    updated_at: str


class JobResultsResponse(BaseModel):
    """§4.5 응답(200) — SUCCEEDED 에서만 조회 가능."""

    request_id: str
    job_id: str
    status: JobStatus = JobStatus.SUCCEEDED
    results: list[ResultItem]


class JobCancelResponse(BaseModel):
    """§4.6 응답(200)."""

    request_id: str
    job_id: str
    status: JobStatus = JobStatus.CANCELED
    canceled_at: str
