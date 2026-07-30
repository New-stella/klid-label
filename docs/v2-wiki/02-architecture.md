# 02. 아키텍처

> 출처: CLAUDE.md(아키텍처 원칙·공통 인프라), D3 컴포넌트설계서, 코드(`backend/.../common`)
> 관련: [01 개요](01-system-overview.md) · [18 DB](18-database.md) · [19 외부 시스템](19-external-security-cvat.md)

## 2.1 워크스페이스 구조 (모노레포)

```
klid-label/  (단일 git)
├── backend/     # Spring Boot 메인 (Java 17) — kr.co.cudo.authoring.{domain}
├── ai-server/   # Python FastAPI 추론 (YOLO/SAM2/VLM, stateless)
├── frontend/    # React + Vite + TS
└── docs/        # requirements/ design/ analysis/ contracts/ v1-wiki/ v2-wiki/
```

- **통신**: Spring Boot → ai-server는 HTTP(WebClient) 호출 (Nuclio 스타일 함수 엔드포인트 분리)

## 2.2 레이어 (백엔드)

```
Controller → Service → Repository (+ QueryDSL 보조)
```
- Controller는 DTO(Request/Response)만 — Entity 직접 노출 금지
- Service에 비즈니스 로직, `@Transactional(readOnly)` 기본
- Repository는 JPA/QueryDSL만 (비즈니스 로직 금지)
- 도메인 중심 패키지: `kr.co.cudo.authoring.{domain}.{controller|service|repository|entity|dto}`

## 2.3 백엔드 도메인 (코드 기준 22개)

| 도메인 | 역할 | 위키 |
|--------|------|------|
| `auth` | JWT 검증, 역할 클레임, 세션, WorkLock | [03](03-auth-roles.md) |
| `user` | 사용자 관리 | [03](03-auth-roles.md) |
| `video` | 영상 메타·스트리밍·등록 | [05](05-video-management.md) |
| `marking` | 자동/수동 마킹 | [06](06-marking.md) |
| `batch` | 파이프라인 오케스트레이션(52 파일) | [07](07-batch-pipeline.md) |
| `deident` | 비식별 컨트롤러 | [08](08-deidentification.md) |
| `meta` | 메타 검수 | [09](09-vlm-timeseries.md) |
| `label`, `preset` | 라벨 CRUD·속성·프리셋 | [10](10-labeling.md) |
| `review`, `assignment` | 검수, 작업 배정 | [12](12-review-assignment.md) |
| `version` | 버전 스냅샷·diff·rollback | [13](13-version-control.md) |
| `augment` | 외부 증강·검수 | [14](14-augmentation.md) |
| `controlnotify` | 관제 통지·조회 API | [15](15-control-notify.md) |
| `portal` | 포털 라벨 | [16](16-portal.md) |
| `stats` | 통계 | [17](17-statistics.md) |
| `webhook` | 비식별/증강/VLM 결과 수신 + 멱등성 | [19](19-external-security-cvat.md) |
| `sysconfig` | 시스템 설정(화이트리스트) | [10](10-labeling.md) |
| `notification`, `observability`, `quality`, `project`, `common` | 보조·공통 | — |

## 2.4 공통 인프라 (`common.*`)

