# DOMAIN-005 검수 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(92건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

작업자가 제출한 라벨링 결과를 REVIEWER 가 검토해 승인·반려하는 도메인.

[★단일 검수 — 1차/2차 단계 폐기(ADR-002)] 구 서술의 '1차 검수 → 2차 검수' 2단계 워크플로우는 폐기됐다. 검수자 1인이 승인할 때까지 반려↔재제출을 반복하는 단일 검수다. 마찬가지로 '관리자 확인 요청'도 폐기됐다 — 별도 ADMIN 역할이 없고 모든 관리 권한이 REVIEWER 에 통합됐기 때문이다(ADR-003). 검수자↔작업자 소통은 반려·문의를 통합한 이슈 스레드(DFEAT-049)가 대신한다.

[★검수 완료 = 작업 완료] 승인(APPROVED) 시점이 작업의 종결이다. 이 시점에 LS_RAW_DATA_STATUS.DATA_STTS_CD 가 APPROVED 로 전이한다 — 검수 종결값은 APPROVED 이며 COMPLETED 로 전이하지 않는다. COMPLETED 로 전이하면 승인된 영상이 데이터마트에 한 건도 노출되지 않기 때문이다. 같은 이름의 COMPLETED 가 배치 단계 축(LS_DATA_RAW.DATA_STTS_CD)에 별도로 존재하나 그것은 배치 처리 완료를 뜻하는 다른 축이며, 배치 완료가 검수 워크플로 상태로 점프하지 않는다(점프하면 검수 제출 ASSIGNED→PENDING 이 상태머신에서 차단된다).

[승인이 촉발하는 연쇄] ① 라벨 전체 스냅샷 생성(LS_LABEL_VERSION, SAVE_REASON_CD=APPROVED) — 학습데이터 버전은 저장 시점이 아니라 승인 시점에만 쌓인다 ② export 폴더 새 버전 전량 재생성 ③ export 성공 후 관제 TASK_COMPLETED 통지. 이벤트 체인은 ReviewApproved(EVT-006) → DatasetExportCompleted(EVT-009) → TaskCompleted(EVT-003) 이다.

[승인 후 수정] 동일 작업 ID 를 유지한 채 재검수되며 재승인 시 새 버전이 쌓인다. 수정 경로는 그 영상을 재검수 대상으로 표시할 뿐이고, export 새 버전 폴더 재생성과 TASK_MODIFIED 발송은 검수자가 그 수정을 다시 승인한 시점에 일어난다.

