"""
IntelliVIX Video VLM 벤더 목 라우터 — API v2.0.1 정합.

엔드포인트(모두 /v1/videovlm/ prefix):
- POST /v1/videovlm/verify   : 이벤트 검증(화재/쓰러짐 등 존재 여부)
- POST /v1/videovlm/describe : 상황 묘사(구간별 자연어 서술)
- GET  /v1/videovlm/status   : 서버 상태 확인

verify/describe 는 요청을 즉시 수락(``{"request_id","status":"accepted"}``)하고,
지연(config.callback_delay_seconds) 후 callback_url 로 결과를 비동기 POST 한다
(FastAPI BackgroundTasks). 접수 이후 처리 오류는 callback_url 로 status=failed 로 전달한다.

결정적 실패 트리거(mock): request_id 가 "fail"(대소문자 무시)로 시작하거나 media.path 에
"fail" 이 포함되면 처리 실패로 간주해 failed 콜백을 발사한다(동기 응답은 규격대로 accepted
유지 — 실패는 콜백으로만 전달). 우리 BE VlmResultService 의 실패 콜백 처리 통합 테스트용.
판정 규칙은 vlm_sim.is_failure_trigger 참조.

보안:
- 입력 검증(CWE-20): pydantic 모델(event_type Enum, selected_frames maxlen 8, media 구조).
  구조 오류→400, 값 오류→422.
- 로그 인젝션(CWE-117): request_id/event_type/callback_url 은 sanitize_for_log 경유.
- 정보 노출(CWE-209): 오류는 공통 핸들러가 규격 응답으로 변환(스택트레이스 미노출).
- SSRF(CWE-918): 콜백 발사는 규격 동작이나 임의 URL outbound 이므로 로컬/테스트 전용
  (vlm_sim.py 경고 주석 참조). 목 서버는 운영 노출 금지.

application/json 과 multipart/form-data(image + request json) 둘 다 수용한다.
"""

from __future__ import annotations

import json
import logging
import uuid
from typing import Type, TypeVar

from fastapi import APIRouter, BackgroundTasks, Request, status
from pydantic import BaseModel, ValidationError

from app.config import get_settings
from app.exceptions import MockApiError
from app.schemas.vlm import AcceptedResponse, DescribeRequest, VerifyRequest
from app.services import vlm_sim
from app.state import sanitize_for_log

router = APIRouter()
logger = logging.getLogger(__name__)

_ModelT = TypeVar("_ModelT", bound=BaseModel)

# pydantic 값 오류(422 대상) 유형 — event_type enum, selected_frames 길이, 수치 범위 등.
_VALUE_ERROR_TYPES = frozenset(
    {
        "enum",
        "literal_error",
        "too_long",
        "too_short",
        "greater_than",
        "greater_than_equal",
        "less_than",
        "less_than_equal",
        "value_error",
        "string_too_short",
        # callback_url(HttpUrl) 형식/스킴 오류 — 잘못된 값 → 422.
        "url_parsing",
        "url_scheme",
        "url_syntax_violation",
        "url_too_long",
    }
)
# 구조 오류(400 대상) 유형 — 필수 누락, 타입 불일치, JSON 파싱 실패 등.
_STRUCTURAL_ERROR_TYPES = frozenset(
    {
        "missing",
        "json_invalid",
        "model_type",
        "model_attributes_type",
        "dict_type",
        "list_type",
    }
)


async def _parse_payload(request: Request) -> dict:
    """application/json 또는 multipart(request part + image) 요청 본문을 dict 로 파싱한다.

    multipart 의 image 파일은 저장하지 않고(mock) 무시한다.
    """
    content_type = request.headers.get("content-type", "")
    if content_type.startswith("multipart/form-data"):
        form = await request.form()
        raw = form.get("request")
        if raw is None or not isinstance(raw, str):
            raise MockApiError(
                status.HTTP_400_BAD_REQUEST,
                "multipart 'request' part is required",
                "VALIDATION_ERROR",
            )
        parsed = _loads_dict(raw.encode("utf-8"))
    else:
        body = await request.body()
        if not body:
            raise MockApiError(
                status.HTTP_400_BAD_REQUEST, "Request body is required", "VALIDATION_ERROR"
            )
        parsed = _loads_dict(body)
    return parsed


def _loads_dict(raw: bytes) -> dict:
    """JSON 바이트를 dict 로 파싱한다. 실패/비객체는 400."""
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "Invalid JSON body", "VALIDATION_ERROR"
        )
    if not isinstance(parsed, dict):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST, "Invalid request body", "VALIDATION_ERROR"
        )
    return parsed


