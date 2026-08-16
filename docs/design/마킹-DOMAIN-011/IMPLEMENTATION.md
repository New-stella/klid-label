# DOMAIN-011 마킹 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(47건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

영상에서 자동(프레임 간격)/수동(작업자 단축키) 모드로 이벤트 시점을 식별·표시하는 도메인.

[★대상은 비식별 영상이다 — 파이프라인의 시작점이 아니다] 구 서술은 마킹을 '파이프라인의 출발점'으로 적었으나, 선두는 비식별화다. 적재 직후 비식별이 자동 수행되어 MARKING_READY 가 된 뒤에야 마킹이 열리며, 작업자는 원본이 아닌 비식별 영상을 보며 마킹한다. 마킹 완료가 트리거하는 것은 전체 배치가 아니라 잔여 배치(VLM → 프레임추출 → YOLO → SAM2 → 보간)이고, 부모의 deIdntfYn 이 'Y' 일 때만 진행한다.

[화면] 비식별 영상 스트리밍(HTTP Range) + 배속 0.25x~4x + 단축키(Space 마킹, Del 삭제, Enter 완료). 스트리밍은 항상 비식별본만 서빙하며 비식별 미완료면 404 로 원본 노출을 막는다.

[★마킹 중 비식별 누락 발견] 이 단계에서도 신고할 수 있다(rawSn 기준). 라벨링 단계 신고(srcSn 기준)와 2채널이며 둘 다 구현됐다.

[VLM 연계] 마킹 결과는 frame_policy(수동 마킹=frame_selected+선택 프레임 목록 selected_frames, 벤더 상한 8 초과분 절단 / 자동 마킹·마킹 부재·미지 모드=frame_interval)로만 VLM 시계열 위탁(POST /v1/videovlm/verify)에 반영된다 — 이벤트명·영상 경로·마킹 원문 배열은 위탁 규격 밖이라 싣지 않는다. ★위탁은 논블로킹 제출이다 — 파이프라인 스레드를 붙잡지 않고 제출만 개시하며, 결과 상세는 VLM 서버가 별도 콜백으로 보낸다. 신고 구간에는 위탁을 보류하고 해소 시 재위탁한다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 마킹(Marking) | 비식별 영상 내 이벤트 시점 표시 — 자동(프레임 간격)/수동(단축키) 모드 |
| intervalFrames | 자동 마킹의 프레임 간격 |
| MARKING_READY | 선두 비식별이 성공해 마킹 진입이 허용된 상태. 마킹은 이 이후에만 가능하다 |
| MarkingCompleted | 마킹 완료 도메인 이벤트(EVT-001) — 트랜잭션 커밋 후 잔여 배치를 비동기로 시작한다 |
| 비식별 신고(마킹 단계) | 마킹 중 개인정보 노출을 발견해 rawSn 기준으로 재비식별을 요청하는 경로 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-006]] · [[ADR-008]] · [[ADR-022]] · [[ADR-046]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-013]] |
| 4 | EVT 이벤트 계약 | [[EVT-001]] |
| 5 | API 경계 계약 | [[API-043]] · [[API-047]] · [[API-084]] · [[API-091]] · [[API-114]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-039]] · [[DFEAT-048]] |
| 7 | SEQ 흐름 배선 | [[SEQ-001]] · [[SEQ-014]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-006]] · [[SCREEN-008]] |
| 10 | UC 검증 | [[UC-019]] |
| 11 | AC 수용 | [[AC-027]] · [[AC-028]] |
| 12 | TEST 통합시험 | [[TEST-001]] · [[TEST-002]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-002]] |
| 14 | C4 컴포넌트 | [[CMP-002]] |
| 15 | INT 외부 연동 | [[INT-002]] |
| 16 | FEAT 상위 기능 | [[FEAT-005]] |
| 17 | SD 고충실 시안 | [[SD-012]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 24 |
| (미기재) | 12 |
| implemented | 11 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-043]] | api_endpoint | implemented | 100 |
| [[API-047]] | api_endpoint | implemented | 100 |
| [[API-084]] | api_endpoint | implemented | 100 |
| [[API-091]] | api_endpoint | implemented | 100 |
| [[API-114]] | api_endpoint | implemented | 100 |
| [[DFEAT-039]] | domain_feature | implemented | 100 |
| [[EVT-001]] | domain_event | implemented | 100 |
| [[FEAT-005]] | feature | implemented | 100 |
| [[INT-002]] | integration_point | implemented | 0 |
| [[SCREEN-008]] | screen_spec | implemented | 100 |
| [[UC-019]] | use_case | implemented | 100 |
| [[AC-027]] | acceptance | planned | 0 |
| [[AC-028]] | acceptance | planned | 0 |
| [[DFEAT-048]] | domain_feature | planned | 0 |
| [[ERD-013]] | erd | planned | 0 |
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
| [[SCREEN-006]] | screen_spec | planned | 0 |
| [[SEQ-001]] | diagram_sequence | planned | 0 |
| [[SEQ-014]] | diagram_sequence | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (6)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-006]] — 비식별 처리 외부 솔루션 연동 — 캔버스 수동 블러 폐기
- [[ADR-008]] — 마킹 도메인 신규 도입 — 자동/수동 이벤트 식별
- [[ADR-022]] — 비식별 신고 게이트 아키텍처 — 판정범위 자기행 단일원천·엔드포인트별 응답코드·해소시 복구범위
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
- [[ERD-013]] — 마킹 ERD (고도화, PostgreSQL)

### domain_event (1)
- [[EVT-001]] — MarkingCompleted

### api_endpoint (5)
- [[API-043]] — GET /v1/videos/{rawSn}
- [[API-047]] — POST /v1/videos/{rawSn}/markings
- [[API-084]] — GET /v1/videos/{rawSn}/stream
- [[API-091]] — POST /v1/videos/{rawSn}/deident-report
- [[API-114]] — GET /v1/videos/{rawSn}/stream-url

### domain_feature (2)
- [[DFEAT-039]] — 마킹 (자동/수동 이벤트 식별)
- [[DFEAT-048]] — 비식별 누락 신고

### diagram_sequence (2)
- [[SEQ-001]] — 영상수집 파이프라인 — 비식별·마킹부터 트랙 보간까지
- [[SEQ-014]] — 비식별 누락 신고·수동 해소 — 신고부터 RESOLVED까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (2)
- [[SCREEN-006]] — 마킹 화면
- [[SCREEN-008]] — 영상 처리 현황 화면

### use_case (1)
- [[UC-019]] — 이벤트 마킹 (자동/수동)

### acceptance (2)
- [[AC-027]] — 자동/수동 마킹 완료·잔여 배치 트리거
- [[AC-028]] — 마킹에서 도출된 VLM 위탁 입력(frame_policy·event_type)

### test_scenario (2)
- [[TEST-001]] — 관제 학습용 영상 적재 후 선두 비식별 처리(외부 위탁·폴링) 정상 흐름
- [[TEST-002]] — 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름

### class_diagram (1)
- [[CDIAG-002]] — 마킹 도메인 모델

### diagram_c4_component (1)
- [[CMP-002]] — 마킹 컴포넌트 (이벤트 시점 마킹·배치 트리거)

### integration_point (1)
- [[INT-002]] — VLM 시계열 위탁 요청

### feature (1)
- [[FEAT-005]] — 개인정보 비식별 처리 (솔루션 연동·옵션 사용)

### screen_design (1)
- [[SD-012]] — SCREEN-006 마킹 화면
