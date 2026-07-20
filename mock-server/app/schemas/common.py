"""
공통 응답 스키마.

ai-server 구조 정합을 위해 공통 ErrorResponse 를 스키마 패키지로 이관한다.
``exceptions.py`` 가 이 모듈에서 import 한다 (시그니처/동작 불변).
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class ErrorResponse(BaseModel):
    """공통 에러 응답 규격."""

    model_config = ConfigDict(extra="forbid")

    error_code: str
    message: str