def _validate(model_cls: Type[_ModelT], payload: dict) -> _ModelT:
    """payload 를 모델로 검증한다. 구조 오류→400, 값 오류→422."""
    try:
        return model_cls.model_validate(payload)
    except ValidationError as exc:
        raise MockApiError(_classify_status(exc), _first_message(exc), "VALIDATION_ERROR")


def _classify_status(exc: ValidationError) -> int:
    """검증 오류를 400(구조)/422(값)로 분류한다. 구조 오류가 하나라도 있으면 400."""
    types = {err.get("type") for err in exc.errors()}
    if types & _STRUCTURAL_ERROR_TYPES:
        return status.HTTP_400_BAD_REQUEST
    if types & _VALUE_ERROR_TYPES:
        # 422 유효하지 않은 파라미터값 — starlette 버전 간 상수명 차이를 피해 정수 리터럴 사용.
        return 422
    return status.HTTP_400_BAD_REQUEST


def _first_message(exc: ValidationError) -> str:
    """첫 검증 오류 메시지를 반환한다(사용자 노출용, 스택트레이스 미포함)."""
    errors = exc.errors()
    if not errors:
        return "validation error"
    return str(errors[0].get("msg", "validation error"))


def _resolve_request_id(provided: str | None) -> str:
    """request_id 누락/공백 시 방어적으로 UUID 를 발급한다(echo 일관성 유지)."""
    if provided is None or not str(provided).strip():
        return uuid.uuid4().hex
    return provided


# ── POST /v1/videovlm/verify ─────────────────────────────────────
@router.post("/v1/videovlm/verify", response_model=AcceptedResponse)
async def verify(request: Request, background_tasks: BackgroundTasks) -> AcceptedResponse:
    """이벤트 검증 접수 — 즉시 accepted 반환 후 콜백으로 결과 발사."""
    payload = await _parse_payload(request)
    req = _validate(VerifyRequest, payload)
    request_id = _resolve_request_id(req.request_id)

    logger.info(
        "[MOCK][VLM] verify accepted request_id=%s event_type=%s callback_url=%s",
        sanitize_for_log(request_id),
        sanitize_for_log(req.event_type.value),
        sanitize_for_log(req.callback_url),
    )
    # 결정적 실패 트리거(request_id "fail" prefix 또는 media.path 에 "fail" 포함)면
    # 규격대로 동기 응답은 accepted 를 유지하고 failed 콜백만 발사한다.
    if vlm_sim.is_failure_trigger(request_id, req.media.path):
        callback = vlm_sim.build_failed_callback(request_id)
    else:
        callback = vlm_sim.build_verify_callback(request_id, req.event_type)
    _enqueue_callback(background_tasks, str(req.callback_url), callback)
    return AcceptedResponse(request_id=request_id, status="accepted")


# ── POST /v1/videovlm/describe ───────────────────────────────────
@router.post("/v1/videovlm/describe", response_model=AcceptedResponse)
async def describe(request: Request, background_tasks: BackgroundTasks) -> AcceptedResponse:
    """상황 묘사 접수 — 즉시 accepted 반환 후 콜백으로 구간별 결과 배열 발사."""
    payload = await _parse_payload(request)
    req = _validate(DescribeRequest, payload)
    request_id = _resolve_request_id(req.request_id)

    logger.info(
        "[MOCK][VLM] describe accepted request_id=%s callback_url=%s",
        sanitize_for_log(request_id),
        sanitize_for_log(req.callback_url),
    )
    # 결정적 실패 트리거면 동기 응답은 accepted 유지, failed 콜백만 발사(규격 동작).
    if vlm_sim.is_failure_trigger(request_id, req.media.path):
        callback = vlm_sim.build_failed_callback(request_id)
    else:
        callback = vlm_sim.build_describe_callback(request_id)
    _enqueue_callback(background_tasks, str(req.callback_url), callback)
    return AcceptedResponse(request_id=request_id, status="accepted")


# ── GET /v1/videovlm/status ──────────────────────────────────────
@router.get("/v1/videovlm/status")
async def status_check() -> dict[str, str]:
    """VLM 목 서버 상태 확인."""
    return {"status": "ok", "service": "videovlm"}


def _enqueue_callback(
    background_tasks: BackgroundTasks, callback_url: str, payload: dict
) -> None:
    """콜백 발사를 BackgroundTask 로 예약한다(지연은 설정값)."""
    delay = get_settings().callback_delay_seconds
    background_tasks.add_task(vlm_sim.schedule_callback, callback_url, payload, delay)
