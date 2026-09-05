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

## 2.3 백엔드 도메인 (코드 기준 27개)

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"코드 기준 22개"*이며 표에 `deident`·`project` 도메인이 있고 `dataset`·`dev`·`eventtype`·`evntanno`·`notice`·`upload` 가 빠져 있던 것은 사실과 다르다. 계수 명령 `ls -d backend/src/main/java/kr/co/cudo/authoring/*/ | wc -l` → **27**. `deident`·`project` 라는 이름의 도메인 패키지는 **존재하지 않는다**(`ls backend/src/main/java/kr/co/cudo/authoring/` 결과에 없음) — 비식별 관련 코드는 `batch`(`DeidentifyStep`·`AsyncDeidentifyRunner`·`IngestDeidentifyBridge`)·`common.client`(`KpstDeidentifyClient`)·`common.security`(`DeidentifyEndpointTrustGuard`)·`label`(`DeidentReportController`·`DeidentReportService`)·`video`(`DeidentReportGate`)·`observability`(`DeidentifyHealthIndicator`)에 분산돼 있다. 근거: `reports/wiki-align-20260819/facts/F1-backend-api.md` §1.

| 도메인 | 역할 | 위키 |
|--------|------|------|
| `auth` | JWT 검증, 역할 클레임, 세션, WorkLock(21 파일) | [03](03-auth-roles.md) |
| `user` | 사용자 관리(14 파일) | [03](03-auth-roles.md) |
| `video` | 영상 메타·스트리밍·등록(61 파일) | [05](05-video-management.md) |
| `upload` | 관리 화면 TUS 재개형 업로드(15 파일, `TusUploadController` — 1차 적재 경로 아님, 폐지 예정) | [05](05-video-management.md) |
| `marking` | 자동/수동 마킹(13 파일) | [06](06-marking.md) |
| `batch` | 파이프라인 오케스트레이션 — 비식별 스텝 포함(113 파일, 도메인 중 2위 규모) | [07](07-batch-pipeline.md) |
| `meta` | 메타 검수(8 파일) | [09](09-vlm-timeseries.md) |
| `evntanno` | 이벤트 어노테이션(VQA/CoT, 10 파일, `EvntAnnoController`) | [09](09-vlm-timeseries.md) |
| `label`, `preset` | 라벨 CRUD·속성·프리셋·비식별 신고 접수(81+7 파일) | [10](10-labeling.md) |
| `eventtype` | 이벤트유형 필터·표시명 그룹핑(16 파일) | [18](18-database.md) |
| `review`, `assignment` | 검수, 작업 배정·작업 게시판(23+25 파일) | [12](12-review-assignment.md) |
| `version` | 버전 스냅샷·diff·rollback(26 파일) | [13](13-version-control.md) |
| `augment` | 외부 증강·검수(82 파일, 도메인 중 1위 규모) | [14](14-augmentation.md) |
| `controlnotify` | 관제 통지·조회 API(28 파일) | [15](15-control-notify.md) |
| `portal` | 포털 라벨·자산 업로드(53 파일) | [16](16-portal.md) |
| `notice` | 게시판(공지)(14 파일) | [20](20-notice-board.md) |
| `stats` | 통계(9 파일) | [17](17-statistics.md) |
| `dataset` | 학습데이터 Export(NIA JSON) 산출·촬영환경/개인정보 메타(74 파일, `export` 하위 36파일) | [24](24-dataset-export.md) |
| `webhook` | 비식별/증강/VLM 결과 수신 + 멱등성(22 파일) | [19](19-external-security-cvat.md) |
| `sysconfig` | 시스템 설정(화이트리스트)(20 파일) | [10](10-labeling.md) |
| `dev` | 개발/테스트 전용 유틸(12 파일, dev 토큰 발급·배치 수동 트리거 — REVIEWER 인가, prd 미배포) | — |
| `notification`, `observability`, `quality`, `common` | 보조·공통(2+12+2+133 파일) | — |

## 2.4 공통 인프라 (`common.*`)

| 컴포넌트 | 역할 |
|----------|------|
| `common.response.ApiResponse<T>` | 모든 API 표준 응답 (`{success, data, message, errorCode}`) |
| `common.exception.{ErrorCode, CustomException, GlobalExceptionHandler}` | 표준 예외 처리 |
| `common.security.JwtAuthenticationFilter` | 관제/포털 JWT 디코드 → `TokenClaims` → SecurityContext |
| `common.security.SecurityConfig` | 역할 기반 접근 제어 |
| `common.datasource.ControlDataSourceConfig` | control EntityManager/TxManager (`@ControlRepo`). ⚠ **듀얼 데이터소스는 2026-08-31 철거** — 아래 §2.5 |
| `common.logging.RequestIdFilter` + Logback JSON | traceId(MDC) + 민감 필드 마스킹 |
| `common.client.*` | `AiServerClient`/`VlmClient`/`KpstDeidentifyClient`/`ControlNotifyClient` (Resilience4j) [폐기 표기 — 아래 참조] |
| `common.config.AsyncConfig` | `@Async` 풀(`batchAsyncExecutor`·`portalExtractExecutor`) + **외부연동 제출 완료 핸들러 전용 풀 3종**(`vlmSubmitExecutor`/`kpstSubmitExecutor`/`augmentSubmitExecutor` + 동명 Reactor `Scheduler` 빈) |
| `common.async.SubmitSignalDispatch` | 논블로킹 제출의 완료 신호를 전용 풀에 **명시 투입** — `publishOn` 만 쓰면 풀 거부가 이벤트 루프에서 흘러 JPA 가 루프에서 돌아간다 |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"`common.client.*` = DeidentifyClient/AiServerClient/VlmClient/ControlNotifyClient/ExternalAugmentClient"* 는 사실과 다르다. ①`common/client/` 패키지에 **`DeidentifyClient` 라는 클래스는 없다** — 비식별 위탁 클라이언트의 실제 이름은 `KpstDeidentifyClient`(같은 패키지)다. ②`ExternalAugmentClient` 는 `common.client` 패키지가 **아니라 `augment/integration/ExternalAugmentClient.java`** 에 있다(도메인 소속 클라이언트). `common/client/` 에 실재하는 클래스는 `AiServerClient`·`VlmClient`·`KpstDeidentifyClient`·`ControlNotifyClient` 4종뿐이다(+취소·DTO 보조 클래스). 근거: `find backend/src/main/java/kr/co/cudo/authoring/common/client -maxdepth 1 -name "*.java"`.

