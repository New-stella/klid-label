# 학습데이터 저작도구 워크스페이스

> 이 파일은 워크스페이스 루트에 위치합니다.
> 팀 공통 규칙은 ~/.claude/rules/ 에 있으므로 여기엔 이 프로젝트 전용 내용만 작성합니다.

## 프로젝트 개요
- **목적**: AI 기반 지방정부 CCTV 관제지원시스템(2차)의 학습데이터 저작도구 — 영상/이미지 라벨링, 검수 워크플로우, 비식별화, 외부 생성 메타데이터 검토
- **주요 도메인**: 사용자/권한, 마킹(자동/수동 이벤트 식별), 배치 파이프라인(비식별→마킹→VLM 콜백→마킹위치 프레임 추출→오토라벨링), 라벨링, 검수(REVIEWER 배정), 비식별화, 버전관리(DB 스냅샷), 데이터 증강(새 영상), 포털(데이터마트 Load)
- **범위 외 — 데이터마트**: 외부 제공 시스템 책임. 저작도구는 라벨링·검수·버전관리까지만 담당하고 마트 구축·검색·다운로드는 담당하지 않음
- **범위 외 — 생성형 AI 본체**: 생성형 AI 모델 학습·파인튜닝·프롬프트 가이드·UI/UX 편의성은 외부 생성 시스템 책임. 저작도구는 외부 증강 결과 검수(SCR-AUG-002)만 보유
- **범위 외 — VLM 모델 본체**: 학습·파인튜닝·프롬프트 관리는 외부 시스템 책임. 저작도구의 VLM 연동은 **외부 VLM 서비스를 호출해 시계열 정보를 획득하는 연동**만 보유(`ai-server/app/routers/vlm.py`는 외부 VLM 호출 어댑터). 응답을 LS_DATA_META(VLM)에 적재하고 SCR-AUTO-002 화면에서 REVIEWER 가 검토·수정
- **범위 외 — 영상 합성 모델 본체**: 외부 시스템 책임. 저작도구는 합성/증강된 영상의 수신·라벨링·검수만 담당
- **범위 외 — 학습데이터셋 내보내기(Export)**: 저작도구는 라벨링·검수·버전관리까지만 담당
- **요구사항 매핑**: 본체가 외부 시스템인 시계열 메타 모델·생성형 AI·영상 합성 모델은 요구사항정의서에서 제외. 저작도구 잔존 책임(외부 VLM 시계열 호출 연동·외부 메타 검토 UI·증강 연동·생성된 영상 라벨링)은 모두 SFR-08(저작도구 핵심 기능)에 흡수됨

## 워크스페이스 구조

```
klid-la-test-v0/
├── backend/                 # Spring Boot 메인 애플리케이션 (Java 17)
│   ├── src/main/java/kr/co/cudo/authoring/   # 도메인 패키지
│   ├── src/main/resources/
│   │   ├── application.yml · application-{local,dev,stg,prd}.yml
│   │   └── db/migration/    # Flyway (PostgreSQL)
│   ├── src/test/java/...
│   ├── build.gradle · settings.gradle
│   └── .env.example
├── ai-server/               # Python FastAPI AI 추론 서버 (YOLO/SAM2/VLM만)
│   ├── app/                 # main + routers/{yolo,sam2,vlm}
│   ├── requirements.txt
│   └── tests/
├── frontend/                # React + Vite + TypeScript
├── docs/
│   ├── requirements/        # 요구사항/DB/UI-UX
│   ├── analysis/            # CVAT 분석 문서 + portable-modules 9종
│   ├── design/ · contracts/ · api-specs/ · architecture/
├── reports/
├── cvat/                    # CVAT 원본 트리 (레퍼런스, .gitignore)
└── CLAUDE.md
```

## 프로젝트 구성
- **구조**: mono (모노레포 — 단일 git, backend/ + ai-server/ + frontend/)
- **백엔드**: `./backend` (Spring Boot — 메인 애플리케이션)
- **AI 추론 서버**: `./ai-server` (Python — YOLO/SAM2/VLM 추론만)
- **프론트엔드**: `./frontend` (React)
- **통신**: Spring Boot → `ai-server`는 HTTP(WebClient) 호출. Nuclio 스타일의 함수 엔드포인트 분리.

## 빌드 예산 (QA 자동화용)

buildTimeout: 1800
slowBuild: true

- 전체 회귀는 **backend 4,000+ 테스트(Testcontainers 포함) + frontend 1,600+ 테스트**로 **15~20분급**이다. 기본값 300초로는 절대 완주하지 못한다.
- 단일 셸 명령 상한(10분) 때문에 `./gradlew test` 한 방으로는 반드시 kill 된다 — **세그먼트 분할** 또는 **백그라운드 실행 + 폴링**으로 완주시켜야 한다.
- Gradle `test` 는 UP-TO-DATE 로 스킵되면 **실행 안 하고 통과처럼 보인다**(과거 거짓 PASS 실사고). `cleanTest test` 또는 `--rerun-tasks` 로 강제하고, 결과 XML 개수·타임스탬프로 실행 증거를 확인할 것.
- 빌드/테스트 에이전트는 **동시에 2개 이상 띄우지 않는다** — `build/test-results` 충돌로 위양성 실패가 난다.

## 기술 스택

### 백엔드 (Spring Boot)
- **Java 17** + **Spring Boot 3.3** + **Gradle 8**
- Spring Data JPA (Hibernate 6) + **QueryDSL 5.1** (복잡한 검색)
- **Spring Security** + **JJWT 0.12** (관제서버/포털 발급 토큰 검증)
- **Flyway 10.13** + `flyway-database-postgresql` (`klid_at` 스키마 — 저작도구 전용 LS_* 테이블은 자체 관리, MNG_* 공유 테이블 변경 시 관제서버팀 협의)
- **Spring Boot Quartz** (PostgreSQL JobStore — `QRTZ_*` 테이블, `PostgreSQLDelegate`)
- **Resilience4j** (외부 API 재시도/서킷 브레이커/타임아웃)
- **Spring WebFlux WebClient** (외부 시스템 연동)
- **net.bramp.ffmpeg** (FFmpeg Java 래퍼)
- **Caffeine** (로컬 캐시 — 시스템 설정 TTL 60s)
- MapStruct 1.5 / Lombok
- **Micrometer + Prometheus** (메트릭 수집 — API 응답시간, 배치 처리량, 외부 API 호출 모니터링)
- Springdoc OpenAPI 2.5 (Swagger UI)
- JUnit 5 + Testcontainers (PostgreSQL)

### AI 추론 서버 (ai-server)
- Python 3.11 + FastAPI
- **YOLOX (onnxruntime, Apache-2.0)** 탐지, **Meta SAM2 (Apache-2.0)** 분할, RT-DETRv2 (transformers) + ByteTrack(trackers), torch/torchvision, opencv-python
- **라이선스 정책**: ultralytics(AGPL-3.0) 미사용 — 탐지/세그멘테이션 모두 permissive(MIT/Apache-2.0) 백엔드로 구성. 모델 무거운 의존(onnxruntime/sam2/transformers)은 lazy import
- 역할: 경량 추론 전용. 상태·인증·DB 없음. Spring Boot가 오케스트레이션 주체

### 프론트엔드
- React 18 + TypeScript 5 + Vite 5
- TanStack Query v5, Zustand, React Router v6, axios
- Tailwind CSS
- 라벨링 캔버스: konva.js (CVAT canvas-drawing.md 참고한 포팅)

### 데이터베이스
- **PostgreSQL** — 드라이버 `org.postgresql.Driver`, JDBC `jdbc:postgresql://...`
- 인코딩 UTF-8, 표준 SQL DDL (PostgreSQL 문법)
- `klid_at` 스키마 운영 — **저작도구 전용(LS_*) 테이블은 자체 소유·구성**, 관제서버 재사용 8개(MNG_*)는 `ddl-auto=validate`로 읽기 위주 참조
- Quartz 스케줄러 `QRTZ_*` 테이블은 PostgreSQL JobStore(`PostgreSQLDelegate`, BYTEA)로 운영
- 외부 채널은 포털 DB 공유
- **관제 인프라 정합**: 관제서버도 PostgreSQL로 전환되며, 저작도구는 자신이 소유한 LS_* 테이블만 PostgreSQL용으로 구성한다. 공유 MNG_*/QRTZ_* 스키마는 인프라가 제공하고 저작도구는 validate/연동만 한다.

## 아키텍처 원칙

### 레이어
- Controller → Service → Repository (또는 QueryDSL 전용 Repository 보조)
- Controller는 DTO(Request/Response)만 다루고 Entity 직접 노출 금지
- Service는 비즈니스 로직. `@Transactional`의 readOnly 기본값 활용
- Repository는 JPA/QueryDSL만 사용, 비즈니스 로직 금지
- 도메인 중심 패키지 구조: `kr.co.cudo.authoring.{domain}.{controller|service|repository|entity|dto}`

### 공통 인프라
- `common.response.ApiResponse<T>` — 모든 API 표준 응답
- `common.exception.ErrorCode` enum + `CustomException` + `@RestControllerAdvice GlobalExceptionHandler`
- `common.security.JwtAuthenticationFilter` — 관제/포털 JWT 디코드 → `TokenClaims` → `SecurityContext`
- `common.security.SecurityConfig` — 역할 기반 접근 제어
- `common.datasource.{ControlDataSourceConfig, PortalDataSourceConfig}` — 듀얼 EntityManager/TransactionManager (`@ControlRepo`, `@PortalRepo`로 분리)
- `common.logging.RequestIdFilter` + Logback JSON 인코더 (민감 필드 마스킹)
- `common.client.*` — DeidentifyClient / AiServerClient / VlmClient / ControlNotifyClient / ExternalAugmentClient (Resilience4j 적용). 관제/포털 양방향 M2M 통합은 deprecated이나 **저작도구 → 관제서버 단방향 outbound 완료/수정 통지(ControlNotifyClient)는 예외로 보유**

### 코드 컨벤션
- 패키지: 소문자 케밥 금지, 영문 소문자만 (`kr.co.cudo.authoring.label`)
- 클래스명: PascalCase (`LabelService`, `LabelServiceTest`)
- 메서드/필드: camelCase
- 상수: UPPER_SNAKE_CASE
- DTO: `{Domain}{Action}{Request|Response}` (예: `LabelUpdateRequest`)
- 테스트 메서드명: 한글 서술형 (`@DisplayName("본인_배정이_아닌_프레임_편집_시_403")`)

### AI 파이프라인 분리 원칙
- **Spring Boot**: 인증/DB/오케스트레이션/큐 관리/라벨 CRUD/외부 연동
- **ai-server**: Stateless 추론만. GPU 자원 경합 격리
- 호출은 `AiServerClient` 빈으로 통일. 재시도 + 타임아웃은 Resilience4j 정책에서 관리
- CVAT Nuclio 함수 패턴 (docs/analysis/portable-modules/05-nuclio-function-template.md) 포팅

## 환경 구성

### 환경변수 목록
| 변수명 | 설명 | 사용 환경 |
|--------|------|:---------:|
| `SPRING_PROFILES_ACTIVE` | local/dev/stg/prd | 전체 |
| `CONTROL_DB_HOST/PORT/NAME/USERNAME/PASSWORD` | klid_system(PostgreSQL) 접속 | dev/stg/prd |
| `PORTAL_DB_HOST/PORT/NAME/USERNAME/PASSWORD` | 포털 DB(PostgreSQL) 접속 | dev/stg/prd |
| `JWT_SECRET` / `JWT_ISSUER` | JWT 검증 | dev/stg/prd |
| `DEIDENTIFY_API_URL` | 비식별 서버 | dev/stg/prd |
| `AI_SERVER_URL` | ai-server 내부 주소 | 전체 |
| `STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH` | 저장 경로 | 전체 |
| `VITE_API_BASE_URL` | FE API 주소 | FE 전체 |

### 환경별 접속 정보
| 환경 | BE | ai-server | FE | DB | 비고 |
|:----:|:---|:---|:---|:---|:-----|
| local | localhost:8080 | localhost:9300 | localhost:5173 | PostgreSQL (Docker/Testcontainers) | `./gradlew bootRun` / `uvicorn` / `vite` |
| dev | (dev BE) | (dev AI) | (dev FE) | (dev PostgreSQL) | |
| stg | (stg BE) | (stg AI) | (stg FE) | (stg PostgreSQL) | |
| prd | (prd BE) | (prd AI) | (prd FE) | (prd PostgreSQL) | 시크릿은 환경변수/Vault |

## 역할 정의

| 역할 | 코드 | 주요 권한 |
|------|------|-----------|
| 검수자 | `REVIEWER` | **사용자 관리·시스템 설정**, 작업자 배정·재배정·배정 이력 조회, 검수 승인/반려 |
| 라벨링 작업자 | `WORKER` | 라벨 수정·검수 제출 |
| 포털 회원 | `PORTAL_USER` | 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 기간 내 다운로드. **본인 자산(이미지/영상) 업로드 + 수동 라벨링(BBOX/POLYGON)** — ADR-013 예외(2026-07-17). 오토라벨링·검수·버전관리는 여전히 없음 |

> 시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한은 REVIEWER에 통합되어 있다. UI 호칭은 '검수자'로 통일하고, 관리 화면 URL은 `/manage/*`다.

## 주요 비즈니스 규칙

### 인증·진입
- 저작도구는 **독립 로그인 UI 없음** — 관제서버(내부) / 포털 서버(외부)가 발급한 JWT 토큰을 인계
- **관제서버와 동일 도메인 운영** → 브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용
- 두 채널 모두 **동일 JWT 발급 서버** — 단일 검증 로직(`JwtAuthenticationFilter`)로 처리
- 토큰 `role` + `channel` 클레임으로 권한 분기 (`@PreAuthorize("hasRole('REVIEWER')")`)
- 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트
- **외부 시스템 양방향 통합(M2M) deprecated**: 관제서버/외부 학습데이터 시스템과의 송수신 API 및 M2M 인증 인프라는 미운영. 재구축 시 별도 설계 필요.
- **예외 — 저작도구 → 관제서버 단방향 outbound 완료/수정 통지**: 영상 단위 작업의 검수 완료 시 `TASK_COMPLETED` 이벤트, 검수 완료 후 라벨/메타 수정 시 `TASK_MODIFIED` 이벤트를 관제서버 inbound SPI 로 push (비동기). 동일 작업 ID(=`LS_DATA_RAW.RAW_SN`) 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신. 양방향 M2M 인증 인프라는 부활하지 않으며, 본 통지는 인계 토큰 또는 IP 화이트리스트로 보호.

### 배치 파이프라인 (인증 불필요)
- 영상 적재는 **관제서버 학습용 설정 기반** — 관제서버가 공유 DB에서 영상을 학습용으로 설정하면 저작도구 주기 배치(`ControlTrainingVideoScanJob`, 1건/분)가 픽업해 적재(비식별 선두). 공유 DB(MNG_*) READ 기반이며 inbound 연동 API·M2M 인증은 미사용. **내부 파이프라인 1차 적재는** 포털 사용자 업로드를 사용하지 않는다 — 포털 자산 업로드(ADR-013 예외, 2026-07-17)는 내부 파이프라인·데이터마트와 완전 분리된 별도 경로(LS_PORTAL_* 전용)다. 구 '관리 화면 자체 업로드(TUS)' 방식은 폐지(코드 정리 후속)
- **파이프라인 순서**(구현됨): ⭐비식별화(전체 영상, 적재 직후 선두 자동) → 마킹(자동/수동, **비식별 영상 대상**) → VLM 시계열(콜백 비동기) → FFmpeg(**마킹 위치 기반** 원본+비식별 2벌 추출) → YOLO(**원본만** 실행, 비식별본 결과 공유) → SAM2 → 트랙 보간
  - 단계 순서는 **선언적 파이프라인**(`batch/pipeline/{BatchStep,BatchContext,BatchPipeline,BatchPipelineConfig}`)에서 관리. `BatchPipelineConfig` 의 `List.of(...)` 한 곳에서 재배치 가능 — pre-marking=[DEIDENTIFY], post-marking=[MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE]. 각 단계는 `isEnabled(ctx)` 조건부 실행 지원(dev 토글). (구 순서 '마킹(원본) 트리거 → VLM → 비식별 → 프레임추출' 폐지 완료)
