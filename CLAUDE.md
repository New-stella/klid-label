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
- **범위 외 — 데이터마트 구축·검색·다운로드**: 외부 제공 시스템 책임. ⚠ **구 서술 폐기(2026-08-15)** — *"학습데이터셋 내보내기(Export)는 범위 외, 저작도구는 라벨링·검수·버전관리까지만 담당"* 은 **사실과 다르다.** **export(NIA JSON) 산출은 저작도구 범위 안**이며 아래 「★ export 재생성·동기화 정책」 절이 그것을 상세히 규정한다(승인 시 `v{n+1}` 전량 재생성 → 통지). 근거 결정이 뒤집힌 것이다 — 구 서술의 근거 `ADR-005` 는 **`superseded`** 이고 `ADR-020`(*"검수 승인 학습데이터 export 산출을 저작도구 범위로 포함"*)이 대체했다. 담당 축은 **검수 승인 경로**이며 데이터 증강 도메인이 아니다
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

- 전체 회귀는 **backend 6,742 테스트(Testcontainers 포함, 결과 XML 745) 약 5분 25초 + frontend 3,213 테스트(409 파일) 약 45초 = 합계 6~7분급**이다(2026-08-16 실측). 기본값 300초로는 완주하지 못하므로 위 `buildTimeout` 선언이 필요하다.
  - ⚠ **구 서술 폐기(2026-08-16 실측)** — *"backend 4,000+ / frontend 1,600+ · 15~20분급"* 은 **테스트 수를 과소, 시간을 과대**로 적고 있었다. 이 오차는 문구 문제가 아니라 **라우팅을 바꾼다**: 하네스의 「장시간 FULL 병행 규칙」은 **600초 초과**일 때만 이득인데 실측이 그 아래라, 그 서술을 믿고 폴링·세그먼트 경로로 보내면 왕복 비용만 늘어난다. **한 호출에서 완주시키는 것이 맞다.**
  - `buildTimeout: 1800` 은 실측의 4배 여유라 그대로 둔다(줄일 이유가 없고, 느린 머신·콜드 캐시에서 여유가 필요하다).
- 단일 셸 명령 상한(10분) 안에 들어오므로 **한 호출에서 기동+대기로 완주시킬 수 있다.** 다만 실측은 특정 머신·웜 캐시 기준이므로, 상한에 걸리면 **백그라운드 실행 + 로그 파일 리다이렉트 + 폴링**으로 전환한다(세그먼트 분할은 마지막 수단 — 교차 오염을 못 잡는다).
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
- **YOLOX (onnxruntime, Apache-2.0)** 탐지 — **단일 백엔드이며 설정으로 고르지 않는다**, **Meta SAM2 (Apache-2.0)** 분할, **ByteTrack(trackers, Apache-2.0)** 추적, torch/torchvision, opencv-python
  - ⚠ **구 서술 폐기(2026-08-16 코드 실측)** — *"RT-DETRv2 (transformers)"* 는 **사실과 다르다.** 그 백엔드는 torch↔torchaudio ABI 불일치로 **제거**됐고(ADR-041), `requirements.txt` 에 `transformers` 가 **없으며** `app/models/rtdetr_loader.py` 도 삭제됐다. 회귀 가드 `ai-server/tests/test_yolo_dispatch.py(test_rtdetr_loader_모듈이_삭제됨 · test_app_routers_yolo에_rtdetr_import가_없음 · test_config에_detector_backend_설정이_없음)` 가 이 상태를 고정한다. **`detector_backend` 설정 분기도 없다** — 그 서술대로 이해하면 존재하지 않는 백엔드 선택지를 설계하게 된다.
- **라이선스 정책**: ultralytics(AGPL-3.0) 미사용 — 탐지/세그멘테이션 모두 permissive(MIT/Apache-2.0) 백엔드로 구성. 모델 무거운 의존(onnxruntime/sam2)은 lazy import
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
- **★영상 적재는 관제가 인입 원장에 직접 INSERT 하고 저작도구가 그것을 폴링한다 (ADR-042 — 적재 주체 반전. 구 서술 "공유 DB(MNG_*) READ 스캔" 폐기)**: 관제서버가 **저작도구 소유 테이블 `LS_DATA_INGEST` 에 평면 1행을 직접 INSERT**(`PRCS_STTS_CD='PENDING'`)하고, 저작도구 주기 배치(`ControlTrainingVideoScanJob`)가 **미처리 인입 행을 폴링**해(`LsDataIngestRepository.findPendingReadyForPolling`) `TrainingVideoIngestService` 가 `LS_DATA_RAW` 로 적재한다(비식별 선두). inbound 연동 API·M2M 인증은 미사용.
  - ⚠ **구 서술은 사실과 다르다 (2026-08-16 코드 실측 정정)** — *"관제서버가 공유 DB에서 영상을 학습용으로 설정하면 … 픽업해 적재. 공유 DB(MNG_*) READ 기반"*. **`MNG_*` 공유 테이블은 실제로 DROP 됐고**(`V167`) 회귀 가드 `MngControlMasterTableRemovalTest` 가 JPA 매핑·타입 참조·실행 SQL 참조 **각 0건**을 고정한다. 그 서술대로 이해하면 존재하지 않는 테이블을 읽는 설계를 하게 된다.
  - ⚠ **이 드리프트는 `SEQ-001`·`REQ-019`·`ERD-024` 에도 같은 형태로 복제돼 있었다** — 한 결정이 층마다 따로 적히는 이 저장소의 반복 패턴이다. **내부 파이프라인 1차 적재는** 포털 사용자 업로드를 사용하지 않는다 — 포털 자산 업로드(ADR-013 예외, 2026-07-17)는 내부 파이프라인·데이터마트와 완전 분리된 별도 경로(LS_PORTAL_* 전용)다. 구 '관리 화면 자체 업로드(TUS)' 방식은 폐지(코드 정리 후속)
- **파이프라인 순서**(구현됨): ⭐비식별화(전체 영상, 적재 직후 선두 자동) → 마킹(자동/수동, **비식별 영상 대상**) → VLM 시계열(콜백 비동기) → FFmpeg(**마킹 위치 기반** 원본+비식별 2벌 추출) → YOLO(**원본만** 실행, 비식별본 결과 공유) → SAM2 → 트랙 보간
  - 단계 순서는 **선언적 파이프라인**(`batch/pipeline/{BatchStep,BatchContext,BatchPipeline,BatchPipelineConfig}`)에서 관리. `BatchPipelineConfig` 의 `List.of(...)` 한 곳에서 재배치 가능 — pre-marking=[DEIDENTIFY], post-marking=[MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE]. 각 단계는 `isEnabled(ctx)` 조건부 실행 지원(dev 토글). (구 순서 '마킹(원본) 트리거 → VLM → 비식별 → 프레임추출' 폐지 완료)