[검수 대상 밖] 증강·해상도 파생 중 해상도(RESL_*)는 내부 생성물이라 accept/reject 가 차단된다. 증강 결과의 사용·폐기 결정은 별도 축(LS_DATA_AUG_RVW)이다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 검수 | 검수자 1인이 승인할 때까지 반려↔재제출을 반복하는 단일 절차. 1차/2차 단계 구분은 폐기됐다 |
| 승인(APPROVED) | 작업 종결 시점. 라벨 스냅샷 생성 → export 재생성 → 관제 통지의 시작점이다 |
| 반려 | 작업자에게 되돌려 재작업을 요청. 사유는 이슈 스레드로 소통한다 |
| 프레임 상태 색상 | 연두=라벨 저장됨, 빨강=문의 제기됨. 주황(반려)은 반려가 영상 단위여서 특정 프레임에 매핑되지 않아 프레임 색으로 표시되지 않는다. 모두 연두일 때만 제출 가능 |
| 검수 이력 | 승인·반려 이력. 라벨 변경 이력(LS_DATA_LBL_HSTRY)과는 별개 축이다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-002]] · [[ADR-003]] · [[ADR-004]] · [[ADR-007]] · [[ADR-009]] · [[ADR-015]] · [[ADR-019]] · [[ADR-020]] · [[ADR-031]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-015]] · [[ERD-023]] |
| 4 | EVT 이벤트 계약 | [[EVT-003]] · [[EVT-004]] · [[EVT-006]] · [[EVT-008]] · [[EVT-009]] |
| 5 | API 경계 계약 | [[API-008]] · [[API-009]] · [[API-010]] · [[API-011]] · [[API-012]] · [[API-013]] · [[API-014]] · [[API-015]] · [[API-016]] · [[API-017]] · [[API-021]] · [[API-065]] · [[API-066]] · [[API-067]] · [[API-102]] · [[API-103]] · [[API-104]] · [[API-105]] · [[API-132]] · [[API-138]] · [[API-178]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-021]] · [[DFEAT-023]] · [[DFEAT-024]] · [[DFEAT-025]] · [[DFEAT-049]] · [[DFEAT-054]] |
| 7 | SEQ 흐름 배선 | [[SEQ-008]] · [[SEQ-010]] · [[SEQ-011]] · [[SEQ-015]] · [[SEQ-023]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-018]] · [[SCREEN-019]] · [[SCREEN-023]] |
| 10 | UC 검증 | [[UC-007]] · [[UC-009]] · [[UC-010]] · [[UC-022]] · [[UC-023]] |
| 11 | AC 수용 | [[AC-010]] · [[AC-022]] · [[AC-024]] |
| 12 | TEST 통합시험 | [[TEST-002]] · [[TEST-003]] · [[TEST-004]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-006]] · [[CDIAG-014]] |
| 14 | C4 컴포넌트 | [[CMP-005]] |
| 15 | INT 외부 연동 | [[INT-003]] |
| 16 | FEAT 상위 기능 | [[FEAT-003]] · [[FEAT-004]] · [[FEAT-008]] · [[FEAT-009]] |
| 17 | SD 고충실 시안 | [[SD-001]] · [[SD-005]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 45 |
| planned | 28 |
| (미기재) | 19 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-008]] | api_endpoint | implemented | 100 |
| [[API-009]] | api_endpoint | implemented | 100 |
| [[API-010]] | api_endpoint | implemented | 100 |
| [[API-011]] | api_endpoint | implemented | 100 |
| [[API-012]] | api_endpoint | implemented | 100 |
| [[API-013]] | api_endpoint | implemented | 100 |
| [[API-014]] | api_endpoint | implemented | 100 |
| [[API-015]] | api_endpoint | implemented | 100 |
| [[API-016]] | api_endpoint | implemented | 100 |
| [[API-017]] | api_endpoint | implemented | 100 |
| [[API-021]] | api_endpoint | implemented | 100 |
| [[API-065]] | api_endpoint | implemented | 100 |
| [[API-066]] | api_endpoint | implemented | 100 |
| [[API-067]] | api_endpoint | implemented | 100 |
| [[API-102]] | api_endpoint | implemented | 100 |
| [[API-103]] | api_endpoint | implemented | 100 |
| [[API-104]] | api_endpoint | implemented | 100 |
| [[API-105]] | api_endpoint | implemented | 100 |
| [[API-138]] | api_endpoint | implemented | 100 |
| [[API-178]] | api_endpoint | implemented | 100 |
| [[DFEAT-021]] | domain_feature | implemented | 100 |
| [[DFEAT-023]] | domain_feature | implemented | 100 |
| [[DFEAT-024]] | domain_feature | implemented | 100 |
| [[DFEAT-025]] | domain_feature | implemented | 100 |
| [[DFEAT-049]] | domain_feature | implemented | 100 |
| [[DFEAT-054]] | domain_feature | implemented | 100 |
| [[ERD-023]] | erd | implemented | 100 |
| [[EVT-003]] | domain_event | implemented | 100 |
| [[EVT-004]] | domain_event | implemented | 100 |
| [[EVT-006]] | domain_event | implemented | 100 |
| [[EVT-008]] | domain_event | implemented | 100 |
| [[EVT-009]] | domain_event | implemented | 100 |
| [[FEAT-003]] | feature | implemented | 100 |
| [[FEAT-004]] | feature | implemented | 100 |
| [[INT-003]] | integration_point | implemented | 0 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-018]] | screen_spec | implemented | 100 |
| [[SCREEN-019]] | screen_spec | implemented | 100 |
| [[SCREEN-023]] | screen_spec | implemented | 100 |
| [[SEQ-008]] | diagram_sequence | implemented | 0 |
| [[SEQ-010]] | diagram_sequence | implemented | 100 |
| [[SEQ-011]] | diagram_sequence | implemented | 0 |
| [[UC-007]] | use_case | implemented | 100 |
| [[UC-009]] | use_case | implemented | 100 |
| [[UC-010]] | use_case | implemented | 100 |
| [[AC-010]] | acceptance | planned | 0 |
| [[AC-022]] | acceptance | planned | 0 |
| [[AC-024]] | acceptance | planned | 0 |
| [[API-132]] | api_endpoint | planned | 0 |
| [[ERD-015]] | erd | planned | 0 |
| [[FEAT-008]] | feature | planned | 0 |
| [[FEAT-009]] | feature | planned | 0 |
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
| [[SEQ-015]] | diagram_sequence | planned | 0 |
| [[SEQ-023]] | diagram_sequence | planned | 0 |
| [[UC-022]] | use_case | planned | 0 |
| [[UC-023]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (10)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-002]] — 검수 워크플로우 단일화 — 2차 검수 폐기
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-004]] — 생성형 AI 본체 외부화 — 저작도구는 증강 결과 검수만
- [[ADR-007]] — 관제서버 연동 — 양방향 M2M 폐기, 단방향 outbound 통지 채택
- [[ADR-009]] — 라벨 버전관리 Gitea 제거 — DB 스냅샷 기반 전환
- [[ADR-015]] — 검수자↔작업자 이슈 소통 채널 도입 — 반려 이력 테이블 확장 + 양방향 스레드
- [[ADR-019]] — 오토라벨 프리셋↔검출라벨 매칭축을 마스터 라벨명에서 COCO 검출클래스(DTCT_TYPE_CD)로 일원화
- [[ADR-020]] — 검수 승인 학습데이터 export(NIA JSON) 산출을 저작도구 범위로 포함 (ADR-005 supersede)
- [[ADR-031]] — VLM·증강 콜백 무서명 규격 전환 + 3계층 방어(IP allowlist·rate limit·request_id)

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

