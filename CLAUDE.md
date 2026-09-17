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
- **★저작도구와 포털은 서로의 DB 에 접근하지 않는다 (2026-08-31 사용자 확정, 구속)** — 데이터 교환은 **API** 로 설계한다. ⚠ 구 서술 *"외부 채널은 포털 DB 공유"* 는 **폐기**. 상세·현황·철거 상태는 `docs/rules/klid-portal.md` 가 정본이다(여기에 복제하지 않는다).
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
| 포털 회원 | `PORTAL_USER` | 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 기간 내 다운로드. **본인 자산(이미지/영상) 업로드 + 수동 라벨링(BBOX/POLYGON)** — ADR-013 예외(2026-07-17). **라벨링 AI 보조(AI 탐지·AI 분할·AI 자동 추적)는 제공**(2026-09-15 · `ADR-013` v24 — 포털 전용 창구). 키포인트·선택 객체 AI 추적·트랙 번호 편집·검수·버전관리는 여전히 없음. **메타·이벤트 어노테이션 수정·추가는 제공**(2026-08-26 확정 — 포털 전용 저장소 적재·단방향) |

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
    - **★관제 계정 호출은 저작도구 WAS 경유다 (2026-09-10 사용자 확정)** — 브라우저는 관제를 직접 부르지 않고 저작도구 API(`POST /v1/auth/control-tokens` 갱신 · `DELETE /v1/auth/control-session` 로그아웃)를 부르며, 저작도구 서버가 관제 계정 창구로 중계한다. 관제 주소는 **관제 계정 창구 설정(`authoring.control-account.url`)** 이며 통지용 클라이언트(통지 토큰·통지 on/off)와 **분리**한다. ⚠ **구 서술 폐기(2026-09-14 · `CO-20260914-관제계정창구-주소분리`)** — *"관제 통지 수신처 설정을 재사용"* 은 틀렸다. 관제는 계정 창구(`/api/account/`)와 데이터셋 창구(`/api/data-set/`)를 **서로 다른 WAS** 에 두어, 통지 WAS 를 넣은 현장에서 세션 연장이 **404 로 전부 실패**했고 누적 실패로 서킷이 열려 로그아웃 중계까지 막혔다. 지금은 **폴백 없음**(비어 있으면 통지 주소로 대체하지 않음) · **기본값 비움**(관제 채널 배포본은 반드시 설정) · **갱신/로그아웃 서킷 분리**다. **되살리지 말 것.** ⚠ 같은 라운드에서 **`type=refresh` 토큰의 저작도구 API 인증을 거부**하도록 막았다 — 관제 refresh 토큰(7일)이 같은 비밀키로 서명돼 그대로 우리 API 자격증명이 되던 구멍이다.
    - **팝업은 관제 세션 팝업과 완전히 같다** — 버튼 「로그아웃」(관제 로그아웃 API → 토큰 키 삭제 → 관제 로그인, 관제 탭도 함께 로그아웃)·「로그인 연장」 둘뿐, 닫기·X·ESC·배경 클릭 없음. ⚠ 같은 날 「닫기+로그인 연장」으로 정했다가 사용자가 뒤집었다 — **세 번째로 되돌리지 말 것.**
    - **실패 결말**: 관제가 갱신을 **거절**(`error≠0` — 다른 기기 등에서 서버 세션이 끊김)하면 **즉시 로그아웃**(단 저장소를 재확인해 다른 탭이 새 토큰을 저장했으면 그것을 쓴다) · 네트워크·5xx 일시 장애는 현재 토큰으로 계속 · 401 뒤 실패는 즉시 로그아웃 · 강제 로그아웃은 미저장 확인 없이 이동(미저장 편집이 있으면 팝업에 경고). ⚠ 저장 형식이 한 글자라도 다르면 **관제 탭이 깨진다.** ⚠ 구 서술 *"관제 채널은 저장소를 읽기만 하고 저작도구가 쓰지 않는다 · 내부 채널에는 갱신 창구가 없다 · 저작도구는 세션을 연장하지 못한다"* 는 **폐기**. 포털 채널(Host 메모리 · 저장소 미사용)은 **무변경**.
  - ⚠ `RelayAdminViewPage` 는 저작도구 뷰가 아니라 **중계서버 관리자** iframe 화면이다(외부 시나리오 문서의 오기).
