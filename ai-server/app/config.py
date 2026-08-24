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

    # 배포 환경 표식 — 백엔드와 동일한 ENV 신호를 재사용한다(새 환경변수 발명 금지).
    # stg|prd 이면 app.startup_guard 가 위험한 설정 조합(mock 모드)에서 기동을 거부한다.
    env: str = Field(
        default="",
        description="배포 환경 표식(local|dev|stg|prd). stg/prd 는 배포 취급 — mock 모드 기동 거부.",
    )

    # 탐지 백엔드는 YOLOX (ONNX Runtime) + ByteTrack 단일 백엔드로 일원화됨.
    # (구 RT-DETRv2 백엔드는 torch↔torchaudio ABI 불일치로 제거 — YOLOX 로 통합)

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
    # VLM 추론 본체는 외부 서비스이며 이 서버의 vlm 라우터는 그 어댑터다.
    # 아래 값은 아직 어떤 모델도 적재하지 않는다 — 로더가 목 응답으로 떨어지며 이 이름을 로그에만 남긴다.
    # 그래서 이 값을 보고 "실제로 쓰는 모델"로 읽으면 안 된다.
    vlm_model_name: str = Field(
        default="openai/clip-vit-base-patch32",
        description="VLM 로더가 목 응답으로 떨어질 때 로그에 남기는 이름. 실제 적재 대상이 아니다.",
    )

    # CORS
    cors_allow_origins: str = Field(
        default="*",
        description="콤마로 구분된 오리진 목록. prd에서는 Spring Boot 도메인만 허용",
    )

    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.cors_allow_origins.split(",") if o.strip()]


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
