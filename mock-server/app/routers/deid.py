"""
KPST 비식별화 벤더 목 라우터 — 11개 엔드포인트(상태ful 시뮬레이션).

KPST 규격은 루트 경로(``/project`` 등)를 쓰므로 main.py 에서 prefix 없이 등록한다.
진행률은 저장하지 않고 조회 시점의 경과초로 계산한다(app.state.elapsed_progress).

보안:
- 입력 검증(CWE-20): POST 바디는 pydantic 모델, 쿼리는 타입 힌트로 검증.
- 로그 인젝션(CWE-117): 사용자 입력은 sanitize_for_log 경유 후 로깅.
- 정보 노출(CWE-209): 오류는 공통 핸들러가 규격 응답으로 변환(스택트레이스 미노출).
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Optional

from fastapi import APIRouter, Query, Request, status
from fastapi.responses import PlainTextResponse
from starlette.concurrency import run_in_threadpool

from app.config import get_settings
from app.exceptions import MockApiError
from app.schemas.deid import (
    DeleteProjectIdRequest,
    DeleteProjectNameRequest,
    DeleteResponse,
    ProjectCreateRequest,
    ProjectCreateResponse,
)
from app.services import deid_sim
from app.state import (
    DuplicateProjectError,
    Project,
    get_store,
    sanitize_for_log,
)

router = APIRouter()
logger = logging.getLogger(__name__)

_DATE_FORMAT = "%Y-%m-%d"


# ── 공통 헬퍼 ────────────────────────────────────────────────────
async def _load_json_body(request: Request) -> dict[str, Any]:
    """GET+JSON 바디를 수동 파싱한다(우리 BE 의 Content-Length 프레이밍 수용).

    FastAPI 는 GET 바디를 자동 바인딩하지 않으므로 raw body 를 직접 읽어 파싱한다.
    """
    raw = await request.body()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        raise MockApiError(status.HTTP_400_BAD_REQUEST, "Invalid JSON body", "VALIDATION_ERROR")
    if not isinstance(parsed, dict):
        raise MockApiError(status.HTTP_400_BAD_REQUEST, "Invalid JSON body", "VALIDATION_ERROR")
    return parsed


def _project_rate(project: Project) -> float:
    """프로젝트의 현재 진행률(0~100)."""
    snapshot = get_store().progress_of(project.prj_id)
    return snapshot.progress_rate if snapshot else 0.0


def _dataset_total_frame(dataset: Any) -> int:
    """데이터셋의 총 프레임 수(생성 시 meta 에 저장)."""
    return int(dataset.meta.get("total_frame", deid_sim.total_frame_for(dataset.dataset_id)))


def _build_ds_status(project: Project, rate: float) -> list[dict[str, Any]]:
    """프로젝트의 데이터셋별 상태 리스트.

    ``fileName`` 은 실서버 계약대로 **원본 입력파일 경로**(데이터셋명)를 그대로 돌려준다 —
    산출물명이 아니다(산출물은 export_path 의 ``{stem}-mask{ext}``).
    """
    proc_state = deid_sim.proc_state_from_rate(rate)
    start_time = deid_sim.format_dt(project.created_epoch)
    end_time = start_time if rate >= 100.0 else None
    result: list[dict[str, Any]] = []
    for ds in get_store().datasets_of(project.prj_id):
        result.append(
            {
                "dsId": ds.dataset_id,
                "fileName": ds.name,
                "procState": proc_state,
                "progressRate": rate,
                "totalFrame": _dataset_total_frame(ds),
                "startTime": start_time,
                "endTime": end_time,
            }
        )
    return result


# ── 1. GET / ─────────────────────────────────────────────────────
@router.get("/", response_class=PlainTextResponse)
async def connect() -> str:
    """헬스/연결 확인 — KPST 규격의 루트 접속 응답."""
    return "Connect"


# ── 2. POST /project ─────────────────────────────────────────────
@router.post("/project", response_model=ProjectCreateResponse)
async def create_project(req: ProjectCreateRequest) -> ProjectCreateResponse:
    """프로젝트 생성 + 작업 등록(상태ful 시뮬레이션 시작)."""
    # 영상 모드(is_img=0)는 files 필수, 이미지 폴더 모드(is_img=1)는 폴더 1개가 데이터셋
    if req.is_img == 0 and not req.files:
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST,
            "files is required for video mode",
            "VALIDATION_ERROR",
        )

    store = get_store()
    try:
        project = store.create_project(
            req.project_name,
            creator=req.creator,
            export_path=req.export_path,
            input_path=req.input_path,
            is_img=req.is_img,
            db_save=req.db_save,
            masking_type=req.masking_type,
        )
    except DuplicateProjectError:
        raise MockApiError(
            status.HTTP_409_CONFLICT,
            f"Project '{req.project_name}' already exists",
            "CONFLICT",
        )

    # 데이터셋 구성 — 영상 모드는 파일당 1개, 이미지 폴더 모드는 폴더 1개.
    #
    # 실서버 계약(2026-07-21 curl/ll 실측)을 그대로 따른다:
    #   - 데이터셋명 = 진행/리포트 응답의 fileName = **원본 입력파일 경로**(input_path + 원본 basename)
    #   - 실제 산출물 = {export_path}/{원본stem}-mask{ext}
    # 즉 fileName 과 산출물명은 <다르다>. 구 목업은 둘을 같은 마스킹명("단일 소스")으로 두었는데,
    # 그러면 BE 의 1차 회수 경로(fileName basename → {stem}-mask{ext})가 항상 빗나가 폴백 스캔으로만
    # 회수돼 정상 경로가 로컬에서 한 번도 검증되지 않았다(B-ISSUE-84).
    # totalFrame 은 dataset_id 로 결정적으로 도출(_dataset_total_frame)하므로 별도 저장 불필요.
    raw_names = req.files if req.is_img == 0 else [req.input_path]
    plans = deid_sim.plan_outputs(raw_names)
    for source_base, _mask_name in plans:
        store.add_dataset(
            project.prj_id, deid_sim.source_path_of(req.input_path, source_base)
        )

    store.append_job_log(project.prj_id, "project_created", req.project_name)
    logger.info(
        "[MOCK][KPST] project created prj_id=%d name=%s creator=%s",
        project.prj_id,
        sanitize_for_log(req.project_name),
        sanitize_for_log(req.creator),
    )

    # 더미 비식별 출력 파일 생성 — BE 무결성(존재+크기>0) 통과용. 실패해도 응답에 영향 없음.
    # 동기 파일 I/O 는 threadpool 로 오프로드해 이벤트 루프 블로킹을 피한다.
    settings = get_settings()
    if settings.write_output_files:
        try:
            await run_in_threadpool(
                deid_sim.write_deid_outputs,
                export_path=req.export_path,
                input_path=req.input_path,
                outputs=plans,
                output_base=settings.output_base,
                # 인증 없는 목이라 input_path 도 임의 지정이 가능하다 — 허용 루트 밖 원본은
                # 복사하지 않고 placeholder 로 대체한다(임의 파일 노출·디스크 고갈 차단).
                input_base=settings.effective_input_base(),
            )
        except Exception as exc:  # noqa: BLE001 — 목 안정성 우선, 어떤 실패도 200 유지
            logger.warning(
                "[MOCK][KPST] deid output generation error prj_id=%d err=%s",
                project.prj_id,
                sanitize_for_log(str(exc)),
            )

    return ProjectCreateResponse(result="success", prj_id=project.prj_id)


# ── 3. POST /delete_project_name ─────────────────────────────────
@router.post("/delete_project_name", response_model=DeleteResponse)
async def delete_project_name(req: DeleteProjectNameRequest) -> DeleteResponse:
    """이름으로 프로젝트 삭제(미존재도 관대하게 성공 처리)."""
    get_store().delete_project_by_name(req.project_name)
    logger.info(
        "[MOCK][KPST] project deleted by name=%s", sanitize_for_log(req.project_name)
    )
    return DeleteResponse(
        result="success", message=f"Project '{req.project_name}' deleted successfully"
    )


# ── 4. POST /delete_project_id ───────────────────────────────────
@router.post("/delete_project_id", response_model=DeleteResponse)
async def delete_project_id(req: DeleteProjectIdRequest) -> DeleteResponse:
    """ID 로 프로젝트 삭제(미존재도 관대하게 성공 처리)."""
    get_store().delete_project(req.project_id)
    logger.info("[MOCK][KPST] project deleted by id=%d", req.project_id)
    return DeleteResponse(
        result="success", message=f"Project '{req.project_id}' deleted successfully"
    )


# ── 5. GET /retrieve_progress (GET + JSON 바디) ──────────────────
@router.get("/retrieve_progress")
async def retrieve_progress(request: Request) -> dict[str, Any]:
    """진행 상황 조회 — GET 바디 JSON 필터를 수동 파싱."""
    body = await _load_json_body(request)

    req_user_id = body.get("reqUserId")
    if not req_user_id or not isinstance(req_user_id, str):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "reqUserId is required", "VALIDATION_ERROR"
        )

    user_id = body.get("userId")
    prj_name = body.get("prjName")
    prj_id = body.get("prjId")
    start_date = body.get("startDate")
    end_date = body.get("endDate")

    if not any(v is not None for v in (user_id, prj_name, prj_id, start_date, end_date)):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST,
            "At least one filter parameter is required",
            "VALIDATION_ERROR",
        )

    projects = _filter_projects(user_id, prj_name, prj_id, start_date, end_date)
    if not projects:
        raise MockApiError(status.HTTP_404_NOT_FOUND, "No projects found", "NOT_FOUND")

    prj_status: list[dict[str, Any]] = []
    for project in projects:
        rate = _project_rate(project)
        create_time = deid_sim.format_dt(project.created_epoch)
        prj_status.append(
            {
                "prjId": project.prj_id,
                "prjName": project.project_name,
                "progressRate": rate,
                "prjState": deid_sim.prj_state_from_rate(rate),
                "createTime": create_time,
                "startTime": create_time,
                "endTime": create_time if rate >= 100.0 else None,
                "createId": project.creator,
                "exportPath": project.export_path,
                "dsCount": len(project.dataset_ids),
                "dsStatus": _build_ds_status(project, rate),
            }
        )

    return {"result": "success", "data": {"prjCount": len(prj_status), "prjStatus": prj_status}}


# ── 6. GET /retrieve_report (GET + JSON 바디) ────────────────────
@router.get("/retrieve_report")
async def retrieve_report(request: Request) -> dict[str, Any]:
    """처리 완료(procState=2) 데이터셋만 리포트.

    ``fileName`` 은 진행조회와 동일하게 **원본 입력파일 경로**다(실서버 계약).
    """
    body = await _load_json_body(request)

    req_user_id = body.get("reqUserId")
    if not req_user_id or not isinstance(req_user_id, str):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "reqUserId is required", "VALIDATION_ERROR"
        )

    user_id = body.get("userId")
    prj_id = body.get("prjId")
    start_date = body.get("startDate")
    end_date = body.get("endDate")

    projects = _filter_projects(user_id, None, prj_id, start_date, end_date)

    prj_status: list[dict[str, Any]] = []
    for project in projects:
        rate = _project_rate(project)
        if rate < 100.0:
            continue  # 완료 데이터셋 없는 프로젝트 제외
        ds_status: list[dict[str, Any]] = []
        for ds in get_store().datasets_of(project.prj_id):
            total_frame = _dataset_total_frame(ds)
            ds_status.append(
                {
                    "dsId": ds.dataset_id,
                    "fileName": ds.name,
                    "faceCount": deid_sim.face_count_for(ds.dataset_id, total_frame),
                    "lpCount": deid_sim.lp_count_for(ds.dataset_id, total_frame),
                    "totalFrame": total_frame,
                    "startTime": deid_sim.format_dt(project.created_epoch),
                    "endTime": deid_sim.format_dt(project.created_epoch),
                }
            )
        if ds_status:
            prj_status.append({"progressRate": rate, "dsStatus": ds_status})

    return {"result": "success", "data": {"prjCount": len(prj_status), "prjStatus": prj_status}}


# ── 7. GET /manual_deid_info ─────────────────────────────────────
@router.get("/manual_deid_info")
async def manual_deid_info() -> dict[str, Any]:
    """수동 비식별화 대상(db_save=1) 프로젝트 정보."""
    targets = _manual_targets()
    if not targets:
        raise MockApiError(
            status.HTTP_404_NOT_FOUND, "No matching records found", "NOT_FOUND"
        )

    data: list[dict[str, Any]] = []
    for project in targets:
        datasets = [
            {
                "dataset_id": ds.dataset_id,
                "file_name": ds.name,
                "masking_table_name": f"masking_data_{ds.dataset_id}",
                "masked_frame_table_name": f"masked_frame_{ds.dataset_id}",
            }
            for ds in get_store().datasets_of(project.prj_id)
        ]
        data.append(
            {
                "project_id": project.prj_id,
                "project_name": project.project_name,
                "creator_id": project.creator,
                "datasets": datasets,
            }
        )
    return {"result": "success", "data": data}


# ── 8. GET /manual_deid_info/project_id ──────────────────────────
@router.get("/manual_deid_info/project_id")
async def manual_deid_info_project_id() -> dict[str, Any]:
    """수동 대상 프로젝트 ID 목록."""
    return {"result": "success", "ids": [p.prj_id for p in _manual_targets()]}


# ── 9. GET /manual_deid_info/project_name ────────────────────────
@router.get("/manual_deid_info/project_name")
async def manual_deid_info_project_name() -> dict[str, Any]:
    """수동 대상 프로젝트 이름 목록."""
    return {"result": "success", "names": [p.project_name for p in _manual_targets()]}


# ── 10. GET /dataset_frames ──────────────────────────────────────
@router.get("/dataset_frames")
async def dataset_frames(
    dataset_id: int = Query(..., description="데이터셋 ID(필수)"),
    frame_no: Optional[str] = Query(default=None, description="단일 '120' 또는 범위 '100-200'"),
) -> dict[str, Any]:
    """마스킹된 프레임 이미지(base64) + bbox 조회."""
    dataset = get_store().get_dataset(dataset_id)
    if dataset is None:
        raise MockApiError(
            status.HTTP_404_NOT_FOUND, "No matching frames found", "NOT_FOUND"
        )

    total_frame = _dataset_total_frame(dataset)
    try:
        frames = deid_sim.build_frames(dataset_id, total_frame, frame_no)
    except ValueError:
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "frame_no is invalid", "VALIDATION_ERROR"
        )
    if not frames:
        raise MockApiError(
            status.HTTP_404_NOT_FOUND, "No matching frames found", "NOT_FOUND"
        )
    return {"result": "success", "data": frames}


# ── 11. GET /retrieve_job_logs ───────────────────────────────────
@router.get("/retrieve_job_logs")
async def retrieve_job_logs(
    user_id: Optional[str] = Query(default=None),
    prj_name: Optional[str] = Query(default=None),
    start_time: Optional[str] = Query(default=None),
    end_time: Optional[str] = Query(default=None),
) -> dict[str, Any]:
    """작업 로그 조회 — 시간 조건은 start_time·end_time 둘 다 있어야 적용."""
    time_range: Optional[tuple[datetime, datetime]] = None
    if start_time and end_time:
        try:
            start_dt = deid_sim.parse_log_time(start_time)
            end_dt = deid_sim.parse_log_time(end_time)
        except ValueError:
            raise MockApiError(
                status.HTTP_400_BAD_REQUEST, "Invalid time format", "VALIDATION_ERROR"
            )
        time_range = (start_dt, end_dt)

    logs: list[dict[str, Any]] = []
    for project in get_store().list_projects():
        if user_id and project.creator != user_id:
            continue
        if prj_name and prj_name not in project.project_name:
            continue
        if time_range is not None:
            created = datetime.fromtimestamp(project.created_epoch)
            if not (time_range[0] <= created <= time_range[1]):
                continue

        rate = _project_rate(project)
        proc_state = deid_sim.proc_state_from_rate(rate)
        message = "completed" if proc_state == deid_sim.PROC_STATE_DONE else "processing"
        for ds in get_store().datasets_of(project.prj_id):
            logs.append(
                {
                    "user_id": project.creator,
                    "prj_name": project.project_name,
                    "dataset_message": f"{ds.name} {message}",
                    "state": proc_state,
                    "export_path": project.export_path,
                    "time": deid_sim.format_dt(project.created_epoch),
                }
            )

    if not logs:
        raise MockApiError(
            status.HTTP_404_NOT_FOUND, "No matching records found", "NOT_FOUND"
        )
    return {"result": "success", "data": logs}


# ── 내부 필터 헬퍼 ───────────────────────────────────────────────
def _filter_projects(
    user_id: Optional[str],
    prj_name: Optional[str],
    prj_id: Optional[int],
    start_date: Optional[str],
    end_date: Optional[str],
) -> list[Project]:
    """조회 필터를 적용해 프로젝트 목록을 반환한다."""
    projects = get_store().list_projects()

    if prj_id is not None:
        projects = [p for p in projects if p.prj_id == prj_id]
    if prj_name:
        projects = [p for p in projects if prj_name in p.project_name]
    if user_id:
        projects = [p for p in projects if p.creator == user_id]

    if start_date:
        start_dt = _parse_date_or_400(start_date)
        projects = [
            p for p in projects if datetime.fromtimestamp(p.created_epoch) >= start_dt
        ]
    if end_date:
        end_dt = _parse_date_or_400(end_date)
        end_of_day = end_dt.replace(hour=23, minute=59, second=59)
        projects = [
            p for p in projects if datetime.fromtimestamp(p.created_epoch) <= end_of_day
        ]
    return projects


def _parse_date_or_400(value: str) -> datetime:
    try:
        return datetime.strptime(value.strip(), _DATE_FORMAT)
    except (ValueError, AttributeError):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "Invalid date format", "VALIDATION_ERROR"
        )


def _manual_targets() -> list[Project]:
    """수동 비식별화 대상(db_save=1) 프로젝트 목록."""
    return [p for p in get_store().list_projects() if p.db_save == 1]
