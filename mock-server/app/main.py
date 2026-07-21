"""
klid-la 외부 벤더 목(mock) 서버.

우리 BE(Spring Boot)가 외부 벤더 서버 개발 완료 전에 실연동 테스트를 하기 위한 목 서버다.
서버 1개에 벤더별 라우터로 구성한다:
- deid   : KPST 비식별화 (루트 경로 규격 — prefix 없음)
- vlm    : IntelliVIX Video VLM 시계열
- augment: 증강 AI (WINTER/NIGHT/RAIN) — Phase 4 확장

인증/DB 없이 인메모리 상태(app.state)만으로 동작한다.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.exceptions import register_exception_handlers
from app.middleware.request_id import RequestIdMiddleware
from app.routers import augment, deid, vlm

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """startup/shutdown 간단 로깅."""
    settings = get_settings()
    logger.info(
        "[MOCK] startup host=%s port=%d sim_speed=%.2f callback_delay=%.2fs",
        settings.host,
        settings.port,
        settings.sim_speed_factor,
        settings.callback_delay_seconds,
    )
    yield
    logger.info("[MOCK] shutdown")


app = FastAPI(title="klid-la 외부 벤더 목 서버", version="0.1.0", lifespan=lifespan)

# 미들웨어 — RequestIdMiddleware를 먼저 추가하여 외곽에 위치하도록 함
app.add_middleware(RequestIdMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=get_settings().cors_origins_list(),
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["*"],
)

# 공통 예외 핸들러
register_exception_handlers(app)

# 벤더 라우터 등록
# deid(KPST)는 루트 경로 규격이므로 prefix 없이 등록한다.
app.include_router(deid.router, tags=["deid"])
app.include_router(vlm.router, tags=["vlm"])
app.include_router(augment.router, tags=["augment"])


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
