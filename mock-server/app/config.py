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

    # ── 생성형 AI(증강) 목 — 「생성형 AI API 연동명세서 v1.1」 ─────────
    genai_step_delay_sec: float = Field(
        default=2.0,
        ge=0.0,
        description=(
            "작업 단계(전처리→추론→후처리→완료) 사이 지연(초). 각 단계마다 webhook 을 발사한다. "
            "0 이면 즉시 진행(테스트용)"
        ),
    )
    genai_output_base: str = Field(
        default="",
        description=(
            "결과 파일 쓰기 허용 루트. 결과는 {base}/genai/{job_id}/ 하위에만 생성한다. "
            "미설정('')이면 fail-closed — 결과 파일을 만들지 않고 작업을 "
            "FAILED(RESULT_SAVE_FAILED)로 종결한다(CWE-22 임의경로 쓰기 차단)"
        ),
    )
    genai_input_base: str = Field(
        default="",
        description=(
            "입력 파일(input_files[].file_path) 허용 루트. 미설정 시 genai_output_base 를 "
            "사용한다. 이 루트를 벗어나는 경로는 400 INVALID_PARAMETER(CWE-22)"
        ),
    )
    genai_callback_allow_hosts: str = Field(
        default=(
            "localhost:8080,127.0.0.1:8080,[::1]:8080,host.docker.internal:8080,"
            "klid-backend:8080,backend:8080"
        ),
        description=(
            "callback_url / status-sync 대상 allowlist(콤마 구분). 항목은 ``host:port`` 또는 "
            "``host`` 형태이며, 포트를 적으면 그 포트만 허용하고 포트를 생략하면 모든 포트를 "
            "허용한다(하위호환). IPv6 는 ``[::1]:8080`` 처럼 대괄호로 감싼다. 스킴은 http|https "
            "만 허용한다. 목록 밖은 400 INVALID_PARAMETER(SSRF, CWE-918). "
            "빈값이면 fail-closed(모두 차단)"
        ),
    )
    genai_callback_path_prefixes: str = Field(
        default="",
        description=(
            "callback_url / status-sync 대상 경로 접두사 allowlist(콤마 구분). 빈값이면 경로를 "
            "제한하지 않는다. 설정 시 URL path 가 접두사 중 하나로 시작해야 한다(SSRF 심층 방어)"
        ),
    )
    genai_self_host_aliases: str = Field(
        default="",
        description=(
            "목 서버 자신을 가리키는 추가 호스트 별칭(콤마 구분). 이 별칭 + 루프백/바인드 호스트 "
            "이면서 포트가 목 서버 포트와 같은 대상은 allowlist 에 있어도 거부한다(자기 SSRF 차단)"
        ),
    )
    genai_status_sync_url: str = Field(
        default="",
        description=(
            "③ 상태 동기화 대상 **base URL**. 목은 여기에 /api/genai/jobs/{job_id}/status-sync "
            "를 붙여 POST 한다. 미설정('')이면 status-sync 비활성(목이 주소를 유추하지 않음)"
        ),
    )
    genai_event_types: str = Field(
        default="",
        description=(
            "허용 evnt_type 목록(콤마 구분). 빈값이면 검증하지 않고 모두 허용한다. "
            "설정 시 목록 밖 값은 400 UNSUPPORTED_EVENT_TYPE"
        ),
    )
    genai_max_input_bytes: int = Field(
        default=5_368_709_120,  # 5GiB
        ge=1,
        description="입력 파일 1건 크기 상한(바이트). 초과 시 413 GA-MEDIA-001",
    )
    genai_webhook_max_attempts: int = Field(
        default=2,
        ge=1,
        le=10,
        description="webhook 전송 시도 횟수 상한(무한 재시도 금지)",
    )
    genai_webhook_retry_delay_sec: float = Field(
        default=0.5,
        ge=0.0,
        description="webhook 재시도 간 지연(초). 0 이면 즉시 재시도(테스트용)",
    )
    genai_max_body_bytes: int = Field(
        default=1_048_576,  # 1MiB
        ge=1,
        description=(
            "요청 본문 크기 상한(바이트). Content-Length 선검사 + 스트리밍 누적 검사로 "
            "초과분은 본문을 전부 메모리에 올리기 전에 413 GA-MEDIA-001 로 거부한다(CWE-770)"
        ),
    )
    genai_max_prompt_bytes: int = Field(
        default=65_536,  # 64KiB
        ge=1,
        description="prompt 직렬화 크기 상한(바이트). 초과 시 400 INVALID_METADATA",
    )
    genai_max_jobs: int = Field(
        default=1_000,
        ge=1,
        description=(
            "인메모리 작업 저장소 보관 상한(건). 초과하면 가장 오래된 작업부터 만료(FIFO)해 "
            "메모리 무제한 증가를 막는다(CWE-770)"
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

    def genai_callback_allow_hosts_list(self) -> list[str]:
        """callback allowlist 문자열을 ``host`` / ``host:port`` 항목 리스트로 변환한다."""
        return [h.strip().lower() for h in self.genai_callback_allow_hosts.split(",") if h.strip()]

    def genai_callback_path_prefixes_list(self) -> list[str]:
        """콜백 대상 경로 접두사 목록. 빈 리스트면 '경로 제한 없음'."""
        return [p.strip() for p in self.genai_callback_path_prefixes.split(",") if p.strip()]

    def genai_self_host_aliases_set(self) -> set[str]:
        """목 서버 자신으로 간주할 호스트 별칭 집합(루프백 + 바인드 호스트 + 설정 별칭)."""
        aliases = {"localhost", "127.0.0.1", "::1", "0.0.0.0", "host.docker.internal"}
        if self.host.strip():
            aliases.add(self.host.strip().lower())
        aliases.update(
            a.strip().lower() for a in self.genai_self_host_aliases.split(",") if a.strip()
        )
        return aliases

    def genai_event_types_set(self) -> set[str]:
        """허용 evnt_type 집합. 빈 집합이면 '검증 안 함'을 뜻한다."""
        return {e.strip() for e in self.genai_event_types.split(",") if e.strip()}

    def genai_effective_input_base(self) -> str:
        """입력 허용 루트 — 미설정 시 출력 루트를 사용한다."""
        return self.genai_input_base or self.genai_output_base


@lru_cache
def get_settings() -> Settings:
    """싱글톤 Settings 인스턴스 (lru_cache)."""
    return Settings()


def reload_settings() -> Settings:
    """환경변수가 바뀐 경우 강제 재로딩 (테스트용)."""
    get_settings.cache_clear()
    return get_settings()
