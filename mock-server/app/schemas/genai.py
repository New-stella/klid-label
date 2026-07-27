"""
생성형 AI 벤더 목 — 요청/응답 스키마 (「생성형 AI API 연동명세서 v1.1」 정합).

명세서 §4.1~§4.6 의 필드명·타입·길이·필수여부를 그대로 반영한다. 요청은 pydantic 모델로
타입/필수/길이/enum 을 검증하므로(CWE-20) 미선언 필드는 무시되고(extra="ignore",
Mass Assignment CWE-915 차단) 서버 내부 상태에 바인딩되지 않는다.

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
    """작업 유형 — 생성 / 증강 / 변형."""

    GENERATE = "GENERATE"
    AUGMENT = "AUGMENT"
    TRANSFORM = "TRANSFORM"


class GenerationMode(str, Enum):
    """생성 모드 — Text/Image to Image/Video."""

    T2I = "T2I"
    T2V = "T2V"
    I2I = "I2I"
    I2V = "I2V"


#: 입력 파일이 1건 이상 필요한 생성 모드(§4.1).
INPUT_REQUIRED_MODES: frozenset[str] = frozenset(
    {GenerationMode.I2I.value, GenerationMode.I2V.value}
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
    evnt_type: str = Field(..., min_length=1, max_length=20)
    operation_type: OperationType
    generation_mode: GenerationMode
    # I2I·I2V 는 1건 이상 필수(라우터에서 REQUIRED_FIELD_MISSING 으로 검증).
    # 상한 100 은 목 서버 자원 보호(CWE-400).
    input_files: list[InputFile] = Field(default_factory=list, max_length=100)
    prompt: dict[str, Any] = Field(..., description="생성·변형 조건 구조화 메타")
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
