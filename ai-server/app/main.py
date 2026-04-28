"""
AI 추론 경량 서버.

역할 분리:
- Spring Boot 백엔드: 인증/인가, DB(klid_system), 배치 오케스트레이션, 라벨 CRUD, 외부 연동
- 본 서버: YOLO/SAM2/VLM 추론만 담당. HTTP로 호출되어 결과 JSON 반환
- 참고: CVAT Nuclio 함수 핸들러 패턴 (docs/analysis/portable-modules/05-nuclio-function-template.md)
"""

from fastapi import FastAPI

from app.routers import yolo, sam2, vlm

app = FastAPI(title="klid-la AI 추론 서버", version="0.1.0")

app.include_router(yolo.router, prefix="/infer/yolo", tags=["yolo"])
app.include_router(sam2.router, prefix="/infer/sam2", tags=["sam2"])
app.include_router(vlm.router, prefix="/infer/vlm", tags=["vlm"])


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
