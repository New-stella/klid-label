# DOMAIN-013 포털 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(66건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

외부 채널(포털 회원) 도메인. ★서로 다른 두 경로를 갖으며 기능 경계가 다르다 — 혼동하지 말 것.

[경로 A — 데이터마트 영상 라벨 작업] 관제가 구축해 포털 DB 에 적재한 데이터마트 영상을 고른다(적재는 관제서버 책임). 이때 Load 하는 기존 라벨·메타는 포털 DB 가 아니라 저작도구 DB 의 검수 승인(APPROVED) 자산(LS_DATA_RAW·LS_DATA_SRC·LS_DATA_LBL)이다 — 저작도구는 포털 DB 를 읽지 않는다. 저장해도 원본·데이터마트는 수정되지 않고 사용자별 작업 데이터(LS_PORTAL_USER_LABEL)로 별도 적재된다(단방향, 마트로 반영 안 됨). 다운로드는 본인 작업 데이터 기준이고 기여도 점수는 없다. 경로 A 가 내보내는 프레임 이미지·라벨은 내부 파이프라인 자산이라, 비식별 누락 신고가 열린 영상이면 412 로 거부되고 그 응답은 Cache-Control: no-store 로 캐시되지 않는다 — 경로 B 의 본인 업로드 자산은 이 게이트의 대상이 아니다.

[경로 B — 본인 자산 업로드 (ADR-013 예외, 2026-07-17)] 포털 사용자가 본인 이미지(jpg/jpeg/png, 20MB/장, 50장/요청)·영상(mp4/mov/avi, 5GB, TUS 재개 업로드)을 직접 올려 수동 라벨링(BBOX/POLYGON 만) 후 본인 데이터를 내려받는다. 업로드 자산은 LS_PORTAL_* 전용 테이블로 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View 와 완전 분리된다. 영상은 본인 데이터라 비식별을 적용하지 않으며 고정 간격 프레임 추출(기본 5초, 상한 2000장)만 한다. 상태는 UPLOADED→PROCESSING→READY|FAILED.

[★제공 범위 — 라벨 편집 도구] 두 경로 모두 수동 라벨링(BBOX/POLYGON)만 제공한다. 도형 종류는 서버 allowlist 로 강제되며(두 경로가 각각 자기 allowlist 를 갖는다) 그 외 값은 400 으로 거부된다. 좌표 개수도 타입별로 강제된다(BBOX 정확히 2점, POLYGON 3~200점).

[★미제공 기능 — 구 서술 2건 폐기] 오토라벨링(YOLO/SAM2)·SAM2 분할·SAM2 추적·키포인트(SKELETON)·VLM 시계열·버전관리·검수는 두 경로 모두 제공하지 않는다(ADR-013).
· 폐기 1 — 구 본문의 '오토라벨링 체험(YOLO+SAM2)'은 오류였다.
· 폐기 2 — 그 뒤 남아 있던 'SAM2 분할·추적과 키포인트는 경로 A 에만 부분 노출된다(ADR-013 부분 override)'도 폐기한다. 부분 override 는 존재하지 않는다.
근거는 두 축이 서로 다르다. ①SAM2 — 구 포털 전용 SAM2 핸들러 기반 노출은 ADR-013 위반으로 폐지됐다 — 포털 경로에는 SAM2 핸들러를 두지 않는다(API-130·API-131 도 같은 근거로 폐기). ②키포인트 — 위 두 allowlist 가 BBOX|POLYGON 만 허용해 fail-closed 로 막으며, 구 '포털 키포인트(SKELETON) 허용'은 폐기됐다(레거시로 적재된 SKELETON 행은 로드 시 조용히 스킵).
⚠ 내부(INTERNAL) 채널의 SAM2 분할·추적은 SFR-08-01(VOS) 핵심 기능이라 그대로 살아 있다 — 포털 미제공을 저작도구 전체 미제공으로 오독하지 말 것.

