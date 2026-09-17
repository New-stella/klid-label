"""
IntelliVIX Video VLM 시뮬레이션 — 콜백 페이로드 생성 + 비동기 콜백 발사.

묘사·추가 질문 창구는 요청을 즉시 수락(accepted)한 뒤, 지연(config.callback_delay_seconds)
후 callback_url 로 결과를 POST 한다. 실제 추론은 하지 않고 결정적(deterministic) mock
페이로드를 만든다.

★ 서술 형식의 정본은 **실제 사업자 콜백 원문**이다(2026-09-14 수신):
  ``reports/vlm-klid-integration/실응답-20260914/LIVE-*.json`` (콜백 body 는 ``body``).
  - 묘사(describe): 라벨 줄 5개 ``장소 · 날씨 · 상황 · 환경 · 심각성`` 이 이 순서. 줄 머리 ``- ``
    는 호출마다 **있기도 없기도** 하다. 값은 괄호 부연이 붙은 여러 문장 문단이고, 심각성은
    ``N점. …`` 또는 ``N점. 이유: …``. **구간(0~8초 등) 문장은 없다.**
  - 사용자 프롬프트(custom): 마크다운 — 「영상에서 '<이벤트 문구>' 이벤트가 발생하지 않았습니다.」
    → ``### 근거:`` → 번호 목록 또는 글머리표 목록 → (선택) ``---`` → 「따라서, …」 결론.
  구 목 형식(``- 상황: 0~8초 구간에서 …`` 을 구간 수만큼 잇는 서술)은 실응답과 모양이 달라 폐기했다.

결정성: 변형(줄 머리 ``- `` 유무 · 발생/미발생 · 목록 모양 등)은 **request_id 의 SHA-256** 으로
고른다. 파이썬 내장 ``hash()`` 는 프로세스마다 솔트가 달라 쓰지 않는다 — 같은 요청은 언제나 같은
서술을 받아야 시험이 재현된다.

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
import hashlib
import logging
import math
import os
import re
from typing import Optional

import httpx

from app.config import get_settings
from app.services import media_probe
from app.state import sanitize_for_log

logger = logging.getLogger(__name__)

# 콜백 HTTP 타임아웃(초) — 대상 무응답 대비 상한.
_CALLBACK_TIMEOUT_SEC = 5.0

# describe 구간 window 크기(초) — 영상 길이를 관측 분량으로 환산하는 단위.
DESCRIBE_WINDOW_SEC = 8
# 영상 길이를 알 수 없을 때 쓰는 폴백 길이(초).
FALLBACK_DURATION_SEC = 16.0
# 길이 상한(초) — 24시간.
MAX_DURATION_SEC = 86_400
# 구간 개수 상한 — 상한을 넘는 길이는 **뒷부분을 잘라내지 않고** window 를 늘려 균등 재분배한다.
MAX_DESCRIBE_SEGMENTS = 450

#: 저작도구 콜백 수신의 description 상한(문자 수) — 넘으면 수신 측이 콜백을 거부한다.
#: 서술은 구성상 이보다 훨씬 짧지만(실응답 400~660자 수준), 넘치는 경우를 막는 마지막 방어선이다.
MAX_DESCRIPTION_CHARS = 2000


# ── 결정적 변형 선택 ─────────────────────────────────────────────
def _pick(request_id: object, axis: str, modulo: int) -> int:
    """request_id 와 축 이름으로 ``0 <= n < modulo`` 를 결정적으로 고른다.

    축마다 솔트를 달리해 한 축의 선택이 다른 축과 같은 비트를 공유하지 않게 한다.
    """
    digest = hashlib.sha256(f"{axis}:{request_id or ''}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") % max(1, modulo)


def _flag(request_id: object, axis: str) -> bool:
    """request_id 로 결정되는 참/거짓 한 비트."""
    return _pick(request_id, axis, 2) == 0


def _cap(text: str) -> str:
    """수신 측 상한을 넘는 서술을 자른다(넘치면 콜백 전체가 거부되므로)."""
    if len(text) <= MAX_DESCRIPTION_CHARS:
        return text
    logger.warning(
        "[MOCK][VLM] 서술이 상한(%d자)을 넘어 잘라낸다 len=%d", MAX_DESCRIPTION_CHARS, len(text)
    )
    return text[:MAX_DESCRIPTION_CHARS]


# ── 묘사(describe) 서술 재료 ─────────────────────────────────────
_PLACES: tuple[str, ...] = (
    "도심 주요 교차로 (왕복 6차선 도로와 횡단보도가 함께 보이는 구간)",
    "아파트 단지 진입로 인근 이면도로 (보행로와 차도가 연석으로 나뉘어 있음)",
    "상가 밀집 지역의 사거리 교차로 (버스 정류장이 교차로 모서리에 인접)",
    "하천변 산책로와 맞닿은 둔치 도로 (제방 아래 저지대 구간)",
    "지하차도 진입부 (진입 차선 2개와 보행자 통로가 분리된 구조)",
)
_WEATHERS: tuple[str, ...] = (
    "맑음 (그림자가 선명하고 먼 곳까지 시야가 충분히 확보된다.)",
    "흐림 (구름이 낮게 깔려 화면 전체의 조도가 다소 낮다.)",
    "비 (노면이 젖어 있어 차량 전조등과 후미등 불빛이 바닥에 반사된다.)",
    "맑음 또는 약간의 안개 (먼 건물의 윤곽이 조금 흐리게 보인다.)",
)
_ENVIRONMENTS: tuple[str, ...] = (
    "건물이 도로 양쪽에 밀집한 도심 지역. 차선이 명확히 구분돼 있고 교차로 모서리마다 신호등과 "
    "보행자 신호기가 설치돼 있다. 가로수와 가로등이 일정한 간격으로 늘어서 있다.",
    "주거지와 소규모 상가가 섞인 생활권 도로. 인도 폭이 좁고 가장자리에 주정차 차량이 일부 보이며, "
    "전봇대와 안내 표지판이 인도 끝에 서 있다.",
    "넓은 광장형 교차로로 중앙에 교통섬이 있고 버스 전용차로가 따로 표시돼 있다. 주변에 고층 건물과 "
    "녹지가 함께 보이며, 교차로 위에는 방향 안내 표지판이 설치돼 있다.",
    "하천과 제방이 화면 한쪽에 보이는 저지대 구간. 도로 가장자리에 배수구가 설치돼 있고, 산책로 "
    "안내판과 난간이 길게 이어진다.",
)

#: 이벤트 유형별 「상황」 문장(발생/미발생 각 4문장)과 「심각성」(점수, 이유).
#: 문장 수는 영상 길이가 정한다(짧으면 앞 2문장, 길면 4문장까지) — 관측 분량이 늘어나는 성질.
_EVENT_SCENES: dict[str, dict[str, object]] = {
    "fire": {
        "occurred": (
            "화재 발생이 확인됨.",
            "화면 우측 건물 1층 창문에서 주황색 불꽃이 새어 나오고, 검은 연기가 위층 외벽을 타고 계속 올라간다.",
            "주변 보행자들이 건물에서 멀어지며 일부는 휴대전화로 촬영하거나 통화하는 모습이 관찰된다.",
            "도로의 차량들은 속도를 줄여 건물 앞을 피해 지나간다.",
        ),
        "not": (
            "화재는 확인되지 않음.",
            "화면 전체에서 불꽃이나 연기 같은 시각적 요소가 나타나지 않으며, 차량과 보행자가 평소처럼 이동하고 있다.",
            "건물 외벽이나 차량 주변에서도 그을음이나 발열로 의심되는 흔적은 관찰되지 않았습니다.",
            "대피하거나 한곳을 주시하는 등의 이상 행동도 보이지 않는다.",
        ),
        "sev_occurred": (8, "건물 내부의 불꽃과 연기가 계속 번지고 있어 인명 피해와 인접 건물로의 확산 위험이 크다."),
        "sev_not": (2, "화재를 판단할 불꽃·연기·대피 행동이 모두 관찰되지 않았으며 일상적인 도시 풍경이 유지되고 있다."),
    },
    "smoke": {
        "occurred": (
            "연기 발생이 확인됨.",
            "화면 상단의 건물 옥상 부근에서 회색 연기가 피어올라 바람을 따라 도로 쪽으로 번진다.",
            "연기가 짙어지면서 먼 곳의 건물 윤곽과 신호등이 점점 흐리게 보인다.",
            "일부 보행자가 입을 가리고 걸음을 재촉하는 모습이 관찰된다.",
        ),
        "not": (
            "연기는 확인되지 않음.",
            "하늘과 건물 윤곽이 영상 내내 선명하게 유지되며 시야를 가리는 회색·검은색 기류가 보이지 않는다.",
            "차량 배기가스 외에 한곳에서 지속적으로 피어오르는 연기 기둥은 관찰되지 않았습니다.",
            "보행자들도 평소와 같은 속도로 이동하고 있다.",
        ),
        "sev_occurred": (6, "연기가 도로 쪽으로 확산되며 시야를 가리고 있어 화재로 이어질 가능성을 확인할 필요가 있다."),
        "sev_not": (1, "연기로 볼 만한 시각적 요소가 전혀 없고 대기가 맑게 유지되고 있다."),
    },
    "fall": {
        "occurred": (
            "사람의 쓰러짐이 확인됨.",
            "횡단보도 끝에서 걷던 보행자 1명이 중심을 잃고 앞으로 넘어진 뒤 바닥에 누운 채 거의 움직이지 않는다.",
            "주변을 지나던 보행자 두 명이 다가가 상태를 살피고, 한 명은 통화를 시작한다.",
            "쓰러진 사람 옆으로 가방과 소지품이 흩어져 있다.",
        ),
        "not": (
            "쓰러짐은 확인되지 않음.",
            "화면 속 보행자들은 모두 서 있거나 정상적인 보폭으로 걷고 있으며, 바닥에 누워 있는 사람은 보이지 않는다.",
            "잠시 몸을 숙이는 동작이 있으나 곧바로 일어나 이동을 이어가 넘어짐으로 볼 수 없습니다.",
            "주변 사람들이 한곳으로 모이는 모습도 관찰되지 않는다.",
        ),
        "sev_occurred": (7, "쓰러진 뒤 움직임이 거의 없어 의식 저하 가능성이 있으며 신속한 구호 확인이 필요하다."),
        "sev_not": (2, "넘어지거나 바닥에 누운 사람이 없고 보행 흐름이 정상적으로 유지되고 있다."),
    },
    "violence": {
        "occurred": (
            "폭력 행위가 확인됨.",
            "인도 가장자리에서 두 사람이 언성을 높이다가 한 명이 상대의 어깨를 밀치고 팔을 반복적으로 휘두른다.",
            "상대방이 뒤로 물러서다 벽에 부딪히고, 주변 보행자들이 거리를 두고 멈춰 선다.",
            "잠시 뒤 제3자가 두 사람 사이에 끼어들어 떼어놓으려 한다.",
        ),
        "not": (
            "폭력 행위는 확인되지 않음.",
            "보행자들이 서로 일정한 거리를 두고 이동하며, 밀치거나 때리는 등의 신체 접촉은 관찰되지 않는다.",
            "두세 명이 모여 대화하는 장면이 있으나 몸짓이 크지 않고 곧 각자 흩어진다.",
            "누군가를 피하거나 뒤쫓는 움직임도 보이지 않습니다.",
        ),
        "sev_occurred": (8, "신체 접촉을 동반한 공격이 반복되고 있어 부상 위험이 크며 즉각적인 확인이 필요하다."),
        "sev_not": (2, "다툼이나 신체 접촉이 없고 주변 사람들의 이동도 평온하게 이루어지고 있다."),
    },
    "flooding": {
        "occurred": (
            "침수 발생이 확인됨.",
            "도로 가장자리부터 흙탕물이 차오르며 차량 바퀴의 절반가량이 물에 잠긴 상태가 관측된다.",
            "배수구 주변으로 물이 역류해 인도 턱을 넘어 흐르고, 보행자들이 물을 피해 건물 쪽으로 붙어 걷는다.",
            "일부 차량은 진입을 포기하고 후진해 되돌아간다.",
        ),
        "not": (
            "침수는 확인되지 않음.",
            "노면이 젖어 있기는 하나 고인 물은 차선 가장자리의 얕은 웅덩이 수준이며 차량 통행에 지장이 없다.",
            "배수구 주변에서도 물이 역류하거나 인도로 넘치는 모습은 관찰되지 않았습니다.",
            "차량과 보행자 모두 평소 경로로 이동하고 있다.",
        ),
        "sev_occurred": (7, "물이 차량 바퀴 높이까지 차올라 통행이 막히고 있어 고립·차량 침수 위험이 크다."),
        "sev_not": (2, "물 고임이 경미해 통행에 영향이 없고 수위가 오르는 기미도 보이지 않는다."),
    },
    "car_accident": {
        "occurred": (
            "교통사고 발생이 확인됨.",
            "교차로 중앙에서 좌회전하던 승용차와 직진하던 SUV가 충돌해 두 차량이 비스듬히 멈춰 선다.",
            "충돌 직후 한 차량의 앞 범퍼 파편이 도로에 흩어지고, 뒤따르던 차량들이 급정거하며 정체가 생긴다.",
            "운전자 한 명이 차에서 내려 파손 부위를 확인하는 모습이 관찰된다.",
        ),
        "not": (
            "교통사고는 확인되지 않음.",
            "모든 차량들이 정상적으로 신호 및 표지판을 따르며 도로를 운행하고 있으며, 차량 간 충돌이나 급격한 방향 변경은 관찰되지 않았습니다.",
            "일부 차량이 브레이크를 밟아 정지하거나 느린 속도로 통과하지만, 이는 정상적인 교차로 통행 행위다.",
            "도로 위에 파편이나 멈춰 선 차량 같은 사고 흔적도 보이지 않는다.",
        ),
        "sev_occurred": (7, "차량 간 충돌로 교차로 통행이 막히고 탑승자 부상 가능성이 있어 현장 확인이 필요하다."),
        "sev_not": (2, "교통사고 발생 여부를 판단하기 위한 조건(충돌, 급격한 방향 변화, 밀려남 등)이 충족되지 않았으며, 모든 차량의 행동이 안전하게 이루어지고 있다."),
    },
    "kidnapping": {
        "occurred": (
            "납치 의심 상황이 확인됨.",
            "인도에서 성인 1명이 다른 1명의 팔을 붙잡고 길가에 정차한 승합차 쪽으로 강제로 끌고 간다.",
            "끌려가는 사람은 몸을 뒤로 빼며 저항하지만 결국 차량 뒷좌석에 태워지고, 차량은 곧바로 출발한다.",
            "주변 보행자 한 명이 멈춰 서서 차량이 떠난 방향을 바라본다.",
        ),
        "not": (
            "납치 의심 상황은 확인되지 않음.",
            "보행자들이 각자 자유롭게 이동하며, 누군가를 붙잡아 끌고 가거나 억지로 차량에 태우는 장면은 관찰되지 않는다.",
            "정차한 차량에 사람이 타고 내리는 모습이 있으나 스스로 문을 열고 탑승해 강제성이 보이지 않습니다.",
            "뒤쫓거나 저항하는 등의 이상 행동도 없다.",
        ),
        "sev_occurred": (9, "저항하는 사람을 강제로 차량에 태워 이동한 정황으로 신변 위협이 매우 크다."),
        "sev_not": (2, "강제 이동이나 저항 행동이 전혀 없고 사람들의 이동이 자연스럽게 이루어지고 있다."),
    },
}

#: 이벤트 유형을 모르거나 받지 못했을 때의 「상황」·「심각성」 — 특정 이벤트를 판정하지 않는다.
_GENERIC_SITUATION: tuple[str, ...] = (
    "일반적인 시내 교통 상황.",
    "다양한 차종(승용차, 버스, 트럭)이 정상적으로 이동하고 있다.",
    "일부 차량은 신호 대기 중이며, 보행자들은 횡단보도를 따라 길을 건넌다.",
    "전체적으로 혼잡함은 없으며 평온한 교통 흐름이 유지된다.",
)
_GENERIC_SEVERITY: tuple[int, str] = (
    2,
    "영상은 일상적인 교통 상황을 담고 있으며, 사고·화재·다툼 같은 이상 현상이 관찰되지 않았다. 따라서 심각성이 낮다고 판단된다.",
)


def _normalize_event_type(event_type: object) -> str:
    return str(event_type).strip().lower() if event_type is not None else ""


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
    """영상 길이를 덮는 총 초(정수) — <b>올림</b>해 영상 꼬리를 반드시 포함한다(#9)."""
    normalized = normalize_duration(duration_sec)
    return max(1, math.ceil(normalized))


def plan_describe_windows(duration_sec: object) -> list[tuple[int, int]]:
    """영상 길이 전체를 덮는 ``(start_sec, end_sec)`` 관측 단위 목록을 만든다.

    ⚠ 이 목록은 **서술에 구간 문장으로 실리지 않는다** — 실응답에 구간 서술이 없기 때문이다.
    쓰임은 「상황」 문단의 관측 문장 수를 영상 길이에 비례시키는 것 하나다
    (``_situation_sentence_count``).

    규칙:
    - 초 단위 **정수** 경계만 쓴다.
    - 총 길이는 올림(ceil)하되 최소 1초 — 1초 미만 영상에서도 0 길이 단위 없이 1개를 보장한다.
    - ``start = 직전 end`` 누적이라 겹침/빈틈/역전이 원천적으로 생기지 않는다.
    - 개수가 상한(450)을 넘으면 **뒤를 자르지 않고** window 를 늘려 균등 재분배한다.
    """
    raw = _positive_finite_unclamped(duration_sec)
    if raw is not None and raw > MAX_DURATION_SEC:
        # 상한 초과분은 관측 단위를 만들지 않는다 — 조용히 사라지지 않도록 드러낸다(OWASP A09).
        logger.warning(
            "[MOCK][VLM] 영상 길이가 구간 상한(%d초)을 초과 — %.0f초 이후는 관측 단위를 만들지 않는다",
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


def _situation_sentence_count(duration_sec: object) -> int:
    """「상황」 문단의 문장 수 — 관측 단위 1개(8초 이하) 영상은 3문장, 그보다 긴 영상은 4문장.

    구 목은 영상 길이만큼 구간 문장을 이어 붙여 긴 영상에서 서술이 한없이 길어졌다. 실응답은
    길이와 무관하게 여러 문장 한 문단이라(3~4문장), 관측 분량의 차이를 **문장 수** 로만 남긴다.
    """
    return 3 if len(plan_describe_windows(duration_sec)) <= 1 else 4


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
        "[MOCK][VLM] 영상 길이를 확인하지 못해 폴백 %.0f초로 describe 서술을 생성한다 file=%s",
        FALLBACK_DURATION_SEC,
        sanitize_for_log(os.path.basename(str(media_path))),
    )
    return FALLBACK_DURATION_SEC


def build_describe_callback(
    request_id: str, duration_sec: object = None, event_type: object = None
) -> dict:
    """묘사 성공 콜백 페이로드 — KLID 연동 API v1.2.0 §2.7·§2.8.

    ``results`` 는 **단일 객체**이고 항목은 ``description`` 하나다. 판정 항목(detected/accuracy)은
    판정 창구 전용이라 여기 담지 않는다.

    ``duration_sec`` 은 이미 결정된 영상 길이(초). 생략/이상값이면 폴백 길이를 쓴다.
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {
            "description": mock_describe_text(
                request_id=request_id, event_type=event_type, duration_sec=duration_sec
            )
        },
    }


def mock_describe_text(
    request_id: object = "", event_type: object = None, duration_sec: object = None
) -> str:
    """묘사 전문을 **실응답 형식**으로 영상 1건당 한 벌 만든다.

    - 라벨 줄 5개 ``장소 · 날씨 · 상황 · 환경 · 심각성`` 을 이 순서로 한 줄씩 둔다.
    - 줄 머리 ``- `` 유무는 request_id 로 결정한다 — 실응답이 호출마다 달랐기 때문이며, 우리 파서가
      두 형식을 모두 받는지 로컬에서 드러난다.
    - 표준 이벤트 유형은 발생/미발생 두 갈래를 request_id 로 섞는다. 모르는 유형·미지정은
      특정 이벤트를 판정하지 않는 일반 서술이다(값을 지어내지 않는다).
    - 심각성은 ``N점. …`` 또는 ``N점. 이유: …``.

    ``settings.describe_omit_situation`` 이 켜지면 「상황」 줄을 **통째로 뺀다**. 그래야
    「상황 줄이 없으면 채우지 않는다(빈 값도 넣지 않는다)」는 미채움 분기를 로컬에서 확인할 수 있다.
    """
    rid = str(request_id or "")
    key = _normalize_event_type(event_type)
    sentence_count = _situation_sentence_count(duration_sec)

    scene = _EVENT_SCENES.get(key)
    if scene is None:
        situation_sentences = _GENERIC_SITUATION
        score, reason = _GENERIC_SEVERITY
    else:
        occurred = _flag(rid, "describe-occurred")
        situation_sentences = scene["occurred" if occurred else "not"]  # type: ignore[assignment]
        score, reason = scene["sev_occurred" if occurred else "sev_not"]  # type: ignore[misc]

    place = _PLACES[_pick(rid, "describe-place", len(_PLACES))]
    weather = _WEATHERS[_pick(rid, "describe-weather", len(_WEATHERS))]
    environment = _ENVIRONMENTS[_pick(rid, "describe-environment", len(_ENVIRONMENTS))]
    situation = " ".join(situation_sentences[:sentence_count])
    severity = f"{score}점. 이유: {reason}" if _flag(rid, "describe-severity") else f"{score}점. {reason}"

    rows: list[tuple[str, str]] = [("장소", place), ("날씨", weather)]
    if not get_settings().describe_omit_situation:
        rows.append(("상황", situation))
    rows.append(("환경", environment))
    rows.append(("심각성", severity))

    prefix = "- " if _flag(rid, "describe-dash") else ""
    return _cap("\n".join(f"{prefix}{label}: {value}" for label, value in rows))


# ── 사용자 프롬프트(custom) 서술 ─────────────────────────────────
#: 질문 문장에서 따옴표로 감싼 이벤트 문구를 뽑는다(곧은·굽은 따옴표, 낫표 모두).
_QUOTED_PHRASE = re.compile(r"[‘'\"“「『]\s*([^‘’'\"“”「」『』\n]{1,80}?)\s*[’'\"”」』]")
#: 문구가 없을 때 질문을 요약해 보여 줄 최대 길이.
_PROMPT_SUMMARY_MAX = 60

#: 질문 문구의 낱말로 주제를 고른다(앞에 둔 것이 우선). 한 글자 「불」은 오탐이 커 맨 뒤에서만 본다.
_TOPIC_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("car_accident", ("교통사고", "충돌", "추돌", "차량")),
    ("kidnapping", ("납치", "끌고", "강제로")),
    ("violence", ("폭행", "폭력", "몸싸움", "싸움")),
    ("fall", ("쓰러", "넘어", "낙상")),
    ("flooding", ("침수", "범람", "물에 잠")),
    ("smoke", ("연기",)),
    ("fire", ("화재", "화염", "불꽃", "불")),
)

#: 주제별 근거 항목(제목, 설명) — 발생/미발생 각 2개. 그 뒤에 공통 항목이 붙는다.
_TOPIC_EVIDENCE: dict[str, dict[str, tuple[tuple[str, str], ...]]] = {
    "car_accident": {
        "not": (
            ("모든 차량이 정상적으로 주행", "영상 전체를 관찰하면 승용차, 버스, 트럭 등 모든 차량이 신호와 차선에 따라 도로를 이용하고 있으며, 서로 간의 충돌이나 추돌은 전혀 없습니다."),
            ("사고 흔적 없음", "도로 위에 파편이나 비스듬히 멈춰 선 차량이 보이지 않고, 뒤따르는 차량의 급정거나 정체도 나타나지 않습니다."),
        ),
        "occurred": (
            ("차량 간 충돌 장면", "교차로 중앙에서 좌회전하던 승용차와 직진 차량이 맞부딪히며 두 차량이 비스듬히 멈춰 섭니다."),
            ("사고 직후의 변화", "충돌 지점에 파편이 흩어지고 뒤따르던 차량들이 급정거하면서 교차로에 정체가 생깁니다."),
        ),
    },
    "fire": {
        "not": (
            ("화염·불꽃 부재", "도로 위나 주변 건물 어디에서도 **화염**, **불꽃**, 또는 **연기** 같은 시각적 요소가 나타나지 않습니다."),
            ("정상적인 주변 활동", "보행자와 차량이 평소처럼 이동하며, 대피하거나 한곳을 주시하는 행동이 관찰되지 않습니다."),
        ),
        "occurred": (
            ("화염 관측", "화면 우측 건물 1층 창문에서 주황색 불꽃이 새어 나오고, 검은 연기가 외벽을 타고 올라갑니다."),
            ("대피 행동", "주변 보행자들이 건물에서 멀어지고, 일부는 휴대전화로 신고하는 모습이 보입니다."),
        ),
    },
    "smoke": {
        "not": (
            ("연기 기둥 없음", "하늘과 건물 윤곽이 영상 내내 선명하며, 한곳에서 지속적으로 피어오르는 연기가 보이지 않습니다."),
            ("시야 변화 없음", "먼 곳의 신호등과 간판이 흐려지는 구간이 없어 연기로 인한 시야 저하가 확인되지 않습니다."),
        ),
        "occurred": (
            ("회색 연기 확산", "건물 옥상 부근에서 회색 연기가 피어올라 바람을 따라 도로 쪽으로 번집니다."),
            ("시야 저하", "연기가 짙어지면서 먼 건물의 윤곽과 신호등이 점점 흐리게 보입니다."),
        ),
    },
    "fall": {
        "not": (
            ("바닥에 누운 사람 없음", "화면 속 보행자들은 모두 서 있거나 정상적인 보폭으로 걷고 있습니다."),
            ("일시적 동작의 회복", "몸을 숙이는 동작이 있으나 곧바로 일어나 이동을 이어가므로 넘어짐으로 볼 수 없습니다."),
        ),
        "occurred": (
            ("넘어지는 순간", "횡단보도 끝에서 걷던 보행자 1명이 중심을 잃고 앞으로 넘어집니다."),
            ("움직임 없음", "넘어진 뒤 바닥에 누운 채 거의 움직이지 않고, 주변 사람이 다가가 상태를 살핍니다."),
        ),
    },
    "violence": {
        "not": (
            ("신체 접촉 없음", "보행자들이 서로 거리를 두고 이동하며, 밀치거나 때리는 동작이 관찰되지 않습니다."),
            ("대화 장면의 평온함", "몇 명이 모여 대화하는 장면이 있으나 몸짓이 크지 않고 곧 흩어집니다."),
        ),
        "occurred": (
            ("반복적인 가격", "두 사람 중 한 명이 상대의 어깨를 밀치고 팔을 반복적으로 휘두릅니다."),
            ("주변 반응", "주변 보행자들이 멈춰 서서 거리를 두고, 제3자가 두 사람을 떼어놓으려 합니다."),
        ),
    },
    "flooding": {
        "not": (
            ("물 고임 경미", "노면이 젖어 있으나 고인 물은 차선 가장자리의 얕은 웅덩이 수준입니다."),
            ("정상 통행", "차량과 보행자가 물을 피하지 않고 평소 경로로 이동합니다."),
        ),
        "occurred": (
            ("수위 상승", "도로 가장자리부터 흙탕물이 차올라 차량 바퀴 절반가량이 잠깁니다."),
            ("통행 차질", "보행자들이 물을 피해 건물 쪽으로 붙어 걷고, 일부 차량은 진입을 포기하고 되돌아갑니다."),
        ),
    },
    "kidnapping": {
        "not": (
            ("강제 이동 없음", "누군가를 붙잡아 끌고 가거나 억지로 차량에 태우는 장면이 관찰되지 않습니다."),
            ("자발적 탑승", "정차한 차량에 타는 사람은 스스로 문을 열고 탑승해 강제성이 보이지 않습니다."),
        ),
        "occurred": (
            ("팔을 붙잡고 끌고 감", "성인 1명이 다른 1명의 팔을 붙잡고 길가의 승합차 쪽으로 강제로 끌고 갑니다."),
            ("저항과 차량 출발", "끌려가는 사람이 몸을 뒤로 빼며 저항하지만 차량 뒷좌석에 태워지고 차량이 곧바로 출발합니다."),
        ),
    },
}

_GENERIC_EVIDENCE: dict[str, tuple[tuple[str, str], ...]] = {
    "not": (
        ("관련 정황 부재", "영상 전체를 검토한 결과, 질문한 이벤트로 판단할 만한 장면이나 행동은 나타나지 않습니다."),
        ("사후 대응 없음", "이벤트가 있었다면 경찰 출동이나 응급 조치, 사람들의 모여듦 같은 반응이 보여야 하지만 영상 내에서는 그런 상황이 전혀 없습니다."),
        ("움직임이 자연스럽고 예측 가능", "사람과 차량은 각자의 방향으로만 움직이며, 갑자기 멈추거나 방향을 트는 비정상적인 모습은 없습니다."),
    ),
    "occurred": (
        ("주변의 즉각적 반응", "장면 직후 주변 사람들이 멈춰 서거나 한곳을 주시하는 등 평소와 다른 반응이 이어집니다."),
        ("전후 장면의 차이", "이벤트 전후로 사람과 차량의 흐름이 눈에 띄게 달라져 우연한 동작으로 보기 어렵습니다."),
        ("반복되는 관측", "같은 정황이 영상 여러 장면에 걸쳐 이어져 일시적인 착시로 보기 어렵습니다."),
    ),
}


def _question_event_phrase(prompt: str) -> Optional[str]:
    """질문 문장에서 따옴표 안의 이벤트 문구를 뽑는다. 없으면 None."""
    match = _QUOTED_PHRASE.search(prompt)
    if match is None:
        return None
    phrase = " ".join(match.group(1).split())
    return phrase or None


def _prompt_summary(prompt: str) -> str:
    """따옴표 문구가 없을 때 질문을 한 줄로 줄인다."""
    flat = " ".join(prompt.split()).rstrip("?？ ")
    if len(flat) > _PROMPT_SUMMARY_MAX:
        return flat[:_PROMPT_SUMMARY_MAX].rstrip() + "…"
    return flat


def _topic_of(text: str) -> Optional[str]:
    for topic, keywords in _TOPIC_KEYWORDS:
        if any(word in text for word in keywords):
            return topic
    return None


def mock_custom_text(request_id: object, prompt: str) -> str:
    """사용자 프롬프트 답변을 **실응답 형식(마크다운)** 으로 만든다.

    구조: 결론 첫 문장 → 빈 줄 → ``### 근거:`` → 번호 목록(``1. **제목**:  \\n   설명``) 또는
    글머리표 목록(``- 설명``) → (선택) ``---`` → 「따라서, …」. 굵게 위치·목록 모양·구분선 유무·
    발생 여부는 request_id 로 결정한다 — 실응답도 호출마다 구조가 조금씩 달랐다.

    질문의 따옴표 안 이벤트 문구를 첫 문장과 결론에 그대로 싣는다 — 「질문이 실제로 전달됐는가」를
    콜백만 보고 확인할 수 있게 하기 위함이다.
    """
    rid = str(request_id or "")
    asked = (prompt or "").strip()
    phrase = _question_event_phrase(asked)
    occurred = _flag(rid, "custom-occurred")
    outcome = "occurred" if occurred else "not"
    verdict = "발생했습니다" if occurred else "발생하지 않았습니다"

    if phrase is not None:
        if _flag(rid, "custom-bold"):
            opening = f"영상에서 **‘{phrase}’** 이벤트가 **{verdict}**."
        else:
            opening = f"영상에서 '{phrase}' 이벤트는 **{verdict}**."
        subject = f"‘{phrase}’ 이벤트"
    else:
        opening = f"질문하신 「{_prompt_summary(asked)}」에 해당하는 이벤트는 영상에서 **{verdict}**."
        subject = "질문하신 이벤트"

    topic = _topic_of(phrase or asked)
    items: list[tuple[str, str]] = []
    if topic is not None:
        # 주제 항목 2개 + 공통 항목 1~2개 → 근거 3~4개(실응답의 항목 수 범위).
        items.extend(_TOPIC_EVIDENCE[topic][outcome])
        items.extend(_GENERIC_EVIDENCE[outcome][: 1 + _pick(rid, "custom-items", 2)])
    else:
        items.extend(_GENERIC_EVIDENCE[outcome])

    if _flag(rid, "custom-numbered"):
        evidence = "\n\n".join(
            f"{index}. **{title}**:  \n   {body}" for index, (title, body) in enumerate(items, start=1)
        )
    else:
        evidence = "\n".join(f"- {body}" for _, body in items)

    if occurred:
        conclusion = (
            f"따라서, **영상 내용 기준으로 {subject}는 발생한 것으로 판단되며**, "
            "관제 요원의 현장 확인이 필요합니다."
        )
    else:
        conclusion = (
            f"따라서, **영상 내용 기준으로 {subject}는 발생하지 않았으며**, "
            "관련된 시각적 증거도 확인되지 않습니다."
        )

    separator = "\n\n---\n\n" if _flag(rid, "custom-rule") else "\n\n"
    return _cap(f"{opening}\n\n### 근거:\n{evidence}{separator}{conclusion}")


def build_custom_callback(request_id: str, prompt: str) -> dict:
    """사용자 프롬프트 성공 콜백 페이로드 — 규격 v1.2.0 §3.4.

    ★ 저작도구의 **추가 질문 축**이 쓰는 창구다. 판정 항목은 제공되지 않는다 — 출력 형식을 서버가
    알 수 없으므로 모델 출력을 description 에 그대로 담는다(규격 §3.4).
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {"description": mock_custom_text(request_id, prompt)},
    }


