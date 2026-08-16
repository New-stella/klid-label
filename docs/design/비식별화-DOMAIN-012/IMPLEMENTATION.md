# DOMAIN-012 비식별화 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(72건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

영상의 개인정보 비식별 처리를 외부 비식별 솔루션(KPST) 위탁으로 수행하는 도메인.

[★파이프라인 선두 — 게이팅 폐지] 적재된 모든 영상이 (ANONY 포함) 무조건 비식별 대상이다. 구 서술 'PRVC_TYPE_CD 가 PRVC/PSDO 일 때만 호출'은 폐기됐다. 적재 직후 VideoIngested(EVT-005) 가 커밋 이후(AFTER_COMMIT) 비동기로 비식별 단계를 자동 트리거하며, 성공 시 LsDataRaw.dataSttsCd 가 MARKING_READY 로 전이해 마킹 진입이 열린다. 마킹·라벨링의 대상은 비식별 영상이고 원본은 별도 경로에 보존된다.

[★결과 수신 = 폴링(콜백 아님)] 구 서술 '외부 콜백(webhook)으로 결과 수신'은 폐기됐다. 위탁 후 주기 폴링(진행조회)으로 완료를 감지하고 산출물 경로를 기록한다. 산출 파일명은 우리가 정하지 않는다 — mock 은 deidentified.mp4, KPST 실연동은 {원본stem}-mask{ext} 라 영상마다 다르므로 반드시 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 값을 읽고 문자열로 조합·추측하지 않는다.

[★제출은 논블로킹] KPST 비식별 제출은 WAITING 원장 행을 선커밋하고 createProject 를 비동기 디스패치한다. 스텝이 확정적으로 말하는 사실은 '제출을 개시했다' 뿐이며 수락(ACK)은 완료 핸들러가 비동기 기록한다. 아무 신호도 없으면 미결 스위퍼가 회수하고, 2노드 Active-Active 에서 같은 후보를 두 번 재위탁하지 않도록 조건부 UPDATE 로 원자 클레임한다.

[실패·재처리] 실패 시 DE_IDNTF_YN='F' 마킹 + 원본 절대 삭제 금지. 자동 재비식별 큐는 폐기됐고 외부 비식별 프로그램에서 수동 재비식별 후 resolve 로 해소한다.

[★비식별 누락 신고] 작업자가 개인정보 노출을 발견하면 신고 → 작업락 + DE_IDNTF_YN='F'. 라벨도 개인정보 판정도 삭제하지 않고 보존한다. 신고 구간에는 조회뿐 아니라 라벨 저장·버전 diff·롤백·개인정보 메타 PUT·이벤트 어노테이션 저장/승인/반려·검수 승인까지 412 로 차단되며(영상 스트리밍만 404), 한 번이라도 검수 승인된 이력이 있는 영상은 신규 신고 접수 자체를 막는다(판정 축은 지금 상태가 아니라 승인 이력). 게이트 판정 범위는 자기 rawSn 행 하나이며 조상/자손 전파는 4라운드 시도 후 철회됐다 — 다시 시도하지 말 것. 파생영상은 신고 체계 바깥이라 접수하지 않고(412) 원본 신고의 영향도 받지 않는다. 검수 완료·통지 건에 대한 관제 접근은 신고 구간에도 차단하지 않는다.

