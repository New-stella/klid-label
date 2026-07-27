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
- `klid_at` 스키마 운영 — **저작도구 전용(LS_*) 테이블은 자체 소유·구성**, 관제서버 재사용 9개(MNG_*)는 `ddl-auto=validate`로 읽기 위주 참조
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
- **마킹 화면**: **비식별 영상** 스트리밍(`GET /v1/videos/{rawSn}/stream`, HTTP Range 지원 — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) + 배속 설정(0.25x~4x) + 키보드 단축키(Space: 마킹, Del: 삭제, Enter: 완료). 마킹 중 비식별 누락 발견 시 **비식별 신고** 가능(rawSn 기준 — planned)
- **VLM 연동**: BatchOrchestrator가 VlmTimeseriesStep을 동기 호출(45s 타임아웃, Resilience4j 재시도). VLM 서버가 즉시 응답 시 파이프라인 다음 단계 진행. 결과 상세는 VLM 서버가 콜백(`POST /v1/vlm/callback`)으로 별도 전송 → VlmResultService가 LS_DATA_META 적재 + 검수큐(LS_DATA_META_REVIEW) 진입
- **배치 상태 전이**: 적재 시 `LsDataRaw.dataSttsCd=PENDING` → 선두 비식별 성공 시 `MARKING_READY`(마킹 진입 허용) → 배치 완료 시 `COMPLETED`. BatchOrchestrator.process() 시작 시 `LsRawDataStatus → PROCESSING`, 완료 시 작업 상태 `→ ASSIGNED 복귀`(COMPLETED 는 검수 승인 시점의 작업 종결 상태이므로 배치 완료가 점프시키지 않음 — 점프 시 검수 제출 ASSIGNED→PENDING 이 상태 머신에서 차단됨), 실패 시 `→ FAILED`. **두 테이블 책임 분리**: `LsDataRaw.dataSttsCd`(배치 단계: PENDING→MARKING_READY→COMPLETED)는 완료 시 `COMPLETED` 로 마감하고, `LsRawDataStatus.dataSttsCd`(작업/검수 워크플로우 상태)는 배정 시점 생성·ASSIGNED 로 복귀시켜 라벨링/검수 플로우가 이어지게 한다. 작업 상태의 `COMPLETED` 는 `ReviewService.approve`(검수 승인) 에서만 전이한다
- **비식별 호출 조건**: **전체 영상 비식별 후 마킹**(ANONY 포함, 게이팅 폐지 — 무조건 자동 실행) — 비식별이 파이프라인 선두 단계로, 적재 직후 자동 트리거(`VideoIngestedEvent`→`IngestDeidentifyBridge`→`AsyncDeidentifyRunner`→`DeidentifyStep`)된다. 비식별 영상이 마킹 대상이 되며 원본은 별도 보존 (구 규칙 'PRVC/PSDO만' 폐지). **증강(augment) 적재 경로는 아직 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned/후속)**. 비식별 실패/신고 영상은 자동 재비식별 큐 없이 외부 비식별 프로그램에서 수동 재비식별(`deIdntfYn='F'` + 기존 resolve 경로)
- 원본 영상과 비식별 영상은 **별도 경로로 동시 저장**
- **오토라벨링**: YOLO/SAM2는 **원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유 (별도 실행 없음)
- YOLO/SAM2/VLM은 `AiServerClient`로 호출 (타임아웃 60s + Resilience4j CircuitBreaker)

### 작업 배정
- **REVIEWER가 WORKER에게 배정** (역할 단일화 — ADMIN 권한은 REVIEWER에 통합)
- `LS_TASK_ASSIGNMENT`에 `TASK_TYPE_CD='LABELER'` INSERT, 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER가 보유

