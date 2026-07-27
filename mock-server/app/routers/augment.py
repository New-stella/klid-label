"""
생성형 AI(증강) 벤더 목 라우터 — 「생성형 AI API 연동명세서 v1.1」 정합.

목이 **제공**하는 4종 (prefix ``/api/genai``):
- ① POST /api/genai/jobs                     : 작업 요청 → 202 RECEIVED
- ④ GET  /api/genai/jobs/{job_id}            : 상태 조회
- ⑤ GET  /api/genai/jobs/{job_id}/results    : 결과 조회(SUCCEEDED 에서만)
- ⑥ POST /api/genai/jobs/{job_id}/cancel     : 취소(RECEIVED·RUNNING 에서만)

목이 **발신**하는 2종:
- ② 진행·결과 Webhook : 요청 바디의 ``callback_url`` 로 단계마다 POST (자동)
- ③ 상태 동기화       : 목 전용 **수동 트리거**(아래 _mock EP). 대상 base 는 환경변수
  ``MOCK_GENAI_STATUS_SYNC_URL`` 이며 미설정 시 비활성 — 목이 수신측 주소를 유추하지 않는다.

목 전용 보조 EP (``/api/genai/_mock/*``) — **명세서에 없는 목 서버 전용 기능**:
- GET  /api/genai/_mock/jobs                        : 작업 목록/상태 확인
- POST /api/genai/_mock/reset                       : 진행 태스크 취소 + 작업 저장소 초기화
- POST /api/genai/_mock/jobs/{job_id}/status-sync   : ③ 상태 동기화 수동 발신

인증(§ 401 UNAUTHENTICATED / 403 FORBIDDEN)은 **이번 스코프에서 의도적으로 미구현**이다.
``Idempotency-Key`` 헤더는 선택 — 동일 키 재요청은 기존 작업을 그대로 반환한다.

보안:
- 입력 검증(CWE-20/915): pydantic 모델로 타입·필수·길이·enum 검증, 미선언 필드 무시.
- 경로 탈출(CWE-22): input_files[].file_path 는 절대경로 + 입력 base 하위만 허용
  (base 미설정이면 fail-closed).
- SSRF(CWE-918): callback_url 은 http|https + ``host[:port]`` allowlist + (선택) 경로 접두사
  통과분만 접수하고, 목 서버 자신을 가리키는 대상은 거부한다.
- 자원 고갈(CWE-770/400): 요청 본문은 ``MOCK_GENAI_MAX_BODY_BYTES`` 안에서만 읽고(초과 413),
  prompt 직렬화 크기와 인메모리 잡 보관 수에도 상한이 있다.
- 예외 처리(CWE-755): 심층 중첩 JSON 등 파싱 실패는 모두 400 으로 매핑한다(500 금지).
- 정보 노출(CWE-209): 오류 메시지는 고정 문구, 스택트레이스/내부 경로 미노출.
"""

from __future__ import annotations

import json
import logging
import uuid
from typing import Any, Type, TypeVar

from fastapi import APIRouter, Request, status
from pydantic import BaseModel, ValidationError

from app.config import Settings, get_settings
from app.exceptions import GenAiApiError
from app.schemas.genai import (
    INPUT_REQUIRED_MODES,
    CancelRequest,
    ErrorCode,
    JobAcceptedResponse,
    JobCancelResponse,
    JobResultsResponse,
    JobStatus,
    JobStatusResponse,
    JobSubmitRequest,
)
from app.services import genai_sim
from app.state import GenAiJob, sanitize_for_log

router = APIRouter(prefix="/api/genai")
logger = logging.getLogger(__name__)

_ModelT = TypeVar("_ModelT", bound=BaseModel)

# Idempotency-Key 헤더 — 명세서상 string(64) 선택 항목.
_IDEMPOTENCY_HEADER = "Idempotency-Key"
_IDEMPOTENCY_MAX_LEN = 64


