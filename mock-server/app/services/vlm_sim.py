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
import math
import os
from typing import Optional

import httpx

from app.schemas.vlm import EventType
from app.services import media_probe
from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

# 콜백 HTTP 타임아웃(초) — 대상 무응답 대비 상한.
_CALLBACK_TIMEOUT_SEC = 5.0

# describe 구간 window 크기(초) — Video VLM 8초 window 규격 반영.
DESCRIBE_WINDOW_SEC = 8
# 영상 길이를 알 수 없을 때 쓰는 폴백 길이(초) — 구 고정 동작(8초 × 2구간)과 동일하다.
FALLBACK_DURATION_SEC = 16.0
# 구간 초 상한 — 우리 BE VlmResultRequest.Segment @Max(86400) 정합(24시간).
MAX_DURATION_SEC = 86_400
# 구간 개수 상한 — BE VlmResultRequest.results @Size(max=500) 아래로 여유를 둔 값.
#   상한을 넘는 길이는 **뒷부분을 잘라내지 않고** window 를 늘려 균등 재분배한다(전체 커버 유지).
MAX_DESCRIBE_SEGMENTS = 450

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


def mock_verify_result(event_type: object = None) -> dict:
    """verify 콜백용 결과(accuracy + description)를 생성한다.

    ``event_type`` 은 표준 6종(:class:`EventType`)일 수도, **우리가 모르는 문자열이거나
    ``None``** 일 수도 있다(2026-08-06 완화 — 스키마 주석 참조). 표준 6종은 각자의 서술을,
    그 밖·미지정은 **폴백 서술**을 돌려준다. ``EventType`` 은 ``str`` 상속 Enum 이라 평문
    문자열 키로도 그대로 조회된다(``hash("fire") == hash(EventType.FIRE)``).
    """
    description = _VERIFY_DESCRIPTIONS.get(
        event_type, "요청한 이벤트에 해당하는 정황이 확인됩니다."
    )
    return {"accuracy": _VERIFY_ACCURACY, "description": description}


# 구간 설명 템플릿 — 축마다 개수가 달라(5/4/7/6) index 순환 주기가 어긋나므로,
# 구간이 이어져도 같은 조합이 바로 반복되지 않는다(결정적, 랜덤 미사용).
_PLACES: tuple[str, ...] = (
    "도심 이면도로",
    "아파트 단지 주차장",
    "상가 밀집 교차로",
    "하천변 산책로",
    "지하보도 진입로",
)
_WEATHERS: tuple[str, ...] = ("맑음", "흐림", "비", "눈")
_SITUATIONS: tuple[str, ...] = (
    "보행자 여러 명이 좌우로 이동",
    "차량 한 대가 서행하며 진입",
    "이륜차가 인도 쪽으로 근접 주행",
    "정차 차량 옆에서 사람이 하차",
    "무리 지어 선 사람들이 대화",
    "자전거가 보행자 사이를 통과",
    "우산을 든 보행자가 횡단",
)
_ENVIRONMENTS: tuple[str, ...] = (
    "주간 순광",
    "야간 저조도",
    "역광",
    "가로등 조명",
    "그림자 대비 강함",
    "새벽 여명",
)


def _severity_comment(severity: int) -> str:
    """심각성 점수에 대응하는 mock 코멘트."""
    if severity <= 4:
        return "특이사항 없음, 정기 관찰을 유지합니다."
    if severity <= 6:
        return "지속 관찰이 필요합니다."
    return "관제 요원의 확인이 권고됩니다."


def _describe_text(index: int, start_sec: int, end_sec: int) -> str:
    """describe 3.4 형식 mock 설명(\\n 포함 단일 문자열).

    구간 index 로 장소/날씨/상황/환경/심각성 축을 각각 다른 주기로 순환시켜, 구간마다 다른
    한글 설명이 나오게 한다(라벨링 화면 시계열 패널에서 실서비스처럼 확인하기 위함).
    """
    place = _PLACES[index % len(_PLACES)]
    weather = _WEATHERS[index % len(_WEATHERS)]
    situation = _SITUATIONS[index % len(_SITUATIONS)]
    environment = _ENVIRONMENTS[index % len(_ENVIRONMENTS)]
    severity = 3 + (index % 6)  # 3~8 결정적 변화
    return (
        f"- 장소: {place}\n"
        f"- 날씨: {weather}\n"
        f"- 상황: {start_sec}~{end_sec}초 구간에서 {situation} 정황이 관측됨\n"
        f"- 환경: {environment}\n"
        f"- 심각성: {severity}/10점 — {_severity_comment(severity)}"
    )


def _positive_finite_unclamped(value: object) -> Optional[float]:
    """양의 유한 실수만 통과시킨다(clamp 없음). 아니면 None."""
    try:
        duration = float(value)  # type: ignore[arg-type] — 비수치는 예외로 걸린다
    except (TypeError, ValueError):
        return None
    if not math.isfinite(duration) or duration <= 0:
        return None
    return duration