- **마킹 단계**: **비식별화 완료 후** 작업자가 **비식별 영상**에서 자동/수동 마킹. 자동=**프레임 간격**(intervalFrames) 기반, 수동=키보드 단축키로 이벤트 시점 마킹. 마킹 결과(이벤트명 + 영상경로 + marks 배열)를 VLM에 콜백 전달. **마킹 완료 시 MarkingCompletedEvent → MarkingBatchBridge(AFTER_COMMIT) → 잔여 배치(VLM→프레임추출→오토라벨링) @Async 시작**. 구현된 흐름: 관제 학습용 설정 → 주기 배치 스캔(`ControlTrainingVideoScanJob`) → `TrainingVideoIngestService`가 `LsDataRaw` 적재(PENDING) → `VideoIngestedEvent`→`IngestDeidentifyBridge`(AFTER_COMMIT)→`AsyncDeidentifyRunner`(@Async)→`DeidentifyStep` 선두 비식별 자동 → 성공 시 `LsDataRaw.dataSttsCd=MARKING_READY` → 마킹 완료(`deIdntfYn='Y'` 가드 통과 시) → 잔여 배치. (post-marking 배치에서 비식별 단계는 제거됨)
- **마킹 화면**: **비식별 영상** 스트리밍(`GET /v1/videos/{rawSn}/stream`, HTTP Range 지원 — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) + 배속 설정(0.25x~4x) + 키보드 단축키(Space: 마킹, Del: 삭제, Enter: 완료). 마킹 중 비식별 누락 발견 시 **비식별 신고** 가능(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report` — 구현됨)
- **★VLM·KPST 위탁은 논블로킹 제출이다 (2026-08-03 실측 정정 — 구 "동기 호출(45s 타임아웃)" 서술 폐기)**: `VlmTimeseriesStep` 은 `.block(45s)` 로 파이프라인 스레드를 붙잡지 않고 **`subscribe()` 로 제출만 개시**한다(Phase C-1). 구 방식은 외부가 느려지면 core 2 짜리 배치 풀이 통째로 마르고 CallerRuns 역압이 호출 스레드까지 물었다. **KPST 비식별 제출도 대칭**으로, WAITING 원장 행을 선커밋하고 `createProject` 를 비동기 디스패치한다(Phase C-2).
  - **스텝이 확정적으로 말하는 사실은 "제출을 개시했다" 뿐**이다. 수락(ACK) 여부는 완료 핸들러가 `LS_BATCH_PROC_LOG`·원장에 비동기 기록하고, 그 기록은 `publishOn` 이 아니라 **명시적 디스패치**(`SubmitSignalDispatch`)로 전용 풀에서만 실행한다 — `publishOn` 은 풀 포화 시 거부가 **시그널을 나른 reactor-netty 이벤트 루프**로 흘러 JPA 쓰기를 이벤트 루프에서 실행시킨다.
  - **아무 신호도 없으면 미결 스위퍼가 회수한다** (`VlmSubmitPendingSweeper` / KPST 대칭). in-memory subscription 은 노드가 죽으면 통째로 사라져 ACK 도 실패 신호도 오지 않고, **실패 행이 없어 재시도 큐·실패 회수기가 집지 못한다** — 스위퍼가 유일한 회수 경로다. 2노드 Active-Active 에서 같은 후보를 두 번 재위탁하지 않도록 처리 전 **조건부 UPDATE 로 원자 클레임**한다(Quartz 클러스터링은 트리거 중복만 막는다).
  - ⚠ **미결 임계는 두 개이며 하나로 덮으면 정상 위탁을 뺏는다**: **ACK 창**(`stale-timeout-minutes`, 기본 30분 — 원장 `ISSUED`, 수락 응답조차 못 본 건) / **콜백 창**(`callback-timeout-minutes`, 기본 360분 — 원장 `ACCEPTED`, describe 는 영상 길이에 따라 수십 분 걸린다). 두 창을 구분하는 근거는 완료 핸들러의 `ISSUED → ACCEPTED` 전이(`VlmSubmitOutcomeRecorder`)이며, 그 전이가 없으면 구분이 성립하지 않는다.
  - 결과 상세는 VLM 서버가 콜백(`POST /v1/vlm/callback`)으로 별도 전송 → VlmResultService가 LS_DATA_META 적재 + 검수큐(LS_DATA_META_REVIEW) 진입
- **배치 상태 전이**: 적재 시 `LsDataRaw.dataSttsCd=PENDING` → 선두 비식별 성공 시 `MARKING_READY`(마킹 진입 허용) → 배치 완료 시 `COMPLETED`. BatchOrchestrator.process() 시작 시 `LsRawDataStatus → PROCESSING`, 완료 시 작업 상태 `→ ASSIGNED 복귀`(COMPLETED 는 검수 승인 시점의 작업 종결 상태이므로 배치 완료가 점프시키지 않음 — 점프 시 검수 제출 ASSIGNED→PENDING 이 상태 머신에서 차단됨), 실패 시 `→ FAILED`. **두 테이블 책임 분리**: `LsDataRaw.dataSttsCd`(배치 단계: PENDING→MARKING_READY→COMPLETED)는 완료 시 `COMPLETED` 로 마감하고, `LsRawDataStatus.dataSttsCd`(작업/검수 워크플로우 상태)는 배정 시점 생성·ASSIGNED 로 복귀시켜 라벨링/검수 플로우가 이어지게 한다. 작업 상태의 `COMPLETED` 는 `ReviewService.approve`(검수 승인) 에서만 전이한다
- **비식별 호출 조건**: **전체 영상 비식별 후 마킹**(ANONY 포함, 게이팅 폐지 — 무조건 자동 실행) — 비식별이 파이프라인 선두 단계로, 적재 직후 자동 트리거(`VideoIngestedEvent`→`IngestDeidentifyBridge`→`AsyncDeidentifyRunner`→`DeidentifyStep`)된다. 비식별 영상이 마킹 대상이 되며 원본은 별도 보존 (구 규칙 'PRVC/PSDO만' 폐지). **증강(augment) 적재 경로는 아직 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned/후속)**. 비식별 실패/신고 영상은 자동 재비식별 큐 없이 외부 비식별 프로그램에서 수동 재비식별(`deIdntfYn='F'` + 기존 resolve 경로)
- 원본 영상과 비식별 영상은 **별도 경로로 동시 저장**
- **오토라벨링**: YOLO/SAM2는 **원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유 (별도 실행 없음)
- YOLO/SAM2/VLM은 `AiServerClient`로 호출 (타임아웃 60s + Resilience4j CircuitBreaker)

### 작업 배정
- **REVIEWER가 WORKER에게 배정** (역할 단일화 — ADMIN 권한은 REVIEWER에 통합)
- `LS_TASK_ASSIGNMENT`에 `TASK_TYPE_CD='LABELER'` INSERT, 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER가 보유

### 목록 화면 정렬·필터 정책 (2026-07-29 확정, 구속)
- **정렬은 시간축 단일 기준** — 상태 우선순위를 `ORDER BY CASE` 로 섞지 않는다. "지금 처리할 것"은 **필터·KPI 카드**로 표현한다(CVAT·Label Studio 관행)
- **필터·집계는 BE 에서 전체 기준으로** 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다
- **기존 호출 하위호환 준수** — 목록 API 에 필터·정렬 파라미터를 추가할 때: 신규 파라미터는 전부 optional, **BE 기본값 불변**(화면 진입 기본값은 FE 가 명시 전송), 축이 다른 필터는 **별도 파라미터로 신설**(예: 작업목록의 배치 상태 축 `status` 와 워크플로 축 `workStatus`)
- **정렬 키는 allowlist 매핑으로만** 해석하고 개수 상한을 둔다 (CWE-89 / CWE-770)
- ⚠ **미등록 정렬 키의 응답은 엔드포인트별로 다르다** — `/v1/tasks/board*` 는 **strict(400)**, `/v1/reviews*` 는 **lenient(200 + 기본 정렬 폴백 + WARN)**. 이유는 "변경 전에 그 엔드포인트가 200 이었는가"다: 작업목록은 원래 `Pageable` 을 받아 잘못된 키면 500 이었으므로 400 이 개선이고, 검수목록은 `sort` 를 받지도 않아 **항상 200** 이었으므로 400 을 내면 FE 가 보존·재전송하는 `sort` 로 북마크·뒤로가기가 죽는다. **두 정책을 "일관성"을 이유로 통일하지 말 것** (회귀 가드: `ListApiBackwardCompatibilityIT`)

### ★이벤트유형 필터는 "표시명 그룹" 축이다 (2026-08-05 사용자 확정, 구속)
- **문제**: 필터 드롭다운에 같은 이름이 여러 번 떴다. 원인은 `LS_EVNT_TYPE.EVNT_NM`(관제 수신 유형명)이 **전 행 NULL** 이라 표시명 4단 폴백이 3순위 **카테고리명**까지 내려가, 같은 카테고리의 상세 유형들이 같은 이름으로 보였기 때문이다(dev 실측: 침수(범람)×3 `EV0100010{1,2,3}` · 교통사고×3 · 화재×2). 관제는 **현재 이벤트 코드만 송신**하며 이름·대분류·카테고리를 인입에 싣지 않는다(사용자 확인: 아직 반영 요청 전, 정합 후 요청 예정). 관제 DB 에는 **이벤트 코드 마스터 테이블 자체가 없다**.
- **해결 — 표시명이 같은 유형코드들을 옵션 1건으로 접는다.** 판정 단일 원천은 `eventtype/service/EventTypeService`(+ 스냅샷 `EventTypeGroupIndex`)이며, 표시명 해석은 반드시 `EventTypeDisplayNamePolicy` 를 **재사용**한다(복제 금지).
  - 그룹 대표코드 = 그룹 내 **최소 유형코드**(정렬 기반 — 실행마다 흔들리면 프리셋 매칭이 흔들린다).
  - `EventTypeResponse.categoryKey`=대표코드, `memberCodes`=**그룹 전체**(2건 이상 가능). **응답 스키마는 무변경**(`EventTypeResponse`·`EventTypeOptionsResponse` 필드명·타입·`truncated` 시맨틱 유지) — FE 변경 0.
- **★필터 파라미터는 이벤트 코드를 유지한다 — 이벤트명 문자열을 파라미터로 올리지 않는다.** 근거: `LS_LABEL_PRESET.EVNT_TYPE_CD` 가 **VARCHAR(20) UNIQUE 코드 컬럼**이라 한글 표시명(최대 200자)이 들어가지 않고, 표시명은 운영자가 바꿀 수 있어 **북마크·저장된 프리셋이 깨진다**. "이벤트 코드는 export 할 때만 쓴다"는 인식은 부정확하다 — 코드는 프리셋 매핑 키이자 `LS_DATA_RAW.EVNT_TYPE_CD` 저장값이다.
- **대표/비대표 어느 코드로 들어와도 그룹 전체를 매칭한다** — `codesForFilterKey` 가 그룹으로 확장한다(기존 북마크 URL 하위호환). `validFilterKeys()` 는 **대표코드가 아니라 그룹 멤버 전부**를 반환한다 — 대표코드만 반환하면 비대표 코드를 가진 **기존 프리셋이 검증에서 탈락해 400** 이 된다.
- **★오토라벨 프리셋도 그룹 축이다** — `filterKeyOf(code)` 가 대표코드를 돌려주므로 대표코드에 저장된 프리셋이 그룹 내 다른 코드 영상에도 적용된다. (V168 이 마이그레이션 안전성 때문에 "의도된 동작 축소"로 남겼던 부분이 런타임 그룹핑으로 복구된 것이며, 마이그레이션이 아니라 조회 시점 해석이라 그때의 기동 실패 리스크가 없다.)
- **적용 3경로 공통**: `/v1/videos` · `/v1/tasks/board*` · `/v1/assignments*`. ⚠ 뒤 두 경로는 **조회 필터가 단일 코드 `eq` 였다** — 옵션만 접으면 대표코드 선택 시 그룹의 나머지 영상이 통째로 사라진다. 그룹 `IN` 매칭으로 전환했고 KPI 집계도 같은 집합을 센다(공용 헬퍼 `assignment/service/EventTypeFilterSupport` 1곳 — 두 서비스에 복제 금지). **인가 축은 무관하게 유지**(REVIEWER=board 전체 / WORKER=본인 배정분).
- **작업목록 옵션의 절단(`truncated`)은 접은 뒤 판정한다** — 접기 전에 자르면 접은 결과가 상한 이하인데도 "일부만 표시" 오안내가 뜬다. 스캔 상한도 접기로 줄어드는 최대치만큼 더 읽는다(과소 신고 = 조용한 손실 방지).
- **미등록·비규격 코드(`INTRUSION` 등)는 버리지 않는다** — 라벨이 코드 원문이라 자기 혼자 그룹이 되며, 옵션에서 제거하면 그 영상이 필터로 **도달 불가능**해진다(조용한 데이터 손실).
- **★영구 병합이 아니다** — 관제가 유형별 이름(`EVNT_NM`)을 보내기 시작하거나 운영자가 관리 화면(`PATCH /v1/manage/event-types`)에서 표시명을 지정하면 표시명이 갈라져 **그룹이 자동으로 쪼개진다**. 따라서 표시명 변경 시 **캐시 무효화가 필수**다(`EventTypeCacheEvictor` 가 캐시 전체 clear — 신규 키를 evict 목록에 따로 등록할 필요 없음).
- 회귀 가드: `EventTypeServiceTest` · `EventTypeCacheIT` · `EventTypeFilterSupportTest` · `EventTypeGroupFilterIT` · `VideoListSearchFilterIT` · `StatsEventTypeGroupDistributionIT` · `PresetLabelLookupGroupMatchingIT`.

### ★영상 목록·상세에 "개인정보 유무"를 표시하지 않는다 (2026-08-05 사용자 확정, 구속)
- 근거: **관제가 개인정보 유무를 실제로 보내지 않는다** — 인입 원장 `LS_DATA_INGEST` 의 `ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN` 이 dev 실측 40행 전부 NULL. 화면이 보던 값은 관제값이 아니라 적재 시 고정되는 레거시 컬럼 `LS_DATA_RAW.PRVC_TYPE_CD` 였다(ANONY 37 / PRVC 18 / PSDO 2).
- **바뀐 것은 화면 노출뿐이다** — `PRVC_TYPE_CD` 컬럼과 응답 필드 `privacyTypeCd` 는 **존치**한다(하위호환). `PRVC_TYPE_CD` 는 계속 **비식별 대상 판정(`needsDeidentify`)** 에 쓰인다.
- ⚠ **라벨링 화면의 개인정보 메타 패널은 별개 축이라 그대로다** — 그건 사람이 직접 입력하는 값이고 export 개인정보 3필드의 원천이다(위 「개인정보 보호」 절). 혼동해 함께 제거하지 말 것.
- FE 컴포넌트 `components/common/PrivacyBadge.tsx` 는 참조 0건이 되어 삭제됐다.

### 작업 단위 + 완료/수정 통지
- **작업 단위 = 영상 1건** — 프로젝트 단위 개념 사용 안 함. 작업 식별자는 영상 단위 ID(`LS_DATA_RAW.RAW_SN`)
- **검수 완료 = 작업 완료** — REVIEWER 가 검수를 `APPROVED` 처리하면 작업이 완료됨. `LsRawDataStatus.dataSttsCd` 가 `COMPLETED` 전이된 시점에 outbound `TASK_COMPLETED` 통지 발행
- **검수 완료 후 수정 시** — 동일 작업 ID 유지, 새 작업 ID 발급/버전 업 모두 안 함. 라벨/메타가 수정될 때마다 outbound `TASK_MODIFIED` 통지 발행. 수신측(관제서버)은 마지막 상태로 갱신
- **통지 단위는 영상 1건** — 라벨/이미지 1장 단위로 통지하지 않음. 영상 내 다수 변경이 같은 트랜잭션·짧은 시간 내 발생하면 디바운스 후 1회 통지(운영 결정)
- **TASK_COMPLETED 페이로드 — 메타만 · 관제 계약(API-251 v17) required 8 + optional 1 = 9필드**: 이벤트 타입 + 작업 ID(RAW_SN) + 영상 메타(파일명·길이·채널) + 검수 완료 일시 + 프레임 개수 + 결과 요약 카운트(라벨 N건·메타 M건) + 요청 ID + **`evnt_cls_cd`·`evnt_ctgry_cd`·`gen_ai_yn`**. 라벨/메타 본문 자체는 포함하지 않으며, 관제가 필요 시 본 도구 API로 보강
  - ⚠ **6필드로 보내면 전량 `422 VALIDATION_FAILED`** 다 — required 라 값이 `null` 이어도 **키를 남긴다**(`gen_ai_yn` 포함).
  - ⚠ **JSON 키는 관제 스펙명이며 우리 컬럼명이 아니다** — `evnt_cls_cd`(관제) ≠ `EVNT_CLSF_CD`(우리 컬럼). 임의로 맞추지 말 것.
  - **이벤트 2코드는 인입 LATERAL 조인으로 조달**한다(설계 D1 — `LS_DATA_RAW` 에 그 두 컬럼은 **없다**). 연결 규칙은 뷰와 같은 단일 진실원 `IngestSourceLink`. 반면 **`gen_ai_yn` 은 인입이 아니라 자기 `LS_DATA_RAW.SRC_TYPE`** 에서 도출한다(파생은 자기 행이 `AUGMENTED`).
- **TASK_MODIFIED 페이로드 — 수정 요약만 전달**: 이벤트 타입 + 작업 ID(RAW_SN) + 마지막 수정 일시 + **변경 프레임 목록**(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED`) + 변경 요약 카운트 + 요청 ID + **`ver_expln`**(버전 설명). 라벨/메타 본문 데이터는 포함하지 않음
  - 관제 `dataset_versions.ver_expln` 이 **NOT NULL** 인데 미전송이라 관제가 스스로 때우고 있었다. 판정 단일 원천은 `VersionExplanationPolicy`(복제 금지).
  - ⚠ **optional 이라 `null` 이면 키를 생략한다**(`@JsonInclude NON_NULL`) — NOT NULL 컬럼에 **명시적 `null` 을 보내는 것보다 미전송이 안전**하다. 위 완료 통지의 "required 는 키를 남긴다"와 **정반대이며 의도된 비대칭**이다.
- **관제서버 조회 패턴**: 관제서버가 통지를 수신하면 저작도구 API를 호출하여 필요한 상세 데이터를 직접 조회. 저작도구는 관제서버가 조회할 수 있는 API를 제공해야 함
- 페이로드에 PII·토큰·원본 비-비식별 이미지 포함 금지
- 이력 보존은 `LS_LABEL_VERSION`(라벨 스냅샷) + `LS_DATA_LBL_HSTRY`로 충분

