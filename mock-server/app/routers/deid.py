"""
KPST 비식별화 벤더 목 라우터 — 11개 엔드포인트(상태ful 시뮬레이션).

KPST 규격은 루트 경로(``/project`` 등)를 쓰므로 main.py 에서 prefix 없이 등록한다.
진행률은 저장하지 않고 조회 시점의 경과초로 계산한다(app.state.elapsed_progress).

★ 구속 원칙 — <b>외부연동은 모두 비동기다</b>. ``POST /project`` 는 <b>접수만</b> 하고 즉시
반환하며, 실제 산출(ffmpeg 인코딩·파일 복사·ffprobe)은 ``deid_sim.spawn_production`` 이 띄운
백그라운드 태스크가 수행한다. 실제 KPST 도 같은 모델이며(우리 BE 의 ``KpstDeidentPollJob`` 이
``retrieve_progress`` 를 폴링해 완료를 감지), 요청 안에서 산출을 마치면 BE 클라이언트 타임아웃
(45초) 압박 때문에 ①산출물 절단 ②위탁 실패 + 409 영구차단 중 하나로 반드시 귀결된다.
⛔ 이 엔드포인트에서 산출 완료를 ``await`` 하도록 되돌리지 말 것.

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
    ProgressSnapshot,
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


def _project_progress(project: Project) -> ProgressSnapshot:
    """프로젝트의 현재 진행 스냅샷(경과초 축 + 백그라운드 산출 축 합산).

    완료 판정은 저장소(``progress_of`` → ``combined_progress``) 한 곳에서만 내린다.
    """
    snapshot = get_store().progress_of(project.prj_id)
    return snapshot if snapshot else ProgressSnapshot(progress_rate=0.0, state="PENDING")


def _dataset_total_frame(dataset: Any) -> int:
    """데이터셋의 총 프레임 수(생성 시 meta 에 저장)."""
    return int(dataset.meta.get("total_frame", deid_sim.total_frame_for(dataset.dataset_id)))


def _build_ds_status(project: Project, snapshot: ProgressSnapshot) -> list[dict[str, Any]]:
    """프로젝트의 데이터셋별 상태 리스트.

    ``fileName`` 은 실서버 계약대로 **원본 입력파일 경로**(데이터셋명)를 그대로 돌려준다 —
    산출물명이 아니다(산출물은 export_path 의 ``{stem}-mask{ext}``).

    ``procState`` 는 <b>산출 축까지 반영</b>한다 — 백그라운드 산출이 끝나기 전에는 완료(2)를
    보고하지 않고, 산출이 실패하면 오류 sentinel(99)을 보고한다.
    """
    rate = snapshot.progress_rate
    proc_state = deid_sim.proc_state_for(snapshot)
    start_time = deid_sim.format_dt(project.created_epoch)
    end_time = start_time if proc_state == deid_sim.PROC_STATE_DONE else None
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
    """프로젝트 생성 + 작업 <b>접수</b> — 산출 완료를 기다리지 않고 즉시 반환한다.

    ★ 구속 원칙(외부연동 비동기). 이 핸들러가 하는 일은 인메모리 등록(프로젝트/데이터셋/로그)과
    산출 태스크 기동뿐이다. 실제 산출은 백그라운드에서 진행되며 그 결과는
    ``retrieve_progress`` 의 ``procState``(1 진행중 / 2 완료 / 99 오류)로 드러난다.
    """
    # 영상 모드(is_img=0)는 files 필수, 이미지 폴더 모드(is_img=1)는 폴더 1개가 데이터셋
    if req.is_img == 0 and not req.files:
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST,
            "files is required for video mode",
            "VALIDATION_ERROR",
        )

    # MEDIUM-3 — 미완료 산출 태스크 총량 상한. 프로젝트를 만들기 <b>전에</b> 거부해야
    # projectName 이 점유되지 않는다(점유 후 거부하면 같은 rawSn 의 재위탁이 409 로 영구
    # 차단된다).
    #
    # ★ #2 — 503 의 효과를 과장하지 않는다(구 주석 "BE 가 그대로 재시도할 수 있는 실패" 는
    #   <b>거짓 전제</b>였다). 우리 BE 실제 동작(2026-07-30 코드 실측):
    #     · ``KpstDeidentifyClient`` 는 5xx 를 resilience4j ``kpstDeid`` retry 로만 재시도한다
    #       — max-attempts 3 · wait 1s · multiplier 2 ⇒ <b>총 대기 약 3초</b>.
    #     · 소진되면 예외가 ``KpstDeidentService.submit`` 의 catch 로 올라가
    #       ``procLog.fail(EXTERNAL_API_ERROR)`` + ``markDeidentified("F")`` 로 <b>종결</b>한다.
    #     · <b>자동 재위탁 큐는 없다</b>(클래스 주석: "자동 재비식별 큐 신설 없음, 외부 수동").
    #       재시도는 운영자가 배치 재처리를 태울 때만 일어난다.
    #   즉 503 이 잦으면 부하 3초 안에 정상 영상이 수동복구 대상 'F' 로 확정된다. 큐가 비는
    #   시간(수 분~수십 분)과 BE 재시도 창(3초)은 자릿수가 다르다.
    #
    #   그래서 503 은 "무제한 누적 방지"라는 최소 방어로만 유지하고(없애면 큐가 무한히 쌓여
    #   더 나쁘다), <b>실질 완화는 접수 상한을 배출률에서 파생시켜 낮춘 것</b>이다
    #   (``deid_sim.PRODUCTION_MAX_INFLIGHT`` — 거부되지 않은 건은 BE 폴링 예산 안에 끝난다).
    #   상한이 배출률과 정합하면 503 도달 자체가 드물어진다.
    #   ⚠ 다음 라운드가 이 주석 위에 "BE 가 알아서 재시도한다"를 다시 쌓지 말 것.
    if not deid_sim.production_capacity_available():
        logger.warning(
            "[MOCK][KPST] project rejected — 미완료 산출 태스크 상한(%d) 초과 name=%s",
            deid_sim.PRODUCTION_MAX_INFLIGHT,
            sanitize_for_log(req.project_name),
        )
        raise MockApiError(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Too many in-flight deidentify jobs; retry later",
            "SERVICE_BUSY",
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
    plans, dropped = deid_sim.plan_outputs_detailed(raw_names)
    if dropped:
        # #4 — 정화 실패 항목을 <b>조용히</b> 버리지 않는다. 전부 버려지면(계획 0건) 데이터셋이
        # 없는 프로젝트가 되고, BE 는 firstDataset=null 이라 완료를 인지하지 못한 채 폴링 예산을
        # 소진해 'F' 로 끝난다. 그래서 아래 spawn_production 에 <b>요청 항목 수</b>를 넘겨
        # 산출 단계가 실패(procState=99)로 종결하게 한다.
        logger.warning(
            "[MOCK][KPST] deid 요청 항목 중 %d/%d 건이 파일명 정화 실패로 제외됨 prj_id=%d",
            len(dropped),
            len(raw_names),
            project.prj_id,
        )
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

    # ★ 산출은 <b>백그라운드</b>에서 — 여기서 완료를 기다리지 않는다(구속 원칙).
    #   ⛔ ``await run_in_threadpool(deid_sim.write_deid_outputs, ...)`` 로 되돌리지 말 것.
    #      요청 안에서 인코딩을 마치려면 BE 타임아웃(45초)에 맞춰야 하고, 그러면 산출물을 잘라
    #      끝내거나(학습데이터 오염) 타임아웃으로 위탁이 실패한다(같은 projectName 재요청은
    #      409 라 영구 차단). 산출 진행/실패는 retrieve_progress 의 procState 로 노출된다.
    settings = get_settings()
    deid_sim.spawn_production(
        project.prj_id,
        export_path=req.export_path,
        input_path=req.input_path,
        outputs=plans,
        # #4 — 계획(plans)이 아니라 <b>요청 항목 수</b>를 넘긴다. 계획이 0건이어도 요청이
        # 있었다면 "산출물 0건 = 성공" 이 되면 안 된다.
        requested=len(raw_names),
        output_base=settings.output_base,
        # 인증 없는 목이라 input_path 도 임의 지정이 가능하다 — 허용 루트 밖 원본은 읽지 않고
        # 그 산출을 실패(procState=99)로 종결한다(임의 파일 노출·디스크 고갈 차단). 대체 산출물을
        # 남기는 안은 폐기 — 읽지도 못한 원본을 '비식별 완료'로 승격시키는 위장 산출물(CWE-345).
        input_base=settings.effective_input_base(),
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
        snapshot = _project_progress(project)
        rate = snapshot.progress_rate
        create_time = deid_sim.format_dt(project.created_epoch)
        prj_status.append(
            {
                "prjId": project.prj_id,
                "prjName": project.project_name,
                "progressRate": rate,
                "prjState": deid_sim.prj_state_for(snapshot),
                "createTime": create_time,
                "startTime": create_time,
                "endTime": create_time if rate >= 100.0 else None,
                "createId": project.creator,
                "exportPath": project.export_path,
                "dsCount": len(project.dataset_ids),
                "dsStatus": _build_ds_status(project, snapshot),
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
        rate = _project_progress(project).progress_rate
        if rate < 100.0:
            continue  # 완료 데이터셋 없는 프로젝트 제외(산출 미완/실패 포함)
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

        proc_state = deid_sim.proc_state_for(_project_progress(project))
        if proc_state == deid_sim.PROC_STATE_DONE:
            message = "completed"
        elif proc_state == deid_sim.PROC_STATE_ERROR:
            message = "failed"
        else:
            message = "processing"
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