[공통] 반응형 웹(PC/태블릿/모바일), WCAG 2.1 AA 준수. 인증은 포털 서버 발급 JWT 인계(channel 클레임으로 분기).

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| PORTAL_USER | 포털 회원 역할. 오토라벨링·검수·버전관리 권한은 없다 |
| 데이터마트 Load | 관제가 구축한 마트 영상의 기존 라벨·메타를 불러오는 것(조달원은 저작도구 DB 의 검수 승인 자산 — 포털 DB 를 읽지 않는다). 저장은 단방향이라 마트에 반영되지 않는다 |
| 포털 자산 업로드 | 포털 사용자 본인의 이미지·영상 업로드(ADR-013 예외). LS_PORTAL_* 전용이며 내부 파이프라인과 완전 분리된다 |
| TUS | 재개 가능 업로드 프로토콜(CVAT 포팅). 포털은 전용 세션 테이블(LS_PORTAL_TUS_ULD)을 쓴다 |
| LS_PORTAL_USER_LABEL | 경로 A 의 사용자별 작업 데이터 (원본 미수정) |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-003]] · [[ADR-013]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-018]] · [[ERD-026]] · [[ERD-028]] |
| 4 | EVT 이벤트 계약 | [[EVT-012]] |
| 5 | API 경계 계약 | [[API-024]] · [[API-081]] · [[API-082]] · [[API-083]] · [[API-110]] · [[API-111]] · [[API-115]] · [[API-139]] · [[API-140]] · [[API-142]] · [[API-147]] · [[API-149]] · [[API-151]] · [[API-154]] · [[API-155]] · [[API-157]] · [[API-159]] · [[API-161]] · [[API-163]] · [[API-166]] · [[API-169]] · [[API-171]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-043]] · [[DFEAT-044]] · [[DFEAT-053]] |
| 7 | SEQ 흐름 배선 | [[SEQ-016]] · [[SEQ-019]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-028]] · [[SCREEN-029]] · [[SCREEN-033]] · [[SCREEN-034]] |
| 10 | UC 검증 | [[UC-024]] · [[UC-027]] |
| 11 | TEST 통합시험 | [[TEST-005]] |
| 12 | CDIAG 클래스 구조 | [[CDIAG-011]] |
| 13 | C4 컴포넌트 | [[CMP-009]] |
| 14 | INT 외부 연동 | [[INT-009]] |
| 15 | SD 고충실 시안 | [[SD-024]] · [[SD-025]] · [[SD-026]] · [[SD-027]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, AC 수용, FEAT 상위 기능 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 30 |
| implemented | 26 |
| (미기재) | 10 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-024]] | api_endpoint | implemented | 100 |
| [[API-081]] | api_endpoint | implemented | 100 |
| [[API-082]] | api_endpoint | implemented | 100 |
| [[API-083]] | api_endpoint | implemented | 100 |
| [[API-110]] | api_endpoint | implemented | 100 |
| [[API-111]] | api_endpoint | implemented | 100 |
| [[API-115]] | api_endpoint | implemented | 100 |
| [[API-139]] | api_endpoint | implemented | 100 |
| [[API-140]] | api_endpoint | implemented | 100 |
| [[API-142]] | api_endpoint | implemented | 100 |
| [[API-147]] | api_endpoint | implemented | 100 |
| [[API-149]] | api_endpoint | implemented | 100 |
| [[API-151]] | api_endpoint | implemented | 100 |
| [[API-154]] | api_endpoint | implemented | 100 |
| [[API-155]] | api_endpoint | implemented | 100 |
| [[API-157]] | api_endpoint | implemented | 100 |
| [[API-159]] | api_endpoint | implemented | 100 |
| [[API-161]] | api_endpoint | implemented | 100 |
| [[API-163]] | api_endpoint | implemented | 100 |
| [[API-166]] | api_endpoint | implemented | 100 |
| [[API-169]] | api_endpoint | implemented | 100 |
| [[API-171]] | api_endpoint | implemented | 100 |
| [[DFEAT-053]] | domain_feature | implemented | 100 |
| [[EVT-012]] | domain_event | implemented | 100 |
| [[INT-009]] | integration_point | implemented | 0 |
| [[SCREEN-029]] | screen_spec | implemented | 100 |
| [[DFEAT-043]] | domain_feature | planned | 0 |
| [[DFEAT-044]] | domain_feature | planned | 0 |
| [[ERD-018]] | erd | planned | 0 |
| [[ERD-026]] | erd | planned | 0 |
| [[ERD-028]] | erd | planned | 0 |
| [[EXTSYS-006]] | external_system | planned | 0 |
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
| [[SCREEN-028]] | screen_spec | planned | 0 |
| [[SCREEN-033]] | screen_spec | planned | 0 |
| [[SCREEN-034]] | screen_spec | planned | 0 |
| [[SEQ-016]] | diagram_sequence | planned | 0 |
| [[SEQ-019]] | diagram_sequence | planned | 0 |
| [[UC-024]] | use_case | planned | 0 |
| [[UC-027]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (2)
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-013]] — 포털 범위 확정 — 데이터마트 영상 선택 + 본인 자산 업로드(예외), 오토라벨링·SAM2 인터랙티브·키포인트·트랙관리·검수·버전관리 미제공

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

