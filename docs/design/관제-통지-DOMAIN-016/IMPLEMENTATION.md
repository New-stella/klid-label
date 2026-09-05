# DOMAIN-016 관제 통지 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(65건) · 버전은 `version-master.md`.
> ⚠ **아래 「빌드 순서」·「구현 현황」·「ITEM 인덱스」 세 표는 2026-08-18 스코프로 얼어 있다.** 키트 다운로더는 이 파일을 재생성하지 않아 SYNC 를 돌려도 따라오지 않는다 — 그 뒤 pin 에 편입된 ITEM(`ROLE-004`·`ADR-055` 등)이 표에 없고, 2026-08-31 재번호로 폐기된 3자리 AC 링크가 남아 있다. **있는 항목은 참이나 전수는 아니므로, 스코프 판정은 `.kit-scope.json` 으로 한다.** 본문 산문은 2026-09-01 에 폐기 결정 기준으로 정정했다.

## 도메인 (bounded context)

저작도구→관제서버 단방향 outbound 통지 + 관제가 상세를 별도로 조회해 가는 inbound 경로를 담당하는 도메인. 양방향 M2M 인증은 deprecated 이며 본 통지만 예외로 보유한다.

[통지 2종] 검수 완료 시 TASK_COMPLETED, 완료된 영상의 라벨/메타 수정이 재검수에서 승인될 때 TASK_MODIFIED. 둘 다 영상 1건 단위이고 메타·수정요약만 싣는다(라벨·메타 본문 미포함, PII·토큰·비-비식별 이미지 금지). 동일 작업 ID(RAW_SN)를 유지하고 버전업하지 않으며 수신측은 마지막 상태로 갱신한다. 요청 ID idempotency + dead-letter + 재등록 큐 + Resilience4j 를 적용한다.

[★발송 시점 = export 성공 이후 (구속)] 승인·재승인 양쪽 모두 export 가 SUCCEEDED 된 뒤에 보낸다. export 가 비동기라 통지가 앞서면 관제가 구 버전 폴더를 픽업한다. export 실패 시 통지를 보류하고 재산출 성공 후 재개한다(유실이 아니라 지연). 이벤트 체인: 검수 승인(EVT-006) → 산출 완료(EVT-009) → 완료 통지(EVT-003).

[★토글 경계] authoring.control-notify.enabled 는 통지 발송만 게이팅한다. 재-export 트리거(승인 연계·수정 축적·디바운스 flush)는 토글과 무관하게 항상 동작한다(dev/stg/prd 기본 형상 포함).

[관제 조회 패턴] 관제는 통지를 받은 뒤 RAW_SN 으로 데이터마트 적재용 View 를 SELECT 해 영상 1건=1row 로 UPSERT 한다. 현재 4종: V_COMPLETED_VIDEO(영상메타 + export 폴더 경로·프레임수) · V_COMPLETED_FRAME(원본/비식별 페어) · V_COMPLETED_LABEL_CHANGE(라벨 변경점) · V_COMPLETED_META(시계열 메타). ★라벨 본문 뷰는 V114(ADR-037)에서 제거됐다 — 라벨 좌표·속성은 export 폴더 JSON 에 있으므로 뷰로 중복 노출하지 않는다. 이는 관제 연동 계약 변경이라 협의 대상이다.

[★파생영상 픽업 경로] ORGNL_RAW_SN 이 non-null 인 행은 메타 동결 시 원본경로가 null 로 동결되어 V_COMPLETED_VIDEO.ORGNL_VDO_PATH_NM(개명 전 ORIGINAL_VIDEO_PATH) 가 NULL 이다. 파생은 '원본영상'이 없고 비식별본만 있기 때문이며, 관제는 DE_IDNTF_FILE_PATH_NM(V138)으로 픽업한다. 또 파생의 video.* 기술메타가 부모와 같은 것은 정상이다 — 증강·해상도 모두 비디오를 재인코딩하지 않고 복사하며 변환 대상은 프레임 이미지뿐이다(RESL 은 비디오 파일 기준이지 해상도 파생의 목표값이 아니다 — 관제에 명시 필요).

