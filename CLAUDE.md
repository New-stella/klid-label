# 학습데이터 저작도구 워크스페이스

> 이 파일은 워크스페이스 루트에 위치합니다.
> 팀 공통 규칙은 ~/.claude/rules/ 에 있으므로 여기엔 이 프로젝트 전용 내용만 작성합니다.

## 프로젝트 개요
- **목적**: AI 기반 지방정부 CCTV 관제지원시스템(2차)의 학습데이터 저작도구 — 영상/이미지 라벨링, 검수 워크플로우, 비식별화, 외부 생성 메타데이터 검토
- **주요 도메인**: 사용자/권한, 마킹(자동/수동 이벤트 식별), 배치 파이프라인(비식별→마킹→VLM 콜백→마킹위치 프레임 추출→오토라벨링), 라벨링, 검수(REVIEWER 배정), 비식별화, 버전관리(DB 스냅샷), 데이터 증강(새 영상), 포털(데이터마트 Load)
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

- 전체 회귀는 **backend 8,134 테스트(Testcontainers 포함, 결과 XML 898) 약 8분 24초 + frontend 4,264 테스트(511 파일) 약 38초 = 합계 9~10분급**이다(2026-08-28 실측).
  - ⚠ **구 수치 갱신(2026-08-28)** — *"backend 7,230(XML 800) 약 8분 35초 · frontend 3,623(456 파일)"*(2026-08-21 실측)에서 **backend 가 +904 테스트 · frontend 가 +641 테스트** 늘었다. **그런데 소요는 오히려 11초 줄었다** — 즉 이 프로젝트에서 **테스트 수와 소요는 비례하지 않는다**(Testcontainers 기동·클래스 수·실행 순서가 지배한다). 테스트가 늘었다고 시간을 선형 외삽하지 말 것.
  - ⚠ **환경 함정** — 이 저장소를 새 셸에서 열면 `JAVA_HOME` 이 비어 있어 `./gradlew` 가 *Unable to locate a Java Runtime* 로 **즉시 죽는다.** 코드 결함으로 오해하지 말고 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` 를 먼저 붙인다(실측 2026-08-28). 기본값 300초로는 완주하지 못하므로 위 `buildTimeout` 선언이 필요하다.
  - ★**이 수치는 문구가 아니라 라우팅을 바꾼다** — 아래 「단일 셸 명령 상한」 판단의 입력값이다. backend 단독 8분 24초(504초)는 상한 10분(600초)까지 **여유가 96초뿐**이다(2026-08-28 실측 — 08-21 의 515초/85초에서 거의 그대로다). 느린 머신·콜드 캐시에서는 넘길 수 있으므로 한 호출 완주가 실패하면 곧바로 백그라운드 경로로 전환하라.
  - ★★**소요는 한 번의 실측으로 고정되지 않는다** — 같은 머신에서 backend 가 **5분 25초(08-16)** · **8분 35초** · **9분 56초**(둘 다 08-21, 서로 다른 실행)로 나왔다. 마지막 값은 **상한 10분에 4초** 남았다. 원인은 미확인이며 콜드 캐시·부하로 **추정할 뿐 단정하지 않는다.** ⇒ 위 「여유 85초」도 낙관적일 수 있으니, **한 호출 완주를 1순위로 두되 상한에 걸리면 곧바로 백그라운드+폴링으로 전환**한다.
  - `buildTimeout: 1800` 은 그대로 둔다 — 합계 실측(약 570초) 대비 **약 3배 여유**다(구 기재 「4배」는 구 수치 기준이라 함께 갱신). 줄일 이유가 없고 느린 머신·콜드 캐시에서 여유가 필요하다.
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
- **★저작도구와 포털은 서로의 DB 에 접근하지 않는다 (2026-08-31 사용자 확정, 구속)** — 데이터 교환은 **API** 로 설계한다. ⚠ 구 서술 *"외부 채널은 포털 DB 공유"* 는 **폐기**. 상세·현황·철거 상태는 「포털 (외부 채널)」 절이 정본이다(여기에 복제하지 않는다).
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
- `common.datasource.ControlDataSourceConfig` — control EntityManager/TransactionManager (`@ControlRepo`). ⚠ **듀얼 데이터소스는 철거됐다 (2026-08-31)** — 상호 DB 미접근 확정으로 `PortalDataSourceConfig`·`@PortalRepo`·복제 워커·발신함·Quartz 잡·메트릭이 **코드에서 제거**됐다. **되살리지 말 것.** ⚠ `LS_META_REPL_OUTBOX` **테이블 정의는 남아 있다** — 드롭 마이그레이션을 분리했고(되돌릴 수 없는 작업) 읽는 코드가 0건이라 무해하다
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
| `JWT_SECRET` / `JWT_ISSUER` | JWT 검증 | dev/stg/prd |
| `DEIDENTIFY_API_URL` | 비식별 서버 | dev/stg/prd |
| `AI_SERVER_URL` | ai-server 내부 주소 | 전체 |
| `STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH` | 저장 경로 | 전체 |
| `VITE_API_BASE_URL` | FE API 주소 | FE 전체 |

⚠ **`PORTAL_DB_HOST/PORT/NAME/USERNAME/PASSWORD` 와 `META_REPLICATION_ENABLED` 는 2026-08-31 에 제거됐다** — 상호 DB 미접근 확정으로 읽는 코드가 없다. 옛 반입물에 남아 있어도 **되살리지 말 것**(반입 설정 템플릿에도 같은 경고가 있다).

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
| **관리자** | **`ADMIN`** | **사용자 관리(역할 지정) · 연동 서버 주소 · 파일 업로드 · 산출물 가져오기 · 관리자 패스워드 교체 · 위험 작업.** ★**검수자 권한을 계층으로 물려받는다**(`ROLE_ADMIN > ROLE_REVIEWER`) — 검수·배정 업무를 그대로 수행하므로 겸직을 위해 계정을 둘 가질 필요가 없다 |
| 검수자 | `REVIEWER` | 시스템 설정(배치·추론·정밀도·비식별) · 라벨 마스터 · 프리셋 · 이벤트유형 · 비식별 신고 관리, 작업자 배정·재배정·배정 이력 조회, 검수 승인/반려. ⚠ **사용자 관리는 이 역할에 없다**(관리자 소유) |
| 라벨링 작업자 | `WORKER` | 라벨 수정·검수 제출 |
| 포털 회원 | `PORTAL_USER` | 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 기간 내 다운로드. **본인 자산(이미지/영상) 업로드 + 수동 라벨링(BBOX/POLYGON)** — ADR-013 예외(2026-07-17). 오토라벨링·SAM2·키포인트·검수·버전관리는 여전히 없음. **메타·이벤트 어노테이션 수정·추가는 제공**(2026-08-26 확정 — 포털 전용 저장소 적재·단방향) |

> ### ★역할 4종 + 계층 (2026-08-28 사용자 확정, 구속 · `ADR-055`)
>
> ⚠ **구 서술 폐기** — *"시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한은 REVIEWER에 통합되어 있다"* 는
> **더 이상 사실이 아니다.** 그 결정(`ADR-003`)은 `ADR-055` 가 **supersede** 했다. 되살리지 말 것.
>
> ```
> 역할 4종: ADMIN · REVIEWER · WORKER · PORTAL_USER
> 계층: ROLE_ADMIN > ROLE_REVIEWER   (한 단계뿐)
> ```
>
> - ★**계층은 「관리자 → 검수자」 한 단계뿐이다.** `WORKER` 를 넣으면 작업자 전용으로 열린 자리에
>   관리자·검수자가 흘러들고(`hasRole('WORKER')` 2곳 실재), `PORTAL_USER` 를 넣으면 채널이 달라
>   외부 채널 사용자가 내부 권한을 얻는다. 회귀 가드 `AC-125`.
> - **계층 덕분에 기존 `hasRole('REVIEWER')` 99곳을 하나도 바꾸지 않는다.** 배선 지점은
>   `common/security/RoleHierarchy` 한 줄이다(그 빈이 원래 있었는데 값이 비어 있었다 —
>   `ADR-003` 이 A안을 기각할 때 이 사실을 몰랐고, 그것이 반전의 근거 하나다).
> - **자가부여(`POST /v1/auth/role-claim`)는 관리자 부트스트랩 전용**이다 — `ADMIN` 이 0명일 때만
>   열리고 부여 역할은 `ADMIN` 고정이며, 한 명이라도 생기면 닫힌다. ⚠ 「0명일 때만」은 2026-08-04 에
>   **사용자가 명시적으로 거부**하고 「항상 자가부여」를 골랐던 형태다. 그때는 좁히면 검수자가 될 길이
>   없었고 지금은 **관리자가 역할을 부여**하므로 전제가 바뀌었다. **세 번째로 뒤집지 말 것.**
> - **역할이 없는 INTERNAL 진입자는 작업자로 자동 등록**된다(조건부·원자 upsert, 사용자당 사실상 1회).
>   ⚠ 이미 부여된 역할을 **덮어쓰지 않는다.** 부수 이득으로 「배정하려면 대상자가 먼저 자가부여를
>   해야 한다」는 제약이 풀린다. 회귀 가드 `AC-1016`.
> - **마지막 관리자의 강등·비활성은 거부**한다(409) — 0명이 되면 부트스트랩 창이 다시 열린다.
>   ⚠ 대가로 관리자를 전부 잃으면 **저장소 직접 수정이 유일한 복구**다. 판정은 조회 후 UPDATE 가
>   아니라 **조건부 UPDATE/잠금**이어야 한다(두 관리자가 서로를 동시에 내리면 둘 다 통과한다).
>   회귀 가드 `AC-124`.
> - **관리자 패스워드는 사라지지 않는다** — 최초 부트스트랩 1회와 **관리자 페이지 진입 유효창**에
>   계속 쓰인다. 유효창은 역할을 올리지 않고 `actor.sub()` 에 바인딩돼 **감사가 개인 단위**로 남는다.
>   ⚠ *"공유 패스워드는 추적이 안 된다"* 를 근거로 쓰지 말 것.
> - **관리 메뉴는 역할로 가른다** — 검수자에게는 관리자 항목이 보이지 않는다. `Lnb.tsx` 가 못박았던
>   *"유효창을 메뉴 노출 조건으로 쓰지 않는다"* 는 **결론은 그대로**이나(노출 조건으로 삼으면 들어갈
>   길이 사라진다) **가르는 축이 역할로 바뀌었다.**
> - ⚠ **배포 직후 `ADMIN` 은 0명**이라 누군가 부트스트랩으로 최초 관리자를 만들 때까지 관리 기능이
>   잠긴다. 운영 런북에 그 절차와 복구 SQL 이 필요하다.
> - ⚠ **검토했으나 기각된 안 — 사용자 사전 등록 명부.** 인계 토큰에 이메일이 없고 이름은 유일하지
>   않으며 계정 표시값은 요청 바디로 오는 **위조 가능한 값**이라 접속자와 명부 행을 이을 **매칭 키가
>   없다.** 자동 등록이 그 문제를 통째로 없앤다. **되살리지 말 것.**
>
> UI 호칭은 검수자를 '검수자'로 통일하고, 관리 화면 URL 은 관리자 소유가 `/admin/*`,
> 검수자 소유가 `/manage/*` 로 갈린다.

## 주요 비즈니스 규칙

### 인증·진입
- 저작도구는 **독립 로그인 UI 없음** — 관제서버(내부) / 포털 서버(외부)가 발급한 JWT 토큰을 인계
- **★★관제 인계 JWT 실측 계약 (2026-09-10 재실측 — 이것이 정본)**: 246 관제 웹에 **실제 로그인한 브라우저**의 저장소를 디코드하고 관제 번들의 토큰 저장 코드(`saveTokens`)로 교차 확인했다. 관제 admin 로그인 실토큰 = **`{"alg":"HS512"}`** · payload `sub="admin"`(**로그인 아이디 문자열**) · **`iss`·`channel`·`role` 없음** · `userId`·`userNm`·`authority`(`SYSTEM_ADMIN` 등 관제 역할)·`sessionId`·`type="access"`·**`sessionExpAlarm`(팝업 임계 분, 현재 5)**·**`sessionTime`(수명 분, 현재 30)**·`eventDate`·`rawVideoDate`·`inaccessible_menus`·`userOgCd`·`userSido`·`userSgg`·`iat`·`exp`(=iat+**30분**). refresh 토큰도 HS512(`type="refresh"`, 같은 `sessionId`, 수명 7일).
  - **저장 (관제가 쓴다)**: `localStorage['klid-jwt-token']` = access 원문 · `localStorage['tokenInfo']` = JSON(access·refresh 토큰 + 클레임 파생 11필드, `expiresAt` 은 밀리초) · `localStorage['userNm']`. 로그아웃은 `tokenInfo`·`klid-jwt-token` 삭제. ⚠ `sessionStorage['label-studio-auth-token']` 키는 현재 시스템에 없다(1차 잔재).
  - **갱신 API**: `POST /api/account/auth/refresh` · 헤더 `x-access-token` 에 **refresh 토큰** · 응답 `{error:0, data:{session_token, refresh_token}}`. refresh 도 교체되고 옛 access 는 만료 전까지 유효. 관제 탭은 요청 직전 ≤10분이면 선제 갱신하고, 만료 `sessionExpAlarm` 분 전에 연장 팝업을 띄운다. 저작도구도 같은 시나리오를 따른다 → `CO-20260910-관제향-토큰갱신-세션연장`.
  - **백엔드 수용(실측 `/me`=200)**: 서명 검증 통과 · `iss` 부재 → 허용 · `channel` 부재 → INTERNAL · **비숫자 `sub` → `userId` 클레임으로 `LS_ACNT_USER` 조회 → userNo(9001) → ADMIN.** ⇒ `ADR-063` 의 비숫자 sub 경로(userId 조회·미등록 진입자 userNo 발급·`userNm` 이름 폴백)는 **관제 진입마다 쓰이는 실사용 경로**다.
  - ⚠ **구 서술 폐기 — 2026-09-05 「실측 계약」** *"HS256 · sub=\"9001\"(숫자) · iss=klid-auth · role·channel·name 있음 · 만료 1시간"* 과, 그것을 근거로 한 *"`ADR-063`·`CO-20260904/05` 의 가정은 틀렸고 비숫자 sub→userId 로직은 한 번도 발동하지 않는 휴면 코드"* 는 **둘 다 틀렸다.** 그때 디코드한 값은 **저작도구 개발 로그인(`DevLoginPage`)이 같은 키 `klid-jwt-token` 에 쓴 토큰**이었을 가능성이 높다 — 그 기록이 함께 적은 부속 키 `klid-user-id`·`klid-authority` 는 `DevLoginPage` 만 쓰는 키이고 관제 번들에는 없다. **2026-09-04 의 원래 가정(sub='admin'·iss 없음·channel 없음·이름 userNm)이 맞았다.** 비숫자 sub 경로를 「휴면」이라며 걷어내지 말 것 — 걷어내면 관제 진입자 전원이 무권한이 된다.
  - **되풀이 방지**: 토큰 계약을 실측할 때는 **관제 로그인 직후** 저장소를 디코드하고, 개발 로그인이 같은 키를 덮어쓰지 않았는지(`tokenInfo` 동반 여부) 먼저 본다. 상세: 메모리 `control-jwt-actual-contract`.
  - ⚠ **2026-09-05 "관제→저작도구 로그인 루프"의 진짜 원인은 토큰이 아니라 인프라**였다 — apache RO 단일파일 마운트 stale(`/label-studio/api` 404, `docker restart apache` 로 해결) + 옛 placeholder FE. 브라우저 토큰 만료도 겹쳤다.
- **★토큰 인계 수단은 채널마다 갈린다 (2026-08-27 · `ADR-012` 개정, 구속)** — 관제 채널은 **동일 도메인 운영**이라 브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 를 전달받는다(기존 그대로). **포털 채널은 포털이 저작도구 프론트를 자기 화면에서 런타임 실행하는 임베딩(`INT-013`)이라 그 전제가 성립하지 않는다** — Host 가 주입한 인계 창구(토큰 획득·갱신·인증 실패 통지·활동 통지)로 토큰을 얻어 **`x-access-token` 헤더**로 싣고(Bearer 미사용), access token 은 **Host 메모리에만** 둔다(브라우저 저장소 미사용). 두 채널 모두 URL 쿼리 파라미터(`?token=`) 방식 미사용.
  - ⚠ **구 서술 폐기** — *"관제서버와 동일 도메인 운영 → 브라우저 스토리지 공유로 JWT 전달"* 을 **전 채널 규칙으로 읽지 말 것.** 저장소나 그 스냅샷을 직접 읽으면 Host 가 토큰을 갱신한 시점 이후로는 **이미 무효가 된 토큰**을 붙잡게 되고, 수십 분을 한 화면에 머무는 저작 화면은 그 파손을 반드시 겪는다(포털이 비협상 조건으로 제시).
  - **해법은 인계 계층을 획득 창구 뒤로 추상화하는 어댑터 한 겹** — 그러면 관제(저장소)·포털(메모리) 두 Host 를 호출부 분기 없이 지원한다. ⚠ 개정 전에는 *"인증 진입 경로의 코드 변경이 없다"* 가 전제였으나 그 전제는 폐기됐다.
  - ⚠ **코드는 아직 미반영이다** — `tokenIngress` 가 브라우저 저장소를 직접 읽고 기본 전략도 그것이며, 요청 헤더는 전 경로 `Authorization: Bearer` 다. `x-access-token` 은 레포에 있으나 **방향이 반대**(저작도구 → 관제 outbound 통지 헤더 + 로그 마스킹)이고 inbound 수용은 0건이다. Module Federation 설정 자체도 없다.
- 두 채널 모두 **동일 JWT 발급 서버** — 단일 검증 로직(`JwtAuthenticationFilter`)로 처리
- 토큰 `role` + `channel` 클레임으로 권한 분기 (`@PreAuthorize("hasRole('REVIEWER')")`)
- 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트
  - **★관제 채널은 만료 전에 연장한다 (2026-09-10 사용자 확정 · `CO-20260910-관제향-토큰갱신-세션연장`)** — 관제는 저작도구를 **새 탭**(`window.open(origin+'/label-studio','_blank')`)으로 열어 관제의 연장 팝업이 우리 탭에서 돌지 않고, 관제 토큰 수명이 30분이라 진입 시 복사한 토큰으로는 30분마다 튕긴다. 그래서 관제 탭과 **같은 시나리오**를 따른다: 매 요청 `klid-jwt-token` 현재값 사용 · **앱 전역**(셸 밖 라벨링 캔버스 포함) 1초 감시와 `sessionExpAlarm` 분 전 연장 팝업 · 요청 직전 ≤10분 선제 갱신 · 401 시 갱신 후 1회 재시도 · 갱신 결과를 관제와 **같은 키·형식으로 저장** · `storage` 이벤트로 다른 탭의 갱신·로그아웃 추종.
    - **★관제 계정 호출은 저작도구 WAS 경유다 (2026-09-10 사용자 확정)** — 브라우저는 관제를 직접 부르지 않고 저작도구 API(`POST /v1/auth/control-tokens` 갱신 · `DELETE /v1/auth/control-session` 로그아웃)를 부르며, 저작도구 서버가 관제 계정 창구로 중계한다. 관제 주소는 **관제 통지 수신처 설정을 재사용**하되 통지용 클라이언트(통지 토큰·통지 on/off)와는 **분리**한다. ⚠ 같은 라운드에서 **`type=refresh` 토큰의 저작도구 API 인증을 거부**하도록 막았다 — 관제 refresh 토큰(7일)이 같은 비밀키로 서명돼 그대로 우리 API 자격증명이 되던 구멍이다.
    - **팝업은 관제 세션 팝업과 완전히 같다** — 버튼 「로그아웃」(관제 로그아웃 API → 토큰 키 삭제 → 관제 로그인, 관제 탭도 함께 로그아웃)·「로그인 연장」 둘뿐, 닫기·X·ESC·배경 클릭 없음. ⚠ 같은 날 「닫기+로그인 연장」으로 정했다가 사용자가 뒤집었다 — **세 번째로 되돌리지 말 것.**
    - **실패 결말**: 관제가 갱신을 **거절**(`error≠0` — 다른 기기 등에서 서버 세션이 끊김)하면 **즉시 로그아웃**(단 저장소를 재확인해 다른 탭이 새 토큰을 저장했으면 그것을 쓴다) · 네트워크·5xx 일시 장애는 현재 토큰으로 계속 · 401 뒤 실패는 즉시 로그아웃 · 강제 로그아웃은 미저장 확인 없이 이동(미저장 편집이 있으면 팝업에 경고). ⚠ 저장 형식이 한 글자라도 다르면 **관제 탭이 깨진다.** ⚠ 구 서술 *"관제 채널은 저장소를 읽기만 하고 저작도구가 쓰지 않는다 · 내부 채널에는 갱신 창구가 없다 · 저작도구는 세션을 연장하지 못한다"* 는 **폐기**. 포털 채널(Host 메모리 · 저장소 미사용)은 **무변경**.
  - ⚠ `RelayAdminViewPage` 는 저작도구 뷰가 아니라 **중계서버 관리자** iframe 화면이다(외부 시나리오 문서의 오기).
- **외부 시스템 양방향 통합(M2M) deprecated**: 관제서버/외부 학습데이터 시스템과의 송수신 API 및 M2M 인증 인프라는 미운영. 재구축 시 별도 설계 필요.
- **예외 — 저작도구 → 관제서버 단방향 outbound 완료/수정 통지**: 영상 단위 작업의 검수 완료 시 `TASK_COMPLETED` 이벤트, 검수 완료 후 라벨/메타 수정 시 `TASK_MODIFIED` 이벤트를 관제서버 inbound SPI 로 push (비동기). 동일 작업 ID(=`LS_DATA_RAW.RAW_SN`) 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신. 양방향 M2M 인증 인프라는 부활하지 않으며, 본 통지는 인계 토큰 또는 IP 화이트리스트로 보호.

### 배치 파이프라인 (인증 불필요)
- **★영상 적재는 관제가 인입 원장에 직접 INSERT 하고 저작도구가 그것을 폴링한다 (ADR-042 — 적재 주체 반전. 구 서술 "공유 DB(MNG_*) READ 스캔" 폐기)**: 관제서버가 **저작도구 소유 테이블 `LS_DATA_INGEST` 에 평면 1행을 직접 INSERT**(`PRCS_STTS_CD='PENDING'`)하고, 저작도구 주기 배치(`ControlTrainingVideoScanJob`)가 **미처리 인입 행을 폴링**해(`LsDataIngestRepository.findPendingReadyForPolling`) `TrainingVideoIngestService` 가 `LS_DATA_RAW` 로 적재한다(비식별 선두). inbound 연동 API·M2M 인증은 미사용.
  - ⚠ **구 서술은 사실과 다르다 (2026-08-16 코드 실측 정정)** — *"관제서버가 공유 DB에서 영상을 학습용으로 설정하면 … 픽업해 적재. 공유 DB(MNG_*) READ 기반"*. **`MNG_*` 공유 테이블은 실제로 DROP 됐고**(`V167`) 회귀 가드 `MngControlMasterTableRemovalTest` 가 JPA 매핑·타입 참조·실행 SQL 참조 **각 0건**을 고정한다. 그 서술대로 이해하면 존재하지 않는 테이블을 읽는 설계를 하게 된다.
  - ⚠ **이 드리프트는 `SEQ-001`·`REQ-019`·`ERD-024` 에도 같은 형태로 복제돼 있었다** — 한 결정이 층마다 따로 적히는 이 저장소의 반복 패턴이다. **내부 파이프라인 1차 적재는** 포털 사용자 업로드를 사용하지 않는다 — 포털 자산 업로드(ADR-013 예외, 2026-07-17)는 내부 파이프라인·데이터마트와 갈라진 별도 경로다 — ⚠ **「전용 테이블」 축은 폐기**(`ADR-058`, 2026-09-02). 지금은 같은 원장에 앉되 출처 판별자로 가른다. 구 '관리 화면 자체 업로드(TUS)' 방식은 폐지(코드 정리 후속)
- **파이프라인 순서**(구현됨): ⭐비식별화(전체 영상, 적재 직후 선두 자동) → 마킹(자동/수동, **비식별 영상 대상**) → VLM 시계열(콜백 비동기) → FFmpeg(**마킹 위치 기반** 원본+비식별 2벌 추출) → YOLO(**원본만** 실행, 비식별본 결과 공유) → SAM2 → 트랙 보간
  - 단계 순서는 **선언적 파이프라인**(`batch/pipeline/{BatchStep,BatchContext,BatchPipeline,BatchPipelineConfig}`)에서 관리. `BatchPipelineConfig` 의 `List.of(...)` 한 곳에서 재배치 가능 — pre-marking=[DEIDENTIFY], post-marking=[MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE]. 각 단계는 `isEnabled(ctx)` 조건부 실행 지원(dev 토글). (구 순서 '마킹(원본) 트리거 → VLM → 비식별 → 프레임추출' 폐지 완료)
- **마킹 단계**: **비식별화 완료 후** 작업자가 **비식별 영상**에서 자동/수동 마킹. 자동=**프레임 간격**(intervalFrames) 기반, 수동=키보드 단축키로 이벤트 시점 마킹. 마킹 결과는 **`frame_policy` 로만** VLM 위탁에 반영된다(⚠ 구 서술 "이벤트명 + 영상경로 + marks 배열을 VLM 에 전달" 은 **폐기** — verify 규격에 그 필드들이 없다. 아래 verify 절). **마킹 완료 시 MarkingCompletedEvent → MarkingBatchBridge(AFTER_COMMIT) → 잔여 배치(VLM→프레임추출→오토라벨링) @Async 시작**. 구현된 흐름: 관제가 `LS_DATA_INGEST` 에 직접 INSERT → 주기 배치가 미처리 인입 행 폴링(`ControlTrainingVideoScanJob`) → `TrainingVideoIngestService`가 `LsDataRaw` 적재(PENDING) → `VideoIngestedEvent`→`IngestDeidentifyBridge`(AFTER_COMMIT)→`AsyncDeidentifyRunner`(@Async)→`DeidentifyStep` 선두 비식별 자동 → 성공 시 `LsDataRaw.dataSttsCd=MARKING_READY` → 마킹 완료(`deIdntfYn='Y'` 가드 통과 시) → 잔여 배치. (post-marking 배치에서 비식별 단계는 제거됨)
- **마킹 화면**: **비식별 영상** 스트리밍(`GET /v1/videos/{rawSn}/stream`, HTTP Range 지원 — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) + 배속 설정(0.25x~4x) + 키보드 단축키(Space: 마킹, Del: 삭제, Enter: 완료). 마킹 중 비식별 누락 발견 시 **비식별 신고** 가능(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report` — 구현됨)
- **★VLM·KPST 위탁은 논블로킹 제출이다 (2026-08-03 실측 정정 — 구 "동기 호출(45s 타임아웃)" 서술 폐기)**: `VlmTimeseriesStep` 은 `.block(45s)` 로 파이프라인 스레드를 붙잡지 않고 **`subscribe()` 로 제출만 개시**한다(Phase C-1). 구 방식은 외부가 느려지면 core 2 짜리 배치 풀이 통째로 마르고 CallerRuns 역압이 호출 스레드까지 물었다. **KPST 비식별 제출도 대칭**으로, WAITING 원장 행을 선커밋하고 `createProject` 를 비동기 디스패치한다(Phase C-2).
  - **스텝이 확정적으로 말하는 사실은 "제출을 개시했다" 뿐**이다. 수락(ACK) 여부는 완료 핸들러가 `LS_BATCH_PROC_LOG`·원장에 비동기 기록하고, 그 기록은 `publishOn` 이 아니라 **명시적 디스패치**(`SubmitSignalDispatch`)로 전용 풀에서만 실행한다 — `publishOn` 은 풀 포화 시 거부가 **시그널을 나른 reactor-netty 이벤트 루프**로 흘러 JPA 쓰기를 이벤트 루프에서 실행시킨다.
  - **★아무 신호도 없으면 회수기가 집는다 — 단 회수기는 축마다 다르다 (2026-09-03 코드 실측 정정)**: 시계열은 **전용 미결 스위퍼**(`VlmSubmitPendingSweeper`)가 집어 **재위탁**하고, **비식별에는 전용 스위퍼가 없다** — **폴링 잡이 ACK 대기 유예 만료로 회수**해 실패 코드(`KPST_ACK_MISSING`)로 원장을 마감한다(**재위탁하지 않는다**). ⚠ **구 서술 폐기** — *「미결 스위퍼가 회수한다(KPST 대칭)」·「스위퍼가 유일한 회수 경로다」*. **제출 축(논블로킹 선커밋)은 대칭이 맞으나 회수 축은 아니다** — 위 줄의 대칭 서술까지 함께 지우지 말 것. ⚠⚠ **구 서술 폐기 — 앞 절은 참인데 결론이 거짓이었다 (2026-09-03 재정정, 코드 실측)**: *「비식별 쪽 회수는 「우리가 ACK 를 관측하지 못했다」는 뜻이지 「외부에서 실패했다」가 아니므로 **영상 비식별 상태를 실패(`F`)로 내리지 않는다**」* 는 **사실과 다르다.** 그 회수는 확정 실패와 **같은 종결 경로**를 타 **영상을 `'F'` 로 내린다.** 영상 상태를 건드리지 않는 것은 **`KPST_SUBMIT_CANCELED`**(호출자 트랜잭션 롤백 — **외부로 나간 것이 없다**) 하나뿐이고, 코드가 그 구분을 명시한다 — *「ACK 유예 회수에 맡기면 그 종착이 `'F'` 라, 실패한 요청이 3분 뒤 영상을 차단 상태로 만든다」*. **`ACK_MISSING` 을 따로 둔 이유는 영상 상태가 아니라 운영 조치다** — KPST 쪽에 프로젝트가 실제로 생성돼 있을 수 있어 사람이 외부 상태를 확인해야 한다. ⚠ 세 종결 코드(`SUBMIT_FAILED`·`ACK_MISSING`·`SUBMIT_CANCELED`)가 **전부 같은 조건부 UPDATE 로 `POLL_STTS_CD='FAILED'` 를 세우므로** 「같은 종료값, 다른 결과」 구조는 실재하나 **예외는 취소 종결 쪽이다.** ⚠ 앞 절(「관측하지 못했다는 뜻이지 외부 실패가 아니다」)은 **그대로 참이니 함께 지우지 말 것** — 그 구분이 나타나는 자리가 영상 상태가 아닐 뿐이다. in-memory subscription 은 노드가 죽으면 통째로 사라져 ACK 도 실패 신호도 오지 않고, **실패 행이 없어 재시도 큐·실패 회수기가 집지 못한다** — 그래서 **각 축의 이 회수 경로가 유일하다**. 여러 노드가 함께 뜨는 Active-Active 형상에서 같은 후보를 두 번 재위탁하지 않도록 처리 전 **조건부 UPDATE 로 원자 클레임**한다(Quartz 클러스터링은 트리거 중복만 막는다).
  - ⚠ **미결 임계는 두 개이며 하나로 덮으면 정상 위탁을 뺏는다**: **ACK 창**(`stale-timeout-minutes`, 기본 30분 — 원장 `ISSUED`, 수락 응답조차 못 본 건) / **콜백 창**(`callback-timeout-minutes`, 기본 360분 — 원장 `ACCEPTED`, 벤더 분석은 영상 길이에 따라 수십 분 걸린다). 두 창을 구분하는 근거는 완료 핸들러의 `ISSUED → ACCEPTED` 전이(`VlmSubmitOutcomeRecorder`)이며, 그 전이가 없으면 구분이 성립하지 않는다.
  - 결과 상세는 VLM 서버가 콜백(`POST /v1/vlm/callback`)으로 별도 전송 → `VlmResultService` 가 `LS_DATA_META` 적재(`vlm.description` — 묘사 축만. 두 축 다 이벤트 어노테이션 초안으로도 간다 — 아래 적재 축 절) + 검수큐(`LS_DATA_META_REVIEW`) 진입은 `vlm.description` 1건만 (아래 verify 절)
- **★외부 시계열 위탁은 묘사(`describe`) + 추가 질문(`describe-sub`) 두 창구다 (2026-08-24 사용자 확정, 구속 · `ADR-051` — 구 `verify` 단일 규격 폐기)**: 확정 계약은 **KLID 연동 API v1.1.0**(`docs/연동규격서/video_vlm_klid_api_v1.1.0.pdf`)이며 우리가 호출하는 것은 **`POST /v1/videovlm-klid/describe`**(장소·환경·상황 서술)와 **`POST /v1/videovlm-klid/describe-sub`**(발생 여부와 근거 서술) 둘이다. **판정 창구 `POST /v1/videovlm-klid/verify` 는 연동하지 않는다** — 그 창구만 제공하는 `detected`·`accuracy` 가 우리 확정 경로(화면 확정·산출물·데이터마트) 어디에도 쓰이지 않기 때문이다.
  - **★적재 축은 창구마다 다르다 (2026-08-25 사업 담당 회신으로 재확정 — 구 「답변 축」 서술 폐기)**

    | 어노테이션 경로 | 조달처 |
    |---|---|
    | 시계열 메타 `vlm.description` | 묘사 전문 — 검수큐 진입·export 조달 **불변** |
    | `event.caption.c1.cot["1단계"]` | 묘사 전문에서 **「상황」 라벨 줄만** 파싱 |
    | `event.caption.c1.caption_text` | 추가 질문 응답 서술 |
    | `event.question` | **저작도구가 보관하는 검증 이벤트 유형별 질문 문구**(`LS_VRFC_EVNT_QSTN`) — 마킹에서 고른 질문이 1순위, 없거나 그 유형 소속이 아니면 **그 유형의 첫 번째** |

    `answer`·`evidence`·CoT 2단계 이후는 **자동으로 채우지 않는다**(사람이 확정할 공란).
    `LS_DATA_META` 는 `(RAW_SN, META_KEY)` 유니크라 두 축이 같은 키를 쓰면 한쪽이 유실된다.

    ⚠ **구 서술 폐기** — *"추가 질문 → 이벤트 어노테이션의 **답변 축** 초안"*. 그건 2026-08-24 확정이었고
    **하루 만에 뒤집혔다.** 같은 축이 이틀 사이 두 번 뒤집혔으므로(캡션·CoT → 답변 → 다시 캡션·CoT)
    **세 번째로 되돌리지 말 것.**
    ⚠ **질문 문장은 사업자 응답에 실려 오지 않는다** — 사업자 서버가 이벤트별로 관리하며 연동 시스템이
    지정할 수도 없다. 그래서 우리가 보관한다. **위탁 요청에 그 질문을 실을 자리도 아직 없어**, 첫 번째가
    아닌 질문을 고르면 **기록된 질문과 사업자가 실제로 쓴 질문이 달라진다** — 인지·수용한 위험이다.
    ⚠ 마킹 선택값을 읽는 행은 **그 영상의 활성 마킹**이고, 없으면 **최신 마킹 한 건**이다. 두 창구의
    도착 순서가 보장되지 않는데 **마킹을 전이시키는 것은 묘사 축뿐**이라, 활성만 보면 도착 순서에 따라
    기록되는 질문이 달라진다.
  - **콜백 바디에 창구 구분자가 없다** — 창구마다 **별개 request_id** 를 발급하고 원장 채널(`VLM` / `VLM_SUB`)로 역조회한다. 같은 request_id 로 둘을 부르면 결과가 유실된다.
  - **콜백 `results` 는 `{description}` 단일**이고 **실패 콜백의 `error` 는 객체가 아니라 문자열**이다(§2.7). 구 `{code,message}` 객체로 되돌리면 실패 콜백이 전량 400 이라 실패 사실 자체를 잃는다. 판정 항목이 섞여 와도 **무시**한다 — 재전송 3회 뒤 결과가 영영 유실되고 조회 API 가 없어(§5.1), 되받을 수 없는 입구에서의 엄격함은 곧 손실이다.
  - **`frame_policy` 에 `framerate` 를 싣지 않는다** — 규격 §2.5 가 **mode 만 연동 시스템이 지정하고 간격·장수는 서버가 관리**한다고 못 박으며 그 필드 자체가 없다. 실제 조립 규칙은 아래 「`frame_policy` 는 마킹에서 도출한다」 항목이 정본이다. ⚠ `LS_MARKING.FRME_INTV_NOCS` **자체는 그대로 쓰인다** — 자동 마킹이 프레임을 고르는 우리 쪽 설정값이며, 폐기된 것은 그 값을 위탁 바디에 싣는 것이지 그 컬럼이 아니다.
  - **429(동시 처리 한도 32건 초과)는 재시도 대상**이다 — 잠시 뒤 다시 보내면 되는 일시 상태이지 요청이 잘못된 것이 아니다. 다른 4xx(400·415)와 함께 비재시도로 묶으면 이중 위탁으로 호출이 두 배가 된 상황에서 정상 위탁이 확정 실패로 종결된다.
  - **마킹의 위탁 상태 표시는 묘사 축 기준**이다 — 추가 질문 축의 실패는 기록만 남기고 마킹을 실패로 내리지 않는다(주 축이 정상인데 화면이 실패로 보이면 안 된다).
  - ⚠ **구 서술 전량 폐기** — *"위탁 엔드포인트는 `POST /v1/videovlm/verify` 하나이며 콜백 `results` 는 객체 `{accuracy, description}` 다"*, *"`framerate` 는 mode 무관 필수이며 마킹 프레임 간격을 싣는다"*, *"`selected_frames` 상한 8"*, *"벤더가 구 배열을 보내면 관대 파싱 없이 400"*. 되살리면 존재하지 않는 창구·필드로 위탁이 나간다.
  - 이미 적재된 구간 키(`0-8`·`8-16` …)는 **작업 결과라 보존**하며 화면·export 가 계속 읽는다(삭제·숨김 금지). 상세는 [09 VLM 시계열](docs/v2-wiki/09-vlm-timeseries.md).
  - ⚠ **구 서술 폐기(2026-08-24)** — *"`framerate` 는 FPS 가 아니라 몇 프레임당 1장(추출 간격)이므로 자동 마킹의 `LS_MARKING.FRME_INTV_NOCS` 를 싣는다 · 값이 없거나 0 이하면 설정 `vlm.client.frame-policy.framerate`(기본 25) 폴백 · 목서버 상한 `le=240` 제거"* 는 **더 이상 우리 계약이 아니다.** KLID 규격 §2.5 에 `framerate` 필드 자체가 없다(위 절). 그 상한 사고의 기록은 남길 가치가 있으나 **되살릴 동작이 아니다** — 목서버 스키마에서도 필드를 뺐다. ⚠ `FRME_INTV_NOCS` 컬럼과 자동 마킹의 간격 설정은 그대로 쓰인다(우리 쪽 프레임 선택 축).
  - **`frame_policy` 는 마킹에서 도출한다** — 모드를 가리지 않고 마킹 본문의 프레임 인덱스를 `frame_selected` + `selected_frames`(정렬·중복제거·음수 제거, **상한 600** 초과분 절단)로 싣는다. 마킹 모드는 그 인덱스를 **누가 골랐는지**만 가른다(수동=작업자 지정 / 자동=간격으로 자동 선택). 실을 프레임이 하나도 없을 때만 `frame_interval` 로 내린다 — 빈 `selected_frames` 는 규격 위반이다. 마킹 JSON 파싱 실패는 예외가 아니라 `frame_interval` 폴백이고, 요청 조립은 **선커밋 이전**에 끝낸다(선커밋 뒤에서 터지면 상관키만 남은 영구 대기가 된다).
  - **★`event_type` 은 저작도구가 매핑표를 만들지 않고 관제 인입에서 수신한다** — 조달처는 **`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`**(V176 신설). 우리가 아는 것은 `fire`·`fall`·`violence`·`flooding`·`car_accident`·`kidnapping`(`LsDataIngest.VRFC_EVNT_TYPES`)이며, KLID 규격은 여기에 **`smoke`** 를 더한 7종을 지원한다(우리 목록 반영은 미착수 — `smoke` 인입 여부가 관제 문의 대기). ⚠ 창구마다 사용 가능한 목록이 다를 수 있어 `GET /v1/videovlm-klid/events` 로 확인한다. 관제가 이 값을 보내기 전까지는 **dev 업로드 화면의 검증이벤트유형 select** 로 지정한다.
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
  - **metaKey 규격** — `vlm.description`(서술 전문 ≤2000, **검수큐 진입 + 편집 가능**) / `vlm.accuracy`(일치도 `0~1`, **화면 전용 · 과거 적재분 전용 — 판정 창구 미연동으로 새로 생기지 않는다**) / 레거시 구간 키(보존 · 화면에 **읽기 전용 병기**) / `video.*`(ffprobe·인입 기술메타). 조회 `GET /v1/frames/{srcSn}/meta` 응답은 `items`(편집) · **`readOnlyMeta`(신설)** · `technicalMeta` 3목록이며 **기존 두 목록의 필드명·타입·시맨틱은 불변**(추가만). `vlm.accuracy` 수정 요청은 `video.*` 와 **같은 400** 이고 메시지에 요청 키를 echo 하지 않는다.
  - **★`vlm.accuracy` 가 데이터마트·export 에 없는 것은 의도다 (R12)** — 검수큐에 넣지 않으므로 리뷰행을 INNER JOIN 하는 `V_COMPLETED_META` 에 **구조적으로 도달하지 않고**, 학습데이터 export JSON 에도 넣지 않는다. **"마트에 안 나온다"를 결함으로 재보고하거나 뷰를 고치지 말 것.** ⚠ **2026-08-24 이후 이 값은 새로 생기지 않는다**(판정 창구 미연동) — 규칙은 과거 적재분을 대상으로 그대로 유효하다.
  - **★R13 — 승인 영상의 서술이 갱신되면 재검수 + 통지**: `APPROVED` 영상의 `vlm.description` 이 **실제로 달라진 경우에만** 검토행을 `PENDING` 으로 되돌리고(`LsDataMetaReview.reopenForRecheck`) `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` 를 발행한다. **값이 같으면 no-op**(멱등 — 재검수·통지 폭주 방지). 근거: 뷰가 **라이브** `LS_DATA_META` 를 조인하므로 상태를 APPROVED 로 둔 채 값만 갱신하면 REVIEWER 가 한 번도 보지 않은 서술이 그대로 관제로 나간다. `REJECTED` 검토행도 되돌린다(반려 판단은 *바뀌기 전 본문*에 대한 것이라 새 본문에 적용되지 않는다). 신규 적재·미승인 영상은 대상이 아니다.
  - **export `video.vd_description` 조달 순서** — ① `vlm.description` → ② `manual-timeseries`(사람이 직접 쓴 전문) → ③ 레거시 구간 행을 **`start_sec` 숫자순**으로 이어붙임 → ④ **`null`**(빈 문자열 아님 · 지어내지 않음). 판정 단일 원천은 **`dataset/export/json/VlmDescriptionPolicy`** 이며 매퍼·서비스가 재유도하지 않는다. 조달 참여 키라도 **값이 실제로 바뀌어야** export 를 재생성한다 — 무변경 저장이 `v2·v3·v4…` 를 이미지 2벌과 함께 적층하던 결함(CWE-770)의 차단이며, 무변경 저장 자체는 **200 성공 + 통지 발행**이고 재생성 플래그만 생략된다.
  - **알려진 관찰 2건 — 결함 아님 (되돌리지 말 것)**: ①관제 pull API `TaskQueryService.getMeta` 는 `LS_DATA_META` **전 행**을 반환해 `vlm.accuracy`·`video.*` 가 포함된다. R12 가 규정한 축(검수큐 → 뷰 → export)과 **다른 채널**이고 `video.*` 도 이전부터 그랬다 — 좁히려면 **관제 계약 협의가 선행**돼야 한다. ②`LabelContentHasher` 의 `VDSC` 블록은 현재 배포 형상에서 **아무것도 게이트하지 않는다**(해시가 게이트하는 유일한 지점이 `forceRegenerate=false` 분기인데 그 값으로 진입하는 프로덕션 경로가 0건 — 유일 후보 `DatasetExportBridge.onReExport` 가 소비하는 `DatasetReExportEvent` 는 **발행처 0 인 휴면 리스너**). 기록만 되며 `force=false` 경로가 되살아날 때를 위해 **유지**한다.
  - **적대검증 기각 근거 (다시 꺼내지 말 것)**: "R13 이 웹훅 재수신으로 폭주한다"는 지적은 **자동 발동 경로가 전부 막혀** 성립하지 않는다 — 재개 러너(`VlmWithheldResumeRunner`)는 **시계열 메타 0건**일 때만 재위탁하고, 미결 스위퍼는 **미결 원장(`ISSUED`/`ACCEPTED`)** 만 회수하며, 수동 재처리는 **`FAILED` 만** 클레임한다(`BatchTransitionService.tryClaimReprocessFromFailed`). 승인 완료 영상은 이 셋 중 어디에도 걸리지 않는다.
    - ⚠ **단 「승인 완료 영상은 어디에도 걸리지 않는다」는 2026-08-19 이후 조건부다 (`ADR-049`·`API-201`)** — 승인 이력이 있는 영상도 **시계열 묶음에 한해 재수행이 열렸다**. 즉 R13 의 발동 경로가 하나 늘었다. **폭주 근거는 아니다** — ①사람이 눌러야만 발동하고 ②되돌린(clear 한) 묶음만 수락하며 ③R13 자체가 **값이 같으면 no-op** 인 멱등이기 때문이다. 자동 트리거(재개 러너·미결 스위퍼·수동 재처리)가 막혀 있다는 위 서술은 **그대로 유효**하다. ⚠ `AUTOLABEL` 은 이 예외 대상이 **아니다**(라벨을 다시 만들어 승인 스냅샷과 어긋난다).
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
  - **★예외 — 출처유형 비식별 제외 (2026-09-14 사용자 확정, 구속 · `ADR-066`)**: 배포 설정 `authoring.deidentify.excluded-src-types`(env `DEIDENTIFY_EXCLUDED_SRC_TYPES`, 쉼표 복수, **기본 `GENERATED`**)에 든 출처유형 영상은 KPST 위탁 없이 **원본을 비식별 영상 쓰기 위치에 복사**해 비식별 단계를 끝낸다 — 이력 `LS_DEIDENT_PROC_LOG` 성공 행 + **`REQ_KND_CD='EXCLUDED'`(비식별 제외)** → `DE_IDENT_YN='Y'` → `MARKING_READY`. 뒤 단계(마킹 가드·스트리밍·프레임 두 벌·파생·관제 뷰)는 **무변경**이고 신규 상태값·컬럼도 없다.
    - **단계를 건너뛰는 것이 아니다** — 「무조건 자동 실행」은 **단계**에 대해 여전히 참이며 그 단계가 위탁 대신 복사로 끝날 뿐이다. ⚠ 「건너뛰기만 하면 된다」로 되돌리지 말 것: 위 소비처가 전부 `'Y'` + 비식별 이력 최신 성공 경로를 전제한다.
    - **기각한 안(되살리지 말 것)**: ①원본 경로를 이력에 적기만(복사 없음) — 비식별 영상 읽기 허용 base 에 원본 마운트를 넣어야 해 **전 영상** 원본 노출 방어가 약해진다 ②`DE_IDENT_YN` 에 「해당없음」 값 신설 — 소비처 누락 시 조용히 막히거나 원본이 샌다 ③`GENERATED` 하드코딩.
    - **설정 파일 전용**(관리 화면 설정 아님 — `ConfigKeys.ALLOWED` 에 넣지 말 것) · **기동 시 값 검사 없음**(사용자 확정 — INFO 로그 1줄만) · 처리 시점 1회 판정·**소급 없음** · 비우면 전부 위탁 · `PRVC_TYPE_CD` 불변 · 설치 문서가 `ORIGINAL`·`RELAY`·`IMPORTED` 기재를 경고한다.
    - **★회수 지점은 검수다** — 오표기(실제 인물 영상이 `GENERATED` 로 인입)는 **검수자가 검수 중 판단해 기존 비식별 누락 신고 → 외부 재비식별 → 해소**로 회수한다. ⚠ **검수완료 재비식별(`ApprovedRedeidentService`·API-112)은 회수 경로가 아니다** — 이미 `'Y'` 인 영상을 409 로 거부하고 제외 성공 영상은 전부 `'Y'` 다(2026-09-14 설계 라운드 중 이 오해가 ITEM 여러 층에 복제됐다가 정정됐다). 승인 이력 영상은 신고도 412 라 **승인 이후·그 파생본은 회수 경로 밖**(인지·수용).
    - ⚠ 관제 조회 뷰 `DE_IDNTF_YN='Y'` 의 뜻이 「비식별 완료 **또는 제외**」로 넓어진다(구분은 `SRC_TYPE`/`GEN_AI_YN`). 영상 상세 비식별 이력 패널은 표시를 바꾸지 않아 제외 회차가 「비식별 / 배치 비식별」로 보인다(인지·수용).
- 원본 영상과 비식별 영상은 **별도 경로로 동시 저장**
- **오토라벨링**: YOLO/SAM2는 **원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유 (별도 실행 없음)
- YOLO/SAM2는 `AiServerClient`로 호출 (타임아웃 60s + Resilience4j CircuitBreaker). **외부 VLM 은 별개 빈 `VlmClient`**(외부 벤더 직접 호출 · `vlm.client.timeout-seconds` 기본 10s + Resilience4j `vlmClient`)이며 ai-server 를 경유하지 않는다

### 작업 배정
- **REVIEWER가 WORKER에게 배정** (관리자도 계층으로 검수자 권한을 물려받아 배정할 수 있다). ⚠ 구 서술 폐기: *"역할 단일화 — ADMIN 권한은 REVIEWER에 통합"* — `ADR-055` 로 관리자 역할이 분리됐다
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
      - **① 승인 상태를 되돌리지 않는다 — `LS_RAW_DATA_STATUS.REVLT_YN`(재검토여부, `CHAR(1)` Y/N, 기본 `N`) 신설.** `APPROVED→PENDING` 자동 전이는 **채택하지 않았다**: 데이터마트 뷰 **4종 전부**가 라이브 `DATA_STTS_CD='APPROVED'` 로 게이트하므로(V174 주석의 *"뷰 노출 ⇔ 현재 라이브 APPROVED"* 불변식) 상태를 내리는 순간 **이미 통지된 영상의 행이 관제에서 사라져** 「검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다」(2026-07-28 구속)를 정면으로 위반한다. 물리명·도메인은 **행안부 공통표준용어 `재검토여부=REVLT_YN` + 도메인 `여부C1`** 그대로다(신규 약어 생성 아님).
      - **② 수정 시 `TaskModifiedEvent` 발행은 그대로 두고, 내보내는 시점만 옮긴다.** 변경 프레임 목록이 축적돼야 `TASK_MODIFIED` 본문을 만들 수 있기 때문이다. `ControlNotifyDebouncer` 는 **`REVLT_YN='Y'` 인 동안 만료 flush 를 보류**하고, 재승인이 표시를 해제한 뒤 축적분을 내보낸다. (구 설계안 "수정 시 발행 자체를 막는다"는 폐기 — 변경 프레임 목록이 소실돼 통지 계약을 못 채운다.)
      - **③ 재검토 필요 여부는 이벤트가 직접 싣는다** — 발행처 클래스명으로 추정하지 않는다. `exportRegenerated` 가 이미 같은 원칙으로 설계돼 있고 그 축을 확장하는 것이다.
      - **④ 대상·제외는 위 「★대상은 개수가 아니라 성질로 정한다」(2026-08-15 확정)가 정본이다** — 여기에 목록을 복제하지 않는다(복제하면 경로가 늘 때 한쪽만 갱신된다). 판정은 *"사람이 산출물에 들어가는 내용을 바꿨는가"* 하나이며 제외는 넷뿐이다.
      - **⑤ 비식별 신고 해소(resolve)도 이 정책을 따른다**(사용자 확정) — 구 동작 "재승인 없이 강제 재생성 + 완료 통지"는 폐기하고 **재검토 표시만 세운다**. ⚠ **인지·수용한 대가**: 재승인 전까지 관제는 **마스킹 실패가 남은 직전 산출물**을 계속 본다(개인정보 관점에서 불리한 방향임을 알고 선택). 단 **강제 재생성(멱등 우회) 근거는 그대로 유효**하다 — 콘텐츠 해시가 이미지 픽셀을 반영하지 않아 해시가 같아도 디스크의 비식별 이미지는 바뀌어 있다. 재승인 시점의 재생성이 강제 재생성이어야 한다.
      - **⑥ 재승인은 상태 전이가 아니다.** 이미 `APPROVED` 이므로 승인 경로는 `REVLT_YN='Y'` 일 때 `APPROVED→APPROVED` 를 예외적으로 허용한다(그 외 `APPROVED` 출발 전이 차단은 유지). 승인 파이프라인(버전 스냅샷·동결·강제 재생성·통지)을 **그대로 재사용**한다 — 재승인 전용 파이프라인을 새로 만들지 않는다.
      - ⚠ **알려진 한계(이번에 만들지 않음, 사용자 확정)**: **수정만 하고 아무도 재승인하지 않은 영상을 감지·알림하는 장치가 없다.** 그 영상은 `REVLT_YN='Y'` 인 채로 방치되고 관제는 직전 승인본에 고착한다. 스윕 잡·알림 채널·임계 설정이 필요한 별도 기능이다.
      - ✅ **판정 지점 단일화는 완료됐다 (2026-08-12 실측 정정)** — 구 서술 *"`isReviewApproved` 판정이 12곳에 복제돼 있다 … 반전 작업은 이를 단일 판정 지점으로 합친다"* 는 **이미 이행된 과제**였다. 현재 복제 `private` 메서드는 **0건**이고 판정은 `assignment/service/ReviewApprovalGate` 한 곳이며 main 18개 파일이 이를 주입해 쓴다(구 복제 건수도 12가 아니라 **14**였다 — `ReviewApprovalGateTest` javadoc 이 정본). 이 항목을 **다시 작업으로 만들지 말 것.**
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
- **★요청 시 생성 조건 5필드는 전부 필수다 (2026-07-31 확정 · 전송 형태는 v1.3 로 갱신)**: REVIEWER 가 `time`/`season`/`weather`/`terrain`/`severity` 를 입력하며, 하나라도 비면 벤더가 어떤 기본값으로 채울지 우리가 알 수 없어 결과가 비결정적이 되므로 **부분 입력을 허용하지 않는다**(벤더 계약은 "최소 1개"라 **우리가 더 엄격한 쪽이며 의도된 것**이다).
  - **전송 형태 = 최상위 `mtdt` + `prompt` 분리 (v1.3)** — 생성 조건 5항목은 최상위 **`mtdt`** 객체로, 자유 지시문은 별개 최상위 **`prompt`**(문자열 ≤1000, 선택)로 보낸다. **구 객체 형식(`prompt.condition`/`prompt.text`)과 최상위 `condition` 은 계약에서 제외**됐고 `prompt` 에 객체를 실으면 **400 INVALID_PARAMETER** 다. 보관(`LS_DATA_AUG.PROMPT_CN`, V147)도 **분리한 형태**로 해 나간 값과 보관값이 갈라지지 않게 한다. 판정 진실원은 `INT-008`(위탁)·`INT-006`(콜백)이다.
  - ⚠ **구 서술 폐기(2026-08-27)** — *"「생성형 AI API 연동명세서 v1.1」§4.1 `prompt` 로 가공 없이 그대로 전송"* · *"명세가 허용값 enum 을 정의하지 않으므로 서버가 값 자체를 좁히지 않는다"*. **정본은 v1.3(갱신일 2026-08-12)이고 다섯 필드 전부 허용 코드가 닫혀 있다** — `time` DAWN\|DAY\|DUSK\|NIGHT · `season` SPRING\|SUMMER\|AUTUMN\|WINTER · `weather` CLEAR\|CLOUDY\|RAIN\|SNOW\|FOG\|WINDY · `terrain` ROAD\|UNDERPASS\|RIVER\|URBAN\|RESIDENTIAL\|RURAL\|MOUNTAIN\|FOREST · `severity` LOW\|MEDIUM\|HIGH.
  - ✅ **코드 반영 완료 (2026-08-31 실측 — 구 서술 「코드 미반영」 폐기)** — 요청 DTO 가 최상위 `mtdt`(다섯 항목 전부 필수) + 자유 지시문 `prompt`(문자열) 형태로 **이미 v1.3 이다**(`augment/dto/AugmentRequestRequest(mtdt)` · `augment/integration/dto/GenAiJobSubmitRequest`). 회귀 가드 `AugmentRequestContractTest(생성조건_5항목을_고르면_외부전송_mtdt_객체에_그대로_담긴다 · 구계약_객체형_prompt는_400)` 가 그 계약을 고정한다.
    ⚠ **구 서술을 되살리지 말 것** — *"요청 DTO 의 `prompt` 가 아직 객체(`Map`)이고 `mtdt` 는 backend 전체에 0건"* 은 **2026-08-27 시점 기록**이고 지금은 `mtdt` 가 backend 에 **14건 실재**한다. 그 서술대로 이해하면 이미 있는 계약을 다시 만들려 든다.
    ⚠ **`prompt` 의 타입을 `Map` 으로 되돌리지 말 것** — 명세가 *"prompt 값으로 객체를 전달하지 않습니다"* 를 명시하며 객체를 실으면 **400 INVALID_PARAMETER** 다.
    · 환경 배선 서술은 그대로 유효하다 — `authoring.augment.external.mode` 공통 기본값이 `http` 라 **local 은 실배선**이고 dev/stg/prd 만 `noop` 이다("전 환경 noop" 은 낡은 서술).
  - **`AUG_TYPE_CD`(증강 종류)는 생성 조건에서 파생하지 않는다** — 그 값이 유형 판정에 흘러가면 산출물 경로 순회(CWE-22)·`RESL_` 네임스페이스 침범이 열리므로 유형의 단일 원천은 별도 `types[]` enum 이다.
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
- **데이터 소스**: 관제서버 → 데이터마트 → 포털 적재 (관제서버 책임). ★**관제서버와 포털은 API 연동으로 적재한다 — 둘 사이에 DB 공유는 없다** (2026-08-27 사용자 확정, 구속). ⚠ 구 서술 *"포털 **DB** 적재"* 는 **폐기** — 그 문장대로 이해하면 관제가 포털 DB 에 직접 쓰는 구조를 전제하게 된다. ⚠ **구 예외 폐기(2026-08-31)** — *"저작도구 → 포털 DB 단방향 복제(메타 복제 배치)는 별개 축이라 그대로다"* 는 **더 이상 사실이 아니다.** 바로 아래 ★ 절이 그 예외를 닫았다.

> ### ★저작도구와 포털은 서로의 DB 에 접근하지 않는다 (2026-08-31 사용자 확정, 구속)
>
> 데이터 교환은 **API** 로 설계한다. **양방향 약속**이다 — 저작도구가 포털 DB 에 쓰지 않고, 포털도 저작도구 DB 를
> 참조하지 않는다(포털 측은 이미 「포털은 저작도구 내부 테이블을 참조하지 않는다」로 선언해 둔 상태이며 우리가 그것을 확인했다).
>
> - **닫힌 것 = 검수 승인 영상의 동결 메타 단방향 복제 1축.** `@PortalRepo` 사용처가 이 축 하나뿐이라
>   (`PortalDatasetVideoMetaRepository` ← `PortalMetaReplicaWriter` ← `MetaReplicationWorker` ← 발신함)
>   **저작도구가 포털 DB 를 무는 경로는 이것이 전부**다.
> - ✅ **철거 완료 (2026-08-31)** — 듀얼 데이터소스(`PortalDataSourceConfig`·`@PortalRepo`)·복제 워커·발신함·
>   Quartz 잡·메트릭이 **코드에서 제거**됐고, 반입 산출물의 포털 DB 생성·스키마 로드 단계와 `PORTAL_DB_*`·
>   `META_REPLICATION_ENABLED` 설정도 함께 사라졌다. **되살리지 말 것.**
>   ⚠ **`LS_META_REPL_OUTBOX` 테이블 정의는 남겼다** — 드롭 마이그레이션은 **분리**했다(되돌릴 수 없는 작업을
>   한 묶음에 넣지 않는다). 읽는 코드가 0건이라 무해하며, 드롭 시점은 별도 판단이다.
>   ⚠ **승인 트랜잭션에서 발신함 적재만 걷어냈고 동결 스냅샷은 그대로다** — 해시·동결 적재는 그보다 앞에서
>   끝나고 발신함 직렬화는 그 어느 쪽의 입력도 아니었다. `V_COMPLETED_VIDEO` 계약면 무변경을 뷰 대조 시험
>   3종(출력컬럼 이름·순서 일치 · `ACTIVE_Y`만 노출 · 승인영상당 1행)으로 확인했다.
> - **대체 API 가 필요한지는 미확정** — 이 복제는 **쓰기 전용**이라 저작도구에 복제본을 읽는 경로가 **0건**이고, 포털
>   설계에도 받는 테이블이 없다(양측 실측). 즉 **실 소비처가 어느 쪽에서도 확인되지 않는다.** 포털 회신으로 소비처가
>   드러나면 REST 대체 경로를 설계하고, 없으면 대체 없이 철거한다. ⚠ 특히 **이벤트 어노테이션 동결 원문**은 이 복제
>   말고 포털로 나가는 경로가 없다.
> - **철거 순서** — 포털에 「철거 완료」를 통보하기 전에는 포털이 대상 테이블·계정을 지우지 않기로 했다(먼저 지우면
>   워커가 실패해 발신함에 실패가 쌓인다). ⚠ 코드 철거는 끝났으므로 **이제 그 통보를 보낼 수 있다** — 보낸 뒤에야
>   포털이 정리한다.
> - 회신 근거: 저작도구 프로젝트 아티팩트 「[회신] 포털 DB 직접 쓰기 폐기」(2026-08-31).
>
> ### ★★배포가 채널마다 갈린다 — 저작도구는 두 벌이다 (2026-08-31 사용자 확정, 구속 · `ADR-012`)
>
> ⚠ **가장 자주 틀리는 지점이다.** 저작도구 서비스 **하나가 포털·관제 양쪽에 붙는 구조가 아니다.**
> **포털에 붙는 저작도구**와 **관제에 붙는 저작도구**가 **각각 별도로 배포**되고, 각 배포본은
> **자기 별도 PostgreSQL DB 에만 연결**한다. 포털향 DB 는 포털 채널에 맞춰 별도로 구성한다.
> **두 배포본끼리도 직접 이어지지 않는다** — 서로의 DB 를 읽지 않고 서로를 직접 호출하지도 않는다.
>
> **자산 전달 경로 — 중계자는 관제다.**
> ① **관제 채널 배포본**에서 검수 **승인** → **관제로 완료 통지**
> ② **관제가 그 자산을 포털로 전달**(**압축 파일** 형태 예정 — 포털 측 수신은 재개 가능 업로드 +
>    배포 등재·상태 폴링 규격)
> ③ 이후 **포털이 포털 채널 배포본에 자산 수신을 API 로 알린다** — 그 배포본이 포털이 보유한
>    자산을 조회해 작업 소재로 삼는다
>
> ⚠ **포털향 배포본이 관제향 배포본의 DB 나 API 를 직접 물어 승인 자산을 가져오는 그림은 틀렸다.**
> 승인 자산이 포털 쪽으로 흐르는 경로는 위 3단계뿐이다.
>
>
> **두 배포본은 인프라 소유 주체가 갈린다.**
> - **관제 채널 배포본** — DB·NAS 모두 **관제지원 소유**(공유 NAS)
> - **포털 채널 배포본** — **PostgreSQL 은 별도 서버이고 저작도구가 직접 관리**하며,
>   **NAS 는 포털이 관리하는 별도 NAS**다(공유 NAS 아님)
>
> **포털 채널 배포본에는 `ai-server`(YOLO·SAM2 추론)가 붙지 않는다** — 오토라벨링·인터랙티브 분할이 없다.
> AI 파이프라인(추론·외부 시계열 위탁·비식별·배치)은 **관제 채널 배포본에만** 있다.
> ⚠ **외부 증강은 별개 축이다** — `ai-server` 가 아니라 외부 생성형 AI 위탁이며, 포털 업로드 영상 대상
> 제공이 2026-08-26 에 확정됐다(`ADR-013` v10). 「ai-server 미연결」을 「포털에 AI 가 전혀 없다」로 읽지 말 것.
> ⚠ **현재 코드는 단일 배포 시절 형상이다** — 포털 라벨 화면(`PortalLabelService`)이
> `controlTransactionManager` 로 control DB 의 승인 자산을 **직접 읽는다**. 배포가 갈리면 그대로
> 성립하지 않으므로, **이 코드를 근거로 「포털향도 control DB 를 읽으면 된다」고 추론하지 말 것.**
  - ⚠ **구 서술 폐기(2026-08-16 코드 실측)** — *"저작도구는 포털 DB에서 Load"* 는 **사실과 다르다.** 포털 라벨 경로(`PortalLabelService`)는 `controlTransactionManager` 로 묶이고 그것이 쓰는 리포지토리는 **전부 control(저작도구) 데이터소스**다. `@PortalRepo` 를 쓰는 것은 **메타 복제 축 하나뿐**이고(`PortalDatasetVideoMetaRepository`·`PortalMetaReplicaWriter`·`MetaReplicationWorker`) 그 방향은 **저작도구 → 포털 DB 쓰기**(단방향 at-least-once)다. 즉 **포털 DB 는 우리가 읽는 곳이 아니라 내보내는 곳**이다. 그 서술대로 이해하면 포털 화면의 조회 경로를 엉뚱한 데이터소스에서 찾게 된다. ⚠ 같은 오기가 **LogiCraft ITEM 여럿과 승인된 통합시험 시나리오에도 복제**돼 있다(별도 정합 대상). ⚠ **2026-08-31 보정** — 「내보내는 곳」이라는 방향 서술은 여전히 맞으나 **그 내보내기 축 자체가 폐기 확정**이다(위 ★ 절). **2026-08-31 철거 완료** — 지금 실측하면 그 경로가 없다(위 ★ 절).
- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면에 표시
- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 별도 적재, **데이터마트에 정합/반영 안 됨(단방향)**
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음
- **★「미제공」의 축은 외부 서버 연동이다 — 화면 표시·사용자 수정까지 막는 것이 아니다 (2026-08-26 사용자 확정, 구속 · `ADR-013` v10)**: 미제공인 것은 **외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)** 과 **오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·트랙 rename/merge·검수·버전관리** 다. 반면 **데이터마트 로드분의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션은 포털 작업 화면에 표시하고 포털 사용자가 직접 수정·추가할 수 있다** — 그 결과는 **포털 전용 저장소에만 적재**되고 데이터마트·원본 동결본을 수정하지 않는다(단방향).
  - ⚠ **구 서술 폐기(2026-08-26)** — *"오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공"* 은 **VLM 축을 너무 넓게 적은 것이다.** `ADR-013` 본문 실측상 「메타」는 **0회**, 「VLM」은 **2회뿐**(결정 동인 1줄 + **폐기된 구 옵션**의 서술 1줄)이며 현행 justification 의 미제공 목록에는 **VLM 도 메타도 없었다.** 그런데 코드 주석이 *"포털은 VLM/메타 미제공(ADR-013)"* 으로 적어 **결정된 적 없는 제약이 근거처럼** 읽히고 있었다. 그 서술을 근거로 화면 기능을 막지 말 것.
  - ⚠ **SAM2·키포인트 미제공은 그대로다** — 2026-08-03 보안 2차 전수검증 HIGH 판정으로 서버에서 완전 제거된 축이며 이 확정이 그것을 뒤집지 않는다. **사용자가 직접 호출하는 추론 엔드포인트**(SAM2·키포인트)와 **서버간 비동기 위탁**(AI 증강 연동)은 **다른 축**이다 — 후자를 여는 것이 전자의 판정을 뒤집는 것이 아니다.
  - ★**설계가 열렸다 (2026-09-02 사용자 확정, 구속)** — *"시계열 메타는 노출을 해줘야 해"*.
    **다섯 축을 전부 노출한다** — 촬영환경 · 프레임 설명 · 개인정보 판정 · **시계열 메타** · 이벤트 어노테이션.
    `UC-024` step2·4·5 와 `AC-1068` 이 이미 표시·수정·추가·포털 전용 적재를 요구하고 있었는데
    `SCREEN-029` 가 **시계열 메타를 AI 보조(분할·추적·오토라벨)와 한 묶음에 넣어 미노출**로 적고 있었다 —
    **범주 오류**였고 그 화면 사양을 고쳤다(메타·이벤트 어노테이션 패널 신설).
    - **적재처는 포털 전용 오버레이 두 표 신설** — `LS_PORTAL_USER_META`(메타 넷, 내부 원장과 같은 K/V) ·
      `LS_PORTAL_USER_EVNT_ANNO`(이벤트 어노테이션, `jsonb` 구조체). 정의는 `ERD-018`.
      ★**두 표로 나눈 근거는 성격이다** — K/V 축과 구조체 축을 한 표에 섞으면 본문 폭과 조회 모양이 서로를 제약한다.
      어노테이션을 K/V 로 펴는 안은 기각했다(산출 JSON 과 모양이 갈려 내보낼 때마다 재조립이 필요하고 CoT 중첩이 무너진다).
    - ★**`ADR-058` 의 「전용은 한 벌뿐」이 세 벌이 됐다** — **기준이 바뀐 것이 아니라 그 기준에 해당하는 것이
      늘었다.** 판정 기준은 그대로 *"원본을 덮어쓰지 않는 것이 존재 이유인 단방향 오버레이인가"* 다.
      ⚠ 라벨과 같은 이유로 **원장에 합치거나 소유자 구분으로 섞지 않는다** — 구분을 한 번만 잊으면 남의
      오버레이가 정본으로 읽히는 fail-open 이고, 그 누락은 새 조회 경로가 생길 때마다 다시 열린다.
    - **창구 4종 신설** — `API-234/235`(프레임 메타 Load·저장) · `API-236/237`(이벤트 어노테이션 Load·저장).
      저장은 오버레이에만 쌓이며 **원본·데이터마트 동결본을 수정하지 않고 관제 통지·산출물 재생성을 일으키지 않는다**.
    - ⚠ **코드는 아직 0건이다** — 설계만 섰다. 화면 플래그만 켜면 여전히 동작하지 않는다.
- **포털 자산 업로드 (ADR-013 예외, 2026-07-17)**: 포털 사용자가 **본인 이미지(jpg/jpeg/png, 20MB/장, 50장/요청)·영상(mp4/mov/avi, 5GB, TUS 재개 업로드)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON만) 후 본인 데이터(JSON export/원본)를 다운로드한다. 업로드 자산·프레임·라벨과 재개 업로드 세션은 **공용 원장**(`LS_DATA_RAW`·`LS_DATA_SRC`·`LS_DATA_LBL`·`LS_TUS_UPLOAD`)에 적재되며 **포털 채널 전용 테이블을 두지 않는다**(2026-09-02 확정 · `ADR-058` — 아래 ★ 절). 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View 로 흘러가지 않는 것은 그대로이나 **그 근거가 바뀌었다.** ⚠ 구 서술 *"신규 `LS_PORTAL_*` 테이블(V107/V108)로 **완전 분리**된다"* 는 폐기 — 되살리지 말 것. ★**신규 접수는 영상만 받는다 (구속)** — 위 「본인 이미지(jpg/jpeg/png, 20MB/장, 50장/요청)」는 **접수 축에서 폐기**이며 되살리지 말 것. 포털 업로드 화면의 섹션도 **영상 업로드(TUS)·업로드 자산 목록 둘뿐**이다. ★★**이미지 축은 화면·사양에서 전부 제거한다 (2026-09-02 사용자 확정, 구속)** — 시안·화면정의서·사용례·수용기준·부품 사양·API 산문과 예시 어디에도 두지 않는다. **남기는 것은 자산 종류 코드 값역의 이미지 값(ERD 한 곳)과 그 직접 투영인 API 응답 enum 뿐**이다(이미 적재된 행의 판독용 — 지우면 계약이 데이터를 표현하지 못한다). ⚠ **구 지시 폐기** — *"이미 접수된 이미지 자산의 조회·라벨링·내려받기·삭제는 유지되고 … 목록의 이미지 행을 잔재로 오인해 지우지 말 것"* 은 **더 이상 사실이 아니다.** 배포 전이라 접수된 이미지가 실제로 0건인데 그 규칙이 시안에 예시 행으로 나타나 검토자에게 "이미지 업로드가 있다"로 읽혔고, **사용자가 세 차례 지적**해 뒤집혔다. **되살리지 말 것.** ⚠ 반면 **「프레임 이미지」는 영상에서 뽑은 프레임이라 이 축과 무관하다** — 낱말로 훑어 함께 지우지 말 것(전수 조사에서 26개 ITEM 이 그 오탐으로 걸렸다). 결정 기록(ADR)·변경 이력도 **소급 삭제하지 않는다.** 오토라벨링·SAM2 인터랙티브·키포인트·검수·버전관리는 미적용. ★**단 업로드 영상에 한해 이벤트구간 마킹과 AI 증강 연동을 제공한다**(2026-08-26 사용자 확정 · `ADR-013` v10 — 2026-07-17 자산 업로드 예외의 연장). ⚠ 증강 요청 계약·파생물 적재 위치는 **미확정**이다(마킹 저장 모델은 2026-09-02 에 확정됐다 — 아래 ★ 절) — 내부 증강 요청은 **검수자 전용 + 검수 완료 영상만** 대상이라 검수가 없는 포털 자산에는 그대로 성립하지 않는다. 영상은 비식별 미적용(본인 데이터)이다. ★**자동 마킹의 기본 간격은 `300` 이고 단위는 프레임 수다** (2026-09-02 사용자 확정 — *"자동은 300프레임을 자동기본으로 하되 사용자가 설정할수있어야해"*). ⚠ **구 서술 폐기** — *"프레임 추출 설정 `portal.upload.frame-interval-sec`(기본 5초)이 이제 자동 마킹의 기본 간격이다"* 는 **사실과 다르다.** 단위가 **초가 아니라 프레임 수**인 이유는 **수동으로 찍는 지점이 이미 프레임 단위**라 자동만 시간 단위로 두면 한 화면 안에서 단위가 갈리고, 재사용하는 마킹 원장의 간격 컬럼도 프레임 수이기 때문이다. 시간으로 받으면 저장 시 초당 프레임 수로 나눠야 하는데 그 값이 영상마다 달라 **같은 입력이 영상마다 다른 저장값**이 된다. 그 대가로 같은 간격이 영상마다 다른 시간을 뜻하므로 **화면이 지금 영상 기준 환산 시간을 함께 보여 준다**(인지·수용). ⚠ 그 결과 **`portal.upload.frame-interval-sec` 는 운영 코드 소비가 0건**이 됐다 — 시드·설정 키 등록은 마이그레이션이 필요해 그대로 두었으니 **되살려 쓰지 말 것.** 추출 장수 상한(`maxFrames`)은 그대로 쓰이며 초과분은 **거부가 아니라 절단**이다(자동=전 구간 균등 재선택 / 수동=앞에서부터). 상태: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED` — ⚠ **`UPLOADED` 의 뜻이 「추출 대기」에서 「마킹 대기」로 바뀌었다.** 신규 화면: 포털 업로드(`/portal/uploads`)·포털 업로드 라벨링(`/portal/uploads/:uldSn/label`)·**포털 업로드 영상 마킹**. 신규 API: `/v1/portal/uploads/**`

> ### ★업로드 영상 마킹 — 별도 화면이며 프레임 추출 위치를 정한다 (2026-09-02 사용자 확정, 구속)
>
> > "개인 영상을 업로드할때만 쓰고 마킹한 영상의 프레임을 추출하는데 사용하는거야. 자동/수동 둘다 지원해야하고."
>
> - **마킹의 쓰임은 프레임 추출 위치 지정 하나**다. 외부 시계열 위탁·검수와 무관하다.
> - **라벨링 화면 안의 모드가 아니라 별도 화면**이다. **자동·수동 둘 다** 제공한다.
> - ★**파이프라인 순서가 바뀐다** — `업로드 → 마킹(자동/수동) → 마킹 위치로 추출 → 준비 완료 → 라벨링`.
>   **업로드 직후 고정 간격 자동 추출은 폐지**되고 그 동작은 **자동 마킹의 기본값으로 흡수**된다
>   (간격을 그대로 두면 구 동작과 같은 결과). 추출을 마킹 뒤에 두면 **낭비 추출도 재추출도 없어**,
>   이미 붙은 라벨을 어떻게 할지 정할 일이 생기지 않는다 — 그것이 이 순서를 고른 이유다.
> - ⚠ **인지·수용한 대가**: `UPLOADED` 상태의 뜻이 「추출 대기」에서 **「마킹 대기」**로 바뀐다.
>   목록 화면이 그 상태에서 무엇을 하라고 안내할지 함께 정해야 한다. 또 마킹을 잘못했을 때의
>   **회복 경로(재마킹·재추출)가 아직 없다.**
> - **저장은 기존 마킹 원장을 재사용**한다 — 포털 전용 표를 새로 만들지 않는다(사용자 식별자 폭만
>   넓힌다. 라벨 원장에 같은 조치를 한 선례가 있다). `ADR-013` 이 **유일하게 「미확정」으로 남겨 뒀던
>   축**이 이것으로 닫혔다.
> - **포털에서 버리는 축**: 비식별 영상 서빙(본인 데이터라 비식별을 하지 않으므로 **원본을 재생**한다) ·
>   비식별 누락 신고 · 검증 질문 선택(관제 인입 이벤트 유형이 이 경로에 **없다**) · 위탁 배치 기동 ·
>   배정·검수 연계. 진입 차단 축도 「비식별 완료 + 배치 단계」에서 **「업로드 완료 + 본인 자산」**으로 갈아끼운다.
> - ⚠ `<video>` 가 인증 헤더를 못 싣는 제약은 **채널과 무관**하므로 서명 주소 발급 방식은 그대로 필요하다.
> - ✅ **구현됐다 (2026-09-05 실측 — 구 서술 「설계만 섰고 코드는 0건이다」 폐기).** 화면·창구·시험이
>   실재한다(`PortalUploadMarkingPage.tsx` · `features/portal/uploads/marking*` · 마킹 창구 4종).
>   ⚠ **파일 존재를 확인한 것이지 사양 충족을 대조한 것은 아니다** — 완성도 판정은 별도 라운드다.
>   상세는 변경지시서 `CO-20260902-포털-업로드영상-마킹화면-신설.md` 와 `CO-20260903-포털-마킹창구-가드추상화-순서반전.md`.

> ### ★★머리 영역과 좌측 메뉴는 Host 가 소유한다 — 우리는 본문만 그린다 (2026-09-02 사용자 확정, 구속)
>
> > "포털의 UI 가 헤더랑 왼쪽 메뉴까지 포털이 다 가져갈거야. 그걸 염두해두고 우리 화면을 구성해야 할 것 같아."
>
> 포털 채널의 저작도구 화면은 Host 가 자기 화면 안에서 우리 프론트를 실행하는 임베딩 환경에 놓인다.
> 그 경계에서 **Host 가 머리 영역과 좌측 주 메뉴를 둘 다 그리고, 저작도구는 그 아래 본문만 그린다.**
>
> - **우리 셸은 머리 영역도 좌측 주 메뉴도 두지 않는다.** 로고·사용자 신원·역할 배지 미보유(Host 가
>   이미 보여 준다) · 왼쪽 레일 미보유(둘이 되면 Host 화면과 부딪힌다) · 푸터 미보유(별개 결정, 2026-08-18).
> - **목적지 셋(내 작업 · 내 업로드 · 증강)은 본문 상단 이동 탭으로 오간다.** 라벨링 편집 화면 둘은
>   목적지가 아니라 목록에서 행을 눌러 들어가는 자리이며, **몰입 편집 화면이라 그 탭도 노출하지 않는다.**
> - ★**Host 메뉴에 우리 하위 목적지를 등재하지 않는다** — 등재하면 이동 수단이 둘이 되고 목적지가
>   늘 때마다 **상대 배포가 필요**해진다. 실측상 Host 메뉴의 저작도구 항목에는 **자식 노드가 없고**,
>   Host 는 *"마운트 영역 안에서 무엇을 어떻게 보여줄지는 저작도구 화면정의서가 정본"* 이라고 명시했다.
>   본문 상단 탭은 **Host 자신도 쓰는 확립된 패턴**이다.
>
> ⚠⚠ **이 축은 두 번 뒤집혔다 — 세 번째로 되돌리지 말 것.** 최초에는 Host 소유였고(상대 셸 정의가
> 그렇게 규정해 우리는 회신 대기로 뒀다), **2026-09-01 에 「저작도구가 좌측 메뉴를 그린다」로
> 뒤집혔다가**, 2026-09-02 에 다시 Host 소유로 확정됐다. 지금은 **상대 설계와 같은 쪽으로 수렴**한
> 상태이고, 그때 「회신 대기」로 남겼던 축이 이것으로 닫혔다.
> ⚠ **함께 폐기된 구 근거는 서로 반대 방향의 둘이며 둘 다 폐기다** — *"포털이 보유한 화면이 적어
> 메뉴 트리가 필요 없다"*(화면이 다섯이 되어 성립하지 않는다) · *"포털 채널이 보유한 화면이 늘어
> 좌측 메뉴를 둔다"*(목적지가 여럿인 것은 맞으나 그것을 **그리는 주체**가 Host 다).
> ⚠ **2026-09-01 의 그 결정은 설계에만 있었고 코드에 닿은 적이 없다** — 포털 채널 레이아웃은 좌측
> 메뉴를 가진 적이 없고 머리 영역은 포털 채널 빌드에서 숨긴다. 즉 이번 반전은 **설계가 구현 쪽으로
> 되돌아온 것**이라 **제거할 코드가 없다.** ✅ **남아 있던 본문 상단 이동 탭도 신설됐다**
> (2026-09-05 실측 — `components/layout/PortalContentTabs.tsx` · `lib/portalNav.ts`. 구 서술
> 「남는 코드 작업은 탭 신설 하나뿐이다」는 그 작업이 끝나 폐기). ⚠ 파일 존재 확인이며 사양 대조는 아니다.

> ### ★포털 채널 전용 테이블을 공용 원장에 흡수한다 — 오버레이 한 벌만 남긴다 (2026-09-02 사용자 확정, 구속 · `ADR-058`)
>
> > "관제서버용 테이블을 활용하도록 설계를 바꿔봐. 포털용의 테이블은 최소화했으면 좋겠어."
>
> **되는 근거(실측)** — 배포 스키마가 **한 벌**이라 전 테이블이 이미 양쪽 배포본에 있고, **백엔드에 배포
> 향 분기가 0건**이라 「포털용/관제용」 구분은 **이름과 관행에만** 있었다. `LS_DATA_AUG` 는 FK 가 없고,
> 관제 조회 통로(`V_COMPLETED_VIDEO`)는 **검수 승인 때만 생기는 동결 메타**가 드라이버라 검수 없는
> 포털 자산이 **구조적으로 도달하지 못하며**, 배치 트리거는 DB 가 아니라 **앱 이벤트**다.
>
> | 흡수 전 | 흡수처 | 신설 |
> |---|---|---|
> | `LS_PORTAL_ULD` | **`LS_DATA_RAW`** | `PORTAL_USER_NO varchar(100) nullable` |
> | `LS_PORTAL_ULD_FRME` | **`LS_DATA_SRC`** | 신설 컬럼 0 (원장이 상위집합) |
> | `LS_PORTAL_ULD_LBL` | **`LS_DATA_LBL`** | 기존 `REG_USER_NO` 를 `bigint`→`varchar(100)` |
> | `LS_PORTAL_TUS_ULD` | **`LS_TUS_UPLOAD`** | 사용자 식별자 폭 64→100 |
> | **`LS_PORTAL_USER_LABEL`** | **★흡수하지 않는다 — 존치** | — |
>
> - **확정값 (재발명 금지 — ERD 가 소유한다)**: 출처 판별자 **`SRC_TYPE='PORTAL_ULD'`**(축 신설이
>   아니라 기존 값역에 값 1개 추가) · 클립 식별자 합성 **`PORTAL_ULD_{RAW_SN}`** · 개인정보 유형
>   **`UNKNOWN`(미상)** ⚠ `ANONY` 금지 — 그건 「비식별 불필요」라는 *판정 결과*인데 우리는 그 판정을
>   한 적이 없다 · 영상 길이 자료형 변경 **0**. 흡수 전 형상과 대응은 `ERD-028` 이 담는다.
> - **★`LS_PORTAL_USER_LABEL` 만 남기는 근거 (되살리지 말 것)** — 존재 이유가 「저장해도 원본과
>   데이터마트를 수정하지 않는다(단방향)」라 라벨 원장에 합치면 **원본을 덮어쓴다.** 소유자 구분으로
>   섞으면 구분을 한 번만 잊는 순간 **남의 오버레이가 정본 라벨로 읽히는 fail-open** 이 되고, 그 누락은
>   **새 조회 경로가 생길 때마다 다시 열려** 한 번의 점검으로 닫히지 않는다. 분리는 조회 필터가 아니라
>   **테이블 자체로** 지킨다. 「일관성」을 이유로 함께 흡수하려는 시도를 막는 것이 이 항목의 목적이다.
> - **★「분리」는 유지되지만 근거가 바뀐다 — 이름만 바꾸고 근거를 두면 참인 결론을 거짓 근거가
>   떠받친다.** ①출처·소유자로 가른다 ②배치는 DB 트리거가 아니라 **앱 이벤트**로 시작하고 포털은 자기
>   이벤트를 쓰므로 발동하지 않는다 ③데이터마트 뷰는 동결 메타가 드라이버라 **구조적으로 도달 불가**다.
> - **★★보존기간 만료 자동 삭제가 특히 위험하다** — 흡수 전에는 전용 테이블이 울타리였다. 이제 삭제
>   대상은 **출처 판별자 + 소유자 보유 + 보존기간 경과 셋을 동시에 충족**해야 하며 **하나만 빠져도 관제
>   영상을 지운다.** 조건은 후보 조회가 아니라 **최종 삭제 실행문에 리터럴로** 박고(파생영상 폐기 삭제와
>   같은 원칙), 영상 식별자만 받아 지우는 창구를 두지 않는다. ⚠ 연쇄 삭제 표면이 **2개 표에서 약 24개
>   표로** 넓어지고, 그중 `LS_DATA_LBL_HSTRY`·`LS_DATA_AUG`·`LS_DATA_AUG_LBL_MAP` 은 **부모 외래키가
>   없어 연쇄로 정리되지 않는다**(오류 없이 조용한 고아).
> - **★착지처를 다 정했다 (2026-09-02 후속 확정) — 영상 원장에 컬럼을 더 달지 않는다.**
>   `ORGNL_FILE_NM`→`video.original_filename` · `MIME_TYPE_NM`→`video.mime` ·
>   `ULD_STTS_CD`→**`portal.upload_status`** · `FAIL_RSN_CN`→**`portal.fail_reason`** (전부 `LS_DATA_META`) ·
>   `ULD_TYPE_CD`(자산 종류)는 **보관하지 않고 MIME 유형에서 판정**한다(두 번째 진실원 방지).
>   프레임률·파일 크기는 `video.fps`·`video.filesize` 로 이미 있고 프레임 수는 프레임 원장을 센다.
>   ⇒ **신설 컬럼은 `PORTAL_USER_NO` 하나뿐이고 나머지 스키마 변경은 식별자 폭 확대뿐이다.**
> - ⚠ **기각한 두 안 — 되살리지 말 것.** ①`LS_DATA_RAW` 에 상태·실패사유 컬럼 신설 — 그 원장은 관제 뷰·배치·
>   산출물·통지가 **모두 읽는 표**라 포털 전용 컬럼이 모든 독자에게 영구히 보인다. 「전용 컬럼이라야 삭제가
>   안전하다」는 논거는 **성립하지 않는다** — 관제 행에는 `portal.*` 키가 애초에 없어 그 키로 대상을 고르는
>   삭제문은 관제 영상을 **구조적으로 못 집고**, 오히려 판별자가 셋으로 는다. ②기존 `DATA_STTS_CD` 값역 확장 —
>   두 값역이 **`PROCESSING`·`FAILED` 를 같은 이름으로 쓰는데 가리키는 파이프라인이 다르다.**
> - ⚠ **인지·수용한 대가 2** — `META_VL` 이 `varchar(2000)` 이라 실패 사유가 **좁아진다**(사유 문장만 담고
>   추적 정보는 담지 않으며, 넘치면 잘라 저장하되 **잘렸다는 사실이 드러나야** 한다). 그리고 그 표가 이미
>   기술메타·시계열 메타를 섞는데 **세 번째 종류**가 는다.
> - ⚠ **스키마·코드 이관 모두 미착수** — 배포 스키마에는 구 4벌이 **그대로 있다.** `ADR-058` 이 코드
>   이관 규모를 **운영 약 50파일 · 시험 약 50파일**로 적어 뒀다.

- 반응형 웹 (PC/태블릿/모바일), WCAG 2.1 AA 준수

### DB 정책
- `klid_at` 스키마(PostgreSQL)에 저작도구 전용 테이블(LS_*) 운영 — 저작도구가 직접 소유·구성
- **★전 환경·전 채널 PostgreSQL 이다 — 포털향을 다른 RDB 로 가르지 않는다 (2026-08-28 사용자 확정, 구속)**: 관제향(control)과 **포털향(portal) DB 가 모두 PostgreSQL** 이며, *"포털향은 MariaDB 로 전환될 수 있다"* 는 검토는 **기각**됐다(관제향 PostgreSQL / 포털향 MariaDB 로 **DBMS 를 이기종으로 가르는** 안 미채택). ⚠ **기각된 축은 DBMS 이기종화이지 배포 분리가 아니다** — 저작도구는 **채널별로 별도 배포되고 각 배포본이 자기 별도 PostgreSQL DB 를 갖는다**(2026-08-31 확정 · 위 「포털 (외부 채널)」 절의 ★★ 블록 · `ADR-012`). 이 문장을 근거로 그 형상을 금지된 것으로 읽지 말 것.
  - **귀결 — 이기종 대응을 미리 넣지 않는다.** 포털 복제 upsert 의 `CAST(... AS jsonb)` · `ON CONFLICT ... DO NOTHING` 은 잠정 선택이 아니라 **확정 전제**이므로, 방언 중립(dialect-neutral)으로 되돌리거나 그것을 제약으로 새로 세우지 말 것. 복제본은 원본(control)과 **같은 문법으로 동형**을 유지한다. 판정 진실원은 `INT-009`. ⚠ **2026-08-31 보정** — 이 지침의 대상인 **포털 복제 자체가 폐기 확정**이라 철거와 함께 소멸한다(「포털 (외부 채널)」 절). **2026-08-31 철거 완료로 이 지침은 소멸**했다(대상 코드가 없다). **PostgreSQL 단일 형상 확정은 복제와 무관하게 유지**된다.
  - ⚠ **낡은 근거를 되살리지 말 것** — 코드 주석의 *"TO_CHAR 가 MariaDB 미지원이라"*(통계 일별·월별 그룹화) · *"H2(local) / MariaDB(dev/stg/prd) 모두 지원"*(배치 큐 잠금)은 **1차 MariaDB 시절 서술**이고 전부 폐기됐다. **local 도 Testcontainers PostgreSQL** 이다. 단 그 서술이 낳은 **동작(Java 측 키 생성 · 비관적 잠금 no-wait)은 그대로 둔다** — 근거만 무효이고 바꿔서 얻는 것이 없다.
  - ⚠ **v1(1차) 관련 MariaDB 기록은 정정 대상이 아니다** — `ADR-010`(MariaDB→PostgreSQL 전환 결정) · LogiCraft `legacy_artifact` 30건의 `legacy_dbms: MariaDB` · ERD 의 `legacy_source.legacy_dbms` 는 **v1 사실의 기록**이라 그대로 둔다.
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
  - ⚠ **위 수치는 「접은 시점」의 역사적 사실이며 지금 V1 을 적용한 결과가 아니다** — 그 뒤 V3·V4 가 죽은 테이블 7종을 지우면서 **V1 에서 정의·시드를 함께 덜어냈고**(기존 DB 는 그 행이 `BASELINE`·checksum NULL 이라 영향 없음), V6 이 `LS_DATA_LBL_AI_INFO` 를 흡수했다. **현재 형상 = 저작도구 소유 `LS_*` 69개 + Quartz 11 + 뷰 4 = 84**(2026-09-06 재실측 — 구 수치 62/73 은 그 뒤 `V27`~`V33` 이 더해져 낡았다. 같은 날 개발서버 실 DB 도 `ls_* 69` · `qrtz_* 11` 로 일치했다. ⚠ 그 서버는 Flyway 를 쓰므로 `flyway_schema_history` 가 하나 더 있어 표가 81개로 세어진다 — 온프렘은 Flyway 를 끄므로 그 표가 없다)(V1 시드 14행 — `ls_com_cd` 5행은 그 테이블과 함께 소멸). 확인: `grep -c "^CREATE TABLE klid_at.ls_" deploy/onprem/db/schema.sql` → 62.
    - ⚠ **구 수치 57 폐기(2026-08-30 실측)** — 그건 쓰던 시점의 값이고 그 뒤 `V14`(+2) · `V18`(+2) · `V21`(+1) 이 더해졌다. 이 개수는 **새 마이그레이션이 늘 때마다 움직이므로 외우지 말고 그때그때 재실측할 것.**
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
  - `V_COMPLETED_VIDEO` : **규격서 §5-1 의 31컬럼**(정본 `docs/관제-저작도구-데이터연동-규격서-20260805.md`). ⚠ **V174 시점은 30컬럼이었고 그 뒤 `V16`·`V17` 이 지나 31 이 됐다** — 판정 진실원은 회귀 가드 `DatamartViewRebuildIT(뷰_출력컬럼이_규격서_31개와_이름_순서까지_일치)` 다. 관제가 `datasets`·`dataset_versions` 를 SELECT 1회로 채우고 산출물을 픽업하는 계약면이다. 영상 메타 + 원본 영상 경로(`ORGNL_VDO_PATH_NM`) + 검수 완료 일시(`RVW_CMPTN_DT`) + **산출 폴더 경로(`OUTPUT_PATH_NM`)·프레임수(`FRME_CNT`)·산출 용량(`DATA_ETBL_CPCT`)** — 최신 SUCCEEDED/PARTIAL export(`LS_DATASET_EXPORT`) 조인. LATERAL 4개(산출 원장·비식별 이력·인입·라벨 집계)가 모두 최대 1행이라 **영상 1건=1row** 불변.
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
| Phase 3 | TUS 재개 가능 업로드 (관리 화면/대용량) — **1차 적재 경로 아님**(→ 「주의사항 · 파일 업로드」) | Java | portable-modules/03 |
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

**대상 5종**: 비식별(**`kpst.deid.base-url`**) · AI 추론(`authoring.integration.ai-server.base-url`) · 외부 시계열 분석 벤더(`vlm.client.url`) · 관제 통지(`authoring.control-notify.url`) · **외부 증강 벤더(`authoring.augment.external.base-url`)**. **DB 접속정보는 대상이 아니다.**

⚠ **증강은 2026-09-01 에 추가됐다 (사용자 확정 · `ADR-046` v10)** — 처음 결정할 때 목록에서 빠져 있었고, 그래서 시계열이 대역 차단을 벗은 뒤에도 **증강만 옛 정책(`ProfileGatedUrlPolicy` — 운영에서 HTTPS 강제 + 사설망 차단)에 남아** 같은 성질의 외부 벤더 위탁인데 한쪽만 평문 주소를 거부했다. `AugmentUrlPolicy` 가 원래 그 비대칭을 없애려고 만들어진 창구라 **방향만 뒤집힌 채 되살아난 것**이다. ⚠ 증강 요청 본문에는 공유 저장소의 비식별 프레임 절대경로가 다수 실려 유출 표면이 시계열보다 넓다 — **인지·수용한 위험**이며, 대역 차단으로는 막히지 않는다는 것이 이 결정의 본래 근거와 같다(공인망 주소로 바꾸면 그만이다).

- **진실원·반영**: 설정에 값이 있으면 설정, 없으면 배포 기본값. **재기동 없이 즉시 다음 호출부터 반영**된다(호출 시점 URL 재작성). 설정 키는 **애플리케이션 속성명 그대로** 쓴다 — 별도 키명을 만들면 그 매핑표가 두 번째 진실원이 된다.
- ⚠ **비식별 키는 `kpst.deid.base-url` 이다.** 구 후보였던 `authoring.integration.deidentify.base-url` 이 구동하는 `deidentifyWebClient` 는 **주입처가 0건인 죽은 빈**이라 그 키로는 아무것도 바뀌지 않는다(실측 확인 · `DeidentifyHealthIndicator` 주석이 2026-07-28 에 같은 사실로 판정축을 옮긴 기록 있음). **되돌리지 말 것.**
- **★주소 대역 차단을 하지 않는다** — 사설 대역·링크로컬·루프백 어느 것도 애플리케이션이 막지 않는다. 남는 검증은 **스킴 `http`/`https` + URL 형식**뿐이고 위반은 400(거부 사유에 해석 결과 미노출).
  - **근거**: ①연동 4종은 **내부망의 별도 GPU 서버**에 있을 가능성이 높아 대역 차단이 **정당한 대상을 막는다**(실제로 `ai-server` 기본값이 `localhost:9300` 이라 구 정책에서 400 이었다) ②시스템 전체가 **온프렘 내부망 배포**이고 외부에 노출되지 않는다(노출되는 것은 포털향뿐이며 **설정 화면은 포털 채널에 비노출**) ③아웃바운드·인바운드 통제는 **인프라 계층이 담당**한다.
  - ⚠ **구 정책(2026-08-10 오전) 폐기**: *"도메인을 IP 로 해석한 뒤 내부망 대역(127.0.0.1·10.x·172.16~31.x·192.168.x·169.254.169.254) 차단 + 요청 직전 재검증(DNS rebinding)"*. **되살리지 말 것** — 되살리면 정상 연동이 전부 막힌다.
  - ⚠ **잔여 위험(인지·수용)**: 관리자 패스워드를 얻은 자가 주소를 바꿔 영상·라벨을 다른 서버로 내보낼 수 있다. 이는 **대역 차단으로는 애초에 막히지 않던 위험**(공인망 주소로 바꾸면 그만)이라 이번 반전으로 새로 생긴 것이 아니다. 클라우드 메타데이터 탈취 경로는 **온프렘이라 성립하지 않는다**.
- **관리자 인증 = 단기 유효창**: 기존 관리자 공유 패스워드(`ADMIN_CLAIM_PASSWORD_HASH`) 재사용 · 무상태 서명 토큰(노드 간 공유 저장소 불필요) · **기본 10분, 상한 30분 강제** · 유효성 판정은 **서버 소유** · 토큰은 **주소 설정 변경 권한 한정**이며 역할을 승격시키지 않는다.
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
- **포털 자산 업로드 신설 (ADR-013 예외, 2026-07-17)**: 영상은 재개 가능 업로드를 쓰며 세션은 **공용 원장 `LS_TUS_UPLOAD`** 에 적재된다(구 전용 세션 테이블 `LS_PORTAL_TUS_ULD` 는 `ADR-058` 로 흡수). **한도·확장자·상태 전이 등 스펙 정본은 위 「포털 (외부 채널)」 절**이며 여기에 복제하지 않는다(두 곳에 적으면 한쪽만 갱신된다).

### 개인정보 보호
- 영상 암호화 저장, 로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)
- 비식별 처리 이력은 영상 단위로 기록

- **상세 규칙 정본은 `docs/rules/klid-privacy.md` 다 (2026-08-27 분리 · 2026-08-27 온디맨드 전환)** — 아래 불변식은 요약이며, 실제 판정은 그 파일을 `Read` 해서 한다.
- ⚠ **이 파일은 더 이상 자동으로 실리지 않는다 — 반드시 직접 열어야 한다.** 구 위치 `.claude/rules/` 는 디렉터리 전체가 매 턴 인라인되는 경로라, 「필요할 때 읽는다」로 설계해 놓고 실제로는 항상 로드되고 있었다(분리의 목적이던 토큰 절감이 **0** 이었다 — CLAUDE.md 에서 27,292자를 덜어낸 커밋이 rules 에 29,083자를 더해 **순증**했다). `docs/rules/` 로 옮겨 설계대로 동작하게 했고, 그 대가로 **안 읽으면 요약만 가진 채 판단하게 된다.** 아래 트리거에 하나라도 걸리면 여는 것이 규칙이다.

**불변식 — 읽지 않아도 틀리면 안 되는 것 4가지**

1. **신고 구간(`DE_IDENT_YN='F'`)은 읽기·쓰기 양쪽을 막는다.** 조회만 막으면 우회가 남는다 — 실측으로 *조회 412 ↔ 저장 200* 비대칭이 열려 full-replace 저장이 기존 라벨을 전량 삭제한 사고가 있었다. 차단 대상 12종과 응답 코드(412/404/400)는 정본 §차단 범위.
2. **프레임 이미지·영상 스트리밍의 기본은 비식별본이다.** 원본은 REVIEWER 가 `?raw=true` 를 명시할 때만 나가고, 파일 open 은 **realpath 재검증 + `NOFOLLOW_LINKS`** 규약을 탄다(lexical 검증 후 lexical open 은 금지 — CWE-59/367).
3. **승인 이력이 있으면 신고 접수·프레임 폐기가 영구 차단된다** — 현재 상태가 아니라 **이력**(`ReviewApprovalGate.hasEverApproved`)으로 판정한다. 현재 상태만 보면 재제출 구간에 통과한다(실증됨).
4. **게이트가 닫히는 변화는 서버 `no-store` 와 FE `removeQueries` 가 세트다.** 둘은 서로를 대체하지 않는다 — 하나만 걸면 캐시된 라벨 좌표가 계속 그려진다.

> ⚠ 위 4가지는 **요약이라 그대로 구현 근거로 쓸 수 없다.** 응답 코드·적용 경로 목록·예외(파생영상·포털 업로드 자산)는 정본에만 있다.

### 배치 성능
- Spring Boot + Quartz는 **Active-Active 이중화** 배포. **★노드 수는 넷이다 (2026-09-04 현장 실측)** — WAS 는 `klid-ai-gen-was-01~04` 네 대이고, 웹의 httpd 분산 묶음 `label-cluster` 에 **네 대가 모두 등록**돼 있어(BalancerMember `10.177.22.80~83:8080`) 넷이 함께 뜬다. ⚠ **구 수치 「2노드」는 2026-07-14 설계 시점 값이다** — 틀렸다기보다 그 뒤 현장 형상이 넷으로 늘었다. 지우지 않고 남기되, **노드 수를 근거로 하는 판단(락 경합·부하 산정·재시도 중복·공유 저장소 필요 여부)은 넷으로 계산할 것.** **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지). DB는 별도 DB 서버(이중화), 파일 스토리지는 별도 NAS 서버(공유 마운트). ⚠⚠ **「관제서버와 동일 서버 공동 배치」는 확인되지 않았다 — 근거로 쓰지 말 것.** 2026-09-04 현장에서 사용자가 관제와 서버가 다른 데 있는 것 같다고 했으나 확정이 아니고, 반대로 반입 명세는 영상 처리 도구를 관제지원 측이 **같은 장비에** 설치한다고 적어 두 서술이 어긋나 있다. 어느 쪽인지 확인되기 전까지는 미확인이다 *(구 '단일 인스턴스·클러스터 미적용' 서술 대체 — 2026-07-14 배포 토폴로지 확정, D5 v1.10 정합)*
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

## ★단위시험 문서의 「예상결과」는 화면에서 보이는 것만 쓴다 (2026-09-09 확정, 구속)

**시험을 수행하는 사람이 화면에서 볼 수 있는 것만** 번호 매긴 짧은 문장(`~한다`)으로 쓰고,
**화면으로 판정이 안 되는 것만** 마지막에 쿼리를 붙인다. 기준은 같은 컨소시엄이 실제로 제출한
통합플랫폼 단위시험 결과서다 — 거기엔 SQL·클래스명·상태코드가 **하나도 없다**.

> 1. 현재 비밀번호 미일치 얼럿 메시지를 노출한다.
> 1. 유효성 필드 하단에 유효성 문구를 노출한다.  2. 해당 필드로 focus 를 이동한다.

**넣지 않는 것**: 클래스·이벤트·메서드 이름(`MarkingCompletedEvent 발행`) · DTO 이름 ·
HTTP 상태코드(`201 Created`) · 구현 용어(`@Async`·`AFTER_COMMIT`·`setScale`) · 내부 식별자
(`{저장 라벨 PK}`) · 위 내용을 되풀이하는 `검증:` 줄. **컬럼·테이블명은 쿼리 안에서만** 쓴다.

⚠ 이걸 어겨 D11 의 예상결과 칸이 **평균 337자**가 됐고 사용자가 *"무슨 말인지 하나도 모르겠다"* 고
지적했다. 대상은 `docs/design/D11-*` 전 판본이며 **CBD 생성의 `--draft` 입력**이라 산출물에 그대로 실린다.
상세·금지 목록·낡은 테이블 주의는 **`docs/rules/unit-test-doc-format.md`**.

## ★★산출물을 클로드 아티팩트로 올리지 않는다 (구속)

**화면 시안·리포트·문서 어느 것도 아티팩트로 게시하지 않는다.** 시안은 `reports/screen-design/` 에
**HTML + PNG** 로 내고(둘 다 gitignore 대상), 그 PNG 를 사용자에게 건넨다.

⚠ **이 규칙은 이미 확정돼 있었는데 또 어겼다** — 기억에만 있어 배경으로 흘러갔고, 도구가 세션에
켜져 있으니 손이 그리로 갔다. **그래서 여기 적는다.** 이 파일은 매 턴 실린다.

- **도구가 있다는 것이 써도 된다는 뜻이 아니다.** 아티팩트 게시를 제안하지도 말 것.
- **이미 올라간 것은 지워야 하는데 도구로는 못 지운다** — 삭제 기능이 없고 읽기도 막혀 있다.
  사용자가 직접 지워야 하므로 **올리는 순간 사용자에게 일이 넘어간다.**
- 시안 PNG 는 `frontend/` 안에 둔 렌더 스크립트로 뽑는다(그 밖에 두면 의존이 안 잡힌다).

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

### CBD 산출물 생성 — 다른 기계에서 재현하려면

- **절차·함정 정본: `docs/rules/cbd-generation.md`** (온디맨드 — 자동으로 실리지 않으니 **직접 연다**)
- **입력값: `docs/cbd-inputs/`** — 설계 원장에서 도출되지 않는 칸의 값. 출처인 제출본이 레포 밖이라
  여기 둔다. ⚠ **원장이 아니라 원장의 재료다**(레시피가 읽는 원장은 `convert-service` 레포 소유).
- ⚠ **레포 하나로는 생성되지 않는다** — 생성 스킬이 `convert-service` 레포를 가리키는 **심링크**라
  그 레포가 없으면 아예 돌지 않는다. LogiCraft 토큰은 사용자에게 받는다(지어내거나 레포에서 찾지 말 것).
- ⚠ **산출물은 범위가 다른 세 벌이다.** 벌 사이의 차이는 대개 결함이 아니라 설계다 —
  「세 벌 다 같아야 한다」로 판정을 짜면 정상이 결함으로 잡힌다(실제로 두 번 오탐).
- ★★**고치거나 검사할 때 넷만 지킨다** (근거·사례는 위 규칙 문서 §0):
  ①**「고쳤다」는 확인이 아니다** — 고친 직후 변이를 심어 실제로 터지는지 본다
  ②**0건 옆에 양성 대조** — 「반드시 잡혀야 하는 것」으로. 아니면 대조가 거짓 0건이 된다
  ③**두 값이 같아 보이면 「둘 다 맞는데 쓰임이 다른 것 아닌가」를 먼저 묻는다**
  ④**그 값을 실제로 꺼내 가는 것이 무엇이고 어디를 읽는지 확인한다** — 정정을 남긴 것과
  반영된 것은 다르다
  ⇒ 이 넷이 실제로 결함을 잡았고 **주의력이 잡은 것은 없었다.** 「조심하자」로는 막히지 않는다.

### 위키·테스트케이스 갱신

- **기능 추가/변경 시 `docs/v2-wiki/`의 해당 페이지를 같은 커밋(또는 같은 PR)에서 갱신한다**
- 신규 도메인이면 v2-wiki에 새 페이지 추가 + `docs/v2-wiki/README.md` 목차 갱신
- 화면 추가 시 `docs/v2-wiki/04-screens-ia.md`, 테이블 추가 시 `docs/v2-wiki/18-database.md` 갱신
- v1 대비 기능 추가 시 `docs/v1-wiki/19-v2-gap-checklist.md` 체크 갱신 (R1 요구사항 외 기능이면 "요구사항 외 추가 결정" 명시)
- **★ 카탈로그의 케이스 표는 동결한다 — 회차 서술만 계속 쓴다 (2026-08-27 사용자 확정, 구속 · 구 「같은 커밋에서 갱신」 정책 폐기)**: `docs/test-cases/` 의 **케이스 행을 새로 추가·정정·폐기표기하지 않고 합계도 재계산하지 않는다.** 대신 각 파일 상단 `## 변경 이력` 표에 **회차 행만** 추가한다.
  - **폐기 사유 — 실행 주체가 없다.** 전수 검증(`/verify-tc`)은 **2026-08-04 3차가 마지막**이고, 그 뒤 회차 77 까지 약 37회차가 전부 「검증 없는 카탈로그 갱신」이었다. 케이스 4,276행은 실제 테스트 11,000건과 **기계적 연결이 없고**(TC-ID 가 테스트 코드에 박힌 것은 4건뿐) 근거 심볼 보유율이 13% 라, 검증이 돌지 않는 한 아무도 읽지 않는다. 커밋당 33~267줄이 드는데 소비자가 없었다. 구 정책의 근거였던 *"다음 전수 검증에서 결함으로 재발견된다"* 는 **그 전수 검증이 돌 때만 성립**한다.
  - **★회차 서술은 남긴다 — 그것이 실제로 읽히는 축이다.** 회차 표의 긴 서술은 케이스 목록이 아니라 **정책 반전 이력**으로 기능해 왔다(회차 71 「판정 축이 되돌릴 수 없는가 → 공짜가 아닌가로 바뀌었다」 · 회차 73 「직전 회차가 미등재로 남긴 판단이 사용자 판정으로 뒤집혔다」). 이 입도는 CLAUDE.md 에 담기엔 잘고 커밋 메시지에 담기엔 흩어지며, 이 저장소의 반복 사고(**철회된 정책 재시도**)를 실제로 막는 유일한 수단이다. 정책이 뒤집힌 항목은 종전대로 "구 정책 → 폐기"를 명시한다.
  - **기존 행은 지우지 않는다** — read-only 참조로 존치한다. 이 파일 두 곳(라벨 표시 색상 축 · 버전 diff 비교축)이 카탈로그 ID 를 근거로 인용하고 있어 지우면 그 참조가 끊긴다. **낡아 가는 것은 결함이 아니라 동결된 상태**이며 드리프트로 집계하지 않는다.
  - ⚠ **되살리려면 검증 재개가 선행 조건이다** — 케이스 표를 다시 쓰려면 `/verify-tc` 를 돌릴 계획이 먼저 서야 한다. 검증 없이 표만 갱신하는 것이 지금 폐기하는 바로 그 상태다.
  - **[동결] 구 규칙 — 되살릴 때만 참조한다**: ①**정정** — 근거는 `파일명(심볼명)` 으로 적고 라인번호는 쓰지 않는다(2026-08-05 확정). 정정 대상은 파일 리네임·삭제·이동, 판정 주체의 클래스 이동, 심볼 리네임이며 라인 이동은 사유가 아니다. 기존 행은 일괄 백필하지 않는다. ②**폐기** — 행을 지우지 말고 폐기 표기 + 사유·근거 커밋을 남긴다. ③**신설** — 경계·오류·하위호환 케이스까지 함께 넣는다. ⚠ 그 근거들(정정 ≈699건 중 실질 24건 · 회차 11 은 76건 전량 드리프트 · stale 라인은 없느니만 못하다)은 **이번 동결의 근거와 같은 방향**이라 되살릴 때도 유효하다.

## 작업 위임 선언

> `/cc` PM이 이 표를 0-2 업무 유형 결정보다 **우선** 적용한다 (규약 → cc-forge `commands/cc.md` §0-2.5).
> 이 선언이 없으면 `/cc`는 LogiCraft 계약을 모른 채 표준 `developer-*` 파이프라인으로 흘러 키트·`@design` 추적 태그·IMPREC 반영이 끊긴다.
> 이 프로젝트의 설계 진실원은 **LogiCraft ITEM**과 로컬 키트(`docs/design/{도메인슬러그}-{DOMAIN-ID}/`)다.
>
> ★ **이 저장소는 `klid-*` 구현 오케스트레이션 세트가 온보딩돼 있다**(`.claude/skills/klid-{dispatch,design-backfill}` · `.claude/agents/klid-d0NN-implementer` 15종 + `klid-web-implementer` + `klid-qa-verifier`).
> **이미 구현된 코드를 고치는 요청은 표준 `developer-*` 파이프라인이 아니라 `klid-dispatch` 로 간다** —
> 그쪽이 CO 장부·설계 선반영·도메인별 진실원·독립 QA 를 갖고 있다. 아래 표에서 **위쪽 행이 더 구체적인 조건**이며 먼저 매칭한다.

### ★변경 순서 — LogiCraft 먼저, 코드는 그 다음 (2026-08-07 사용자 확정, 구속)

**LogiCraft ITEM 으로 추적되는 변경은 ① ITEM 을 먼저 고쳐 사양을 확정하고 → ② 그 ITEM 을 진실원으로 코드를 반영하고 → ③ 구현 seam 에 `@design <ITEM-ID>` 태그로 잇는다.** 코드를 먼저 고치고 ITEM 을 나중에 따라오게 하지 않는다 — 그러면 ITEM 이 사양이 아니라 **구현 상태 서술**로 오염되고, 진실원이 무너져 다음 사람이 코드에서 설계를 역추정하게 된다.

- ⚠ **"권한·응답코드는 구현이 진실원"과 모순이 아니다** — 그건 **이미 어긋난 것을 사후에 메우는 정합(감사) 모드**의 판정 규칙이고, **앞으로 바꿀 것**은 이 순서를 탄다. 정합에서 "코드가 확정 정책과 충돌"로 잡힌 건의 수정도 ITEM 먼저다.
- **예외**: 탐색·스파이크는 코드를 먼저 만져도 되나 **확정은 ITEM 에 먼저** 넣고 정식 구현은 그 뒤 · 구현 중 설계가 틀렸다고 판명되면 코드를 조용히 틀지 말고 **ITEM 을 먼저 고친 뒤 재개** · 긴급 장애로 코드가 앞섰다면 예외가 아니라 **빚**이라 같은 작업의 마무리로 ITEM 을 맞춘다.
- 절차·함정 상세(한글 손상·배열 원소 소실·`status` 함정·검사기 사각): **`docs/rules/logicraft-integration.md`** (ITEM 을 쓰기 전에 읽는다)
- ⚠ **그 파일도 자동으로 실리지 않는다 (2026-08-27 온디맨드 전환)** — `.claude/rules/` 에 있던 것을 옮겼다(사유는 위 「개인정보 보호」 절의 같은 항목). `.claude/rules/learned-logicraft.md` 의 요약은 훅이 키워드로 주입하지만 **그것은 요약이지 절차가 아니다.** ITEM 을 쓰기 전에 정본을 연다.

### ★키트 — 워크트리 소유권 (2026-08-27 확정, 구속)

**키트 상태의 소유자는 메인 워크트리 하나뿐이다.** 링크드 워크트리(`[ "$(git rev-parse --git-dir)" != "$(git rev-parse --git-common-dir)" ]` 가 참 — 현재 `orca/workspaces/klid-label/*` 가 여기 해당)에서는 아래를 지킨다. **어느 스킬이 호출됐는지와 무관하게 적용된다** (`mc-logi-*` · `klid-dispatch` · `klid-design-backfill` 전부).

- **키트 SYNC 금지** — `docs/design/**`·`docs/screen-design/**` 에 쓰지 않는다(다운로더 `download-kit.mjs`·`arrange-screen-kit.mjs` 실행 포함). stale 이어도 사실만 보고하고 진행하며, 갱신은 메인에서 하고 이 트리를 rebase 한다.
- **Phase 5(추적 역동기화) 전체 유예** — `IMPLEMENTATION.md`·`SCREENS.md`·`version-master.md`·이 파일의 `mc-logi-*:` 마커 블록에 쓰지 않고, IMPREC(`create_implementation_record`)도 기록하지 않는다. 기록했어야 할 내용은 **파일 대신 최종 보고로 반환**해 PR 본문 `## 키트 반영 대기` 블록에 싣는다.
- **머지 방식은 PR 고정** — `main 머지`를 고르지 않는다(메인 워크트리가 main 을 점유해 git 이 거부한다).
- **머지 후 메인 워크트리에서** `추적만`(Phase 5 단독)을 1회 실행해 유예분을 반영한다.

> 왜: 구현 현황 표와 마커 블록은 **도메인 전역 레지스트리**다. 병렬 워크트리가 각자 종료 시점에 같은 파일의 같은 줄을 고치면, 의미상 합집합이어야 할 상태표가 git 에서는 경쟁 편집이 되어 **피처마다 머지 충돌**이 난다. 브랜치마다 다른 시점에 SYNC 하면 충돌 표면이 상태표를 넘어 설계 문서 전체로 넓어진다. IMPREC 를 워크트리에서 기록하면 squash 머지로 사라질 커밋 해시가 서버에 남아 추적이 어긋난다.

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
- 층별 절차·함정 상세는 `docs/rules/logicraft-integration.md` §2-E·§5.

**적용 범위 (Critical — 위임은 예외이지 기본값이 아니다)**: 이 선언은 **LogiCraft ITEM/도메인으로 추적되는 작업에만** 적용한다. 이 저장소의 모든 작업이 LogiCraft 대상은 아니다 — 일반 기능 추가·버그 수정·인프라/빌드·CVAT 포팅·위키/테스트케이스 갱신 등은 **기본값인 표준 `/cc` 파이프라인**으로 간다. 요청이 아래 `조건`에 명확히 매칭될 때만 위임하고, 애매하면 표준 경로를 택하거나 사용자에게 확인한다.

- 조건: **이미 구현된 코드의 수정·버그**(테스트하다 발견한 수정사항 포함) → 위임: `klid-dispatch` | 근거: 변경지시서(CO) 장부 + 영향 도메인 게이트 + **설계 선반영(Phase 3.6)** 후 도메인 implementer 병렬 fan-out + 독립 QA(`klid-qa-verifier`). 실행=메인
- 조건: **밀린 설계 반영·IMPREC 백필** → 위임: `klid-design-backfill` | 근거: 부채 회수 3모드(CO 예외건 / 온보딩 이전 누적 / IMPREC 갭). 실행=메인
- 조건: **신규 도메인 최초 구현** → 위임: `mc-logi-implement` | 근거: `klid-dispatch` 는 "이미 구현된 코드 수정" 전용이라 최초 구현은 대상이 아니다. 실행=메인
- 조건: 도메인 백엔드 구현 (API·ERD/DB·domain_event·service·NFR) → 위임: `mc-logi-implement` | 근거: 키트가 단일 진실원이며 `@design <ITEM-ID>` 추적 태그 규약을 포함. **[WT] 아래 §PM 보정 절차 적용**
- 조건: 화면(screen_spec) 프론트엔드 구현 → 위임: `mc-logi-screen-implement` | 근거: 화면은 implement 가 아닌 screen-implement 담당 (중복 구현 방지). **[WT] 동일 보정**
- 조건: 화면 비주얼 디자인·고충실도 목업 → 위임: `mc-logi-screen-design`
- 조건: 키트 다운로드·동기화(stale 해소) → 위임: `mc-logi-implement-kit` / `mc-logi-screen-kit` | **[WT] 위임하지 않는다** — "키트 SYNC 는 메인 워크트리에서 하고 이 트리를 rebase 하세요"로 안내하고 종료
- 조건: LogiCraft ITEM 수정·정합·cascade → 위임: `mc-logi-update` | 근거: AI 임의 등록 금지 + cascade 재귀 추적 절차 보유
- 조건: ERD 컬럼 용어사전(4계층) 정합·표준화 → 위임: `mc-logi-glossary-align` | 근거: 위 "주요 비즈니스 규칙"의 사전 판정 규칙(CSV grep 우선, `createdBy` 확인)과 동일 절차를 강제
- 조건: 구현 코드 ↔ 키트 정합 점검 → 위임: `mc-logi-implement-review`
- 조건: 도메인 갭 감사 → 위임: `mc-logi-domain-review`
- 조건: 구현 코드의 `code_module` 등록·정합 → 위임: `mc-logi-module-register`
- 제외: **CBD 산출물(R1~D9) 생성은 기존대로 `/cc-doc-gen`** — LogiCraft ITEM → 문서 생성 축이라 구현 키트 축과 다르다. ⚠ 다만 그 산출물은 **2026-08-15 동결 중**(위 「문서 동기화 규칙」)이라 감사 결함 수정이 끝나기 전에는 재생성하지 않는다
- 제외: LogiCraft ITEM과 무관한 일반 버그 수정·리팩토링·빌드/설정 변경, `docs/v2-wiki`·`docs/test-cases` 갱신 → 표준 `/cc` 파이프라인
- 조건: 머지 완료 후 유예된 키트 상태 반영 (예: "키트 반영해줘", "추적만 돌려줘") → 위임: `mc-logi-implement 추적만` / `mc-logi-screen-implement 추적만` | 근거: Phase 5 단독 회수. **메인 워크트리에서만** — 워크트리면 거절하고 메인에서 실행하도록 안내. 실행=메인
- 참고: 위 표에 없는 `mc-logi-*` 스킬은 스킬 description 매칭으로 호출된다 (표를 전수 유지하지 않는다)

### PM 보정 절차 [WT] — 링크드 워크트리에서 구현 스킬을 위임할 때

판별: `[ "$(git rev-parse --git-dir)" != "$(git rev-parse --git-common-dir)" ]` 가 참일 때만 적용한다(메인이면 스킬 기본 흐름 그대로).

**위임 전** — 스킬 프롬프트에 아래를 그대로 실어 보낸다:

~~~
## 워크트리 세션 — 키트 정책 (CLAUDE.md §★키트 — 워크트리 소유권 우선)
- Phase 4 까지만 수행하고 Phase 5(추적 역동기화) 전체를 유예한다.
  IMPLEMENTATION.md / SCREENS.md / version-master.md / CLAUDE.md 마커 블록에 쓰지 않고,
  IMPREC(create_implementation_record)도 기록하지 않는다.
- Phase 0 에서 키트가 stale 이어도 SYNC 하지 않는다. stale 사실만 보고하고 진행한다.
- Phase 4 게이트 3(머지 방식)은 PR 고정이다. `main 머지`를 선택지로 제시하지 않는다.
- Phase 5 에서 기록했어야 할 내용(구현 현황 1줄·운영 전 확인 잔여·IMPREC 대상 ITEM 목록·
  mc-logi-update 권고)은 파일에 쓰지 말고 최종 보고에 그대로 반환한다. PM 이 PR 본문에 싣는다.
~~~

**위임 후** — PM 이 수행한다:

1. 회수한 내용을 `/cc-pr` 로 만드는 PR 본문에 **`## 키트 반영 대기`** 블록으로 싣는다(검증 블록과 같은 자리).
2. 스킬이 키트 파일을 이미 건드렸으면 `git checkout -- <경로>` 로 되돌리고 그 사실을 사용자에게 1줄 보고한다. **조용히 넘어가지 않는다.**
3. 완료 보고 마지막에 `머지 후 메인 워크트리에서 /cc 키트 반영해줘` 를 명시한다.


<!-- mc-logi-kit:start (자동 관리 — 직접 수정 금지, mc-logi-implement-kit 재실행 시 갱신) -->
# Logicraft 구현 키트

이 레포는 logicraft 설계 기반으로 구현한다. **코드 작업 전 아래 키트의 IMPLEMENTATION.md 를 먼저 읽을 것.**

> ★★★★★ **2026-09-05 SYNC — 구현 15 키트 전량 (비기능 요구 정합 라운드).**
> 계기는 **`nfr` 26건 신규 + 8건 수정**이다. 요구사항정의서(비기능)의 담당=`공통` 88건 중 저작도구가
> 담고 있던 것이 9건뿐이어서 나머지를 등재했고(`NFR-023`~`NFR-048`), `nfr` 은 **전역 타입이라 15개 키트
> 전부가 영향권**이다. 전 키트 `nfr` 15 → **41건**.
> **검증**: 15키트 전건 `서버 건수 = pin 건수` 일치 · 직전 커밋 `version-master.md` 대비 **유실 0** ·
> 키트 `_raw` 와 서버 본문 **완전일치**(무열화).
> ★**두 바퀴 돌려야 수렴한다** — 1차 실행이 끝나면 다운로더가 **새 미판정을 채우기 때문**이다. 1차에서
> 핵심 타입 48건(`CDIAG-016`~`045` · `DFEAT-060` · `API-242`~`244` · `ADR-063` · `INT-014` · `SEQ-036` ·
> `ERD-032` · `EVT-001`)이 올라왔고, 2차에서 그것을 승격·재다운로드해 **핵심 타입 잔여 0** 이 됐다.
> 한 바퀴만 돌고 끝내면 그 48건은 설계에 있는데 키트가 못 보는 상태로 남는다.
> ⚠ **`SCREEN-034` 를 pin 에서 걷어냈다** — 포털 키트가 `pin 124 / 서버 123` 으로 어긋났는데, 그 차집합을
> `status` 조회하니 **`deprecated`**(포털 라벨링 화면이 `SCREEN-029` 로 통합)였다. `_retired/` 에 보존돼
> 있어 유실이 아니다. 근거는 `.kit-scope.json` 의 `note_pruned` 에 남겼다.
> ⚠ **화면 키트 7종은 이 라운드 대상이 아니다** — 화면 키트 스코프에는 `nfr` 타입이 없어 영향이 없다.
> ⚠ 자격증명·`--domain` 함정은 그대로다(아래 ★★★ 항목).
>
> ★★★★ **2026-09-02 SYNC — 구현 15 + 화면 7 = 22 키트 전량 (증강 종류 단일화 라운드 · `ADR-059`).**
> 전 키트 **유실 0**(직전 커밋 `version-master.md` 대비 ID 집합 대조). 건수가 는 둘은 **`ADR-059` 승격분**이다 —
> `DOMAIN-007`(소속 도메인) 77→78 · `DOMAIN-003`(`SCREEN-022`·`DFEAT-029`·`ERD-012` 를 pin 해 영향권) 136→137.
> ⚠ **`NFR-009` 가 전역 타입이라 구현 키트 15개 전부**, `UI-*` 카탈로그가 화면 키트 7개 전부에 걸려
> **22 키트가 모두 영향권**이었다 — 한 도메인 변경이라고 그 키트만 돌리면 나머지가 조용히 뒤처진다.
> ⚠ **화면 키트 6종이 첫 실행에서 `변경 N · 유지 0`(전건 재기록)으로 찍혔다** — `.kit-manifest.json`
> 델타 기준선이 없을 때 나는 현상이고 **재동기화 1회로 `유지 N` 으로 정착**한다. 진짜 델타는
> git 의 이전 `version-master.md` 와 대조해 얻는다(그렇게 해서 유실 0을 확인했다).
> ⚠ **`SCREEN-034`(포털 업로드 라벨링)가 `_retired/` 로 이동**했다 — 서버에서 `deprecated`(포털 라벨링
> 화면이 `SCREEN-029` 로 통합)이며 **이번 라운드와 무관한 다른 세션의 변경**이다. 물리 삭제가 아니라 이동이다.
> ⚠ 자격증명 함정은 그대로다 — 이 저장소 설정은 `~/.claude.json` 의 **`projects[<경로>].mcpServers`**
> 아래라 다운로더가 못 찾는다. `LOGICRAFT_API_BASE`/`LOGICRAFT_API_KEY` 를 **직접 넘겨야** 한다.
>
> ★★★ **2026-08-31 SYNC — 15 도메인 전량 (오늘 머지된 PR 3건 반영 · AC 전면 재번호 회수).** 전 키트 **`서버 건수 = pin 건수` 15/15 일치**로 스코프 무변동을 확인했다. 반영분: `INT-013` v13 · `SCREEN-004` v16 · `API-153` v5(PR #163) · `API-058` v4 · `SCREEN-021` v29 · **`AC-1084`·`AC-1085` 신설**(PR #162) · `ADR-012` v10 · `INT-009` v12 · **`NFR-012` v5(15개 키트 전부)**(PR #161).
> ★**이번 SYNC 의 큰 사건은 `acceptance` 전면 재번호다.** 구 AC(3자리) **131건이 서버에서 `deprecated`/`superseded`** 로 바뀌고 신규 AC(4자리) **75건**이 그 자리를 대신한다(`AC-1043` 등으로 통합). 첫 실행에서 다운로더가 **`🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 73건 — 전량 누락`** 을 띄웠는데, **이것은 유실이 아니다** — 126건 전수 `status` 조회로 **폐기 124 + superseded 2 · 폐기가 아닌데 안 내려온 것 0건**을 확인했고, 파일은 **131건 전부 `_retired/` 에 보존**됐다(미보존 0). pin 에서는 그 폐기 ID 를 걷어냈고(근거를 `.kit-scope.json` 의 `note_pruned` 에 기록) 그 결과 pin 이 서버와 정확히 맞는다.
> ⚠ **「서버 건수 < pin 건수」를 보면 먼저 그 차집합의 `status` 를 전수 조회하라** — 폐기면 정상이고 아니면 진짜 유실이다. 건수만 보고 판단하면 둘을 구분할 수 없다.
> ⚠ **다운로더가 이 저장소의 MCP 설정을 스스로 찾지 못한다** — `~/.claude.json` **루트**의 `mcpServers` 를 보는데 이 프로젝트 설정은 **`projects[<경로>].mcpServers`** 아래에 있다. 그래서 `LOGICRAFT_API_BASE`/`LOGICRAFT_API_KEY` 를 **직접 넘겨야** 한다(안 그러면 15개 전부 `rc=1` 로 아무것도 쓰지 않고 멈춘다 — 실측).
> ⚠ **`--domain` 을 반드시 준다** — 빼면 pin 을 무시하고 프로젝트 전량을 받는다.
> 미판정 판정: **승격 99건**(AC 75 · NFR 15 · UC 6 · DFEAT 3 — Tier 1·2) · **기각 243건**(제외 타입) · **보류 2건**(`OSS-*` — Tier 표에도 제외 목록에도 없는 미분류 타입이라 선례대로 판정을 미뤘다).
>
> ★★ **2026-08-29 SYNC — 15 도메인 전량 (역할 축 시안·사양 정합 라운드).** 서버 대비 변경분만 내려왔고 전 키트 **`서버 건수 = pin 건수`** 로 스코프 무변동을 확인했다. 실질 내용 변경은 **전역 타입 둘**이다 — `NFR-020`(v7→v8: *"관리 화면은 검수자 전용"* → **관리자 전용**, 역할 열거에 `ADMIN` 추가)과 `ADR-046`(v7→v8: 「새 관리자 역할을 만들지 않는다」 절이 `ADR-055` 로 **뒤집힌 사실을 표기**). ⚠ **ADR 본문은 고치지 않았다** — 결정 기록이라 기각 근거를 지우면 왜 그때 그렇게 정했는지가 사라진다. 반전 표기만 덧붙였다.
> ⚠ **`--exclude-types` 를 붙이면 D003·D004·D013·D014 에서 파일이 사라진다** — pin 에 남아 있는 제외 타입(SHELL·NAV·REQ·LEGACY)이 `_retired/` 로 밀려나기 때문이다. 이번에 한 번 그렇게 됐다가 **원상복구**했다. `CLAUDE.md` 가 정한 대로 **pin 이 정본**이라, 빼려면 pin 에서 먼저 지워야 한다. 그 넷은 종전대로 **exclude 없이** 돌린다.
> ⚠ 미판정은 이번에도 올라왔고(D001 30건 등) 전부 제외 타입이라 기각했다 — 다음 SYNC 에도 다시 올라오며 그것이 정상이다.
>
> ★ **2026-08-28 SYNC — 15 도메인 전량 재동기화 (관리자 역할 라운드 · PR #160)**. `ADR-055` 가 `ADR-003`·`ADR-043` 을 supersede 해
> **`ADR-003` 이 pin 돼 있던 14개 키트가 폐기된 역할 결정을 들고 있던 상태**를 닫았다 — 두 결정은 `_retired/` 로 옮겨졌고 대체 결정 `ADR-055` 와
> `ROLE-004`(관리자 역할)를 **15개 전 키트에 pin 승격**했다(`permission_role` 은 전역 타입이라 전 키트 대상). 이어서 누적 미판정을 판정해
> **43건을 추가 승격**했다(`AC-124`~`AC-127`·`ADR-046`·`API-194` 등 관리자·역할 축 신규 + 묵은 잔여). 기각은 `CTX-001`(diagram_c4_context 는 제외 타입)과
> 제외 타입 258건이며, 그 258건은 다음 SYNC 에도 다시 올라오고 그것이 정상이다.
> **검증**: 15키트 전건 무열화 통과 · `pin = 파일 + _retired` **미보유 0** · git HEAD 기준선 대비 **유실은 `ADR-003`·`ADR-043` 뿐**(의도된 폐기 이동).
> ⚠ **화면 키트 7종은 이 라운드에서 손대지 않았다 — 여전히 stale 이다**(`SCREEN-024` 서버 v29 vs 키트 v24·v27). 화면 키트는 `.kit-scope.json` pin 이 **없어**
> 그래프 폴백(재현율 약 90%)으로 도는데, 그 조건이 바로 아래 「`--ids` 를 `.staging` 글롭에서 뽑지 마라」가 기록한 **스코프 526→469 조용한 축소** 사고의 조건이다.
> 인벤토리도 평평하지 않다(`_shared/_raw/` 에 DS·NAV · `SD-*` 는 렌더 전용이라 `.md` 가 없다). **pin 을 먼저 확정한 뒤** 별도 라운드로 돌릴 것.
>
> 활성 15 도메인 전량 · 직전 sync **2026-08-26** · 전건 무열화 검증 통과 · 서버 현재 버전과 **불일치 0건**(키트 ITEM 1,739건 직접 대조). ⚠ **2026-08-26 2차 SYNC** — `ADR-013` v10 개정 라운드 반영으로 D013·D004 를 재동기화했고 양쪽 뒤처짐 **0**이다. 그 라운드에서 pin 에 **ADR 2건이 상호 승격**됐다(`ADR-026`→D013 · `ADR-013`→D004 — 두 결정이 서로를 근거로 인용한다).
> 이날 세 가지를 함께 했다 — ① CO-014~CO-017 라운드 반영 ② 묵은 stale 전파 39건 판정·해소 ③ **어느 키트에도 없던 핵심 ITEM 6건 승격**.
> **승격분**: `INTSPEC-003`(시계열 위탁 요청 규격 — D011·D014 본문이 이미 그 ID 를 참조하는데 ITEM 이 없어 **참조가 끊겨 있었다**. `INTSPEC-002` 선례와 동일) · `INT-011`(관제 영상 인입 픽업 → D003·D016) · `INT-012`+`EXTSYS-007`(외부 어노테이션 폴더 반입 → D017) · `ADR-029`(외부 HTTP 위탁 트랜잭션 무보유 → D004·D007·D011·D012·D016) · `ADR-030`(배치 스텝 트랜잭션 경계 → D003·D004·D011·D012).
> **승격하지 않은 것**: `CNT-001`(C4 컨테이너 — 도메인 링크가 없어 Tier 3 조건 미충족) · `DEPLOY-001`(WAR 반입 명세 — 배포 형상 축) · `OSS-001`~`OSS-101`(**`core-item-set.md` 의 Tier 표에도 제외 목록에도 언급이 0건인 미분류 타입** — 판정이 선행돼야 한다).
> **pin 승격 외 미판정 132건은 전부 제외 타입**(MOD·LEGACY·IMPREC·NAV·SHELL·REQ·RFP)이라 기각했다. 다음 SYNC 에도 다시 올라오며 그것이 정상이다.
> ⚠ `--exclude-types` 없이 돌리면 pin 에 있던 제외 타입이 실제 파일로 내려온다(D014 `SHELL-001`·`NAV-001`·`REQ-008` · D013 `SHELL-002`·`NAV-002` · D004 `SHELL-001`). pin 이 정본이라 유실이 아니며, 빼려면 pin 에서 먼저 제거해야 한다. 중간 SYNC 가 남긴 `_retired/` 사본이 함께 있으나 라이브 파일이 정본이고 `_retired/` 는 지우지 않는다.
> ⚠ **2026-08-26 3차 SYNC — D013 단독 재동기화**(포털 범위 확대 라운드 반영). CHANGED 19 · RETIRED 0 · 삭제 0 이고, 미판정에 섞여 있던 `INT-013`(포털 프론트엔드 런타임 임베딩 — `belongs_to_domain=DOMAIN-013` · `provided_by=EXTSYS-006`)을 pin 에 승격해 **81 → 82** 가 됐다. 나머지 미판정 18건은 전부 제외 타입(MOD 3 · DEP 1 · IMPREC 14)이라 기각했고 다음 SYNC 에 다시 올라온다. `CONST`·`FEAT` 전량 누락 경고는 이번에도 그대로다(해소하려면 서버에서 `domain_id` 를 채워야 한다).

| 도메인 | 키트 경로 | ITEM | 구현 현황 (설계 쪽 주장) | 설계 0건 단계 |
|---|---|---|---|---|
| DOMAIN-001 사용자·권한 | docs/design/사용자권한-DOMAIN-001/ | 108 | implemented 34 / planned 55 / (미기재) 19 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, FEAT 상위 기능 |
| DOMAIN-003 영상·프레임 수집 | docs/design/영상프레임-수집-DOMAIN-003/ | 181 | implemented 82 / in_progress 3 / verified 1 / planned 63 / (미기재) 32 | CONST 상수값 |
| DOMAIN-004 AI 보조 라벨링 | docs/design/ai-보조-라벨링-DOMAIN-004/ | 175 | implemented 75 / in_progress 7 / planned 61 / (미기재) 32 | TEST 통합시험 |
| DOMAIN-005 검수 | docs/design/검수-DOMAIN-005/ | 138 | implemented 63 / in_progress 1 / verified 1 / planned 51 / (미기재) 22 | CONST 상수값 |
| DOMAIN-006 통계·대시보드 | docs/design/통계대시보드-DOMAIN-006/ | 75 | implemented 19 / planned 47 / (미기재) 9 | CONST 상수값, ERD 데이터 계층, EVT 이벤트 계약, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-007 데이터 증강 | docs/design/데이터-증강내보내기-DOMAIN-007/ | 110 | implemented 36 / in_progress 1 / planned 51 / (미기재) 22 | CONST 상수값 |
| DOMAIN-009 게시판·공지 | docs/design/게시판공지-DOMAIN-009/ | 75 | implemented 22 / planned 46 / (미기재) 7 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-010 라벨링 | docs/design/라벨링-DOMAIN-010/ | 201 | implemented 101 / in_progress 1 / planned 68 / (미기재) 31 | INT 외부 연동 |
| DOMAIN-011 마킹 | docs/design/마킹-DOMAIN-011/ | 87 | implemented 23 / in_progress 2 / planned 47 / (미기재) 15 | CONST 상수값 |
| DOMAIN-012 비식별화 | docs/design/비식별화-DOMAIN-012/ | 118 | implemented 36 / in_progress 2 / planned 59 / (미기재) 21 | CONST 상수값 |
| DOMAIN-013 포털 | docs/design/포털-DOMAIN-013/ | 127 | implemented 42 / in_progress 3 / planned 67 / (미기재) 15 — **2026-09-06 SYNC(변경 26 · 유실 0)** + PR #195 IMPREC 8건(`IMPREC-394`~`401`) 반영 | CONST 상수값, FEAT 상위 기능 |
| DOMAIN-014 시스템 설정 | docs/design/시스템-설정-DOMAIN-014/ | 130 | implemented 55 / in_progress 1 / planned 56 / (미기재) 18 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험 |
| DOMAIN-015 작업 배정 | docs/design/작업-배정-DOMAIN-015/ | 73 | implemented 20 / planned 48 / (미기재) 5 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-016 관제 통지 | docs/design/관제-통지-DOMAIN-016/ | 93 | implemented 26 / in_progress 2 / planned 51 / (미기재) 14 | CONST 상수값, SD 고충실 시안 |
| DOMAIN-017 외부 산출물 이관 | docs/design/외부-산출물-이관-DOMAIN-017/ | 106 | implemented 33 / in_progress 4 / planned 58 / (미기재) 11 | CONST 상수값, C4 컴포넌트 |

## 작업 규칙 (키트 워크플로)
1. **키트가 설계 진실원** — 도메인 규칙·제약·빌드순서는 키트에서 읽는다. 키트 파일은 read-only 산출물 — **직접 수정 금지**.
2. **기능/시나리오를 수정하려면**: `/mc-logi-update` 로 logicraft 설계를 먼저 수정 → `/mc-logi-implement-kit` SYNC 로 로컬 키트 재동기화 → 그 다음 코드 반영.
3. **구현 착수는** `/mc-logi-implement` — 키트 신선도 게이트부터 시작한다.
4. **구현 완료 시** logicraft 에 IMPREC 추적 기록 (mc-logi-implement Phase 5 가 수행).
5. 작업 전 키트가 오래됐으면(`version-master.md` last sync 확인) SYNC 먼저.

## 도메인별 주의 (상세는 각 IMPLEMENTATION.md)
- **공통**: 위 「구현 현황」은 **ITEM 이 스스로 적은 주장**이며 코드와 대조된 값이 아니다. 대조는 `/mc-logi-implement-review` 의 몫이다.
- **공통**: 스코프는 `.kit-scope.json` pin 이 정본이다 — 서버 `--domain` 필터는 `domain_id` 컬럼만 봐서 재현율 59% 다(이 프로젝트는 1,080 ITEM 중 절반 가까이가 `domain_id` 미설정). pin 은 `kit-export` 전수 그래프(1-hop 도메인 확장)로 판정했고 **키트와 함께 커밋**해야 다른 PC 가 같은 키트를 얻는다.
- **공통 — 미판정(`pending`) 처리 규칙 (2026-08-21 확정)**: 다운로더는 매 SYNC 마다 「그래프상 닿는데 pin 에 없는 ITEM」을 `pending` 에 채운다. 판정 기준은 `core-item-set.md` 이며 **`code_module`(MOD)·`legacy_artifact`(LEGACY)·`app_shell`(SHELL)·`navigation_tree`(NAV)·`requirement`(REQ)·`rfp_item`(RFP)은 제외 타입이라 기각**한다. 이 여섯은 기각해도 다음 SYNC 에 다시 `pending` 으로 올라오며 **그것이 정상**이다(제외 판정을 기록할 필드가 스키마에 없다). ⚠ 그럼에도 **D013·D014·D004 키트에는 SHELL·NAV·REQ 가 이미 pin 에 들어 있다** — 과거 판정의 잔재이며 빼면 유실 위험이 있어 그대로 뒀다(축이 갈려 있음을 알고 쓸 것). **2026-08-26 SYNC 부터는 그 pin 대로 실제 파일까지 내려온다.**
- **공통**: 폐기(`deprecated`/`superseded`) ITEM 은 키트에 담지 않는다. 「설계 0건 단계」는 **다운로드 누락이 아니라 설계 결손**이다.
- **`CONST` 가 대부분 도메인에서 0건인 것은 정상** — 프로젝트 전역 CONST 가 2건뿐이고 둘 다 COCO 라벨링 축이라 D004·D010 에만 들어간다.
- **DOMAIN-003**: 2026-08-21 SYNC 에서 시계열 건너뛰기 축 8건(`ADR-049`·`ADR-050`·`API-212`~`API-214`·`AC-049`~`AC-051`)이 pin 에 승격돼 처음 내려왔다. 그 전까지는 미판정이라 키트에 **한 건도 없었다** — 이 축을 로컬 키트로만 판정했다면 「설계에 없다」고 오판했을 구간이다.
- **DOMAIN-006**: ⚠ **구 서술 폐기(2026-08-21)** — *"활성 `use_case`·`acceptance`·`test_scenario` 가 전부 0건"* 은 더 이상 사실이 아니다. `UC-033`·`AC-029`~`AC-031`·`TEST-006` 이 미판정 상태로 서버에만 있다가 이번 SYNC 에서 pin 승격돼 들어왔다. **여전히 0건인 것은 `erd`·`domain_event`·`diagram_sequence`** 이며(유일 ERD-003 은 폐기) 데이터 모델 축은 그대로 결손이다.
- **DOMAIN-009 · DOMAIN-001 · DOMAIN-015**: 0건 단계가 6~10개로 많다 — 검증 축(UC/AC/TEST)이 통째로 비어 있다.
- **DOMAIN-017**: ⚠ **구 서술 폐기(2026-08-21)** — *"implemented 1 / 자기 소속 ITEM 23건뿐"* 은 낡았다. pin 60건이고 구현 주장이 21건(+verified 6)이며, `UC-036`·`AC-052`~`AC-054`(이관 이력 조회 축) 신설로 검증 축이 생겼다. 타도메인에서 끌어온 근거 8건(①`DFEAT-057.persists_in_tables` 와 `ERD-031` FK 가 가리키는 ERD 5건 — ERD-010·012·017·019·025 ②`ADR-048` 을 상호 인용한 ADR-042·EVT-005·UC-018)은 그대로 유지된다.
<!-- mc-logi-kit:end -->

<!-- mc-logi-screen-kit:start (자동 관리 — 직접 수정 금지, mc-logi-screen-kit 재실행 시 갱신) -->
# Logicraft 화면 키트

이 레포는 logicraft 화면 설계 기반으로 프론트엔드를 구현한다. **화면 작업 전 아래 키트의 SCREENS.md 를 먼저 읽을 것.**

> ⚠ **후속 라운드(같은 날) — 평평 디렉터리 정리 + 화면 2건 편입.**
> ①**키트 루트의 평평한 타입 디렉터리를 전부 지웠다**(추적 파일 **2,423개** — 앞 문단의 「1,155개」는 과소
> 집계였다). 지우기 전에 그 내용이 정본 경로에 있는지 대조했고, 이름이 정본에 없던 `SD-*` 26건도
> 실체는 `screens/*/design/`(`_sd-meta.md`·`design-main.html/css`·`design-notes.md`)에 있고 버전은
> `version-master.md` 가 추적한다. 문서의 경로 참조도 **0건**이었다. `bin/`(와이어프레임 생성기)은 보존.
> ⇒ 그때는 키트 최상위가 `screens/`·`_shared/`(+통합만 `bin/`·`_retired/`) 뿐이 됐다.
>
> ⚠⚠ **그러나 그 상태는 유지되지 않았다 (2026-09-08 실측) — 「지웠다」로 읽지 말 것.**
> **다음 SYNC(`050a09aa8`)가 그대로 되살렸다.** 지금 통합 키트 최상위에 평평 디렉터리가
> **`acceptance`·`api_endpoint`·`app_shell`·`screen_design`·`screen_spec`·`ui_component`·`use_case`**
> 로 다시 있고 **추적 파일이 198개**다.
> ⇒ **지우는 것으로는 닫히지 않는다.** 원인은 다운로더 인자(`--out` 을 키트 루트로 주는 것)이므로
> **그것을 틀리지 않는 것이 유일한 방어**다(스킬 §함정 1 · 화면 키트는 `--out` 이 `<키트>/.staging`).
>
> ★**위험은 「낡는다」가 아니라 「정본으로 읽힌다」이다.** 그 사본들은 **arranger 가 관리하지 않아
> SYNC 를 돌려도 갱신되지 않는데 파일 이름이 정본과 같다.** 실측: `screen_spec/_raw/SCREEN-042.json`
> 이 **v11** 인데 서버는 **v20** 이었다(정본 경로 `screens/` 는 SYNC 로 따라왔다).
> 누가 `screen_spec/SCREEN-042.md` 를 열면 **아홉 판 전 사양을 현재 사양으로 읽는다.**
> ⇒ **키트에서 사양을 볼 때는 `screens/`·`_shared/` 만 본다.** 평평 디렉터리는 쳐다보지 말 것.
> ②**`SCREEN-044`(포털 증강)·`SCREEN-045`(포털 업로드 영상 마킹)를 폐포와 함께 편입**했다 — 소비 API 6건
> (`API-232`·`233`·`238`~`241`)과 UC 경유 AC 2건(`AC-1070`·`1071`). 통합 키트는 전 화면 정본이라
> 서버 활성 38건과 이제 같다.
> ★★**소형 키트에는 담당 화면만 둔다 (2026-09-05 사용자 확정, 구속)** — 그래프로 닿는다는 이유로 새 화면을
> 편입하지 않는다. 전 화면이 필요하면 **통합 키트(38화면)** 를 쓴다. 후보 25건(D003 +10 · D010 +7 ·
> D001 +4 · D005 +2 · D015 +2)은 **매 SYNC 마다 pending 에 다시 올라오며 그때마다 기각**한다 —
> 각 키트 `.kit-scope.json` 의 `note_pruned` 에도 같은 문구를 박아 뒀다. **되살리지 말 것.**
> ⚠ **화면의 소속으로 키트 귀속을 정할 수 없다** — `screen_spec` **38건 전부 `domain_id` 가 없다**(실측).
> 소형 키트의 화면 목록은 그래프가 아니라 **손으로 큐레이션한 것**이며, 그래서 새 화면 편입이 사실 판정이
> 아니라 결정이다. **위 확정이 그 결정이다.**
>
> ★★★ **2026-09-05 SYNC — 7개 키트 전부 (구현 키트 라운드에 이어 화면 축 따라잡기).**
> 계기는 **뒤처짐이 실측으로 확인된 것**이다 — 로컬 `_raw` 의 `current_version` 을 서버와 대조해
> 통합 48 · D010 19 · D003 19 · D005 18 · D001 18 · D009 16 · D015 16 건을 확인하고 돌렸다.
> **결과: 정본 경로(`screens/`·`_shared/`) 뒤처짐 7키트 전부 0** · 재실행 델타 `변경 0 · 유지 467` 로 수렴 ·
> 직전 커밋 `version-master.md` 대비 **유실 0** · **`design.html`/`design.css` 로컬 원본 변경·삭제 0**.
> ★**`--scope-file` 만으로는 pin 이 적용되지 않는다 — `--domain` 이 함께 있어야 한다.**
> 다운로더가 `scopeByClient = Boolean(domain) && …` 로 판정하므로, `--domain` 을 빼면 pin 분기에
> 아예 들어가지 않고 **프로젝트 전량(1,646건)** 을 받는다. dry-run 이 그것을 잡았다(pin 468 vs 서버 1,646).
> 통합 키트의 pin `domain` 값은 **`DOMAIN-000`** 이므로 그 값을 그대로 넘긴다.
> ★**pending 판정을 「따라잡기」와 「스코프 확대」로 갈랐다** — 기존 pin 화면이 소비하는 API·연결된 UC/AC·
> 공유자산(ROLE·CONST) **42건만 승격**하고, **새 화면 25건과 그에 딸린 SD 6건은 보류**했다. 도메인별
> 소형 키트는 담당 화면만 담는 의도적 축소판이라, 그래프로 닿는다고 새 화면을 넣으면 키트 정체성이 바뀐다.
> ⚠ **`SCREEN-034` 를 통합 pin 에서 걷어냈다**(서버 `deprecated` — `SCREEN-029` 로 통합). `_retired/` 보존, 유실 아님.
> ⚠ **통합 키트에 아직 없는 화면 2건: `SCREEN-044`(포털 증강) · `SCREEN-045`.** 화면 편입은 폐포(소비 API·UC·AC·렌더)를
> 함께 승격하는 별도 라운드가 필요하다 — `SCREEN-039` 때와 같은 이유다.
> ⚠ **키트 루트에 평평한 타입 디렉터리가 남아 있다**(`use_case/`·`api_endpoint/`·`screen_spec/` 등 키트당 8~10개,
> 추적 파일 1,155개). 과거 `--out` 을 키트 루트로 준 실수(스킬 §함정 1)의 잔재이며 **arranger 가 관리하지 않아
> 영원히 낡는다.** 실제로 이번에 남은 「뒤처짐」은 전부 여기에 있었다(정본 경로는 0). 정리는 추적 파일 삭제라 별도 판단.
> ⚠ **`.staging-s1`~`s37c` 옛 스냅샷 7개**가 통합 키트에 있다(미추적). 버전 대조 시 이것까지 세면 뒤처짐이 부풀려진다.
>
> ★★ **2026-08-29 SYNC — 7개 키트 전부 · `--ids` 대신 pin(`--scope-file`)으로 돌렸다.** 전 키트 **`서버 건수 = pin 건수`**(통합 539·D010 225·D005 172·D001 168·D009 168·D003 166·D015 163)이고, 직전 커밋 `version-master.md` 대비 **유실 0 · 신규 0**. 통합 키트는 **32 → 36 화면**이 됐다(SCREEN-040~043 편입). 세션은 통합 s33 · 도메인 s15(D003 만 s18)다.
> 이 라운드가 바꾼 것: **`SCREEN-002` 가 「역할 클레임」에서 「관리자 등록」으로 전면 개정**됐고(역할 선택 라디오가 사양에서 사라졌다), 관리자 화면 6곳의 `검수자 권한`·`검수자 전용` 서술이 실제 인가(`ADMIN_ONLY`)에 맞춰졌으며, `UI-110` 배지 카탈로그에 **관리자·미매핑** 변형이 생겼다. 와이어프레임 7건·시안 9건을 재게시했고 전건 **`replace` opcode 0** 으로 무손상을 증명했다.
> ⚠ **`검수자 권한` 을 일괄 치환하지 마라** — *"조회도 검수자 권한만으로는 되지 않는다"*(검수자를 **하한**으로 말함)와 *"관리자가 검수자 권한을 계층으로 물려받는다"* 는 **정당한 서술**이다. 실제로 `GET /v1/users` 는 `hasRole('REVIEWER')` 이고 `PATCH` 만 `ADMIN` 이다. 바꿔야 하는 것은 **유효창이 가산되는 밑바탕 권한**을 검수자로 적은 자리뿐이다.
>
> ★ **2026-08-28 SYNC — 7개 키트 전부 · 그리고 이제 `.kit-scope.json` pin 이 있다.** 그전까지 화면 키트는
> **pin 이 없어 그래프 폴백(재현율 약 90%)** 으로 돌았고, 그게 아래 ★★ 가 기록한 **스코프 526→469 조용한
> 축소** 사고의 조건이었다. 이번에 **직전 커밋의 `version-master.md` 표를 모집단으로** pin 을 세워 그
> 위험을 구조적으로 닫았다. 화면 8건이 갱신됐다(`SCREEN-003` v10→v12 · `024` v27→v29 · `025`·`027`·
> `040`·`041`·`042`·`043`). `ROLE-004`(관리자 역할)를 전 키트에 승격했다.
> **검증**: 7키트 전건 **유실 0** · 신규는 의도한 `ROLE-004` 하나뿐 · `배치본 파일 − 표` 가 정확히
> **렌더 전용 `SD` 건수**와 일치 · `design.*` 로컬 원본 변경·삭제 **0건**.
> ⚠ **다운로더는 `--domain` 이 있을 때만 pin 을 본다** — 빼고 부르면 pin 을 무시하고 프로젝트 전량
> (1,358건)을 받는다. 이번에 실제로 그렇게 됐고 **pin 건수와 대조해서 잡았다.** 스코프는 축소만
> 위험한 게 아니라 확대도 조용히 일어난다 — **매번 「서버 N건」이 pin 건수와 같은지 확인할 것.**
> ⚠ 화면 키트 스코프에는 **`adr`·`nfr` 타입이 없다** — 그 축의 🚨 유실 경고는 설계상 정상이며
> 미판정에 올라오는 `NFR-*` 도 기각 대상이다. `SCREEN-039` 도 여전히 스코프 밖이다(별도 폐포 라운드).
>
> **2026-08-26 SYNC — 7개 키트 전부** · 서버 현재 버전과 **불일치 0건** · **정적 렌더 미러 33/33 최신**(`source_hash` 대조).
> 그날 화면 5건의 와이어프레임을 재생성·재게시했다 — `SCREEN-012`·`022`·`024`·`030`·`031`(모달 포함). 모달 렌더는 생성기가 만들지 않는 표면이라 내용이 현재 사양과 일치함을 확인한 뒤 동일 바이트로 재게시해 스탬프만 갱신했다.
>
> ★★ **`--ids` 를 `.staging` 글롭에서 뽑지 마라 — 통합 키트 스코프가 526 → 469 로 조용히 줄었다.** `.staging` 은 gitignore 대상이라 이전 판에 있던 ITEM 이 빠져 있을 수 있고, 그렇게 줄어든 목록으로 SYNC 하면 배치본에 파일만 남아 **영구히 낡는다**(실제로 `AC-095`~`098` 이 그 상태였다). 복원 근거는 **직전 커밋의 `version-master.md`** 이며, 매 SYNC 뒤 `git show HEAD:<version-master> 대비 유실 0` 과 `배치본 파일 − 표 = 0` 두 검사를 돌린다.
>
> ★ **통합 키트가 정본이다** — 도메인별 소형 키트는 화면 1~5개만 담으므로, 화면을 동기화하거나
> 사양을 판정할 때는 **먼저 `klid-authoring-screens/`(36화면 통합)를 본다.** 소형 키트만 보면
> 그 스코프 밖 화면의 변경이 절대 반영되지 않는다.

| 키트 | 화면 수 | 키트 경로 | ui_component 카탈로그 | last sync | 표 ITEM |
|---|---|---|---|---|---|
| **전체 통합 (38화면)** | 38개 (SCREEN-001~045 중 38건 — 서버 활성 전건) | docs/screen-design/klid-authoring-screens/ | 145건 | **2026-09-05 (s35)** | 477 |
| DOMAIN-010 라벨링 | 2개 (SCREEN-005, SCREEN-026) | docs/screen-design/라벨링-DOMAIN-010/ | 144건 | 2026-09-05 (s16) | 227 |
| DOMAIN-005 검수 | 2개 (SCREEN-018, SCREEN-019) | docs/screen-design/검수-DOMAIN-005/ | 144건 | 2026-09-05 (s16) | 180 |
| DOMAIN-015 작업 배정 | 1개 (SCREEN-012) | docs/screen-design/작업-배정-DOMAIN-015/ | 144건 | 2026-09-05 (s16) | 166 |
| DOMAIN-003 영상·프레임 수집 | 1개 (SCREEN-009) | docs/screen-design/영상프레임-수집-DOMAIN-003/ | 144건 | 2026-09-05 (s19) | 163 |
| DOMAIN-009 게시판·공지 | 4개 (SCREEN-030, SCREEN-031, SCREEN-036, SCREEN-037) | docs/screen-design/게시판공지-DOMAIN-009/ | 144건 | 2026-09-05 (s16) | 172 |
| DOMAIN-001 사용자·권한 | 5개 (SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-024) | docs/screen-design/사용자권한-DOMAIN-001/ | 144건 | 2026-09-05 (s16) | 176 |

## 작업 규칙 (화면 키트 워크플로)
1. **키트가 설계 진실원** — 화면 규칙·제약·빌드순서는 키트에서 읽는다. 키트 파일은 read-only 산출물 — **직접 수정 금지**.
2. **화면/시나리오를 수정하려면**: `/mc-logi-update` 로 logicraft 설계를 먼저 수정 → `/mc-logi-screen-kit` SYNC 로 로컬 키트 재동기화 → 그 다음 코드 반영. (코드만 고치고 설계를 안 고치면 다음 SYNC 때 충돌)
3. **구현 착수는** `/mc-logi-screen-implement` — 키트 신선도 게이트부터 시작한다.
4. **구현 완료 시** logicraft 에 IMPREC 추적 기록 (mc-logi-screen-implement Phase 5 가 수행).
5. 작업 전 키트가 오래됐으면(`version-master.md` last sync 확인) SYNC 먼저.

## 공통 주의
- ★ **`design-main.html`/`.css` 는 서버 게시본의 미러**라 SYNC 가 서버 판으로 되돌린다. 로컬에서 미러를 고치면 SYNC 한 번에 사라지므로, 순서는 **`design.html`(로컬 원본) 수정 → `upload_design_render` 로 게시 → 미러가 따라옴** 이다. (2026-08-21 SYNC 실측: `design.*` 원본 변경 0건 · 삭제 0건.)
- ⚠ **`SCREEN-039`(외부 산출물 이관 이력)는 어느 화면 키트에도 없다.** 통합 키트 스코프가 SCREEN-038 까지라 이번 SYNC 에서도 들어오지 않았다. 그 화면의 설계는 **`docs/design/외부-산출물-이관-DOMAIN-017/screen_spec/`** 에 있다. 통합 키트에 넣으려면 화면 폐포(소비 API·UC·AC·렌더)를 함께 승격하는 별도 SYNC 가 필요하다.
- ⚠ **게시본으로 접근성을 판정하지 마라 (2026-08-29 실측)** — 게시 sanitizer 가 `scope`·`autocomplete`·`inputmode`·`min` 을 **지운다**(`aria-*`·`role`·`title` 은 보존). 시안 원본에는 있는데 게시본에만 없으므로, 게시본만 보면 실제보다 나쁘게 나온다. 우리 잘못이 아니고 정규화기를 늘려 맞출 일도 아니다 — LogiCraft 에 신고돼 있다. 판정은 **로컬 원본**으로 한다.
- ⚠ `.staging/` 은 gitignore 대상이라 워크트리를 바꾸면 델타 기준선이 사라지고 **첫 SYNC 가 전건 `NEW` 로 찍힌다.** 재동기화 1회로 `UNCHANGED` 로 정착하며, 진짜 델타는 git 의 이전 `version-master.md` 와 대조해 얻는다.

## 도메인별 주의 (상세는 각 SCREENS.md §주의)
- **DOMAIN-010**: SCREEN-026(프리셋 관리)은 UC-032 만 연결되고 AC(수용기준) 링크·screen_design(SD) 이 없다(logicraft 쪽 보강 여지). DOMAIN-010 소속인 SCREEN-010(로드 버전 선택)·SCREEN-035(라벨 관리)는 이 소형 키트 스코프 밖이며 **통합 키트에는 있다**.
- **DOMAIN-005**: SCREEN-018·SCREEN-019 2화면. 변경 알림·RETIRED 없음.
- **DOMAIN-015**: SCREEN-012 는 AC(수용기준) 링크가 아직 없다(UC-029 에 covered_by 미등록) — 화면 키트는 정상이고 logicraft 쪽 보강 여지다.
- **DOMAIN-003**: SCREEN-009 1화면. 변경 알림·RETIRED 없음.
- **DOMAIN-009**: 화면 4개 모두 use_case·acceptance 미연결. SCREEN-031(공지 상세)은 와이어프레임 렌더 2건(main + delete-confirm)을 갖는다.
- **DOMAIN-001**: SCREEN-001·SCREEN-003·SCREEN-004(세션 인계·접근 거부·개발용 로그인)는 인증 전이거나 셸이 없는 화면이라 SHELL-001·NAV-001 미적용이고 consumes_apis·required_roles·UC/AC 링크가 모두 없다. SCREEN-002(관리자 등록 — 관리자가 0명일 때만 열리는 부트스트랩 창구)는 API-007 만 연결된다. ⚠ 구 이름 「역할 클레임 화면」은 폐기다(`ADR-055`) — 역할을 고르는 화면이 아니라 부여 역할이 관리자로 고정된 화면이다. SCREEN-024(사용자 관리)만 SHELL-001+NAV-001 적용 대상이며 UC-030(사용자 계정·역할 관리)이 역참조로 연결된다(AC 없음).
<!-- mc-logi-screen-kit:end -->

