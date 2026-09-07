"""
IntelliVIX Video VLM 시뮬레이션 — 콜백 페이로드 생성 + 비동기 콜백 발사.

묘사·추가 질문 창구는 요청을 즉시 수락(accepted)한 뒤, 지연(config.callback_delay_seconds)
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

from app.config import get_settings
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


def _situation_phrase(index: int, start_sec: int, end_sec: int) -> str:
    """한 구간의 「상황」 문장. 구간 블록과 영상 1건 전문이 <같은 문장>을 쓰도록 단일 원천으로 둔다."""
    situation = _SITUATIONS[index % len(_SITUATIONS)]
    return f"{start_sec}~{end_sec}초 구간에서 {situation} 정황이 관측됨"


def _describe_text(index: int, start_sec: int, end_sec: int) -> str:
    """describe 3.4 형식 mock 설명(\\n 포함 단일 문자열).

    구간 index 로 장소/날씨/상황/환경/심각성 축을 각각 다른 주기로 순환시켜, 구간마다 다른
    한글 설명이 나오게 한다(라벨링 화면 시계열 패널에서 실서비스처럼 확인하기 위함).
    """
    place = _PLACES[index % len(_PLACES)]
    weather = _WEATHERS[index % len(_WEATHERS)]
    environment = _ENVIRONMENTS[index % len(_ENVIRONMENTS)]
    severity = 3 + (index % 6)  # 3~8 결정적 변화
    return (
        f"- 장소: {place}\n"
        f"- 날씨: {weather}\n"
        f"- 상황: {_situation_phrase(index, start_sec, end_sec)}\n"
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


def build_describe_callback(request_id: str, duration_sec: object = None) -> dict:
    """묘사 성공 콜백 페이로드 — KLID 연동 API v1.2.0 §2.7·§2.8.

    ``results`` 는 **단일 객체**이고 항목은 ``description`` 하나다. 구 규격의 구간 배열
    (``[{start_sec,end_sec,description}]``)은 폐기됐다. 판정 항목(detected/accuracy)은 판정
    창구 전용이라 여기 담지 않는다.

    구간 서술 생성기는 그대로 재사용해 **한 편의 서술로 이어 붙인다** — 영상 길이에 비례해
    내용이 늘어나는 성질을 유지하기 위함이다.

    ``duration_sec`` 은 이미 결정된 영상 길이(초). 생략/이상값이면 폴백 길이를 쓴다.
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {"description": mock_describe_text(duration_sec)},
    }


#: 「상황」 값 안에서 구간 서술을 잇는 구분자.
#:
#: 구간을 <줄> 로 나누지 않고 한 줄에 잇는 것은 의도다 — 확정 규칙상 「상황」 값은 **그 줄의
#: 줄바꿈까지**이고 다음 라벨 줄로 이어붙이지 않는다. 줄로 나누면 두 번째 구간부터는 우리 파서가
#: 가져가지 않아, 영상이 길수록 서술이 늘어나는 성질이 그 값에 반영되지 않는다.
_SITUATION_JOIN = " / "


def mock_describe_text(duration_sec: object = None) -> str:
    """묘사 전문을 규격 형식(``- 라벨: 값`` 줄 단위)으로 <영상 1건당 한 벌> 만든다.

    구 구현은 구간마다 만든 5항목 블록 앞에 ``- 0~8초: `` 를 덧붙여 이어 붙였다. 그 결과
    ``- 0~8초: - 장소: ...`` 처럼 <첫 줄이 뭉개진> 채로 같은 라벨 블록이 구간 수만큼 반복됐다.
    실제 사업자는 영상 1건에 블록 하나를 돌려주고, 우리 파서도 「상황」 줄 하나만 찾는다.

    영상 길이에 비례해 내용이 늘어나는 기존 성질은 **「상황」 값 안**에서 유지한다 — 구간이
    늘면 그 줄이 길어진다. 「상황」 뒤에 다른 라벨 줄을 두는 것도 의도다: 우리 파서가 값 경계를
    줄바꿈에서 끊는지 실동작으로 드러낸다.

    ``settings.describe_omit_situation`` 이 켜지면 「상황」 줄을 **통째로 뺀다**. 그래야
    「상황 줄이 없으면 채우지 않는다(빈 값도 넣지 않는다)」는 미채움 분기를 로컬에서 확인할 수 있다.
    """
    windows = plan_describe_windows(duration_sec)
    situation = _SITUATION_JOIN.join(
        _situation_phrase(index, start, end) for index, (start, end) in enumerate(windows)
    )
    # 대표 축은 첫 구간 값을 쓴다 — 블록이 하나뿐이므로 구간마다 달랐던 축을 하나로 접는다.
    place = _PLACES[0 % len(_PLACES)]
    weather = _WEATHERS[0 % len(_WEATHERS)]
    environment = _ENVIRONMENTS[0 % len(_ENVIRONMENTS)]
    severity = 3 + (max(len(windows) - 1, 0) % 6)

    lines = [f"- 장소: {place}", f"- 날씨: {weather}"]
    if not get_settings().describe_omit_situation:
        lines.append(f"- 상황: {situation}")
    lines.append(f"- 환경: {environment}")
    lines.append(f"- 심각성: {severity}/10점 — {_severity_comment(severity)}")
    return "\n".join(lines)