- **외부 시스템 양방향 통합(M2M) deprecated**: 관제서버/외부 학습데이터 시스템과의 송수신 API 및 M2M 인증 인프라는 미운영. 재구축 시 별도 설계 필요.
- **예외 — 저작도구 → 관제서버 단방향 outbound 완료/수정 통지**: 영상 단위 작업의 검수 완료 시 `TASK_COMPLETED` 이벤트, 검수 완료 후 라벨/메타 수정 시 `TASK_MODIFIED` 이벤트를 관제서버 inbound SPI 로 push (비동기). 동일 작업 ID(=`LS_DATA_RAW.RAW_SN`) 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신. 양방향 M2M 인증 인프라는 부활하지 않으며, 본 통지는 인계 토큰 또는 IP 화이트리스트로 보호.

### 배치 파이프라인 (인증 불필요)

> **정본: `docs/rules/klid-batch-pipeline.md`** — 온디맨드라 자동으로 실리지 않는다(2026-09-17, 150k자 한도로 분리). 아래 신호가 하나라도 걸리면 **그 파일을 `Read` 한 뒤** 판단한다. 요약만 보고 구현·판정하지 않는다. **수정도 그 파일에서 한다**(두 곳에 적으면 한쪽만 갱신된다).
>
> **읽어야 하는 신호**
> - 심볼: `BatchPipeline*` · `BatchOrchestrator` · `ControlTrainingVideoScanJob` · `TrainingVideoIngestService` · `IngestDeidentifyBridge` · `AsyncDeidentifyRunner` · `DeidentifyStep` · `MarkingBatchBridge` · `VlmTimeseriesStep` · `VlmSubmit*` · `VlmResultService` · `VlmWithheldResumeRunner` · `VlmDescriptionPolicy` · `BatchTransitionService` · `KPST*`
> - 경로: `/v1/vlm/callback` · `/v1/videos/{rawSn}/stream` · `describe` · `describe-sub` · `verify`
> - 테이블·컬럼·설정: `LS_DATA_INGEST` · `VRFC_EVNT_TYPE_CD` · `LS_DATA_RAW.DATA_STTS_CD` · `LS_RAW_DATA_STATUS` · `LS_BATCH_PROC_LOG` · `LS_DATA_META` · `LS_VRFC_EVNT_QSTN` · `FRME_INTV_NOCS` · `SKIP_REASON_*` · `excluded-src-types` · `stale-timeout-minutes` · `callback-timeout-minutes`
> - 개념어: 적재·인입 · 선두 비식별 · 비식별 제외 · 마킹 · 시계열 위탁 · `event_type` · `frame_policy` · 미결 회수 · R12/R13 · `COMPLETED` 상태

**불변식 — 읽지 않아도 틀리면 안 되는 것** (요약이라 응답 코드·예외·경로 목록은 정본에만 있다)

1. **영상 적재는 관제가 `LS_DATA_INGEST` 에 직접 INSERT 하고 저작도구 배치가 폴링한다.** `MNG_*` 공유 테이블은 DROP 됐다 — 그것을 읽는 설계로 되돌리지 말 것.
2. **순서는 비식별(적재 직후 선두 자동) → 마킹(비식별 영상 대상) → 시계열 위탁 → 마킹 위치 프레임 추출(원본+비식별 2벌) → YOLO(원본만) → SAM2 → 보간**이며 `BatchPipelineConfig` 한 곳에서 관리한다. 출처유형 비식별 제외(기본 `GENERATED`)는 위탁 대신 원본 복사로 **비식별 단계를 끝내는 것**이지 단계를 건너뛰는 것이 아니다.
3. **외부 위탁(시계열·KPST)은 논블로킹 제출이고 회수기는 축마다 다르다** — 시계열은 미결 스위퍼가 재위탁, 비식별은 폴링 잡이 ACK 유예 만료로 실패 종결(영상 `'F'`). 미결 임계는 ACK 창·콜백 창 둘이며 하나로 덮지 않는다.
4. **시계열 위탁은 `describe`·`describe-sub` 두 창구다**(`verify` 미연동) · 창구마다 별도 request_id · `event_type` 은 위탁을 막지 않고 조달값을 그대로 싣는다(지어내지 않음) · `frame_policy` 에 `framerate` 를 싣지 않는다.
5. **적재 축은 두 번 뒤집혔다 — 세 번째로 되돌리지 말 것.** 승인 영상의 서술이 실제로 바뀌면 재검수+통지(R13), 같으면 no-op.
6. **`COMPLETED` 는 배치 단계·검수 워크플로·FE 표시 코드 세 축에 있고 셋 다 정상이다** — 하나로 통일하지 말 것.

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

