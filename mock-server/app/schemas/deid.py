"""
KPST 비식별화 벤더 목 — 요청/응답 스키마.

요청은 pydantic 모델로 타입/필수를 검증한다(CWE-20). 요청 스키마를 명시적으로
정의하므로 Entity 직접 바인딩(Mass Assignment) 우려가 없다. 외부 벤더 관대성을
위해 알 수 없는 필드는 무시(extra="ignore")한다.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class ProjectCreateRequest(BaseModel):
    """POST /project — 프로젝트 생성 + 작업 등록 요청."""

    model_config = ConfigDict(extra="ignore")

    project_name: str = Field(..., min_length=1, description="프로젝트명(고유)")
    creator: str = Field(..., min_length=1, description="생성자 ID")
    export_path: str = Field(..., min_length=1, description="결과 저장 경로")
    input_path: str = Field(..., min_length=1, description="입력 경로(끝에 / 포함)")
    files: list[str] = Field(default_factory=list, description="영상모드 대상 파일 목록")
    masking_type: int = Field(default=0, description="색상0/모자이크2/블러3")
    db_save: int = Field(default=0, ge=0, le=1, description="0=미저장 1=DB저장")
    masking_range: int = Field(default=1, description="마스킹 범위")
    exp_quality: int = Field(default=0, description="내보내기 품질")
    exp_format: int = Field(default=1, description="내보내기 포맷")
    is_img: int = Field(default=0, description="동영상0/이미지폴더1")


class ProjectCreateResponse(BaseModel):
    """POST /project 응답."""

    result: str
    prj_id: int


class DeleteProjectNameRequest(BaseModel):
    """POST /delete_project_name 요청."""

    model_config = ConfigDict(extra="ignore")

    project_name: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)


class DeleteProjectIdRequest(BaseModel):
    """POST /delete_project_id 요청."""

    model_config = ConfigDict(extra="ignore")

    project_id: int = Field(...)
    user_id: str = Field(..., min_length=1)


class DeleteResponse(BaseModel):
    """삭제 응답."""

    result: str
    message: str
