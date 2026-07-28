"""
IntelliVIX Video VLM 시뮬레이션 — 콜백 페이로드 생성 + 비동기 콜백 발사.

verify/describe 는 요청을 즉시 수락(accepted)한 뒤, 지연(config.callback_delay_seconds)
후 callback_url 로 결과를 POST 한다. 실제 추론은 하지 않고 결정적(deterministic) mock
페이로드를 만든다.

⚠️ SSRF 경고 (CWE-918) — 운영 노출 금지:
  ``callback_url`` 은 **요청자가 지정하는 URL** 이며, 이 서버는 그 URL 로 서버측 HTTP POST 를
  발사한다. 규격 동작(비동기 콜백)이라 목 서버에서도 구현하되, 대상 호스트는 라우터가
  ``app.services.url_guard`` + ``MOCK_CALLBACK_ALLOWED_HOSTS`` allowlist 로 제한한다
  (목록 밖이면 접수 400 + outbound 미발사). 그럼에도 본 목 서버는 **로컬/테스트 전용**이며
  공개망/운영에 노출하면 안 된다(compose 는 루프백 전용 발행).

콜백 발사 격리:
  콜백 대상이 다운/타임아웃이어도 서버 안정성이나 동기 응답에 영향이 없도록
  fire_callback 은 모든 예외를 삼키고 로깅만 한다.
"""

from __future__ import annotations

import asyncio
import logging

import httpx

from app.schemas.vlm import EventType
from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

# 콜백 HTTP 타임아웃(초) — 대상 무응답 대비 상한.
_CALLBACK_TIMEOUT_SEC = 5.0

# describe 구간 window 크기(초) — Video VLM 8초 window 규격 반영.
DESCRIBE_WINDOW_SEC = 8
# mock describe 총 길이(초) → window 로 나눠 배열 결과 생성.
_DESCRIBE_TOTAL_SEC = 16

# verify mock 정확도(고정 mock 값).
_VERIFY_ACCURACY = 0.8

# 이벤트별 mock 검증 설명.
_VERIFY_DESCRIPTIONS: dict[EventType, str] = {
    EventType.FIRE: "건물 창문에서 화염과 검은 연기가 관측되어 화재 발생이 확인됩니다.",
    EventType.FALL: "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다.",
    EventType.VIOLENCE: "두 사람 사이 물리적 충돌과 폭력 정황이 확인됩니다.",
    EventType.FLOODING: "도로 위로 물이 차오르며 침수가 진행되는 상황이 확인됩니다.",
    EventType.CAR_ACCIDENT: "교차로에서 차량 두 대가 충돌한 사고 정황이 확인됩니다.",
    EventType.KIDNAPPING: "한 사람이 다른 사람을 강제로 끌고 가는 정황이 확인됩니다.",
}


def mock_verify_result(event_type: EventType) -> dict:
    """verify 콜백용 결과(accuracy + description)를 생성한다."""
    description = _VERIFY_DESCRIPTIONS.get(
        event_type, "요청한 이벤트에 해당하는 정황이 확인됩니다."
    )
    return {"accuracy": _VERIFY_ACCURACY, "description": description}


def _describe_text(index: int, start_sec: int, end_sec: int) -> str:
    """describe 3.4 형식 mock 설명(\\n 포함 단일 문자열)."""
    severity = 4 + (index % 5)  # 4~8 결정적 변화
    return (
        "- 장소: 도심 이면도로\n"
        "- 날씨: 흐림\n"
        f"- 상황: {start_sec}~{end_sec}초 구간에서 보행자 이동이 관측됨 (심각성 {severity}점)\n"
        "- 환경: 야간 저조도\n"
        f"- 심각성: {severity}/10점 — 지속 관찰이 필요합니다."
    )


def mock_describe_results() -> list[dict]:
    """describe 콜백용 구간별 결과 배열을 생성한다(8초 window)."""
    results: list[dict] = []
    index = 0
    start = 0
    while start < _DESCRIBE_TOTAL_SEC:
        end = min(start + DESCRIBE_WINDOW_SEC, _DESCRIBE_TOTAL_SEC)
        results.append(
            {
                "start_sec": start,
                "end_sec": end,
                "description": _describe_text(index, start, end),
            }
        )
        start = end
        index += 1
    return results


def build_verify_callback(request_id: str, event_type: EventType) -> dict:
    """verify 성공 콜백 페이로드(status=completed)."""
    return {
        "request_id": request_id,
        "status": "completed",
        "results": mock_verify_result(event_type),
    }


def build_describe_callback(request_id: str) -> dict:
    """describe 성공 콜백 페이로드(status=completed, results 는 배열)."""
    return {
        "request_id": request_id,
        "status": "completed",
        "results": mock_describe_results(),
    }


def build_failed_callback(request_id: str, message: str = "Video VLM inference failed") -> dict:
    """접수 이후 처리 실패 콜백 페이로드(status=failed)."""
    return {
        "request_id": request_id,
        "status": "failed",
        "error": {"code": "INFERENCE_ERROR", "message": message},
    }


def is_failure_trigger(request_id: str, media_path: str | None) -> bool:
    """결정적 실패 트리거 판정 — 접수 이후 처리 실패(failed 콜백)를 재현하기 위한 mock 규칙.

    규격상 접수(동기 응답)는 항상 성공(accepted)이고, 처리 오류는 callback_url 로만
    status="failed" 로 전달된다. 이를 통합 테스트에서 결정적으로 재현하기 위해 아래
    입력을 실패 트리거로 간주한다(verify/describe 공통):
      - request_id 가 "fail"(대소문자 무시)로 시작하는 경우, 또는
      - media.path 에 "fail" 이 포함된 경우.
    트리거가 걸리면 build_failed_callback 을, 아니면 성공 콜백을 발사한다.
    """
    rid = (request_id or "").lower()
    path = (media_path or "").lower()
    return rid.startswith("fail") or "fail" in path


async def fire_callback(callback_url: str, payload: dict) -> None:
    """callback_url 로 결과를 POST 발사한다(예외 격리).

    ⚠️ SSRF: callback_url 은 요청자 지정 임의 URL — 로컬/테스트 전용 목 서버에서만 사용.
    콜백 대상 다운/타임아웃/연결거부 등 모든 예외를 삼켜 서버 안정성/동기 응답에 영향 없이
    로깅만 한다(콜백 실패 격리).
    """
    request_id = ""
    if isinstance(payload, dict):
        request_id = str(payload.get("request_id", ""))
    try:
        async with httpx.AsyncClient(timeout=_CALLBACK_TIMEOUT_SEC) as client:
            resp = await client.post(callback_url, json=payload)
        logger.info(
            "[MOCK][VLM] callback sent url=%s status=%d request_id=%s",
            sanitize_for_log(callback_url),
            resp.status_code,
            sanitize_for_log(request_id),
        )
    except Exception as exc:  # noqa: BLE001 — 콜백 실패는 서버에 전파 금지(격리 + 로깅)
        logger.warning(
            "[MOCK][VLM] callback failed url=%s type=%s request_id=%s",
            sanitize_for_log(callback_url),
            type(exc).__name__,
            sanitize_for_log(request_id),
        )
    return None


async def schedule_callback(callback_url: str, payload: dict, delay: float) -> None:
    """지연(delay 초) 후 콜백을 발사한다. delay<=0 이면 즉시 발사."""
    if delay > 0:
        await asyncio.sleep(delay)
    await fire_callback(callback_url, payload)
