"""
ai-server 환경 설정.

환경변수 기반 설정 (pydantic-settings). AI_MOCK_MODE=true 일 때만 실제 모델 로드 없이
고정 응답을 반환한다 — 기본값은 False (실제 모델 사용). 테스트 환경에서는 반드시
명시적으로 AI_MOCK_MODE=true 를 설정해야 한다 (tests/conftest.py 참조).
"""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """애플리케이션 환경 설정."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # 동작 모드
    ai_mock_mode: bool = Field(
        default=False,
        description=(
            "mock 모드 활성화 여부. 기본값 False — 실제 모델/가중치 사용. "
            "테스트/개발에서 모델 로드 없이 고정 응답을 받으려면 "
            "AI_MOCK_MODE=true 로 명시적으로 활성화해야 한다."
        ),
    )
    ai_device: str = Field(default="cpu", description="cuda | cpu")

    # 탐지 백엔드 선택 (전환 가능)
    #  - "yolox"  (기본): YOLOX (ONNX Runtime) + ByteTrack — ultralytics 대체 신규 백엔드
    #  - "rtdetr"       : RT-DETRv2 (transformers) + ByteTrack (supervision)
    # 잘못된 값은 resolved_detector_backend() 에서 안전하게 "yolox" 로 정규화한다(서버 기동 안전).
    detector_backend: str = Field(
        default="yolox",
        description='탐지/트래킹 백엔드. "yolox" | "rtdetr" (기본 yolox)',
    )
    # RT-DETR 모델 ID는 설정값(사용자 요청 입력 아님)이며, 알려진 화이트리스트만 허용한다.
    rtdetr_model_id: str = Field(
        default="PekingU/rtdetr_v2_r50vd",
        description="RT-DETRv2 HuggingFace 모델 ID (설정 기반 — 임의 reflection 로드 금지)",
    )

    # 보안/제한
    max_image_size_mb: int = Field(default=10, ge=1, le=100, description="image_b64 최대 크기(MB)")

    # 모델 가중치 경로 (mock 모드에서는 무시)
    # YOLOX ONNX 가중치 경로 (설정 기반 — 사용자 입력 reflection 금지).
    # 부재 시 yolox_loader 는 mock 응답 (mock_reason=weights_missing).
    yolox_weights_path: str = Field(
        default="./weights/yolox_s.onnx",
        description="YOLOX ONNX 모델 가중치 경로 (ONNX Runtime 세션 로드 대상).",
    )
    # Meta 공식 sam2(Apache-2.0) HF 모델 ID (설정 기반 — 사용자 입력 reflection 금지).
    # SAM2ImagePredictor.from_pretrained 로 HF 에서 자동 다운로드한다.
    sam2_model_id: str = Field(
        default="facebook/sam2-hiera-tiny",
        description="Meta SAM2 HF 모델 ID (설정 기반 — 사용자 입력 reflection 금지)",
    )
    # 로컬 ckpt 대안 경로(HF 미사용/오프라인 시 후속 확장용). 현재 HF from_pretrained 경로를
    # 우선 사용하므로 미사용 상태로 유지한다(Phase 4 에서 로컬 ckpt 폴백 검토).
    sam2_weights_path: str = Field(default="./weights/sam2_t.pt")
    vlm_model_name: str = Field(default="openai/clip-vit-base-patch32")

    # CORS
    cors_allow_origins: str = Field(
        default="*",
        description="콤마로 구분된 오리진 목록. prd에서는 Spring Boot 도메인만 허용",
    )

    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.cors_allow_origins.split(",") if o.strip()]

    def resolved_detector_backend(self) -> str:
        """알려진 백엔드만 반환 — 잘못된 값은 "yolox" 로 안전 폴백."""
        backend = (self.detector_backend or "").strip().lower()
        return backend if backend in {"yolox", "rtdetr"} else "yolox"


_settings: Settings | None = None


def get_settings() -> Settings:
    """싱글톤 Settings 인스턴스."""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reload_settings() -> Settings:
    """환경변수가 바뀐 경우 강제 재로딩 (테스트용)."""
    global _settings
    _settings = Settings()
    return _settings
