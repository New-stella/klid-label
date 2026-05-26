# 학습데이터 저작도구 워크스페이스

> 이 파일은 워크스페이스 루트에 위치합니다.
> 팀 공통 규칙은 ~/.claude/rules/ 에 있으므로 여기엔 이 프로젝트 전용 내용만 작성합니다.

## 프로젝트 개요
- **목적**: AI 기반 지방정부 CCTV 관제지원시스템(2차)의 학습데이터 저작도구 — 영상/이미지 라벨링, 검수 워크플로우, 비식별화, 외부 생성 메타데이터 검토
- **주요 도메인**: 사용자/권한, **마킹(자동/수동 이벤트 식별)**, 배치 파이프라인(마킹→VLM 콜백→비식별→프레임 추출→오토라벨링), 라벨링, 검수(REVIEWER 배정), 비식별화, 버전관리(Gitea), 데이터 증강(새 영상), 포털(데이터마트 Load)
- **범위 외 (V1.4)**: 데이터마트(SFR-13) — 외부제공 시스템 책임으로 이관. 저작도구는 학습데이터셋 내보내기까지만 담당하고 마트 구축·검색·다운로드는 담당하지 않음
- **범위 외 (V1.5)**: SFR-06 본체(생성형 AI 모델 학습·파인튜닝·UI/UX 편의성·프롬프트 가이드 등) — 외부 생성 시스템 책임. 저작도구는 SFR-07 증강 결과 검수(SCR-AUG-002)만 보유. SFR-15 다운로드 기능은 포털 자체 책임으로 이관 — 저작도구 mock에 다운로드 카드·D-day 배지·다운로드 버튼·만료 처리 UI 미제공. (2026-05-15 정리: 배경영상 요청 API `/v1/generate/background/*` 는 외부 미연동 상태로 제거. 추후 외부 연동 결정 시 재도입)
- **범위 외 (V1.7)**: VLM 모델 본체(학습·파인튜닝·프롬프트 관리) — 외부 시스템 책임. 저작도구의 VLM 연동은 **외부 VLM 서비스를 호출해 시계열 정보를 획득하는 연동**만 보유 (`ai-server/app/routers/vlm.py`는 외부 VLM 호출 어댑터). 응답을 LS_DATA_META(VLM)에 적재하고 SCR-AUTO-002 화면에서 REVIEWER 가 검토·수정
- **요구사항 정리 (V1.8)**: 본체가 외부 시스템인 **SFR-03(시계열 메타 모델)·SFR-06(생성형 AI)·SFR-11(영상 합성 모델)은 요구사항정의서에서 제거**. 저작도구 잔존 책임(외부 VLM 시계열 호출 연동·외부 메타 검토 UI·증강 연동·생성된 영상 라벨링)은 모두 **SFR-08(저작도구 핵심 기능)에 흡수**됨. 화면 인덱스의 SFR 매핑도 SFR-08로 단일화. (2026-05-15 정리: 외부 미연동 상태인 배경영상 요청 인터페이스 BE 코드는 제거됨)
- **범위 외 (V1.9)**: 학습데이터셋 내보내기(Export) — 범위 외로 변경. 저작도구는 라벨링·검수·버전관리까지만 담당

## 워크스페이스 구조