> **정본: `docs/rules/klid-labeling-version.md`** — 온디맨드라 자동으로 실리지 않는다(2026-09-17, 150k자 한도로 분리). 아래 신호가 하나라도 걸리면 **그 파일을 `Read` 한 뒤** 판단한다. 요약만 보고 구현·판정하지 않는다. **수정도 그 파일에서 한다**(두 곳에 적으면 한쪽만 갱신된다).
>
> **읽어야 하는 신호**
> - 심볼: `LabelService` · `VersionService` · `LabelContentHasher` · `ReviewApprovalGate` · `ReviewRecheckMarkListener` · `ControlNotifyDebouncer` · `LsMonNotiAcmlRepository` · `EvntAnnoService` · `DatasetExportBridge` · `DatasetExportFailureRecoverer` · `PresetLabelLookupService` · `AutolabelOnlineService` · `labelColor` · `OverlayLayer.newLabelFrom` · `labelMasterLookup` · `ObjectClassTree` · `HistoryPanel`
> - 경로: `/v1/versions/*/diff` · `/diff-with-working` · `/sam2-track` · `/sam2-segment`
> - 테이블·컬럼: `LS_LABEL` · `COLR_VL` · `DTCT_TYPE_CD` · `LS_LABEL_PRESET_CODE` · `LS_DATA_LBL` · `LS_LABEL_VERSION` · `LS_DATA_LBL_HSTRY` · `REVLT_YN` · `LS_DATASET_EXPORT` · `OUTPUT_PATH_NM`
> - 개념어: 프리셋 · 라벨 색상 · 버전·diff·롤백 · 재검수·재승인 · export 재생성 · `v{n+1}` · `TaskModifiedEvent` · `exportRegenerated`

**불변식 — 읽지 않아도 틀리면 안 되는 것** (요약이라 응답 코드·예외·경로 목록은 정본에만 있다)

1. **라벨 마스터(`LS_LABEL`)가 단일 진실원이다** — 프리셋은 `LBL_ID` 로 실시간 join, 형태는 마스터 소유, 오토라벨 매칭은 검출 클래스 축(`DTCT_TYPE_CD`).
2. **표시 색상 판정은 `labelColor.getLabelDisplayColor` 한 곳**이고, 라벨 생성 payload 는 `OverlayLayer.newLabelFrom` 을 거쳐 **`labelId` 를 반드시 채운다**(빠지면 저장 후에만 색·이름이 끊긴다). 하드코딩 색상표를 되살리지 말 것.
3. **작업 임시저장(`LS_DATA_LBL`)과 학습데이터 버전(검수 승인 시 `LS_LABEL_VERSION` 스냅샷)은 별개다.** 롤백은 대상 스냅샷 재활성이며 멱등이면 no-op.
4. ★**사람이 산출물 내용을 고치면 무조건 재검수 → 재승인 시 새 버전 폴더 `v{n+1}` 전량 재생성 + 통지.** 트리거는 검수 승인 한 곳이고, 승인 상태를 내리지 않고 `REVLT_YN='Y'` 로 표시하며 디바운서가 재승인까지 flush 를 보류한다. 제외는 넷뿐이다. 대상 경로를 「N종」 개수로 적지 말 것.
5. **통지는 export 성공 뒤**에 보낸다 · 버전 폴더는 전부 보존(retention 없음) · 비식별 영상 파일명은 조합하지 말고 `DE_IDNTF_FILE_PATH_NM` 을 읽는다.
6. **버전 diff 비교축은 `id`·`lblTypeCd`·`label`·`labelId`·`points`·`trackId`**이고, 단일 선택은 현재 작업본과 비교(`/diff-with-working`)한다.

### 증강 = 새 영상

> **정본: `docs/rules/klid-augment-derivative.md`** — 온디맨드라 자동으로 실리지 않는다(2026-09-17, 150k자 한도로 분리). 아래 신호가 하나라도 걸리면 **그 파일을 `Read` 한 뒤** 판단한다. 요약만 보고 구현·판정하지 않는다. **수정도 그 파일에서 한다**(두 곳에 적으면 한쪽만 갱신된다).
>
> **읽어야 하는 신호**
> - 심볼: `AugmentRequestService` · `AugmentReviewService` · `AugmentResultService` · `AugmentJobSubmitService` · `VideoResolutionService` · `Resolution*` · `DerivedMetaCopier` · `StorageSubtreePolicy` · `LsDataAug*`
> - 경로: `/v1/augments/**` · `/v1/videos/{rawSn}/resolution`
> - 테이블·컬럼: `LS_DATA_AUG` · `AUG_TYPE_CD` · `RESL_*` · `AUG_PROC_STTS_CD` · `LS_DATA_AUG_RVW` · `LS_DATA_AUG_LBL_MAP` · `ORGNL_RAW_SN` · `PROMPT_CN` · `NEW_RAW_SN` · `LS_LABEL_ATTR`
> - 개념어: 증강 · 파생영상 · 해상도 변경 · `mtdt` · 생성 조건 · 중복 요청 · 파생 깊이 · 폐기·유예·실삭제