# ── 요청 파싱/검증 ────────────────────────────────────────────────
def _too_large() -> GenAiApiError:
    """413 — starlette 버전 간 상수명 차이를 피해 정수 리터럴 사용."""
    return GenAiApiError(
        413, ErrorCode.MEDIA_TOO_LARGE.value, "요청 본문 크기가 허용 한도를 초과했습니다"
    )


async def _read_body_limited(request: Request, limit: int) -> bytes:
    """요청 본문을 상한 안에서만 읽는다(CWE-770).

    ``Content-Length`` 를 먼저 보고, 헤더가 없거나 거짓일 수 있으므로 스트리밍 누적분도
    함께 검사한다. 상한 초과분은 전량 메모리에 올리기 전에 413 으로 끊는다.
    """
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > limit:
                raise _too_large()
        except ValueError:
            raise GenAiApiError(
                status.HTTP_400_BAD_REQUEST,
                ErrorCode.INVALID_PARAMETER.value,
                "Content-Length 헤더가 유효하지 않습니다",
            )
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            raise _too_large()
        chunks.append(chunk)
    return b"".join(chunks)


async def _load_json_object(request: Request) -> dict[str, Any]:
    """요청 본문을 JSON 객체로 파싱한다. 비어 있으면 빈 dict(필수 검증은 모델이 수행)."""
    raw = await _read_body_limited(request, get_settings().genai_max_body_bytes)
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except Exception:
        # RecursionError(심층 중첩) 등 JSONDecodeError 밖의 파싱 실패도 400 으로 매핑한다(F-10).
        raise GenAiApiError(
            status.HTTP_400_BAD_REQUEST,
            ErrorCode.INVALID_PARAMETER.value,
            "요청 본문이 유효한 JSON 이 아닙니다",
        )
    if not isinstance(parsed, dict):
        raise GenAiApiError(
            status.HTTP_400_BAD_REQUEST,
            ErrorCode.INVALID_PARAMETER.value,
            "요청 본문은 JSON 객체여야 합니다",
        )
    return parsed


def _validate(model_cls: Type[_ModelT], payload: dict[str, Any]) -> _ModelT:
    """payload 를 모델로 검증하고, 실패 시 명세서 400 오류코드로 변환한다."""
    try:
        return model_cls.model_validate(payload)
    except ValidationError as exc:
        raise GenAiApiError(
            status.HTTP_400_BAD_REQUEST, _error_code_of(exc), _first_message(exc)
        )


def _error_code_of(exc: ValidationError) -> str:
    """검증 오류 → 명세서 §3.3 400 코드 매핑."""
    errors = exc.errors()
    if any(err.get("type") == "missing" for err in errors):
        return ErrorCode.REQUIRED_FIELD_MISSING.value
    if any((err.get("loc") or ("",))[0] == "prompt" for err in errors):
        return ErrorCode.INVALID_METADATA.value
    return ErrorCode.INVALID_PARAMETER.value


def _first_message(exc: ValidationError) -> str:
    """첫 검증 오류를 사용자 노출용 한 줄 메시지로 만든다(내부 정보 미포함)."""
    errors = exc.errors()
    if not errors:
        return "요청 값이 유효하지 않습니다"
    first = errors[0]
    field = ".".join(str(part) for part in first.get("loc", ()))
    return f"{field}: {first.get('msg', 'invalid value')}" if field else str(first.get("msg"))


def _bad_request(code: ErrorCode, message: str) -> GenAiApiError:
    return GenAiApiError(status.HTTP_400_BAD_REQUEST, code.value, message)


def _idempotency_key(request: Request) -> str | None:
    """Idempotency-Key 헤더를 읽고 길이(64)를 검증한다."""
    key = request.headers.get(_IDEMPOTENCY_HEADER)
    if key is None:
        return None
    key = key.strip()
    if not key:
        return None
    if len(key) > _IDEMPOTENCY_MAX_LEN:
        raise _bad_request(
            ErrorCode.INVALID_PARAMETER, f"{_IDEMPOTENCY_HEADER} 는 64자 이하여야 합니다"
        )
    return key