### 라벨링·버전관리
- 바운딩박스 / 폴리곤 / 세그멘테이션 / SAM2 분할(클릭·박스 프롬프트) / SAM2 Track — 캔버스는 konva.js
- **SFR-08-01(라벨링 정확도 향상) = VOS(추적+분할)로 해석** (2026-06-04 재정정 — 구 '자석 올가미' 해석 폐기·구현물 제거): SAM2 Track(박스→N프레임 추적 전파, `POST /v1/frames/{srcSn}/sam2-track`) + SAM2 분할(클릭/박스→폴리곤+신뢰도, `POST /v1/frames/{srcSn}/sam2-segment`, mock 응답은 FE 자동적용 차단)이 담당
- **라벨 프리셋 = 라벨 마스터(LS_LABEL) 단일 진실원 참조 (V117~V119)**: 프리셋 코드(`LS_LABEL_PRESET_CODE`)는 라벨명·형태를 스냅샷 저장하지 않고 `LBL_ID`(BIGINT FK→`LS_LABEL.LBL_ID`, nullable=미연결 허용)로 마스터를 실시간 join 한다 — 조회·표시·오토라벨 사용 시점에 마스터에서 라벨명/형태를 가져오므로 마스터에서 라벨명·형태를 바꾸면 신규·기존 프리셋 모두에 즉시 반영된다. **형태는 마스터 `LBL_TYPE_CD`가 소유**(BBOX→bbox, POLYGON→polygon, POINT/SKELETON→도형 오토라벨 미적용) — 프리셋에서 형태 개별 토글 불가(구 `BBOX_ENABLED`/`POLYGON_ENABLED` 토글 컬럼 제거). labelId 기반 신규 행은 코드 문자열을 저장하지 않아 `LBL_CD` NOT NULL 제거(미연결 레거시 행만 표시용 코드 보유), 부분 유니크 인덱스 `UK_LS_LABEL_PRESET_CODE_LBLID (PRESET_ID, LBL_ID) WHERE LBL_ID IS NOT NULL`로 프리셋당 같은 labelId 중복 저장 방지. 마스터에 매칭 안 되는 기존 코드(labelId null/비활성)는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음). 오토라벨(YOLO/SAM2) 경로의 프리셋↔검출 라벨 매칭은 **AI 검출 클래스 축(`LS_LABEL.DTCT_TYPE_CD`, COCO 80 클래스명)으로 일원화**하고 형태를 마스터에서 파생한다 *(2026-07-23 supersede — 구 '마스터 라벨명 축' 폐기. 근거: 라벨 관리에 COCO 검출 클래스 매핑을 등록하는 이슈3 반영. 한글 마스터 라벨명↔COCO 영문명이 1:1 대응하지 않아, 검출 라벨 귀속·프리셋 토글·온라인/배치 매칭을 모두 마스터의 COCO 매핑 컬럼 `DTCT_TYPE_CD`를 진실원으로 삼는다. `findLabelIdByDtctType`가 `findLabelIdByName`을 대체하고 배치(`YoloAutolabelStep`)·온라인(`AutolabelOnlineService`)·SAM2·프리셋 토글(`PresetLabelLookupService`) 4경로가 동일 축으로 통일됨)*. **AI 탐지 후보는 라벨 마스터(LS_LABEL) 기준으로 노출**하되 `DTCT_TYPE_CD`가 매핑된 라벨만 실제 검출 가능(미매핑은 표시하되 선택 불가). 매핑 강제는 BE(`AutolabelOnlineService.resolveDetectClasses`)가 담당 — FE 요청을 신뢰하지 않고 마스터 매핑 화이트리스트와 교집합만 ai-server 로 전달. `DTCT_TYPE_CD`는 표준용어(검출=DTCT·유형=TYPE·코드=CD) + 코드값 표준도메인 `VARCHAR(20)`, 활성 라벨 부분 유니크(1 COCO 클래스=1 활성 라벨). COCO allowlist(`CocoClasses` 80종)는 ai-server `COCO_ID2LABEL` 와 계약 정합(드리프트 테스트). 마이그레이션 후 기존 라벨은 매핑 NULL 로 시작 — 운영자가 COCO 매핑을 지정하기 전까지 AI 탐지는 게이팅(0건).
- **★라벨 표시 색상의 단일 진실원 = 라벨 마스터(`LS_LABEL.COLR_VL`), 판정기는 `label/utils/labelColor.getLabelDisplayColor` 한 곳 (2026-08-06 사용자 확정, 구속)**
  - **판정 순서**: `label.color`(BE enrichment) → `labelMasters[labelId||classId].color` → `trackId` 해시 → source fallback. **컴포넌트가 이 순서를 복제하지 않는다** — 넘길 항목만 고르고 판정은 위임한다.
  - **★마스터 연결의 실체는 `labelId` 다 — 라벨을 만들거나 분류를 바꾸는 모든 경로가 `labelId` 를 채워야 한다.** `classId` 만 채우면 `api.serializeLabel` 이 `labelId: null` 로 직렬화 → BE `LabelResponse.Item.from` 이 `LS_LABEL` 조인을 건너뛰어 재조회 응답의 `color`/`label` 이 null → 판정이 **3순위 trackId 해시색으로 낙하**한다. **색뿐 아니라 라벨명·속성 정의까지 함께 끊긴다.** ⚠ **저장 *전*에는 `classId` 폴백이 마스터를 물고 있어 정상으로 보이고 저장 *후*에만 깨진다** — 그래서 발견이 늦다(사용자 신고로 발견된 실사고, 2026-08-06).
  - **생성 payload 는 단일 진입점 `OverlayLayer.newLabelFrom` 을 거친다** — 도구가 늘어도 경로별 필드 누락이 재발하지 않게. 도구를 추가할 때 이 헬퍼를 우회해 payload 를 직접 만들지 말 것(실제로 BBOX·POLYGON·AI 분할·KEYPOINT 4경로가 각자 만들다 전부 같은 필드를 빠뜨렸다).
  - **라벨명 → 마스터 PK 역해석(`utils/labelMasterLookup.resolveLabelIdByName`)은 fail-closed** — 동명 활성 마스터가 **0건이거나 2건 이상이면 `null`**. 라벨명에는 유일성 제약이 없고 운영자가 바꿀 수 있어, 추측하면 **다른 분류로 저장**된다. 이 경로가 필요한 이유는 SAM2 Track 응답(`TrackedItem`)이 라벨명만 돌려주기 때문이며, **BE 응답 계약을 넓히지 않는다**(외부 FE 팀 사용).
  - **★두 색상 축을 통일하지 말 것**: 우측 객체 패널 **그룹 헤더 = 분류축**(마스터 색상, `useTrackFallback:false` 로 트랙색 유입 차단) / **개별 항목 막대 = 트랙 시각화축**(`trackIdToColor` 해시색). 서로 다른 것을 보여주는 것이 의도다.
  - **그룹 대표는 순서 독립으로 고른다**(`ObjectClassTree.groupRepresentative` — 최소 `labelId`, 미연결 null 은 뒤, 동률이면 `id` 사전순). 그룹핑 키가 `className` **문자열**이라 동명·다른 `labelId` 가 한 그룹에 섞일 수 있고, `items[0]` 으로 고르면 정렬·필터·재조회로 **순서만 바뀌어도 그룹 색이 흔들린다**. ⚠ 그룹핑 키를 `labelId` 로 바꾸는 것은 화면 구조 변경이라 **범위 밖**(별도 논의).
  - ⚠ **하드코딩 색상표를 되살리지 말 것** — 구 `features/label/labelColors.ts` 의 `LABEL_CLASS_DEFS`(9종, `PERSON`→`#EF4444` 등)는 **파일째 삭제**됐다. 마스터에서 색을 바꿔도 반영되지 않고 미등록 분류(`INTRUSION` 등)는 무조건 회색으로 떨어지던, **마스터와 어긋나는 두 번째 진실원**이었다. 같은 파일의 **표시명** 치환이 2026-08-03 에 *"마스터가 단일 진실원"* 이라는 **같은 이유로** 이미 폐지된 바 있어(→ `resolveLabelDisplayName`) 그 연장선이다.
  - **기존 `labelId=null` 행은 백필하지 않는다**(사용자 확정) — 해당 프레임을 **재저장하면 자연 복구**된다. 라벨명 매칭 소급 UPDATE 는 동명이인·비활성 마스터 오매칭 위험이 있어 채택하지 않았다. BE·마이그레이션 변경 0.
  - 회귀 가드: `OverlayLayerLabelIdWiring.test.tsx` · `ObjectClassTreeGroupColor.test.tsx` · `labelMasterLookup.test.ts` · `api.test.ts`. 카탈로그: `docs/test-cases/H-frontend-e2e.md` H-20(TC-FE-328~334).
- **2계층 분리 (Critical)**: ①**작업 임시저장**(작업 중) — 라벨러 저장 시 현재 작업본을 `LS_DATA_LBL`에 영속(upsert)하고 되돌리기는 FE undo/redo(세션)로 처리한다. 학습데이터 버전이 아니며 `LS_LABEL_VERSION` 스냅샷을 만들지 않는다. ②**학습데이터 버전**(확정) — 아래 별개 개념.
- **버전관리는 DB 기반 (외부 VCS 미사용)**: **검수 승인(`APPROVED`) 시점**에 영상 단위로 라벨 **전체 스냅샷(JSON)** 을 `LS_LABEL_VERSION.LABEL_PAYLOAD`에 저장한다(`SAVE_REASON='APPROVED'`). 라벨 저장 시점에는 버전을 생성하지 않는다 — SFR-08의 '학습데이터셋의 버전 관리'는 **검수 완료되어 학습데이터로 확정된 단위**를 대상으로 하기 때문. 버전 식별자는 페이로드 해시(`VERSION_HASH`, SHA-256)이며 동일 페이로드 재스냅샷은 동일 해시로 중복 식별한다. 검수완료 후 수정→재검수→재승인 시 새 버전이 쌓인다.
- **★버전 히스토리 단일 선택 = 현재 작업본과 비교 + diff 비교축 확장 (2026-08-05 사용자 확정, 구속)**
  - **신규 `GET /v1/versions/{version}/diff-with-working`** — from=버전 스냅샷, to=**현재 작업본(`LS_DATA_LBL`)**. 승인 버전이 **1건뿐인 프레임**은 기존 `/diff?compareWith=` 로 비교 대상이 없어 변경 내역을 **아예 볼 수 없었다**(사용자 신고). 별도 sub-resource 인 이유는 「같은 URL 에 쿼리 파라미터로 행위 분기 금지」(전례 `/v1/frames/{srcSn}/deid-image`) — 기존 `/diff` 의 **URL·파라미터·응답 스키마·상태코드는 무변경**.
  - **작업본 payload 는 승인 스냅샷과 동일 방식으로 생성**한다(같은 `LabelResponse.of` 인자 + `LBL_SN` 오름차순 + 같은 직렬화 경로). 어긋나면 **수정이 없는데 diff 가 나오는 오탐**이 난다. 저장하지 않는다(읽기 전용). ⚠ 1MB 초과 폴리곤 단순화는 **양쪽 모두** 재현한다 — 재현하지 않으면 이미 단순화된 승인 스냅샷과 비교할 때 수정 0건인데 전량 MODIFIED 가 난다. 대가인 경계 교차 시 과대보고는 **인지·수용**(WARN 관측).
  - **손상 스냅샷은 빈 결과가 아니라 400** — 신규 경로에서만 `items` 가 **명시적 배열일 때만** 통과(`{}`·`{"items":null}`·스칼라 root 거부). 빈 결과가 화면에서 "변경 없음"으로 표시되므로 거짓말이 되기 때문. ⚠ 기존 `/diff` 의 장애격리(파싱 실패 → 빈 리스트, TC-DIFF-007)는 **유지** — 두 경로 차이는 의도이며 통일하지 말 것. `LABEL_PAYLOAD` 가 blank/null 인 것은 손상이 아니라 라벨 0건이다.
  - **★비교축(R7) = `id`·`lblTypeCd`·`label`·`labelId`·`points`·`trackId`.** 구 동작(`lblTypeCd`·`label`·`points` **3개만**)은 **폐기** — **트랙 병합**(`reassignTrack`)처럼 시스템 자신이 `TaskModifiedEvent(LABEL_UPDATED)` 를 발행해 export 를 `v{n+1}` 로 재생성·관제 재통지까지 한 변경이 diff 에선 "변경 없음"이었다. 판정 축 근거 = `LabelContentHasher.appendLabels` 입력(`lblSn·srcSn·labelId·lblTypeCd·labelNm·pointCn·trackId`)과 **`srcSn` 제외 합동**. ⚠ 비교기를 `/diff` 와 **공유**하므로 그 엔드포인트의 **판정 결과도 함께 바뀐다**(계약은 무변경). **AI 메타(`autoLblYn`/`confScore`/`lblSrcCd`)·`labelName`/`color` 는 의도적 제외** — 기존 `LBL_SN` 의 AI 메타만 바뀌는 사람의 편집 경로가 없고 export 해시 축에도 없으며, `confScore` 부동소수는 잡음 diff 를 만든다.
  - **화면(SC-005 히스토리 '버전' 탭)**: 커밋 **1건 클릭 = 현재 작업본 비교** / **2건 체크 = 두 버전 비교**(유지). 구 동작 "1건 = 목록상 직전 버전"은 **폐기**. 변경 0건은 빈 목록이 아니라 **"변경 없음" 안내**이며, 렌더 분기가 `미선택 → 로딩 → 에러 → 결과` 순이라 **조회 실패(412 등)가 "변경 없음"으로 표시되지 않는다**. 캐시 키는 `VERSION_KEYS.workingDiff` 로 기존 `diff` 와 분리하되 **`VERSION_KEYS.all` 하위**에 둔다(라벨 저장·롤백의 기존 무효화로 자동 갱신 — 신규 배선 불필요). 프레임 전환 직후 stale 조회는 "현재 목록에 실재하는 해시만 조회"로 막고 **단건·두건 두 축에 대칭 적용**한다.
  - **알려진 한계(인지·수용)**: ①`items[]` 원소에 `id` 가 없으면 조용히 skip 되어 과대보고(앱이 쓴 payload 엔 항상 있어 도달성 0) ②`MAX_LABELS=500` 절단을 응답이 알리지 않음(기존 계약) ③롤백 PK 충돌 폴백으로 `LBL_SN` 재발급 시 내용이 같아도 `REMOVED+ADDED` 쌍(기존 성질).
  - 회귀 가드: `VersionServiceTest`(작업본 diff 12건 + R7 4건) · `VersionServiceDiffNullSrcSnTest`(게이트 순서) · `VersionControllerTest` · `HistoryPanel.test.tsx`. 카탈로그: `docs/test-cases/D-review-version-notify.md` D-5a(TC-DIFF-032~047) · `H-frontend-e2e.md` H-19(TC-FE-320~327). 위키: `docs/v2-wiki/13-version-control.md` §13.3.1~13.3.3.
- **diff / 롤백**: **검수완료(APPROVED) 버전 간** DB 스냅샷을 앱에서 비교해 diff를 계산한다. 외부 Git/Gitea 등 VCS에 의존하지 않는다. 데이터마트 동기화(SFR-08 3번째 항목)는 검수완료 후 수정 시 `TASK_MODIFIED` 통지로 연계한다. 롤백 시맨틱(구현 실측 정합 — 구 "새 active 버전으로 복원" 서술 정정):
  - **대상 스냅샷 행을 재활성**한다 — 새 버전 행을 적층하지 않는다(`SAVE_REASON='ROLLBACK'` 코드 폐기. 롤백 결과 페이로드는 대상 스냅샷 그 자체라 재계산 해시가 대상 행과 같고 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 로 적층이 불가능하다).
  - **롤백 행위는 `LS_DATA_LBL_HSTRY` 에 기록**한다 — 누가(actor)·언제(시각)·어느 버전으로(대상 `VERSION_HASH`).
  - **라벨 본문을 작업본(`LS_DATA_LBL`)으로 실제 복원**하며 `LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 **보존 복원**한다(PK 재발급 시 diff 가 "전량 교체"로 오분류되므로 점유된 PK 만 신규 발급 폴백).
  - **멱등 롤백은 no-op** — 현재 active 가 이미 대상 스냅샷이면 라벨을 재작성하지 않고 이력·통지도 발행하지 않는다.
- **★ export 재생성·동기화 정책 (Critical — 2026-07-27 확정, 구 "승인 시점에만 재생성" 정책 폐기)**: 사업 요구사항 원문 *"동일 학습데이터의 버전 관리 및 비교 기능"*(버전별 변경 내용 **비교·복구**) + *"기 구축된 데이터 마트 연계 관리기능"*(**데이터마트로 구축이 완료된 학습데이터의 라벨링 수정 시 기존 데이터마트 학습데이터셋의 라벨링 정보 동기화**)을 충족하기 위한 구속 규칙이다.
  - **승인 후 아래 수정 경로는 export 를 새 버전 폴더 `v{n+1}` 로 전량 재생성**한다(이미지 2벌 포함, 승인 경로와 동일 산출): 라벨 수정(`LabelService`)·트랙 편집(`TrackEditService`)·트랙 병합(`TrackMergeService`)·버전 롤백(`VersionService`)·촬영환경(날씨/시간대/계절) 수정(`EnvironmentMetaService`)·프레임 설명 수정(`FrameDescriptionService`)·프레임 개인정보 메타 수정(`FramePrivacyMetaService`). *구 정책 "재생성은 승인 시점에만, 편집분은 다음 재승인에서 재산출"은 폐기* — 파일이 옛 내용이면 "라벨링 정보 동기화" 요구가 성립하지 않는다.
  - **통지는 export 성공(SUCCEEDED) 후 발송**한다(승인 `TASK_COMPLETED`·수정 `TASK_MODIFIED` 양쪽). export 가 `@Async` 라 통지가 앞서면 관제가 **구 버전 폴더**를 픽업한다. **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(통지 유실이 아니라 성공 시점으로 지연). **재export 트리거(`DatasetExportBridge`·수정 축적·디바운스 flush)는 `authoring.control-notify.enabled` 토글과 무관하게 항상 동작**한다(토글 off 인 dev/stg/prd 기본 형상 포함) — 이 토글은 통지 발송(`sendCompleted`/`sendModified`)만 게이팅한다.
  - **버전마다 전체 자기완결 + 전 버전 보존(삭제 안 함)**. 델타만 두면 요구의 *복구(rollback)* 가 성립하지 않으므로 **retention 정리 로직을 만들지 않는다**. 저장소 증폭은 감수한다(검수 완료 영상의 재검수 빈도가 낮다는 판단).
  - `LS_DATASET_EXPORT.OUTPUT_PATH_NM`(V173 개명 — 구 `EXPORT_PATH_NM`) 은 **영상 루트**(`{rawSn}`, 버전 루트 아님)를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다.
  - ⚠ **비식별 영상 파일명은 고정이 아니다** — 우리가 지정하는 것은 **디렉터리(`export_path`)까지**이고 파일명은 외부 비식별 솔루션이 정한다. mock 은 `deidentified.mp4`(우리가 직접 씀), **KPST 실연동은 `{원본stem}-mask{ext}`**(예: `001.mp4` → `001-mask.mp4`)로 **영상마다 다르다**. 따라서 **파일명을 조합·추측하지 말고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽는다**(위 데이터마트 View 절의 "문자열 치환 도출 아님"과 동일 규칙). 관제도 파일명을 고정으로 기대할 수 없으므로 **뷰에 이 경로를 노출하는 것이 관제가 비식별 영상을 찾는 유일한 수단**이다.
  - ⚠ **`EvntAnnoService`(event_annotation 수정)는 예외** — 이 서비스는 재동결(`materialize`)을 하지 않아 재export 만 붙이면 승인 시점 동결본(`EVNT_ANNO_CN`)이 그대로 나간다. 적용하려면 재동결 배선이 선행돼야 하며 **미확정**이다.
- **CVAT 트랙 보간 알고리즘** 포팅 (docs/analysis/portable-modules/01-track-interpolation.md → Java)
- **MASK ↔ RLE ↔ Polygon 변환** 포팅 (portable-modules/02)

### 증강 = 새 영상
- 증강 AI는 **이미지-to-이미지** — 영상(비디오)을 재생성하지 않는다. 증강 결과 영상은 **원본(비식별) 영상 파일을 그대로 복사**하고 **프레임 이미지만 변환**한다.
- 증강 결과는 **새 영상(RAW_SN) 생성** — 원본과 다른 영상 ID. `LS_DATA_RAW.ORGNL_RAW_SN`(V82 rename, 구 `PARENT_RAW_SN` — 표준단어 ORGNL=원본)으로 원본(부모) 참조. **용어 표준**: `RAW`=원시(RAW_SN·LS_DATA_RAW 자체), `ORGNL`=원본(파생물의 부모/소스). 데이터마트 뷰 `V_COMPLETED_VIDEO` 출력 컬럼도 **`ORGNL_RAW_SN` 로 통일**(외부 소비자 부재로 구 `PARENT_RAW_SN` 외부 계약명 폐지 — V95. 내부·외부 모두 ORGNL_RAW_SN)
- 어노테이션(라벨)은 **원본(비식별) 정보 기준** — 원본 영상의 라벨/메타 JSON을 새 영상에 **복사**(외부 증강 3종 WINTER/NIGHT/RAIN은 해상도 동일 → 좌표 그대로 복사)
- **해상도 변경(SFR-06-03) = 증강형 파생영상 + 증강 저장모델 통합 (Critical — 2026-07-21 설계 반전 → 2026-07-22 저장모델 통합, feat/resolution-derivative-video)**: 표준 해상도 3종 고정 프리셋마다 **새 파생영상(RAW_SN) 생성**, `ORGNL_RAW_SN`으로 원본 참조 — 증강과 동일하게 **파생영상 취급**(구 '증강 아님·1행 추적' 정책 폐기). 비디오 파일은 **원본(비식별) 복사**(재인코딩 없음), **프레임 이미지셋만 목표 해상도로 리스케일**(축소/확대) — 이 두 원칙은 증강과 동일하며 유지. **라벨/이미지 좌표를 해상도 배율(scaleX=targetW/srcW, scaleY=targetH/srcH)로 재계산해 적재**(BBOX/POLYGON/세그멘테이션/키포인트 전 종류 — 구 '좌표 미제공' 폐기). **업스케일(확대)도 허용**(구 `targetH>=srcH` 400 거부 가드 제거) — 원본과 동일 해상도인 프리셋만 스킵하고 나머지는 3종 모두 생성. **저장모델은 증강과 완전 통합** — 파생 판별·라벨매핑을 위한 전용 테이블을 두지 않고 `LS_DATA_AUG` + `LS_DATA_AUG_LBL_MAP`에 적재한다: 판별자는 **`AUG_TYPE_CD` 값 `RESL_1080P`/`RESL_720P`/`RESL_480P`**(해상도 사업표준단어=RESL, 신규 컬럼 없음), 라벨 배율은 기존 `LS_DATA_AUG_LBL_MAP.COORD_RECALC_YN/SCALE_X/SCALE_Y` 재사용(신규 배율 컬럼 없음), 중복 방지는 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'`(V125). **구 전용 테이블 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`은 폐기**(V126 백필 후 fail-closed DROP). 해상도 파생은 **증강 이력(`GET /v1/augments`)에 노출**(응답 `resolutionTypes` 필드)되고 **집계/통계에 포함**되나, **accept/reject(검수 승인·반려)는 차단**(내부 생성물·라벨 검수 대상 아님). aug 상태 라이프사이클: **예약 시 PENDING(생성 중) → finalize 성공 시 ACCEPTED(생성 완료)**, 실패 시 예약행 삭제. API `POST /v1/videos/{rawSn}/resolution` 응답은 "생성된 파생영상 목록 `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}`", 요청 바디는 선택적 `presets[]`(미지정=3종 전체). 1건 이상 생성 성공=201 / 전부 실패=500 / 대상 프리셋 전부 스킵(모두 원본과 동일 해상도)=400. **★해상도 파생 생성도 원본 비식별 신고와 무관하다 (2026-07-29 확정, 구속)** — 증강 파생과 동일 정책이며 해상도 파생은 **외부 위탁이 전혀 없는 내부 ffmpeg 리스케일**뿐이라 신고 구간에 생성해도 외부 유출 경로가 열리지 않는다. 부모 게이트 3곳(`ResolutionReservationPersister` 예약 · `ResolutionSnapshotService` Phase A · `ResolutionPersistService` Phase C)은 **`DE_IDENT_YN='N'`(비식별 미수행)·null 만 차단**하고 `'F'`(신고)는 통과시킨다. 판정 단일 원천은 `LsDataRaw.hasDeidentArtifact()`(`'Y'`|`'F'`)이며 **증강 경로(`AugmentResultService.evaluateParentGate`)도 같은 헬퍼를 쓴다**. `'F'` 는 의미가 둘(①신고 — 산출물 존재 ②비식별 API 실패 — 산출물 부재)이라 플래그만으로 구분되지 않으므로, **산출물 실재 검증이 fail-closed 로 뒤를 받친다**(Phase A: 최신 SUCCESS 비식별 procLog 경로 부재 → NOT_FOUND, 프레임 비식별 경로 부재 → CONFLICT / Phase B: 비식별 영상 파일 부재 → NOT_FOUND). **원본(비-비식별) 경로 폴백은 어디에도 두지 않는다.** Phase C 의 **stale 창 게이트는 존치하되 판정축이 신고가 아니라 복사 원자성**이다 — 구 조건 ①`capturedAt` 이후 신고 이력은 제거하고, ②최신 SUCCESS 비식별 procLog 경로 불일치 ③(파일 존재 시) mtime > `capturedAt` 두 조건만 남긴다(스냅샷 이후 부모 비식별본이 교체되면 프레임별로 다른 버전이 섞인 산출물이 나오므로 abort).
- 파생영상은 **미검수(PENDING) 상태**로 시작 → 작업자 배정 → 수정 → 검수 (증강·해상도 변경 파생 공통, 기존 플로우 동일). 검수 승인 시 관제 **별도 완료 통지(TASK_COMPLETED)** 발송 — 파생영상은 기존 RAW_SN 파이프라인/데이터마트 뷰(`V_COMPLETED_*`)를 그대로 타므로 뷰 스키마 변경은 불필요
- **★ 파생영상에는 "원본영상"이 없다 — 비식별 영상만 있다 (Critical, 2026-07-28 사용자 확정 · Phase 7)**: 증강·해상도 파생 공통. 저작도구는 파생영상을 **재생·마킹하지 않으며 유일한 소비자는 관제서버**다(통지 수신 → `RAW_SN` 으로 뷰 SELECT → 1행 UPSERT). 따라서 파생영상의 수용 기준은 "관제 쪽에서 1행이 정확한가" 하나다.
  - **비디오 파일은 부모의 비식별 영상을 실제로 복사**한다 — `{deidBase}/videos/augment|resolution/{parentRawSn}/{newRawSn}/{...}.mp4`. 경로는 **`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽어** 정하고 **문자열로 조합·추측하지 않는다**(파일명이 mock=`deidentified.mp4` / KPST=`{stem}-mask{ext}` 로 다르다). *구 동작 "복사 없이 부모 원본 NAS 경로만 기록" 폐기 — 관제가 경로를 픽업해도 파일이 없었다.*
  - **부모 원본 경로 폴백 금지.** 파생 `LS_DATA_RAW.RAW_FILE_PATH_NM` 에는 **자기 비식별 사본 경로**를 적재한다. 부모의 비식별 이전 원본 경로를 넣으면 `DE_IDENT_YN='Y'` 행에 PII 원본 경로가 실린다(CWE-359).
  - **★ 관제 계약 (협의 대상)**: `ORGNL_RAW_SN` 이 non-null 인 행은 메타 동결(`LS_DATASET_VIDEO_META.RAW_FILE_PATH_NM`) 시 **null 로 동결**되어 **`V_COMPLETED_VIDEO.ORIGINAL_VIDEO_PATH` 가 NULL** 이다. 관제는 파생 비디오를 **`DE_IDNTF_FILE_PATH_NM`(V138)** 으로 픽업한다. export co-locate base 는 라이브 `LS_DATA_RAW` 를 쓰므로 동결 null 과 무관하게 성립한다.
  - **video.\* 기술메타(`RESL`/`VDO_WDTH`/`VDO_HGT`/`FPS`/`BIT_RT`/`VDO_CDC`)는 부모와 동일한 것이 정상**이다 — 증강·해상도 **둘 다 비디오를 재인코딩하지 않고 복사**하며 변환 대상은 프레임 이미지뿐이기 때문. `DerivedMetaCopier` 의 부모 값 복사는 결함이 아니다. ⚠ **`RESL` 은 "비디오 파일 기준"** 이며 해상도 파생의 목표 해상도가 아니다(목표값은 `LS_DATA_AUG.AUG_TYPE_CD=RESL_*` 에 있다) — 관제에 이 의미를 명시할 것.
  - ffprobe 는 **파생의 비식별 사본**을 대상으로 하고, **사본 확정 이후에만** 트리거한다(프레임 러너와 동시 기동 시 레이스로 값이 결정되던 것 폐기). 사본이 없으면 원본 폴백 없이 skip.