[잔존 책임] 저작도구는 위탁·결과 저장(DFEAT-041)·대상 범위(DFEAT-042)·누락 신고(DFEAT-048)를 보유한다. 비식별 결과의 상세(deep) 검토는 외부 솔루션 프로그램이 수행하며, 저작도구는 그 축을 경량 상태·이력 확인(FEAT-006)으로만 보유한다 — 폐기된 것은 FEAT-006 자체가 아니라 그 상세 검토 UC·화면·API(UC-012·SCREEN-016/017·API-051/052)다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| DE_IDNTF_YN | 비식별 여부. Y=완료, N=미수행, F=실패 또는 누락 신고 중. 'F' 는 의미가 둘이라 플래그만으로 구분되지 않고 산출물 실재 검증이 fail-closed 로 뒤를 받친다 |
| DE_IDNTF_SRC_FILE_PATH_NM | 비식별 프레임 경로 (원본 SRC_FILE_PATH_NM 과 별도). 신규 추출은 frames/raw|deid/{rawSn} 로 분기 저장돼 두 경로가 항상 다르다 |
| DE_IDNTF_FILE_PATH_NM | 비식별 영상 파일 경로. 외부 솔루션이 파일명을 정하므로 이 값을 읽어야 하며 조합·추측 금지 |
| 비식별 누락 신고 | 마킹·라벨링 중 개인정보 노출을 발견해 재비식별을 요청하는 행위. 작업락과 'F' 마킹을 동반한다. 라벨과 개인정보 판정은 지우지 않고 보존한다 |
| 신고 게이트 | 자기 rawSn 행의 DE_IDNTF_YN='F' 단일 컬럼만 보고 판정하는 프리컨디션. 인가 검사 이후 평가되며 역할 무관이다 |
| MARKING_READY | 선두 비식별이 성공해 마킹 진입이 허용된 상태 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-006]] · [[ADR-020]] · [[ADR-022]] · [[ADR-024]] · [[ADR-025]] · [[ADR-027]] · [[ADR-032]] · [[ADR-046]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-017]] |
| 4 | EVT 이벤트 계약 | [[EVT-007]] · [[EVT-008]] |
| 5 | API 경계 계약 | [[API-032]] · [[API-091]] · [[API-094]] · [[API-109]] · [[API-112]] · [[API-183]] · [[API-184]] · [[API-202]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-041]] · [[DFEAT-042]] · [[DFEAT-048]] · [[DFEAT-051]] · [[DFEAT-054]] |
| 7 | SEQ 흐름 배선 | [[SEQ-001]] · [[SEQ-012]] · [[SEQ-013]] · [[SEQ-014]] · [[SEQ-025]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-008]] · [[SCREEN-009]] · [[SCREEN-025]] · [[SCREEN-032]] |
| 10 | UC 검증 | [[UC-011]] · [[UC-013]] · [[UC-016]] |
| 11 | AC 수용 | [[AC-011]] · [[AC-013]] · [[AC-016]] · [[AC-019]] · [[AC-023]] |
| 12 | TEST 통합시험 | [[TEST-001]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-003]] |
| 14 | C4 컴포넌트 | [[CMP-003]] |
| 15 | INT 외부 연동 | [[INT-004]] · [[INT-005]] |
| 16 | FEAT 상위 기능 | [[FEAT-005]] · [[FEAT-006]] |
| 17 | SD 고충실 시안 | [[SD-021]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 34 |
| implemented | 22 |
| (미기재) | 16 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-032]] | api_endpoint | implemented | 100 |
| [[API-091]] | api_endpoint | implemented | 100 |
| [[API-094]] | api_endpoint | implemented | 100 |
| [[API-112]] | api_endpoint | implemented | 100 |
| [[API-202]] | api_endpoint | implemented | 100 |
| [[DFEAT-051]] | domain_feature | implemented | 100 |
| [[DFEAT-054]] | domain_feature | implemented | 100 |
| [[EVT-007]] | domain_event | implemented | 100 |
| [[EVT-008]] | domain_event | implemented | 100 |
| [[EXTSYS-003]] | external_system | implemented | 100 |
| [[FEAT-005]] | feature | implemented | 100 |
| [[FEAT-006]] | feature | implemented | 80 |
| [[INT-004]] | integration_point | implemented | 100 |
| [[INT-005]] | integration_point | implemented | 100 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-008]] | screen_spec | implemented | 100 |
| [[SCREEN-009]] | screen_spec | implemented | 100 |
| [[SCREEN-025]] | screen_spec | implemented | 100 |
| [[SCREEN-032]] | screen_spec | implemented | 100 |
| [[SEQ-012]] | diagram_sequence | implemented | 0 |
| [[SEQ-013]] | diagram_sequence | implemented | 0 |
| [[UC-016]] | use_case | implemented | 100 |
| [[AC-011]] | acceptance | planned | 0 |
| [[AC-013]] | acceptance | planned | 0 |
| [[AC-016]] | acceptance | planned | 0 |
| [[AC-019]] | acceptance | planned | 0 |
| [[AC-023]] | acceptance | planned | 0 |
| [[API-109]] | api_endpoint | planned | 0 |
| [[API-183]] | api_endpoint | planned | 0 |
| [[API-184]] | api_endpoint | planned | 0 |
| [[DFEAT-041]] | domain_feature | planned | 0 |
| [[DFEAT-042]] | domain_feature | planned | 0 |
| [[DFEAT-048]] | domain_feature | planned | 0 |
| [[ERD-017]] | erd | planned | 0 |
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
| [[SEQ-001]] | diagram_sequence | planned | 0 |
| [[SEQ-014]] | diagram_sequence | planned | 0 |
| [[SEQ-025]] | diagram_sequence | planned | 0 |
| [[UC-011]] | use_case | planned | 0 |
| [[UC-013]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (10)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-006]] — 비식별 처리 외부 솔루션 연동 — 캔버스 수동 블러 폐기
- [[ADR-020]] — 검수 승인 학습데이터 export(NIA JSON) 산출을 저작도구 범위로 포함 (ADR-005 supersede)
- [[ADR-022]] — 비식별 신고 게이트 아키텍처 — 판정범위 자기행 단일원천·엔드포인트별 응답코드·해소시 복구범위
- [[ADR-024]] — VLM 시계열 위탁의 신고구간 보류(SKIPPED, 실패 아님)
- [[ADR-025]] — 게이트된 미디어 응답 = Cache-Control: no-store 통일
- [[ADR-027]] — 재비식별 강제 재생성 + resolve fail-closed 게이트
- [[ADR-032]] — 촬영환경·개인정보 메타 수동입력 신설(+self-fill 자동파생 폐기)
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
- [[ERD-017]] — 비식별 ERD (고도화, PostgreSQL)