**불변식 — 읽지 않아도 틀리면 안 되는 것** (요약이라 응답 코드·예외·경로 목록은 정본에만 있다)

1. **증강·해상도 변경은 모두 새 영상(RAW_SN)을 만들고 `ORGNL_RAW_SN` 으로 부모를 참조한다.** 비디오는 부모 비식별본을 실제로 복사하고(경로는 `DE_IDNTF_FILE_PATH_NM` 에서 읽는다 · 원본 경로 폴백 금지) 프레임만 변환한다. 파생영상에는 「원본영상」이 없다.
2. **해상도 파생은 `LS_DATA_AUG`(`AUG_TYPE_CD=RESL_*`)에 통합 저장**하며 검수 승인·반려는 차단한다. 전용 테이블을 되살리지 말 것.
3. **증강 요청은 생성 조건 5필드 전부 필수**이고 최상위 `mtdt` + 문자열 `prompt`(v1.3 — 객체 금지)로 보낸다. **중복 요청은 BE 가 막지 않는다**(속도 제한도 없음) — 되살리지 말 것.
4. **파생 깊이는 1로 고정한다** — 파생본에서 증강을 요청하면 400. 기존 깊이 2 이상 데이터는 정리하지 않으니 조회 코드는 그것을 견뎌야 한다.
5. **원본 비식별 신고(`'F'`)는 파생 생성을 막지 않는다**(`'N'`·null 만 차단, 판정 `hasDeidentArtifact()`). 신고가 막는 것은 외부 위탁뿐이며 보류가 아니라 거부다.
6. **생성 결과(`AUG_PROC_STTS_CD`)와 사용·폐기 결정(`LS_DATA_AUG_RVW.RVW_STTS_CD`)은 다른 컬럼이다** — 등재 게이트도 리뷰 축(해상도 파생 예외). 반려 파생은 유예 7일 뒤 실삭제하되 조건 셋을 최종 DELETE 에 리터럴로 박고, `NEW_RAW_SN` NULL 은 제외하며, **`LS_LABEL_ATTR` 은 삭제 대상이 아니다.**

### 포털 (외부 채널)

> **정본: `docs/rules/klid-portal.md`** — 온디맨드라 자동으로 실리지 않는다(2026-09-17, 150k자 한도로 분리). 아래 신호가 하나라도 걸리면 **그 파일을 `Read` 한 뒤** 판단한다. 요약만 보고 구현·판정하지 않는다. **수정도 그 파일에서 한다**(두 곳에 적으면 한쪽만 갱신된다).
>
> **읽어야 하는 신호**
> - 심볼: `Portal*` · `PortalLabelService` · `tokenIngress` · `PortalContentTabs` · `portalNav` · `PortalUploadMarkingPage`
> - 경로: `/v1/portal/**` · `/portal/**` 화면
> - 테이블·컬럼: `LS_PORTAL_*` · `PORTAL_USER_NO` · `SRC_TYPE='PORTAL_ULD'` · `portal.upload_status` · `portal.fail_reason` · `LS_TUS_UPLOAD`
> - 개념어: 포털 · 외부 채널 · Host 임베딩 · `x-access-token` · 채널별 배포 · 오버레이 · 포털 업로드·마킹 · 포털 AI 보조 · `ADR-013` · `ADR-058`

**불변식 — 읽지 않아도 틀리면 안 되는 것** (요약이라 응답 코드·예외·경로 목록은 정본에만 있다)