def _positive_finite(value: object) -> Optional[float]:
    """양의 유한 실수만 통과시키고 상한(24시간)으로 clamp 한다. 아니면 None.

    None/비수치/``nan``/``inf``/0/음수를 한 곳에서 걸러 폴백 판단을 단일화한다.
    """
    duration = _positive_finite_unclamped(value)
    if duration is None:
        return None
    return min(duration, float(MAX_DURATION_SEC))


def normalize_duration(value: object) -> float:
    """영상 길이를 정규화한다 — 이상값은 폴백(16초)으로 치환한다."""
    normalized = _positive_finite(value)
    return FALLBACK_DURATION_SEC if normalized is None else normalized


def total_cover_sec(duration_sec: object) -> int:
    """구간이 덮어야 할 총 초(정수) — <b>올림</b>해 영상 꼬리를 반드시 포함한다(#9).

    BE ``Segment.startSec/endSec`` 가 Integer 라 초 경계를 정수로 맞춰야 하는데, 그 반올림 방향에
    따라 경계 오차가 생긴다:
    - 구 동작(내림): 100.9초 영상은 100초까지만 덮어 <b>마지막 0.9초가 미커버</b>였다.
    - 현재(올림): 101초까지 덮어 영상 끝을 0.1초 <b>초과</b>한다.

    올림을 택한 근거 — 이 목의 산출물은 라벨링 화면 시계열 패널에 쓰이는 **구간 메타데이터**다.
    미커버 구간은 "그 시간대에 아무 정보가 없다"는 <b>결손</b>이지만, 마지막 구간이 1초 미만
    초과하는 것은 그 구간의 설명이 실재 프레임 없는 꼬리를 조금 포함하는 것뿐이라 소비 측에서
    무해하다(BE 는 구간을 그대로 저장·표시하며 프레임과 대조하지 않는다). 결손보다 미세 초과가
    낫다.

    상한(24시간)은 BE ``Segment @Max(86400)`` 정합을 위한 clamp 이며 그대로 유지한다 — 그
    뒤가 통째로 미커버되는 것은 의도된 상한이고, ``plan_describe_windows`` 가 WARN 으로 드러낸다.
    """
    normalized = normalize_duration(duration_sec)
    return max(1, math.ceil(normalized))


def plan_describe_windows(duration_sec: object) -> list[tuple[int, int]]:
    """영상 길이 전체를 덮는 ``(start_sec, end_sec)`` 구간 목록을 만든다.

    규칙:
    - 초 단위 **정수** 경계만 쓴다(BE ``Segment.startSec/endSec`` 가 Integer).
    - 총 길이는 올림(ceil)하되 최소 1초 — 영상 꼬리를 빠뜨리지 않으면서(#9) 1초 미만
      영상에서도 0 길이 구간(``start == end``) 없이 1구간을 보장한다.
    - ``start = 직전 end`` 누적이라 겹침/빈틈/역전이 원천적으로 생기지 않는다.
    - 구간 수가 상한(450)을 넘으면 **뒤를 자르지 않고** window 를 늘려 균등 재분배한다.
    """
    raw = _positive_finite_unclamped(duration_sec)
    if raw is not None and raw > MAX_DURATION_SEC:
        # 상한 초과분은 구간을 만들지 않는다 — 조용히 사라지지 않도록 드러낸다(OWASP A09).
        logger.warning(
            "[MOCK][VLM] 영상 길이가 구간 상한(%d초)을 초과 — %.0f초 이후는 시계열 구간을 "
            "만들지 않는다(BE Segment @Max 정합)",
            MAX_DURATION_SEC,
            float(MAX_DURATION_SEC),
        )
    total = total_cover_sec(duration_sec)
    window = DESCRIBE_WINDOW_SEC
    if math.ceil(total / window) > MAX_DESCRIBE_SEGMENTS:
        window = math.ceil(total / MAX_DESCRIBE_SEGMENTS)

    windows: list[tuple[int, int]] = []
    start = 0
    while start < total:
        end = min(start + window, total)
        windows.append((start, end))
        start = end
    return windows


def mock_describe_results(duration_sec: object = None) -> list[dict]:
    """describe 콜백용 구간별 결과 배열을 생성한다(기본 8초 window).

    ``duration_sec`` 이 없거나 이상값이면 폴백(16초) 기준으로 만든다.
    """
    return [
        {
            "start_sec": start,
            "end_sec": end,
            "description": _describe_text(index, start, end),
        }
        for index, (start, end) in enumerate(plan_describe_windows(duration_sec))
    ]