### domain_event (2)
- [[EVT-007]] — DeidentGateReopened
- [[EVT-008]] — DeidentReportResolved

### api_endpoint (8)
- [[API-032]] — POST /v1/labels/{srcSn}/deident-report
- [[API-091]] — POST /v1/videos/{rawSn}/deident-report
- [[API-094]] — POST /v1/deident-reports/{rprtSn}/resolve
- [[API-109]] — GET /v1/deident-reports
- [[API-112]] — POST /v1/videos/{rawSn}/redeident
- [[API-183]] — GET /v1/videos/{rawSn}/privacy-meta
- [[API-184]] — PUT /v1/videos/{rawSn}/privacy-meta
- [[API-202]] — GET /v1/deident-reports/{rprtSn}/deident-candidates

### domain_feature (5)
- [[DFEAT-041]] — 비식별 처리 위탁·결과 저장
- [[DFEAT-042]] — 비식별 대상·범위 설정 (전체 영상 자동 실행 — 게이팅 폐지)
- [[DFEAT-048]] — 비식별 누락 신고
- [[DFEAT-051]] — 촬영환경·개인정보 메타 수동입력
- [[DFEAT-054]] — 검수 승인 학습데이터 산출(NIA JSON)

### diagram_sequence (5)
- [[SEQ-001]] — 영상수집 파이프라인 — 비식별·마킹부터 트랙 보간까지
- [[SEQ-012]] — 비식별 처리 요청 — 선두 자동 위탁부터 KPST 폴링 완료까지
- [[SEQ-013]] — 비식별 옵션 설정 — 관리 화면 설정 저장
- [[SEQ-014]] — 비식별 누락 신고·수동 해소 — 신고부터 RESOLVED까지
- [[SEQ-025]] — 연동 서버 주소 변경 — 관리자 단기 유효창 발급부터 재기동 없는 반영까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (5)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-008]] — 영상 처리 현황 화면
- [[SCREEN-009]] — 영상 상세 화면
- [[SCREEN-025]] — 시스템 설정 화면
- [[SCREEN-032]] — 비식별 신고 관리 화면

### use_case (3)
- [[UC-011]] — 비식별 처리 요청
- [[UC-013]] — 비식별 옵션 설정
- [[UC-016]] — 비식별 처리 상태·이력 확인

### acceptance (5)
- [[AC-011]] — 비식별 처리 요청·결과 저장
- [[AC-013]] — 비식별 옵션 설정
- [[AC-016]] — 비식별 처리 상태·이력 화면 확인
- [[AC-019]] — 개인정보 비식별화 처리·검수·누락 신고
- [[AC-023]] — 이미지 학습데이터 가공(추출·라벨링·가명·검수)

### test_scenario (1)
- [[TEST-001]] — 관제 학습용 영상 적재 후 선두 비식별 처리(외부 위탁·폴링) 정상 흐름

### class_diagram (1)
- [[CDIAG-003]] — 비식별화 도메인 모델

### diagram_c4_component (1)
- [[CMP-003]] — 비식별 컴포넌트 (위탁·폴링·신고)

### integration_point (2)
- [[INT-004]] — 비식별 처리 위탁 요청
- [[INT-005]] — 비식별 진행 상태 폴링

### feature (2)
- [[FEAT-005]] — 개인정보 비식별 처리 (솔루션 연동·옵션 사용)
- [[FEAT-006]] — 비식별 처리 상태·이력 확인 (경량)

### screen_design (1)
- [[SD-021]] — SCREEN-032 비식별 신고 관리 화면