> **외부연동 제출은 모두 논블로킹**(2026-07-30) — VLM·KPST·증강 세 연동 모두 수락(ACK) 왕복조차 스레드를 점유하지 않는다. 선커밋 → subscribe 후 즉시 반환 → 전용 풀의 완료 핸들러가 ACK/실패 기록 → 무신호는 각 연동의 **기존** 회수기가 회수. 전용 풀은 `batchAsyncExecutor`(CallerRuns)와 분리하고 포화 정책을 **AbortPolicy** 로 둔다 — CallerRuns 는 포화 시 reactor-netty 이벤트 루프에서 JPA 를 실행해 논블로킹 이득을 정확히 되돌린다. 세 연동의 풀도 서로 분리한다(한쪽 벤더 지연이 다른 쪽 ACK 기록을 굶기지 않게). 상세 규칙 → [07 §7.2-1](07-batch-pipeline.md)

## 2.5 데이터소스 — 단일 (구 「듀얼 데이터소스」, 2026-08-31 철거)

- **Control DB** (`klid_at` 스키마, PostgreSQL) — 저작도구 LS_* 소유. `@ControlRepo`. **각 배포본이 무는 DB 는 이것 하나뿐이다.**
- ⚠ **[폐기·철거 완료] Portal DB** — 저작도구 → 포털 DB 단방향 메타 복제(쓰기 전용, at-least-once)와 `@PortalRepo`·
  `PortalDataSourceConfig` 는 **2026-08-31 「저작도구와 포털은 서로의 DB 에 접근하지 않는다」 확정(구속)으로 폐기**됐고
  **같은 날 코드에서 제거**됐다. 데이터 교환은 API 로 설계한다. **되살리지 말 것.**
  ★ **근본 근거 — 저작도구는 채널별로 별도 배포되고 각 배포본이 자기 별도 PostgreSQL DB 에만 연결한다**
  (2026-08-31 확정, ADR-012). 한 배포본이 두 채널의 DB 를 함께 무는 **듀얼 데이터소스 구도 자체가 성립하지 않는다.**
  승인 자산이 포털로 흐르는 경로는 **관제 중계**다(승인 → 관제 통지 → 관제가 압축 파일로 포털 전달 → 포털이 API 로 통지).
  판정 정본은 `INT-009`·`ADR-012` → [16](16-portal.md)
- ⚠ `LS_META_REPL_OUTBOX` **테이블 정의는 남아 있다** — 드롭 마이그레이션을 분리했고 읽는 코드가 0건이라 무해하다 → [18](18-database.md)

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 두 항목 모두 정정한다.
> 1. *"Control DB 는 저작도구 LS_* + 관제 MNG_* 참조"* — **관제 공유 테이블 `MNG_*` 는 현재 코드에 하나도 남아 있지 않다.** 관제 2차에서 적재 주체가 반전(ADR-042)돼 관제는 이제 저작도구 소유 `LS_DATA_INGEST` 에 직접 INSERT 하고, 저작도구가 과거 읽던 공유 마스터 4종(`MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST`·`MNG_RESOURCE_CCTV`·`MNG_EX_LOCAL_GOV`)은 `V167` 로 DROP 됐다. 회귀 가드 `architecture/MngControlMasterTableRemovalTest`(소스 전수 스캔)가 이 4종의 재등장을 차단하며, 전수 확인 결과 `@Table(name="MNG_...")` 형태의 살아있는 JPA 매핑은 **0건**이다(`main` 소스에 남은 22건의 `MNG_` 문자열은 전부 이 제거 사실을 설명하는 javadoc/주석이다). 근거: `backend/src/test/java/kr/co/cudo/authoring/architecture/MngControlMasterTableRemovalTest.java`.
> 2. *"Portal DB — 포털 채널 데이터 Load"* — 저작도구가 포털 DB에서 데이터를 **읽어오는(Load) 것이 아니었다.** ⚠ **2026-08-31 이후로는 쓰기 축마저 철거돼 포털 DB 를 무는 경로가 0건이다** — 아래는 그 철거 이전의 실측 기록이다. 실측: 포털 라벨 조회·저장을 담당하는 `PortalLabelService` 는 `@Transactional(value = "controlTransactionManager", ...)` 로 **Control DB** 를 사용한다. `@PortalRepo` 를 실제로 쓰는 리포지토리는 `PortalDatasetVideoMetaRepository` **1건뿐**이며, 그 방향은 `dataset/worker/PortalMetaReplicaWriter`·`MetaReplicationWorker` 가 수행하는 **저작도구 → 포털 DB 메타 복제(쓰기)** 다. 즉 Portal DB는 우리가 읽는 곳이 아니라 내보내는 곳이다. 근거: `PortalLabelService.java`(클래스 선언부 `@Transactional`), `grep -a -rl "@PortalRepo" backend/src/main/java` → 1건.

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

> 컴포넌트 ID 체계(`KLID-AT-CO-*`)·클래스(`KLID-AT-CL-*`)는 D3/D1 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#194-설계-문서-카탈로그).