def build_describe_sub_callback(request_id: str, event_type: object = None) -> dict:
    """추가 질문 성공 콜백 페이로드 — 규격 §3.3.

    발생 여부와 근거를 **서술로** 답한다. 규격 예시처럼 「네,」/「아니요,」로 시작하는 짧은 자유
    문장이며 판정 항목은 제공되지 않는다. (저작도구는 이 창구 대신 custom 을 쓴다 — 목에만 남아 있다.)
    """
    return {
        "request_id": request_id,
        "status": "completed",
        "results": {"description": _describe_sub_text(request_id, event_type)},
    }


#: 이벤트별 추가 질문 답변(발생, 미발생).
_DESCRIBE_SUB_ANSWERS: dict[str, tuple[str, str]] = {
    "fire": (
        "네, 화면 우측 건물 창문에서 주황색 불꽃과 함께 검은 연기가 지속적으로 피어오릅니다.",
        "아니요, 영상 전체에서 불꽃이나 연기가 보이지 않으며 주변 사람들도 평소처럼 이동합니다.",
    ),
    "smoke": (
        "네, 영상 중반부터 회색 연기가 화면 상단으로 계속 번지며 시야를 가립니다.",
        "아니요, 하늘과 건물 윤곽이 선명하게 유지되며 피어오르는 연기는 관찰되지 않습니다.",
    ),
    "fall": (
        "네, 보행자 1인이 중심을 잃고 바닥에 쓰러진 뒤 이후 움직임이 거의 없습니다.",
        "아니요, 바닥에 쓰러지거나 누워 있는 사람은 보이지 않고 모두 정상적으로 걷고 있습니다.",
    ),
    "violence": (
        "네, 두 사람이 서로를 향해 팔을 반복적으로 휘두르며 몸싸움을 이어갑니다.",
        "아니요, 사람들 사이에 밀치거나 때리는 신체 접촉은 관찰되지 않습니다.",
    ),
    "flooding": (
        "네, 도로 하단부터 물이 차오르며 차량 바퀴 절반가량이 잠긴 상태가 관측됩니다.",
        "아니요, 노면에 얕은 물 고임만 있을 뿐 차량 통행에 지장이 있는 침수는 보이지 않습니다.",
    ),
    "car_accident": (
        "네, 교차로에서 차량 두 대가 충돌한 뒤 한 대가 도로 중앙에 정지해 있습니다.",
        "아니요, 모든 차량이 신호에 따라 정상적으로 주행하며 충돌이나 사고 흔적은 없습니다.",
    ),
    "kidnapping": (
        "네, 성인 1인이 다른 1인의 팔을 잡아끌며 차량 쪽으로 강제로 이동시킵니다.",
        "아니요, 누군가를 붙잡아 끌고 가거나 억지로 차량에 태우는 장면은 보이지 않습니다.",
    ),
}