- 관제서버 통지 시 **새 영상 ID(RAW_SN)로 별도 완료 통지** 발송
- 증강 요청/수신 흐름: ExternalAugmentClient → 콜백
- **★요청 시 생성 조건(prompt) 5필드는 전부 필수·자유 문자열이다 (2026-07-31 확정)**: REVIEWER 가 `time`/`season`/`weather`/`terrain`/`severity` 를 입력하면 「생성형 AI API 연동명세서 v1.1」§4.1 `prompt` 로 가공 없이 그대로 전송되고 `LS_DATA_AUG.PROMPT_CN`(V147)에 원문(JSON)이 보관된다. 명세가 허용값 enum 을 정의하지 않으므로 서버가 값 자체를 좁히지 않는다(검증은 필수·공백금지·상한·보이지 않는 문자 제거뿐). **`AUG_TYPE_CD`(증강 종류)는 prompt 에서 파생하지 않는다** — 자유 문자열이 유형 판정에 흘러가면 산출물 경로 순회(CWE-22)·`RESL_` 네임스페이스 침범이 열리므로 유형의 단일 원천은 별도 `types[]` enum 이다.
- **★중복 증강 요청은 BE 가 차단하지 않는다 — 구 "요청 1회 = 파생영상 1건" 계약 폐기 (2026-07-31 사용자 확정, 구속)**: 같은 (영상 × 증강 종류)를 **몇 번이든 다시 요청할 수 있다.** 근거(사용자 발언): *"증강 이미지가 요청때마다 다르게 나올텐데 원하는 이미지가 안 나오면 동일하게 다시 요청할 수도 있다"* — 생성 결과가 매번 달라 **동일 조건 재요청이 정당한 운영 동선**이며, 구 정책의 전제("같은 종류를 또 만들 이유가 없다")가 성립하지 않는다. 따라서 ①서비스 사전 조회 가드 ②409 응답·안내 문구 ③부분 유니크 인덱스 `UK_LS_DATA_AUG_ACTVTN`(V143 → **V147 에서 DROP**) ④술어 미러 상수 `LsDataAug.ACTIVE_STATUSES` 를 **모두 제거**했다. **연타(오조작) 방어는 FE 단독 책임**이다(요청 버튼 비활성·중복 제출 차단). ⚠ **속도 제한(RateLimiter)도 두지 않는다** — 보안 검토가 지적한 자원 소모(CWE-770)는 **사용자가 인지하고 수용한 잔여 위험**이다. "보안 강화" 명목으로 되살리지 말 것. ⚠ **되돌리기 금지** — 이 프로젝트의 "철회된 정책 재시도" 패턴(조상/자손 전파 4라운드) 차단 관례를 따른다. 차단이 다시 필요해지면 인덱스 재생성 전에 활성 중복(PENDING·ACCEPTED) 선정리가 필요하다(절차는 V143/V147 주석). ⚠ **`UK_LS_DATA_AUG_RESL`(V125, 해상도 `RESL_` 접두)은 별개 계약이라 유지**된다 — 해상도 파생은 외부 위탁이 없는 내부 결정론적 리스케일이라 같은 프리셋을 두 번 만들 이유가 없다. ⚠ 중복 허용의 귀결로 **같은 (영상 × 종류) 파생이 여러 건 공존**한다 — 결과물 구분의 유일한 축은 `LS_DATA_AUG.PROMPT_CN`(요청 시 전송한 생성 조건 원문, V147)이므로 조회 경로에서 반드시 노출한다(`GET /v1/augments/{jobId}/result` 항목별 `prompt`). 파생 영상 식별자(`LS_DATA_RAW.VMS_CLIP_ID`)도 시각이 아니라 **증강 행 PK(`DATA_AUG_SN`)** 로 유일화한다 — 시각 기반이면 동시 콜백이 같은 밀리초에 충돌해 `UK_LS_DATA_RAW_VMS_CLIP` 위반으로 결과물이 유실된다.
- **★증강 파생 생성은 원본 비식별 신고와 무관하다 (2026-07-29 확정, 구속)**: "파생영상은 비식별 신고 체계 바깥" 정책과 대칭이다. 신고 구간(`DE_IDENT_YN='F'`)에 증강 콜백이 도착해도 **파생을 생성한다**(`AugmentResultService` 의 부모 게이트는 `'N'`=비식별 미완료만 막는다 — 비식별 영상 파일이 없어 파생 비디오 복사가 물리적으로 불가). 신고가 막는 것은 **외부 위탁(요청·전송)** 뿐이며 그 차단은 `AugmentRequestService`(요청 입구 412)와 `AugmentJobSubmitService`(전송 진입점) 두 곳이 담당한다. **차단은 보류가 아니라 거부다** — 위탁 전 신고면 `LS_DATA_AUG_JOB` 에 `DEID_REPORT_OPEN` 사유를 남기고 실패 롤업으로 종결하며(해소 후 재요청이 정상 동선), 위탁 도중 신고 관측이면 남은 청크를 `DEIDENT_REPORT` 로 중단해 부분 프레임셋 확정을 막는다. 구 "정책 보류 + 해소 시 재개"(`AugmentApplyResult.WITHHELD_*`, `AugmentRequestBridge.onDeidentReportResolved`)는 **폐기** — 자손 팬아웃 복구 배선이 철회돼 재개 트리거가 없어졌고, 트리거 없는 보류는 PENDING 영구 고착이기 때문이다.
- **★파생 깊이는 1 로 고정한다 — 파생본에서는 어떤 파생도 만들지 않는다 (2026-07-31 사용자 확정, 구속)**: `LS_DATA_RAW.ORGNL_RAW_SN IS NOT NULL`(증강·해상도 파생본)인 영상은 **증강 요청 대상이 될 수 없다**(`AugmentRequestService.request` → **400**). 근거: ①파생 비디오는 부모의 **비식별본을 복사**하고 프레임만 변환한 것이라 파생의 파생은 변환이 중첩되고(480p 축소본에 WINTER 를 다시 입히는 식) 라벨도 복사본의 복사본이라 출처 추적이 흐려진다 ②"파생본을 재비식별할 수단이 없다"는 확정 정책과 정합한다 ③조상/자손 전파는 4라운드 시도 후 철회됐는데, 깊이를 1 로 고정하면 **그 클래스의 문제가 구조적으로 소멸**한다(모든 파생의 부모는 항상 원본) ④등재 게이팅·비식별 판정이 재귀 없이 컬럼 1개 조회로 끝난다.
  - ⚠ **이것은 신규 정책 도입이 아니라 드리프트 정합 수정이다** — **해상도 경로는 이미 막고 있었다**(`VideoResolutionService.loadAndValidate` 의 `parent.getOrgnlRawSn() != null` → 400, 컨트롤러 `/{rawSn}/resolution` 에 실제 배선된 라이브 경로). 안 막혀 있던 것은 증강 경로 하나뿐이었고, 같은 조건에 두 경로가 다르게 동작하던 것이 결함이었다.
  - **거부 코드는 400 으로 통일**한다 — 해상도가 같은 조건에 이미 400 을 쓰므로, 같은 사유에 다른 코드를 주면 FE 가 두 갈래로 분기해야 한다. (비식별 신고 거부의 412 와는 **사유가 다르다** — 412 는 "지금은 안 되지만 해소되면 된다"는 일시 조건이고, 파생 차단은 **영구 조건**이라 재시도 여지가 없다.)
  - **거부 안내는 원본으로 유도하지 않는다** — 파생에 배정된 WORKER 는 원본 접근 권한이 없어(`LabelAccessGuard` 403) 따라갈 수 없다. "파생영상은 비식별 신고 체계 바깥" 절의 안내 문구 규칙과 동일하며 부모 rawSn 도 내려주지 않는다.
  - **기존 손자 파생(깊이 2+)은 정리하지 않는다** — 신규 생성만 차단한다. 데이터 삭제 위험 0 이고 관제에 이미 통지된 건이 사라지지 않는다. 따라서 **판정 코드는 깊이 2+ 데이터가 실재할 수 있음을 견뎌야 한다**(조회 시 예외를 던지지 말 것).
  - **FE 동선**: 영상 상세(`VideoDetailResponse.derivative`)로 파생 여부를 미리 알아 **증강 요청 버튼을 비활성 + 사유를 툴팁으로 표시**한다. 400 안내 노출은 화면이 파생 여부를 모르는 경우의 안전망으로 유지.
- **★"생성 결과" 축과 "사람의 사용/폐기 결정" 축은 서로 다른 컬럼이다 (2026-07-31 사용자 확정, 구속)**: `LS_DATA_AUG.AUG_PROC_STTS_CD` 는 **생성 결과 전용**(웹훅이 소유 — 생성 성공 `ACCEPTED` / 실패 `REJECTED` / 취소 `CANCELED`)이고, **REVIEWER 의 사용·폐기 결정은 `LS_DATA_AUG_RVW.RVW_STTS_CD` 가 단독으로 소유**한다(검수자 `RVW_ID`·검수일시 `RVW_DT`·반려사유 `RJCT_RSN` 동반).
  - **경위 (같은 실수 반복 방지)**: 두 주체가 한 컬럼에 쓰고 있었다. 웹훅이 생성 성공 시 `AUG_PROC_STTS_CD='ACCEPTED'` 로 전이시키는데, `LsDataAug.applyReviewStatus` 는 **`PENDING` 에서만** 전이를 허용한다. 외부 증강은 웹훅이 항상 먼저 도착하므로 **REVIEWER 의 승인·반려가 영구히 409("이미 처리된 증강 결과입니다")로 막혀 있었다** — 즉 사용/폐기 워크플로 자체가 도달 불가였다. 기존 테스트는 `aug` 를 `PENDING` 으로 심어놓고 `accept()` 를 호출해 이 조합을 한 번도 통과시키지 않아 커버리지 밖이었다.
  - **구현 규칙**: `AugmentReviewService.accept()/reject()` 는 **`aug.applyReviewStatus(...)` 를 호출하지 않는다** — 리뷰 행만 쓴다. `AUG_PROC_STTS_CD` 를 "검수 상태"로 읽는 코드·화면·통계는 전부 **리뷰 축으로 옮긴다**(두 축을 다시 합치지 말 것).
  - **★등재 게이트의 축도 리뷰 행이다** — "승인해야만 작업목록·배정에 등재" 판정은 `LS_DATA_AUG_RVW.RVW_STTS_CD='ACCEPTED'` 로 한다. `AUG_PROC_STTS_CD='ACCEPTED'` 로 게이팅하면 **생성 성공만으로 전부 통과**해 기능이 무의미해진다.
  - ⚠ **해상도 파생(`RESL_*`)은 게이트 통과 예외를 명시적으로 박는다** — 내부 생성물이라 검수 대상이 아니고 accept/reject 가 차단돼 있어 **리뷰 행이 영영 생기지 않는다**. 예외를 빠뜨리면 해상도 파생이 전부 작업목록에서 사라진다.
  - ⚠ **기존 파생은 그랜드퍼더링** — 게이트 도입 이전에 생성된 파생은 매핑 컬럼(`NEW_RAW_SN`)도 리뷰 행도 없어 무조건 미등재로 판정된다. 이미 배정된 WORKER 의 영상이 화면에서 사라져 **고아 배정**이 되므로, 게이트는 신규 파생부터 적용한다.
