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
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.exceptions import register_exception_handlers
from app.middleware.request_id import RequestIdMiddleware
from app.models import yolox_loader
from app.models.sam2_loader import warmup_sam2_models
from app.models.vlm_loader import get_vlm_model
from app.routers import sam2, vlm, yolo
from app.slots import get_slot_registry, shutdown_slots
from app.startup_guard import verify_deployment_settings, verify_models_available

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """startup: 설정 가드 → 모델 싱글톤 워밍업. MOCK 모드면 워밍업은 즉시 반환."""
    settings = get_settings()
    # 위험한 설정 조합(배포 환경 + mock 모드)은 워밍업 이전에 기동을 거부한다(fail-closed).
    verify_deployment_settings(settings)
    # 배포 환경에서 모델이 없으면 가짜 응답 형상이 되므로 기동을 거부한다.
    verify_models_available(settings)
    logger.info(
        "[AI] startup mock_mode=%s device=%s max_image_mb=%d",
        settings.ai_mock_mode,
        settings.ai_device,
        settings.max_image_size_mb,
    )
    # 용도별 실행 슬롯(배치 전용 / 화면 전용)을 먼저 세운다 — 모델 워밍업이 슬롯 수에 달렸다.
    get_slot_registry()
    # 탐지 백엔드 워밍업 — YOLOX (ONNX Runtime) 세션은 **슬롯 공유**라 하나만 로드한다.
    yolox_loader.get_yolox_model()
    # SAM2 predictor 는 **슬롯마다 별도 인스턴스** — 공유하면 이미지 임베딩이 서로 덮인다(ADR-056).
    warmup_sam2_models()
    get_vlm_model()
    yield
    shutdown_slots()
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
    """살아있는가만 답한다.

    ⚠ **여기에 부하를 섞지 않는다.** "살아있는가"와 "여유가 있는가"가 한 값에 엉키면, 포화를
    이유로 헬스가 내려가 앞단이 **살아있는 노드를 이탈시키고** 남은 노드에 전부 몰린다 —
    페일오버 사고를 스스로 만드는 셈이다. 부하는 :func:`internal_load` 가 따로 답한다.
    """
    return {"status": "ok"}


@app.get("/internal/load")
async def internal_load() -> dict[str, object]:
    """슬롯별 부하 — 앞단이 노드를 고를 근거. 내부망 전용이며 숫자만 나간다.

    슬롯 분리 뒤에는 **대기가 밀렸다는 사실이 응답 지연으로 드러나지 않으므로**(대기 120건에서도
    화면 응답이 300ms 아래였다) 포화를 읽으려면 이 경로가 필요하다.

    ★ **이 경로는 슬롯을 거치지 않는다.** 슬롯 뒤에 두면 포화 시 이 응답도 함께 늦어져 *정확히
    필요한 순간에* 못 읽는다. 이벤트 루프에서 바로 답한다.

    값은 잠금 없이 읽은 **근사값**이다(정확한 스냅샷을 만들려고 잠그면 이 관측이 슬롯을
    방해한다). 라벨 내용·경로·개인정보는 절대 싣지 않는다 — 슬롯별 숫자 셋뿐이다.

    @design ADR-056
    """
    return {
        "slots": get_slot_registry().load_snapshot(),
        "observed_at": datetime.now(timezone.utc).isoformat(),
    }