def _check_event_type(evnt_type: str, settings: Settings) -> None:
    """허용 evnt_type 목록이 설정된 경우에만 화이트리스트를 강제한다."""
    allowed = settings.genai_event_types_set()
    if allowed and evnt_type not in allowed:
        raise _bad_request(
            ErrorCode.UNSUPPORTED_EVENT_TYPE, "지원하지 않는 이벤트 유형입니다"
        )


def _check_callback_url(callback_url: str | None, settings: Settings) -> None:
    """SSRF(CWE-918) — 스킴 + ``host[:port]`` allowlist + 경로 접두사 + 자기참조 차단."""
    if not callback_url:
        return
    allowed = genai_sim.is_allowed_url(
        callback_url,
        settings.genai_callback_allow_hosts_list(),
        path_prefixes=settings.genai_callback_path_prefixes_list(),
    )
    if not allowed:
        logger.warning(
            "[MOCK][GENAI] callback_url rejected(url guard) url=%s",
            sanitize_for_log(callback_url),
        )
        raise _bad_request(
            ErrorCode.INVALID_PARAMETER,
            "callback_url 이 허용되지 않습니다"
            "(스킴 http|https + host:port 허용목록 + 경로 접두사, 목 자신은 금지)",
        )


def _check_prompt(prompt: dict[str, Any], settings: Settings) -> None:
    """prompt 직렬화 크기 상한(CWE-770) — 초과 시 400 INVALID_METADATA."""
    try:
        size = len(json.dumps(prompt, ensure_ascii=False).encode("utf-8"))
    except (TypeError, ValueError, RecursionError):
        raise _bad_request(ErrorCode.INVALID_METADATA, "prompt 를 해석할 수 없습니다")
    if size > settings.genai_max_prompt_bytes:
        raise _bad_request(
            ErrorCode.INVALID_METADATA, "prompt 크기가 허용 한도를 초과했습니다"
        )


def _resolve_input_files(req: JobSubmitRequest, settings: Settings) -> list[tuple[int, str]]:
    """입력 파일 규칙(필수/중복/경로/크기)을 검증하고 (sequence, 절대경로) 목록을 만든다."""
    if req.generation_mode.value in INPUT_REQUIRED_MODES and not req.input_files:
        raise _bad_request(
            ErrorCode.REQUIRED_FIELD_MISSING,
            "input_files 는 I2I·I2V 에서 1건 이상 필요합니다",
        )

    sequences = [f.sequence for f in req.input_files]
    if len(set(sequences)) != len(sequences):
        raise _bad_request(
            ErrorCode.INVALID_PARAMETER, "input_files[].sequence 는 중복될 수 없습니다"
        )

    input_base = settings.genai_effective_input_base()
    resolved_files: list[tuple[int, str]] = []
    for item in req.input_files:
        resolved = genai_sim.resolve_input_path(item.file_path, input_base)
        if resolved is None:
            logger.warning(
                "[MOCK][GENAI] input path rejected(path guard) path=%s",
                sanitize_for_log(item.file_path),
            )
            raise _bad_request(
                ErrorCode.INVALID_PARAMETER,
                "input_files[].file_path 는 허용된 루트의 절대경로여야 합니다",
            )
        # 파일이 존재하는 경우에만 크기 상한을 검사한다(부재는 접수 후 FAILED 로 통보).
        try:
            if resolved.is_file() and resolved.stat().st_size > settings.genai_max_input_bytes:
                # 413 — starlette 버전 간 상수명 차이를 피해 정수 리터럴 사용.
                raise GenAiApiError(
                    413,
                    ErrorCode.MEDIA_TOO_LARGE.value,
                    "입력 파일 크기가 허용 한도를 초과했습니다",
                )
        except OSError:
            pass  # 접근 불가 — 접수는 허용하고 처리 단계에서 FAILED 로 통보
        resolved_files.append((item.sequence, str(resolved)))
    return sorted(resolved_files)


