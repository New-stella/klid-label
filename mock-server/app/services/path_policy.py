"""파일 경로 <b>읽기·쓰기 경계 판정의 단일 원천</b> (F-7, CWE-22/CWE-1188).

목 서버는 인증이 없어 ``input_path`` / ``export_path`` 를 요청자가 임의로 지정할 수 있다.
따라서 "어떤 디렉터리를 읽어도 되는가 / 어디에 써도 되는가"는 **한 곳에서만** 판정해야 한다.

<b>왜 모듈로 분리했나</b>: 구 구조는 같은 정책이 세 곳(``deid_sim.resolve_input_dir`` ·
``deid_sim.input_root_configured`` · ``media_probe.resolve_probe_target``)에 인라인 중복돼 있었고,
그중 저수준 헬퍼(``resolve_input_dir``)는 "base 가 비면 제한 없음"이라는 **fail-open** 계약이었다.
지금은 우회로가 없지만, 세 번째 소비자가 저수준 헬퍼만 쓰면 그 순간 조용히 fail-open 이 된다.
이 프로젝트에는 "상태 게이트를 호출처마다 배선하면 반드시 샌다"는 재발 이력이 있으므로,
**정책 판정(``resolve_readable_dir``)을 여기 하나로 모으고** 저수준 헬퍼는 그 내부 부품으로만 둔다.

계층:
- ``resolve_input_dir`` / ``resolve_output_dir`` — 저수준 경계 헬퍼(기존 공개 계약 보존).
- ``read_root_configured`` — 읽기 허용 루트가 실제로 설정돼 있는지(미설정 = fail-closed).
- ``resolve_readable_dir`` — ★ **원본을 읽는 모든 소비자가 써야 하는 정책 진입점**
  (fail-closed + 경계 검증을 한 번에 수행).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

#: 읽기 허용 루트 미설정 경고를 1회만 남기기 위한 플래그(로그 스팸 방지).
_read_root_unset_warned: bool = False


def reset_warning() -> None:
    """허용 루트 미설정 경고 플래그를 초기화한다(테스트용)."""
    global _read_root_unset_warned
    _read_root_unset_warned = False


def base_tokens(base: str) -> list[str]:
    """콤마 구분 허용 루트 문자열을 유효 토큰 목록으로 정규화한다(빈 토큰 제거)."""
    if not isinstance(base, str):
        return []
    return [b.strip() for b in base.split(",") if b.strip()]


def is_within(path: Path, base: Path) -> bool:
    """path 가 base 하위(또는 동일)인지 검사한다."""
    try:
        path.relative_to(base)
        return True
    except ValueError:
        return False


def resolve_output_dir(export_path: str, output_base: str = "") -> Optional[Path]:
    """export_path 를 정규화하고 output_base 하위인지 검증한다(CWE-22 심층 방어).

    ``output_base`` 는 **콤마 구분 다중 base** 를 허용한다 — BE 의 co-locate 산출(Phase 5A)에서
    비식별 export_path 가 ``dirname(원본)/{rawSn}/deid/`` 라, 원본이 놓인 마운트 루트와 비식별
    저장소를 모두 허용해야 목이 산출물을 쓸 수 있다. BE 의 ``STORAGE_RAW_MOUNT_ROOTS`` 와
    **같은 값**으로 맞추는 것이 규약이다.

    **fail-closed**: output_base 가 비어있으면 임의 절대경로 쓰기를 막기 위해 None 을
    반환한다(HIGH-1).

    Returns:
        정규화된 출력 디렉터리 Path. base 미설정/정규화 실패/모든 base 밖이면 None.
    """
    bases = base_tokens(output_base)
    if not bases:
        return None
    try:
        export_resolved = Path(export_path).resolve()
    except (OSError, ValueError, TypeError):
        return None
    for base in bases:
        try:
            base_resolved = Path(base).resolve()
        except (OSError, ValueError):
            continue
        if is_within(export_resolved, base_resolved):
            return export_resolved
    return None


def resolve_input_dir(input_path: str, input_base: str = "") -> Optional[Path]:
    """input_path 를 정규화하고 input_base 하위인지 검증한다(CWE-22 읽기 경계).

    ⚠ **저수준 헬퍼다** — ``input_base`` 가 비면 "제한 없음"으로 해석한다(기존 공개 계약).
    원본을 실제로 읽는 소비자는 이 함수를 직접 쓰지 말고 :func:`resolve_readable_dir` 를 쓴다.

    Returns:
        정규화된 입력 디렉터리 Path. 모든 base 밖/정규화 실패면 None.
    """
    try:
        in_dir = Path(input_path).resolve()
    except (OSError, ValueError, TypeError):
        return None
    bases = base_tokens(input_base)
    if not bases:
        return in_dir
    for base in bases:
        try:
            base_resolved = Path(base).resolve()
        except (OSError, ValueError):
            continue
        if is_within(in_dir, base_resolved):
            return in_dir
    return None


def read_root_configured(input_base: str) -> bool:
    """원본 읽기 허용 루트가 실제로 설정돼 있는지 — <b>미설정이면 아무것도 읽지 않는다</b>.

    이유: ``MOCK_INPUT_BASE`` 미설정 시 자동 도출값(``MOCK_OUTPUT_BASE`` 항목의 상위)이
    ``/nas-storage`` 같은 <b>최상위 1단 디렉터리</b>면 상위가 ``/`` 로 붕괴해 "허용 루트 =
    파일시스템 전체"가 된다(CWE-22/CWE-1188). 설정에서 그 붕괴값을 제거하면
    (``Settings.effective_input_base``) 여기로 빈 문자열이 들어오는데, 그것을 "제한 없음"으로
    해석하면 붕괴와 똑같이 위험하다.
    """
    if base_tokens(input_base):
        return True
    global _read_root_unset_warned
    if not _read_root_unset_warned:
        logger.warning(
            "[MOCK] 원본 읽기 허용 루트 미설정 — 원본을 읽지 않는다(fail-closed). "
            "MOCK_INPUT_BASE 를 BE 의 원본 마운트 루트로 명시 설정하세요"
            "(MOCK_OUTPUT_BASE 가 최상위 1단 경로면 상위 자동 도출이 '/' 로 붕괴하므로 사용하지 않는다)."
        )
        _read_root_unset_warned = True
    return False


def resolve_readable_dir(input_path: str, input_base: str = "") -> Optional[Path]:
    """★ 원본을 읽어도 되는 디렉터리인지 판정하는 <b>정책 단일 진입점</b>.

    ``read_root_configured``(fail-closed) + ``resolve_input_dir``(경계)를 한 번에 수행한다.
    원본 파일을 읽는 모든 소비자(``deid_sim`` 복사·워터마킹, ``media_probe`` 길이 조회)는
    이 함수만 사용한다 — 판정이 호출처마다 재구현되면 그중 하나가 반드시 샌다.

    Returns:
        정규화된 입력 디렉터리 Path. 허용 루트 미설정/경계 밖/정규화 실패면 None.
    """
    if not read_root_configured(input_base):
        return None
    return resolve_input_dir(input_path, input_base)
