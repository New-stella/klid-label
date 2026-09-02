# DOMAIN-014 시스템 설정 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(85건) · 버전은 `version-master.md`.
> ⚠ **아래 「빌드 순서」·「구현 현황」·「ITEM 인덱스」 세 표는 2026-08-18 스코프로 얼어 있다.** 키트 다운로더는 이 파일을 재생성하지 않아 SYNC 를 돌려도 따라오지 않는다 — 그 뒤 pin 에 편입된 ITEM(`ROLE-004`·`ADR-055` 등)이 표에 없고, 2026-08-31 재번호로 폐기된 3자리 AC 링크가 남아 있다. **있는 항목은 참이나 전수는 아니므로, 스코프 판정은 `.kit-scope.json` 으로 한다.** 본문 산문은 2026-09-01 에 폐기 결정 기준으로 정정했다.

## 도메인 (bounded context)

저작도구 운영 파라미터를 관리하는 도메인. 성격이 다른 두 계층을 구분한다.

[계층 1 — 런타임 설정(DB 키-값)] LS_SYSTEM_CONFIG 에 저장되며 REVIEWER 가 관리 화면(/manage/settings)에서 조회·수정한다. 서버가 타입별 값 + 범위 검증을 건다. Caffeine 로컬 캐시 TTL 60s. 예: POLYGON_SIMPLIFY_TOLERANCE, YOLO_CONF_THRESHOLD, portal.upload.frame-interval-sec(기본 5초). 외부 연동 서버 주소(비식별 · AI 추론 · 외부 시계열 분석 벤더 · 관제 통지 수신처)도 이 계층이다 — 관리자 패스워드를 다시 확인해 여는 짧은 유효창(기본 10분, 상한 30분) 안에서만 저장할 수 있고, 저장하면 재기동 없이 다음 호출부터 반영된다. 값의 우선순위는 설정에 값이 있으면 설정, 없으면 배포 기본값이다.

[계층 2 — 배포 설정(환경변수·프로파일)] DB 접속·토글 등은 화면에서 바꾸지 않고 배포 형상으로 관리한다.