# ── ① POST /api/genai/jobs ───────────────────────────────────────
@router.post("/jobs", status_code=status.HTTP_202_ACCEPTED, response_model=JobAcceptedResponse)
async def submit_job(request: Request) -> JobAcceptedResponse:
    """작업 요청 접수 — 202 로 즉시 수락하고 백그라운드로 진행하며 webhook 을 발사한다."""
    payload = await _load_json_object(request)
    req = _validate(JobSubmitRequest, payload)
    settings = get_settings()

    _check_event_type(req.evnt_type, settings)
    _check_prompt(req.prompt, settings)
    input_files = _resolve_input_files(req, settings)
    _check_callback_url(req.callback_url, settings)
    idempotency_key = _idempotency_key(request)

    job = GenAiJob(
        job_id=uuid.uuid4().hex,
        request_id=req.request_id,
        request_channel=req.request_channel.value,
        request_user_id=req.request_user_id,
        evnt_type=req.evnt_type,
        operation_type=req.operation_type.value,
        generation_mode=req.generation_mode.value,
        callback_url=req.callback_url,
        input_files=input_files,
    )
    stored, created = genai_sim.get_job_store().create_or_get(job, idempotency_key)

    if created:
        genai_sim.spawn_job_task(stored.job_id)
        logger.info(
            "[MOCK][GENAI] job accepted job_id=%s request_id=%s mode=%s inputs=%d",
            sanitize_for_log(stored.job_id),
            sanitize_for_log(stored.request_id),
            sanitize_for_log(stored.generation_mode),
            len(input_files),
        )
    else:
        logger.info(
            "[MOCK][GENAI] idempotent replay job_id=%s key=%s",
            sanitize_for_log(stored.job_id),
            sanitize_for_log(idempotency_key or ""),
        )

    return JobAcceptedResponse(
        request_id=stored.request_id,
        job_id=stored.job_id,
        status=JobStatus(stored.status),
        received_at=stored.received_at,
    )


# ── ④ GET /api/genai/jobs/{job_id} ───────────────────────────────
@router.get("/jobs/{job_id}", response_model=JobStatusResponse)
async def get_job(job_id: str) -> JobStatusResponse:
    """작업 상태 조회."""
    job = _require_job(job_id)
    return JobStatusResponse(
        request_id=job.request_id,
        job_id=job.job_id,
        status=JobStatus(job.status),
        progress=job.progress,
        current_step=job.current_step,
        received_at=job.received_at,
        started_at=job.started_at,
        completed_at=job.completed_at,
        error_code=job.error_code,
        error_message=job.error_message,
        updated_at=job.updated_at,
    )


# ── ⑤ GET /api/genai/jobs/{job_id}/results ───────────────────────
@router.get("/jobs/{job_id}/results", response_model=JobResultsResponse)
async def get_job_results(job_id: str) -> JobResultsResponse:
    """결과 조회 — SUCCEEDED 상태에서만 허용(그 외 409 STATE_CONFLICT)."""
    job = _require_job(job_id)
    if job.status != JobStatus.SUCCEEDED.value:
        raise GenAiApiError(
            status.HTTP_409_CONFLICT,
            ErrorCode.STATE_CONFLICT.value,
            "결과는 SUCCEEDED 상태에서만 조회할 수 있습니다",
        )
    if not job.results:
        raise GenAiApiError(
            status.HTTP_404_NOT_FOUND,
            ErrorCode.RESULT_NOT_FOUND.value,
            "결과를 찾을 수 없습니다",
        )
    return JobResultsResponse(
        request_id=job.request_id,
        job_id=job.job_id,
        status=JobStatus.SUCCEEDED,
        results=job.results,
    )