def resolve_describe_duration(
    media_path: object = None, duration_hint: object = None
) -> float:
    """describe 대상 영상의 길이(초)를 3단 폴백 체인으로 결정한다.

    ①요청 ``media.duration_sec`` 힌트(목 전용 확장) → ②ffprobe 조회 → ③고정 폴백(16초).
    ffprobe 실패/미설치/타임아웃/경로 거부는 물론 예상 밖 예외까지 삼켜 **항상 값을 반환**한다 —
    콜백 발사 자체가 길이 조회 때문에 실패하면 안 되기 때문이다.

    ⚠ 블로킹(subprocess) 호출이므로 이벤트 루프에서 직접 부르지 말 것
      (``build_describe_callback_async`` 가 ``asyncio.to_thread`` 로 오프로드한다).
    """
    hinted = _positive_finite(duration_hint)
    if hinted is not None:
        return hinted

    probed: Optional[float] = None
    try:
        probed = _positive_finite(media_probe.probe_duration_sec(media_path))
    except Exception as exc:  # noqa: BLE001 — 길이 조회 실패가 콜백을 죽이면 안 된다
        logger.warning(
            "[MOCK][VLM] duration probe 예외 — 폴백 사용 type=%s", type(exc).__name__
        )
    if probed is not None:
        return probed

    # 전체 경로는 남기지 않는다(CWE-532) — 어떤 영상인지 식별할 basename 만.
    logger.warning(
        "[MOCK][VLM] 영상 길이를 확인하지 못해 폴백 %.0f초로 describe 구간을 생성한다 file=%s",
        FALLBACK_DURATION_SEC,
        sanitize_for_log(os.path.basename(str(media_path))),
    )
    return FALLBACK_DURATION_SEC


def build_verify_callback(request_id: str, event_type: object = None) -> dict:
    """verify 성공 콜백 페이로드(status=completed)."""
    return {
        "request_id": request_id,
        "status": "completed",
        "results": mock_verify_result(event_type),
    }


def build_describe_callback(request_id: str, duration_sec: object = None) -> dict:
    """describe 성공 콜백 페이로드(status=completed, results 는 배열).

    ``duration_sec`` 은 이미 결정된 영상 길이(초). 생략/이상값이면 폴백 길이를 쓴다.
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": mock_describe_results(duration_sec),
    }


#: 동시에 진행할 수 있는 describe 길이 조회(ffprobe) 태스크 수 상한(F-5, CWE-400/770).
#:
#: describe 는 **무인증**이고 접수 제한이 없다. 요청마다 BackgroundTask 가 쌓이면 기본 executor
#: (``min(32, cpu+4)``) 만큼 ffprobe 가 동시에 뜨고, ``asyncio.to_thread`` 워커가 고갈되면 다른
#: 백그라운드 작업까지 밀린다. 상한을 넘는 요청은 **조회를 건너뛰고 즉시 폴백 페이로드로
#: 콜백한다** — 콜백 자체는 어떤 경우에도 발사되어야 하기 때문이다(기존 graceful degrade 규약).
MAX_DESCRIBE_PROBE_INFLIGHT: int = 8

#: 진행 중인 describe 길이 조회 수(단일 이벤트 루프에서만 갱신 — await 사이 원자성 보장).
_describe_probe_inflight: int = 0


def describe_probe_inflight() -> int:
    """현재 진행 중인 describe 길이 조회 수(테스트/관측용)."""
    return _describe_probe_inflight


async def build_describe_callback_async(
    request_id: str, media_path: object = None, duration_hint: object = None
) -> dict:
    """영상 길이를 조회해 describe 콜백 페이로드를 만든다(이벤트 루프 비차단).

    길이 조회는 블로킹 subprocess(ffprobe)라 ``asyncio.to_thread`` 로 오프로드한다 —
    목 서버는 단일 이벤트 루프라 여기서 막히면 다른 요청까지 지연된다.

    F-5 — 동시 조회 수가 ``MAX_DESCRIBE_PROBE_INFLIGHT`` 를 넘으면 조회를 생략하고 폴백 길이로
    페이로드를 만든다(콜백은 그대로 발사).
    """
    global _describe_probe_inflight
    if _describe_probe_inflight >= MAX_DESCRIBE_PROBE_INFLIGHT:
        logger.warning(
            "[MOCK][VLM] describe 길이 조회 동시 상한(%d) 초과 — 폴백 %.0f초로 콜백 request_id=%s",
            MAX_DESCRIBE_PROBE_INFLIGHT,
            FALLBACK_DURATION_SEC,
            sanitize_for_log(request_id),
        )
        return build_describe_callback(request_id, FALLBACK_DURATION_SEC)
    _describe_probe_inflight += 1
    try:
        duration = await asyncio.to_thread(
            resolve_describe_duration, media_path, duration_hint
        )
    finally:
        _describe_probe_inflight -= 1
    return build_describe_callback(request_id, duration)


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


async def schedule_describe_callback(
    callback_url: str,
    request_id: str,
    media_path: object,
    duration_hint: object,
    delay: float,
) -> None:
    """지연 후 **영상 길이를 조회해** describe 성공 콜백을 발사한다.

    페이로드 생성을 접수(동기 응답) 경로가 아니라 백그라운드로 미룬다 — 길이 조회가 최대
    ``FFPROBE_TIMEOUT_SEC`` 걸릴 수 있어 accepted 응답을 지연시키면 안 되기 때문이다.
    """
    if delay > 0:
        await asyncio.sleep(delay)
    payload = await build_describe_callback_async(request_id, media_path, duration_hint)
    await fire_callback(callback_url, payload)
