"""
관제지원시스템(관제서버) inbound 통지 목 라우터 — 관제 계약 API-251 / API-285 정합.

우리 BE(ControlNotifyClient)가 검수 완료/수정 통지를 실제 HTTP 로 왕복하며 검증할 수 있게 한다.
로컬은 내부 목모드로 외부 호출을 우회하지 않고 **반드시 이 목 서버(:9400)를 바라본다**.

엔드포인트:
- POST /api/data-set/v2/jobs/{job_id}/notify-completed : 검수 완료 통지
    · 최초 등록  → 202 + {"dataset_version_id": "<uuid>"}
    · 동일 job_id 재호출 → **409** (관제 ``datasets.job_id UNIQUE`` 제약 모사)
- POST /api/data-set/v2/jobs/{job_id}/notify-updated   : 검수 후 수정 통지
    · 선행 completed 존재 → 202 + {"dataset_version_id": "<uuid>"}
    · 선행 completed 없음 → **404**
- POST /api/data-set/v2/jobs/_reset                    : 인메모리 등록 상태 초기화(테스트 편의)

이 409/404 응답이 우리 BE 의 자기치유(409 completed→updated / 404 updated→completed)를
유닛테스트가 아닌 **실제 HTTP 왕복**으로 검증하게 해준다.

보안:
- 입력 검증(CWE-20): job_id 는 숫자(BIGINT 문자열)만 허용 — 그 외는 400.
  (우리 BE 도 dispatch 진입부에서 동일 형식을 강제한다.)
- 로그 인젝션(CWE-117): job_id 등 외부 입력은 sanitize_for_log 경유.
- 정보 노출(CWE-209): 오류는 공통 핸들러 규격 응답(스택트레이스 미노출).
- 인증 없음 — 로컬/테스트 전용. 신뢰망에서만 기동한다.
"""

from __future__ import annotations

import logging
import re
import threading
import uuid
from typing import Any

from fastapi import APIRouter, Request, status

from app.exceptions import MockApiError
from app.state import sanitize_for_log

router = APIRouter()
logger = logging.getLogger(__name__)

# 관제 계약 경로 prefix.
_JOBS_PREFIX = "/api/data-set/v2/jobs"

# job_id = LS_DATA_RAW.RAW_SN(BIGINT) 문자열. 경로 세그먼트 조작 차단.
_JOB_ID_PATTERN = re.compile(r"^\d{1,19}$")


class _Registry:
    """job_id → dataset_version_id 등록부 (프로세스 인메모리, 스레드세이프)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._versions: dict[str, str] = {}
        self._update_counts: dict[str, int] = {}

    def register(self, job_id: str) -> str | None:
        """최초 등록 시 dataset_version_id 를 발급한다. 이미 있으면 None(=409)."""
        with self._lock:
            if job_id in self._versions:
                return None
            version_id = str(uuid.uuid4())
            self._versions[job_id] = version_id
            self._update_counts[job_id] = 0
            return version_id

    def touch_update(self, job_id: str) -> str | None:
        """수정 통지 반영. 선행 등록이 없으면 None(=404)."""
        with self._lock:
            version_id = self._versions.get(job_id)
            if version_id is None:
                return None
            self._update_counts[job_id] = self._update_counts.get(job_id, 0) + 1
            return version_id

    def snapshot(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return {
                job_id: {
                    "dataset_version_id": version_id,
                    "update_count": self._update_counts.get(job_id, 0),
                }
                for job_id, version_id in self._versions.items()
            }

    def clear(self) -> None:
        with self._lock:
            self._versions.clear()
            self._update_counts.clear()


_registry = _Registry()


def get_registry() -> _Registry:
    """전역 등록부 — 테스트가 상태를 확인/초기화할 때 사용."""
    return _registry


def _validate_job_id(job_id: str) -> str:
    if not _JOB_ID_PATTERN.match(job_id or ""):
        raise MockApiError(
            status.HTTP_400_BAD_REQUEST,
            "job_id 는 숫자여야 합니다.",
            "INVALID_JOB_ID",
        )
    return job_id


async def _read_body(request: Request) -> dict[str, Any]:
    """본문을 관대하게 읽는다 — 목이므로 스키마 강제는 하지 않고 로깅 요약에만 쓴다."""
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 — 본문 없음/비 JSON 도 허용(관제는 본문만 저장)
        return {}
    return body if isinstance(body, dict) else {}


@router.post(_JOBS_PREFIX + "/_reset")
async def reset() -> dict[str, str]:
    """등록 상태 초기화 — 로컬 시나리오 재실행 편의(테스트 전용)."""
    _registry.clear()
    logger.info("[MOCK] control notify registry reset")
    return {"status": "reset"}


@router.get(_JOBS_PREFIX)
async def list_jobs() -> dict[str, Any]:
    """등록 현황 조회 — 통지가 실제로 도달했는지 눈으로 확인하는 용도."""
    return {"jobs": _registry.snapshot()}


@router.post(_JOBS_PREFIX + "/{job_id}/notify-completed", status_code=status.HTTP_202_ACCEPTED)
async def notify_completed(job_id: str, request: Request) -> dict[str, str]:
    """검수 완료 통지 — 최초 202, 동일 job_id 재호출은 409(job_id UNIQUE 모사)."""
    _validate_job_id(job_id)
    body = await _read_body(request)

    version_id = _registry.register(job_id)
    if version_id is None:
        logger.info("[MOCK] notify-completed conflict job_id=%s", sanitize_for_log(job_id))
        raise MockApiError(
            status.HTTP_409_CONFLICT,
            "이미 등록된 job_id 입니다.",
            "DUPLICATE_JOB",
        )

    logger.info(
        "[MOCK] notify-completed accepted job_id=%s image_count=%s",
        sanitize_for_log(job_id),
        sanitize_for_log(body.get("image_count")),
    )
    return {"dataset_version_id": version_id}


@router.post(_JOBS_PREFIX + "/{job_id}/notify-updated", status_code=status.HTTP_202_ACCEPTED)
async def notify_updated(job_id: str, request: Request) -> dict[str, str]:
    """검수 후 수정 통지 — 선행 completed 가 없으면 404."""
    _validate_job_id(job_id)
    body = await _read_body(request)

    version_id = _registry.touch_update(job_id)
    if version_id is None:
        logger.info("[MOCK] notify-updated not-found job_id=%s", sanitize_for_log(job_id))
        raise MockApiError(
            status.HTTP_404_NOT_FOUND,
            "선행 완료 통지가 없는 job_id 입니다.",
            "JOB_NOT_FOUND",
        )

    changed = body.get("changed_items") or {}
    logger.info(
        "[MOCK] notify-updated accepted job_id=%s images=%s jsons=%s",
        sanitize_for_log(job_id),
        sanitize_for_log(len(changed.get("images") or []) if isinstance(changed, dict) else 0),
        sanitize_for_log(len(changed.get("jsons") or []) if isinstance(changed, dict) else 0),
    )
    return {"dataset_version_id": version_id}
