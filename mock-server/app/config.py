"""
mock-server 환경 설정.

환경변수 기반 설정 (pydantic-settings). 접두사 `MOCK_`.
외부 벤더(KPST 비식별화 / IntelliVIX VLM / 증강 AI) 서버를 흉내내는 목 서버이므로
인증/DB 설정은 없으며 시뮬레이션 파라미터와 CORS만 관리한다.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

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

    describe_omit_situation: bool = Field(
        default=False,
        description=(
            "묘사(describe) 콜백 서술에서 「상황」 라벨 줄을 통째로 뺄지 여부. "
            "우리 BE 는 묘사 전문에서 그 줄만 파싱해 이벤트 어노테이션의 사고 단계 1단계를 채우고, "
            "줄이 없으면 채우지 않는다(빈 값도 넣지 않는다). 그 미채움 분기를 로컬·dev 에서 "
            "실동작으로 확인하려면 「상황」이 없는 응답을 낼 수 있어야 한다. "
            "기본값 False — 평소에는 규격 형식대로 「상황」을 포함한다"
        ),
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
    deid_max_projects: int = Field(
        default=1_000,
        ge=1,
        description=(
            "인메모리 비식별 프로젝트 보관 상한(건). 초과하면 가장 오래된 프로젝트부터 "
            "만료(FIFO)하며 딸린 데이터셋/작업로그도 함께 정리한다. 목은 무인증이라 상한이 "
            "없으면 POST /project 반복만으로 메모리가 무제한 증가한다(CWE-770). "
            "생성형 AI 저장소의 MOCK_GENAI_MAX_JOBS 와 같은 축이다"
        ),
    )
    input_base: str = Field(
        default="",
        description=(
            "복사 원본(input_path) 읽기 허용 루트. 목 서버는 인증이 없어 input_path 를 임의로 "
            "지정할 수 있으므로, 이 루트 밖의 파일은 읽지 않고 해당 산출을 실패(procState=99)로 "
            "종결한다(임의 파일 노출 + GB급 반복 복사에 의한 디스크 고갈 차단 — CWE-22/CWE-400). "
            "대체 산출물을 최종 경로에 남기는 안은 폐기됐다 — 읽지도 못한 원본을 BE 무결성에 "
            "통과시켜 '비식별 완료'로 승격시키는 위장 산출물이고(CWE-345), no-overwrite 라 그 "
            "이름이 이후 어떤 재시도로도 대체되지 않는다. "
            "미설정('')이면 output_base 의 상위(= storage 루트)를 자동 사용한다"
        ),
    )

    # 콜백(outbound POST) 허용 호스트 — SSRF(CWE-918) 방어.
    #   VLM verify/describe 는 요청자가 지정한 callback_url 로 서버측 outbound POST 를 발사한다.
    #   인증이 없는 목 서버이므로 호스트를 제한하지 않으면 내부망 포트 스캔/요청 위조가 성립한다.
    callback_allowed_hosts: str = Field(
        default="klid-backend,localhost,127.0.0.1",
        description=(
            "콤마로 구분된 callback_url 허용 호스트 목록. 목록 밖 호스트는 400 으로 거부하고 "
            "outbound 를 발사하지 않는다. 기본값은 저작도구 BE(컨테이너명) + 루프백"
        ),
    )

    # ── 생성형 AI(증강) 목 — 「생성형 AI API 연동명세서 v1.3」 ─────────
    genai_step_delay_sec: float = Field(
        default=2.0,
        ge=0.0,
        description=(
            "작업 단계(전처리→추론→후처리→완료) 사이 지연(초). 각 단계마다 webhook 을 발사한다. "
            "0 이면 즉시 진행(테스트용)"
        ),
    )
    genai_max_concurrent_jobs: int = Field(
        default=2,
        ge=1,
        description=(
            "동시에 **처리**할 수 있는 작업 수(내부 큐 슬롯). 실제 증강 벤더가 요청을 접수만 하고 "
            "내부 큐로 순차 처리하는 동작을 모사한다. 초과분은 접수(202 RECEIVED)된 뒤 FIFO 로 "
            "대기하다 슬롯이 나면 RUNNING 으로 전이한다. 접수 응답 자체는 큐 대기와 무관하게 "
            "즉시 반환한다(명세서 §4.1). 값을 키우면 병렬 처리량이 늘고, 1 로 두면 완전 순차다. "
            "MOCK_GENAI_STEP_DELAY_SEC(단계 지연)과는 별개 축이다"
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
        default="FLOOD,WILDFIRE,ETC",
        description=(
            "허용 evnt_type 목록(콤마 구분). 「생성형 AI API 연동명세서 v1.3」 §4.1 의 "
            "FLOOD | WILDFIRE 에 협의된 중립값 ETC 를 더한 것이 기본값이며, 목록 밖 값은 "
            "400 UNSUPPORTED_EVENT_TYPE. 빈값으로 두어도 검증이 꺼지지 않고 "
            "SUPPORTED_EVNT_TYPES 로 fail-closed 한다. "
            "★ ETC 는 벤더와 협의가 끝난 값이고(2026-09-02 사용자 확정 · ADR-059) 저작도구가 "
            "증강 위탁에 고정 송신한다. ⚠ 다만 v1.3(갱신일 2026-08-12) 문서에는 아직 없으므로 "
            "'규격서에 없다' 는 이유로 빼지 말 것 — 개정판을 받으면 대조한다. "
            "판정 근거·성격 차이는 schemas/genai.py 의 SUPPORTED_EVNT_TYPES 주석이 정본"
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

    def callback_allowed_hosts_list(self) -> list[str]:
        """콤마 구분 문자열을 콜백 허용 호스트 리스트로 변환한다(소문자 정규화)."""
        return [h.strip().lower() for h in self.callback_allowed_hosts.split(",") if h.strip()]

    def effective_input_base(self) -> str:
        """복사 원본 읽기 허용 루트를 결정한다(콤마 구분 다중 허용).

        명시 설정(``MOCK_INPUT_BASE``)이 우선이고, 미설정이면 ``output_base`` **각 항목의**
        상위 디렉터리(= 공용 storage 루트)를 사용한다. 예) ``/app/storage/deidentified`` →
        ``/app/storage`` 이므로 BE 가 넘기는 원본 경로(``/app/storage/raw/...``)가 통과한다.
        ``output_base`` 는 co-locate 산출(Phase 5A) 이후 콤마 구분 다중 base 이므로
        항목별로 상위를 구해 중복 없이 콤마로 합친다.

        **MEDIUM-3 — 루트 붕괴 방지(CWE-22/CWE-1188)**: 상위 도출은 1단만 올라가므로
        ``output_base`` 가 ``/nas-storage`` 같은 **최상위 1단 디렉터리**면 상위가 ``/`` 가 되어
        "허용 루트 = 파일시스템 전체"로 붕괴한다. 운영 스토리지 루트가 실제로 그 형태이므로
        (CLAUDE.md), 루트로 붕괴하는 항목은 **채택하지 않고 버린다**.

        반환값이 빈 문자열이면 "허용 루트 없음"이며 소비자는 **fail-closed** 로 동작한다
        (``deid_sim.input_root_configured`` → 원본 미열람 · ``media_probe.resolve_probe_target``
        → 길이 조회 안 함). 즉 이 상황에서는 ``MOCK_INPUT_BASE`` 를 명시 설정해야 한다.
        """
        if self.input_base:
            return self.input_base
        parents: list[str] = []
        for base in (b.strip() for b in self.output_base.split(",")):
            if not base:
                continue
            parent_path = Path(base).parent
            if parent_path == parent_path.parent:
                # 파일시스템 루트('/') 로 붕괴 — 허용 루트로 채택하지 않는다(fail-closed).
                continue
            parent = str(parent_path)
            if parent not in parents:
                parents.append(parent)
        return ",".join(parents)

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
        """허용 evnt_type 집합(설정 문자열 파싱). 빈 집합이면 **'검증 안 함' 이 아니다**.

        설정(``MOCK_GENAI_EVENT_TYPES``)이 비어 있으면 소비자
        (``routers/augment.py`` 의 ``_check_event_type``)가
        ``settings.genai_event_types_set() or SUPPORTED_EVNT_TYPES`` 로 **계약 목록으로 되돌아가
        fail-closed** 한다 — 검증이 꺼져 아무 값이나 통과하는 경로는 없다.
        회귀 가드: ``tests/test_genai_jobs.py``
        (``test_evnt_type_허용목록을_비워도_계약목록으로_fail_closed``).

        ⚠ 구 docstring *"빈 집합이면 '검증 안 함' 을 뜻한다"* 는 **사실과 달랐고 실제로 사고를
        냈다** — 그 서술을 읽은 쪽이 "빈 집합이면 검증하지 않는다" 로 보고해, ETC 허용을
        설정 기본값만 고치는 반쪽 수정(설정을 비운 구성에서만 400)으로 이어질 뻔했다.
        같은 파일 ``genai_event_types`` Field description 은 처음부터 정확했다.
        """
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
