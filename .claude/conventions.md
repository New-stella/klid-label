# conventions.md — KLID-저작도구 구현 공통 규약 (전 도메인 에이전트 공통 참조)

모든 `klid-d0NN-implementer` · `klid-web-implementer` · `klid-qa-verifier` 는 구현/검증 전에 이 파일을 정독한다. 값·경계·명령을 여기서 단일 진실원으로 가져간다. **AI 임의 추정 금지.**

> 이 파일은 `mc-logi-build-onboard` 가 방출한 산출물이다. 프로젝트 `CLAUDE.md` 와 어긋나면 **CLAUDE.md 가 우선**한다(그쪽이 구속 정책의 진실원).

## 확정 기술 스택 (킥오프 + ADR — 재논의 금지)

| 영역 | 스택 | 근거 |
|---|---|---|
| 백엔드 | Java 17 · Spring Boot 3.3 · Gradle 8 · JPA(Hibernate 6) + QueryDSL 5.1 | 킥오프 `tech_stack_constraints` |
| DB | PostgreSQL (단일 RDB) · Flyway 10.13 · 스키마 `klid_at` | 킥오프 `persistence_strategy: single_rdb` |
| 스케줄러 | Spring Boot Quartz (PostgreSQL JobStore, `QRTZ_*`) | 킥오프 |
| 외부 호출 | Spring WebFlux WebClient + Resilience4j (재시도·서킷·타임아웃) | 킥오프 |
| AI 추론 | Python 3.11 · FastAPI (`ai-server`, Stateless 추론 전용) | 킥오프 |
| 프론트 | React 18 · TypeScript 5 · Vite 5 · TanStack Query v5 · Zustand · Tailwind · konva.js | 킥오프 · `frontend/package.json` |
| 아키텍처 | modular monolith · on-premise · 2노드 Active-Active | 킥오프 `service_architecture`·`deployment` |
| 인증 | in-house — 독립 로그인 UI 없음. 관제/포털이 발급한 동일 JWT 를 인계받아 검증, `role`+`channel` 클레임으로 분기 | 킥오프 `auth_model` |

## 코드 레이아웃

```
klid-label/
├── backend/src/main/java/kr/co/cudo/authoring/   # Spring Boot — 도메인 패키지
│   ├── {assignment,augment,auth,controlnotify,dataset,dev,eventtype,evntanno,
│   │    label,marking,meta,notice,notification,observability,portal,preset,
│   │    quality,review,stats,sysconfig,upload,user,version,video,webhook}/
│   ├── batch/                                    # ★ 공유 — 파이프라인·스텝·스케줄러
│   └── common/                                   # ★ 공유 — response·exception·security·client·datasource
├── backend/src/main/resources/db/migration/      # ★ 공유 — Flyway
├── ai-server/app/                                # FastAPI — routers/{yolo,sam2,vlm}
├── mock-server/app/                              # 로컬 외부연동 대역 (:9400)
├── frontend/src/{features,pages,components}/     # React
└── docs/design/{도메인슬러그}-{DOMAIN-ID}/         # LogiCraft 구현 키트
```

- 도메인 code_root 경계 안에서만 작업한다.
- `common/`·`batch/`·`db/migration/`·`AuthoringApplication.java` 는 **공통** — 도메인 에이전트가 임의 수정 금지. 필요하면 `notes_for_main.needs_core_change` 로 요청(오케스트레이터가 조율).
- ⚠ `dataset/` 은 DOMAIN-007(증강)과 DOMAIN-010(라벨링 export)에 **걸쳐 있다.** 여기를 건드리면 두 도메인 모두에 영향이 갈 수 있으므로 `cross_domain` 으로 보고한다.
- ⚠ DOMAIN-012(비식별화)는 **전용 패키지가 없다** — `Deident` 계열이 `batch`·`label`·`video` 에 분산돼 있다. 그 도메인 에이전트의 경계는 자기 지침의 「코드 레이아웃」 절이 정한다.

## 프론트엔드 트랙

| 항목 | 값 |
|---|---|
| code_root | `frontend/` |
| 스택 | React 18 SPA (Vite 5) · TS 5 · TanStack Query v5 · Zustand · React Router v6 · axios · Tailwind |
| 캔버스 | konva.js / react-konva (라벨링 화면) |
| 진입 포트 | dev `5173` (playwright 기준 `127.0.0.1:5174`) |
| 화면 키트 | `docs/screen-design/{도메인슬러그}-{DOMAIN-ID}/` (7 도메인 보유) |

프론트는 백엔드 응답 계약을 **소비만** 한다. 계약이 불명확하면 멈추고 질문한다 — 필드를 지어내거나 mock 으로 우회하지 않는다.

## 빌드·테스트·품질 명령