| 컴포넌트 | 역할 |
|----------|------|
| `common.response.ApiResponse<T>` | 모든 API 표준 응답 (`{success, data, message, errorCode}`) |
| `common.exception.{ErrorCode, CustomException, GlobalExceptionHandler}` | 표준 예외 처리 |
| `common.security.JwtAuthenticationFilter` | 관제/포털 JWT 디코드 → `TokenClaims` → SecurityContext |
| `common.security.SecurityConfig` | 역할 기반 접근 제어 |
| `common.datasource.{ControlDataSourceConfig, PortalDataSourceConfig}` | **듀얼 EntityManager/TxManager** (`@ControlRepo`/`@PortalRepo`) |
| `common.logging.RequestIdFilter` + Logback JSON | traceId(MDC) + 민감 필드 마스킹 |
| `common.client.*` | DeidentifyClient/AiServerClient/VlmClient/ControlNotifyClient/ExternalAugmentClient (Resilience4j) |
| `common.config.AsyncConfig` | `@Async` 풀(`batchAsyncExecutor`·`portalExtractExecutor`) + **외부연동 제출 완료 핸들러 전용 풀 3종**(`vlmSubmitExecutor`/`kpstSubmitExecutor`/`augmentSubmitExecutor` + 동명 Reactor `Scheduler` 빈) |
| `common.async.SubmitSignalDispatch` | 논블로킹 제출의 완료 신호를 전용 풀에 **명시 투입** — `publishOn` 만 쓰면 풀 거부가 이벤트 루프에서 흘러 JPA 가 루프에서 돌아간다 |

> **외부연동 제출은 모두 논블로킹**(2026-07-30) — VLM·KPST·증강 세 연동 모두 수락(ACK) 왕복조차 스레드를 점유하지 않는다. 선커밋 → subscribe 후 즉시 반환 → 전용 풀의 완료 핸들러가 ACK/실패 기록 → 무신호는 각 연동의 **기존** 회수기가 회수. 전용 풀은 `batchAsyncExecutor`(CallerRuns)와 분리하고 포화 정책을 **AbortPolicy** 로 둔다 — CallerRuns 는 포화 시 reactor-netty 이벤트 루프에서 JPA 를 실행해 논블로킹 이득을 정확히 되돌린다. 세 연동의 풀도 서로 분리한다(한쪽 벤더 지연이 다른 쪽 ACK 기록을 굶기지 않게). 상세 규칙 → [07 §7.2-1](07-batch-pipeline.md)

## 2.5 듀얼 데이터소스

- **Control DB** (`klid_at` 스키마, PostgreSQL) — 저작도구 LS_* + 관제 MNG_* 참조. `@ControlRepo`
- **Portal DB** — 포털 채널 데이터 Load. `@PortalRepo`

## 2.6 AI 파이프라인 분리 원칙

- **Spring Boot**: 인증/DB/오케스트레이션/큐/라벨 CRUD/외부 연동
- **ai-server**: stateless 추론만 (GPU 자원 경합 격리). **다중 인스턴스 수평 확장 가능**
- 호출은 `AiServerClient` 단일 빈, 재시도/타임아웃은 Resilience4j
- CVAT Nuclio 함수 패턴 포팅

## 2.7 배포 특성

- **주 서버 2노드 Active-Active 이중화** — 관제서버와 동일 서버 공동 배치, 앱 2노드 동시 기동
- Spring Boot + Quartz는 **Quartz 클러스터링 적용**(PostgreSQL JobStore 의 `QRTZ_LOCKS` 행 락으로 트리거를 1회만 발화). 공통 기본값은 `org.quartz.jobStore.isClustered=${QUARTZ_CLUSTERED:false}`(단일 노드 기준)이고, **stg/prd 프로파일이 `true` 로 override** 한다 — 이 두 환경에서 꺼져 있으면 `QuartzClusteringGuard` 가 **기동을 거부**한다. local/dev 는 단일 노드라 off 허용. ⚠ 클러스터 모드는 노드 간 시계 동기화(NTP/chrony) 필수
- DB는 별도 DB 서버(이중화), 파일 스토리지는 별도 NAS 서버(공유 마운트)
- ai-server(YOLO/SAM2)는 주 서버 내 별도 프로세스(무상태) — 토폴로지와 무관하게 다중 인스턴스 수평 확장
- 환경: local/dev/stg/prd (Spring Profile)

> 컴포넌트 ID 체계(`KLID-AT-CO-*`)·클래스(`KLID-AT-CL-*`)는 D3/D1 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그).