# ── ⑥ POST /api/genai/jobs/{job_id}/cancel ───────────────────────
@router.post("/jobs/{job_id}/cancel", response_model=JobCancelResponse)
async def cancel_job(job_id: str, request: Request) -> JobCancelResponse:
    """작업 취소 — RECEIVED·RUNNING 에서만 가능(종결 상태는 409 STATE_CONFLICT)."""
    payload = await _load_json_object(request)
    cancel_req = _validate(CancelRequest, payload)
    _require_job(job_id)

    result = genai_sim.get_job_store().transition(job_id, JobStatus.CANCELED.value)
    if result.outcome == "NOT_FOUND":
        raise _job_not_found()
    if not result.ok or result.job is None:
        raise GenAiApiError(
            status.HTTP_409_CONFLICT,
            ErrorCode.STATE_CONFLICT.value,
            "이미 종료된 작업은 취소할 수 없습니다",
        )

    logger.info(
        "[MOCK][GENAI] job canceled job_id=%s requested_by=%s",
        sanitize_for_log(job_id),
        sanitize_for_log(cancel_req.requested_by),
    )
    return JobCancelResponse(
        request_id=result.job.request_id,
        job_id=result.job.job_id,
        status=JobStatus.CANCELED,
        canceled_at=result.job.canceled_at or result.job.updated_at,
    )


# ── 목 전용 보조 EP (명세서 밖) ──────────────────────────────────
@router.get("/_mock/jobs")
async def mock_list_jobs() -> dict[str, Any]:
    """[목 전용] 등록된 작업 목록/상태를 확인한다."""
    jobs = [
        {
            "job_id": job.job_id,
            "request_id": job.request_id,
            "status": job.status,
            "progress": job.progress,
            "current_step": job.current_step,
            "error_code": job.error_code,
            "result_count": len(job.results),
            "updated_at": job.updated_at,
        }
        for job in genai_sim.get_job_store().list_jobs()
    ]
    return {"jobs": jobs, "active_tasks": genai_sim.active_task_count()}


@router.post("/_mock/reset")
async def mock_reset() -> dict[str, Any]:
    """[목 전용] 진행 중 태스크를 취소하고 작업 저장소를 비운다."""
    await genai_sim.cancel_all_tasks()
    genai_sim.get_job_store().clear()
    logger.info("[MOCK][GENAI] store reset")
    return {"result": "success"}


@router.post("/_mock/jobs/{job_id}/status-sync")
async def mock_status_sync(job_id: str) -> dict[str, Any]:
    """[목 전용] ③ 상태 동기화(§4.3) 수동 발신.

    대상은 ``MOCK_GENAI_STATUS_SYNC_URL`` (base) + ``/api/genai/jobs/{job_id}/status-sync``.
    미설정이면 비활성(sent=false) — 목이 수신측 주소를 유추하지 않는다.
    """
    job = _require_job(job_id)
    target = genai_sim.status_sync_target(job_id)
    if target is None:
        return {
            "job_id": job_id,
            "sent": False,
            "target": None,
            "reason": "MOCK_GENAI_STATUS_SYNC_URL 미설정 또는 허용되지 않는 대상 — status-sync 비활성",
        }
    sent = await genai_sim.send_webhook(target, genai_sim.build_webhook_payload(job))
    return {
        "job_id": job_id,
        "sent": sent,
        "target": target,
        "reason": None if sent else "대상이 응답하지 않았습니다(재시도 상한 소진)",
    }


# ── 내부 헬퍼 ────────────────────────────────────────────────────
def _job_not_found() -> GenAiApiError:
    return GenAiApiError(
        status.HTTP_404_NOT_FOUND, ErrorCode.JOB_NOT_FOUND.value, "작업을 찾을 수 없습니다"
    )


def _require_job(job_id: str) -> GenAiJob:
    """작업을 조회하고 없으면 404 JOB_NOT_FOUND."""
    job = genai_sim.get_job_store().get(job_id)
    if job is None:
        raise _job_not_found()
    return job