[fail-closed 기동 가드] 잘못 닫히면 사고가 되는 설정은 경고가 아니라 기동 차단으로 막는다 — WARN 은 배포 로그에 묻히기 때문이다. 예: ①Quartz 클러스터링은 stg/prd 에서 강제되며 꺼져 있으면 기동을 거부한다(프로파일 allowlist + ENV 배포 표식 두 축) ②파생 폐기 유예기간은 0·음수·파싱실패면 기동 실패시킨다(파괴적 기능이 fail-open 되면 '반려 즉시 실삭제'로 전락한다). 빈 설정값이 배포 기본값으로 조용히 대체되는 구성은 이 방어를 무력화하므로 피한다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| ConfigKey | 설정 키 enum (타입·범위 메타 포함) |
| 런타임 설정 | 화면에서 바꿀 수 있는 DB 키-값 설정 |
| 배포 설정 | 환경변수·프로파일로 관리하는 설정. 화면에서 변경하지 않는다 |
| fail-closed 기동 가드 | 안전에 직결된 설정이 빠지거나 잘못되면 경고가 아니라 기동을 거부하는 방어 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-006]] · [[ADR-007]] · [[ADR-039]] · [[ADR-046]] · [[ADR-055]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-016]] |
| 4 | API 경계 계약 | [[API-068]] · [[API-069]] · [[API-090]] · [[API-118]] · [[API-141]] · [[API-193]] · [[API-194]] |
| 5 | DFEAT 비즈니스 로직 | [[DFEAT-045]] |
| 6 | SEQ 흐름 배선 | [[SEQ-007]] · [[SEQ-013]] · [[SEQ-024]] · [[SEQ-025]] |
| 7 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 8 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-025]] |
| 9 | UC 검증 | [[UC-006]] · [[UC-013]] · [[UC-031]] |
| 10 | AC 수용 | [[AC-006]] |
| 11 | CDIAG 클래스 구조 | [[CDIAG-012]] |
| 12 | C4 컴포넌트 | [[CMP-011]] |
| 13 | INT 외부 연동 | [[INT-002]] · [[INT-004]] · [[INT-005]] · [[INT-007]] |
| 14 | FEAT 상위 기능 | [[FEAT-007]] |
| 15 | SD 고충실 시안 | [[SD-015]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, EVT 이벤트 계약, TEST 통합시험 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 25 |
| implemented | 16 |
| (미기재) | 9 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-068]] | api_endpoint | implemented | 100 |
| [[API-069]] | api_endpoint | implemented | 100 |
| [[API-090]] | api_endpoint | implemented | 100 |
| [[API-118]] | api_endpoint | implemented | 100 |
| [[API-141]] | api_endpoint | implemented | 100 |
| [[API-193]] | api_endpoint | implemented | 100 |
| [[FEAT-007]] | feature | implemented | 100 |
| [[INT-002]] | integration_point | implemented | 0 |
| [[INT-004]] | integration_point | implemented | 100 |
| [[INT-005]] | integration_point | implemented | 100 |
| [[INT-007]] | integration_point | implemented | 100 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-025]] | screen_spec | implemented | 100 |
| [[SEQ-007]] | diagram_sequence | implemented | 0 |
| [[SEQ-013]] | diagram_sequence | implemented | 0 |
| [[UC-006]] | use_case | implemented | 100 |
| [[AC-006]] | acceptance | planned | 0 |
| [[API-194]] | api_endpoint | planned | 0 |
| [[DFEAT-045]] | domain_feature | planned | 0 |
| [[ERD-016]] | erd | planned | 0 |
| [[NFR-008]] | nfr | planned | 0 |
| [[NFR-009]] | nfr | planned | 0 |
| [[NFR-010]] | nfr | planned | 0 |
| [[NFR-011]] | nfr | planned | 0 |
| [[NFR-012]] | nfr | planned | 0 |
| [[NFR-013]] | nfr | planned | 0 |
| [[NFR-014]] | nfr | planned | 0 |
| [[NFR-015]] | nfr | planned | 0 |
| [[NFR-016]] | nfr | planned | 0 |
| [[NFR-017]] | nfr | planned | 0 |
| [[NFR-018]] | nfr | planned | 0 |
| [[NFR-019]] | nfr | planned | 0 |
| [[NFR-020]] | nfr | planned | 0 |
| [[NFR-021]] | nfr | planned | 0 |
| [[ROLE-001]] | permission_role | planned | 0 |
| [[ROLE-002]] | permission_role | planned | 0 |
| [[ROLE-003]] | permission_role | planned | 0 |
| [[SEQ-024]] | diagram_sequence | planned | 0 |
| [[SEQ-025]] | diagram_sequence | planned | 0 |
| [[UC-013]] | use_case | planned | 0 |
| [[UC-031]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (5)
- [[ADR-055]] — 역할 4종 + 계층(ROLE_ADMIN > ROLE_REVIEWER) — ADR-003·ADR-043 supersede
- [[ADR-006]] — 비식별 처리 외부 솔루션 연동 — 캔버스 수동 블러 폐기
- [[ADR-007]] — 관제서버 연동 — 양방향 M2M 폐기, 단방향 outbound 통지 채택
- [[ADR-039]] — dev 로그인/dev 업로드 — prd 빌드 env 토글 허용(기본 OFF, fail-closed)
- [[ADR-046]] — 연동 서버 주소를 운영 화면에서 재기동 없이 교체 — 저장 축 IP 대역 차단 폐지 + 관리자 단기 유효창

### nfr (14)
- [[NFR-008]] — 학습데이터 단계별 품질관리 기준 (수집·제작·검수)
- [[NFR-009]] — 학습데이터 종류·제작방법별 품질관리 기준
- [[NFR-010]] — 학습데이터 값 검증·정합성 (공공데이터 품질진단 기준)
- [[NFR-011]] — 화면 응답시간 기준
- [[NFR-012]] — 시스템 자원 효율
- [[NFR-013]] — 세션·계정 보안대책
- [[NFR-014]] — 시큐어코딩 (SW 개발보안)
- [[NFR-015]] — 웹표준·크로스브라우징
- [[NFR-016]] — 데이터 표준 준수
- [[NFR-017]] — API 호출 규약 (Base URL /api · Bearer JWT 인증 · 채널 격리)
- [[NFR-018]] — 기능 수행 지연 사전 안내
- [[NFR-019]] — 오류 응답 속도
- [[NFR-020]] — 역할별 접근제어
- [[NFR-021]] — 취약점 점검·모의해킹

### erd (1)
- [[ERD-016]] — 시스템 설정 ERD (고도화, PostgreSQL)

### api_endpoint (7)
- [[API-068]] — GET /v1/manage/configs
- [[API-069]] — PUT /v1/manage/configs/{key}
- [[API-090]] — GET /v1/manage/health
- [[API-118]] — GET /v1/system/scheduler/health
- [[API-141]] — GET /health
- [[API-193]] — GET /v1/ai-defaults
- [[API-194]] — POST /v1/manage/admin-session

### domain_feature (1)
- [[DFEAT-045]] — 시스템 설정 관리

### diagram_sequence (4)
- [[SEQ-007]] — 라벨링 정밀도 조절 — 설정 변경부터 SAM2 단순화 적용까지
- [[SEQ-013]] — 비식별 옵션 설정 — 관리 화면 설정 저장
- [[SEQ-024]] — 시스템 운영 설정 관리 — 설정 카드 조회·수정과 외부 연동 헬스 모니터링
- [[SEQ-025]] — 연동 서버 주소 변경 — 관리자 단기 유효창 발급부터 재기동 없는 반영까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (2)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-025]] — 시스템 설정 화면

### use_case (3)
- [[UC-006]] — 라벨링 정밀도 조절
- [[UC-013]] — 비식별 옵션 설정
- [[UC-031]] — 시스템 운영 설정 관리

### acceptance (1)
- [[AC-006]] — 라벨링 정밀도(폴리곤 단순화) 조절

### class_diagram (1)
- [[CDIAG-012]] — 시스템 설정 도메인 모델

### diagram_c4_component (1)
- [[CMP-011]] — 시스템 설정 컴포넌트

### integration_point (4)
- [[INT-002]] — VLM 시계열 위탁 요청
- [[INT-004]] — 비식별 처리 위탁 요청
- [[INT-005]] — 비식별 진행 상태 폴링
- [[INT-007]] — 관제서버 완료/수정 통지

### feature (1)
- [[FEAT-007]] — 라벨링 정밀도 조절

### screen_design (1)
- [[SD-015]] — SCREEN-025 시스템 설정 화면