### 작업 단위 + 완료/수정 통지
- **작업 단위 = 영상 1건** — 프로젝트 단위 개념 사용 안 함. 작업 식별자는 영상 단위 ID(`LS_DATA_RAW.RAW_SN`)
- **검수 완료 = 작업 완료** — REVIEWER 가 검수를 `APPROVED` 처리하면 작업이 완료됨. `LsRawDataStatus.dataSttsCd` 가 `COMPLETED` 전이된 시점에 outbound `TASK_COMPLETED` 통지 발행
- **검수 완료 후 수정 시** — 동일 작업 ID 유지, 새 작업 ID 발급/버전 업 모두 안 함. 라벨/메타가 수정될 때마다 outbound `TASK_MODIFIED` 통지 발행. 수신측(관제서버)은 마지막 상태로 갱신
- **통지 단위는 영상 1건** — 라벨/이미지 1장 단위로 통지하지 않음. 영상 내 다수 변경이 같은 트랜잭션·짧은 시간 내 발생하면 디바운스 후 1회 통지(운영 결정)
- **TASK_COMPLETED 페이로드 — 메타만**: 이벤트 타입 + 작업 ID(RAW_SN) + 영상 메타(파일명·길이·채널) + 검수 완료 일시 + 프레임 개수 + 결과 요약 카운트(라벨 N건·메타 M건) + 요청 ID. 라벨/메타 본문 자체는 포함하지 않으며, 관제가 필요 시 본 도구 API로 보강
- **TASK_MODIFIED 페이로드 — 수정 요약만 전달**: 이벤트 타입 + 작업 ID(RAW_SN) + 마지막 수정 일시 + **변경 프레임 목록**(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED`) + 변경 요약 카운트 + 요청 ID. 라벨/메타 본문 데이터는 포함하지 않음
- **관제서버 조회 패턴**: 관제서버가 통지를 수신하면 저작도구 API를 호출하여 필요한 상세 데이터를 직접 조회. 저작도구는 관제서버가 조회할 수 있는 API를 제공해야 함
- 페이로드에 PII·토큰·원본 비-비식별 이미지 포함 금지
- 이력 보존은 `LS_LABEL_VERSION`(라벨 스냅샷) + `LS_DATA_LBL_HSTRY`로 충분

### 라벨링·버전관리
- 바운딩박스 / 폴리곤 / 세그멘테이션 / SAM2 분할(클릭·박스 프롬프트) / SAM2 Track — 캔버스는 konva.js
- **SFR-08-01(라벨링 정확도 향상) = VOS(추적+분할)로 해석** (2026-06-04 재정정 — 구 '자석 올가미' 해석 폐기·구현물 제거): SAM2 Track(박스→N프레임 추적 전파, `POST /v1/frames/{srcSn}/sam2-track`) + SAM2 분할(클릭/박스→폴리곤+신뢰도, `POST /v1/frames/{srcSn}/sam2-segment`, mock 응답은 FE 자동적용 차단)이 담당
- **라벨 프리셋 = 라벨 마스터(LS_LABEL) 단일 진실원 참조 (V117~V119)**: 프리셋 코드(`LS_LABEL_PRESET_CODE`)는 라벨명·형태를 스냅샷 저장하지 않고 `LBL_ID`(BIGINT FK→`LS_LABEL.LBL_ID`, nullable=미연결 허용)로 마스터를 실시간 join 한다 — 조회·표시·오토라벨 사용 시점에 마스터에서 라벨명/형태를 가져오므로 마스터에서 라벨명·형태를 바꾸면 신규·기존 프리셋 모두에 즉시 반영된다. **형태는 마스터 `LBL_TYPE_CD`가 소유**(BBOX→bbox, POLYGON→polygon, POINT/SKELETON→도형 오토라벨 미적용) — 프리셋에서 형태 개별 토글 불가(구 `BBOX_ENABLED`/`POLYGON_ENABLED` 토글 컬럼 제거). labelId 기반 신규 행은 코드 문자열을 저장하지 않아 `LBL_CD` NOT NULL 제거(미연결 레거시 행만 표시용 코드 보유), 부분 유니크 인덱스 `UK_LS_LABEL_PRESET_CODE_LBLID (PRESET_ID, LBL_ID) WHERE LBL_ID IS NOT NULL`로 프리셋당 같은 labelId 중복 저장 방지. 마스터에 매칭 안 되는 기존 코드(labelId null/비활성)는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음). 오토라벨(YOLO/SAM2) 경로의 프리셋↔검출 라벨 매칭은 **AI 검출 클래스 축(`LS_LABEL.DTCT_TYPE_CD`, COCO 80 클래스명)으로 일원화**하고 형태를 마스터에서 파생한다 *(2026-07-23 supersede — 구 '마스터 라벨명 축' 폐기. 근거: 라벨 관리에 COCO 검출 클래스 매핑을 등록하는 이슈3 반영. 한글 마스터 라벨명↔COCO 영문명이 1:1 대응하지 않아, 검출 라벨 귀속·프리셋 토글·온라인/배치 매칭을 모두 마스터의 COCO 매핑 컬럼 `DTCT_TYPE_CD`를 진실원으로 삼는다. `findLabelIdByDtctType`가 `findLabelIdByName`을 대체하고 배치(`YoloAutolabelStep`)·온라인(`AutolabelOnlineService`)·SAM2·프리셋 토글(`PresetLabelLookupService`) 4경로가 동일 축으로 통일됨)*. **AI 탐지 후보는 라벨 마스터(LS_LABEL) 기준으로 노출**하되 `DTCT_TYPE_CD`가 매핑된 라벨만 실제 검출 가능(미매핑은 표시하되 선택 불가). 매핑 강제는 BE(`AutolabelOnlineService.resolveDetectClasses`)가 담당 — FE 요청을 신뢰하지 않고 마스터 매핑 화이트리스트와 교집합만 ai-server 로 전달. `DTCT_TYPE_CD`는 표준용어(검출=DTCT·유형=TYPE·코드=CD) + 코드값 표준도메인 `VARCHAR(20)`, 활성 라벨 부분 유니크(1 COCO 클래스=1 활성 라벨). COCO allowlist(`CocoClasses` 80종)는 ai-server `COCO_ID2LABEL` 와 계약 정합(드리프트 테스트). 마이그레이션 후 기존 라벨은 매핑 NULL 로 시작 — 운영자가 COCO 매핑을 지정하기 전까지 AI 탐지는 게이팅(0건).
- **2계층 분리 (Critical)**: ①**작업 임시저장**(작업 중) — 라벨러 저장 시 현재 작업본을 `LS_DATA_LBL`에 영속(upsert)하고 되돌리기는 FE undo/redo(세션)로 처리한다. 학습데이터 버전이 아니며 `LS_LABEL_VERSION` 스냅샷을 만들지 않는다. ②**학습데이터 버전**(확정) — 아래 별개 개념.
- **버전관리는 DB 기반 (외부 VCS 미사용)**: **검수 승인(`APPROVED`) 시점**에 영상 단위로 라벨 **전체 스냅샷(JSON)** 을 `LS_LABEL_VERSION.LABEL_PAYLOAD`에 저장한다(`SAVE_REASON='APPROVED'`). 라벨 저장 시점에는 버전을 생성하지 않는다 — SFR-08의 '학습데이터셋의 버전 관리'는 **검수 완료되어 학습데이터로 확정된 단위**를 대상으로 하기 때문. 버전 식별자는 페이로드 해시(`VERSION_HASH`, SHA-256)이며 동일 페이로드 재스냅샷은 동일 해시로 중복 식별한다. 검수완료 후 수정→재검수→재승인 시 새 버전이 쌓인다.
- **diff / 롤백**: **검수완료(APPROVED) 버전 간** DB 스냅샷을 앱에서 비교해 diff를 계산하고, 롤백은 대상 검수완료 스냅샷을 새 active 버전으로 복원한다. 외부 Git/Gitea 등 VCS에 의존하지 않는다. 데이터마트 동기화(SFR-08 3번째 항목)는 검수완료 후 수정 시 `TASK_MODIFIED` 통지로 연계한다.
- **★ export 재생성·동기화 정책 (Critical — 2026-07-27 확정, 구 "승인 시점에만 재생성" 정책 폐기)**: 사업 요구사항 원문 *"동일 학습데이터의 버전 관리 및 비교 기능"*(버전별 변경 내용 **비교·복구**) + *"기 구축된 데이터 마트 연계 관리기능"*(**데이터마트로 구축이 완료된 학습데이터의 라벨링 수정 시 기존 데이터마트 학습데이터셋의 라벨링 정보 동기화**)을 충족하기 위한 구속 규칙이다.
  - **승인 후 라벨 수정·촬영환경(날씨/시간대/계절) 수정 → export 를 새 버전 폴더 `v{n+1}` 로 전량 재생성**한다(이미지 2벌 포함, 승인 경로와 동일 산출). *구 정책 "재생성은 승인 시점에만, 편집분은 다음 재승인에서 재산출"은 폐기* — 파일이 옛 내용이면 "라벨링 정보 동기화" 요구가 성립하지 않는다.
  - **통지는 export 완료 후 발송**한다(승인 `TASK_COMPLETED`·수정 `TASK_MODIFIED` 양쪽). export 가 `@Async` 라 통지가 앞서면 관제가 **구 버전 폴더**를 픽업한다.
  - **버전마다 전체 자기완결 + 전 버전 보존(삭제 안 함)**. 델타만 두면 요구의 *복구(rollback)* 가 성립하지 않으므로 **retention 정리 로직을 만들지 않는다**. 저장소 증폭은 감수한다(검수 완료 영상의 재검수 빈도가 낮다는 판단).
  - `LS_DATASET_EXPORT.EXPORT_PATH_NM` 은 **영상 루트**(`{rawSn}`, 버전 루트 아님)를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다.
  - ⚠ **비식별 영상 파일명은 고정이 아니다** — 우리가 지정하는 것은 **디렉터리(`export_path`)까지**이고 파일명은 외부 비식별 솔루션이 정한다. mock 은 `deidentified.mp4`(우리가 직접 씀), **KPST 실연동은 `{원본stem}-mask{ext}`**(예: `001.mp4` → `001-mask.mp4`)로 **영상마다 다르다**. 따라서 **파일명을 조합·추측하지 말고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽는다**(위 데이터마트 View 절의 "문자열 치환 도출 아님"과 동일 규칙). 관제도 파일명을 고정으로 기대할 수 없으므로 **뷰에 이 경로를 노출하는 것이 관제가 비식별 영상을 찾는 유일한 수단**이다.
  - ⚠ **`EvntAnnoService`(event_annotation 수정)는 예외** — 이 서비스는 재동결(`materialize`)을 하지 않아 재export 만 붙이면 승인 시점 동결본(`EVNT_ANNO_CN`)이 그대로 나간다. 적용하려면 재동결 배선이 선행돼야 하며 **미확정**이다.
- **CVAT 트랙 보간 알고리즘** 포팅 (docs/analysis/portable-modules/01-track-interpolation.md → Java)
- **MASK ↔ RLE ↔ Polygon 변환** 포팅 (portable-modules/02)

### 증강 = 새 영상
- 증강 AI는 **이미지-to-이미지** — 영상(비디오)을 재생성하지 않는다. 증강 결과 영상은 **원본(비식별) 영상 파일을 그대로 복사**하고 **프레임 이미지만 변환**한다.
- 증강 결과는 **새 영상(RAW_SN) 생성** — 원본과 다른 영상 ID. `LS_DATA_RAW.ORGNL_RAW_SN`(V82 rename, 구 `PARENT_RAW_SN` — 표준단어 ORGNL=원본)으로 원본(부모) 참조. **용어 표준**: `RAW`=원시(RAW_SN·LS_DATA_RAW 자체), `ORGNL`=원본(파생물의 부모/소스). 데이터마트 뷰 `V_COMPLETED_VIDEO` 출력 컬럼도 **`ORGNL_RAW_SN` 로 통일**(외부 소비자 부재로 구 `PARENT_RAW_SN` 외부 계약명 폐지 — V95. 내부·외부 모두 ORGNL_RAW_SN)
- 어노테이션(라벨)은 **원본(비식별) 정보 기준** — 원본 영상의 라벨/메타 JSON을 새 영상에 **복사**(외부 증강 3종 WINTER/NIGHT/RAIN은 해상도 동일 → 좌표 그대로 복사)
- **해상도 변경(SFR-06-03) = 증강형 파생영상 + 증강 저장모델 통합 (Critical — 2026-07-21 설계 반전 → 2026-07-22 저장모델 통합, feat/resolution-derivative-video)**: 표준 해상도 3종 고정 프리셋마다 **새 파생영상(RAW_SN) 생성**, `ORGNL_RAW_SN`으로 원본 참조 — 증강과 동일하게 **파생영상 취급**(구 '증강 아님·1행 추적' 정책 폐기). 비디오 파일은 **원본(비식별) 복사**(재인코딩 없음), **프레임 이미지셋만 목표 해상도로 리스케일**(축소/확대) — 이 두 원칙은 증강과 동일하며 유지. **라벨/이미지 좌표를 해상도 배율(scaleX=targetW/srcW, scaleY=targetH/srcH)로 재계산해 적재**(BBOX/POLYGON/세그멘테이션/키포인트 전 종류 — 구 '좌표 미제공' 폐기). **업스케일(확대)도 허용**(구 `targetH>=srcH` 400 거부 가드 제거) — 원본과 동일 해상도인 프리셋만 스킵하고 나머지는 3종 모두 생성. **저장모델은 증강과 완전 통합** — 파생 판별·라벨매핑을 위한 전용 테이블을 두지 않고 `LS_DATA_AUG` + `LS_DATA_AUG_LBL_MAP`에 적재한다: 판별자는 **`AUG_TYPE_CD` 값 `RESL_1080P`/`RESL_720P`/`RESL_480P`**(해상도 사업표준단어=RESL, 신규 컬럼 없음), 라벨 배율은 기존 `LS_DATA_AUG_LBL_MAP.COORD_RECALC_YN/SCALE_X/SCALE_Y` 재사용(신규 배율 컬럼 없음), 중복 방지는 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'`(V125). **구 전용 테이블 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`은 폐기**(V126 백필 후 fail-closed DROP). 해상도 파생은 **증강 이력(`GET /v1/augments`)에 노출**(응답 `resolutionTypes` 필드)되고 **집계/통계에 포함**되나, **accept/reject(검수 승인·반려)는 차단**(내부 생성물·라벨 검수 대상 아님). aug 상태 라이프사이클: **예약 시 PENDING(생성 중) → finalize 성공 시 ACCEPTED(생성 완료)**, 실패 시 예약행 삭제. API `POST /v1/videos/{rawSn}/resolution` 응답은 "생성된 파생영상 목록 `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}`", 요청 바디는 선택적 `presets[]`(미지정=3종 전체). 1건 이상 생성 성공=201 / 전부 실패=500 / 대상 프리셋 전부 스킵(모두 원본과 동일 해상도)=400.
- 파생영상은 **미검수(PENDING) 상태**로 시작 → 작업자 배정 → 수정 → 검수 (증강·해상도 변경 파생 공통, 기존 플로우 동일). 검수 승인 시 관제 **별도 완료 통지(TASK_COMPLETED)** 발송 — 파생영상은 기존 RAW_SN 파이프라인/데이터마트 뷰(`V_COMPLETED_*`)를 그대로 타므로 뷰 스키마 변경은 불필요
- 관제서버 통지 시 **새 영상 ID(RAW_SN)로 별도 완료 통지** 발송
- 증강 요청/수신 흐름: ExternalAugmentClient → 콜백

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
  - **물리명**: program/gov·산업 표준용어에 등록된 단어의 약어 조합만 사용(임의 약어 생성 금지). 예 — 해상도=RESL, 배치=BAT, 재시도=RTY, 변경=CHG, 수정=MDFCN
  - **타입·크기**: 표준용어에 연결된 **표준도메인**의 타입·길이를 그대로 채택(임의 크기 금지). 예 — 코드값 도메인은 `VARCHAR(20)`(`EVNT_TYPE_CD`=20이 표준, 32로 잡으면 드리프트 결함=감리 지적)
  - **확정 전 조회처**: 파일 정본 `../docs/표준용어/`(`사업표준단어.csv`·`사업표준도메인.csv` + `공통표준단어.csv`·`공통표준도메인.csv`) + LogiCraft MCP(`program_word_search`/`gov_word_search`/`gov_domain_get`/`gov_suggest_for_column`/`program_domain_get`). 표준에 없으면 소유권 규칙 따라 등록 후 사용
  - **3계층 강제 (재작업 반복 차단 — 수동 규칙·사후 QA 가 모두 놓쳤던 지점)**:
    1. **write 시점 훅(결정론적)**: `.claude/hooks/standard-term-guard.sh`(PostToolUse Edit|Write)가 마이그레이션 SQL(`db/migration/V*.sql`)·JPA 엔티티에 `CREATE TABLE`/`ADD COLUMN`/`@Column` 등 새 스키마 DDL 을 감지하면 표준용어·표준도메인 검증 리마인더를 자동 주입한다(비차단, 서브에이전트 컨텍스트에도 도달). 이 리마인더가 뜨면 커밋 전에 반드시 물리명+타입+크기를 검증·정정한다.
    2. **위임 프롬프트 규칙**: PM 이 DB 를 건드리는 Phase 를 `developer-*` 에이전트에 위임할 때, 위임 프롬프트에 **이 표준용어·표준도메인 제약 + 관련 확정 조회값(메모리)** 을 반드시 포함한다(서브에이전트는 메인 세션 메모리를 자동 상속하지 않으므로 명시 전달 필수).
    3. **QA 검사 명시**: DB 변경 Phase 의 `database-reviewer`/`design-verifier` 호출 프롬프트에 **"새 컬럼·테이블 물리명(표준단어 조합) + 타입·크기(표준도메인) 준수 여부"** 검사를 명시 항목으로 넣는다. 비표준 발견 = HIGH → DEV_FIX(표준어·표준크기로 정정).

### 데이터마트 적재용 View
- `klid_at` 스키마에 4종 View 제공 — 검수 완료(`LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`) 영상만 노출
  - `V_COMPLETED_VIDEO` : 영상 메타 + 원본 영상 경로 + 검수 완료 일시 + **export 폴더 경로(`EXPORT_PATH_NM`)·프레임수(`FRAME_CNT`)**(V114) — 최신 SUCCEEDED export(`LS_DATASET_EXPORT`) 조인, 미export 영상은 두 값 null(영상 1건=1row 유지)
  - `V_COMPLETED_FRAME` : 프레임 페어 (`ORIGINAL_PATH`=원본 `SRC_FILE_PATH_NM`, `DEIDENTIFIED_PATH`=비식별 `DE_IDNTF_SRC_FILE_PATH_NM`) — 신규 추출은 `{base}/frames/raw|deid/{rawSn}` 로 분기 저장돼 두 경로가 항상 상이(원본 덮어쓰기 0)
  - `V_COMPLETED_LABEL_CHANGE`(V114 신설) : 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, `CHG_KIND_CD`=ADDED/UPDATED/DELETED, APPROVED 게이트) — 라벨 좌표·속성 본문은 검수 승인 export 폴더 JSON에 존재하므로 뷰로 중복 노출하지 않음(구 `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 제거)
  - `V_COMPLETED_META` : 시계열 메타 (`RVW_STTS_CD='APPROVED'` 만)