1. **저작도구와 포털은 서로의 DB 에 접근하지 않는다**(API 로 교환). 포털 DB 복제 코드는 철거 완료 — 되살리지 말 것.
2. **저작도구는 채널마다 별도 배포되고 각자 자기 PostgreSQL 에만 붙는다.** 승인 자산은 관제를 거쳐서만 포털로 간다. `PortalLabelService` 가 control DB 를 읽는 현재 코드는 단일 배포 시절 형상이라 근거로 쓰지 말 것. 포털 배포본도 기존 `ai-server` 를 가리킨다(라벨링 AI 보조 전용).
3. **포털 저장은 원본·데이터마트를 수정하지 않는다**(단방향 오버레이 `LS_PORTAL_USER_LABEL`·`_META`·`_EVNT_ANNO`). 업로드 자산은 공용 원장에 흡수됐고(`SRC_TYPE='PORTAL_ULD'` · `PORTAL_USER_NO`), 자동 삭제는 판별자+소유자+보존기간 셋을 최종 삭제문에 리터럴로 건다.
4. **「미제공」의 축은 외부 서버 연동이다** — 메타·이벤트 어노테이션 표시·수정은 제공하고, 라벨링 AI 보조(AI 탐지·AI 분할·AI 자동 추적)는 포털 전용 창구로 제공한다. 키포인트·선택 객체 AI 추적·검수·버전관리와 배치 오토라벨링 파이프라인은 없다.
5. **업로드는 영상만 받는다**(이미지 축은 화면·사양에서 전부 제거 — 「프레임 이미지」와 혼동 금지). 순서는 업로드 → 마킹(별도 화면, 자동 기본 300프레임) → 추출 → 라벨링.
6. **머리 영역·좌측 메뉴는 Host 소유**이고 우리는 본문과 상단 이동 탭만 그린다(두 번 뒤집혔다 — 되돌리지 말 것). 토큰은 Host 인계 창구로 받아 `x-access-token` 헤더로 싣는다.

### DB 정책

> **정본: `docs/rules/klid-db-policy.md`** — 온디맨드라 자동으로 실리지 않는다(2026-09-17, 150k자 한도로 분리). 아래 신호가 하나라도 걸리면 **그 파일을 `Read` 한 뒤** 판단한다. 요약만 보고 구현·판정하지 않는다. **수정도 그 파일에서 한다**(두 곳에 적으면 한쪽만 갱신된다).
>
> **읽어야 하는 신호**
> - 파일: `db/migration/V*.sql` · `V1__baseline.sql` · `deploy/onprem/db/schema.sql` · JPA 엔티티(`@Column`·`@Table`)
> - 설정: `DB_SCHEMA` · `klid_at` · `currentSchema` · Flyway · `ddl-auto` · Quartz `tablePrefix`
> - 컬럼: `DE_IDENT_YN` · `DE_IDNTF_YN` · `NXTM_RTRY_DT` · `NEXT_RTRY_DT` · `MNG_*`
> - 개념어: 마이그레이션 · 새 컬럼·테이블 · 표준용어 · 표준도메인 · 물리명 · 스키마 이관

**불변식 — 읽지 않아도 틀리면 안 되는 것** (요약이라 응답 코드·예외·경로 목록은 정본에만 있다)

1. **전 환경·전 채널 PostgreSQL 이다.** 스키마 `klid_at` 은 `${DB_SCHEMA:klid_at}` 값 하나를 네 지점(커넥션 `currentSchema`·Flyway·Quartz·hibernate)이 함께 읽는다. 마이그레이션 SQL 에 `public.` 리터럴과 `${...}`(주석 안이라도) 를 쓰지 않는다.
2. **Flyway 는 스쿼시됐다** — `V1`·`V2` 는 내용을 고치지 않고 새 번호 파일로만 바꾼다. `LS_*` 는 자체 관리, `MNG_*` 변경은 관제 협의가 먼저다. `ddl-auto=validate` 고정, 엔티티를 바꾸면 마이그레이션을 함께 쓴다.
3. ★**새 컬럼·테이블은 물리명·타입·크기 모두 표준을 따른다** — 우선순위는 행안부 공통표준 → 사업 표준 → 신규 등록이고, 판정은 `docs/LogiCraft-*` CSV grep 으로만 한다(MCP 검색으로 「미등록」 판정 금지). 사전을 고치기 전 `createdBy` 를 확인한다. **DB 를 건드리는 작업을 위임할 때는 이 제약을 프롬프트에 싣는다.**
4. **`DE_IDENT_YN`(테이블)과 `DE_IDNTF_YN`(뷰 출력명)은 둘 다 맞다.** `NXTM_RTRY_DT` 와 `NEXT_RTRY_DT` 도 다른 테이블에 공존한다 — 전역 치환 금지.

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

