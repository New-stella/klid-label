"""
AI 추론 경량 서버.

역할 분리:
- Spring Boot 백엔드: 인증/인가, DB(klid_system), 배치 오케스트레이션, 라벨 CRUD, 외부 연동
- 본 서버: YOLO/SAM2/VLM 추론만 담당. HTTP로 호출되어 결과 JSON 반환
- 참고: CVAT Nuclio 함수 핸들러 패턴 (docs/analysis/portable-modules/05-nuclio-function-template.md)
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.exceptions import register_exception_handlers
from app.middleware.request_id import RequestIdMiddleware
from app.models import yolox_loader
from app.models.sam2_loader import get_sam2_model
from app.models.vlm_loader import get_vlm_model
from app.routers import sam2, vlm, yolo
from app.startup_guard import verify_deployment_settings

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """startup: 설정 가드 → 모델 싱글톤 워밍업. MOCK 모드면 워밍업은 즉시 반환."""
    settings = get_settings()
    # 위험한 설정 조합(배포 환경 + mock 모드)은 워밍업 이전에 기동을 거부한다(fail-closed).
    verify_deployment_settings(settings)
    logger.info(
        "[AI] startup mock_mode=%s device=%s max_image_mb=%d",
        settings.ai_mock_mode,
        settings.ai_device,
        settings.max_image_size_mb,
    )
    # 탐지 백엔드 워밍업 — YOLOX (ONNX Runtime) 싱글톤을 미리 로드한다.
    yolox_loader.get_yolox_model()
    get_sam2_model()
    get_vlm_model()
    yield
    logger.info("[AI] shutdown")


app = FastAPI(title="klid-la AI 추론 서버", version="0.1.0", lifespan=lifespan)

# 미들웨어 — RequestIdMiddleware는 가장 먼저 추가하여 외곽에 위치하도록 함
app.add_middleware(RequestIdMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=get_settings().cors_origins_list(),
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# 공통 예외 핸들러
register_exception_handlers(app)

app.include_router(yolo.router, prefix="/infer/yolo", tags=["yolo"])
app.include_router(sam2.router, prefix="/infer/sam2", tags=["sam2"])
app.include_router(vlm.router, prefix="/infer/vlm", tags=["vlm"])


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
