"""
pytest 공통 설정.

- 목 서버는 인증/DB/외부 모델이 없으므로 별도 강제 환경변수는 없다.
- FastAPI TestClient fixture와 매 테스트마다 초기화되는 상태 저장소 fixture를 제공한다.
"""

from __future__ import annotations

import os
from collections.abc import Iterator

import pytest

# import 전에 CORS 등 기본값 설정 — Settings 캐시보다 먼저 적용되도록
os.environ.setdefault("MOCK_CORS_ORIGINS", "*")


@pytest.fixture
def client() -> Iterator["TestClient"]:  # noqa: F821
    """FastAPI TestClient — 앱 lifespan을 포함해 기동한다."""
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        yield c


@pytest.fixture
def store() -> "InMemoryStore":  # noqa: F821
    """매 테스트마다 새로운 인메모리 상태 저장소."""
    from app.state import InMemoryStore

    return InMemoryStore()