- **★미사용 파생의 폐기 = 소프트 삭제 + 유예 7일 후 실삭제 (2026-07-31 사용자 확정, 구속)**: REVIEWER 가 증강 결과를 반려하면 파생 영상이 즉시 작업 대상에서 빠지고(등재 게이트가 리뷰 축이라 자동), **유예기간이 지나면 배치가 DB 행과 생성 파일까지 삭제**한다.
  - **삭제 대상은 3조건 동시 충족만** — `ORGNL_RAW_SN IS NOT NULL`(파생) + 반려 + 유예 경과. **하나라도 빠지면 원본이 지워진다.** 조건은 서비스 레이어뿐 아니라 **최종 DELETE SQL 자체에 리터럴로** 박는다(검사~삭제 사이 창을 단일 문장으로 폐쇄 — 기존 `VideoRepository.deleteFailedDerivative` 와 동일 원칙). 원본 `rawSn` 만 받는 삭제 메서드를 만들지 않는다.
  - **유예기간은 설정값이고 기본 7일**이다. 하한 검증을 기동 가드로 걸어 `0`·음수·파싱 실패면 **기동을 실패**시킨다(파괴적 기능이 fail-open 되면 "반려 즉시 실삭제"로 전락한다 — `.env.example` 빈값이 `${KEY:default}` 를 무력화한 사고 이력 참조).
  - **복구는 반려 자체를 되돌린다** — 폐기 표식 해제 + 리뷰 재오픈으로 **다시 채택/반려를 고를 수 있다**(표식만 지우면 리뷰가 REJECTED 로 남아 목록에 계속 안 보이는 반쪽 복구가 된다). `LsDataAugRvw.ensurePending` 의 "재결정 금지" 불변식을 여는 것이므로 **되돌린 이력(누가·언제·왜)을 반드시 남긴다.**
  - ⚠ **`NEW_RAW_SN` 이 NULL 인 그랜드퍼더링 증강은 실삭제 대상에서 완전 제외**한다 — 어느 파생을 지워야 하는지 알 수 없고, 시각 기반 역추정은 중복 요청 허용 이후 **다른 요청의 파생본을 지운다**(V149 가 이미 폐기한 방법). 스윕 쿼리에 `NEW_RAW_SN IS NOT NULL` 을 필수 조건으로 걸고, 감사 로그에 "수동 정리 필요"만 남긴다.
  - **파일 삭제는 `StorageSubtreePolicy` 로 실경로(`toRealPath()`)를 검증하고 그 실경로로 지운다.** lexical 경로로 검증하고 lexical 경로로 지우면 심링크 교체로 원본이 삭제된다(CWE-59/367). **삭제는 열기보다 위험하다 — 잘못 열면 유출이지만 잘못 지우면 비가역이다.** 판정이 OK 가 아니면 **삭제를 건너뛰고 WARN**(원본 삭제보다 고아 파일 존치가 안전).
  - **삭제 순서**: DB 커밋 먼저 → 그다음 파일. 역순이면 "파일 없는데 DB 는 살아있음"이 된다. ⚠ `LS_DATA_LBL`·`LS_DATA_AUG_LBL_MAP`·`LS_DATA_LBL_HSTRY`·`LS_DATA_AUG_RVW`(FK 가 **부모** RAW 를 가리켜 파생 삭제로 정리되지 않는다) 는 **FK 가 없거나 방향이 달라 CASCADE 로 안 지워진다** — 명시적으로 지우지 않으면 **조용히** 고아가 남는다(FK 위반으로 시끄럽게 실패하는 게 아니라서 더 위험하다).
  - ⚠⚠ **`LS_LABEL_ATTR` 은 삭제 대상이 아니다 (2026-07-31 실측 정정 — 오지시 사고 직전에 차단됨)**: 이 테이블은 **라벨 마스터의 속성 *정의*** 이고 FK 는 `LABEL_ID → LS_LABEL` 이다(`V33`). 데이터 라벨을 참조하는 것은 **`LS_DATA_LBL_ATTR_VAL.LBL_SN → LS_DATA_LBL`**(FK 있음 → 라벨 삭제 시 정리)이다. 폐기 배치가 `LS_LABEL_ATTR` 을 지우면 **프로젝트 전체의 라벨 속성 정의가 사라진다.** 회귀 가드: `AugmentDiscardPurgeIT.라벨_마스터_속성정의_테이블은_삭제_대상이_아니다`. **이름이 비슷한 두 테이블을 혼동하지 말 것.**
  - **배치는 원자 클레임(조건부 UPDATE)** 으로 중복 실행을 막고, **최종 DELETE 에도 조건을 다시 건다**(클레임 이후 복구가 끼어들 수 있다). Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 막지 않는다.