**대상**: 비식별(**`kpst.deid.base-url`**) · AI 추론(`authoring.integration.ai-server.base-url`) · 외부 시계열 분석 벤더(`vlm.client.url`) · 관제 통지(`authoring.control-notify.url`) · **외부 증강 벤더(`authoring.augment.external.base-url`)** · **관제 계정 창구(`authoring.control-account.url`)**. **DB 접속정보는 대상이 아니다.** (개수로 세지 말고 열거로 읽는다 — 항목이 늘 때마다 숫자를 인용한 층이 함께 틀린다.)

⚠ **관제 계정 창구는 2026-09-14 에 추가됐다 (사용자 확정 · `ADR-046` v18 · `ADR-012` v22 · `INT-015` v8 · `CO-20260914-관제계정창구-주소분리`)** — 관제 채널 세션 중계(갱신·로그아웃)가 **관제 통지 수신처 주소를 재사용**하고 있었는데, 관제는 계정 창구(`/api/account/`)와 데이터셋 창구(`/api/data-set/`)를 **서로 다른 WAS** 에 둬 통지 WAS 를 넣은 현장에서 세션 연장이 404 로 전부 실패했다. 이제 세션 중계는 이 주소**만** 쓰고 통지는 통지 주소**만** 쓴다. **폴백 없음**(비어 있어도 통지 주소로 대체하지 않음) · **배포 기본값 비움**(관제 채널 배포본은 반드시 설정) · 설정 칸 축(비식별·통지·증강과 같은 규칙) · 전송 시점 검사는 관제 통지와 같은 축(빈값·형식 불명만). ⚠ 잔여 위험(`NFR-013`)이 이 주소로 옮겨 왔다 — 이 주소를 바꾸면 사용자 세션 자격증명이 그 주소로 나간다.

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
- **포털 자산 업로드 신설 (ADR-013 예외, 2026-07-17)**: 영상은 재개 가능 업로드를 쓰며 세션은 **공용 원장 `LS_TUS_UPLOAD`** 에 적재된다(구 전용 세션 테이블 `LS_PORTAL_TUS_ULD` 는 `ADR-058` 로 흡수). **한도·확장자·상태 전이 등 스펙 정본은 `docs/rules/klid-portal.md`**이며 여기에 복제하지 않는다(두 곳에 적으면 한쪽만 갱신된다).

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

> **SYNC 할 때 지킬 규칙** (회차별 이력은 **`docs/rules/kit-sync-log.md`** — 온디맨드라 자동으로 실리지 않는다. 사고 경위가 필요하면 연다)
> - ★**이력은 이 블록에 쌓지 않는다** — 매 SYNC 기록은 `docs/rules/kit-sync-log.md` 맨 위에 추가한다. 이 파일은 150k자 한도에 걸려 있어(2026-09-17 초과로 분리) 여기에 쌓으면 다시 넘는다.
> - **자격증명은 직접 넘긴다** — 이 저장소의 MCP 설정은 `~/.claude.json` 의 `projects[<경로>].mcpServers` 아래라 다운로더가 못 찾는다. `LOGICRAFT_API_BASE`/`LOGICRAFT_API_KEY` 를 주지 않으면 15개 전부 `rc=1` 로 아무것도 쓰지 않고 멈춘다.
> - **`--domain` 을 반드시 준다** — 빼면 pin 을 무시하고 프로젝트 전량을 받는다. 매번 「서버 N건 = pin 건수」를 확인한다(스코프는 축소도 확대도 조용히 일어난다).
> - **「서버 건수 < pin 건수」면 차집합의 `status` 를 전수 조회한다** — 폐기(`deprecated`/`superseded`)면 정상이고(`_retired/` 보존 확인 후 pin 에서 걷어내고 `note_pruned` 에 근거 기록) 아니면 진짜 유실이다.
> - **`--exclude-types` 를 붙이지 않는다** — D003·D004·D013·D014 는 pin 에 제외 타입(SHELL·NAV·REQ·LEGACY)이 있어 붙이면 파일이 `_retired/` 로 밀려난다. pin 이 정본이라 빼려면 pin 에서 먼저 지운다.
> - **두 바퀴 돌려야 수렴한다** — 1차 실행이 새 미판정을 채우므로, 그것을 승격·재다운로드하는 2차까지 돌린다. 한 바퀴로 끝내면 승격 대상이 키트에서 빠진 채 남는다.
> - **전역 타입(`nfr`·`permission_role` 등)이 바뀌면 15개 키트 전부가 영향권**이다 — 한 도메인 변경이라고 그 키트만 돌리면 나머지가 조용히 뒤처진다.
> - **검증**: 직전 커밋 `version-master.md` 대비 유실 0 · 전건 무열화. `version-master.md` 의 changelog 는 **마지막 실행분만** 남으므로 델타는 git 으로 본다.
> - **미판정 판정 선례**: 제외 타입(IMPREC·MOD·LEGACY·NAV·SHELL·REQ·RFP·CTX·DEP)은 기각(다음 SYNC 에 다시 올라오는 것이 정상) · 앞방향 인용뿐인 것은 기각 · `OSS-*`·`SETT-001` 은 미분류 타입이라 보류. 근거는 각 `.kit-scope.json` 의 `note_promoted`/`note_pruned` 에 남긴다.
> - **폐기·반전된 ADR 은 본문을 고치지 않고 반전 표기만 덧붙인다** — 기각 근거를 지우면 그때의 판단 이유가 사라진다.