- **라벨 내용 뷰 제거(V114) = 관제 연동 계약 변경(관제팀 협의 대상)**: 관제서버는 이제 ①export 폴더 경로(`V_COMPLETED_VIDEO.EXPORT_PATH_NM`) 픽업 + ②변경점(`V_COMPLETED_LABEL_CHANGE`)·메타(`V_COMPLETED_META`)만 DB 뷰로 쿼리한다. 라벨 본문 뷰를 SELECT 하던 관제 쿼리는 파손되므로 협의 필요(M2M deprecated·뷰 SELECT 방식)
- 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 통지 수신 후 RAW_SN 으로 4 View 단순 SELECT → 영상 1건=1 row UPSERT
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
- **비식별 누락 신고** (R1 v1.14 수동 흐름): 작업자가 개인정보 노출을 발견하면 신고 → 작업락 + **해당 영상(rawSn) 전체 라벨 삭제**(삭제 전 `LS_LABEL_VERSION`에 `SAVE_REASON='DEIDENT_REPORT'` 비활성 스냅샷 + `LS_DATA_LBL_HSTRY` 이력 보존) + `DE_IDENT_YN='F'`. 자동 재비식별 큐는 폐기 — 작업자/검수자가 **외부 솔루션으로 수동 비식별화** 후 `POST /v1/deident-reports/{rprtSn}/resolve`(WORKER 본인 배정/REVIEWER 전체)로 OPEN→RESOLVED 전이 + 작업락 해제. APPROVED 영상 신고 시 `TASK_MODIFIED` 통지 발행. **마킹 단계**(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report` — 설계 타깃/planned) + **라벨링 단계**(srcSn 기준, `POST /v1/labels/{srcSn}/deident-report` — 구현됨) 양쪽 가능. 적재 테이블 `LS_DEIDENT_REPORT`

### 배치 성능
- Spring Boot + Quartz는 **주 서버 2노드 Active-Active 이중화** 배포 — 관제서버와 동일 서버 공동 배치, 앱 2노드 동시 기동, **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지). DB는 별도 DB 서버(이중화), 파일 스토리지는 별도 NAS 서버(공유 마운트) *(구 '단일 인스턴스·클러스터 미적용' 서술 대체 — 2026-07-14 배포 토폴로지 확정, D5 v1.10 정합)*
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