- **마킹 단계**: **비식별화 완료 후** 작업자가 **비식별 영상**에서 자동/수동 마킹. 자동=**프레임 간격**(intervalFrames) 기반, 수동=키보드 단축키로 이벤트 시점 마킹. 마킹 결과는 **`frame_policy` 로만** VLM 위탁에 반영된다(⚠ 구 서술 "이벤트명 + 영상경로 + marks 배열을 VLM 에 전달" 은 **폐기** — verify 규격에 그 필드들이 없다. 아래 verify 절). **마킹 완료 시 MarkingCompletedEvent → MarkingBatchBridge(AFTER_COMMIT) → 잔여 배치(VLM→프레임추출→오토라벨링) @Async 시작**. 구현된 흐름: 관제가 `LS_DATA_INGEST` 에 직접 INSERT → 주기 배치가 미처리 인입 행 폴링(`ControlTrainingVideoScanJob`) → `TrainingVideoIngestService`가 `LsDataRaw` 적재(PENDING) → `VideoIngestedEvent`→`IngestDeidentifyBridge`(AFTER_COMMIT)→`AsyncDeidentifyRunner`(@Async)→`DeidentifyStep` 선두 비식별 자동 → 성공 시 `LsDataRaw.dataSttsCd=MARKING_READY` → 마킹 완료(`deIdntfYn='Y'` 가드 통과 시) → 잔여 배치. (post-marking 배치에서 비식별 단계는 제거됨)
- **마킹 화면**: **비식별 영상** 스트리밍(`GET /v1/videos/{rawSn}/stream`, HTTP Range 지원 — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) + 배속 설정(0.25x~4x) + 키보드 단축키(Space: 마킹, Del: 삭제, Enter: 완료). 마킹 중 비식별 누락 발견 시 **비식별 신고** 가능(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report` — 구현됨)
- **★VLM·KPST 위탁은 논블로킹 제출이다 (2026-08-03 실측 정정 — 구 "동기 호출(45s 타임아웃)" 서술 폐기)**: `VlmTimeseriesStep` 은 `.block(45s)` 로 파이프라인 스레드를 붙잡지 않고 **`subscribe()` 로 제출만 개시**한다(Phase C-1). 구 방식은 외부가 느려지면 core 2 짜리 배치 풀이 통째로 마르고 CallerRuns 역압이 호출 스레드까지 물었다. **KPST 비식별 제출도 대칭**으로, WAITING 원장 행을 선커밋하고 `createProject` 를 비동기 디스패치한다(Phase C-2).
  - **스텝이 확정적으로 말하는 사실은 "제출을 개시했다" 뿐**이다. 수락(ACK) 여부는 완료 핸들러가 `LS_BATCH_PROC_LOG`·원장에 비동기 기록하고, 그 기록은 `publishOn` 이 아니라 **명시적 디스패치**(`SubmitSignalDispatch`)로 전용 풀에서만 실행한다 — `publishOn` 은 풀 포화 시 거부가 **시그널을 나른 reactor-netty 이벤트 루프**로 흘러 JPA 쓰기를 이벤트 루프에서 실행시킨다.
  - **아무 신호도 없으면 미결 스위퍼가 회수한다** (`VlmSubmitPendingSweeper` / KPST 대칭). in-memory subscription 은 노드가 죽으면 통째로 사라져 ACK 도 실패 신호도 오지 않고, **실패 행이 없어 재시도 큐·실패 회수기가 집지 못한다** — 스위퍼가 유일한 회수 경로다. 2노드 Active-Active 에서 같은 후보를 두 번 재위탁하지 않도록 처리 전 **조건부 UPDATE 로 원자 클레임**한다(Quartz 클러스터링은 트리거 중복만 막는다).
  - ⚠ **미결 임계는 두 개이며 하나로 덮으면 정상 위탁을 뺏는다**: **ACK 창**(`stale-timeout-minutes`, 기본 30분 — 원장 `ISSUED`, 수락 응답조차 못 본 건) / **콜백 창**(`callback-timeout-minutes`, 기본 360분 — 원장 `ACCEPTED`, 벤더 분석은 영상 길이에 따라 수십 분 걸린다). 두 창을 구분하는 근거는 완료 핸들러의 `ISSUED → ACCEPTED` 전이(`VlmSubmitOutcomeRecorder`)이며, 그 전이가 없으면 구분이 성립하지 않는다.
  - 결과 상세는 VLM 서버가 콜백(`POST /v1/vlm/callback`)으로 별도 전송 → `VlmResultService` 가 `LS_DATA_META` 적재(`vlm.description`/`vlm.accuracy`) + 검수큐(`LS_DATA_META_REVIEW`) 진입은 `vlm.description` 1건만 (아래 verify 절)
- **★외부 VLM 위탁은 `verify` 다 (2026-08-06 사용자 확정, 구속 — 구 `describe` 규격 폐기)**: 위탁 엔드포인트는 **`POST /v1/videovlm/verify`** 하나이며 콜백 `results` 는 **배열이 아니라 객체** `{accuracy, description}` 다. 요구가 *"시계열 데이터를 전문 1개로 보여주고 편집 가능하게"* 였고, verify 가 "그 이벤트가 실제로 있었는가(일치도) + 무엇이 보이는가(서술)"를 한 벌로 돌려주기 때문이다. ⚠ **구 서술 "`describe` 로 구간(`start_sec`~`end_sec`)별 시계열 서술을 배열로 받아 구간 metaKey 로 적재한다" 는 폐기** — 벤더 규격서 v2.0.1 에 `describe` 엔드포인트는 존재하나 **우리는 호출하지 않는다**. 이미 적재된 구간 키(`0-8`·`8-16` …)는 **작업 결과라 보존**하며 화면·export 가 계속 읽는다(삭제·숨김 금지). 과도기 방어로 벤더가 구 배열을 보내면 **관대 파싱 없이 400**(무단 하위호환은 벤더 버그를 숨긴다). 상세는 [09 VLM 시계열](docs/v2-wiki/09-vlm-timeseries.md).
  - **★`framerate` 는 FPS 가 아니라 "몇 프레임당 1장"(추출 간격)이다** (규격서 §2.1 본문 근거) — 따라서 자동 마킹은 **`LS_MARKING.FRME_INTV_NOCS`(마킹 프레임 간격)** 를 싣고 **`LsMarking.fps` 는 쓰지 않는다**. FPS 를 보내면 벤더가 전혀 다른 간격으로 프레임을 뽑는다. 값이 없거나 0 이하면 설정 `vlm.client.frame-policy.framerate`(기본 25) 폴백. ⚠ **이름만 보고 FPS 로 단정하는 오해가 실제로 한 번 발생한 지점**이다 — 목서버 스키마(`mock-server/app/schemas/vlm.py`)에도 FPS 해석에서 온 **상한 `le=240` 이 남아 있어 자동 마킹 `intervalFrames=300` 이 422 로 죽었다**(2026-08-06 로컬 실측). 간격 해석에서는 240 초과가 정상 입력이라 **상한을 제거**했고 하한(`ge=1`)만 남겼다. 회귀 가드 `mock-server/tests/test_vlm.py(test_framerate가_240을_넘어도_수락한다_구_상한_폐기)`. ⚠ **실벤더의 상한 여부는 미확인**이라 관제·벤더 확답이 필요하다.
  - **`frame_policy` 는 마킹에서 도출한다** — 수동 마킹 → `frame_selected` + `selected_frames`(마킹 프레임 인덱스를 **정렬·중복제거**, 벤더 상한 **8** 초과분 절단) / 자동 마킹·마킹 부재·미지 모드 → `frame_interval`. `framerate` 는 **mode 무관 필수**(벤더 §3.2). 마킹 JSON 파싱 실패는 예외가 아니라 `frame_interval` 폴백이고, 요청 조립은 **선커밋 이전**에 끝낸다(선커밋 뒤에서 터지면 상관키만 남은 영구 대기가 된다).
  - **★`event_type` 은 저작도구가 매핑표를 만들지 않고 관제 인입에서 수신한다** — 조달처는 **`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`**(V176 신설). 우리가 아는 6종은 `fire`·`fall`·`violence`·`flooding`·`car_accident`·`kidnapping`(`LsDataIngest.VRFC_EVNT_TYPES`)이다. 관제가 이 값을 보내기 전까지는 **dev 업로드 화면의 검증이벤트유형 select** 로 지정한다.
  - **★★event_type 은 위탁을 막지 않는다 — 항상 호출하고 수용 여부는 벤더 응답이 정한다 (2026-08-06 사용자 확정, 구속 · 구 "사전 차단" 정책 폐기)**: 값이 **미수신이거나 우리 목록 밖이어도 조달값을 그대로 실어 위탁**한다(`VlmTimeseriesStep.resolveEventType` — 정규화만 하고 판정하지 않는다). 판정의 단일 진실원은 **벤더 응답**이며, 거부되면 `VlmSubmitOutcomeRecorder.onSubmitFailed` 가 `SKIP_REASON_SUBMIT_FAILED` 로 기록하고 마킹을 `VLM_REQUESTED→VLM_FAILED` 로 전이시킨다.
    - **폐기된 구 동작**: 미수신·미허용이면 **외부 호출 0 + `LS_BATCH_PROC_LOG` `VLM/SKIPPED`**(미수신 / 미지원 사유 구분) 후 재개 대기. **폐기 사유** — 그 허용목록은 **벤더 enum 의 우리 쪽 사본**이라 벤더가 값을 넓히면 **정상 값을 우리가 먼저 막는다**(사본이 두 번째 진실원이 되는 이 저장소의 반복 결함 패턴).
    - ⚠ **값을 지어내지는 않는다** — 조달값이 없으면 `null` 을 그대로 보낸다(벤더 필수 필드라 4xx 가 예상되며 그 거부를 기록한다). 관제 이벤트 코드에서 유추해 채우지 않는다.
    - ⚠ **인지·수용한 대가**: 관제 값이 채워지기 전 영상은 **매번 외부 호출 1회 + 확정 실패 기록**을 남긴다(구 동작은 호출 0). 또 확정 실패는 **비재시도**이고 `data_stts_cd` 가 종결되면 **재마킹이 412 로 막혀** 그 영상은 시계열 메타 없이 확정된다 — 자동 재위탁 트리거는 없다.
    - ⚠ `SKIP_REASON_EVENT_TYPE_MISSING`·`_UNSUPPORTED` **상수는 신규 발생이 없어도 존치**한다(이미 적재된 `LS_BATCH_PROC_LOG` 행의 재개 배선 키 — 삭제·문구 변경 시 과거 보류분이 영구 고착). `SKIP_REASON_*` 문자열은 재개 판정의 키라 다듬지 말 것(레거시 낱말 `describe` 가 값에 남아 있는 것은 의도).
    - **★우리 쓰기 통로(dev 업로드)도 6종 allowlist 를 폐기했다 (2026-08-06 사용자 확정 — "6+수동입력")**: 화면은 **6종 프리셋 + 직접 입력**이고(`TusMetaFieldsets.VrfcEvntTypeField`, 센티넬 `__manual__` 은 화면 모드 표식이라 전송되지 않는다), 서버 판정은 목록이 아니라 **형식**이다 — 판정 단일 원천 `LsDataIngest.isVrfcEvntTypeFormatValid`(**소문자·숫자·밑줄 + `VRFC_EVNT_TYPE_MAX_LENGTH` 20자**), DTO `@AssertTrue` 와 `TusUploadService.validateVrfcEvntType` 2단이 **같은 함수**를 부른다.
      - **형식은 왜 남기나** ①**길이** — 컬럼이 `VARCHAR(20)` 이라 입구에서 400 을 안 주면 INSERT 시점 **DB 오류(500)** 가 된다 ②**문자 집합** — 이 값은 **외부 벤더 요청 바디와 로그에 그대로 실린다**. 공백·개행·제어문자가 섞이면 로그 인젝션(CWE-117)·벤더측 파싱 오류가 된다(벤더 enum 표기와 같은 소문자 스네이크로 제한).
      - `VRFC_EVNT_TYPES` 6종은 **프리셋(자주 쓰는 값)일 뿐 허용목록이 아니다** — 서술을 가진 목업 응답 매핑 키이자 화면 드롭다운 소스다.
      - 회귀 가드: `TusUploadServiceTest`(`★6종_밖이어도_형식만_맞으면_통과한다_구_allowlist_폐기` · `★컬럼_폭을_넘는_21자는_400이다_DB오류로_새지_않는다`) · `tusUpload.test.tsx`(`직접입력을_고르면_자유입력칸이_열리고_그_값이_전송된다` · `프리셋에서_직접입력으로_바꾸면_이전_프리셋값이_남지_않는다`).
    - ⚠ **목서버는 이 반전에 맞춰 `event_type` 을 완화했다 (2026-08-06)** — `mock-server/app/schemas/vlm.py(VerifyRequest.event_type)` 가 **enum 6종 강제·필수를 폐기**하고 `Optional[str]` 로 열려 **어떤 값이든·없어도 200 accepted** 다(표준 6종은 전용 서술, 그 밖·미지정은 **폴백 서술**). 안 그러면 관제 값이 채워지기 전 영상은 `400 Field required` 로 로컬·dev 에서 **한 건도 완주하지 못한다**. ⚠⚠ **목서버 한정 완화이며 실벤더가 관대하다는 근거가 아니다** — 규격상 `event_type` 은 required + enum 6종이므로 **"목이 받아줬으니 실연동도 된다"고 판단하지 말 것**. 벤더 확답이 오면 되돌리거나 토글로 감싼다.
    - 회귀 가드: `VlmTimeseriesStepVerifyRequestTest`(`검증이벤트유형이_없어도_위탁한다_구_사전차단_폐기` · `우리가_아는_6종_밖의_검증이벤트유형도_그대로_위탁한다_구_허용목록_차단_폐기` · `구_보류사유_상수는_과거행_판독을_위해_재개목록에_존치된다`).
  - **metaKey 규격** — `vlm.description`(서술 전문 ≤2000, **검수큐 진입 + 편집 가능**) / `vlm.accuracy`(일치도 `0~1`, **화면 전용**) / 레거시 구간 키(보존 · 화면에 **읽기 전용 병기**) / `video.*`(ffprobe·인입 기술메타). 조회 `GET /v1/frames/{srcSn}/meta` 응답은 `items`(편집) · **`readOnlyMeta`(신설)** · `technicalMeta` 3목록이며 **기존 두 목록의 필드명·타입·시맨틱은 불변**(추가만). `vlm.accuracy` 수정 요청은 `video.*` 와 **같은 400** 이고 메시지에 요청 키를 echo 하지 않는다.
  - **★`vlm.accuracy` 가 데이터마트·export 에 없는 것은 의도다 (R12)** — 검수큐에 넣지 않으므로 리뷰행을 INNER JOIN 하는 `V_COMPLETED_META` 에 **구조적으로 도달하지 않고**, 학습데이터 export JSON 에도 넣지 않는다. **"마트에 안 나온다"를 결함으로 재보고하거나 뷰를 고치지 말 것.**
  - **★R13 — 승인 영상의 서술이 갱신되면 재검수 + 통지**: `APPROVED` 영상의 `vlm.description` 이 **실제로 달라진 경우에만** 검토행을 `PENDING` 으로 되돌리고(`LsDataMetaReview.reopenForRecheck`) `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` 를 발행한다. **값이 같으면 no-op**(멱등 — 재검수·통지 폭주 방지). 근거: 뷰가 **라이브** `LS_DATA_META` 를 조인하므로 상태를 APPROVED 로 둔 채 값만 갱신하면 REVIEWER 가 한 번도 보지 않은 서술이 그대로 관제로 나간다. `REJECTED` 검토행도 되돌린다(반려 판단은 *바뀌기 전 본문*에 대한 것이라 새 본문에 적용되지 않는다). 신규 적재·미승인 영상은 대상이 아니다.
  - **export `video.vd_description` 조달 순서** — ① `vlm.description` → ② `manual-timeseries`(사람이 직접 쓴 전문) → ③ 레거시 구간 행을 **`start_sec` 숫자순**으로 이어붙임 → ④ **`null`**(빈 문자열 아님 · 지어내지 않음). 판정 단일 원천은 **`dataset/export/json/VlmDescriptionPolicy`** 이며 매퍼·서비스가 재유도하지 않는다. 조달 참여 키라도 **값이 실제로 바뀌어야** export 를 재생성한다 — 무변경 저장이 `v2·v3·v4…` 를 이미지 2벌과 함께 적층하던 결함(CWE-770)의 차단이며, 무변경 저장 자체는 **200 성공 + 통지 발행**이고 재생성 플래그만 생략된다.
  - **알려진 관찰 2건 — 결함 아님 (되돌리지 말 것)**: ①관제 pull API `TaskQueryService.getMeta` 는 `LS_DATA_META` **전 행**을 반환해 `vlm.accuracy`·`video.*` 가 포함된다. R12 가 규정한 축(검수큐 → 뷰 → export)과 **다른 채널**이고 `video.*` 도 이전부터 그랬다 — 좁히려면 **관제 계약 협의가 선행**돼야 한다. ②`LabelContentHasher` 의 `VDSC` 블록은 현재 배포 형상에서 **아무것도 게이트하지 않는다**(해시가 게이트하는 유일한 지점이 `forceRegenerate=false` 분기인데 그 값으로 진입하는 프로덕션 경로가 0건 — 유일 후보 `DatasetExportBridge.onReExport` 가 소비하는 `DatasetReExportEvent` 는 **발행처 0 인 휴면 리스너**). 기록만 되며 `force=false` 경로가 되살아날 때를 위해 **유지**한다.
  - **적대검증 기각 근거 (다시 꺼내지 말 것)**: "R13 이 웹훅 재수신으로 폭주한다"는 지적은 **발동 경로 3개가 전부 막혀** 성립하지 않는다 — 재개 러너(`VlmWithheldResumeRunner`)는 **시계열 메타 0건**일 때만 재위탁하고, 미결 스위퍼는 **미결 원장(`ISSUED`/`ACCEPTED`)** 만 회수하며, 수동 재처리는 **`FAILED` 만** 클레임한다(`BatchTransitionService.tryClaimReprocessFromFailed`). 승인 완료 영상은 이 셋 중 어디에도 걸리지 않는다.
- **배치 상태 전이**: 적재 시 `LsDataRaw.dataSttsCd=PENDING` → 선두 비식별 성공 시 `MARKING_READY`(마킹 진입 허용) → 배치 완료 시 `COMPLETED`. BatchOrchestrator.process() 시작 시 `LsRawDataStatus → PROCESSING`, 완료 시 작업 상태 `→ ASSIGNED 복귀`(COMPLETED 는 검수 승인 시점의 작업 종결 상태이므로 배치 완료가 점프시키지 않음 — 점프 시 검수 제출 ASSIGNED→PENDING 이 상태 머신에서 차단됨), 실패 시 `→ FAILED`. **두 테이블 책임 분리**: `LsDataRaw.dataSttsCd`(배치 단계: PENDING→MARKING_READY→COMPLETED)는 완료 시 `COMPLETED` 로 마감하고, `LsRawDataStatus.dataSttsCd`(작업/검수 워크플로우 상태)는 배정 시점 생성·ASSIGNED 로 복귀시켜 라벨링/검수 플로우가 이어지게 한다. ⚠ **구 서술 폐기(2026-08-15 코드 실측)**: *"작업 상태의 `COMPLETED` 는 `ReviewService.approve` 에서만 전이한다"* 는 **사실과 다르다** — 검수 종결값은 **`APPROVED`** 이고 `LsRawDataStatus.STTS_COMPLETED` 로 **전이하는 코드는 `src/main` 에 0건**이다(`transitionTo(...STTS_COMPLETED)` 실측 0건). ⚠ **"사용처가 0건" 은 아니다 (2026-08-15 재실측 정정)** — `marking/listener/MarkingBatchBridge(SKIP_STATUSES)` 가 이 상수를 **읽는다**(재트리거 차단 집합의 멤버십 검사, 방어적 용도). **구 서술 "선언만 있을 뿐 `src/main` 사용처가 0건(테스트 16건만 참조)" 은 폐기** — 그 문구를 근거로 상수를 지우면 그 배선이 깨진다. 도달 경로가 없다는 실질 주장의 근거는 **"전이 대상으로 쓰이지 않는다"** 이지 "아무도 안 읽는다" 가 아니다
  - **★`COMPLETED` 는 세 축에 있고 셋 다 정상이다 — 하나로 통일하지 말 것 (2026-08-15 실측, 구속)**

    | 축 | 소유 | `COMPLETED` 의 뜻 |
    |---|---|---|
    | **배치 단계** | `LsDataRaw.DATA_STTS_COMPLETED` | 배치 처리 완료 — **실사용 중** |
    | **검수 워크플로** | `LsRawDataStatus.STTS_COMPLETED` | **도달 경로 없음** — 종결은 `STTS_APPROVED` |
    | **FE 표시 코드 (2곳)** | `ReviewResponse.mapToFeStatus`(검수 응답) · **`assignment/domain/AssignmentWorkStatus`**(배정 목록 `GET /v1/assignments`) | **`APPROVED` → `"COMPLETED"` 매핑** — 응답 `status` enum 에 실재하는 **정상 계약** |

  - ⚠ **배정 목록 축의 값 집합은 `PENDING`·`IN_PROGRESS`·`REVIEW_PENDING`·`COMPLETED`·`REJECTED` 이며 `APPROVED` 를 반환하지 않는다.** 이 축의 `COMPLETED` 를 `APPROVED` 로 "정정"하면 **대시보드 진행률이 검수완료 작업마다 0% 로 떨어진다**(소비처 `frontend/src/pages/DashboardPage.tsx` 진행률 계산). 실제로 감사가 이 축을 결함으로 오분류한 사례가 있다(DOMAIN-006 「지배적 결함 4」 — **기각됨**).
  - ⇒ ITEM·문서에서 `APPROVED/COMPLETED` 병기를 보면 **"둘 중 아무거나"가 아니라 "`dataSttsCd=APPROVED` / 응답 `status=COMPLETED`"** 일 수 있다. **단순 치환하면 정확한 계약을 지운다.** 판정 근거는 `ReviewStateMachine`(클래스 javadoc)·`ReviewResponse(mapToFeStatus)`
- **비식별 호출 조건**: **전체 영상 비식별 후 마킹**(ANONY 포함, 게이팅 폐지 — 무조건 자동 실행) — 비식별이 파이프라인 선두 단계로, 적재 직후 자동 트리거(`VideoIngestedEvent`→`IngestDeidentifyBridge`→`AsyncDeidentifyRunner`→`DeidentifyStep`)된다. 비식별 영상이 마킹 대상이 되며 원본은 별도 보존 (구 규칙 'PRVC/PSDO만' 폐지). **증강(augment) 적재 경로는 아직 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned/후속)**. 비식별 실패/신고 영상은 자동 재비식별 큐 없이 외부 비식별 프로그램에서 수동 재비식별(`deIdntfYn='F'` + 기존 resolve 경로)
- 원본 영상과 비식별 영상은 **별도 경로로 동시 저장**
- **오토라벨링**: YOLO/SAM2는 **원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유 (별도 실행 없음)
- YOLO/SAM2는 `AiServerClient`로 호출 (타임아웃 60s + Resilience4j CircuitBreaker). **외부 VLM 은 별개 빈 `VlmClient`**(외부 벤더 직접 호출 · `vlm.client.timeout-seconds` 기본 10s + Resilience4j `vlmClient`)이며 ai-server 를 경유하지 않는다

### 작업 배정
- **REVIEWER가 WORKER에게 배정** (역할 단일화 — ADMIN 권한은 REVIEWER에 통합)
- `LS_TASK_ALTMNT`에 `TASK_TYPE_CD='LABELER'` INSERT, 재배정 시 `LS_TASK_EVNT_LOG` 에 `REASSIGN` 기록 (V4 정합 — 구 `LS_TASK_ASSIGN_HISTORY` 는 이중 기록이었고 조회는 이벤트 로그만 읽었다. 테이블 제거)
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
- **TASK_COMPLETED 페이로드 — 메타만 · 실제 전송은 10필드 (2026-08-15 실측 정정 · 진실원은 구현 `controlnotify/dto/TaskCompletedPayload`)** — 관제 계약(API-251 v17) 자체는 **9필드**(required 8 + optional 1)이고 거기에 우리가 **`output_ver_no`(선택)를 더해 10개**를 보낸다. ⚠ 구현은 **앞 9개를 전부 키 유지** 대상으로 다루므로(javadoc 이 그 9개를 *"기존 required 필드"* 로 부른다) **"관제 계약상 optional 이 하나 있다"와 "우리는 9개 키를 항상 남긴다"는 층이 다른 서술이다** — 한쪽으로 통일하지 말 것. 필드 구성: `job_id`(=`RAW_SN`) + `event_type_cd`(**영상 이벤트 유형 코드이지 통지 종류가 아니다**) + `evnt_cls_cd` + `evnt_ctgry_cd` + `lclgv_cd` + `lclgv_nm` + `duration_sec` + `image_count` + `gen_ai_yn` = **required 9**, 여기에 **`output_ver_no`**(optional) 하나. 라벨/메타 본문 자체는 포함하지 않으며, 관제가 필요 시 본 도구 API로 보강
  - ⚠ **구 서술은 폐기** — *"이벤트 타입 + 영상 메타(파일명·채널) + 검수 완료 일시 + 결과 요약 카운트(라벨 N건·메타 M건) + 요청 ID"* 는 **어느 것도 페이로드에 없다**(각 0건). 특히 `요청 ID` 는 바디가 아니라 `ControlNotifyService` 가 UUID 로 만들어 **폴백 큐 `IDMP_KEY` 로만** 쓴다. 이 서술대로 만들면 관제가 받지 않는 필드를 조립하게 된다.
  - ⚠ **키 유무 규약은 비대칭이며 의도된 것이다** — **required 9 는 값이 `null` 이어도 키를 남긴다**(빼면 전량 `422 VALIDATION_FAILED`). 반면 **`output_ver_no` 는 null 이면 키 자체를 생략**한다(관제가 "키 없음 = 산출물 변경 없음 → 재픽업 불요"로 읽는다). 따라서 `@JsonInclude` 는 **필드 레벨로만** 건다 — 클래스 레벨에 걸면 required 까지 생략돼 통지가 전량 깨진다(회귀 가드 `TaskPayloadStructureTest`).
  - ⚠ **JSON 키는 관제 스펙명이며 우리 컬럼명이 아니다** — `evnt_cls_cd`(관제) ≠ `EVNT_CLSF_CD`(우리 컬럼). 임의로 맞추지 말 것.
  - **이벤트 2코드는 인입 LATERAL 조인으로 조달**한다(설계 D1 — `LS_DATA_RAW` 에 그 두 컬럼은 **없다**). 연결 규칙은 뷰와 같은 단일 진실원 `IngestSourceLink`. 반면 **`gen_ai_yn` 은 인입이 아니라 자기 `LS_DATA_RAW.SRC_TYPE`** 에서 도출한다(파생은 자기 행이 `AUGMENTED`).
- **TASK_MODIFIED 페이로드 — 변경 *파일명* 목록만 전달 (2026-08-12 실측 정정 · 진실원은 구현 `controlnotify/dto/TaskModifiedPayload`)**: **4필드**다 — 작업 ID(`job_id` = `RAW_SN`) + **`changed_items`**(`{images: [파일명], jsons: [파일명]}`) + **`ver_expln`**(optional) + **`output_ver_no`**(optional, 2026-08-15 실측 보정 — 구 서술 "3필드" 는 이 필드를 빠뜨렸다). 라벨/메타 본문·좌표·PII·토큰·**절대 경로**는 담지 않는다(파일 *이름*만 — CWE-359). 파일명 규칙의 단일 지점은 `dataset/export/ExportFileNaming` 이며 export writer 와 공유한다(`{FRM_NO 4자리 zero-pad}.jpg`/`.json`).
  - ⚠ **구 서술은 폐기** — *"마지막 수정 일시 + 변경 프레임 목록(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|…`) + 변경 요약 카운트 + 요청 ID"* 는 **어느 것도 페이로드에 없다.** 이 서술대로 만들면 관제가 받지 않는 필드를 조립하게 된다.
  - **`changed_items` 의 범위 규칙은 "export 재생성을 동반했는가"** 다 — 재생성 동반이면 **전 프레임** 이미지·JSON 을 싣는다(비우면 관제가 재픽업을 안 해 보유본이 stale 로 고착). 재생성 없음(촬영환경 메타 수정 등)이면 **빈 리스트가 정상**이다(없는 파일을 실으면 관제가 404). **어느 경우든 통지 자체는 발송된다.**
  - **`data_info` 는 싣지 않는다** — 관제 명세에 키 스키마가 없다(규격서 §7-E, 회신 대기). 추정 스키마로 필드를 만들면 관제가 422 로 거부하거나 잘못된 값을 적재한다.
  - **변경 종류 상수(`ChangeType`)는 내부 전용이다** — `LABEL_ADDED`·`LABEL_UPDATED`·`LABEL_DELETED`·`META_UPDATED` 에 **`FRAME_DISCARDED`·`FRAME_RESTORED`**(R4·R5)가 더해져 6종이지만, **관제로 전송되지 않는다.** 디바운서의 축적 키와 flush 요약 감사 로그로만 쓰인다(관제가 관측하는 변화는 "그 프레임 파일이 산출물에서 사라진다"이며 `V_COMPLETED_FRAME`·산출 폴더에 이미 드러난다). 폐기·복원을 한 값으로 합치지 않는 이유는 두 방향이 산출물에 정반대 영향을 주므로 감사에서 구분돼야 하기 때문이다. ⚠ 두 신규 값은 **관제와 미합의(협의 대상)** 이나 미전송이라 계약 영향은 없다.
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
  - **★★작업자가 화면에서 내용을 고치면 무조건 재검수를 거치고, 재승인 시 무조건 새 버전이 나온다 — 예외 없음 (2026-08-13 사용자 확정, 구속)**

    > "라벨링 화면에서 수정이 들어가면 무조건 검수를 새로 타야 하고 그러면 무조건 새 버전이 나오는거야." · "이벤트 어노테이션 단독 수정도 당연히 생성되어야지. 어노테이션에 들어가는데"

    해당 경로: 라벨 추가·수정·삭제 · 트랙 편집·병합 · 버전 롤백 · 촬영환경(날씨/시간대/계절) 수정 · 프레임 설명 수정 · 개인정보 메타 수정(프레임 축·영상 축) · **이벤트 어노테이션 저장**. 이벤트 어노테이션이 포함되는 이유는 그 값이 **산출 JSON 최상위에 pass-through** 되고 콘텐츠 해시 축에도 들어가기 때문이다 — 바뀌면 산출물이 바뀐다.
    - **폴더 없이 통지만 나가는 경우는 비식별 누락 신고 접수 1종뿐**이다(라벨·메타가 아니라 상태 플래그만 바뀐다). ⚠ 단 **승인 이력이 있는 영상은 신고 접수 자체가 거부**(412)되므로, 실제로 관제에 도달하는 그 통지는 없다.
    - **수정 횟수와 무관하게 재승인 1건 = 새 폴더 1개 = 통지 1건**이다(디바운스 윈도우가 합친다). 관제에는 `output_ver_no` 로 그 버전 번호를 실어 통지↔폴더 대응을 추적하게 한다.

  - **★재생성·통지의 트리거는 「검수 승인」 한 곳이다 — 최초 승인이든 재승인이든 (2026-08-07 사용자 확정, 구속 · 2026-07-27 "수정 즉시 재생성" 정책 폐기)**: 승인 후 수정이 발생하면 그 영상은 **재검수 대상**이 되고, **검수자가 그 수정을 다시 검수해 승인한 시점**에 export 를 새 버전 폴더 `v{n+1}` 로 전량 재생성(이미지 2벌 포함)한 뒤 통지한다.
  - **★대상은 개수가 아니라 성질로 정한다 (2026-08-15 사용자 확정, 구속)**: **사람이 콘텐츠를 고치는 모든 경로**가 대상이며, **제외는 넷뿐**이다 — **외부 VLM 콜백** · **비식별 신고 접수** · **운영자 정정 배치** · **event_annotation 지연 승인**(승인 행위 자체). 판정은 *"사람이 산출물에 들어가는 내용을 바꿨는가"* 하나다.
    - ⚠ **"N종"·"N개 경로" 같은 개수 표기를 쓰지 마라.** 경로가 하나 늘 때마다 그 숫자를 인용한 모든 층(ADR·SEQ·AC·INTSPEC·EVT…)이 동시에 틀리고, 다음 감사가 그것을 결함으로 다시 잡는다. **실제로 그 일이 일어났다** — 같은 목록이 7종·8종·9종으로 갈려 있었다(2026-08-15 전수 정합).
    - **열거는 예시일 뿐 전수 목록이 아니다**: 라벨 수정(`LabelService`) · 트랙 편집(`TrackEditService`) · 트랙 병합(`TrackMergeService`) · 버전 롤백(`VersionService`) · 촬영환경 수정(`EnvironmentMetaService`) · 프레임 설명 수정(`FrameDescriptionService`) · 개인정보 판정 수정(**영상 축 `VideoPrivacyMetaService` · 프레임 축 `FramePrivacyMetaService`**) · 메타 수동 편집(`MetaService`) · 이벤트 어노테이션 저장(`EvntAnnoService`).
    - ⚠ 이벤트를 **발행하는 지점**은 위 예시보다 많고, 그중 제외 4종에 해당하는 것만 재검토 대상이 아니다. **발행 지점과 재검토 대상 경로는 다른 축이니 같은 것으로 세지 마라.** (참고로 2026-08-07 전수 실측 당시는 14개 클래스·16개 지점이었다 — **그 시점 기록이며 현재 사양의 개수가 아니다.** 실제로 이 수치는 `EVT-004` 12 · 검증결과 11 로 층마다 갈려 있었고, 그래서 개수 표기를 걷어냈다)
    - **근거**: 학습데이터는 **검수를 통과한 것만** 확정이다. 사람이 고쳤을 뿐 아직 검수자가 보지 않은 내용이 관제·데이터마트로 나가면, 검수라는 게이트가 산출물 축에서만 우회된다. *"파일이 옛 내용이면 동기화 요구가 성립하지 않는다"* 는 구 근거는 **미검수 내용을 내보내는 것으로 그 요구를 충족시키려 한 것**이라 방향이 틀렸다 — 동기화의 단위는 **확정된 학습데이터**다.
    - ⚠ **인지·수용한 대가**: 수정 후 재승인 전까지 관제는 **직전 승인본**을 본다. 그 구간의 최신 내용은 관제에 없다.
    - ✅ **반전 구현은 완료됐다 (2026-08-13 실측 확정 — 구 서술 "절반만 됐다"·"구현이 아직 이 정책을 따르지 않는다" 는 둘 다 폐기)**
      - ✅ **①수정 시 재검토 표시 세우기 = 구현됨**(Phase 7a-1). 축은 이벤트가 직접 싣고(`controlnotify/event/TaskModifiedEvent` 의 `needsRecheck`) `controlnotify/listener/ReviewRecheckMarkListener` 가 소비해 `ReviewApprovalGate.markNeedsRecheck` 로 `REVLT_YN='Y'` 를 세운다. 서비스들이 수정 즉시 `TaskModifiedEvent` 를 계속 발행하는 것은 **설계 ②가 규정한 의도**다(변경 프레임 목록이 축적돼야 통지 본문을 만들 수 있다).
      - ✅ **②재승인까지 통지 flush 보류 = 구현됨.** 배선 위치는 `controlnotify/debounce/LsMonNotiAcmlRepository` 이며 **2겹**이다 — **Phase 7a-2**(flush 후보 SELECT 에서 `NOT EXISTS (… REVLT_YN='Y')` 로 제외) + **Phase 7a-2b**(클레임 UPDATE 문 자체에도 같은 조건 재확인, SELECT~UPDATE 창 폐쇄). 재승인이 `ReviewService.approve` 에서 `clearNeedsRecheck()` 로 표식을 지우면 축적 윈도우가 다음 flush tick 에 풀린다.
        - ⚠ **구 서술이 "미구현"이라 오판한 이유 — 같은 실수를 반복하지 말 것**: 근거가 *"`ControlNotifyDebouncer` 가 재검토 표시를 전혀 읽지 않는다(참조 0건)"* 였는데 **그 문장 자체는 참이다.** 틀린 것은 결론이다 — 그 클래스가 **호출하는 리포지토리 쿼리**에 조건이 있다. **클래스 파일 하나를 grep 해서 "그 기능이 없다"고 결론내지 말 것.** 이 저장소는 조건이 JPQL·네이티브 쿼리·리스너로 분산되는 것이 통상 구조다.
      - ★**재승인 시 재생성을 하는 주체는 승인 이벤트가 아니라 디바운서다.** **최초 승인**은 `ReviewApprovedEvent` → `runApprovalAsync` → `doExport(rawSn, true)` 강제 재생성 → `TASK_COMPLETED`. **재승인**은 그 이벤트를 **발행하지 않고**(`isReapproval` 분기 — 중복 방지) 표식 해제로 풀린 축적 윈도우를 디바운서가 flush 하며 재생성 + `TASK_MODIFIED` 를 낸다. 따라서 *"승인 경로는 항상 force=true(R6)"* 는 **최초 승인·폴백 경로에만** 적용되고, 재승인의 재생성 여부는 **축적된 `exportRegenerated`(OR 누적)** 가 정한다. 이걸 혼동하면 "재승인하면 무조건 새 버전"이라고 잘못 결론낸다.
    - ⚠ VLM 서술 갱신(R13)은 이미 **재검수로 되돌리는** 축이라 이 정책과 방향이 같다. 다만 현재는 되돌림과 통지를 **동시에** 하므로 통지 시점만 재승인으로 옮기면 된다.
    - **★반전 구현 설계 (2026-08-07 사용자 확정, 구속 — 위 문단의 "코드 변경"이 이 설계다)**
      - ⚠ **위 열거는 7개가 아니라 14개 클래스·16개 지점이었다 (2026-08-07 전수 실측)** — 누락돼 있던 7개: `VlmResultService` · `DatasetVideoMetaEnvCorrectionTx` · `VideoPrivacyMetaService` · `DeidentReportService` · `MetaService` · `EvntAnnoReviewService` · `EvntAnnoService`. **목록을 7개로 되돌리지 말 것.**
      - **① 승인 상태를 되돌리지 않는다 — `LS_RAW_DATA_STATUS.REVLT_YN`(재검토여부, `CHAR(1)` Y/N, 기본 `N`) 신설.** `APPROVED→PENDING` 자동 전이는 **채택하지 않았다**: 데이터마트 뷰 **4종 전부**가 라이브 `DATA_STTS_CD='APPROVED'` 로 게이트하므로(V174 주석의 *"뷰 노출 ⇔ 현재 라이브 APPROVED"* 불변식) 상태를 내리는 순간 **이미 통지된 영상의 행이 관제에서 사라져** 「검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다」(2026-07-28 구속)를 정면으로 위반한다. 물리명·도메인은 **행안부 공통표준용어 `재검토여부=REVLT_YN` + 도메인 `여부C1`** 그대로다(신규 약어 생성 아님).
      - **② 수정 시 `TaskModifiedEvent` 발행은 그대로 두고, 내보내는 시점만 옮긴다.** 변경 프레임 목록이 축적돼야 `TASK_MODIFIED` 본문을 만들 수 있기 때문이다. `ControlNotifyDebouncer` 는 **`REVLT_YN='Y'` 인 동안 만료 flush 를 보류**하고, 재승인이 표시를 해제한 뒤 축적분을 내보낸다. (구 설계안 "수정 시 발행 자체를 막는다"는 폐기 — 변경 프레임 목록이 소실돼 통지 계약을 못 채운다.)
      - **③ 재검토 필요 여부는 이벤트가 직접 싣는다** — 발행처 클래스명으로 추정하지 않는다. `exportRegenerated` 가 이미 같은 원칙으로 설계돼 있고 그 축을 확장하는 것이다.
      - **④ 대상 = 사람이 콘텐츠를 고치는 경로만**(사용자 확정): 라벨 저장 · 트랙 편집 · 트랙 병합 · 버전 롤백 · 촬영환경 수정 · 프레임 설명 수정 · 개인정보 판정 수정(영상 축·프레임 축) · 메타 수동 편집 · event_annotation 저장. **제외**: 외부 VLM 콜백(R13 이 이미 항목 단위 재검토 축을 가진다) · 비식별 신고 **접수** · 운영자 정정 배치 · event_annotation **지연 승인**(승인 행위 자체).
      - **⑤ 비식별 신고 해소(resolve)도 이 정책을 따른다**(사용자 확정) — 구 동작 "재승인 없이 강제 재생성 + 완료 통지"는 폐기하고 **재검토 표시만 세운다**. ⚠ **인지·수용한 대가**: 재승인 전까지 관제는 **마스킹 실패가 남은 직전 산출물**을 계속 본다(개인정보 관점에서 불리한 방향임을 알고 선택). 단 **강제 재생성(멱등 우회) 근거는 그대로 유효**하다 — 콘텐츠 해시가 이미지 픽셀을 반영하지 않아 해시가 같아도 디스크의 비식별 이미지는 바뀌어 있다. 재승인 시점의 재생성이 강제 재생성이어야 한다.
      - **⑥ 재승인은 상태 전이가 아니다.** 이미 `APPROVED` 이므로 승인 경로는 `REVLT_YN='Y'` 일 때 `APPROVED→APPROVED` 를 예외적으로 허용한다(그 외 `APPROVED` 출발 전이 차단은 유지). 승인 파이프라인(버전 스냅샷·동결·강제 재생성·통지)을 **그대로 재사용**한다 — 재승인 전용 파이프라인을 새로 만들지 않는다.
      - ⚠ **알려진 한계(이번에 만들지 않음, 사용자 확정)**: **수정만 하고 아무도 재승인하지 않은 영상을 감지·알림하는 장치가 없다.** 그 영상은 `REVLT_YN='Y'` 인 채로 방치되고 관제는 직전 승인본에 고착한다. 스윕 잡·알림 채널·임계 설정이 필요한 별도 기능이다.
      - ✅ **판정 지점 단일화는 완료됐다 (2026-08-12 실측 정정)** — 구 서술 *"`isReviewApproved` 판정이 12곳에 복제돼 있다 … 반전 작업은 이를 단일 판정 지점으로 합친다"* 는 **이미 이행된 과제**였다. 현재 복제 `private` 메서드는 **0건**이고 판정은 `assignment/service/ReviewApprovalGate` 한 곳이며 main 18개 파일이 이를 주입해 쓴다(구 복제 건수도 12가 아니라 **14**였다 — `ReviewApprovalGateTest` javadoc 이 정본). 이 항목을 **다시 작업으로 만들지 말 것.**
      - ⚠ **CLAUDE.md 자체 드리프트 1건(2026-08-07 실측 정정)**: 위 「배치 상태 전이」 절의 *"작업 상태의 `COMPLETED` 는 `ReviewService.approve`(검수 승인) 에서만 전이한다"* 는 **사실과 다르다** — 승인은 `STTS_APPROVED` 로 전이하며, `LsRawDataStatus.STTS_COMPLETED` 로 전이하는 코드는 **한 곳도 없다**(`ReviewStateMachine` 주석도 *"검수 종결 시 영속되는 상태는 APPROVED"* 라고 명시). 검수 완료 상태값은 **`APPROVED`** 다.
    - ⚠ **이 문단은 2026-08-07 정합 라운드 중 미커밋 작업본에서 통째로 삭제됐다가 복원됐다** — 같은 편집이 「작업 위임 선언」을 추가하면서 이 5줄을 함께 지워, 본문이 폐기된 2026-07-27 서술로 되돌아가 있었다(사용자 재확인 후 복원). **다시 지우지 말 것.**
  - **통지는 export 성공(SUCCEEDED) 후 발송**한다(승인 `TASK_COMPLETED`·수정 `TASK_MODIFIED` 양쪽). export 가 `@Async` 라 통지가 앞서면 관제가 **구 버전 폴더**를 픽업한다. **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(통지 유실이 아니라 성공 시점으로 지연). **재export 트리거(`DatasetExportBridge`·수정 축적·디바운스 flush)는 `authoring.control-notify.enabled` 토글과 무관하게 항상 동작**한다(토글 off 인 dev/stg/prd 기본 형상 포함) — 이 토글은 통지 발송(`sendCompleted`/`sendModified`)만 게이팅한다.
  - **버전마다 전체 자기완결 + 전 버전 보존(삭제 안 함)**. 델타만 두면 요구의 *복구(rollback)* 가 성립하지 않으므로 **retention 정리 로직을 만들지 않는다**. 저장소 증폭은 감수한다(검수 완료 영상의 재검수 빈도가 낮다는 판단).
  - `LS_DATASET_EXPORT.OUTPUT_PATH_NM`(V173 개명 — 구 `EXPORT_PATH_NM`) 은 **영상 루트**(`{rawSn}`, 버전 루트 아님)를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다.
  - ⚠ **비식별 영상 파일명은 고정이 아니다** — 우리가 지정하는 것은 **디렉터리(`export_path`)까지**이고 파일명은 외부 비식별 솔루션이 정한다. mock 은 `deidentified.mp4`(우리가 직접 씀), **KPST 실연동은 `{원본stem}-mask{ext}`**(예: `001.mp4` → `001-mask.mp4`)로 **영상마다 다르다**. 따라서 **파일명을 조합·추측하지 말고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽는다**(위 데이터마트 View 절의 "문자열 치환 도출 아님"과 동일 규칙). 관제도 파일명을 고정으로 기대할 수 없으므로 **뷰에 이 경로를 노출하는 것이 관제가 비식별 영상을 찾는 유일한 수단**이다.
  - ★**`EvntAnnoService`(event_annotation 수정)도 재생성 대상이다 — 예외 없음 (2026-08-13 반영 · 구 "예외·미확정" 서술 폐기)**. 그 값은 산출 JSON 최상위에 pass-through 되고 `LabelContentHasher` 해시 축에도 들어가므로, 바뀌면 산출물이 바뀌는 것이 맞다. 구 서술이 든 근거(*"재동결(`materialize`)을 하지 않아 재export 만 붙이면 승인 시점 동결본(`EVNT_ANNO_CN`)이 그대로 나간다"*)는 **재승인 경로에서 성립하지 않는다** — `ReviewService.approve` 가 **같은 승인 트랜잭션에서 `materialize` 를 먼저** 돌려 동결본을 갱신하고, 재생성은 그 **이후 디바운스 flush tick** 에 일어나 export 가 이미 새 동결본을 읽는다. 따라서 별도 재동결 배선 없이 `exportRegenerated=true` 만으로 성립한다(옛 동결본 문제는 **디바운서 단독 재생성 경로**에만 있었다). **되돌리지 말 것.**
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
- **데이터 소스**: 관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임).
  - ⚠ **구 서술 폐기(2026-08-16 코드 실측)** — *"저작도구는 포털 DB에서 Load"* 는 **사실과 다르다.** 포털 라벨 경로(`PortalLabelService`)는 `controlTransactionManager` 로 묶이고 그것이 쓰는 리포지토리는 **전부 control(저작도구) 데이터소스**다. `@PortalRepo` 를 쓰는 것은 **메타 복제 축 하나뿐**이고(`PortalDatasetVideoMetaRepository`·`PortalMetaReplicaWriter`·`MetaReplicationWorker`) 그 방향은 **저작도구 → 포털 DB 쓰기**(단방향 at-least-once)다. 즉 **포털 DB 는 우리가 읽는 곳이 아니라 내보내는 곳**이다. 그 서술대로 이해하면 포털 화면의 조회 경로를 엉뚱한 데이터소스에서 찾게 된다. ⚠ 같은 오기가 **LogiCraft ITEM 여럿과 승인된 통합시험 시나리오에도 복제**돼 있다(별도 정합 대상)
- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면에 표시
- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 별도 적재, **데이터마트에 정합/반영 안 됨(단방향)**
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음
- **오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공** (ADR-013 — 포털은 데이터마트 영상 선택 + 본인 자산 업로드 전용)
- **포털 자산 업로드 (ADR-013 예외, 2026-07-17)**: 포털 사용자가 **본인 이미지(jpg/jpeg/png, 20MB/장, 50장/요청)·영상(mp4/mov/avi, 5GB, TUS 재개 업로드)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON만) 후 본인 데이터(JSON export/원본)를 다운로드한다. 업로드 자산은 신규 `LS_PORTAL_*` 테이블(V107/V108: `LS_PORTAL_ULD`·`LS_PORTAL_ULD_FRME`·`LS_PORTAL_ULD_LBL`·`LS_PORTAL_TUS_ULD`)로 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View와 **완전 분리**된다. 오토라벨링·SAM2·VLM·검수·버전관리는 미적용. 영상은 비식별 미적용(본인 데이터)이며 고정 간격 프레임 추출(`LS_SYSTEM_CONFIG` `portal.upload.frame-interval-sec` 기본 5초, 상한 maxFrames 2000). 상태: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED`. 신규 화면: 포털 업로드(`/portal/uploads`)·포털 업로드 라벨링(`/portal/uploads/:uldSn/label`). 신규 API: `/v1/portal/uploads/**`
- 반응형 웹 (PC/태블릿/모바일), WCAG 2.1 AA 준수

### DB 정책
- `klid_at` 스키마(PostgreSQL)에 저작도구 전용 테이블(LS_*) 운영 — 저작도구가 직접 소유·구성
- **★스키마 `klid_at` 은 이제 설정으로 실제 배선돼 있다 (2026-08-13 — 전 환경, 구속)**: 그 전까지 이 서술은 **설계 문서에만 있고 코드에는 없었다**(어디에도 스키마 지정이 없어 PostgreSQL 기본값 `public` 으로 떨어져 있었다 — 주석·javadoc 에만 존재하던 드리프트).
  - **배선 지점 4곳 + 값 1개**: 값은 `${DB_SCHEMA:klid_at}` 하나이고 네 지점이 **모두 그 값을 읽는다**(갈리면 JPA·네이티브 쿼리·Quartz 가 서로 다른 스키마를 본다). ① 커넥션 `spring.datasource.control.data-source-properties.currentSchema`(pgjdbc 접속 시작 파라미터 → search_path) ② Flyway `schemas`/`default-schema`/`create-schemas` ③ Quartz `org.quartz.jobStore.tablePrefix` ④ control EMF 의 `hibernate.default_schema`(`ControlDataSourceConfig`).
  - **★네이티브 쿼리 축이 핵심이다** — `hibernate.default_schema` 는 **JPA 매핑 SQL 만** 한정하고 `@Query(nativeQuery=true)` 의 비한정 테이블명·Quartz JobStore·Flyway 는 전부 **커넥션의 search_path** 를 따른다. ①이 빠지면 **테스트는 통과하는데 런타임에서 네이티브 쿼리만 깨진다**. JDBC URL 에 `?currentSchema=` 로 붙이지 않는 이유는 프로파일 yml 4곳 복제 + **테스트가 URL 을 Testcontainers 값으로 통째로 덮어써 그 파라미터가 사라지기** 때문이다(= 검증되지 않는 배선).
  - **portal 데이터소스는 대상이 아니다** — 별개 물리 DB 이고 복제본 스키마는 설치 단계(`17-load-portal-schema.sh`)가 `public` 에 로드한다. 테스트에서만 control 과 같은 DB 를 가리키므로 `src/test/resources/application-local.yml` 에서 테스트 한정으로 맞춘다.
  - **★마이그레이션 SQL 에 `public.` 리터럴을 박지 말 것** — 신규 DB 재적용이 조용히 어긋난다. 실측: `V62`/`V71` 의 stub 교정 가드가 `table_schema='public'` 이라 klid_at 에서는 **영원히 거짓**이 되어 교정이 건너뛰어지고 `V167` 이 `column m.file_fmt does not exist` 로 실패했고, `V63` 이 stub 을 `public` 에만 만들어 `V164` 가 `relation mng_clip_evnt_lst does not exist` 로 실패했다. 세 파일을 `current_schema()` + 비한정 식별자로 교정했다(대상 스키마와 조작 대상이 반드시 같아야 한다). **`search_path` 에 `public` 을 폴백으로 끼워 넣는 방식은 해결이 아니다** — 그러면 `V164` 는 통과해도 `V167` 이 klid_at 의 구 shape stub 을 집어 그대로 실패한다(두 순서 모두 실측).
  - ⚠ **기존 DB 는 배포 전에 스키마를 옮겨야 한다** — 옮기지 않고 배포하면 Flyway 가 klid_at 을 빈 스키마로 보고 V1 부터 전량 재적용해 **데이터는 `public` 에 남고 앱은 빈 klid_at 을 본다**(조용한 분기 — 오류가 아니다).
  - ⚠ **위 세 파일 교정으로 Flyway 체크섬이 바뀐다** — 이미 적용된 DB 는 기동이 **거부**된다(조용한 손상이 아니라 즉시 실패). 스키마 이관 런북에 체크섬 재정렬 1회를 포함할 것(정확한 값은 그 변경 커밋 메시지에 있다).
    - **★2026-08-13 스쿼시 이후 이 체크섬 재정렬은 무의미해졌다** — 기존 DB 는 이력 180행을 **통째로 베이스라인 1행으로 교체**하므로(런북 §2-5-2) 개별 행의 체크섬을 맞출 대상이 없다. 런북 §2-5-1 ③ 은 스쿼시 이전 배포본으로 이관하는 경우에만 해당한다.
    - 이 사고가 남긴 **규칙 자체는 그대로 유효**하며, 앞으로 조건부 마이그레이션을 쓸 때 참조하도록 `V1__baseline.sql` 헤더 「규칙 1·2」로 옮겨 적었다(스코프 없는 카탈로그 조회 금지 — `conrelid = to_regclass(...)` / `schemaname = current_schema()`). 원문 세 파일은 아카이브에 있다.
  - ⚠ **관제 계약면이 움직인다** — 데이터마트 뷰 4종(`V_COMPLETED_*`)이 `public` → `klid_at` 으로 옮겨간다. 그중 **규격상 관제가 SELECT 하는 것은 `V_COMPLETED_VIDEO` 하나**이며(아래 「데이터마트 적재용 View」 절), 그 하나가 스키마를 옮기는 것만으로도 **관제팀 협의 대상**이다.
- 관제서버 MNG_* 테이블 재사용 (READ 위주, JPA `ddl-auto=validate`)
- ⚠ **★`DE_IDENT_YN` 과 `DE_IDNTF_YN` 은 둘 다 맞다 — 전역 치환 금지 (2026-08-15 실측 확정)**: **테이블 컬럼은 `LS_DATA_RAW.DE_IDENT_YN`**(`@Column(name = "DE_IDENT_YN")`)이고, **관제 계약면인 뷰 출력명은 `DE_IDNTF_YN`** 이다 — `V_COMPLETED_VIDEO` 가 `m.de_ident_yn AS de_idntf_yn` 으로 **별칭을 단다**. 한쪽으로 통일하면 **엔티티 매핑이 깨지거나 관제 계약면이 바뀐다.** 같은 파일의 `DE_IDNTF_SRC_FILE_PATH_NM`·`DE_IDNTF_FILE_PATH_NM`·`DE_IDNTF_PJT_ID`·`DE_IDNTF_DATST_ID` 는 **애초에 별개 컬럼**이라 무관하다. (동명이표 주의 사례는 `NEXT_RTRY_DT` 와 같은 계열 — 위 「표준용어·표준도메인 준수」 절 참조)
- **관제 공유 클립 테이블 진실원·산출물 비대상**: UC-018 관제 학습용 적재가 READ하는 `MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST` 실제 스키마(복합 PK, `FILE_PATH` 등 — DB 직접 조회 확정)는 LogiCraft **ERD-024**(관제 공유 클립 ERD)에 진실원으로 기록한다. 단 `MNG_*`는 공유(READ) 스키마라 **D8/D9 산출물 비대상**(cc-doc-gen `MNG_*` prefix 규칙으로 자동 제외 — "공유(READ)" 비고만). 적재 어댑터 매핑(`CLIP_ID→VMS_CLIP_ID`, `FILE_PATH→RAW_FILE_PATH_NM`, `VDO_LEN_SEC` ms→초, `EVNT_LST.EVNT_TYPE_CD/SHT_DT` 조인)은 ERD-024 description에 명세.
- Flyway 마이그레이션: LS_* 전용 테이블은 자체 관리, **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수**
- 모든 마이그레이션 SQL은 PostgreSQL 표준 문법으로 작성 (MariaDB 고유 문법 금지)
- **★Flyway 스쿼시 완료 — 신규 마이그레이션은 `V7` 부터다 (2026-08-13, 구속 · 스쿼시 직후엔 `V3` 였고 그 뒤 V3~V6 이 쌓였다)**: 누적 180개(V0~V185)를 **`V1__baseline.sql`(스키마 전량 + 시드 19행)** 하나로 접었고 `V2` 는 `CM_CODE`→**`LS_COM_CD`** 개명이다(소유 접두 `LS_` + **표준용어 약어 교정** — 구 이름의 `CM`·`CODE` 는 공통·사업 표준단어 **어디에도 없고** 정본 CSV 대조상 공통=`COM`·코드=`CD` 다. 접두만 붙이면 비표준 물리명이 그대로 남아 한 번에 바로잡았다). 접은 시점 기준: 테이블 76 · 뷰 4 · 시퀀스 49 · `COMMENT ON` 187 · 시드 19행(`ls_system_config` 12 / `ls_com_cd` 5 / `qrtz_locks` 2 — **그 밖의 테이블에는 시드가 없다**).
  - ⚠ **위 수치는 「접은 시점」의 역사적 사실이며 지금 V1 을 적용한 결과가 아니다** — 그 뒤 V3·V4 가 죽은 테이블 7종을 지우면서 **V1 에서 정의·시드를 함께 덜어냈고**(기존 DB 는 그 행이 `BASELINE`·checksum NULL 이라 영향 없음), V6 이 `LS_DATA_LBL_AI_INFO` 를 흡수했다. **현재 형상 = 저작도구 소유 `LS_*` 57개 + Quartz 11 + 뷰 4, V1 시드 14행**(`ls_com_cd` 5행은 그 테이블과 함께 소멸). 확인: `grep -c "^CREATE TABLE klid_at.ls_" deploy/onprem/db/schema.sql` → 57.
  - **왜 지금 접었나**: stg/prd/온프렘 **미배포**라 이력 수술 대상이 로컬·dev 둘뿐이었고, 온프렘은 Flyway 를 쓰지 않는다(`schema.sql` 로드). 누적 `CREATE TABLE` 102종 중 **25종이 나중에 DROP** 되는 순수 잔재라 신규 설치가 매번 만들었다 지우는 왕복을 하고 있었다.
  - **동일성은 기계로 증명했다** — 베이스라인은 손으로 쓴 것이 아니라 **180개를 클린 DB 에 전량 적용한 뒤 뜬 `pg_dump`** 다. 컬럼·제약·인덱스·뷰정의·시퀀스·COMMENT·행수 7축 + 덤프 블록 577개가 전부 일치하며, 유일한 차이는 개명분이다.
  - **★옛 180개 파일은 지우지 않았다** — `backend/src/test/resources/db-archive/migration/` 에 원문 보존한다(Flyway `locations=classpath:db/migration` 이 안 읽는 경로). **21개 테스트 클래스가 이 파일들을 직접 읽어 백필·DROP 순서·롤백 절차 주석을 검증**하고 있어 삭제하면 그 회귀 커버리지가 통째로 사라진다. 아카이브 디렉터리 basename 을 `migration` 으로 유지하는 것도 의도다 — 아키텍처 제거 가드(`*TableRemovalTest`)의 allowlist 필터가 부모 디렉터리명으로 판정한다.
  - **기존 DB(로컬·dev)는 앱 기동 전에 이력 180행 → 베이스라인 1행 이관이 필요**하다. 절차·검증 쿼리는 `deploy/onprem/docs/09-operations-runbook.md` §2-5-2. **`baseline-version: 0` 은 그대로 두되 근거가 바뀌었다**(구 근거 "V0 는 placeholder" 는 V0 소멸로 무효 — 지금은 "non-empty·무이력 DB 에서 V1 이 스킵되면 스키마가 통째로 안 생긴다"가 근거다).
  - ⚠ **`V1`·`V2` 는 내용을 수정하지 않는다**(체크섬 불일치 = 전 노드 기동 실패). 스키마 변경은 새 버전 파일로만 한다.
  - ⚠ **마이그레이션 파일에 `${...}` 를 쓰지 말 것 — 주석 안이라도 파싱이 실패한다**(Flyway placeholder). 실측: 헤더 주석의 `${DB_SCHEMA}` 하나로 `No value provided for placeholder` 가 나 베이스라인 전체가 적용되지 않았다.
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
- `klid_at` 스키마에 **View 4종이 실재**한다 — 검수 완료(`LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`) 영상만 노출
  - **★관제 연동 규격이 규정하는 조회 채널은 `V_COMPLETED_VIDEO` 하나다 (2026-08-19 정합)**: 정본 `docs/관제-저작도구-데이터연동-규격서-20260805.md` §1 이 전달 수단을 **「①인입 ②통지 ③조회」 3개 채널로 전수 열거**하고 그 ③이 이 뷰 하나이며, §2 흐름도·§5 뷰 명세도 동일하다. **나머지 3종은 규격서에 언급 0건**이다.
  - ⚠ **그렇다고 3종을 지우거나 「미사용」으로 단정하지 말 것 — 미합의 상태이지 폐기가 아니다.** 코드·위키 여러 곳이 이 뷰들을 관제 노출면으로 **전제**하고 그 위에 판단을 세워 뒀다: 비식별 신고 절이 `V_COMPLETED_FRAME.DEIDENTIFIED_PATH` **노출**을 근거로 프레임 복구를 막고 · R12 가 `vlm.accuracy` 의 `V_COMPLETED_META` **미도달**을 의도된 설계로 못박고 · 라벨 이력 추가가 `V_COMPLETED_LABEL_CHANGE` 에 미치는 영향 때문에 별건으로 미뤄져 있다. 뷰를 없애면 이 논거들이 함께 무너진다. **좁히려면 관제 계약 협의가 선행**돼야 한다(관제 pull API `TaskQueryService.getMeta` 를 임의로 좁히지 않는 것과 같은 축).
  - `V_COMPLETED_VIDEO` : **V174 재작성 — 30컬럼**(정본 `docs/관제-저작도구-데이터연동-규격서-20260805.md` §5-1). 관제가 `datasets`·`dataset_versions` 를 SELECT 1회로 채우고 산출물을 픽업하는 계약면이다. 영상 메타 + 원본 영상 경로(`ORGNL_VDO_PATH_NM`) + 검수 완료 일시(`RVW_CMPTN_DT`) + **산출 폴더 경로(`OUTPUT_PATH_NM`)·프레임수(`FRME_CNT`)·산출 용량(`DATA_ETBL_CPCT`)** — 최신 SUCCEEDED/PARTIAL export(`LS_DATASET_EXPORT`) 조인. LATERAL 4개(산출 원장·비식별 이력·인입·라벨 집계)가 모두 최대 1행이라 **영상 1건=1row** 불변.
    - ⚠ **미export 영상의 두 값은 대칭이 아니다** — `FRME_CNT` 는 **0**(규격서가 NOT NULL 로 공표, 관제 `datasets.img_nocs` 공급)이고 `OUTPUT_PATH_NM`·`DATA_ETBL_CPCT` 는 **NULL** 이다. 구 서술 "미export 영상은 두 값 null" 은 **폐기**(V174).
    - ⚠ **출력명 6건이 V174 에서 표준 물리명으로 바뀌었다** — `DURATION_SEC`→`VDO_LEN_SEC` · `FRAME_CNT`→`FRME_CNT` · `REVIEW_COMPLETED_AT`→`RVW_CMPTN_DT` · `ORIGINAL_VIDEO_PATH`→`ORGNL_VDO_PATH_NM` · `EXPORT_PATH_NM`→`OUTPUT_PATH_NM` · `EXPORT_STTS_CD`→`OUTPUT_STTS_CD`. **V173 의 원장 rename 은 뷰 출력명을 바꾸지 않았다**(PostgreSQL 은 `RENAME COLUMN` 시 뷰 *본문*만 추종하고 출력명은 자동 별칭으로 보존한다) — 관제 계약면을 실제로 바꾼 것은 **V174** 다.
    - ⚠ 인입(`LS_DATA_INGEST`) 유래 컬럼(`EVNT_CLSF_CD`·`EVNT_CTGRY_CD`·`LCLGV_NM`·`SRC_*_INCL_YN`)은 **동결 스냅샷에 넣지 않고 LATERAL 조인**한다(설계 D1) — 인입은 관제 수신 원장이라 불변이므로 동결과 라이브 조인의 결과가 같다. 조인 규칙은 앱의 단일 진실원 `IngestSourceLink` 와 동일. **파생영상은 대응 인입 행이 없어 `SRC_*_INCL_YN` 이 NULL** 이다.
    - ⚠ `GEN_AI_YN` 은 동결 `AI_CRT_YN` 을 읽지 **않는다**(설계 D2) — `LS_DATA_RAW.SRC_TYPE IN ('GENERATED','AUGMENTED')` 로 직접 도출한다.
  - `V_COMPLETED_FRAME` : 프레임 페어 (`ORIGINAL_PATH`=원본 `SRC_FILE_PATH_NM`, `DEIDENTIFIED_PATH`=비식별 `DE_IDNTF_SRC_FILE_PATH_NM`) — 신규 추출은 `{base}/frames/raw|deid/{rawSn}` 로 분기 저장돼 두 경로가 항상 상이(원본 덮어쓰기 0)
  - `V_COMPLETED_LABEL_CHANGE`(V114 신설) : 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, `CHG_KIND_CD`=ADDED/UPDATED/DELETED, APPROVED 게이트) — 라벨 좌표·속성 본문은 검수 승인 export 폴더 JSON에 존재하므로 뷰로 중복 노출하지 않음(구 `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 제거)
  - `V_COMPLETED_META` : 시계열 메타 (`RVW_STTS_CD='APPROVED'` 만)
- **라벨 내용 뷰 제거(V114) = 관제 연동 계약 변경(관제팀 협의 대상)**: 관제가 산출 폴더 경로(`V_COMPLETED_VIDEO.OUTPUT_PATH_NM` — V174 개명, 구 `EXPORT_PATH_NM`)를 픽업하는 축은 유효하다. 라벨 본문 뷰를 SELECT 하던 관제 쿼리는 파손되므로 협의 필요(M2M deprecated·뷰 SELECT 방식)
  - ⚠ **구 서술 일부 폐기(2026-08-19)** — *"②변경점(`V_COMPLETED_LABEL_CHANGE`)·메타(`V_COMPLETED_META`)만 DB 뷰로 쿼리한다"* 는 규격서(2026-08-05)와 어긋난다. 규격서의 조회 채널에 그 두 뷰는 **없다**. 이 서술을 근거로 "관제가 그 뷰를 쿼리한다"고 전제하지 말 것
- 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 통지 수신 후 `RAW_SN` 으로 **`V_COMPLETED_VIDEO` 를 SELECT** → 영상 1건=1 row UPSERT (규격서 §2 흐름도·§5)
  - ⚠ **구 서술 「4 View 단순 SELECT」 폐기(2026-08-19)** — 이 파일 안에서 같은 사실이 **4뷰·3뷰·1뷰 세 갈래**로 갈려 있었다. 정본은 규격서이며 조회 채널은 1뷰다
- **★ 검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다 (2026-07-28 사용자 확정 — 구속)**: 검수 승인(`APPROVED`)되어 관제에 통지된 영상은 **어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다.** 특히 **비식별 누락 신고(`DE_IDENT_YN='F'`) 구간에도 관제 접근을 차단하지 않는다** — `V_COMPLETED_VIDEO`·`V_COMPLETED_FRAME` 에 신고 필터를 넣는 안, 신고 중 `OUTPUT_PATH_NM`(V174 개명, 구 `EXPORT_PATH_NM`)/`DEIDENTIFIED_PATH` 를 NULL 로 비우는 안은 **모두 폐기**한다(관제가 보던 행이 예고 없이 사라져 관제 측 배치가 삭제·오류로 오인).
  - **함의(의도된 설계, 결함 아님)**: 비식별 신고 게이트(`DeidentReportGate`)는 **저작도구 앱 내부 통로에만** 적용되고 **관제 경계(뷰·통지)에는 적용되지 않는다.** 따라서 신고 구간에도 관제는 마스킹 실패 픽셀이 남은 export 폴더·비식별 프레임 경로에 접근한다. 이를 "잔여 누수"로 재분류해 다시 고치려 들지 말 것.
  - **닫혀 있는 흐름** ⚠ **2026-08-10 이후 승인 영상에서는 이 흐름이 시작되지 않는다** — 승인 영상은 신고 접수 자체가 412 로 막히므로(위 「비식별 누락 신고」 절) *"신고 접수 시 `TASK_MODIFIED`(META_UPDATED) 발행 → 관제가 재픽업"* 은 **미승인 영상에만** 성립한다. 이어지는 *"신고 해제(RESOLVED) 시 APPROVED 영상 export 재산출 + 재통지 → 관제가 정상 산출물로 갱신"* 은 **그 변경 이전에 접수돼 아직 열려 있는 신고**에만 해당한다. 신규로는 이 경로가 생기지 않는다. 다만 `V_COMPLETED_VIDEO` 가 `DE_IDNTF_YN` 을 컬럼으로 내보내므로 관제가 원하면 자체 판단은 여전히 가능하다(우리가 강제하지 않을 뿐).
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

#### ★연동 서버 주소는 설정으로 바꾼다 — 대역 차단은 하지 않는다 (2026-08-10 사용자 확정, 구속)

**대상 4종**: 비식별(**`kpst.deid.base-url`**) · AI 추론(`authoring.integration.ai-server.base-url`) · 외부 시계열 분석 벤더(`vlm.client.url`) · 관제 통지(`authoring.control-notify.url`). **DB 접속정보는 대상이 아니다.**

- **진실원·반영**: 설정에 값이 있으면 설정, 없으면 배포 기본값. **재기동 없이 즉시 다음 호출부터 반영**된다(호출 시점 URL 재작성). 설정 키는 **애플리케이션 속성명 그대로** 쓴다 — 별도 키명을 만들면 그 매핑표가 두 번째 진실원이 된다.
- ⚠ **비식별 키는 `kpst.deid.base-url` 이다.** 구 후보였던 `authoring.integration.deidentify.base-url` 이 구동하는 `deidentifyWebClient` 는 **주입처가 0건인 죽은 빈**이라 그 키로는 아무것도 바뀌지 않는다(실측 확인 · `DeidentifyHealthIndicator` 주석이 2026-07-28 에 같은 사실로 판정축을 옮긴 기록 있음). **되돌리지 말 것.**
- **★주소 대역 차단을 하지 않는다** — 사설 대역·링크로컬·루프백 어느 것도 애플리케이션이 막지 않는다. 남는 검증은 **스킴 `http`/`https` + URL 형식**뿐이고 위반은 400(거부 사유에 해석 결과 미노출).
  - **근거**: ①연동 4종은 **내부망의 별도 GPU 서버**에 있을 가능성이 높아 대역 차단이 **정당한 대상을 막는다**(실제로 `ai-server` 기본값이 `localhost:9300` 이라 구 정책에서 400 이었다) ②시스템 전체가 **온프렘 내부망 배포**이고 외부에 노출되지 않는다(노출되는 것은 포털향뿐이며 **설정 화면은 포털 채널에 비노출**) ③아웃바운드·인바운드 통제는 **인프라 계층이 담당**한다.
  - ⚠ **구 정책(2026-08-10 오전) 폐기**: *"도메인을 IP 로 해석한 뒤 내부망 대역(127.0.0.1·10.x·172.16~31.x·192.168.x·169.254.169.254) 차단 + 요청 직전 재검증(DNS rebinding)"*. **되살리지 말 것** — 되살리면 정상 연동이 전부 막힌다.
  - ⚠ **잔여 위험(인지·수용)**: 관리자 패스워드를 얻은 자가 주소를 바꿔 영상·라벨을 다른 서버로 내보낼 수 있다. 이는 **대역 차단으로는 애초에 막히지 않던 위험**(공인망 주소로 바꾸면 그만)이라 이번 반전으로 새로 생긴 것이 아니다. 클라우드 메타데이터 탈취 경로는 **온프렘이라 성립하지 않는다**.
- **관리자 인증 = 단기 유효창**: 기존 관리자 공유 패스워드(`ADMIN_CLAIM_PASSWORD_HASH`) 재사용 · 무상태 서명 토큰(2노드 공유 저장소 불필요) · **기본 10분, 상한 30분 강제** · 유효성 판정은 **서버 소유** · 토큰은 **주소 설정 변경 권한 한정**이며 역할을 승격시키지 않는다.
  - ⚠ `RoleClaimService.claim` 을 그대로 부르면 안 된다(역할 보유자면 409) — **해시·rate limiter 는 공유하되 진입점은 별도**.
- **감사**: 새 테이블을 만들지 않는다. `LS_SYSTEM_CONFIG.MDFR_ID`/`MDFCN_DT` + INFO 로그로 충당한다(**패스워드·토큰은 절대 기록 금지**).

- 비식별·ai-server·VLM — **Resilience4j로 타임아웃/재시도/서킷 브레이커 적용 필수**
- 비식별 API 실패 시 영상 상태 `DE_IDENT_YN='F'` 로 마킹. 원본 절대 삭제 금지. ⚠ **자동 재비식별 큐는 없다 (2026-08-15 정정 — 구 서술 "+ 재시도 큐" 폐기)**: 회수는 **외부 비식별 프로그램에서 수동 재비식별 후 resolve 경로**이며, 이는 아래 「비식별 누락 신고」 절의 확정 정책과 같은 축이다. ⚠ `batch/retry/BatchRetryQueue` 는 실재하지만 그건 **배치 일반 재시도**이지 재비식별 전용 큐가 아니다 — 클래스 실재를 근거로 이 문장을 되돌리지 말 것
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
  - **`*Source`(MANUAL/DERIVED)·프리필은 존치한다 (2026-08-04 검토 확정)** — "적재 기본값 vs 사람이 고른 값"을 읽는 소비자는 0 이지만, **레거시 행(이 변경 이전 적재)에는 여전히 `NULL` 이 있고** 그 행에서 `DERIVED` 가 **"아직 값이 없다"**는 신호로 유효하다. 응답 필드 삭제는 외부 FE 계약 파괴이기도 하다. 따라서 **① `*Source` 응답 필드 ② GET 의 `NULL`→상수 프리필 ③ FE 가 미터치·`DERIVED` 필드를 `null` 로 전송하는 규약** 셋 다 유지한다. ⚠ **구 존치 근거 "비식별 신고 리셋이 항상 명시적 `NULL` 을 쓰므로(`DeidentReportService`) 리셋 직후 `DERIVED` 가 '아직 재판정하지 않았다'는 신호로 살아난다" → 폐기(2026-08-04)**: 리셋 자체가 폐기돼 그 경로가 없다. **존치 결론은 불변** — 근거만 레거시 행 + FE 계약으로 교체됐다(근거가 무너졌다고 필드·프리필을 제거하지 말 것). "누가 실제로 손댔나"는 `LS_TASK_EVNT_LOG` 감사가 컬럼과 **독립적으로** 담당한다(INSERT 자동 채움은 감사를 호출하지 않고 사람의 PUT 만 호출).
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
  - **★영상 축 PUT 은 ①동시 승인과 직렬화되고 ②신고 구간에 412 로 차단된다** (2026-08-03 DEV_FIX). ① 구 주석의 "경합 창 자체가 없다"는 **오류**였다 — 동결은 이 컬럼을 안 읽지만 **실소비자인 export 가 라이브 raw 를 직독**하므로 창은 동결→산출로 옮겨간 것이었다(PUT 이 PENDING 관측 → 통지 미발행 확정 → 승인 커밋 → async export 가 구 스냅샷 null 로 `v1` 산출 → 재산출 트리거 없음 = 비가역 과소 신고). 지금은 `flush`(raw 행락) → **`acquireRawLock`(rawSn advisory, `materialize` 와 동일 락)** 순서로 잠그고 상태는 **잠금 없이** 읽는다. ⚠ **`LS_RAW_DATA_STATUS` 를 `FOR SHARE` 로 잠그면 안 된다 — 교착(40P01)** (2026-08-03 2차 정정): 그러면 이 트랜잭션이 `raw → status` 순서가 되는데 `BatchTransitionService`(`markRawDataProcessingBlocked`/`Completed`/`Failed`)가 같은 `REQUIRES_NEW` 안에서 **`status`(조건부 벌크 UPDATE) → `raw`(dirty checking)** 순서로 잠근다. "승인 경로는 raw 를 안 잠근다"는 1차 근거는 참이지만 **교착 상대가 승인이 아니라 배치**였다(배치 진입점은 주기 배치·수동 재처리 등 모든 배치 시작에서 돈다). advisory 를 쓰면 간선이 `raw → advisory` 하나뿐이라 기존 불변식(`EnvironmentMetaService`·`materialize`)에 합류한다. 정적 회귀 가드 = `LockOrderGuardTest`. ② 신고 구간은 **"비식별이 잘못됐다"고 알려진 구간**이라, 그 잘못된 비식별본 위에서 내린 개인정보 판정을 이 구간에 새로 쓰면 resolve 후 그 값이 그대로 관제로 나간다(같은 구간에 라벨 조회를 412 로 막는 것과 같은 축). **게이트는 영상 축·프레임 축 PUT 양쪽에 건다**(단건 + 벌크 — 한쪽만 걸면 비대칭을 없앤 게 아니라 옮긴 것이다). 촬영환경(`EnvironmentMetaService`)은 **PII 축이 아니라** 위 근거가 성립하지 않으므로 **제외**한다. ⚠ **구 근거 "신고가 이 3필드를 재판정 대상으로 리셋하는데 같은 구간에 PUT 으로 되돌릴 수 있으면…(신고는 두 축을 함께 리셋한다)" → 폐기(2026-08-04, 리셋 폐기)**. **게이트 자체는 리셋 여부와 무관하게 성립하므로 유지한다 — 근거가 바뀌었다고 412 를 걷어내지 말 것.** **GET 은 차단하지 않는다** — 값이 PII 가 아니고 막으면 화면이 안 뜬다. ③ **영상 축 변경은 `LS_TASK_EVNT_LOG` 에 행 단위 감사**(`PRIVACY_META_UPDATE`, actor+사유. 판단값 Y/N 은 남기지 않는다 — CWE-359). ⚠ `PRIVACY_META_RESET` 타입·팩토리는 **신규 발생이 없지만**(신고 리셋 폐기, 2026-08-04) **이미 적재된 과거 행 판독을 위해 존치**한다 — 삭제 금지. 라벨 이력 `LS_DATA_LBL_HSTRY` 는 `SRC_SN NOT NULL` 인 프레임 스코프라 영상 축을 담지 못할 뿐, "행 단위 감사가 불가능"한 것은 아니었다.
  - **GET 응답은 수동값 우선 + 기본상수 프리필 + `*Source`(MANUAL/DERIVED) 병기** — 화면이 상수를 하드코딩하지 않게 BE 가 내려준다(상수 원천은 `ExportPrivacyPolicy.DEID_DEFAULT_*`). ⚠ FE 는 **사용자가 직접 고르지 않은 필드를 null 로 전송**해야 한다(DERIVED 프리필을 되돌려 보내면 상수가 사람의 판정으로 승격된다 — BE 는 출처를 알 수 없어 막지 못한다).
  - **영상 단위 값은 라이브 `LS_DATA_RAW` 에만 두고 동결 스냅샷 컬럼을 만들지 않는다** — 소비자가 export JSON 하나뿐이고(데이터마트 뷰에 없음) export 는 라이브 raw 를 이미 로드하기 때문. 대신 ①승인 후 수정 시 `TaskModifiedEvent(exportRegenerated=true)` 로 새 버전 전량 재생성 ②`LabelContentHasher` 입력 편입(멱등 skip 방지)이 **세트로** 필요하다(하나만 빠지면 저장은 됐는데 산출물이 안 바뀐다).
- **비식별 누락 신고** (R1 v1.14 수동 흐름): 작업자가 개인정보 노출을 발견하면 신고 → 작업락 + `DE_IDENT_YN='F'`. **★개인정보 3필드(익명/가명/PII 포함여부)도 리셋하지 않고 보존한다 (2026-08-04 사용자 확정 — 구 "리셋" 정책 폐기, 구속)**: 구 정책은 **프레임 축(`LS_DATA_SRC`, V130)과 영상 축(`LS_DATA_RAW`, V163) 3필드를 모두 `null` 로 리셋**했고 그 근거는 *"그 판정은 비식별이 잘못된 영상에서 내려진 것이므로 재판정 대상이며, 한 축만 리셋하면 export 의 video/image 가 근거 없이 비대칭이 된다"* 였다(행 단위 리셋 감사 `LS_DATA_LBL_HSTRY`·`LS_TASK_EVNT_LOG PRIVACY_META_RESET` 동반). **폐기 사유**: 바로 아래 라벨 보존 정책(2026-07-27)과 **같은 취지** — 신고는 "비식별이 잘못됐다"는 신호일 뿐 **사람이 입력한 판정도 라벨과 같은 작업 결과**이므로 폐기하지 않고, resolve 후 **기존 판정을 그대로 이어서** 진행한다. stale 우려는 신고 구간의 export 산출 보류 + 해제 시 재산출·재통지가 막는다. 리셋 감사 이벤트 타입·팩토리는 **과거 행 판독용으로 존치**(신규 발생 0). **★라벨은 삭제하지 않고 보존한다 (2026-07-27 사용자 확정 — 구 "해당 영상 전체 라벨 삭제 + `SAVE_REASON='DEIDENT_REPORT'` 비활성 스냅샷" 정책 폐기)**: 신고는 "비식별이 잘못됐다"는 신호일 뿐 라벨 작업 결과를 폐기할 근거가 아니며, 그 스냅샷은 `DATA_SRC_SN=NULL`(영상 스코프)이라 srcSn 스코프 조회·롤백 진입점이 없는 write-only 이력이었다(D-ISSUE-25). **스냅샷도 남기지 않는다.** 대신 신고 구간 동안 **라벨 조회를 차단**한다(`DE_IDENT_YN='F'` 인 영상의 `GET /v1/frames/{srcSn}/labels` → 412, 역할 무관 — 영상 스트리밍·마킹 게이트와 동일 정책. 라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족). **라벨 저장·수정도 같은 게이트가 412 로 차단한다** (2026-08-04, C-ISSUE-22 — 구 서술 *"저장·수정은 기존 작업락으로 409 차단"* 폐기: 작업락은 6h 만료 후 회수되는데 `'F'` 는 resolve 까지 남아 조회 412 ↔ 저장 200 비대칭이 열렸다. 아래 '차단 범위' ⑨ 참조). resolve 로 `'F'→'Y'` 가 복원되면 게이트가 자동 해제되어 **보존된 기존 라벨을 그대로 재사용**한다(별도 복원 API 없음). 자동 재비식별 큐는 폐기 — 작업자/검수자가 **외부 솔루션으로 수동 비식별화** 후 `POST /v1/deident-reports/{rprtSn}/resolve`(WORKER 본인 배정/REVIEWER 전체)로 OPEN→RESOLVED 전이 + 작업락 해제. **★검수가 승인된 영상은 신고를 접수하지 않는다 (2026-08-10 사용자 확정, 구속)** — `PRECONDITION_FAILED`(412), 역할 무관. 근거는 **"승인된 학습데이터는 되돌리지 않는다"** 이며, 판정은 **`ReviewApprovalGate.hasEverApproved`**(2026-08-12 확대 — 구 `isApproved` 판정 폐기)를 두 진입점이 수렴하는 `DeidentReportService.doReport` 한 곳에만 배선한다(진입점마다 복제 금지). ★**지금 상태가 아니라 이력으로 판정한다** — `ReviewStateMachine` 이 `APPROVED→PENDING`(작업자 재검수 재제출)을 허용하므로 현재 상태만 보면 **그 구간에 신고가 통과한다**(실증됨). 그러면 재비식별이 일어나 이미 산출된 회차의 이미지(옛 마스킹)와 뷰가 가리키는 영상(항상 최신 비식별본)이 어긋나고, **데이터마트를 그 회차로 되돌리면 걷어낸 개인정보가 되살아난다.** 판정 = `LS_DATASET_VIDEO_META` 행 존재 `OR` `LS_TASK_EVNT_LOG` 의 `EVENT_APPROVE` 존재(**fail-closed OR** — 전자는 V97 신설이라 그 이전 승인 + 백필 이전 재제출 영상은 행이 0건일 수 있어 감사 축이 뒤를 받친다). ⚠ 기각된 후보(다시 검토하지 말 것): `LS_LABEL_VERSION`(`commitApproved` 가 라벨 0건이면 조기 반환 → **fail-open**) · `LS_DATASET_EXPORT`(신고·실패 시 행 미생성, 비동기) · `V_COMPLETED_VIDEO.RVW_CMPTN_DT`(뷰가 라이브 `APPROVED` 로 게이트 — 배제하려는 그 판정) · `REVLT_YN`(다른 축) · 신규 컬럼(불필요). ⚠ **`isApproved` 는 폐기되지 않는다** — 해소 후 재산출 통지처럼 **현재 상태가 맞는 축**에서 계속 쓰인다. 두 축을 합치지 말 것. 게이트는 **인가 이후·작업락 409 검사 이전**에 평가한다(락보다 뒤면 응답이 잠금 상태 오라클이 된다 — CWE-209).
  - ⚠ **구 서술 *"APPROVED 영상 신고 시 `TASK_MODIFIED` 통지 발행"* 은 폐기** — 접수 자체가 막혀 **도달 불가**가 됐고 그 발행 분기는 코드에서 제거했다. 되살리지 말 것.
  - ⚠ **차단은 신규 접수에만 걸린다 — 이미 접수된 신고의 `resolve` 는 그대로 열어 둔다.** 막으면 그 영상이 작업락 + `'F'` 로 **영구 고착**된다("차단엔 되돌리는 길" 원칙). `resolveManually` 의 APPROVED 분기는 **이 변경 이전에 접수된 건**을 위해 존치한다.
  - 승인 경로(`ReviewService.approve`)에도 신고 중 차단(412)이 이미 있으므로 **"승인됨 + 신고 열림" 조합은 앞으로 생기지 않는다**(낙관적 락 경계의 매우 좁은 경합 창은 남으나, 그 결과 상태도 resolve 로 해소 가능하다).
  - ⚠⚠ **잔여 위험 — 승인 영상은 조치 수단이 0 이다 (인지·수용, 되돌리지 말 것)**: 신고 412 에 더해 **재비식별 요청도 이미 막혀 있다** — `ApprovedRedeidentService.requestRedeident` 가 `DE_IDENT_YN='Y'` 를 **409** 로 배제하고(그 가드의 사유는 개인정보가 아니라 `frm_no` 의미 불일치다) 화면의 재비식별 버튼도 `deIdntfYn !== 'Y'` 일 때만 뜬다. 정상 완주한 승인 영상은 전부 `'Y'` 이므로, 잔존 개인정보를 발견해도 **신고·재비식별 어느 쪽도 열리지 않고** 데이터마트 뷰·export 를 통한 관제 노출을 멈출 수단이 없다.
  - **★한번이라도 검수가 완료된 영상은 프레임을 새로 폐기·복원할 수 없다 (2026-08-12 사용자 확정, 구속)** — `INVALID_INPUT`(**400**), 역할 무관. 신고 차단(412)과 코드가 갈리는 것은 **의도된 비대칭**이다: 412 는 "지금은 안 되지만 해소되면 된다"는 일시 조건이고, 승인 이력은 **영구 조건**이라 재시도 여지가 없다(파생영상 차단이 400 인 것과 같은 축). 근거는 신고 차단과 동일한 **데이터마트 롤백 정합성** — 이미 산출되어 외부로 나간 회차에서 프레임이 빠지거나 되살아나면 그 회차의 산출물과 어긋난다. 판정은 신고와 같은 `ReviewApprovalGate.hasEverApproved` 이며, 게이트는 폐기 쓰기의 **단일 적용 지점**(`LabelService.requireDiscardAllowed` → `FrameDiscardApplier.apply`) 한 곳에만 둔다. 정적 가드가 `DSCD_YN` 쓰기 통로가 그 하나뿐임을 고정한다.
    - ★**회차 적용은 예외다** — 확정 저장이 **불러온 회차의 폐기 상태를 적용하는 것은 막지 않는다.** 그 회차의 폐기 상태는 이미 승인·통지된 것이라 새로 바꾸는 게 아니라 그 시점으로 되돌아가는 것이고, 막으면 라벨만 적용되고 폐기는 현재값으로 남아 **"한 영상 = 한 회차" 불변식이 깨진다.** ⚠ 판정은 **필드 존재가 아니라 값 비교**다(`edit.dscdYn()` 이 회차 값과 **다를 때만** 새 조작). 존재로 판정하면 화면이 폐기를 토글하지 않아도 회차 값을 그대로 실어 보내므로 **승인 이력 영상의 라벨 수정이 전량 400** 이 된다. 반대로 경로 단위 예외로 두면 회차를 한 번 불러오는 것만으로 **차단이 우회**된다. ⚠ **기준선은 2단계다** — 그 회차의 스냅샷 값이 기준선이고, **회차를 알 수 없는 프레임은 라이브 현재 값**이 기준선이다(`VideoLabelSaveTxService.discardBaselineOf`). 구 서술 *"스냅샷을 모르는 프레임은 보수적으로 새 조작으로 본다"* 는 **폐기** — 그 판정이 회차 불러오기→확정 저장 왕복을 400 으로 막던 결함의 원인이었다(생산자는 "그대로 저장하면 no-op" 을 보장하는데 소비자가 스냅샷 없는 프레임을 새 조작으로 본 것).
    - ⚠⚠ **잔여 위험 — 승인 후 잘못 폐기된 프레임은 되돌릴 수단이 0 이다 (2026-08-12 사용자 확정: 현상 수용, 되돌리지 말 것)**: 프레임 축·영상 축·REVIEWER 권한 어느 경로로도 복원이 400 이고, **폐기 이전 회차가 존재하지 않으면**(첫 승인에서 이미 폐기 상태로 동결) 되돌아갈 회차 자체가 없다. 그 프레임은 학습데이터 산출물에서 **영구 누락**된다. 위 재비식별 잔여 위험과 같은 성질이며 **발견은 승인 전에 이뤄지는 것이 전제**다. REVIEWER 정정 override 를 두는 안은 검토 후 **채택하지 않았다** — "승인된 학습데이터는 되돌리지 않는다"에 예외를 만들지 않는다. **이것을 결함으로 재분류해 복원 경로를 열려 하지 말 것.**
    - ⚠ 게이트 판정이 무잠금이라 **승인 커밋과 교차하는 좁은 창**이 남는다(폐기 tx 가 미승인으로 관측한 사이 승인이 커밋 → 승인 스냅샷은 폐기 이전을 동결했는데 라이브는 폐기됨 → 그 회차 산출물과 불일치). 창을 닫으려면 영상 단위 락이 필요해 비용이 맞지 않고, 위 잔여 위험 때문에 결과도 비가역이다 — **인지·수용**한다(신고 축의 낙관적 락 경합 창과 같은 계열).
    - **사용자 확정(2026-08-10)**: 보완책으로 "승인 영상에 재비식별을 열어 주는" 안을 검토했으나 **채택하지 않았다** — *"승인영상에 비식별화 버튼을 열면 너무 시나리오가 꼬이고 이슈가 많을 것 같다"*. 발견은 **승인 전에 이뤄지는 것이 전제**다.
    - ⚠ **이것을 결함으로 재분류해 다시 열려 하지 말 것.** 열려면 `'Y'` 배제 가드가 막고 있는 `frm_no` 의미 불일치부터 해결해야 하며, 그건 별도 설계다.
    - 대신 **거부된 신고의 사유는 로그로 남긴다**(신고 행도 REVIEWER 알림도 생기지 않는 경로라 로그가 유일한 기록이다 — 파생영상 거부와 동일한 관례).
  - ⚠ **외부 비식별 서버 프로젝트 삭제 시나리오는 채택하지 않았다** (2026-08-10 사용자 확정) — 외부 정리는 KPST 클라이언트에서 사람이 하며 **우리 코드에 삭제 호출을 만들지 않는다**(`KpstDeidentifyClient.deleteProject` 는 프로덕션 호출부 0건 상태를 유지). "삭제 시점을 승인으로 옮기자"는 구 안은 폐기.
  **마킹 단계**(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`) + **라벨링 단계**(srcSn 기준, `POST /v1/labels/{srcSn}/deident-report`) 양쪽 가능. 적재 테이블 `LS_DEIDENT_REPORT`.
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
  - **단계와 무관한 2종은 무변경**이다 — `DeidentGateReopenedEvent`(항상, VLM 위탁 재개) · `TaskModifiedEvent`(`META_UPDATED`+`exportRegenerated=true`+`needsRecheck=true`, APPROVED 한정, **재검토 표시만**). ⚠ **구 서술 폐기(2026-08-15 코드 실측)** — *"`DeidentReportResolvedEvent`(APPROVED 한정, export 재산출·관제 재통지)"* 는 **사실과 다르다.** 그 이벤트는 `src/main` 에 `new DeidentReportResolvedEvent(` 가 **0건**인 **휴면 확장점**이며(테스트가 리스너를 직접 부르는 1건뿐), 클래스와 `DatasetExportBridge` 수신 배선만 존치한다. 판정 단일 원천은 `DeidentReportService(publishResolvedForExportRecovery)`. 단계별 재개는 **신설 `DeidentStageResumeEvent`** 가 담당하며 소비자는 `AFTER_COMMIT` 리스너다(`'F'→'Y'` 복원 커밋 전에 돌면 재개 작업이 자기 게이트에 스스로 막힌다).
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
- **차단 범위(구현 실측)와 응답 코드**: ①라벨 조회 `GET /v1/frames/{srcSn}/labels`·라벨 이력 → **412** ②버전 diff·롤백(`VersionService`) → **412** ③프레임 이미지 `GET /v1/frames/{srcSn}/image`·`GET /v1/frames/{srcSn}/deid-image`·`GET /v1/videos/{rawSn}/frames/{frameNo}/image` → **412** ④포털 프레임 라벨·포털 프레임 이미지 → **412** ⑤관제 조회 API(라벨 본문 `TaskQueryController`) → **412** ⑥데이터셋 export(`DatasetExportService`/`DatasetExportTxService`/`DatasetExportFailureRecoverer`) → 산출 자체를 보류(skip, 통지도 보류) ⑦**영상 스트리밍 `GET /v1/videos/{rawSn}/stream`·`GET /v1/videos/{rawSn}/stream-url` → 404** ⑧**개인정보 메타 저장(PUT)** — 영상 축 `PUT /v1/videos/{rawSn}/privacy-meta` · 프레임 축 `PUT /v1/frames/{srcSn}/privacy-meta`·`PUT /v1/frames/privacy-meta`(벌크) → **412** (2026-08-03 — 이 게이트의 유일한 **쓰기** 호출자다. 근거는 "신고 구간에 그 잘못된 비식별본 위의 개인정보 판정을 새로 쓰면 resolve 후 관제로 나간다"이며 **영상 축·프레임 축 양쪽에 건다** — 한 축만 막으면 우회가 남는다. 촬영환경 PUT 은 PII 축이 아니라 제외). ⚠ **구 근거 "신고가 두 축을 함께 리셋하므로" → 폐기(2026-08-04, 리셋 폐기). 게이트 자체는 리셋 여부와 무관하게 성립하므로 ⑧은 그대로 유지한다.** ⑨**라벨 저장 `PUT /v1/frames/{srcSn}/labels`(`LabelService.bulkUpsert`) → 412** (2026-08-04, C-ISSUE-22 — 구 서술 *"저장·수정은 기존 작업락으로 409 차단"* 은 **폐기**: 신고 락은 6h 만료 후 `WorkLockSweepJob` 이 회수하는데 `'F'` 는 resolve 까지 남아, 그 창에서 **조회 412 ↔ 저장 200** 비대칭이 열렸고 full-replace 계약상 `items:[]` 저장이 기존 라벨을 전량 삭제했다. 게이트는 **락 검사보다 먼저** 평가해 락 유무와 무관하게 412 로 통일한다 — 409/412 로 갈리면 응답이 잠금 상태 오라클이 된다. 409 는 **신고와 무관한 락**(트랙 병합 등 일시적 충돌)에만 남는다). ⑩**라벨 객체 속성값 조회·저장** `GET`·`PUT /v1/labels/{lblSn}/attrs`(`LabelAttrValueService`) → **412** (2026-08-07 사용자 확정 — 라벨 본문은 ①⑨로 막히는데 그 객체의 속성값만 열려 있어 **축이 비대칭**이었다. `lblSn` → `LS_DATA_LBL.SRC_SN` → 영상으로 해석해 같은 판정기를 태운다). ⑪**이벤트 어노테이션 저장·승인·반려** `PUT /v1/videos/{rawSn}/event-annotation`·`POST …/approve`·`POST …/reject`(`EvntAnnoService`·`EvntAnnoReviewService`) → **412** (2026-08-07 사용자 확정 — 저장만 막으면 **승인·반려로 우회**된다. 신고 구간에서 내린 승인 판정은 해소 후 그대로 확정돼 외부로 나가고, 반려 판정은 재비식별 이후 내용에 적용되지 않는다. ⚠ **조회(`GET`)는 열어 둔다** — 이번에 조회를 막은 것은 ⑩ 하나뿐이다). ⑫**검수 승인** `POST /v1/reviews/{videoId}/approve`(`ReviewService.approve`) → **412** (2026-08-07 사용자 확정 — 승인은 라벨 전체 스냅샷을 버전으로 **동결**하는 지점이라 그 구간에 통과시키면 잘못된 비식별본 기준의 산출물이 확정된다. ⚠ **승인 이후의 export·통지 배선은 이 변경 대상이 아니다**). 스트리밍만 404 인 것은 "비식별 유효하지 않으면 원본 노출 금지 → 404" 라는 그 엔드포인트의 **기존 규약**에 맞춘 것으로, 신고 여부에 따라 404/412 로 갈리면 **응답 코드가 영상 상태를 알려주는 오라클**이 되기 때문이다(CWE-209). 모든 게이트는 **인가 검사 이후** 평가되는 프리컨디션이며 역할 무관(REVIEWER 포함)이다. 거부 메시지는 **행위 중립**("비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.")이다 — 구 문구는 "…라벨을 조회할 수 없습니다"라 쓰기 호출자에게 맞지 않았다. **★위 목록은 전부 '원본 보호 축'이며 그대로 유지된다. 반면 '파생 *생성* 축'(증강 콜백 인계·해상도 파생 예약/확정)은 차단 범위가 아니다 (2026-07-29)** — 파생 생성은 원본 신고와 무관하고(위 '파생영상은 비식별 신고 체계 바깥' 대칭), 신고가 막는 것은 **외부 위탁(요청·전송)** 뿐이다. 증강 고아 PENDING 만료 스윕 (`AugmentJobExpiryTxService.expireOrphanPending` / `LsDataAugRepository.findOrphanPendingAugSns`)의 신고 구간 제외 술어도 **제거**했다 — 보류 재개 리스너가 폐기돼 깨울 주체가 없으므로 제외하면 PENDING 영구 고착만 남는다.
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
- **해소(resolve) 시 자동 복구 — ★복구 범위 = 차단 범위와 동일하게 "그 영상 하나" (2026-07-29)**: `'F'→'Y'` 복원으로 위 게이트가 전부 자동 해제되고, 신고 구간에 보류됐던 산출·위탁이 재트리거된다. 게이트가 자기 행만 보므로 신고가 막던 노드도 그 영상 하나뿐이고, 복구 발행·스트림 메타 캐시 무효화 범위도 동일하게 그 영상 하나다(자손 팬아웃 없음). 발행 이벤트는 둘: ①`DeidentGateReopenedEvent` = **항상**(보류된 VLM 위탁 재개용 — VLM 보류는 대개 미승인 영상에서 일어나므로 승인 전용 이벤트로는 신호가 도달하지 않는다) ②`TaskModifiedEvent`(`META_UPDATED`+`exportRegenerated=true`+`needsRecheck=true`) = **APPROVED 일 때만** — **재검토 표시(`REVLT_YN='Y'`)를 세우고 변경분을 축적할 뿐 즉시 재산출·재통지가 아니다.** 실제 재생성·통지는 **재승인 시점**에 디바운스 flush 로 나간다(위 「재생성·통지의 트리거는 「검수 승인」 한 곳이다」 절과 같은 축). 차단된 export 는 `LS_DATASET_EXPORT` 행을 남기지 않아 실패 회수기가 집지 못하므로 **이 재트리거가 유일한 복구 경로**다.
  - ⚠ **구 서술 폐기(2026-08-15 코드 실측)** — *"②`DeidentReportResolvedEvent` = APPROVED 일 때만(export 재산출·관제 재통지)"* 는 **사실과 다르다**(발행처 0건 휴면 확장점). 이 오기는 `CLAUDE.md`·`docs/v2-wiki` 3곳·`docs/test-cases` 5행·코드 javadoc 4파일에 **같은 형태로 복제**돼 있었고 한 라운드로 함께 정정했다. 되돌리지 말 것.
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

### ★납품 설계 산출물은 동결·격리됐다 — 고치지 말 것 (2026-08-15 사용자 확정, 구속)

`docs/design/`(납품 CBD 산출물 11종 `KLID_AT_*.md` + 백업)과 `docs/cbd/`(생성 소스)는
**`docs/archive/frozen-20260815/` 로 옮겨 자동참조 경로에서 뺐다.** 사유·해제 조건은 그 폴더의 `README.md`.

- **판정 근거로 쓰지 않는다.** 낡은 것이 실측으로 확인됐다 — 예: 추적표 주1 이 *"비식별 옵션은 저작도구 전용 화면 없이 외부 솔루션 화면에서 설정"* 이라 적었는데 `frontend/src/features/sysconfig/components/DeidentConfigCard.tsx`(+테스트)가 실재하고 ITEM 3건(`SCREEN-025`·`UC-013`·`SEQ-013`)도 반대를 말한다.
- **개별 수정 금지** — 감사 결함 수정이 **전부 끝난 뒤 한 번에** 맞춘다. 지금 찔러 고치면 정합분과 미정합분이 구분되지 않는다.
- **격리 효과**: `docs/design/*.md` 가 없으므로 「설계 baseline 준수」 규칙과 `cc-design-verifier` 는 graceful SKIP 된다(낡은 baseline 으로 drift 판정을 내리지 않는다). LogiCraft 구현 키트(`docs/design/{도메인슬러그}-{DOMAIN-ID}/`)는 이 경로를 그대로 재사용한다.
- ⚠ **`docs/test-cases/`·`docs/v2-wiki/` 는 격리 대상이 아니다** — 전자는 LogiCraft 보다 최신이고(감사 6도메인 연속 확인) 후자는 아래 갱신 규칙의 대상이다. 함께 묶어 옮기지 말 것.

### ★정합의 진실원은 실제 코드다 (2026-08-15 사용자 확정, 구속)

> "실제 코드를 기반으로 봐야할거야. 문서들은 너무 낡았어."

이미 어긋난 것을 메우는 **정합 모드**에서는 코드가 판정한다. 문서 A 와 문서 B 가 다르면 어느 쪽도 근거가 아니다 — 코드를 연다.
⚠ **앞으로 바꿀 것**은 종전대로 「LogiCraft 먼저 → 코드」 순서를 탄다(위 「작업 위임 선언」 절). 두 모드를 섞지 말 것.

### 위키·테스트케이스 갱신

- **기능 추가/변경 시 `docs/v2-wiki/`의 해당 페이지를 같은 커밋(또는 같은 PR)에서 갱신한다**
- 신규 도메인이면 v2-wiki에 새 페이지 추가 + `docs/v2-wiki/README.md` 목차 갱신
- 화면 추가 시 `docs/v2-wiki/04-screens-ia.md`, 테이블 추가 시 `docs/v2-wiki/18-database.md` 갱신
- v1 대비 기능 추가 시 `docs/v1-wiki/19-v2-gap-checklist.md` 체크 갱신 (R1 요구사항 외 기능이면 "요구사항 외 추가 결정" 명시)
- **★ 동작·정책이 바뀌면 `docs/test-cases/` 카탈로그도 같은 커밋에서 갱신한다 (2026-08-03 사용자 확정, 구속)**: wiki 만 고치고 테스트케이스를 두면 카탈로그가 **폐기된 동작을 계속 검증 대상으로 들고 있어** 다음 전수 검증에서 "결함"으로 재발견된다(이 저장소의 "철회된 정책 재시도" 사고 패턴과 같은 뿌리).
  - **정정**: 기대결과·전제·근거를 실제 코드로 재확인해 고친다. **★근거는 `파일명(심볼명)` 으로 적는다 — 라인번호 금지, 심볼은 가능하면 반드시 (2026-08-05 사용자 확정, 구속)**: `PresetService.java(resolveLabels)` 처럼 적고 `:105-143` 같은 라인 참조는 적지 않는다. **심볼 생략도 안 된다** — 심볼은 그 판정을 실제로 수행하는 메서드·클래스·테스트명을 가리키며 라인과 달리 리네임 전까지 안정적이면서 검색으로 즉시 도달한다. 판정 지점이 여럿이면 ` · ` 로 나열하고, **심볼 특정이 불가능한 경우**(설정 yml·마이그레이션 SQL 등)에만 파일명 단독을 허용한다. 근거 정정 대상은 **파일 리네임·삭제·이동, 판정 주체의 클래스 이동, 심볼 리네임**이다(라인 이동은 더 이상 사유가 아니다). ⚠ **기존 행은 일괄 백필하지 않는다** — 회차 12 시점 심볼 보유율 13%(287/2,134)이고 전수 보강은 코드를 열어 판정 메서드를 찾아야 해 회차 4 급 비용이다. **앞으로 손대는 행부터 채워** 점진 상승시키며, 파일명 단독 행은 결함이 아니라 미보강 상태다(드리프트로 집계하지 않는다). 구 정책(*"라인 드리프트만 바뀐 것도 고친다 — 근거 정확도가 이 카탈로그의 존재 이유"*)은 **폐기** — 근거: 회차 4 는 정정 ≈699건 중 기대결과·전제가 실제로 바뀐 건이 **24건뿐**이었고 회차 11 은 **76건 전량이 순수 리팩토링 드리프트**였다(노동의 대부분이 "코드를 여는 시간 몇 초"에 소모). 게다가 **stale 라인은 없느니만 못하다** — 회차 4 에서 H-16 의 17건이 **엉뚱한 함수**를 가리키고 있었고, 틀린 좌표는 검증자를 잘못된 판정으로 이끈다. ⚠ 다만 **"소스 무변경이니 근거도 맞다"는 추론 금지 규칙은 그대로 유효**하다(파일 소멸·판정 이동은 라인 폐지와 무관하게 놓친다).
  - **폐기**: 행을 **지우지 말고 폐기 표기 + 사유·근거 커밋**을 남긴다. 지우면 "왜 이 케이스가 없어졌지"로 되돌아와 같은 논의를 반복한다.
  - **신설**: 새 동작은 경계·오류·하위호환 케이스까지 함께 넣는다.
  - 각 파일 상단 `## 변경 이력` 표에 회차 행을 추가하고, **정책이 뒤집힌 항목은 "구 정책 → 폐기"를 명시**한다.

## 작업 위임 선언

> `/cc` PM이 이 표를 0-2 업무 유형 결정보다 **우선** 적용한다 (규약 → cc-forge `commands/cc.md` §0-2.5).
> 이 선언이 없으면 `/cc`는 LogiCraft 계약을 모른 채 표준 `developer-*` 파이프라인으로 흘러 키트·`@design` 추적 태그·IMPREC 반영이 끊긴다.
> 이 프로젝트의 설계 진실원은 **LogiCraft ITEM**과 로컬 키트(`docs/design/{도메인슬러그}-{DOMAIN-ID}/`)다.

### ★변경 순서 — LogiCraft 먼저, 코드는 그 다음 (2026-08-07 사용자 확정, 구속)

**LogiCraft ITEM 으로 추적되는 변경은 ① ITEM 을 먼저 고쳐 사양을 확정하고 → ② 그 ITEM 을 진실원으로 코드를 반영하고 → ③ 구현 seam 에 `@design <ITEM-ID>` 태그로 잇는다.** 코드를 먼저 고치고 ITEM 을 나중에 따라오게 하지 않는다 — 그러면 ITEM 이 사양이 아니라 **구현 상태 서술**로 오염되고, 진실원이 무너져 다음 사람이 코드에서 설계를 역추정하게 된다.

- ⚠ **"권한·응답코드는 구현이 진실원"과 모순이 아니다** — 그건 **이미 어긋난 것을 사후에 메우는 정합(감사) 모드**의 판정 규칙이고, **앞으로 바꿀 것**은 이 순서를 탄다. 정합에서 "코드가 확정 정책과 충돌"로 잡힌 건의 수정도 ITEM 먼저다.
- **예외**: 탐색·스파이크는 코드를 먼저 만져도 되나 **확정은 ITEM 에 먼저** 넣고 정식 구현은 그 뒤 · 구현 중 설계가 틀렸다고 판명되면 코드를 조용히 틀지 말고 **ITEM 을 먼저 고친 뒤 재개** · 긴급 장애로 코드가 앞섰다면 예외가 아니라 **빚**이라 같은 작업의 마무리로 ITEM 을 맞춘다.
- 절차·함정 상세(한글 손상·배열 원소 소실·`status` 함정·검사기 사각): **`.claude/rules/logicraft-integration.md`** (ITEM 을 쓰기 전에 읽는다)

### ★★LogiCraft 완성도가 최우선이다 — 정합은 "타입"이 아니라 "층" 단위로 돈다 (2026-08-08 사용자 확정, 구속)

> "똑같은 실수를 반복하지않게해. logicraft 완성도가 우선이야. 개발은 그거에 따라가면 그만이니깐." — 사용자 원문

**설계 산출물의 완성도가 개발보다 우선한다.** 코드는 그것을 따라가면 되므로, 정합에서 무엇을 먼저 볼지 갈릴 때는 **항상 LogiCraft 쪽을 먼저 완결**시킨다.

**★반복의 진짜 원인 — 라운드를 ITEM 타입별로 짜면 층이 통째로 빠진다.** 지금까지 라운드는 `screen_spec` 32건 / `ui_component` 99건 / `api_endpoint` 171건처럼 **타입 단위**로 돌았다. 그런데 **하나의 결정은 여러 층에 복제돼 있어** 타입별 스윕은 각 층의 일부만 지나간다. 실측 결과(2026-08-08): 페이저 폐기 결정이 ITEM 에는 반영됐는데 **미러 렌더에는 8건 남아** 있었고, 별칭 `TaskAssignDialog` 는 `custom_name` 축에서 "잔재 0건"으로 확인됐는데 **미러에는 살아 있었으며**, **정적 렌더는 5개 라운드 동안 한 번도 열리지 않았다.**

**따라서 라운드 단위는 "결정"이고, 판정 단위는 "층"이다.** 결정 하나를 잡으면 아래 층을 **전부 세고**, 안 본 층을 **명시**한다.

| # | 층 | 확인 수단 |
|---|---|---|
| 1 | ITEM 본문(`data`) | `get_item` — **부분 read 로 필드 누락 주의** |
| 2 | **정적 렌더 미러**(`static_renders`, page+overlay surface) | `list_static_renders` → **`source_hash` 대조**(아래) |
| 3 | 링크·역참조(`referenced_by_*`·`consumes_apis`·`links.unresolved`) | `get_neighbors` · 선언 ID 실재 확인 |
| 4 | 로컬 키트 스냅샷(`docs/screen-design/.staging-*`) | `current_version` 대조 — **낡은 사본을 정본으로 읽는 사고가 실제로 있었다** |
| 5 | 위키 `docs/v2-wiki/` | 화면 IA + 도메인 페이지 |
| 6 | 테스트케이스 `docs/test-cases/` | 폐기 동작이 검증 대상으로 남아 있는지 |
| 7 | 코드 | 구현 대조 |

- **★`source_hash` 오라클(2026-08-08 발견)**: 서버가 렌더마다 **ITEM `sections` 의 sha256(compact JSON)** 을 들고 있다. 로컬 계산값과 대조하면 **시각 비교 없이 stale 을 확정**할 수 있다 — 실제로 이 해시가 시각 기반 판정 2건을 뒤집었다(한 화면은 이미 최신, 다른 하나는 `sections` 가 아니라 `purpose` 만 낡음). ⚠ **`sections` 만 덮는다** — `purpose` 등 다른 필드는 별도 대조가 필요하다.
- **★보고 형식 강제 — "0건"만 적는 것을 금지한다.** 이 저장소는 *"0건을 믿지 마라 — 세 번 뚫렸다"* 를 이미 규칙으로 갖고 있었는데 2026-08-08 에 네 번째로 뚫렸다. 전부 **검사기의 시야가 좁았던 것**이다. 따라서 모든 정합 보고는 **①어느 층에서 0건인지 ②어느 층을 보지 않았는지**를 함께 적는다. 층을 안 봤다는 사실이 보고에 없으면 그 라운드는 미완이다.
- **완료 선언의 기준**: "정합 완료"는 **그 결정에 대해 7개 층을 전부 확인했을 때만** 쓴다. 타입 하나를 다 훑은 것은 "그 타입 완료"이지 정합 완료가 아니다.
- 층별 절차·함정 상세는 `.claude/rules/logicraft-integration.md` §2-E·§5.

**적용 범위 (Critical — 위임은 예외이지 기본값이 아니다)**: 이 선언은 **LogiCraft ITEM/도메인으로 추적되는 작업에만** 적용한다. 이 저장소의 모든 작업이 LogiCraft 대상은 아니다 — 일반 기능 추가·버그 수정·인프라/빌드·CVAT 포팅·위키/테스트케이스 갱신 등은 **기본값인 표준 `/cc` 파이프라인**으로 간다. 요청이 아래 `조건`에 명확히 매칭될 때만 위임하고, 애매하면 표준 경로를 택하거나 사용자에게 확인한다.

- 조건: 도메인 백엔드 구현 (API·ERD/DB·domain_event·service·NFR) → 위임: `mc-logi-implement` | 근거: 키트가 단일 진실원이며 `@design <ITEM-ID>` 추적 태그 규약을 포함
- 조건: 화면(screen_spec) 프론트엔드 구현 → 위임: `mc-logi-screen-implement` | 근거: 화면은 implement 가 아닌 screen-implement 담당 (중복 구현 방지)
- 조건: 화면 비주얼 디자인·고충실도 목업 → 위임: `mc-logi-screen-design`
- 조건: 키트 다운로드·동기화(stale 해소) → 위임: `mc-logi-implement-kit` / `mc-logi-screen-kit`
- 조건: LogiCraft ITEM 수정·정합·cascade → 위임: `mc-logi-update` | 근거: AI 임의 등록 금지 + cascade 재귀 추적 절차 보유
- 조건: ERD 컬럼 용어사전(4계층) 정합·표준화 → 위임: `mc-logi-glossary-align` | 근거: 위 "주요 비즈니스 규칙"의 사전 판정 규칙(CSV grep 우선, `createdBy` 확인)과 동일 절차를 강제
- 조건: 구현 코드 ↔ 키트 정합 점검 → 위임: `mc-logi-implement-review`
- 조건: 도메인 갭 감사 → 위임: `mc-logi-domain-review`
- 조건: 구현 코드의 `code_module` 등록·정합 → 위임: `mc-logi-module-register`
- 제외: **CBD 산출물(R1~D9) 생성은 기존대로 `/cc-doc-gen`** — LogiCraft ITEM → 문서 생성 축이라 구현 키트 축과 다르다. ⚠ 다만 그 산출물은 **2026-08-15 동결 중**(위 「문서 동기화 규칙」)이라 감사 결함 수정이 끝나기 전에는 재생성하지 않는다
- 제외: LogiCraft ITEM과 무관한 일반 버그 수정·리팩토링·빌드/설정 변경, `docs/v2-wiki`·`docs/test-cases` 갱신 → 표준 `/cc` 파이프라인
- 참고: 위 표에 없는 `mc-logi-*` 스킬은 스킬 description 매칭으로 호출된다 (표를 전수 유지하지 않는다)

> ⚠ **구 서술 폐기(2026-08-16)** — *"현재 이 저장소에는 로컬 키트가 아직 없다(`docs/design/` 은 비어 있다)"* 는 더 이상 사실이 아니다. **구현 키트가 활성 14 도메인 전량에 실재**한다(아래 「Logicraft 구현 키트」 블록). 따라서 키트 선행이 필요한 위임(`mc-logi-implement`·`mc-logi-implement-review`)은 곧바로 동작한다. **화면 키트는 여전히 7개뿐**이므로 `mc-logi-screen-implement` 는 대상 도메인의 화면 키트 유무를 먼저 확인한다. 납품 산출물이 2026-08-15 에 `docs/archive/frozen-20260815/` 로 동결 이관된 것은 그대로이고, 구 서술이 말한 `backup/`·`hwpx/` 디렉터리는 실재하지 않는다. 키트 유무는 `find docs/design docs/screen-design -maxdepth 2 -name version-master.md` 로 확인한다.

<!-- mc-logi-kit:start (자동 관리 — 직접 수정 금지, mc-logi-implement-kit 재실행 시 갱신) -->
# Logicraft 구현 키트

이 레포는 logicraft 설계 기반으로 구현한다. **코드 작업 전 아래 키트의 IMPLEMENTATION.md 를 먼저 읽을 것.**

> 활성 15 도메인 전량 · last sync **2026-08-21 (SYNC)** · 전건 무열화 검증 통과 · 서버 대비 뒤처짐 **0건**.

| 도메인 | 키트 경로 | ITEM | 구현 현황 (설계 쪽 주장) | 설계 0건 단계 |
|---|---|---|---|---|
| DOMAIN-001 사용자·권한 | docs/design/사용자권한-DOMAIN-001/ | 53 | implemented 18 / planned 20 / (미기재) 15 | CONST 상수값, EVT 이벤트 계약, AC 수용, TEST 통합시험, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-003 영상·프레임 수집 | docs/design/영상프레임-수집-DOMAIN-003/ | 107 | implemented 56 / planned 33 / (미기재) 18 | CONST 상수값, INT 외부 연동 |
| DOMAIN-004 AI 보조 라벨링 | docs/design/ai-보조-라벨링-DOMAIN-004/ | 84 | implemented 36 / planned 27 / (미기재) 21 | ERD 데이터 계층, EVT 이벤트 계약, TEST 통합시험, INT 외부 연동 |
| DOMAIN-005 검수 | docs/design/검수-DOMAIN-005/ | 94 | implemented 46 / planned 28 / (미기재) 20 | CONST 상수값 |
| DOMAIN-006 통계·대시보드 | docs/design/통계대시보드-DOMAIN-006/ | 44 | implemented 13 / planned 21 / (미기재) 10 | CONST 상수값, ERD 데이터 계층, EVT 이벤트 계약, SEQ 흐름 배선, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-007 데이터 증강 | docs/design/데이터-증강내보내기-DOMAIN-007/ | 72 | implemented 26 / planned 28 / (미기재) 18 | CONST 상수값 |
| DOMAIN-009 게시판·공지 | docs/design/게시판공지-DOMAIN-009/ | 41 | implemented 17 / planned 17 / (미기재) 7 | CONST 상수값, EVT 이벤트 계약, SEQ 흐름 배선, UC 검증, AC 수용, TEST 통합시험, CDIAG 클래스 구조, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-010 라벨링 | docs/design/라벨링-DOMAIN-010/ | 155 | implemented 77 / planned 49 / (미기재) 29 | INT 외부 연동 |
| DOMAIN-011 마킹 | docs/design/마킹-DOMAIN-011/ | 47 | implemented 11 / planned 24 / (미기재) 12 | CONST 상수값 |
| DOMAIN-012 비식별화 | docs/design/비식별화-DOMAIN-012/ | 72 | implemented 22 / planned 34 / (미기재) 16 | CONST 상수값 |
| DOMAIN-013 포털 | docs/design/포털-DOMAIN-013/ | 76 | implemented 37 / in_progress 1 / planned 26 / (미기재) 12 | CONST 상수값, FEAT 상위 기능 |
| DOMAIN-014 시스템 설정 | docs/design/시스템-설정-DOMAIN-014/ | 53 | implemented 17 / planned 25 / (미기재) 11 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험 |
| DOMAIN-015 작업 배정 | docs/design/작업-배정-DOMAIN-015/ | 42 | implemented 15 / planned 20 / (미기재) 7 | CONST 상수값, EVT 이벤트 계약, AC 수용, TEST 통합시험, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-016 관제 통지 | docs/design/관제-통지-DOMAIN-016/ | 58 | implemented 18 / in_progress 1 / planned 26 / (미기재) 13 | CONST 상수값, SD 고충실 시안 |
| DOMAIN-017 외부 산출물 이관 | docs/design/외부-산출물-이관-DOMAIN-017/ | 56 | implemented 19 / in_progress 1 / planned 33 / (미기재) 3 | CONST 상수값, TEST 통합시험, CDIAG 클래스 구조, C4 컴포넌트, INT 외부 연동, SD 고충실 시안 |

## 작업 규칙 (키트 워크플로)
1. **키트가 설계 진실원** — 도메인 규칙·제약·빌드순서는 키트에서 읽는다. 키트 파일은 read-only 산출물 — **직접 수정 금지**.
2. **기능/시나리오를 수정하려면**: `/mc-logi-update` 로 logicraft 설계를 먼저 수정 → `/mc-logi-implement-kit` SYNC 로 로컬 키트 재동기화 → 그 다음 코드 반영.
3. **구현 착수는** `/mc-logi-implement` — 키트 신선도 게이트부터 시작한다.
4. **구현 완료 시** logicraft 에 IMPREC 추적 기록 (mc-logi-implement Phase 5 가 수행).
5. 작업 전 키트가 오래됐으면(`version-master.md` last sync 확인) SYNC 먼저.

## 도메인별 주의 (상세는 각 IMPLEMENTATION.md)
- **공통**: 위 「구현 현황」은 **ITEM 이 스스로 적은 주장**이며 코드와 대조된 값이 아니다. 대조는 `/mc-logi-implement-review` 의 몫이다.
- **공통**: 스코프는 `.kit-scope.json` pin 이 정본이다 — 서버 `--domain` 필터는 `domain_id` 컬럼만 봐서 재현율 59% 다(이 프로젝트는 1,080 ITEM 중 절반 가까이가 `domain_id` 미설정). pin 은 `kit-export` 전수 그래프(1-hop 도메인 확장)로 판정했고 **키트와 함께 커밋**해야 다른 PC 가 같은 키트를 얻는다.
- **공통 — 미판정(`pending`) 처리 규칙 (2026-08-21 확정)**: 다운로더는 매 SYNC 마다 「그래프상 닿는데 pin 에 없는 ITEM」을 `pending` 에 채운다. 판정 기준은 `core-item-set.md` 이며 **`code_module`(MOD)·`legacy_artifact`(LEGACY)·`app_shell`(SHELL)·`navigation_tree`(NAV)·`requirement`(REQ)·`rfp_item`(RFP)은 제외 타입이라 기각**한다. 이 여섯은 기각해도 다음 SYNC 에 다시 `pending` 으로 올라오며 **그것이 정상**이다(제외 판정을 기록할 필드가 스키마에 없다). ⚠ 그럼에도 **D013·D014 키트에는 SHELL-001·NAV-001 이 이미 pin 에 들어 있다** — 과거 판정의 잔재이며 빼면 유실 위험이 있어 그대로 뒀다(축이 갈려 있음을 알고 쓸 것).
- **공통**: 폐기(`deprecated`/`superseded`) ITEM 은 키트에 담지 않는다. 「설계 0건 단계」는 **다운로드 누락이 아니라 설계 결손**이다.
- **`CONST` 가 대부분 도메인에서 0건인 것은 정상** — 프로젝트 전역 CONST 가 2건뿐이고 둘 다 COCO 라벨링 축이라 D004·D010 에만 들어간다.
- **DOMAIN-003**: 2026-08-21 SYNC 에서 시계열 건너뛰기 축 8건(`ADR-049`·`ADR-050`·`API-212`~`API-214`·`AC-049`~`AC-051`)이 pin 에 승격돼 처음 내려왔다. 그 전까지는 미판정이라 키트에 **한 건도 없었다** — 이 축을 로컬 키트로만 판정했다면 「설계에 없다」고 오판했을 구간이다.
- **DOMAIN-006**: ⚠ **구 서술 폐기(2026-08-21)** — *"활성 `use_case`·`acceptance`·`test_scenario` 가 전부 0건"* 은 더 이상 사실이 아니다. `UC-033`·`AC-029`~`AC-031`·`TEST-006` 이 미판정 상태로 서버에만 있다가 이번 SYNC 에서 pin 승격돼 들어왔다. **여전히 0건인 것은 `erd`·`domain_event`·`diagram_sequence`** 이며(유일 ERD-003 은 폐기) 데이터 모델 축은 그대로 결손이다.
- **DOMAIN-009 · DOMAIN-001 · DOMAIN-015**: 0건 단계가 6~10개로 많다 — 검증 축(UC/AC/TEST)이 통째로 비어 있다.
- **DOMAIN-017**: ⚠ **구 서술 폐기(2026-08-21)** — *"implemented 1 / 자기 소속 ITEM 23건뿐"* 은 낡았다. pin 56건이고 구현 주장이 19건이며, `UC-036`·`AC-052`~`AC-054`(이관 이력 조회 축) 신설로 검증 축이 생겼다. 타도메인에서 끌어온 근거 8건(①`DFEAT-057.persists_in_tables` 와 `ERD-031` FK 가 가리키는 ERD 5건 — ERD-010·012·017·019·025 ②`ADR-048` 을 상호 인용한 ADR-042·EVT-005·UC-018)은 그대로 유지된다.
<!-- mc-logi-kit:end -->

<!-- mc-logi-screen-kit:start (자동 관리 — 직접 수정 금지, mc-logi-screen-kit 재실행 시 갱신) -->
# Logicraft 화면 키트

이 레포는 logicraft 화면 설계 기반으로 프론트엔드를 구현한다. **화면 작업 전 아래 키트의 SCREENS.md 를 먼저 읽을 것.**

> ★ **통합 키트가 정본이다** — 도메인별 소형 키트는 화면 1~5개만 담으므로, 화면을 동기화하거나
> 사양을 판정할 때는 **먼저 `klid-authoring-screens/`(32화면 통합)를 본다.** 소형 키트만 보면
> 그 스코프 밖 화면의 변경이 절대 반영되지 않는다.

| 키트 | 화면 수 | 키트 경로 | ui_component 카탈로그 | last sync |
|---|---|---|---|---|
| **전체 통합 (32화면)** | 32개 (SCREEN-001~038 중 32건) | docs/screen-design/klid-authoring-screens/ | 144건 | 2026-08-21 (s20) |
| DOMAIN-010 라벨링 | 2개 (SCREEN-005, SCREEN-026) | docs/screen-design/라벨링-DOMAIN-010/ | 144건 | 2026-08-21 (s15) |
| DOMAIN-005 검수 | 2개 (SCREEN-018, SCREEN-019) | docs/screen-design/검수-DOMAIN-005/ | 144건 | 2026-08-21 (s9) |
| DOMAIN-015 작업 배정 | 1개 (SCREEN-012) | docs/screen-design/작업-배정-DOMAIN-015/ | 144건 | 2026-08-21 (s9) |
| DOMAIN-003 영상·프레임 수집 | 1개 (SCREEN-009) | docs/screen-design/영상프레임-수집-DOMAIN-003/ | 144건 | 2026-08-21 (s9) |
| DOMAIN-009 게시판·공지 | 4개 (SCREEN-030, SCREEN-031, SCREEN-036, SCREEN-037) | docs/screen-design/게시판공지-DOMAIN-009/ | 144건 | 2026-08-21 (s8) |
| DOMAIN-001 사용자·권한 | 5개 (SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-024) | docs/screen-design/사용자권한-DOMAIN-001/ | 144건 | 2026-08-21 (s8) |

## 작업 규칙 (화면 키트 워크플로)
1. **키트가 설계 진실원** — 화면 규칙·제약·빌드순서는 키트에서 읽는다. 키트 파일은 read-only 산출물 — **직접 수정 금지**.
2. **화면/시나리오를 수정하려면**: `/mc-logi-update` 로 logicraft 설계를 먼저 수정 → `/mc-logi-screen-kit` SYNC 로 로컬 키트 재동기화 → 그 다음 코드 반영. (코드만 고치고 설계를 안 고치면 다음 SYNC 때 충돌)
3. **구현 착수는** `/mc-logi-screen-implement` — 키트 신선도 게이트부터 시작한다.
4. **구현 완료 시** logicraft 에 IMPREC 추적 기록 (mc-logi-screen-implement Phase 5 가 수행).
5. 작업 전 키트가 오래됐으면(`version-master.md` last sync 확인) SYNC 먼저.

## 공통 주의
- ★ **`design-main.html`/`.css` 는 서버 게시본의 미러**라 SYNC 가 서버 판으로 되돌린다. 로컬에서 미러를 고치면 SYNC 한 번에 사라지므로, 순서는 **`design.html`(로컬 원본) 수정 → `upload_design_render` 로 게시 → 미러가 따라옴** 이다. (2026-08-21 SYNC 실측: `design.*` 원본 변경 0건 · 삭제 0건.)
- ⚠ **`SCREEN-039`(외부 산출물 이관 이력)는 어느 화면 키트에도 없다.** 통합 키트 스코프가 SCREEN-038 까지라 이번 SYNC 에서도 들어오지 않았다. 그 화면의 설계는 **`docs/design/외부-산출물-이관-DOMAIN-017/screen_spec/`** 에 있다. 통합 키트에 넣으려면 화면 폐포(소비 API·UC·AC·렌더)를 함께 승격하는 별도 SYNC 가 필요하다.
- ⚠ `.staging/` 은 gitignore 대상이라 워크트리를 바꾸면 델타 기준선이 사라지고 **첫 SYNC 가 전건 `NEW` 로 찍힌다.** 재동기화 1회로 `UNCHANGED` 로 정착하며, 진짜 델타는 git 의 이전 `version-master.md` 와 대조해 얻는다.

## 도메인별 주의 (상세는 각 SCREENS.md §주의)
- **DOMAIN-010**: SCREEN-026(프리셋 관리)은 UC-032 만 연결되고 AC(수용기준) 링크·screen_design(SD) 이 없다(logicraft 쪽 보강 여지). DOMAIN-010 소속인 SCREEN-010(로드 버전 선택)·SCREEN-035(라벨 관리)는 이 소형 키트 스코프 밖이며 **통합 키트에는 있다**.
- **DOMAIN-005**: SCREEN-018·SCREEN-019 2화면. 변경 알림·RETIRED 없음.
- **DOMAIN-015**: SCREEN-012 는 AC(수용기준) 링크가 아직 없다(UC-029 에 covered_by 미등록) — 화면 키트는 정상이고 logicraft 쪽 보강 여지다.
- **DOMAIN-003**: SCREEN-009 1화면. 변경 알림·RETIRED 없음.
- **DOMAIN-009**: 화면 4개 모두 use_case·acceptance 미연결. SCREEN-031(공지 상세)은 와이어프레임 렌더 2건(main + delete-confirm)을 갖는다.
- **DOMAIN-001**: SCREEN-001·SCREEN-003·SCREEN-004(세션 인계·접근 거부·개발용 로그인)는 인증 전이거나 셸이 없는 화면이라 SHELL-001·NAV-001 미적용이고 consumes_apis·required_roles·UC/AC 링크가 모두 없다. SCREEN-002(역할 클레임)는 API-007 만 연결된다. SCREEN-024(사용자 관리)만 SHELL-001+NAV-001 적용 대상이며 UC-030(사용자 계정·역할 관리)이 역참조로 연결된다(AC 없음).
<!-- mc-logi-screen-kit:end -->