def build_custom_callback(request_id: str, prompt: str) -> dict:
    """사용자 프롬프트 성공 콜백 페이로드 — 규격 v1.2.0 §3.4.

    ★ 저작도구의 **추가 질문 축**이 쓰는 창구다. 받은 질문 문구에 답하는 형태로 서술한다 —
    실벤더도 프롬프트에 답하므로, 목이 질문과 무관한 고정문을 돌려주면 「질문이 실제로 전달됐는가」를
    로컬에서 확인할 수 없다.

    판정 항목은 제공되지 않는다 — 출력 형식을 서버가 알 수 없으므로 모델 출력을 description 에
    그대로 담는다(규격 §3.4).
    """
    asked = (prompt or "").strip()
    # 질문을 그대로 되비추어 <무엇을 받았는지>가 콜백에 드러나게 한다(로컬 검증용).
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {
            "description": (
                f"네, 확인됩니다. 질문 「{asked[:120]}」에 대해 영상에서 관련 정황이 관측됩니다 — "
                "두 사람이 근접해 접촉하는 장면이 이어지고 주변 보행자가 물러납니다."
            )
        },
    }


def build_describe_sub_callback(request_id: str, event_type: object = None) -> dict:
    """추가 질문 성공 콜백 페이로드 — 규격 §3.3.

    발생 여부와 근거를 **서술로** 답한다. 모델이 "네"/"아니오"로 답을 시작해도 그 문장은
    description 에 그대로 담기며, 판정 항목은 제공되지 않는다.
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {"description": _describe_sub_text(event_type)},
    }


def _describe_sub_text(event_type: object = None) -> str:
    """이벤트별 추가 질문 답변 서술(표준 7종은 전용 문장, 그 밖은 폴백)."""
    known = {
        "fire": "네, 화면 우측 건물 창문에서 주황색 불꽃과 함께 검은 연기가 지속적으로 피어오릅니다.",
        "smoke": "네, 영상 중반부터 회색 연기가 화면 상단으로 계속 번지며 시야를 가립니다.",
        "fall": "네, 보행자 1인이 중심을 잃고 바닥에 쓰러진 뒤 이후 움직임이 거의 없습니다.",
        "violence": "네, 두 사람이 서로를 향해 팔을 반복적으로 휘두르며 몸싸움을 이어갑니다.",
        "flooding": "네, 도로 하단부터 물이 차오르며 차량 바퀴 절반가량이 잠긴 상태가 관측됩니다.",
        "car_accident": "네, 교차로에서 차량 두 대가 충돌한 뒤 한 대가 도로 중앙에 정지해 있습니다.",
        "kidnapping": "네, 성인 1인이 다른 1인의 팔을 잡아끌며 차량 쪽으로 강제로 이동시킵니다.",
    }
    key = str(event_type).strip().lower() if event_type is not None else ""
    if key in known:
        return known[key]
    return "해당 이벤트로 판단할 만한 뚜렷한 근거는 확인되지 않습니다. 화면에는 통상적인 통행만 관측됩니다."


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


def build_failed_callback(request_id: str, message: str = "추론 실패: Video VLM inference failed") -> dict:
    """접수 이후 처리 실패 콜백 페이로드(status=failed).

    ★ ``error`` 는 객체가 아니라 **문자열**이다(규격 §2.7). 구 규격의 ``{code, message}`` 객체를
    되살리면 우리 BE 가 실패 콜백을 전량 400 으로 거부해 실패 사실 자체를 받지 못한다.
    """
    return {
        "request_id": request_id,
        "status": "failed",
        "error": message,
    }


def is_failure_trigger(request_id: str, media_path: str | None) -> bool:
    """결정적 실패 트리거 판정 — 접수 이후 처리 실패(failed 콜백)를 재현하기 위한 mock 규칙.

    규격상 접수(동기 응답)는 항상 성공(accepted)이고, 처리 오류는 callback_url 로만
    status="failed" 로 전달된다. 이를 통합 테스트에서 결정적으로 재현하기 위해 아래
    입력을 실패 트리거로 간주한다(두 창구 공통):
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