| 도메인 | 키트 경로 | ITEM | 구현 현황 (설계 쪽 주장) | 설계 0건 단계 |
|---|---|---|---|---|
| DOMAIN-001 사용자·권한 | docs/design/사용자권한-DOMAIN-001/ | 136 | implemented 50 / in_progress 1 / planned 65 / (미기재) 20 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, FEAT 상위 기능 |
| DOMAIN-003 영상·프레임 수집 | docs/design/영상프레임-수집-DOMAIN-003/ | 218 | implemented 113 / in_progress 5 / verified 1 / planned 65 / (미기재) 34 | CONST 상수값 |
| DOMAIN-004 AI 보조 라벨링 | docs/design/ai-보조-라벨링-DOMAIN-004/ | 185 | implemented 82 / in_progress 7 / planned 65 / (미기재) 31 | TEST 통합시험 |
| DOMAIN-005 검수 | docs/design/검수-DOMAIN-005/ | 158 | implemented 77 / in_progress 2 / verified 1 / planned 50 / (미기재) 28 | CONST 상수값 |
| DOMAIN-006 통계·대시보드 | docs/design/통계대시보드-DOMAIN-006/ | 75 | implemented 20 / planned 47 / (미기재) 8 | CONST 상수값, ERD 데이터 계층, EVT 이벤트 계약, C4 컴포넌트, INT 외부 연동 |
| DOMAIN-007 데이터 증강 | docs/design/데이터-증강내보내기-DOMAIN-007/ | 110 | implemented 37 / in_progress 1 / planned 50 / (미기재) 22 | CONST 상수값 |
| DOMAIN-009 게시판·공지 | docs/design/게시판공지-DOMAIN-009/ | 74 | implemented 23 / planned 45 / (미기재) 6 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-010 라벨링 | docs/design/라벨링-DOMAIN-010/ | 209 | implemented 104 / in_progress 1 / planned 69 / (미기재) 35 | INT 외부 연동 |
| DOMAIN-011 마킹 | docs/design/마킹-DOMAIN-011/ | 88 | implemented 24 / in_progress 2 / planned 47 / (미기재) 15 | CONST 상수값 |
| DOMAIN-012 비식별화 | docs/design/비식별화-DOMAIN-012/ | 128 | implemented 46 / in_progress 2 / planned 55 / (미기재) 25 | CONST 상수값 |
| DOMAIN-013 포털 | docs/design/포털-DOMAIN-013/ | 176 | implemented 67 / in_progress 3 / planned 78 / (미기재) 28 | CONST 상수값 |
| DOMAIN-014 시스템 설정 | docs/design/시스템-설정-DOMAIN-014/ | 139 | implemented 63 / in_progress 1 / planned 56 / (미기재) 19 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험 |
| DOMAIN-015 작업 배정 | docs/design/작업-배정-DOMAIN-015/ | 82 | implemented 29 / in_progress 1 / planned 46 / (미기재) 6 | CONST 상수값, EVT 이벤트 계약, TEST 통합시험, INT 외부 연동, FEAT 상위 기능 |
| DOMAIN-016 관제 통지 | docs/design/관제-통지-DOMAIN-016/ | 98 | implemented 29 / in_progress 2 / planned 53 / (미기재) 14 | CONST 상수값, SD 고충실 시안 |
| DOMAIN-017 외부 산출물 이관 | docs/design/외부-산출물-이관-DOMAIN-017/ | 115 | implemented 40 / in_progress 4 / planned 56 / (미기재) 15 | CONST 상수값, C4 컴포넌트 |

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

