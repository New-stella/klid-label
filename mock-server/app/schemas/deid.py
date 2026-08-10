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
    files: list[str] = Field(
        default_factory=list,
        max_length=1000,
        description="영상모드 대상 파일 목록(최대 1000개 — 자원 상한, CWE-400/CWE-770)",
    )
    masking_type: int = Field(default=0, description="색상0/모자이크2/블러3")
    db_save: int = Field(default=0, ge=0, le=1, description="0=미저장 1=DB저장")
    # ★ 실수다 — 정수가 아니다. 마스킹 영역 "배율"(벤더 확인: 0.5~2.0)이라 int 로 두면
    #   저작도구가 보낸 0.5·1.5 가 전부 422 로 죽어 로컬·dev 파이프라인이 한 건도 완주하지 못한다.
    #   (framerate 상한 le=240 과 같은 실패 방식 — 목서버 스키마가 의미를 오해해 정상 입력을 막는 것.)
    #   상한·하한은 두지 않는다 — 판정의 단일 원천은 저작도구 설정 검증(ConfigKeys.DECIMAL_RANGE)이며
    #   목서버에 사본을 두면 두 번째 진실원이 된다.
    masking_range: float = Field(default=1.0, description="마스킹 영역 배율(실수)")
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