```
klid-la-test-v0/
├── backend/                 # Spring Boot 메인 애플리케이션 (Java 17)
│   ├── src/main/java/kr/co/cudo/authoring/   # 도메인 패키지
│   ├── src/main/resources/
│   │   ├── application.yml · application-{local,dev,stg,prd}.yml
│   │   └── db/migration/    # Flyway
│   ├── src/test/java/...
│   ├── build.gradle · settings.gradle
│   └── .env.example
├── ai-server/               # Python FastAPI AI 추론 서버 (YOLO/SAM2/VLM만)
│   ├── app/                 # main + routers/{yolo,sam2,vlm}
│   ├── requirements.txt
│   └── tests/
├── frontend/                # React + Vite + TypeScript
├── docs/
│   ├── requirements/        # 요구사항/DB/UI-UX V1.1
│   ├── analysis/            # CVAT 분석 문서 + portable-modules 9종
│   ├── design/ · contracts/ · api-specs/ · architecture/
├── reports/
├── cvat/                    # CVAT 원본 트리 (레퍼런스, .gitignore)
├── .claude-plan.md
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
- **Flyway 10.13** (`klid_system` DB 내 `klid_at` 스키마 — 저작도구 전용 테이블은 독립, MNG_* 공유 테이블 변경 시 관제서버팀 협의)
- **Spring Boot Quartz** (기존 `QRTZ_*` 테이블과 호환)
- **Resilience4j** (외부 API 재시도/서킷 브레이커/타임아웃)
- **Spring WebFlux WebClient** (외부 시스템 연동)
- **net.bramp.ffmpeg** (FFmpeg Java 래퍼)
- **Caffeine** (로컬 캐시 — 시스템 설정 TTL 60s)
- MapStruct 1.5 / Lombok
- **Micrometer + Prometheus** (메트릭 수집 — API 응답시간, 배치 처리량, 외부 API 호출 모니터링)
- Springdoc OpenAPI 2.5 (Swagger UI)
- JUnit 5 + Testcontainers (MariaDB)

### AI 추론 서버 (ai-server)
- Python 3.11 + FastAPI
- ultralytics (YOLO), torch/torchvision (SAM2), opencv-python
- 역할: 경량 추론 전용. 상태·인증·DB 없음. Spring Boot가 오케스트레이션 주체

### 프론트엔드
- React 18 + TypeScript 5 + Vite 5
- TanStack Query v5, Zustand, React Router v6, axios
- Tailwind CSS
- 라벨링 캔버스: konva.js (CVAT canvas-drawing.md 참고한 포팅)

### 데이터베이스
- **MariaDB 10.11.13 (LTS)** — `klid_system` @ 192.168.102.101:13307
- utf8mb4 / utf8mb4_unicode_ci / InnoDB
- MaxScale 24.02.5 (Master-Slave Read/Write Splitting)
- `klid_system` DB 내 `klid_at` 스키마 운영 — **저작도구 전용 신규 34개(LS_*) + 관제서버 재사용 9개(MNG_*) = 총 43개 테이블**
- Quartz 스케줄러 `QRTZ_*` 11개 테이블은 기존 `klid_system` 공유
- 외부 채널은 포털 DB 공유

## 아키텍처 원칙

### 레이어
- Controller → Service → Repository (또는 QueryDSL 전용 Repository 보조)
- Controller는 DTO(Request/Response)만 다루고 Entity 직접 노출 금지
- Service는 비즈니스 로직. `@Transactional`의 readOnly 기본값 활용
- Repository는 JPA/QueryDSL만 사용, 비즈니스 로직 금지
- 도메인 중심 패키지 구조: `kr.co.cudo.authoring.{domain}.{controller|service|repository|entity|dto}`

### 공통 인프라 (Phase 0에서 구축)
- `common.response.ApiResponse<T>` — 모든 API 표준 응답
- `common.exception.ErrorCode` enum + `CustomException` + `@RestControllerAdvice GlobalExceptionHandler`
- `common.security.JwtAuthenticationFilter` — 관제/포털 JWT 디코드 → `TokenClaims` → `SecurityContext`
- `common.security.SecurityConfig` — 역할 기반 접근 제어
- `common.datasource.{ControlDataSourceConfig, PortalDataSourceConfig}` — 듀얼 EntityManager/TransactionManager (`@ControlRepo`, `@PortalRepo`로 분리)
- `common.logging.RequestIdFilter` + Logback JSON 인코더 (민감 필드 마스킹)
- `common.client.*` — DeidentifyClient / AiServerClient / GiteaClient / ControlNotifyClient (Resilience4j 적용) — 관제/포털 양방향 M2M 통합은 deprecated이나 **저작도구 → 관제서버 단방향 outbound 완료/수정 통지(ControlNotifyClient)는 예외로 보유**

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
| `CONTROL_DB_HOST/PORT/NAME/USERNAME/PASSWORD` | klid_system 접속 | dev/stg/prd |
| `PORTAL_DB_HOST/PORT/NAME/USERNAME/PASSWORD` | 포털 DB 접속 | dev/stg/prd |
| `JWT_SECRET` / `JWT_ISSUER` | JWT 검증 | dev/stg/prd |
| `DEIDENTIFY_API_URL` | 비식별 서버 | dev/stg/prd |
| `AI_SERVER_URL` | ai-server 내부 주소 | 전체 |
| `GITEA_BASE_URL` / `GITEA_TOKEN` / `GITEA_OWNER` / `GITEA_REPO` | Gitea 버전관리 | dev/stg/prd |
| `STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH` | 저장 경로 | 전체 |
| `VITE_API_BASE_URL` | FE API 주소 | FE 전체 |

### 환경별 접속 정보
| 환경 | BE | ai-server | FE | DB | 비고 |
|:----:|:---|:---|:---|:---|:-----|
| local | localhost:8080 | localhost:9300 | localhost:5173 | H2 (임시) | `./gradlew bootRun` / `uvicorn` / `vite` |
| dev | (dev BE) | (dev AI) | (dev FE) | 192.168.102.101:13307 | |
| stg | (stg BE) | (stg AI) | (stg FE) | (stg DB) | |
| prd | (prd BE) | (prd AI) | (prd FE) | (prd DB) | 시크릿은 환경변수/Vault |

## 역할 정의 (V1.3)

| 역할 | 코드 | 주요 권한 |
|------|------|-----------|
| 검수자 | `REVIEWER` | **사용자 관리·시스템 설정**, 작업자 배정·재배정·배정 이력 조회, 검수 승인/반려 (V1.3 — ADMIN 권한 흡수) |
| 라벨링 작업자 | `WORKER` | 라벨 수정·검수 제출 |
| 포털 회원 | `PORTAL_USER` | 이미지/영상 업로드, 간편 라벨링, 본인 데이터 기간 내 다운로드 |

> **V1.3 변경**: 시스템 관리자(ADMIN) 역할이 제거되고 모든 권한이 REVIEWER에 통합되었다. UI 호칭은 '검수자'로 통일하며, 관리 화면 URL은 `/manage/*`로 변경되었다.

## 주요 비즈니스 규칙

### 인증·진입 (V1.1)
- 저작도구는 **독립 로그인 UI 없음** — 관제서버(내부) / 포털 서버(외부)가 발급한 JWT 토큰을 인계
- **관제서버와 동일 도메인 운영** → 브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용
- 두 채널 모두 **동일 JWT 발급 서버** — 단일 검증 로직(`JwtAuthenticationFilter`)로 처리
- 토큰 `role` + `channel` 클레임으로 권한 분기 (`@PreAuthorize("hasRole('REVIEWER')")`)
- 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트
- **외부 시스템 양방향 통합(M2M) deprecated**: 관제서버/외부 학습데이터 시스템과의 송수신 API 및 M2M 인증 인프라는 본 버전에서 제거. 재구축 시 별도 설계 필요.
- **예외 — 저작도구 → 관제서버 단방향 outbound 완료/수정 통지 (V1.8 부활)**: 영상 단위 작업의 검수 완료 시 `TASK_COMPLETED` 이벤트, 검수 완료 후 라벨/메타 수정 시 `TASK_MODIFIED` 이벤트를 관제서버 inbound SPI 로 push (비동기). 동일 작업 ID(=`LS_DATA_RAW.RAW_SN`) 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신. 양방향 M2M 인증 인프라는 부활하지 않으며, 본 통지는 인계 토큰 또는 IP 화이트리스트로 보호.

### 배치 파이프라인 (인증 불필요)
- 영상 적재는 자체 업로드(포털 TUS / 관리 화면) 기반 — 관제서버 자동 송신은 미연동
- **V2.0 파이프라인 순서**: ⭐마킹(자동/수동) → VLM 시계열(콜백 비동기) → 비식별화 → FFmpeg(**마킹 위치 기반** 원본+비식별 2벌 추출) → YOLO(**원본만** 실행, 비식별본 결과 공유) → SAM2 → 트랙 보간
- **마킹 단계 (V2.0 신규)**: 영상별 자동/수동 모드 설정. 자동=프레임 간격 기반 마킹, 수동=작업자 키보드 단축키로 이벤트 시점 마킹. 마킹 결과(이벤트명 + 영상경로 + marks 배열)를 VLM에 콜백 형태로 전달
- **VLM 연동 (V2.0 변경)**: 동기 호출 → 콜백 비동기. 마킹 완료 → VLM 요청 → 콜백으로 시계열 결과 수신 후 파이프라인 재개
- **비식별 호출 조건**: 영상 `PRVC_TYPE_CD='PRVC' or 'PSDO'`일 때만. `ANONY`는 원본만 저장
- 원본 이미지와 비식별 이미지는 **별도 경로로 동시 저장**
- **오토라벨링 (V2.0 변경)**: YOLO/SAM2는 **원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유 (별도 실행 없음)
- YOLO/SAM2/VLM은 `AiServerClient`로 호출 (타임아웃 60s + Resilience4j CircuitBreaker)

### 작업 배정 (V1.3)
- **REVIEWER가 WORKER에게 배정** (역할 단일화 — V1.3에서 ADMIN 제거)
- `LS_TASK_ASSIGNMENT`에 `TASK_TYPE_CD='LABELER'` INSERT, 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER가 보유 (V1.3 — 기존 ADMIN 권한 흡수)

### 작업 단위 + 완료/수정 통지 (V1.8 신규)
- **작업 단위 = 영상 1건** — 프로젝트 단위 개념 사용 안 함. 작업 식별자는 영상 단위 ID(`LS_DATA_RAW.RAW_SN`)
- **검수 완료 = 작업 완료** — REVIEWER 가 검수를 `APPROVED` 처리하면 작업이 완료됨. `LsRawDataStatus.dataSttsCd` 가 `COMPLETED` 전이된 시점에 outbound `TASK_COMPLETED` 통지 발행
- **검수 완료 후 수정 시** — 동일 작업 ID 유지, 새 작업 ID 발급/버전 업 모두 안 함. 라벨/메타가 수정될 때마다 outbound `TASK_MODIFIED` 통지 발행. 수신측(관제서버)은 마지막 상태로 갱신
- **통지 단위는 영상 1건** — 라벨/이미지 1장 단위로 통지하지 않음. 영상 내 다수 변경이 같은 트랜잭션·짧은 시간 내 발생하면 디바운스 후 1회 통지(운영 결정)
- **TASK_COMPLETED 페이로드 — 메타만**: 이벤트 타입 + 작업 ID(RAW_SN) + 영상 메타(파일명·길이·채널) + 검수 완료 일시 + 프레임 개수 + 결과 요약 카운트(라벨 N건·메타 M건) + 요청 ID. 라벨/메타 본문 자체는 포함하지 않으며, 관제가 필요 시 본 도구 API 또는 Gitea 조회로 보강
- **TASK_MODIFIED 페이로드 — 수정 요약만 전달**: 이벤트 타입 + 작업 ID(RAW_SN) + 마지막 수정 일시 + **변경 프레임 목록**(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED`) + 변경 요약 카운트 + 요청 ID. 라벨/메타 본문 데이터는 포함하지 않음
- **관제서버 조회 패턴**: 관제서버가 통지를 수신하면 저작도구 API를 호출하여 필요한 상세 데이터를 직접 조회. 저작도구는 관제서버가 조회할 수 있는 API를 제공해야 함
- 페이로드에 PII·토큰·원본 비-비식별 이미지 포함 금지
- 이력 보존은 기존 인프라(`LS_DATA_LBL_HSTRY` + Gitea 커밋)로 충분

### 라벨링·버전관리
- 바운딩박스 / 폴리곤 / 세그멘테이션 / SAM2 Track — 캔버스는 konva.js
- 라벨 저장 이벤트 → Gitea 자동 커밋 (`GiteaClient.commit`). `LS_DATA_LBL_HSTRY`에 커밋 해시 저장
- diff 비교 / 롤백 지원
- **CVAT 트랙 보간 알고리즘** 포팅 (docs/analysis/portable-modules/01-track-interpolation.md → Java)
- **MASK ↔ RLE ↔ Polygon 변환** 포팅 (portable-modules/02)

### 증강 = 새 영상 (V2.0 신규)
- 증강 결과는 **새 영상(RAW_SN) 생성** — 원본과 다른 영상 ID. `LS_DATA_RAW.PARENT_RAW_SN`으로 원본 참조
- 원본 영상의 라벨/메타 JSON을 새 영상에 **복사**. 해상도 변경 증강은 이미지 해상도만 변경하여 저장
- 새 영상은 **미검수(PENDING) 상태**로 시작 → 작업자 배정 → 수정 → 검수 (기존 플로우 동일)
- 관제서버 통지 시 **새 영상 ID(RAW_SN)로 별도 완료 통지** 발송
- 증강 요청/수신 흐름은 기존 유지 (ExternalAugmentClient → 콜백)

### 포털 (외부 채널) — V2.0
- **데이터 소스 변경**: 관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임). 저작도구는 포털 DB에서 Load
- 포털 사용자가 영상 선택 → 라벨/메타 데이터 Load → 라벨링 화면에 표시
- **저장 시 원본 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 별도 적재
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음
- 오토라벨링 체험(YOLO+SAM2), VLM/버전관리/검수 미제공
- 반응형 웹 (PC/태블릿/모바일), WCAG 2.1 AA 준수

### DB 정책
- `klid_system` DB 내 `klid_at` 스키마에 저작도구 전용 테이블 34개(LS_*) 신규 운영
- 관제서버 MNG_* 테이블 9개 재사용 (READ 위주, JPA `ddl-auto=validate`)
- Flyway 마이그레이션: LS_* 전용 테이블은 자체 관리, **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수**

## CVAT 포팅 전략

CVAT 원본은 Django + TypeScript, 본 프로젝트는 Spring Boot + TypeScript. 전체 fork가 아닌 **9개 독립 모듈을 Phase별로 포팅**한다. (출처: `docs/analysis/index.md`)

| Phase 연관 | CVAT 포팅 모듈 | 언어 | 참고 문서 |
|:--------:|-------------|:---:|----------|
| Phase 3 | manifest.jsonl 포맷 (프레임 매니페스트) | Java | portable-modules/04 |
| Phase 3 | TUS 재개 가능 업로드 (포털/대용량) | Java | portable-modules/03 |
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
- 생성형 AI 증강 4종: WINTER / NIGHT / RAIN / RESOLUTION (SFR-07)

## 주의사항

### 외부 API 연동
- 비식별·Gitea·ai-server — **Resilience4j로 타임아웃/재시도/서킷 브레이커 적용 필수**
- 비식별 API 실패 시 영상 상태 `DE_IDNTF_YN='F'`로 마킹 + 재시도 큐. 원본 절대 삭제 금지
- 관제서버 세션 토큰은 저작도구가 발급하지 않음 — 검증 실패 시 관제서버 로그인 페이지로 리다이렉트
- 관제/포털 양방향 통합 API 및 외부 학습데이터 API는 deprecated — 재구축 전까지 미연동
- **관제서버 통지 + 조회 API (V1.8)**: `ControlNotifyClient`가 `TASK_COMPLETED`·`TASK_MODIFIED` 통지를 영상 단위로 송신(수정 요약만, 본문 미포함) — 요청 ID idempotency + dead-letter + 재등록 큐 + Resilience4j 적용. 관제서버는 통지 수신 후 저작도구 API를 호출하여 상세 데이터 조회. 단방향 outbound 통지 + inbound 조회 API 제공, 양방향 M2M 인증은 여전히 deprecated

### 파일 업로드 (포털 + TUS)
- 확장자 allowlist + 파일 크기 제한 + MIME 검증 필수
- TUS 프로토콜(재개 가능 업로드) 포팅 — CVAT portable-modules/03 참고
- 업로드 영상은 포털 사용자 본인 범위 내에서만 접근

### 개인정보 보호
- 영상 암호화 저장, 로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)
- 비식별 처리 이력은 프레임 단위로 기록

### 배치 성능
- Spring Boot + Quartz는 **단일 인스턴스 서비스** 배포 (Docker/Pod 미사용, Quartz 클러스터 미적용)
- Quartz 기반 1건/분 처리. ai-server GPU 자원 모니터링 포인트 확보
- **ai-server(YOLO/SAM2)는 다중 인스턴스 수평 확장 가능** — GPU Worker 별도 프로세스로 운영
- 배치 실패 시 재처리 정책 (최대 재시도 횟수, 실패 알림)

### DB 공유 주의
- MNG_* 공유 테이블 변경 시 **Flyway 마이그레이션 전 관제서버팀 협의 필수**
- 관제서버 소유 MNG_* 스키마 변경 시 Hibernate validate 모드에서 기동 불가 — 변경 알림 프로세스 필요
- JPA `ddl-auto=validate` 고정. 엔티티 수정 시 Flyway migration 동반 작성

### Self-evolving rules (Claude 특이)
- `rule-injector.sh`가 `.claude/rules/learned-*.md`의 LEARNED 블록을 키워드로 주입
- `mistake-recorder.sh`가 빌드/테스트 실패 시 feedback memory 자동 기록