> **SYNC 할 때 지킬 규칙** (회차별 이력은 **`docs/rules/kit-sync-log.md`** 「화면 키트」 절 — 온디맨드)
> - ★**이력은 이 블록에 쌓지 않는다** — 구현 키트 블록과 같은 규칙이다.
> - ★**통합 키트가 정본이다** — 도메인별 소형 키트는 화면 1~5개만 담으므로, 화면을 동기화하거나 사양을 판정할 때는 먼저 `klid-authoring-screens/` 를 본다.
> - ★★**소형 키트에는 담당 화면만 둔다 (2026-09-05 사용자 확정, 구속)** — 그래프로 닿는다는 이유로 새 화면을 편입하지 않는다. 후보는 매 SYNC 마다 pending 에 다시 올라오며 그때마다 기각한다(각 `.kit-scope.json` 의 `note_pruned`). **되살리지 말 것.** `screen_spec` 은 전부 `domain_id` 가 없어 소형 키트의 화면 목록은 큐레이션 결과다. 소형 키트 승격은 pin 화면의 링크와 pin UC 의 `covered_by` 만 인정한다.
> - **`--scope-file` 은 `--domain` 과 함께 준다** — 다운로더가 `--domain` 이 있을 때만 pin 을 적용한다. 통합 키트의 값은 `DOMAIN-000`. 매번 「서버 N건 = pin 건수」를 확인한다.
> - **`--out` 은 `<키트>/.staging` 이다** — 키트 루트로 주면 평평한 타입 디렉터리(`screen_spec/`·`use_case/` 등)가 생기고, arranger 가 관리하지 않아 **낡은 사양이 정본과 같은 이름으로 남는다.** 사양은 `screens/`·`_shared/` 만 본다.
> - **`--ids` 를 `.staging` 글롭에서 뽑지 않는다** — gitignore 대상이라 스코프가 조용히 줄어든다(526→469 사고). 매 SYNC 뒤 `git show HEAD:<version-master> 대비 유실 0` 과 `배치본 파일 − 표 = 렌더 전용 SD 뿐` 두 검사를 돌린다.
> - **링크로 이어진 누락은 미판정 목록에 드러나지 않는다** — pin 항목의 서버 구조 링크를 pin 과 차집합해 확인한다. 새 화면을 통합 키트에 넣을 때는 폐포(소비 API·UC·AC·렌더)를 함께 승격한다.
> - 화면 키트 스코프에는 `adr`·`nfr` 타입이 없다 — 그 축의 유실 경고와 미판정 `NFR-*` 는 정상·기각 대상이다.
> - 통합 키트의 `.staging-s*` 옛 스냅샷은 버전 대조에서 뺀다(세면 뒤처짐이 부풀려진다). `source_hash` 가 서버 `sections` 해시와 다른 와이어프레임은 동기화 결함이 아니라 서버 렌더 재게시 대기다.
> - **`검수자 권한` 을 일괄 치환하지 않는다** — 검수자를 하한으로 말하거나 계층 상속을 말하는 서술은 정당하다.

| 키트 | 화면 수 | 키트 경로 | ui_component 카탈로그 | last sync | 표 ITEM |
|---|---|---|---|---|---|
| **전체 통합 (39화면)** | 39개 (SCREEN-001~046 중 39건 — 서버 활성 전건) | docs/screen-design/klid-authoring-screens/ | 158건 | **2026-09-17 (s44)** | 634 |
| DOMAIN-010 라벨링 | 2개 (SCREEN-005, SCREEN-026) | docs/screen-design/라벨링-DOMAIN-010/ | 147건 | 2026-09-17 (s21) | 245 |
| DOMAIN-005 검수 | 2개 (SCREEN-018, SCREEN-019) | docs/screen-design/검수-DOMAIN-005/ | 147건 | 2026-09-17 (s21) | 211 |
| DOMAIN-015 작업 배정 | 1개 (SCREEN-012) | docs/screen-design/작업-배정-DOMAIN-015/ | 144건 | 2026-09-17 (s21) | 181 |
| DOMAIN-003 영상·프레임 수집 | 1개 (SCREEN-009) | docs/screen-design/영상프레임-수집-DOMAIN-003/ | 144건 | 2026-09-17 (s23) | 172 |
| DOMAIN-009 게시판·공지 | 4개 (SCREEN-030, SCREEN-031, SCREEN-036, SCREEN-037) | docs/screen-design/게시판공지-DOMAIN-009/ | 144건 | 2026-09-17 (s21) | 172 |
| DOMAIN-001 사용자·권한 | 5개 (SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-024) | docs/screen-design/사용자권한-DOMAIN-001/ | 144건 | 2026-09-17 (s22) | 181 |

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

