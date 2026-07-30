"""
klid-la 외부 벤더 목(mock) 서버.

우리 BE(Spring Boot)가 외부 벤더 서버 개발 완료 전에 실연동 테스트를 하기 위한 목 서버다.
서버 1개에 벤더별 라우터로 구성한다:
- deid   : KPST 비식별화 (루트 경로 규격 — prefix 없음)
- vlm    : IntelliVIX Video VLM 시계열
- augment: 증강 AI (WINTER/NIGHT/RAIN) — Phase 4 확장
- control: 관제지원시스템 inbound 통지 (notify-completed / notify-updated)

인증/DB 없이 인메모리 상태(app.state)만으로 동작한다.
"""

from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.exceptions import register_exception_handlers
from app.middleware.request_id import RequestIdMiddleware
from app.routers import augment, control, deid, vlm
from app.services import deid_sim, genai_sim

LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s - %(message)s"


def configure_logging() -> None:
    """애플리케이션 로거 출력을 stdout 으로 연결한다.

    uvicorn 의 기본 로깅 설정(``uvicorn`` / ``uvicorn.access`` 로거)은 **root 로거를 건드리지
    않는다**. 그래서 이 앱의 ``logging.getLogger(__name__)`` 로거들(``app.services.deid_sim`` 등)은
    핸들러 없는 root 로 전파되어 INFO 가 통째로 버려지고, WARNING 이상만 ``logging.lastResort``
    로 포맷 없이 새어나갔다 — 즉 **경계 위반 WARN·콜백 거부 같은 방어 로그가 동작해도
    ``docker logs`` 에서 관측되지 않았다**(관측성 결함).

    ``basicConfig`` 는 root 에 핸들러가 이미 있으면 아무 것도 하지 않으므로(``force`` 미사용),
    uvicorn 이 자체 로거에 붙인 핸들러와 중복 출력되지 않고 pytest 의 caplog 핸들러도 덮어쓰지
    않는다.
    """
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, stream=sys.stdout)


configure_logging()

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """startup/shutdown 간단 로깅 + 백그라운드 태스크 정리."""
    settings = get_settings()
    logger.info(
        "[MOCK] startup host=%s port=%d sim_speed=%.2f callback_delay=%.2fs",
        settings.host,
        settings.port,
        settings.sim_speed_factor,
        settings.callback_delay_seconds,
    )
    # MEDIUM-1 — 이전 기동이 강제 종료(SIGKILL/compose down/OOM)돼 남은 워터마킹 임시 산출물을
    # 정리한다. 기동 시점에는 우리 인코딩이 하나도 진행 중이 아니므로 남은 것은 전부 고아다.
    # 실패해도 기동을 막지 않는다(목 안정성 우선).
    try:
        deid_sim.sweep_orphan_temp_files(settings.output_base)
    except Exception:  # noqa: BLE001 — 정리 실패가 기동을 막으면 안 된다
        logger.warning("[MOCK][KPST] 고아 임시 산출물 정리 실패 — 기동은 계속한다")
    yield
    # 백그라운드 태스크 누수 방지 — 종료 시 모두 취소·정리한다.
    # (KPST 산출 태스크는 구속 원칙 "외부연동은 모두 비동기"에 따라 요청과 분리돼 있다.)
    await deid_sim.cancel_all_productions()
    await genai_sim.cancel_all_tasks()
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
# control(관제)은 관제 계약 경로(/api/data-set/v2/...)를 그대로 노출하므로 prefix 없이 등록한다.
app.include_router(control.router, tags=["control"])


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