```bash
# 백엔드 — 전체 회귀 6,742 테스트 약 5분 25초 (예산 1800s)
cd backend && ./gradlew cleanTest test
cd backend && ./gradlew build

# 프론트 — 3,213 테스트(409 파일) 약 45초
cd frontend && npm run lint && npm run test && npm run build

# ai-server
cd ai-server && python -m pytest tests -q

# mock-server (로컬 외부연동 대역)
cd mock-server && python -m pytest tests -q
```

★ **Gradle `test` 는 UP-TO-DATE 로 스킵되면 실행하지 않고 통과처럼 보인다**(과거 거짓 PASS 실사고). 반드시 `cleanTest test` 또는 `--rerun-tasks` 로 강제하고, **결과 XML 개수·타임스탬프로 실행 증거를 확인**한다.
★ **빌드/테스트를 동시에 2개 이상 돌리지 않는다** — `build/test-results` 충돌로 위양성 실패가 난다.
★ red(실패)는 숨기지 말고 그대로 보고. 값·계약 불명확하면 **구현 멈추고** `notes_for_main` 에 질문.

## 로컬 실행 전제

- 외부 연동(비식별 KPST · VLM · 증강)은 **전부 `mock-server`(:9400)가 대신 수행**한다. 실벤더를 호출하지 않는다.
- 내부 목 모드로 외부 호출을 건너뛰는 우회는 금지. **자체 채움(self-fill)은 결함**이다 — 값은 항상 외부 연동 응답에서 와야 한다.

## 진실원·참조 우선순위

1. **`change_detail`**(CO 의 이 도메인 섹션) = 구현 진실원.
2. **확정된 LogiCraft ITEM** — `klid-dispatch` 가 구현 전에 이미 정합시킨 상태다(§설계 선반영). `design_refs` 로 내려온 ID 를 조회해 계약을 확인한다.
3. 로컬 키트 `docs/design/{도메인}/IMPLEMENTATION.md` = 배경 참고(빌드 순서·용어·CONST 표). **SYNC 하지 않는다.**
4. 프로젝트 `CLAUDE.md` = 구속 정책의 진실원. 키트와 어긋나면 CLAUDE.md 가 이긴다.
5. CONST/enum/임계치는 설계 근거 없이 상상해서 하드코딩 금지.

## DB 표준용어·표준도메인 (신규 컬럼·테이블 필수)

새로 만드는 DB 컬럼·테이블은 **물리명 + 타입 + 크기**를 모두 표준에 맞춘다.

- **우선순위: ① 행안부 공통표준 → ② 사업 표준 → ③ (둘 다 없을 때만) 신규 등록.** 행안부가 1순위다.
- 판정은 반드시 **CSV 정본 grep** 으로 한다: `docs/LogiCraft-공공표준용어-*/` · `docs/LogiCraft-사업용어-*/`.
  ⚠ LogiCraft 검색 API 로 "미등록"을 판정하지 마라 — `limit` 상한이 있어 구조적으로 조회 불가한 구간이 있고, 실제로 정상 컬럼을 "비표준"으로 오판시킨 적이 있다.
- 마이그레이션 SQL 은 PostgreSQL 문법. `public.` 리터럴을 박지 말 것(스키마는 `klid_at`).
- 마이그레이션 파일에 `${...}` 를 쓰지 마라 — **주석 안이라도** Flyway placeholder 파싱이 실패한다.
- `V1__baseline.sql`·`V2` 는 수정 금지(체크섬 불일치 = 전 노드 기동 실패). 스키마 변경은 새 버전 파일로만.

## 절대 규칙

- **LogiCraft 쓰기 금지** — 구현은 코드만. 설계 변경이 필요하면 `notes_for_main` 으로 올린다. 단 IMPREC 추적(`mark_implementation`)은 허용·권장.
- 외부 엔드포인트/시크릿은 코드에 박지 않음 — config/env 경유. URL 하드코딩 금지.
- 로그에 개인정보·토큰 출력 금지. 사용자 입력 로그 출력 시 개행 제거(CWE-117).
- 커밋 안 함(오케스트레이터가 처리). 커밋금지: `.env` · `.env.local` · `*.local` · `.claude/settings.local.json` · `backend/storage/` · `/storage/` · `cvat/` · `docs/design/backup/`.
  (`.env.example` 은 placeholder 만 담아 **커밋 대상**이다.)

## 출력 규약 (구현 에이전트 — YAML 한 블록)

```yaml
implemented: {files: [...], summary: ...}
verification: {build: ..., tests: ..., lint: ..., acceptance: ...}
tracking: {imprec: ..., design_ref: ...}
notes_for_main: {needs_core_change: [...], info_gaps: [...], cross_domain: [...], follow_ups: [...], learned: [...]}
```
