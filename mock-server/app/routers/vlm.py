"""
IntelliVIX Video VLM 벤더 목 라우터 — KLID 연동 API v1.1.0 정합.

엔드포인트(모두 ``/v1/videovlm-klid/`` prefix):
- POST /describe     : 이벤트 묘사(장소·환경·상황 서술)
- POST /describe-sub : 이벤트 추가 질문(발생 여부와 근거 서술)
- GET  /events       : 지원 이벤트 목록과 창구별 가용성
- GET  /status       : 서버 처리 가능 상태

★ 판정(verify) 라우트는 두지 않는다 — 저작도구가 연동하지 않는 창구다. 목에 남겨 두면
누군가 그 경로로 다시 배선하게 되므로 의도적으로 뺐다. 되살리지 말 것.

두 위탁 창구는 요청을 즉시 수락(HTTP 202 + ``{"request_id","status":"accepted"}``)하고,
지연(config.callback_delay_seconds) 후 callback_url 로 결과를 비동기 POST 한다
(FastAPI BackgroundTasks). 접수 이후 처리 오류는 callback_url 로 status=failed 로 전달한다.

결정적 실패 트리거(mock): request_id 가 "fail"(대소문자 무시)로 시작하거나 media.path 에
"fail" 이 포함되면 처리 실패로 간주해 failed 콜백을 발사한다(동기 응답은 규격대로 accepted
유지 — 실패는 콜백으로만 전달). 우리 BE VlmResultService 의 실패 콜백 처리 통합 테스트용.
판정 규칙은 vlm_sim.is_failure_trigger 참조.

보안:
- 입력 검증(CWE-20): pydantic 모델(selected_frames maxlen 600, media 구조).
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
from app.schemas.vlm import AcceptedResponse, DescribeRequest, DescribeSubRequest
from app.services import url_guard, vlm_sim
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


def _assert_allowed_callback(callback_url: str, request_id: str) -> None:
    """콜백 대상 호스트를 허용 목록으로 제한한다 (SSRF, CWE-918).

    목 서버는 인증이 없으므로, 도달 가능한 누구나 임의 URL 을 넣어 목을 발판으로 내부망에
    POST 를 쏠 수 있다. 허용 밖 호스트는 접수 자체를 거부(400)하고 outbound 를 발사하지 않는다.
    """
    allowed = get_settings().callback_allowed_hosts_list()
    if url_guard.is_allowed_callback(callback_url, allowed):
        return
    logger.warning(
        "[MOCK][VLM] callback_url rejected(not allowed host) request_id=%s host=%s",
        sanitize_for_log(request_id),
        sanitize_for_log(str(url_guard.callback_host(callback_url))),
    )
    raise MockApiError(
        status.HTTP_400_BAD_REQUEST,
        "callback_url host is not allowed",
        "VALIDATION_ERROR",
    )


def _resolve_request_id(provided: str | None) -> str:
    """request_id 누락/공백 시 방어적으로 UUID 를 발급한다(echo 일관성 유지)."""
    if provided is None or not str(provided).strip():
        return uuid.uuid4().hex
    return provided


# ── POST /v1/videovlm-klid/describe ──────────────────────────────
@router.post(
    "/v1/videovlm-klid/describe",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def describe(request: Request, background_tasks: BackgroundTasks) -> AcceptedResponse:
    """이벤트 묘사 접수 — 즉시 202 accepted 반환 후 콜백으로 서술 발사."""
    payload = await _parse_payload(request)
    req = _validate(DescribeRequest, payload)
    request_id = _resolve_request_id(req.request_id)
    _assert_allowed_callback(str(req.callback_url), request_id)

    logger.info(
        "[MOCK][VLM] describe accepted request_id=%s event_type=%s callback_url=%s",
        sanitize_for_log(request_id),
        sanitize_for_log(req.event_type or "(none)"),
        sanitize_for_log(req.callback_url),
    )
    # 결정적 실패 트리거면 동기 응답은 accepted 유지, failed 콜백만 발사(규격 동작).
    if vlm_sim.is_failure_trigger(request_id, req.media.path):
        _enqueue_callback(
            background_tasks,
            str(req.callback_url),
            vlm_sim.build_failed_callback(request_id),
        )
    else:
        # 성공 콜백은 대상 영상의 <b>실제 길이</b>를 조회해 그 길이에 걸맞은 서술을 만든다.
        # 길이 조회(ffprobe)는 블로킹이라 페이로드 생성을 백그라운드로 미룬다 — accepted 응답을
        # 지연시키지 않기 위함. 조회 실패는 폴백 길이로 degrade 한다(콜백은 항상 발사).
        background_tasks.add_task(
            vlm_sim.schedule_describe_callback,
            str(req.callback_url),
            request_id,
            req.media.path,
            req.media.duration_sec,
            get_settings().callback_delay_seconds,
        )
    return AcceptedResponse(request_id=request_id, status="accepted")


# ── POST /v1/videovlm-klid/describe-sub ──────────────────────────
@router.post(
    "/v1/videovlm-klid/describe-sub",
    response_model=AcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def describe_sub(
    request: Request, background_tasks: BackgroundTasks
) -> AcceptedResponse:
    """이벤트 추가 질문 접수 — 발생 여부와 근거를 서술로 답하는 콜백을 발사한다.

    질문 문장은 이벤트별로 서버가 관리하며 연동 시스템이 지정하지 않는다(규격 §3.3).
    영상 길이에 의존하지 않으므로 묘사와 달리 ffprobe 조회를 하지 않는다.
    """
    payload = await _parse_payload(request)
    req = _validate(DescribeSubRequest, payload)
    request_id = _resolve_request_id(req.request_id)
    _assert_allowed_callback(str(req.callback_url), request_id)

    logger.info(
        "[MOCK][VLM] describe-sub accepted request_id=%s event_type=%s callback_url=%s",
        sanitize_for_log(request_id),
        sanitize_for_log(req.event_type or "(none)"),
        sanitize_for_log(req.callback_url),
    )
    if vlm_sim.is_failure_trigger(request_id, req.media.path):
        callback = vlm_sim.build_failed_callback(request_id)
    else:
        callback = vlm_sim.build_describe_sub_callback(request_id, req.event_type)
    _enqueue_callback(background_tasks, str(req.callback_url), callback)
    return AcceptedResponse(request_id=request_id, status="accepted")


# ── GET /v1/videovlm-klid/events ─────────────────────────────────
@router.get("/v1/videovlm-klid/events")
async def events() -> dict:
    """지원 이벤트 목록과 창구별 가용성 — 규격 §3.4.

    실벤더는 창구마다 사용 가능한 event_type 이 다를 수 있다고 명시한다. 이 목은 7종 전부를
    두 창구에서 사용 가능한 것으로 돌려준다 — 목의 목적이 배선 확인이라 여기서 값을 좁히면
    로컬에서 정상 흐름을 재현할 수 없기 때문이다(스키마의 event_type 완화와 같은 취지).
    """
    names = {
        "fire": ("화재", "불꽃 등 화재 상황"),
        "smoke": ("연기", "연기 등 화재 상황"),
        "fall": ("쓰러짐", "사람이 쓰러지거나 바닥에 누워 있는 상황"),
        "violence": ("폭력", "폭행, 몸싸움, 물리적 충돌 상황"),
        "flooding": ("침수", "물이 차오르거나 공간이 물에 잠긴 상황"),
        "car_accident": ("교통사고", "차량 충돌, 전복, 사고 정황"),
        "kidnapping": ("납치", "강제로 끌고 가거나 납치로 의심되는 상황"),
    }
    codes = list(names)
    return {
        "version": 1,
        "events": [
            {
                "event_type": code,
                "name": names[code][0],
                "description": names[code][1],
                "describe": True,
                "describe_sub": True,
            }
            for code in codes
        ],
        "describe_events": codes,
        "describe_sub_events": codes,
    }


# ── GET /v1/videovlm-klid/status ─────────────────────────────────
@router.get("/v1/videovlm-klid/status")
async def status_check() -> dict:
    """서버 처리 가능 상태 — 규격 §3.5.

    ``ready`` 즉시 접수 가능 · ``busy`` 접수는 되나 결과 지연 · ``loading`` 아직 처리 불가.
    목은 항상 ready 를 돌려주되, 진행 중인 길이 조회 수를 대기 수로 실어 관측에 쓴다.
    """
    inflight = vlm_sim.describe_probe_inflight()
    return {"status": "ready", "queue": inflight, "pending": inflight}


def _enqueue_callback(
    background_tasks: BackgroundTasks, callback_url: str, payload: dict
) -> None:
    """콜백 발사를 BackgroundTask 로 예약한다(지연은 설정값)."""
    delay = get_settings().callback_delay_seconds
    background_tasks.add_task(vlm_sim.schedule_callback, callback_url, payload, delay)