### erd (2)
- [[ERD-015]] — 검수·영상상태 ERD (고도화, PostgreSQL)
- [[ERD-023]] — 이슈 스레드 ERD (고도화, PostgreSQL — LS_DATA_ISSUE 확장, LS_ISSUE_COMMENT)

### domain_event (5)
- [[EVT-003]] — TaskCompleted
- [[EVT-004]] — TaskModified
- [[EVT-006]] — ReviewApproved
- [[EVT-008]] — DeidentReportResolved
- [[EVT-009]] — DatasetExportCompleted

### api_endpoint (21)
- [[API-008]] — GET /v1/reviews
- [[API-009]] — GET /v1/reviews/{videoId}
- [[API-010]] — GET /v1/reviews/{videoId}/frames
- [[API-011]] — GET /v1/reviews/{videoId}/issues
- [[API-012]] — POST /v1/reviews/{videoId}/submit
- [[API-013]] — POST /v1/reviews/{videoId}/start
- [[API-014]] — POST /v1/reviews/{videoId}/approve
- [[API-015]] — POST /v1/reviews/{videoId}/reject
- [[API-016]] — POST /v1/meta/{metaReviewSn}/approve
- [[API-017]] — POST /v1/meta/{metaReviewSn}/reject
- [[API-021]] — GET /v1/frames/{srcSn}/image
- [[API-065]] — POST /v1/vlm/callback
- [[API-066]] — GET /v1/frames/{srcSn}/meta
- [[API-067]] — PUT /v1/frames/{srcSn}/meta
- [[API-102]] — POST /v1/videos/{rawSn}/issues
- [[API-103]] — GET /v1/videos/{rawSn}/issues
- [[API-104]] — POST /v1/issues/{issueSn}/comments
- [[API-105]] — POST /v1/issues/{issueSn}/resolve
- [[API-132]] — GET /v1/videos/{rawSn}/event-annotation
- [[API-138]] — GET /v1/reviews/summary
- [[API-178]] — POST /v1/reviews/{videoId}/cancel-submit

### domain_feature (6)
- [[DFEAT-021]] — 검수 (검수자 1인 승인까지 반복)
- [[DFEAT-023]] — 프레임 상태 색상 표기 (연두/주황/빨강)
- [[DFEAT-024]] — 승인·반려
- [[DFEAT-025]] — 검수 이력
- [[DFEAT-049]] — 검수자↔작업자 이슈 소통 채널 (반려·문의 통합 스레드)
- [[DFEAT-054]] — 검수 승인 학습데이터 산출(NIA JSON)

### diagram_sequence (5)
- [[SEQ-008]] — 라벨 버전 저장·이력 추적 — 검수 승인 시점 스냅샷 생성
- [[SEQ-010]] — 데이터마트 수정 통지 — 검수완료 후 수정부터 TASK_MODIFIED push까지
- [[SEQ-011]] — 증강 영상 활용 여부 검수 — 결과 조회부터 사용/폐기 결정·복구까지
- [[SEQ-015]] — 검수 승인·반려 시퀀스
- [[SEQ-023]] — VLM 시계열 메타 검토 — 결과 콜백 수신부터 검토·수정·승인 확정까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (4)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-018]] — 검수 목록 화면
- [[SCREEN-019]] — 검수 상세 화면
- [[SCREEN-023]] — 증강 결과 화면

### use_case (5)
- [[UC-007]] — 라벨 버전 저장·이력 추적
- [[UC-009]] — 검수 완료·수정 통지
- [[UC-010]] — 증강 영상 활용 여부 검수
- [[UC-022]] — VLM 시계열 메타 검토
- [[UC-023]] — 검수 승인·반려

### acceptance (3)
- [[AC-010]] — 증강 영상 활용 여부 검수
- [[AC-022]] — 학습데이터셋 자동·수동 검수
- [[AC-024]] — 영상 학습데이터 가공(라벨링·메타·VLM 시계열 메타 검수)

### test_scenario (3)
- [[TEST-002]] — 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름
- [[TEST-003]] — 외부 생성형 AI 증강 위탁·콜백 수신·활용 검수 정상 흐름
- [[TEST-004]] — 검수 완료·수정에 따른 관제서버 단방향 통지 정상 흐름

### class_diagram (2)
- [[CDIAG-006]] — 검수 도메인 모델
- [[CDIAG-014]] — VLM 시계열 메타 도메인 모델

### diagram_c4_component (1)
- [[CMP-005]] — 검수 컴포넌트 (워크플로우·상태머신·승인 통지)

### integration_point (1)
- [[INT-003]] — VLM 시계열 결과 콜백 수신

### feature (4)
- [[FEAT-003]] — 데이터마트 라벨 동기화 통지
- [[FEAT-004]] — 영상 증강 연동·검수 + 해상도 변경
- [[FEAT-008]] — 학습데이터 검수 (승인·반려)
- [[FEAT-009]] — VLM 시계열 메타 검토·수정

### screen_design (2)
- [[SD-001]] — SCREEN-018 검수 목록 화면
- [[SD-005]] — SCREEN-019 검수 상세 화면
