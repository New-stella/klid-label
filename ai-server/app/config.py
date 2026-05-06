"""
ai-server 환경 설정.

환경변수 기반 설정 (pydantic-settings). MOCK_MODE=true이면 실제 모델 로드 없이
고정 응답을 반환하여 테스트/개발 환경에서도 동작하도록 한다.
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
    ai_mock_mode: bool = Field(default=True, description="실제 모델 로드 대신 mock 응답 반환")
    ai_device: str = Field(default="cpu", description="cuda | cpu")

    # 보안/제한
    max_image_size_mb: int = Field(default=10, ge=1, le=100, description="image_b64 최대 크기(MB)")

    # 모델 가중치 경로 (mock 모드에서는 무시)
    yolo_weights_path: str = Field(default="./weights/yolov8n.pt")
    sam2_weights_path: str = Field(default="./weights/sam2.pt")
    vlm_model_name: str = Field(default="openai/clip-vit-base-patch32")

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