### erd (3)
- [[ERD-018]] — 포털 ERD (고도화, PostgreSQL)
- [[ERD-026]] — 메타 복제 발신함 ERD (고도화, PostgreSQL)
- [[ERD-028]] — 포털 업로드 자산 ERD (고도화, PostgreSQL — LS_PORTAL_ULD, LS_PORTAL_ULD_FRME, LS_PORTAL_ULD_LBL, LS_PORTAL_TUS_ULD)

### domain_event (1)
- [[EVT-012]] — PortalVideoUploaded

### api_endpoint (22)
- [[API-024]] — GET /v1/manage/labels
- [[API-081]] — GET /v1/portal/datamart/labels
- [[API-082]] — POST /v1/portal/user-labels
- [[API-083]] — GET /v1/portal/user-labels
- [[API-110]] — GET /v1/portal/frames/{srcSn}/labels
- [[API-111]] — GET /v1/portal/frames/{srcSn}/image
- [[API-115]] — GET /v1/portal/datamart/videos
- [[API-139]] — POST /v1/portal/uploads/images
- [[API-140]] — GET /v1/portal/uploads/{uldSn}
- [[API-142]] — GET /v1/portal/uploads
- [[API-147]] — GET /v1/portal/uploads/{uldSn}/frames
- [[API-149]] — GET /v1/portal/uploads/frames/{uldFrmeSn}/image
- [[API-151]] — DELETE /v1/portal/uploads/{uldSn}
- [[API-154]] — PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels
- [[API-155]] — GET /v1/portal/uploads/frames/{uldFrmeSn}/labels
- [[API-157]] — GET /v1/portal/uploads/{uldSn}/export
- [[API-159]] — GET /v1/portal/uploads/{uldSn}/file
- [[API-161]] — OPTIONS /v1/portal/uploads/tus
- [[API-163]] — POST /v1/portal/uploads/tus
- [[API-166]] — HEAD /v1/portal/uploads/tus/{uldId}
- [[API-169]] — PATCH /v1/portal/uploads/tus/{uldId}
- [[API-171]] — DELETE /v1/portal/uploads/tus/{uldId}

### domain_feature (3)
- [[DFEAT-043]] — 데이터마트 영상 등록·기존 라벨 Load
- [[DFEAT-044]] — 포털 사용자 라벨 수정·저장·다운로드 (사용자별 격리)
- [[DFEAT-053]] — 포털 자산 업로드·수동 라벨링

### diagram_sequence (2)
- [[SEQ-016]] — 포털 데이터마트 라벨 작업 — 영상 선택부터 본인 작업 라벨 저장까지
- [[SEQ-019]] — 포털 자산 업로드·수동 라벨링 — 업로드부터 본인 데이터 다운로드까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (4)
- [[SCREEN-028]] — 포털 홈 화면
- [[SCREEN-029]] — 포털 라벨링 화면
- [[SCREEN-033]] — 포털 업로드 화면
- [[SCREEN-034]] — 포털 업로드 라벨링 화면

### use_case (2)
- [[UC-024]] — 포털 라벨 작업 (조회·수정·다운로드)
- [[UC-027]] — 포털 자산 업로드·수동 라벨링

### test_scenario (1)
- [[TEST-005]] — 포털 채널 데이터 읽기 Load 정상 흐름

### class_diagram (1)
- [[CDIAG-011]] — 포털 사용자 라벨 도메인 모델

### diagram_c4_component (1)
- [[CMP-009]] — 배정·포털·관제 통지 컴포넌트 (작업배정·포털 라벨·outbound 통지)

### integration_point (1)
- [[INT-009]] — 포털 DB 메타 단방향 복제 (듀얼 데이터소스)

### screen_design (4)
- [[SD-024]] — SCREEN-028 포털 홈 화면
- [[SD-025]] — SCREEN-029 포털 라벨링 화면
- [[SD-026]] — SCREEN-033 포털 업로드 화면
- [[SD-027]] — SCREEN-034 포털 업로드 라벨링 화면
