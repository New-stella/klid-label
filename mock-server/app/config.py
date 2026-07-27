"""
mock-server 환경 설정.

환경변수 기반 설정 (pydantic-settings). 접두사 `MOCK_`.
외부 벤더(KPST 비식별화 / IntelliVIX VLM / 증강 AI) 서버를 흉내내는 목 서버이므로
인증/DB 설정은 없으며 시뮬레이션 파라미터와 CORS만 관리한다.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """애플리케이션 환경 설정."""

    model_config = SettingsConfigDict(
        env_prefix="MOCK_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # 서버 바인딩
    host: str = Field(default="0.0.0.0", description="바인드 호스트")
    port: int = Field(default=9400, ge=1, le=65535, description="바인드 포트")

    # 시뮬레이션 파라미터
    sim_speed_factor: float = Field(
        default=1.0,
        gt=0.0,
        description="진행률 시뮬레이션 배속. 클수록 progressRate가 빨리 100에 도달",
    )
    callback_delay_seconds: float = Field(
        default=2.0,
        ge=0.0,
        description="VLM 콜백 전송 지연(초) — Phase 3에서 사용",
    )

    # KPST 비식별 더미 출력 파일 생성
    write_output_files: bool = Field(
        default=True,
        description=(
            "POST /project 시 {export_path}/{마스킹명} 에 더미 비식별 출력 파일을 생성할지 여부. "
            "우리 BE 의 무결성 검증(존재+크기>0)을 통과시켜 e2e 파이프라인을 여는 용도. "
            "실제 생성은 write_output_files=True 그리고 output_base 설정 둘 다일 때만"
        ),
    )
    output_base: str = Field(
        default="",
        description=(
            "출력 쓰기 허용 루트(콤마 구분 다중 허용). 설정 시 export_path 가 resolve 후 이 base 중 "
            "하나의 하위일 때만 파일을 쓴다(경로순회/임의 절대경로 쓰기 차단). "
            "BE co-locate 산출(Phase 5A)에서 비식별 export_path 가 dirname(원본)/{rawSn}/deid/ 이므로 "
            "BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값으로 맞춘다. "
            "미설정('')이면 fail-closed — 어떤 파일도 생성하지 않는다(HIGH-1)"
        ),
    )

    # CORS — 콤마 구분 문자열 또는 리스트 모두 허용
    cors_origins: str = Field(
        default="*",
        description="콤마로 구분된 오리진 목록. prd에서는 Spring Boot 도메인만 허용",
    )

    def cors_origins_list(self) -> list[str]:
        """콤마 구분 문자열을 오리진 리스트로 변환한다."""
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    """싱글톤 Settings 인스턴스 (lru_cache)."""
    return Settings()


def reload_settings() -> Settings:
    """환경변수가 바뀐 경우 강제 재로딩 (테스트용)."""
    get_settings.cache_clear()
    return get_settings()