[★검수 완료·통지 건의 관제 접근은 무조건 보장] 승인되어 통지된 영상은 어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다. 비식별 누락 신고 구간에도 마찬가지다 — 관제가 보던 행이 예고 없이 사라지면 관제 배치가 삭제로 오인하기 때문이다. 즉 신고 게이트는 저작도구 앱 내부 통로에만 적용되고 관제 경계(뷰·통지)에는 적용되지 않는다 — 의도된 설계이므로 '잔여 누수'로 재분류해 다시 고치려 들지 말 것. ⚠ 승인 이력이 있는 영상은 신고 접수 자체가 412 로 막히므로 신고 접수 시 TASK_MODIFIED 를 발행하던 구 분기는 도달 불가가 됐다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| TASK_COMPLETED | 검수 완료 outbound 통지(메타만). export 가 SUCCEEDED 된 뒤에 발송된다 |
| TASK_MODIFIED | 검수 완료 후 수정 outbound 통지(변경 요약만). 재검수 승인 시점에 영상 1건 1회 |
| RAW_SN | 통지 단위 = 영상 1건 작업 ID. 재검수·수정에도 새로 발급하지 않는다 |
| OUTPUT_PATH_NM | V_COMPLETED_VIDEO 가 내려주는 영상 루트 경로(개명 전 EXPORT_PATH_NM). 버전 루트가 아니라 v1·v2 를 한 경로 아래에서 골라 비교·복구할 수 있게 한다 |
| changed_items | TASK_MODIFIED 의 변경 프레임 목록. export 재생성 경로면 전 프레임을, 아니면 빈 목록을 싣는다 |
| 데이터마트 적재용 View | 검수 완료 영상만 노출하는 4종 View. 관제가 통지 수신 후 단순 SELECT 한다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-002]] · [[ADR-007]] · [[ADR-013]] · [[ADR-020]] · [[ADR-033]] · [[ADR-037]] · [[ADR-046]] · [[ADR-055]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-021]] · [[ERD-027]] |
| 4 | EVT 이벤트 계약 | [[EVT-003]] · [[EVT-004]] · [[EVT-009]] · [[EVT-010]] |
| 5 | API 경계 계약 | [[API-074]] · [[API-075]] · [[API-076]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-006]] · [[DFEAT-043]] · [[DFEAT-044]] · [[DFEAT-046]] · [[DFEAT-047]] · [[DFEAT-053]] · [[DFEAT-054]] |
| 7 | SEQ 흐름 배선 | [[SEQ-010]] · [[SEQ-015]] · [[SEQ-020]] · [[SEQ-023]] · [[SEQ-025]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-019]] |
| 10 | UC 검증 | [[UC-009]] |
| 11 | AC 수용 | [[AC-009]] |
| 12 | TEST 통합시험 | [[TEST-004]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-013]] |
| 14 | C4 컴포넌트 | [[CMP-009]] |
| 15 | INT 외부 연동 | [[INT-007]] · [[INT-010]] |
| 16 | FEAT 상위 기능 | [[FEAT-003]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, SD 고충실 시안 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 27 |
| implemented | 17 |
| (미기재) | 13 |
| in_progress | 1 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-074]] | api_endpoint | implemented | 100 |
| [[API-075]] | api_endpoint | implemented | 100 |
| [[API-076]] | api_endpoint | implemented | 100 |
| [[DFEAT-006]] | domain_feature | implemented | 100 |
| [[DFEAT-046]] | domain_feature | implemented | 0 |
| [[DFEAT-047]] | domain_feature | implemented | 0 |
| [[DFEAT-053]] | domain_feature | implemented | 100 |
| [[DFEAT-054]] | domain_feature | implemented | 100 |
| [[EVT-003]] | domain_event | implemented | 100 |
| [[EVT-004]] | domain_event | implemented | 100 |
| [[EVT-009]] | domain_event | implemented | 100 |
| [[EVT-010]] | domain_event | implemented | 100 |
| [[FEAT-003]] | feature | implemented | 100 |
| [[INT-007]] | integration_point | implemented | 100 |
| [[SCREEN-019]] | screen_spec | implemented | 100 |
| [[SEQ-010]] | diagram_sequence | implemented | 100 |
| [[UC-009]] | use_case | implemented | 100 |
| [[EXTSYS-005]] | external_system | in_progress | 80 |
| [[AC-009]] | acceptance | planned | 0 |
| [[DFEAT-043]] | domain_feature | planned | 0 |
| [[DFEAT-044]] | domain_feature | planned | 0 |
| [[ERD-021]] | erd | planned | 0 |
| [[ERD-027]] | erd | planned | 0 |
| [[INT-010]] | integration_point | planned | 0 |
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
| [[SEQ-020]] | diagram_sequence | planned | 0 |
| [[SEQ-023]] | diagram_sequence | planned | 0 |
| [[SEQ-025]] | diagram_sequence | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (8)
- [[ADR-002]] — 검수 워크플로우 단일화 — 2차 검수 폐기
- [[ADR-055]] — 역할 4종 + 계층(ROLE_ADMIN > ROLE_REVIEWER) — ADR-003·ADR-043 supersede
- [[ADR-007]] — 관제서버 연동 — 양방향 M2M 폐기, 단방향 outbound 통지 채택
- [[ADR-013]] — 포털 범위 확정 — 데이터마트 영상 선택 + 본인 자산 업로드(예외), 오토라벨링·SAM2 인터랙티브·키포인트·트랙관리·검수·버전관리 미제공
- [[ADR-020]] — 검수 승인 학습데이터 export(NIA JSON) 산출을 저작도구 범위로 포함 (ADR-005 supersede)
- [[ADR-033]] — 라벨 변경이력 재설계 — 저장이벤트=diff + full-replace + 되돌리기
- [[ADR-037]] — 데이터마트 뷰 슬림화 — 라벨 내용 뷰 제거(V114)
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