### 포털 (외부 채널)
- **데이터 소스**: 관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임). 저작도구는 포털 DB에서 Load
- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면에 표시
- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 별도 적재, **데이터마트에 정합/반영 안 됨(단방향)**
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음
- **오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공** (ADR-013 — 포털은 데이터마트 영상 선택 + 본인 자산 업로드 전용)
- **포털 자산 업로드 (ADR-013 예외, 2026-07-17)**: 포털 사용자가 **본인 이미지(jpg/jpeg/png, 20MB/장, 50장/요청)·영상(mp4/mov/avi, 5GB, TUS 재개 업로드)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON만) 후 본인 데이터(JSON export/원본)를 다운로드한다. 업로드 자산은 신규 `LS_PORTAL_*` 테이블(V107/V108: `LS_PORTAL_ULD`·`LS_PORTAL_ULD_FRME`·`LS_PORTAL_ULD_LBL`·`LS_PORTAL_TUS_ULD`)로 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View와 **완전 분리**된다. 오토라벨링·SAM2·VLM·검수·버전관리는 미적용. 영상은 비식별 미적용(본인 데이터)이며 고정 간격 프레임 추출(`LS_SYSTEM_CONFIG` `portal.upload.frame-interval-sec` 기본 5초, 상한 maxFrames 2000). 상태: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED`. 신규 화면: 포털 업로드(`/portal/uploads`)·포털 업로드 라벨링(`/portal/uploads/:uldSn/label`). 신규 API: `/v1/portal/uploads/**`
- 반응형 웹 (PC/태블릿/모바일), WCAG 2.1 AA 준수

### DB 정책
- `klid_at` 스키마(PostgreSQL)에 저작도구 전용 테이블(LS_*) 운영 — 저작도구가 직접 소유·구성
- 관제서버 MNG_* 테이블 재사용 (READ 위주, JPA `ddl-auto=validate`)
- **관제 공유 클립 테이블 진실원·산출물 비대상**: UC-018 관제 학습용 적재가 READ하는 `MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST` 실제 스키마(복합 PK, `FILE_PATH` 등 — DB 직접 조회 확정)는 LogiCraft **ERD-024**(관제 공유 클립 ERD)에 진실원으로 기록한다. 단 `MNG_*`는 공유(READ) 스키마라 **D8/D9 산출물 비대상**(cc-doc-gen `MNG_*` prefix 규칙으로 자동 제외 — "공유(READ)" 비고만). 적재 어댑터 매핑(`CLIP_ID→VMS_CLIP_ID`, `FILE_PATH→RAW_FILE_PATH_NM`, `VDO_LEN_SEC` ms→초, `EVNT_LST.EVNT_TYPE_CD/SHT_DT` 조인)은 ERD-024 description에 명세.
- Flyway 마이그레이션: LS_* 전용 테이블은 자체 관리, **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수**
- 모든 마이그레이션 SQL은 PostgreSQL 표준 문법으로 작성 (MariaDB 고유 문법 금지)
- **표준용어·표준도메인 준수 (Critical)**: 새로 만드는 DB 컬럼·테이블은 **물리명 + 데이터 타입 + 크기(길이)** 모두 표준/산업 표준을 따른다.
  - **★우선순위 (2026-08-05 사용자 확정, 구속)**: **① 행안부 공통표준 → ② 사업 표준 → ③ (둘 다 없을 때만) 신규 등록.** 행안부가 **1순위**다. 같은 개념이 양쪽에 다른 약어로 있으면 행안부 것을 쓴다(예 재시도 — 행안부 `RTRY` ○ / 사업 `RTY` ✗). 사업표준은 행안부에 **없는 개념**을 채우는 보충이다.
    - ⚠ 실사고: 이 순서를 거꾸로(사업 우선) 적용해 `LS_DATA_INGEST.NEXT_RTRY_DT` 에서 **이미 맞던 `RTRY`(행안부)까지** 사업 약어 `RTY` 로 바꿔 `NXTM_RTY_DT` 가 됐다(V172). V175 는 **`RTY`→`RTRY` 만 되돌렸고** `NEXT`→`NXTM` 은 유지했다(`NEXT` 는 양쪽 사전 미등록). **확정 물리명 = `LS_DATA_INGEST.NXTM_RTRY_DT`**(차기 NXTM + 재시도 RTRY + 일시 DT, 전부 행안부 공통표준) — "V175 로 원래 이름 `NEXT_RTRY_DT` 로 복귀"가 **아니다**. ⚠ 동명이표 주의: `LS_CONTROL_NOTIFY_FALLBACK.NEXT_RTRY_DT` 는 V44 이래 무변경으로 공존하므로 **전역 치환 금지**. 또 같은 `LS_DATA_INGEST` 안에 `RTY_CNT`(기존)와 `NXTM_RTRY_DT`(신규)가 **의도적으로 일시 공존**한다(기존 RTY 자산 7컬럼 + 테이블명 `LS_BAT_RTY_WTNG` 통일은 별도 백로그 — 여기서 함께 바꾸지 말 것). **기존 컬럼이 이미 행안부 표준을 쓰고 있는지 먼저 확인**할 것.
    - ⚠ 도메인(타입·크기)도 같은 우선순위다 — 행안부에 그 **용어**가 등록돼 있으면 그 도메인을 쓴다(예 `DATST_NM` = 행안부 명V200, 사업 `DATA_SET_NM` 명V100 이 아니다).
  - **물리명**: program/gov·산업 표준용어에 등록된 단어의 약어 조합만 사용(임의 약어 생성 금지). 예 — 해상도=RESL, 배치=BAT, 재시도=**RTRY**(행안부), 변경=CHG, 수정=MDFCN
  - **타입·크기**: 표준용어에 연결된 **표준도메인**의 타입·길이를 그대로 채택(임의 크기 금지). 예 — 코드값 도메인은 `VARCHAR(20)`(`EVNT_TYPE_CD`=20이 표준, 32로 잡으면 드리프트 결함=감리 지적)
  - **확정 전 조회처 — 파일 정본이 유일한 판정 근거다** (레포에 커밋돼 있다):
    - `docs/LogiCraft-공공표준용어-2026.08.05 151557/` — `공통표준단어.csv`(3,284) · `공통표준도메인.csv`(123) · `공통표준용어.csv`(13,176) ← **1순위**
    - `docs/LogiCraft-사업용어-2026.08.05 151552/` — `사업표준단어.csv`(471) · `사업표준도메인.csv`(45) · `사업표준용어.csv`(1,373)
    - 인코딩 `utf-8-sig`(BOM), **디렉터리명에 공백**이 있어 shell glob 이 깨진다 → python `os.path.join` 또는 따옴표
    - ⚠⚠ **LogiCraft MCP 검색으로 "미등록"을 판정하지 마라** — `program_word_search` 는 `limit` 상한 200 + `offset` 없음이라 471건 중 **271건이 구조적으로 조회 불가**다(`count: 200` 은 캡에 걸린 것이지 전체가 아니다). 한글 키워드도 표기가 다르면 못 찾는다("형식"으로 찾으면 `FRM` 만 나오고 `FMT`(포맷)는 안 나온다). **"검색 0건 = 미등록" 추론이 이미 정상 컬럼 8건 이상을 "비표준"으로 오판시켰다.** MCP 는 개별 확인·등록에만 쓰고 판정은 **CSV grep** 으로 한다.
    - 표준에 없으면 소유권 규칙 따라 등록 후 사용 (→ 아래 소유권 주의)
  - ⚠ **LogiCraft 사전 수정 전 `createdBy` 확인 필수**: `createdBy: null` = **KLID-BM 표준용어정의서 2026-05-28 배포분**이라 우리 수정 대상이 아니다(사업 사전은 관제서버·포털 프로젝트가 공유한다). **CSV 에는 `createdBy` 컬럼이 없고** `출처`·`관리기관` 공란이 곧 그 배포분 표식이므로, 수정 직전에는 반드시 MCP 응답의 `createdBy` 를 다시 확인한다.
  - **3계층 강제 (재작업 반복 차단 — 수동 규칙·사후 QA 가 모두 놓쳤던 지점)**:
    1. **write 시점 훅(결정론적)**: `.claude/hooks/standard-term-guard.sh`(PostToolUse Edit|Write)가 마이그레이션 SQL(`db/migration/V*.sql`)·JPA 엔티티에 `CREATE TABLE`/`ADD COLUMN`/`@Column` 등 새 스키마 DDL 을 감지하면 표준용어·표준도메인 검증 리마인더를 자동 주입한다(비차단, 서브에이전트 컨텍스트에도 도달). 이 리마인더가 뜨면 커밋 전에 반드시 물리명+타입+크기를 검증·정정한다.
    2. **위임 프롬프트 규칙**: PM 이 DB 를 건드리는 Phase 를 `developer-*` 에이전트에 위임할 때, 위임 프롬프트에 **이 표준용어·표준도메인 제약 + 관련 확정 조회값(메모리)** 을 반드시 포함한다(서브에이전트는 메인 세션 메모리를 자동 상속하지 않으므로 명시 전달 필수).
    3. **QA 검사 명시**: DB 변경 Phase 의 `database-reviewer`/`design-verifier` 호출 프롬프트에 **"새 컬럼·테이블 물리명(표준단어 조합) + 타입·크기(표준도메인) 준수 여부"** 검사를 명시 항목으로 넣는다. 비표준 발견 = HIGH → DEV_FIX(표준어·표준크기로 정정).

### 데이터마트 적재용 View
- `klid_at` 스키마에 4종 View 제공 — 검수 완료(`LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`) 영상만 노출
  - `V_COMPLETED_VIDEO` : **V174 재작성 — 30컬럼**(정본 `docs/관제-저작도구-데이터연동-규격서-20260805.md` §5-1). 관제가 `datasets`·`dataset_versions` 를 SELECT 1회로 채우고 산출물을 픽업하는 계약면이다. 영상 메타 + 원본 영상 경로(`ORGNL_VDO_PATH_NM`) + 검수 완료 일시(`RVW_CMPTN_DT`) + **산출 폴더 경로(`OUTPUT_PATH_NM`)·프레임수(`FRME_CNT`)·산출 용량(`DATA_ETBL_CPCT`)** — 최신 SUCCEEDED/PARTIAL export(`LS_DATASET_EXPORT`) 조인. LATERAL 4개(산출 원장·비식별 이력·인입·라벨 집계)가 모두 최대 1행이라 **영상 1건=1row** 불변.
    - ⚠ **미export 영상의 두 값은 대칭이 아니다** — `FRME_CNT` 는 **0**(규격서가 NOT NULL 로 공표, 관제 `datasets.img_nocs` 공급)이고 `OUTPUT_PATH_NM`·`DATA_ETBL_CPCT` 는 **NULL** 이다. 구 서술 "미export 영상은 두 값 null" 은 **폐기**(V174).
    - ⚠ **출력명 6건이 V174 에서 표준 물리명으로 바뀌었다** — `DURATION_SEC`→`VDO_LEN_SEC` · `FRAME_CNT`→`FRME_CNT` · `REVIEW_COMPLETED_AT`→`RVW_CMPTN_DT` · `ORIGINAL_VIDEO_PATH`→`ORGNL_VDO_PATH_NM` · `EXPORT_PATH_NM`→`OUTPUT_PATH_NM` · `EXPORT_STTS_CD`→`OUTPUT_STTS_CD`. **V173 의 원장 rename 은 뷰 출력명을 바꾸지 않았다**(PostgreSQL 은 `RENAME COLUMN` 시 뷰 *본문*만 추종하고 출력명은 자동 별칭으로 보존한다) — 관제 계약면을 실제로 바꾼 것은 **V174** 다.
    - ⚠ 인입(`LS_DATA_INGEST`) 유래 컬럼(`EVNT_CLSF_CD`·`EVNT_CTGRY_CD`·`LCLGV_NM`·`SRC_*_INCL_YN`)은 **동결 스냅샷에 넣지 않고 LATERAL 조인**한다(설계 D1) — 인입은 관제 수신 원장이라 불변이므로 동결과 라이브 조인의 결과가 같다. 조인 규칙은 앱의 단일 진실원 `IngestSourceLink` 와 동일. **파생영상은 대응 인입 행이 없어 `SRC_*_INCL_YN` 이 NULL** 이다.
    - ⚠ `GEN_AI_YN` 은 동결 `AI_CRT_YN` 을 읽지 **않는다**(설계 D2) — `LS_DATA_RAW.SRC_TYPE IN ('GENERATED','AUGMENTED')` 로 직접 도출한다.
  - `V_COMPLETED_FRAME` : 프레임 페어 (`ORIGINAL_PATH`=원본 `SRC_FILE_PATH_NM`, `DEIDENTIFIED_PATH`=비식별 `DE_IDNTF_SRC_FILE_PATH_NM`) — 신규 추출은 `{base}/frames/raw|deid/{rawSn}` 로 분기 저장돼 두 경로가 항상 상이(원본 덮어쓰기 0)
  - `V_COMPLETED_LABEL_CHANGE`(V114 신설) : 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, `CHG_KIND_CD`=ADDED/UPDATED/DELETED, APPROVED 게이트) — 라벨 좌표·속성 본문은 검수 승인 export 폴더 JSON에 존재하므로 뷰로 중복 노출하지 않음(구 `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 제거)
  - `V_COMPLETED_META` : 시계열 메타 (`RVW_STTS_CD='APPROVED'` 만)
- **라벨 내용 뷰 제거(V114) = 관제 연동 계약 변경(관제팀 협의 대상)**: 관제서버는 이제 ①산출 폴더 경로(`V_COMPLETED_VIDEO.OUTPUT_PATH_NM` — V174 개명, 구 `EXPORT_PATH_NM`) 픽업 + ②변경점(`V_COMPLETED_LABEL_CHANGE`)·메타(`V_COMPLETED_META`)만 DB 뷰로 쿼리한다. 라벨 본문 뷰를 SELECT 하던 관제 쿼리는 파손되므로 협의 필요(M2M deprecated·뷰 SELECT 방식)
- 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 통지 수신 후 RAW_SN 으로 4 View 단순 SELECT → 영상 1건=1 row UPSERT
- **★ 검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다 (2026-07-28 사용자 확정 — 구속)**: 검수 승인(`APPROVED`)되어 관제에 통지된 영상은 **어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다.** 특히 **비식별 누락 신고(`DE_IDENT_YN='F'`) 구간에도 관제 접근을 차단하지 않는다** — `V_COMPLETED_VIDEO`·`V_COMPLETED_FRAME` 에 신고 필터를 넣는 안, 신고 중 `OUTPUT_PATH_NM`(V174 개명, 구 `EXPORT_PATH_NM`)/`DEIDENTIFIED_PATH` 를 NULL 로 비우는 안은 **모두 폐기**한다(관제가 보던 행이 예고 없이 사라져 관제 측 배치가 삭제·오류로 오인).
  - **함의(의도된 설계, 결함 아님)**: 비식별 신고 게이트(`DeidentReportGate`)는 **저작도구 앱 내부 통로에만** 적용되고 **관제 경계(뷰·통지)에는 적용되지 않는다.** 따라서 신고 구간에도 관제는 마스킹 실패 픽셀이 남은 export 폴더·비식별 프레임 경로에 접근한다. 이를 "잔여 누수"로 재분류해 다시 고치려 들지 말 것.
  - **닫혀 있는 흐름**: 신고 접수 시 `TASK_MODIFIED`(META_UPDATED) 발행 → 관제가 재픽업, 신고 해제(RESOLVED) 시 APPROVED 영상 export 재산출 + 재통지 → 관제가 정상 산출물로 갱신. 또 `V_COMPLETED_VIDEO` 가 `DE_IDNTF_YN` 을 컬럼으로 내보내므로 관제가 원하면 자체 판단도 가능하다(우리가 강제하지 않을 뿐).
- 비식별 영상 파일 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 에 적재된 값을 사용(문자열 치환 도출 아님, View 미포함). `STORAGE_RAW_PATH==STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`, 의도된 동일 설정)여도 영상은 비식별본만 `videos/{rawSn}/` 하위(mock=저작도구가 `deidentified.mp4` 기록 / KPST 공유마운트=KPST 가 `export_path` 에 직접 산출, 응답 `fileName` 으로 경로 기록)에 위치하고 원본은 관제 NAS 절대경로를 기록만 하므로 충돌 없음
- 모두 `CREATE OR REPLACE VIEW` 라 멱등 — Flyway 재실행해도 안전

## CVAT 포팅 전략

CVAT 원본은 Django + TypeScript, 본 프로젝트는 Spring Boot + TypeScript. 전체 fork가 아닌 **9개 독립 모듈을 Phase별로 포팅**한다. (출처: `docs/analysis/index.md`)

| Phase 연관 | CVAT 포팅 모듈 | 언어 | 참고 문서 |
|:--------:|-------------|:---:|----------|
| Phase 3 | manifest.jsonl 포맷 (프레임 매니페스트) | Java | portable-modules/04 |
| Phase 3 | TUS 재개 가능 업로드 (관리 화면/대용량) — **1차 적재 경로 아님: 관제 학습용 설정 기반 적재로 대체(폐지 예정)** | Java | portable-modules/03 |
| Phase 3 | AI 함수 핸들러 템플릿 | Python(ai-server) | portable-modules/05 |
| Phase 6 | MASK ↔ RLE ↔ Polygon 변환 | Java | portable-modules/02 |
| Phase 6 | 좌표 변환/회전 유틸 | Java + TS | portable-modules/06 |
| Phase 6 | 캔버스 드로잉 패턴 | TS | analysis/canvas-drawing.md |
| Phase 6 | 트랙 보간 알고리즘 | Java | portable-modules/01 |
| Phase 10 | YOLO/COCO 변환 | Java | portable-modules/07 |
| Phase 10 | 품질 충돌 감지 (GT Job) | Java | portable-modules/09 |
| Phase 3/배치 | RQ Worker 패턴 → Quartz Job 매핑 | Java | portable-modules/08 |

> `cvat/` 디렉토리는 `.gitignore` 처리된 참조용. CVAT 소스 직접 import 금지 — 분석 문서 기반으로 Java에 재구현.

## 핵심 산출물 목표
- 이미지 학습데이터 **10만장** (SFR-16)
- 영상 학습데이터 **5,000건** (30초 이상/건, SFR-17)
- 생성형 AI 외부 증강 3종: WINTER / NIGHT / RAIN (SFR-07) — 해상도 변경은 외부 위탁이 아닌 **저작도구 내부 수행**(SFR-06-03, 아래 '해상도 변경' 참조)

## 주의사항

### 외부 API 연동
- 비식별·ai-server·VLM — **Resilience4j로 타임아웃/재시도/서킷 브레이커 적용 필수**
- 비식별 API 실패 시 영상 상태 `DE_IDENT_YN='F'`로 마킹 + 재시도 큐. 원본 절대 삭제 금지
- 관제서버 세션 토큰은 저작도구가 발급하지 않음 — 검증 실패 시 관제서버 로그인 페이지로 리다이렉트
- 관제/포털 양방향 통합 API 및 외부 학습데이터 API는 deprecated — 재구축 전까지 미연동
- **관제서버 통지 + 조회 API**: `ControlNotifyClient`가 `TASK_COMPLETED`·`TASK_MODIFIED` 통지를 영상 단위로 송신(수정 요약만, 본문 미포함) — 요청 ID idempotency + dead-letter + 재등록 큐 + Resilience4j 적용. 관제서버는 통지 수신 후 저작도구 API를 호출하여 상세 데이터 조회. 단방향 outbound 통지 + inbound 조회 API 제공, 양방향 M2M 인증은 여전히 deprecated

### 파일 업로드 (관리 화면 + TUS)
- **1차 적재 경로 아님 — 관제 학습용 설정 기반 적재로 대체(폐지 예정)**: 영상 1차 적재는 관제서버가 공유 DB에서 학습용으로 설정한 영상을 주기 배치가 픽업하는 방식이며, 아래 TUS 업로드는 1차 적재 경로로 사용하지 않는다(코드 정리 후속)
- 확장자 allowlist + 파일 크기 제한 + MIME 검증 필수
- TUS 프로토콜(재개 가능 업로드) 포팅 — CVAT portable-modules/03 참고 (내부 관리 화면 영상 적재용)
- **포털 자산 업로드 신설 (ADR-013 예외, 2026-07-17)**: 포털 사용자 본인 자산(이미지 20MB/장·50장/요청, 영상 5GB) 업로드 허용. 영상은 별도 포털 TUS 세션(`LS_PORTAL_TUS_ULD`)으로 재개 가능 업로드. 내부 파이프라인·데이터마트와 분리된 `LS_PORTAL_*` 경로이며 오토라벨링·검수 미제공 — 상세는 위 '포털 (외부 채널)' 참조

### 개인정보 보호
- 영상 암호화 저장, 로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)
- 비식별 처리 이력은 영상 단위로 기록
- **★export 개인정보 3필드 정책 — 원천·비식별 두 축을 모두 판정한다 (2026-08-04 사용자 확정, 구속)**: 학습데이터 export JSON 의 `anonymity`/`pseudonymity`/`privacy_included` 는 **산출종류 × 블록**의 4칸으로 정해지며, 판정 단일 원천은 `dataset/export/ExportPrivacyPolicy` 한 곳이다(복제 금지).

  | 블록 | 원천(`orgnl`) | 비식별(`deid`) |
  |---|---|---|
  | `video`(영상 단위) | **관제 인입값** `LS_DATA_INGEST.*_INCL_YN`(V166 신설 · **V170 fail-closed DB DEFAULT `N`/`N`/`Y`**) 그대로. 관제 미송신 필드는 **`null` 유지**(지어내지 않음) | 영상 단위 수동값(`LS_DATA_RAW`, V163) 우선, 미입력 시 `Y`/`N`/`N` |
  | `image`(프레임 단위) | **정책 상수 `N`/`N`/`Y`**(`ORGNL_DEFAULT_*`) — 프레임 단위 원천 판정 **데이터가 존재하지 않기** 때문 | 프레임 단위 수동값(`LS_DATA_SRC`, V130) 우선, 미입력 시 `Y`/`N`/`N` |

  - **왜 원천에 값을 채우는가(왕복 근거)** — export JSON 이 **다시 읽혀 적재되는 자산**이 된다(*"차후에 데이터마트를 업로드하는 기능(json 읽어서 넣기)도 생길 예정"*). 원천이 `null` 이면 재적재 시 **"판정 안 함"과 "값 유실"이 구분되지 않는다.**
  - **두 블록의 원천값은 같아 보여도 출처가 다르다 — 통합 금지**: `video`=관제가 실제 판정한 값 / `image`=정책 상수. 관제가 `PRVC_INCL_YN='N'` 을 보내면 `video=N` / `image=Y` 로 **갈리는데 모순이 아니라 입도가 다른 사실**이다.
  - **`LS_DATA_SRC` 에 원천용 DB DEFAULT 를 걸지 않는다** — 그 3컬럼은 **한 벌인데 두 축이 공유**하므로 원천 기본값(`N`/`N`/`Y`)을 걸면 비식별의 "미입력"이 사라져 `deid.anonymity` 가 `Y`→`N` 으로 **뒤집힌다**.
  - ★ **비식별 축은 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 적재한다 (2026-08-04)** — `LsDataRaw`(`@Builder` 생성자)·`LsDataSrc`(팩토리)에서 채우고 수정은 **기존 라벨링 화면 경로**(`PUT /v1/frames/{srcSn}/privacy-meta` · `PUT /v1/videos/{rawSn}/privacy-meta`)를 그대로 쓴다(새 화면·새 API 없음). ⚠ **DB 컬럼 DEFAULT 가 아니라 애플리케이션 팩토리인 이유**: 두 엔티티에 `@DynamicInsert` 가 없어 **Hibernate 가 모든 컬럼을 명시 INSERT**(값 없으면 명시적 NULL)하므로 DEFAULT 가 주 적재 경로에서 **적용될 수 없다**. 반대로 인입(`LS_DATA_INGEST`)은 **관제가 우리 코드를 거치지 않고 직접 INSERT** 하므로 DB DEFAULT 가 유일한 수단이다 — 두 축이 다른 기법을 쓰는 기준은 **"누가 INSERT 하는가"**다.
  - **`null` 이 남는 경로는 레거시 행 하나뿐**이다(이 변경 이전에 적재된 행). 그래서 `ExportPrivacyPolicy` 의 **프리필 상수(`DEID_DEFAULT_*`)를 제거하지 않고 안전망으로 존치**한다(제거하면 그 행들의 export 가 빈 값이 된다). 그 행들의 export 산출값은 프리필 상수와 같아 **파일 내용은 달라지지 않는다.** ⚠ **구 서술 "`null` 이 남는 경로는 둘뿐 — ① 레거시 행 ② 비식별 누락 신고 리셋 직후(= 사람이 다시 판정해야 한다)" → 폐기(2026-08-04)**: 같은 날 확정으로 **신고가 개인정보 3필드를 리셋하지 않게** 되어(아래 「비식별 누락 신고」 절) ② 경로가 소멸했다. **결론(프리필·상수 존치)은 그대로**이며 근거만 ①로 좁혀졌다.
  - **기존 행 백필 없음**(인입·비식별 양축 공통) — ①소급 UPDATE 는 "관제/사람이 실제로 판정한 값"과 영구히 구분되지 않는 **사실 날조**이고 ②전 행 rewrite 는 2노드 무중단 배포에 불리하며 ③백필하면 레거시 행의 **"아직 재판정하지 않았다"(`DERIVED`) 신호가 사라져** 아래 프리필 존치 논리와 충돌한다. 레거시 행은 프리필(비식별)·`null`(원천 video)로 남는다.
  - **`*Source`(MANUAL/DERIVED)·프리필은 존치한다 (2026-08-04 검토 확정)** — "적재 기본값 vs 사람이 고른 값"을 읽는 소비자는 0 이지만, **레거시 행(이 변경 이전 적재)에는 여전히 `NULL` 이 있고** 그 행에서 `DERIVED` 가 **"아직 값이 없다"**는 신호로 유효하다. 응답 필드 삭제는 외부 FE 계약 파괴이기도 하다. 따라서 **① `*Source` 응답 필드 ② GET 의 `NULL`→상수 프리필 ③ FE 가 미터치·`DERIVED` 필드를 `null` 로 전송하는 규약** 셋 다 유지한다. ⚠ **구 존치 근거 "비식별 신고 리셋이 항상 명시적 `NULL` 을 쓰므로(`DeidentReportService`) 리셋 직후 `DERIVED` 가 '아직 재판정하지 않았다'는 신호로 살아난다" → 폐기(2026-08-04)**: 리셋 자체가 폐기돼 그 경로가 없다. **존치 결론은 불변** — 근거만 레거시 행 + FE 계약으로 교체됐다(근거가 무너졌다고 필드·프리필을 제거하지 말 것). "누가 실제로 손댔나"는 `LS_TASK_EVENT_LOG` 감사가 컬럼과 **독립적으로** 담당한다(INSERT 자동 채움은 감사를 호출하지 않고 사람의 PUT 만 호출).
  - ⚠ **잃는 것(인지·수용, 되돌리지 말 것)**: DEFAULT/적재값이 생기면 **"미송신"과 "실제로 N 판정"**, **"적재 기본값"과 "사람이 고른 값"**이 값만으로는 구분되지 않는다.
  - **관제 인입값은 비식별 누락 신고가 건드리지 않는다** — 인입은 **관제가 보낸 수신 원장**이고 원천 영상 자체는 신고로 달라지지 않으므로 서버가 보정·되돌리지 않는다. 저작도구가 `LS_DATA_INGEST` 를 쓰는 통로는 내부 업로드 1곳뿐이며 `LsDataIngestWriteGuardTest` 가 이를 기계적으로 고정한다. ⚠ **구 서술 "신고는 비식별 축(`LS_DATA_SRC`·`LS_DATA_RAW`) 3필드만 `null` 로 되돌린다" → 폐기(2026-08-04)**: 신고는 **비식별 축도 되돌리지 않는다**(아래 「비식별 누락 신고」 절). 즉 두 축 모두 신고의 영향을 받지 않으며, 인입 축을 제외한다는 결론만 유효하다.
  - **재export 세트** — 원천 축 값은 `LabelContentHasher` 입력에 편입돼 있어(조건부 블록 `SPRV`, 값 전무하면 기존 해시 유지) 관제가 판정을 정정 재송신한 뒤의 재승인이 멱등 skip 으로 stale 고착되지 않는다. 우리 화면에 원천 축 쓰기 경로가 없으므로 `TaskModifiedEvent` 발행 지점은 없다(비식별 축은 종전대로 이벤트+해시 세트를 갖춘다).

  ⚠ **폐기된 구 정책(2026-08-03) — 되돌리지 말 것**: *"원천은 비식별 처리 전이라 판정이 성립하지 않으므로 `orgnl` 3필드는 전부 `null`"*. 폐기 사유 — ①관제가 원천 판정을 **무조건 채워 보내며**(사용자 확정) 그 수신 통로가 V166 으로 실재한다 ②export JSON 이 재적재되는 왕복 자산이 되어 `null` 이 "유실"과 구분되지 않는다. **단 그 정책의 "값을 지어내지 않는다" 조항은 `video` 원천 축에서 존치**된다(관제 미송신 = `null`). 더 앞선 구 정책(2026-07-31, 원천을 상수로 단정 + `DEIDENTIFIED` 수동값 무시)은 여전히 폐기 상태다.
  - **수동값 원천은 블록마다 자기 입도의 축**이다 — `video` 블록(`VideoMetaMapper`) ← **영상 단위** `LS_DATA_RAW.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN`(V163, 화면 `GET/PUT /v1/videos/{rawSn}/privacy-meta`) / `image` 블록(`NiaJsonBuilder`) ← **프레임 단위** `LS_DATA_SRC.*_INCL_YN`(V130). 판정 **로직**은 `ExportPrivacyPolicy` 한 곳에서만 정한다(복제 금지).
  - **두 블록의 값이 다른 것은 모순이 아니다** — `video.privacy_included=Y` / `image.privacy_included=N` 은 **"영상 어딘가엔 있지만 이 프레임엔 없다"**는 서로 다른 입도의 사실이다.
  - ⚠ **폐기된 구 정책(2026-07-31) — 되돌리지 말 것**: 구 정책은 정확히 반대였다(원천=개인정보 있음 + `ORIGINAL` 한정 수동 override, 비식별=상수 고정 + **수동값 무시**). 비식별에서 override 를 막았던 이유는 "프레임 수동값을 태울 원천이 `video` 에 없어 같은 문서에서 모순"이었는데, 그 모순의 실체는 **"원천이 없어서 기본값인 video" vs "사실인 image"**의 충돌이었다. 영상 단위 저장소(V163) 신설로 **두 블록 모두 사람이 입력한 사실**을 읽게 되어 억제 근거가 소멸했다(구 주석이 명시한 해소 조건 충족). 따라서 "DEIDENTIFIED override 를 풀면 모순이 재발한다"는 억제도 함께 폐기된다.
  - **`PRVC_TYPE_CD`/`PRVC_YN` 파생은 export 에서 소멸**했다(구 사유 "`ORIGINAL` 이 null 이므로" → **2026-08-04 정정**: 원천값이 생겼지만 그 원천은 **관제 인입값·정책 상수**이지 이 두 컬럼이 아니다. 결론은 그대로 소멸). ★2026-08-03 DEV_FIX 로 **프레임 메타 GET 프리필에서도 폐기**됐다 — 두 컬럼은 이제 **비식별 대상 판정(`needsDeidentify`)에만** 남는다.
  - **★두 패널의 프리필은 같은 상수를 참조한다** (2026-08-03 DEV_FIX): 프레임 패널(`FramePrivacyMetaService`)이 구 `PRVC_TYPE_CD=='ANONY' ? Y : N` 파생을 남겨 둬, 업로드가 `PRVC` 고정이 된 뒤로 **실질 모든 신규 영상에서 화면은 `anonymity=N`** 인데 deid export `image` 블록은 기본상수 `Y` 를 실었다(사용자가 보는 값 ≠ 파일 값, 영상 패널은 `Y` 인데 프레임 패널만 `N`). 이제 두 패널 모두 `ExportPrivacyPolicy.DEID_DEFAULT_*` 를 **참조**한다 — 상수·판정을 복제하면 한쪽만 갱신돼 어긋난다(이 결함이 그 사례).
  - **★파생영상(증강·해상도)은 부모의 영상 축 판정을 생성 시점 1회 계승**한다(`LsDataRaw.copyPrivacyMetaFrom`, 촬영환경 복사와 같은 지점). 계승하지 않으면 파생 프레임은 부모 값을 복사받는데 영상 축만 미입력이라 같은 문서에서 `image="Y"` / `video="N"` 이 나는데, 이는 위 "입도가 다른 두 사실"이 정당화한 방향의 **역방향이라 성립 불가능**하며 실질은 개인정보 잔존의 **과소 신고**다. 이후 부모 정정은 파생으로 재전파하지 않는다(촬영환경과 동일 스냅샷 시맨틱).
  - **★영상 축 PUT 은 ①동시 승인과 직렬화되고 ②신고 구간에 412 로 차단된다** (2026-08-03 DEV_FIX). ① 구 주석의 "경합 창 자체가 없다"는 **오류**였다 — 동결은 이 컬럼을 안 읽지만 **실소비자인 export 가 라이브 raw 를 직독**하므로 창은 동결→산출로 옮겨간 것이었다(PUT 이 PENDING 관측 → 통지 미발행 확정 → 승인 커밋 → async export 가 구 스냅샷 null 로 `v1` 산출 → 재산출 트리거 없음 = 비가역 과소 신고). 지금은 `flush`(raw 행락) → **`acquireRawLock`(rawSn advisory, `materialize` 와 동일 락)** 순서로 잠그고 상태는 **잠금 없이** 읽는다. ⚠ **`LS_RAW_DATA_STATUS` 를 `FOR SHARE` 로 잠그면 안 된다 — 교착(40P01)** (2026-08-03 2차 정정): 그러면 이 트랜잭션이 `raw → status` 순서가 되는데 `BatchTransitionService`(`markRawDataProcessingBlocked`/`Completed`/`Failed`)가 같은 `REQUIRES_NEW` 안에서 **`status`(조건부 벌크 UPDATE) → `raw`(dirty checking)** 순서로 잠근다. "승인 경로는 raw 를 안 잠근다"는 1차 근거는 참이지만 **교착 상대가 승인이 아니라 배치**였다(배치 진입점은 주기 배치·수동 재처리 등 모든 배치 시작에서 돈다). advisory 를 쓰면 간선이 `raw → advisory` 하나뿐이라 기존 불변식(`EnvironmentMetaService`·`materialize`)에 합류한다. 정적 회귀 가드 = `LockOrderGuardTest`. ② 신고 구간은 **"비식별이 잘못됐다"고 알려진 구간**이라, 그 잘못된 비식별본 위에서 내린 개인정보 판정을 이 구간에 새로 쓰면 resolve 후 그 값이 그대로 관제로 나간다(같은 구간에 라벨 조회를 412 로 막는 것과 같은 축). **게이트는 영상 축·프레임 축 PUT 양쪽에 건다**(단건 + 벌크 — 한쪽만 걸면 비대칭을 없앤 게 아니라 옮긴 것이다). 촬영환경(`EnvironmentMetaService`)은 **PII 축이 아니라** 위 근거가 성립하지 않으므로 **제외**한다. ⚠ **구 근거 "신고가 이 3필드를 재판정 대상으로 리셋하는데 같은 구간에 PUT 으로 되돌릴 수 있으면…(신고는 두 축을 함께 리셋한다)" → 폐기(2026-08-04, 리셋 폐기)**. **게이트 자체는 리셋 여부와 무관하게 성립하므로 유지한다 — 근거가 바뀌었다고 412 를 걷어내지 말 것.** **GET 은 차단하지 않는다** — 값이 PII 가 아니고 막으면 화면이 안 뜬다. ③ **영상 축 변경은 `LS_TASK_EVENT_LOG` 에 행 단위 감사**(`PRIVACY_META_UPDATE`, actor+사유. 판단값 Y/N 은 남기지 않는다 — CWE-359). ⚠ `PRIVACY_META_RESET` 타입·팩토리는 **신규 발생이 없지만**(신고 리셋 폐기, 2026-08-04) **이미 적재된 과거 행 판독을 위해 존치**한다 — 삭제 금지. 라벨 이력 `LS_DATA_LBL_HSTRY` 는 `SRC_SN NOT NULL` 인 프레임 스코프라 영상 축을 담지 못할 뿐, "행 단위 감사가 불가능"한 것은 아니었다.
  - **GET 응답은 수동값 우선 + 기본상수 프리필 + `*Source`(MANUAL/DERIVED) 병기** — 화면이 상수를 하드코딩하지 않게 BE 가 내려준다(상수 원천은 `ExportPrivacyPolicy.DEID_DEFAULT_*`). ⚠ FE 는 **사용자가 직접 고르지 않은 필드를 null 로 전송**해야 한다(DERIVED 프리필을 되돌려 보내면 상수가 사람의 판정으로 승격된다 — BE 는 출처를 알 수 없어 막지 못한다).
  - **영상 단위 값은 라이브 `LS_DATA_RAW` 에만 두고 동결 스냅샷 컬럼을 만들지 않는다** — 소비자가 export JSON 하나뿐이고(데이터마트 뷰에 없음) export 는 라이브 raw 를 이미 로드하기 때문. 대신 ①승인 후 수정 시 `TaskModifiedEvent(exportRegenerated=true)` 로 새 버전 전량 재생성 ②`LabelContentHasher` 입력 편입(멱등 skip 방지)이 **세트로** 필요하다(하나만 빠지면 저장은 됐는데 산출물이 안 바뀐다).
- **비식별 누락 신고** (R1 v1.14 수동 흐름): 작업자가 개인정보 노출을 발견하면 신고 → 작업락 + `DE_IDENT_YN='F'`. **★개인정보 3필드(익명/가명/PII 포함여부)도 리셋하지 않고 보존한다 (2026-08-04 사용자 확정 — 구 "리셋" 정책 폐기, 구속)**: 구 정책은 **프레임 축(`LS_DATA_SRC`, V130)과 영상 축(`LS_DATA_RAW`, V163) 3필드를 모두 `null` 로 리셋**했고 그 근거는 *"그 판정은 비식별이 잘못된 영상에서 내려진 것이므로 재판정 대상이며, 한 축만 리셋하면 export 의 video/image 가 근거 없이 비대칭이 된다"* 였다(행 단위 리셋 감사 `LS_DATA_LBL_HSTRY`·`LS_TASK_EVENT_LOG PRIVACY_META_RESET` 동반). **폐기 사유**: 바로 아래 라벨 보존 정책(2026-07-27)과 **같은 취지** — 신고는 "비식별이 잘못됐다"는 신호일 뿐 **사람이 입력한 판정도 라벨과 같은 작업 결과**이므로 폐기하지 않고, resolve 후 **기존 판정을 그대로 이어서** 진행한다. stale 우려는 신고 구간의 export 산출 보류 + 해제 시 재산출·재통지가 막는다. 리셋 감사 이벤트 타입·팩토리는 **과거 행 판독용으로 존치**(신규 발생 0). **★라벨은 삭제하지 않고 보존한다 (2026-07-27 사용자 확정 — 구 "해당 영상 전체 라벨 삭제 + `SAVE_REASON='DEIDENT_REPORT'` 비활성 스냅샷" 정책 폐기)**: 신고는 "비식별이 잘못됐다"는 신호일 뿐 라벨 작업 결과를 폐기할 근거가 아니며, 그 스냅샷은 `DATA_SRC_SN=NULL`(영상 스코프)이라 srcSn 스코프 조회·롤백 진입점이 없는 write-only 이력이었다(D-ISSUE-25). **스냅샷도 남기지 않는다.** 대신 신고 구간 동안 **라벨 조회를 차단**한다(`DE_IDENT_YN='F'` 인 영상의 `GET /v1/frames/{srcSn}/labels` → 412, 역할 무관 — 영상 스트리밍·마킹 게이트와 동일 정책. 라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족). **라벨 저장·수정도 같은 게이트가 412 로 차단한다** (2026-08-04, C-ISSUE-22 — 구 서술 *"저장·수정은 기존 작업락으로 409 차단"* 폐기: 작업락은 6h 만료 후 회수되는데 `'F'` 는 resolve 까지 남아 조회 412 ↔ 저장 200 비대칭이 열렸다. 아래 '차단 범위' ⑨ 참조). resolve 로 `'F'→'Y'` 가 복원되면 게이트가 자동 해제되어 **보존된 기존 라벨을 그대로 재사용**한다(별도 복원 API 없음). 자동 재비식별 큐는 폐기 — 작업자/검수자가 **외부 솔루션으로 수동 비식별화** 후 `POST /v1/deident-reports/{rprtSn}/resolve`(WORKER 본인 배정/REVIEWER 전체)로 OPEN→RESOLVED 전이 + 작업락 해제. APPROVED 영상 신고 시 `TASK_MODIFIED` 통지 발행. **마킹 단계**(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`) + **라벨링 단계**(srcSn 기준, `POST /v1/labels/{srcSn}/deident-report`) 양쪽 가능. 적재 테이블 `LS_DEIDENT_REPORT`.
  - ⚠ **구 서술 "마킹 단계 … 구현됨(B-ISSUE-28)" 은 BE 만 구현된 상태를 뭉뚱그린 것이었다** — API 는 있었으나 **마킹 화면(`MarkingPage`)에 신고 버튼이 없어 도달 경로가 0** 이었다(FE 호출 0건). 2026-08-05 FE 신설로 해소.
- **★ 신고에는 단계가 있고, 해소 후 재개 지점이 그 단계로 갈린다 (2026-08-05 사용자 확정, 구속 · V171 `LS_DEIDENT_REPORT.DCLR_STP_CD`)**

  | 신고 단계 | 접수 조건 | 해소(resolve) 후 재개 지점 |
  |---|---|---|
  | **MARKING** (`POST /v1/videos/{rawSn}/deident-report`) | **배치 단계가 `MARKING_READY` 일 때만**(아니면 412) | 비식별 재수행 결과 위에서 **마킹부터 다시** — 배치 단계를 `MARKING_READY` 로 되감고 활성 마킹(`PENDING`/`VLM_REQUESTED`)을 `SKIPPED` 로 종결해 재마킹 409(V142 부분 유니크)를 푼다 |
  | **LABELING** (`POST /v1/labels/{srcSn}/deident-report`) | **기존 동작 유지**(배치 단계 무관 — 검수 완료 영상 신고가 정상 동선이다) | **프레임 이미지만 다시 뽑아** 라벨링을 이어간다 — 마킹 유지, **라벨 좌표 보존** |

  - **왜 마킹 단계만 `MARKING_READY` 로 제한하는가**: 그 상태는 **선두 비식별 성공 직후·마킹 이전**이라 **프레임 행(`LS_DATA_SRC`)도 라벨도 아직 존재하지 않는다**(프레임 추출은 마킹 완료로 트리거되는 배치의 `FRAME_EXTRACT` 단계이고, 그 배치는 진입 시 단계를 `PROCESSING` 으로 전이한다). 따라서 ①재마킹이 파괴할 작업 결과가 없고 ②검수 완료(APPROVED) 영상의 강등 충돌이 없으며(APPROVED 는 배치 단계가 `COMPLETED`) ③마킹 화면에서만 도달 가능한 상태라 단계 판정이 상태로 확정된다.
  - **거부는 412 한 종류·문구 한 종류다** — `PROCESSING`/`COMPLETED`/`FAILED`/`PENDING` 을 구분해 알리지 않는다. 구분하면 응답이 **영상 처리 단계를 알려주는 오라클**이 된다(CWE-209 — 스트리밍만 404 로 통일한 전례와 같은 취지). FE 는 `MARKING_READY` 가 아니면 버튼을 비활성화해 사유를 다 적고 제출한 뒤에야 거부되는 동선을 없앤다.
  - **라벨링 재개는 `DeidentFrameAttacher.attachDeidentFrames(refreshExisting=true)` 를 재사용**한다 — 기존 `LS_DATA_SRC` 행을 dirty-update 하므로 `SRC_SN` 이 보존되어 라벨 FK 가 끊기지 않는다. ⚠ **`FfmpegFrameExtractor` 를 쓰면 안 된다** — `LsDataSrc.create()` 로 **새 행을 INSERT** 해 기존 라벨이 고아가 된다. 비식별 영상 경로는 **`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값을 읽는다**(조합·추측 금지 — mock=`deidentified.mp4` / KPST=`{stem}-mask{ext}`). 경로가 없으면 재추출을 **건너뛰고 WARN**(fail-closed).
  - **마킹 재개는 상태 되감기까지만** 한다 — 실제 재실행은 사람이 다시 마킹하면 기존 `MarkingCompletedEvent → MarkingBatchBridge` 가 그대로 탄다(파이프라인을 재구현하지 않는다).
  - **기존 2종 이벤트는 무변경**이다 — `DeidentGateReopenedEvent`(항상, VLM 위탁 재개) · `DeidentReportResolvedEvent`(APPROVED 한정, export 재산출·관제 재통지). 단계별 재개는 **신설 `DeidentStageResumeEvent`** 가 담당하며 소비자는 `AFTER_COMMIT` 리스너다(`'F'→'Y'` 복원 커밋 전에 돌면 재개 작업이 자기 게이트에 스스로 막힌다).
  - **단계 미상(NULL)은 백필하지 않고 재개 이벤트도 발행하지 않는다** — 컬럼 신설 이전 신고는 어디서 접수됐는지 알 수 없고, 지어내서 마킹으로 오판정하면 **라벨이 있는 영상을 재마킹 대기로 되감는다**. 미발행 = 기존 2종만 도는 현행 동작 유지.
  - **resolve 는 `OPEN→RESOLVED` 조건부 UPDATE 로 원자 클레임**하고 **영향행수 1 을 받은 성공자만** 락 해제·`'Y'` 복원·재개 이벤트를 수행한다(CWE-362 — 2노드 Active-Active). 구 `findById`→상태 비교는 read-then-write 라 두 노드가 동시 통과해 무거운 프레임 재추출이 2회 기동됐다. 선례 `BatchTransitionService.tryClaimReprocessFromFailed`.
  - **★선결 결함 수정(같은 라운드)** — 재비식별 프레임 재추출이 `FRM_NO`(**추출 순번** 0,1,2…)를 프레임 번호로 넘겨 **비식별 영상 맨 앞**을 뽑아 붙이고 있었다. 실제 영상 내 위치는 **`VDO_FRM_NO`** 다(초기 추출이 `seekMillis = frameIndex × 1000 / fps` 로 실제 위치를 뽑고 두 값을 각각 적재한다). 이제 `VDO_FRM_NO` 로 추출하며, **NULL(레거시 행)은 순번 폴백 없이 그 프레임만 skip + WARN**(폴백하면 결함을 그대로 유지). **출력 파일명·디렉터리는 불변**(`{deidBase}/frames/deid/{rawSn}/frame-{FRM_NO}.jpg` — 초기 추출과 동일해 제자리 교체되므로 고아 파일이 생기지 않는다)
- **★파생영상은 비식별 신고 체계 바깥이다 — 양방향 무관 (2026-07-29 사용자 확정, 구속)**: `LS_DATA_RAW.ORGNL_RAW_SN IS NOT NULL`(증강·해상도 파생본)인 영상은 신고를 **접수하지 않고**(`POST /v1/labels/{srcSn}/deident-report` → **412 PRECONDITION_FAILED**), **원본의 신고도 파생에 아무 영향을 주지 않는다**(게이트가 자기 rawSn 행만 판정). 근거: 재비식별은 외부 솔루션이 **원본**을 다시 처리하는 방식뿐이라 파생본 자체를 다시 비식별할 수단이 없다 — 접수해도 해소 불가능한 신고(작업락 + `'F'` 고착)만 남고, 전파해도 풀 방법이 없다.
  - **함의(사용자가 인지·감수한 사항 — 결함으로 되돌리지 말 것)**: 원본에 마스킹 실패가 있으면 그 시점에 복사된 **파생본에도 남아 있는데 파생본은 계속 서빙·산출된다.**
  - **거부 안내는 사실만 말한다**: "이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다." **원본으로 유도하지 않으며 부모 rawSn 도 내려주지 않는다** — 원본을 신고해도 이 파생본은 달라지지 않고, 파생에 배정된 WORKER 는 원본 접근 권한이 없어(`LabelAccessGuard` 403) 따라갈 수도 없다(구 "원본 영상(영상번호 N)에서 신고해 주세요" 안내 폐기).
  - **FE 동선**: 라벨링 화면이 영상 상세(`VideoDetailResponse.derivative`)로 파생 여부를 미리 알아 **신고 버튼을 비활성화 + 사유를 툴팁으로 표시**한다(사유를 다 적고 제출한 뒤에야 거부되는 동선 제거). 412 안내 노출은 화면이 파생 여부를 모르는 경우의 안전망으로 유지.
  - **REVIEWER 알림 없음**: 거부 경로는 신고 행(`LS_DEIDENT_REPORT`)을 만들지 않으므로 알림을 보내면 REVIEWER 가 존재하지 않는 신고를 찾게 된다(처리할 워크플로도 없다). 대신 발견 사실이 유실되지 않도록 사용자가 적은 사유를 `LogSanitizer` 로 정제해 **WARN 감사 로그**로 남긴다.
  - **★귀결 — '파생 경유 열람'은 결함이 아니다 (2026-07-29, 적대검증 후 명시)**: 부모가 신고 구간이어도 파생 생성이 허용되므로, **부모 라벨이 파생으로 복사되고 파생 rawSn 은 `'Y'` 라 게이트를 통과한다.** 결과적으로 원본에서 412 로 막히는 **라벨 조회·프레임 이미지를 파생 경유로 열람**할 수 있다(파생에 배정된 WORKER·REVIEWER 기준). 이는 "파생은 원본 신고와 무관"(2026-07-29 확정)의 **필연적 귀결**이며 **결함으로 재분류해 다시 막으려 하지 말 것** — 막으려면 "파생본 재비식별 수단"부터 만들어야 한다(조상/자손 전파는 이미 4라운드 시도 후 철회됨. 아래 '신고 게이트 판정 범위' 참조).
- **★신고 게이트 판정 범위 = 자기 rawSn 행 하나 (2026-07-29 확정)**: 판정 단일 원천은 `video/service/DeidentReportGate` 이며 `DE_IDENT_YN='F'` 단일 컬럼 조회다. **폐기된 안 — 조상/자손 전파(2026-07-28 PR #52 및 후속 DEV_FIX 4차까지)**: 부모 신고를 파생까지 닫으려고 ①조상 체인 순회 판정 ②자손 캐시 evict·복구 팬아웃 ③조상 행 부재 fail-closed ④체인 잠금 정준 순서를 넣었으나 차단↔복구 비대칭, 팬아웃 상한 초과 시 **신고 0건인 정상 트리 영구 fail-closed(DoS)**, 막다른 안내가 연쇄로 나와 전부 철회했다. **다시 시도하지 말 것** — 되살리려면 "파생본 재비식별 수단"부터 만들어야 한다.
- **차단 범위(구현 실측)와 응답 코드**: ①라벨 조회 `GET /v1/frames/{srcSn}/labels`·라벨 이력 → **412** ②버전 diff·롤백(`VersionService`) → **412** ③프레임 이미지 `GET /v1/frames/{srcSn}/image`·`GET /v1/frames/{srcSn}/deid-image`·`GET /v1/videos/{rawSn}/frames/{frameNo}/image` → **412** ④포털 프레임 라벨·포털 프레임 이미지 → **412** ⑤관제 조회 API(라벨 본문 `TaskQueryController`) → **412** ⑥데이터셋 export(`DatasetExportService`/`DatasetExportTxService`/`DatasetExportFailureRecoverer`) → 산출 자체를 보류(skip, 통지도 보류) ⑦**영상 스트리밍 `GET /v1/videos/{rawSn}/stream`·`GET /v1/videos/{rawSn}/stream-url` → 404** ⑧**개인정보 메타 저장(PUT)** — 영상 축 `PUT /v1/videos/{rawSn}/privacy-meta` · 프레임 축 `PUT /v1/frames/{srcSn}/privacy-meta`·`PUT /v1/frames/privacy-meta`(벌크) → **412** (2026-08-03 — 이 게이트의 유일한 **쓰기** 호출자다. 근거는 "신고 구간에 그 잘못된 비식별본 위의 개인정보 판정을 새로 쓰면 resolve 후 관제로 나간다"이며 **영상 축·프레임 축 양쪽에 건다** — 한 축만 막으면 우회가 남는다. 촬영환경 PUT 은 PII 축이 아니라 제외). ⚠ **구 근거 "신고가 두 축을 함께 리셋하므로" → 폐기(2026-08-04, 리셋 폐기). 게이트 자체는 리셋 여부와 무관하게 성립하므로 ⑧은 그대로 유지한다.** ⑨**라벨 저장 `PUT /v1/frames/{srcSn}/labels`(`LabelService.bulkUpsert`) → 412** (2026-08-04, C-ISSUE-22 — 구 서술 *"저장·수정은 기존 작업락으로 409 차단"* 은 **폐기**: 신고 락은 6h 만료 후 `WorkLockSweepJob` 이 회수하는데 `'F'` 는 resolve 까지 남아, 그 창에서 **조회 412 ↔ 저장 200** 비대칭이 열렸고 full-replace 계약상 `items:[]` 저장이 기존 라벨을 전량 삭제했다. 게이트는 **락 검사보다 먼저** 평가해 락 유무와 무관하게 412 로 통일한다 — 409/412 로 갈리면 응답이 잠금 상태 오라클이 된다. 409 는 **신고와 무관한 락**(트랙 병합 등 일시적 충돌)에만 남는다). 스트리밍만 404 인 것은 "비식별 유효하지 않으면 원본 노출 금지 → 404" 라는 그 엔드포인트의 **기존 규약**에 맞춘 것으로, 신고 여부에 따라 404/412 로 갈리면 **응답 코드가 영상 상태를 알려주는 오라클**이 되기 때문이다(CWE-209). 모든 게이트는 **인가 검사 이후** 평가되는 프리컨디션이며 역할 무관(REVIEWER 포함)이다. 거부 메시지는 **행위 중립**("비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.")이다 — 구 문구는 "…라벨을 조회할 수 없습니다"라 쓰기 호출자에게 맞지 않았다. **★위 목록은 전부 '원본 보호 축'이며 그대로 유지된다. 반면 '파생 *생성* 축'(증강 콜백 인계·해상도 파생 예약/확정)은 차단 범위가 아니다 (2026-07-29)** — 파생 생성은 원본 신고와 무관하고(위 '파생영상은 비식별 신고 체계 바깥' 대칭), 신고가 막는 것은 **외부 위탁(요청·전송)** 뿐이다. 증강 고아 PENDING 만료 스윕 (`AugmentJobExpiryTxService.expireOrphanPending` / `LsDataAugRepository.findOrphanPendingAugSns`)의 신고 구간 제외 술어도 **제거**했다 — 보류 재개 리스너가 폐기돼 깨울 주체가 없으므로 제외하면 PENDING 영구 고착만 남는다.
- **게이트가 걸린 미디어 응답은 `Cache-Control: no-store`** — 적용 경로 **전체 목록**(코드 실측): ①`GET /v1/videos/{rawSn}/stream`(200·206, `VideoStreamService`) ②`GET /v1/frames/{srcSn}/image`(`FrameImageController`) ③`GET /v1/frames/{srcSn}/deid-image`(`FrameImageService.serveDeidentified`) ④`GET /v1/videos/{rawSn}/frames/{frameNo}/image`(`FrameImageService.serve`) ⑤`GET /v1/portal/frames/{srcSn}/image`(`PortalLabelService.serveFrameImage` — 포털로 내보내는 **내부 파이프라인 비식별 프레임**. 2026-07-28 누락 보정: 유일하게 `private, max-age=300` 이 남아 신고 후에도 최대 5분 재노출됐다). 반면 **포털 업로드 자산**(`GET /v1/portal/uploads/frames/{uldFrmeSn}/image`, `PortalUploadService`)은 포털 사용자 **본인 업로드분**이라 신고 게이트 대상이 아니며 이 통일 대상이 아니다. 이들은 매 요청 게이트를 통과해야 하는데, 클라이언트가 `max-age` 동안 응답을 재사용하면 **서버에 오지 않아** 신고 직후에도 마스킹 실패 영상/프레임이 계속 재생·표시된다(CWE-359/525 — 재생 중 신고 접수 시나리오). 응답에 검증자(ETag/Last-Modified)가 없어 `no-cache`(재검증)로는 대역폭 이득 없이 디스크 캐시 잔존 위험만 남으므로 `no-store` 로 통일한다. 서버측 `stream-meta` 캐시는 유지하되 **게이트를 캐시 앞(매 요청)에서 평가**하고, 신고/해소 시 그 영상의 캐시를 커밋 후 무효화한다.
- **★게이트가 닫히는 변화는 FE 캐시를 `removeQueries` 로 **제거**한다 — `invalidateQueries` 는 부족하다 (2026-08-05 사용자 확정, 구속)**: 위 `no-store` 가 **서버 응답의 브라우저 캐시**를 막는다면, 이 규칙은 **React Query 클라이언트 캐시**를 막는다. 둘은 서로를 대체하지 않는다 — `no-store` 를 걸어도 이미 받아둔 응답이 쿼리 캐시에 남아 있으면 화면은 서버에 오지 않고 그것을 그린다.
  - **판정 기준**: 그 변화로 **서버가 이후 요청을 거부하게 되면(게이트가 닫히면) `removeQueries`**, 단순히 **서버 상태가 바뀌어 최신값을 다시 받아야 하는 것뿐이면 `invalidateQueries`**.
  - **현재 적용 대상은 비식별 신고 접수 1건**이다(`LabelingPage.handleDeidentReportSuccess` — `LABEL_KEYS.byVideo` · `LABEL_KEYS.all` 양 분기). 트랙 편집·병합(`LabelingPage:886/908/933`)·저장 이력(`useUpdateLabels`)·버전 롤백(`useRollback`)·포털 저장(`useSavePortalLabels`)은 **정합성 갱신**이라 `invalidate` 가 맞다. 게이트가 하나 더 생기면 이 목록에 추가한다.
  - **왜 `invalidate` 로는 새는가**: `invalidateQueries` 는 항목을 stale 로 표시할 뿐 **버리지 않는다.** `useLabels` 는 `staleTime: 30_000` + `refetchOnWindowFocus: false` + `gcTime` 기본 5분이라 ①신고 후 **30초 이내 재진입 시 재조회가 아예 일어나지 않아** 412 도 잠금 배너도 없이 캐시된 라벨 좌표가 그려지고(`reportedLock` 은 컴포넌트 상태라 재마운트 시 초기화) ②30초~5분에는 캐시를 먼저 그린 뒤 백그라운드 412 라 **한순간 노출**된다. 라벨 좌표는 개인정보의 위치를 특정하는 정보다(CWE-359).
  - ⚠ **회귀 가드에 함정이 둘 있다**: ①**활성 화면 테스트로는 차이가 드러나지 않는다** — `removeQueries` 후에도 마운트된 observer 가 쿼리를 재생성해 재조회가 돌아 결과가 `invalidate` 와 같다. **반드시 "이탈 → staleTime 내 재진입" 케이스**로 가드하고 캐시 잔존을 `getQueryData() === undefined` 로 **직접** 단언한다(DOM 관측만 두면 실패 원인이 우발적 에러에 가려진다). ②공용 `createTestQueryClient` 는 `gcTime: 0` 이라 unmount 즉시 GC 되어 remove/invalidate 차이가 소멸한다 — 이 가드만 **`gcTime` 을 프로덕션 기본값으로 둔 전용 클라이언트**를 쓴다. 회귀 가드: `TC-FE-315`(`LabelingPageDeidentReport.test.tsx`), mutation 실증 완료.
- **★라벨링 캔버스는 비식별 프레임을 서빙한다 (2026-07-30 확정, 구속)**: `GET /v1/frames/{srcSn}/image` 는 **기본이 비식별본**(`DE_IDNTF_SRC_FILE_PATH_NM`)이며, 원본은 **REVIEWER 가 `?raw=true` 를 명시할 때만** 나간다(WORKER 의 `raw=true` 는 무시하고 DEID 강제). 비식별 경로가 없으면 PRVC/PSDO 는 404, **ANONY 레거시만 원본 폴백**(하위호환, 백필은 별건). 이 규칙은 `(rawSn, frameNo)` 경로(`FrameImageService.serve`)와 **같은 판정기(`serveFrame`)를 공유**한다.
  - 경위: 원래 이 정책은 `/v1/videos/{rawSn}/frames/{frameNo}/image` 에만 배선돼 있었고, 라벨링 캔버스가 쓰는 `/v1/frames/{srcSn}/image` 는 **컨트롤러가 경로 해석을 자체 구현**해 정책이 영영 미배선이었다. 그 결과 ①WORKER 가 라벨링 내내 **원본(마스킹 전) 프레임**을 봤고(화면 뱃지는 "DEID") ②`SRC_FILE_PATH_NM` 이 null 인 **해상도·증강 파생 프레임은 전부 404 → 캔버스 백지**였다. **컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다** — 판정은 서비스 한 곳에만 둔다.
  - ⚠ **REVIEWER 원본 열람 동선은 FE 에 두지 않는다**(2026-07-30 사용자 확정). `raw=true` 는 API 로만 존재하며 화면에는 호출부가 없다. 필요해지면 감사 로그·권한 정책과 함께 별도 설계.
- **★비식별 프레임을 파일로 여는 4경로는 단일 규약이다 (2026-07-30 구속)**: `/v1/frames/{srcSn}/image` · `/v1/frames/{srcSn}/deid-image` · `/v1/videos/{rawSn}/frames/{frameNo}/image` · `/v1/portal/frames/{srcSn}/image` 는 모두 **①`StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기로 검증하고 ②판정이 돌려준 실경로(`toRealPath()`)를 그대로 사용하며 ③`FrameImageService.openNoFollow`(`NOFOLLOW_LINKS`)로 연다.** lexical 경로로 검증하고 lexical 경로로 여는 구현은 금지 — 검증~open 사이에 최종 컴포넌트를 원본 프레임 심링크로 교체하면 마스킹 전 픽셀이 "비식별본"으로 200 서빙된다(CWE-59/367/359). 원본 분기도 realpath 로 재검증한다. `Files.newInputStream`·`FileSystemResource` 직접 사용 금지. **예외**: 포털 사용자 **본인 업로드 자산**(`PortalUploadService`)은 비식별 계약 대상이 아니다.
- **★프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다 (2026-07-30 구속)**: 조회·인가·신고 게이트는 `FrameImageLookupService`(`@Transactional(readOnly)`)가 수행해 값 레코드만 돌려주고, 경로 검증·파일 open·스트림 응답은 **트랜잭션 밖**에서 한다. 라벨링 캔버스는 프레임마다·썸네일마다 이 경로를 호출하므로 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다(전례 있음). 서빙 빈에 `@Transactional` 을 되돌리거나 조회를 같은 빈으로 합치면 `FrameImageServingHardeningTest` 가 실패한다 — **자기호출로 프록시를 우회하지 말 것**.
- **신규 엔드포인트 `GET /v1/frames/{srcSn}/deid-image`**: 프레임의 **비식별 이미지 전용** 서빙(`DE_IDNTF_SRC_FILE_PATH_NM`). 해상도 파생 프레임은 원본 픽셀이 실재하지 않아 `SRC_FILE_PATH_NM` 이 null 이라 기존 `/image` 로는 조회되지 않는다. **원본 폴백 없음**(비식별 경로가 없으면 404). 쿼리 파라미터 행위 분기 금지 원칙에 따라 별도 sub-resource 로 둔다.
- **해소(resolve) 시 자동 복구 — ★복구 범위 = 차단 범위와 동일하게 "그 영상 하나" (2026-07-29)**: `'F'→'Y'` 복원으로 위 게이트가 전부 자동 해제되고, 신고 구간에 보류됐던 산출·위탁이 재트리거된다. 게이트가 자기 행만 보므로 신고가 막던 노드도 그 영상 하나뿐이고, 복구 발행·스트림 메타 캐시 무효화 범위도 동일하게 그 영상 하나다(자손 팬아웃 없음). 발행 이벤트는 둘: ①`DeidentGateReopenedEvent` = **항상**(보류된 VLM 위탁 재개용 — VLM 보류는 대개 미승인 영상에서 일어나므로 승인 전용 이벤트로는 신호가 도달하지 않는다) ②`DeidentReportResolvedEvent` = **APPROVED 일 때만**(export 재산출·관제 재통지 — 미승인에 발행하면 불필요한 v1 생성). 차단된 export 는 `LS_DATASET_EXPORT` 행을 남기지 않아 실패 회수기가 집지 못하므로 **이 재트리거가 유일한 복구 경로**다.
- **★VLM 시계열 위탁은 신고 구간에 보류한다 (2026-07-29)**: `VlmTimeseriesStep` 이 외부 VLM 벤더로 보내는 `media.path` 는 **비식별본**인데, 신고는 바로 그 비식별본의 마스킹 실패를 뜻한다. 신고 구간 영상이 배치 재처리(`BatchReprocessService.retry`·Quartz 재큐)로 돌면 회수 불가능한 외부 유출이 된다. 게이트는 **스텝 안(전송 직전)** 에 둔다 — 차단 대상이 파이프라인 전체가 아니라 외부 전송 하나이고(YOLO/SAM2 는 원본만 쓰는 **내부** 추론이라 대상 아님), `run`/`runWithMarking` 이 dev 트리거 등에서 직접 호출되는 public 진입점이라 오케스트레이터에 두면 샌다. 차단은 **실패가 아니라 보류** — 기존 NO-OP 규약과 동일하게 SKIPPED 응답 + 사유를 `LS_BATCH_PROC_LOG` 에 적재(B-ISSUE-24)해 해소 후 재처리 대상으로 식별되게 한다(예외로 실패시키면 재시도 상한 소진 + 작업 상태 FAILED 역행). **보류는 스스로 재개되지 않으므로**(실패 행이 없어 재시도 큐·회수기가 집지 않는다) 해소 시 `DeidentGateReopenedEvent` → `VlmResumeBridge` → `VlmWithheldResumeRunner` 가 이 SKIPPED 기록을 근거로 **재위탁**한다(멱등 조건: 시계열 메타 0건). 이 배선이 없으면 시계열 메타가 영구 결손된다.

### 배치 성능
- Spring Boot + Quartz는 **주 서버 2노드 Active-Active 이중화** 배포 — 관제서버와 동일 서버 공동 배치, 앱 2노드 동시 기동, **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지). DB는 별도 DB 서버(이중화), 파일 스토리지는 별도 NAS 서버(공유 마운트) *(구 '단일 인스턴스·클러스터 미적용' 서술 대체 — 2026-07-14 배포 토폴로지 확정, D5 v1.10 정합)*
- **클러스터링은 stg/prd 에서 강제(fail-closed) — 꺼져 있으면 기동 거부** *(2026-07-29 확정, B-ISSUE-81/02)*: `QUARTZ_CLUSTERED` 는 stg/prd 프로파일 기본값이 `true` 이고, `false`(또는 미설정으로 공통 기본값 상속) 면 `QuartzClusteringGuard` 가 `@PostConstruct` 에서 기동을 실패시킨다. 판정은 **allowlist**(단일 노드 허용 = local/dev 프로파일 전부일 때만, `containsAll`) + **`ENV` 배포 표식 독립 축**(`DevProfileGuard.DEPLOYED_ENVS` 동일 기준 — `SPRING_PROFILES_ACTIVE=dev` 로 낮춰도 우회 불가) 두 축이며, 오타·미지정·혼합 프로파일은 자동으로 엄격하다. WARN 은 배포 로그에 묻히므로 경고가 아니라 기동 차단이다(`VlmUrlPolicy`·`GenAiIntegrationWiringGuard` 와 동일 골격). ⚠ 클러스터링은 **트리거 중복 발화**만 막는다 — 잡 내부 레이스는 각 잡의 원자 클레임(조건부 UPDATE)이 별도로 막으며 서로 대체하지 않는다. ⚠ 노드 간 시계 동기(NTP) 전제 — 운영 체크리스트는 `deploy/onprem/docs/04-configuration.md`·`09-operations-runbook.md`
- Quartz 기반 1건/분 처리. ai-server 추론 자원 모니터링 포인트 확보
- **ai-server(YOLO/SAM2)는 주 서버 내 별도 프로세스(무상태)** — 필요 시 다중 프로세스 확장 가능
- 배치 실패 시 재처리 정책 (최대 재시도 횟수, 실패 알림)

### DB 공유 주의
- MNG_* 공유 테이블 변경 시 **Flyway 마이그레이션 전 관제서버팀 협의 필수**
- 관제서버 소유 MNG_* 스키마 변경 시 Hibernate validate 모드에서 기동 불가 — 변경 알림 프로세스 필요
- JPA `ddl-auto=validate` 고정. 엔티티 수정 시 Flyway migration 동반 작성

### Self-evolving rules (Claude 특이)
- `rule-injector.sh`가 `.claude/rules/learned-*.md`의 LEARNED 블록을 키워드로 주입
- `mistake-recorder.sh`가 빌드/테스트 실패 시 feedback memory 자동 기록

## 문서 동기화 규칙 (Critical)
- **기능 추가/변경 시 `docs/v2-wiki/`의 해당 페이지를 같은 커밋(또는 같은 PR)에서 갱신한다**
- 신규 도메인이면 v2-wiki에 새 페이지 추가 + `docs/v2-wiki/README.md` 목차 갱신
- 화면 추가 시 `docs/v2-wiki/04-screens-ia.md`, 테이블 추가 시 `docs/v2-wiki/18-database.md` 갱신
- v1 대비 기능 추가 시 `docs/v1-wiki/19-v2-gap-checklist.md` 체크 갱신 (R1 요구사항 외 기능이면 "요구사항 외 추가 결정" 명시)
- **★ 동작·정책이 바뀌면 `docs/test-cases/` 카탈로그도 같은 커밋에서 갱신한다 (2026-08-03 사용자 확정, 구속)**: wiki 만 고치고 테스트케이스를 두면 카탈로그가 **폐기된 동작을 계속 검증 대상으로 들고 있어** 다음 전수 검증에서 "결함"으로 재발견된다(이 저장소의 "철회된 정책 재시도" 사고 패턴과 같은 뿌리).
  - **정정**: 기대결과·전제·근거를 실제 코드로 재확인해 고친다. **★근거는 `파일명(심볼명)` 으로 적는다 — 라인번호 금지, 심볼은 가능하면 반드시 (2026-08-05 사용자 확정, 구속)**: `PresetService.java(resolveLabels)` 처럼 적고 `:105-143` 같은 라인 참조는 적지 않는다. **심볼 생략도 안 된다** — 심볼은 그 판정을 실제로 수행하는 메서드·클래스·테스트명을 가리키며 라인과 달리 리네임 전까지 안정적이면서 검색으로 즉시 도달한다. 판정 지점이 여럿이면 ` · ` 로 나열하고, **심볼 특정이 불가능한 경우**(설정 yml·마이그레이션 SQL 등)에만 파일명 단독을 허용한다. 근거 정정 대상은 **파일 리네임·삭제·이동, 판정 주체의 클래스 이동, 심볼 리네임**이다(라인 이동은 더 이상 사유가 아니다). ⚠ **기존 행은 일괄 백필하지 않는다** — 회차 12 시점 심볼 보유율 13%(287/2,134)이고 전수 보강은 코드를 열어 판정 메서드를 찾아야 해 회차 4 급 비용이다. **앞으로 손대는 행부터 채워** 점진 상승시키며, 파일명 단독 행은 결함이 아니라 미보강 상태다(드리프트로 집계하지 않는다). 구 정책(*"라인 드리프트만 바뀐 것도 고친다 — 근거 정확도가 이 카탈로그의 존재 이유"*)은 **폐기** — 근거: 회차 4 는 정정 ≈699건 중 기대결과·전제가 실제로 바뀐 건이 **24건뿐**이었고 회차 11 은 **76건 전량이 순수 리팩토링 드리프트**였다(노동의 대부분이 "코드를 여는 시간 몇 초"에 소모). 게다가 **stale 라인은 없느니만 못하다** — 회차 4 에서 H-16 의 17건이 **엉뚱한 함수**를 가리키고 있었고, 틀린 좌표는 검증자를 잘못된 판정으로 이끈다. ⚠ 다만 **"소스 무변경이니 근거도 맞다"는 추론 금지 규칙은 그대로 유효**하다(파일 소멸·판정 이동은 라인 폐지와 무관하게 놓친다).
  - **폐기**: 행을 **지우지 말고 폐기 표기 + 사유·근거 커밋**을 남긴다. 지우면 "왜 이 케이스가 없어졌지"로 되돌아와 같은 논의를 반복한다.
  - **신설**: 새 동작은 경계·오류·하위호환 케이스까지 함께 넣는다.
  - 각 파일 상단 `## 변경 이력` 표에 회차 행을 추가하고, **정책이 뒤집힌 항목은 "구 정책 → 폐기"를 명시**한다.