def _describe_sub_text(request_id: object = "", event_type: object = None) -> str:
    """이벤트별 추가 질문 답변 서술(표준 7종은 전용 문장, 그 밖은 폴백)."""
    key = _normalize_event_type(event_type)
    answers = _DESCRIBE_SUB_ANSWERS.get(key)
    if answers is None:
        return "아니요, 해당 이벤트로 판단할 만한 뚜렷한 근거는 확인되지 않습니다. 화면에는 통상적인 통행만 관측됩니다."
    return answers[0] if _flag(request_id, "describe-sub-occurred") else answers[1]


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
    request_id: str,
    media_path: object = None,
    duration_hint: object = None,
    event_type: object = None,
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
        return build_describe_callback(request_id, FALLBACK_DURATION_SEC, event_type)
    _describe_probe_inflight += 1
    try:
        duration = await asyncio.to_thread(
            resolve_describe_duration, media_path, duration_hint
        )
    finally:
        _describe_probe_inflight -= 1
    return build_describe_callback(request_id, duration, event_type)


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
    event_type: object = None,
) -> None:
    """지연 후 **영상 길이를 조회해** describe 성공 콜백을 발사한다.

    페이로드 생성을 접수(동기 응답) 경로가 아니라 백그라운드로 미룬다 — 길이 조회가 최대
    ``FFPROBE_TIMEOUT_SEC`` 걸릴 수 있어 accepted 응답을 지연시키면 안 되기 때문이다.
    """
    if delay > 0:
        await asyncio.sleep(delay)
    payload = await build_describe_callback_async(
        request_id, media_path, duration_hint, event_type
    )
    await fire_callback(callback_url, payload)