### erd (2)
- [[ERD-021]] — 관제 통지·외부 위탁 멱등 ERD (고도화, PostgreSQL)
- [[ERD-027]] — 관제 통지 누적 ERD (고도화, PostgreSQL)

### domain_event (4)
- [[EVT-003]] — TaskCompleted
- [[EVT-004]] — TaskModified
- [[EVT-009]] — DatasetExportCompleted
- [[EVT-010]] — DatasetReExport

### api_endpoint (3)
- [[API-074]] — GET /v1/tasks/{rawSn}/summary
- [[API-075]] — GET /v1/tasks/{rawSn}/labels
- [[API-076]] — GET /v1/tasks/{rawSn}/meta

### domain_feature (7)
- [[DFEAT-006]] — 작업 배정·재배정·확인
- [[DFEAT-043]] — 데이터마트 영상 등록·기존 라벨 Load
- [[DFEAT-044]] — 포털 사용자 라벨 수정·저장·다운로드 (사용자별 격리)
- [[DFEAT-046]] — 작업 완료/수정 outbound 통지
- [[DFEAT-047]] — 관제 inbound 상세 조회 API 제공
- [[DFEAT-053]] — 포털 자산 업로드·수동 라벨링
- [[DFEAT-054]] — 검수 승인 학습데이터 산출(NIA JSON)

### diagram_sequence (5)
- [[SEQ-010]] — 데이터마트 수정 통지 — 검수완료 후 수정부터 TASK_MODIFIED push까지
- [[SEQ-015]] — 검수 승인·반려 시퀀스
- [[SEQ-020]] — 라벨 편집·임시저장 시퀀스
- [[SEQ-023]] — VLM 시계열 메타 검토 — 결과 콜백 수신부터 검토·수정·승인 확정까지
- [[SEQ-025]] — 연동 서버 주소 변경 — 관리자 단기 유효창 발급부터 재기동 없는 반영까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (1)
- [[SCREEN-019]] — 검수 상세 화면

### use_case (1)
- [[UC-009]] — 검수 완료·수정 통지

### acceptance (1)
- [[AC-009]] — 검수 완료 후 수정 통지(TASK_MODIFIED)

### test_scenario (1)
- [[TEST-004]] — 검수 완료·수정에 따른 관제서버 단방향 통지 정상 흐름

### class_diagram (1)
- [[CDIAG-013]] — 관제 통지 도메인 모델

### diagram_c4_component (1)
- [[CMP-009]] — 배정·포털·관제 통지 컴포넌트 (작업배정·포털 라벨·outbound 통지)

### integration_point (2)
- [[INT-007]] — 관제서버 완료/수정 통지
- [[INT-010]] — 데이터마트 View 제공(관제 픽업 경로)

### feature (1)
- [[FEAT-003]] — 데이터마트 라벨 동기화 통지
