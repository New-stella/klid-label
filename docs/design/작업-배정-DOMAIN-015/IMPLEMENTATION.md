# DOMAIN-015 작업 배정 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(42건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

REVIEWER 가 WORKER 에게 영상 단위 작업을 배정·재배정하는 도메인. LS_TASK_ALTMNT(TASK_TYPE_CD='LABELER') INSERT, 재배정 시 작업 이벤트 로그에 기록. 배정 이력 조회·재배정 권한도 REVIEWER 가 보유하며 TaskBoard 로 작업 현황을 조회한다.

[작업 상태 소유권] LS_RAW_DATA_STATUS.DATA_STTS_CD 가 작업·검수 워크플로우 상태를 소유한다(배치 단계 축인 LS_DATA_RAW.DATA_STTS_CD 와 별개). 배정 시점에 생성되고 배치 완료 시 ASSIGNED 로 복귀하며, 검수 승인 시 APPROVED 로 종결한다(이 축에 COMPLETED 로 전이하는 경로는 없다).

[★목록 정렬·필터 정책 (2026-07-29 확정, 구속)] 정렬은 시간축 단일 기준이며 상태 우선순위를 ORDER BY CASE 로 섞지 않는다 — '지금 처리할 것'은 필터·KPI 카드로 표현한다(CVAT·Label Studio 관행). 필터·집계는 BE 에서 전체 기준으로 처리하고 정렬 키는 allowlist 매핑으로만 해석한다(CWE-89/770). ⚠ 미등록 정렬키의 응답은 엔드포인트별로 다르다 — 작업목록(/v1/tasks/board*)은 strict(400), 검수목록(/v1/reviews*)은 lenient(200 + 기본정렬 폴백). 근거는 '변경 전에 그 엔드포인트가 200 이었는가'이며 일관성을 이유로 통일하지 말 것.

[★파생영상 등재 게이트] 증강 파생은 REVIEWER 가 사용하기로 결정한 것만 작업목록·배정에 등재된다. 판정축은 LS_DATA_AUG_RVW.RVW_STTS_CD(검수 결정)이며 생성 결과축(AUG_PROC_STTS_CD)이 아니다 — 생성 성공만으로 통과시키면 게이트가 무의미해진다. ⚠ 해상도 파생(RESL_*)은 검수 대상이 아니라 리뷰 행이 영영 생기지 않으므로 통과 예외를 명시적으로 박는다. ⚠ 게이트 도입 이전 파생은 그랜드퍼더링한다 — 이미 배정된 WORKER 의 영상이 화면에서 사라지면 고아 배정이 된다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 배정(Assignment) | REVIEWER→WORKER 영상 단위 작업 할당 |
| 재배정 | 배정 변경 — 작업 이벤트 로그에 이력 기록 |
| TaskBoard | 작업 현황 목록. 배치 상태 축(status)과 워크플로 축(workStatus)을 별도 파라미터로 구분한다 |
| 등재 게이트 | 파생영상이 작업목록에 등재될 자격. 검수 결정축(리뷰 행)으로 판정하며 해상도 파생은 예외다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-038]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-014]] |
| 4 | API 경계 계약 | [[API-001]] · [[API-002]] · [[API-070]] · [[API-071]] · [[API-072]] · [[API-073]] · [[API-116]] · [[API-136]] · [[API-137]] · [[API-187]] |
| 5 | DFEAT 비즈니스 로직 | [[DFEAT-006]] |
| 6 | SEQ 흐름 배선 | [[SEQ-017]] |
| 7 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 8 | SCREEN 화면 | [[SCREEN-008]] · [[SCREEN-011]] · [[SCREEN-012]] |
| 9 | UC 검증 | [[UC-029]] |
| 10 | CDIAG 클래스 구조 | [[CDIAG-007]] |
| 11 | C4 컴포넌트 | [[CMP-009]] |
| 12 | SD 고충실 시안 | [[SD-003]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, EVT 이벤트 계약, AC 수용, TEST 통합시험, INT 외부 연동, FEAT 상위 기능 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 22 |
| implemented | 13 |
| (미기재) | 7 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-001]] | api_endpoint | implemented | 100 |
| [[API-002]] | api_endpoint | implemented | 100 |
| [[API-070]] | api_endpoint | implemented | 100 |
| [[API-071]] | api_endpoint | implemented | 100 |
| [[API-072]] | api_endpoint | implemented | 100 |
| [[API-073]] | api_endpoint | implemented | 100 |
| [[API-116]] | api_endpoint | implemented | 100 |
| [[API-136]] | api_endpoint | implemented | 100 |
| [[API-137]] | api_endpoint | implemented | 100 |
| [[DFEAT-006]] | domain_feature | implemented | 100 |
| [[SCREEN-008]] | screen_spec | implemented | 100 |
| [[SCREEN-011]] | screen_spec | implemented | 100 |
| [[SCREEN-012]] | screen_spec | implemented | 100 |
| [[API-187]] | api_endpoint | planned | 0 |
| [[ERD-014]] | erd | planned | 0 |
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
| [[SEQ-017]] | diagram_sequence | planned | 0 |
| [[STATE-001]] | diagram_state | planned | 0 |
| [[UC-029]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (3)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-038]] — 목록 화면(작업목록/검수목록) 정렬·필터 정책 — 시간축 단일 정렬 + 엔드포인트별 차등 응답

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
- [[ERD-014]] — 작업 배정 ERD (고도화, PostgreSQL)

### api_endpoint (10)
- [[API-001]] — GET /v1/users
- [[API-002]] — GET /v1/users/workers
- [[API-070]] — POST /v1/assignments
- [[API-071]] — PATCH /v1/assignments/{assignmentId}
- [[API-072]] — GET /v1/assignments
- [[API-073]] — GET /v1/tasks/board
- [[API-116]] — GET /v1/assignments/{assignmentId}/history
- [[API-136]] — GET /v1/tasks/board/summary
- [[API-137]] — GET /v1/tasks/board/event-types
- [[API-187]] — GET /v1/assignments/event-types

### domain_feature (1)
- [[DFEAT-006]] — 작업 배정·재배정·확인

### diagram_sequence (1)
- [[SEQ-017]] — 작업 목록 조회·필터링·배정 시퀀스

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (3)
- [[SCREEN-008]] — 영상 처리 현황 화면
- [[SCREEN-011]] — 대시보드 화면
- [[SCREEN-012]] — 작업 목록 화면

### use_case (1)
- [[UC-029]] — 작업 목록 조회·필터링·배정

### class_diagram (1)
- [[CDIAG-007]] — 작업 배정 도메인 모델

### diagram_c4_component (1)
- [[CMP-009]] — 배정·포털·관제 통지 컴포넌트 (작업배정·포털 라벨·outbound 통지)

### screen_design (1)
- [[SD-003]] — SCREEN-012 작업 목록 화면
