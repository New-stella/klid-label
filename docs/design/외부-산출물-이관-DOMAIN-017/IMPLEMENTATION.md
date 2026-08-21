# DOMAIN-017 외부 산출물 이관 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(49건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

외부에서 이미 라벨링이 끝난 산출물 묶음을 저작도구로 가져와, 검수를 거쳐 학습데이터로 편입시키는 도메인이다.

첫 대상은 1차 어노테이션 산출물이며, 다른 형식의 외부 산출물이 생기면 같은 도메인에서 받는다. 산출물은 프레임 이미지와 그 이미지를 설명하는 문서가 짝을 이룬 폴더로 들어오고, 문서에는 영상 단위 정보와 프레임 단위 정보, 그리고 도형 라벨과 텍스트 항목이 함께 담겨 있다.

검수자가 관리 화면에서 폴더 경로를 넣으면 서버가 그 폴더를 훑어 무엇이 몇 건 들어오는지, 처리할 수 없는 항목은 무엇인지 미리 보여준다. 검수자가 확인한 뒤에야 실제 적재가 일어난다. 적재된 영상은 곧바로 검수 대기 상태가 되며, 검수자는 내용을 그대로 승인하거나 고칠 것이 있으면 작업자에게 배정한다. 승인 이후는 저작도구가 원래 갖고 있던 경로를 그대로 탄다.

이 도메인이 따로 있는 이유는 세 가지 예외 때문이다. 첫째, 영상 적재는 관제가 인입 원장에 넣고 저작도구가 그것을 주기적으로 가져가는 것이 원칙인데, 이 경로는 그 원장을 거치지 않는다. 인입 원장은 관제가 보낸 것을 기록하는 자리이므로 저작도구가 스스로 넣으면 그 기록이 사실과 달라진다. 둘째, 비식별 처리는 모든 영상의 첫 단계로 자동 수행되는데, 외부 산출물은 이미 그 처리가 끝난 상태로 오거나 원본 그대로 오며 어느 쪽인지는 가져올 때 사람이 지정한다. 셋째, 검수는 작업자가 라벨링을 마치고 제출한 것을 대상으로 하는데, 이 경로에는 그 제출 단계가 없다.

외부 산출물의 분류 이름은 저작도구의 라벨 체계와 다르므로 그 대응을 한 번 정해 두고 재사용한다. 처음 보는 분류가 나오면 이름이 비슷한 후보를 제시하고 사람이 확인해 확정하며, 확정 이후 같은 분류는 자동으로 연결된다. 확정되지 않은 분류가 남아 있으면 적재하지 않는다. 이름만 보고 짐작해 연결하면 다른 분류로 저장되고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없기 때문이다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 산출물 폴더 | 외부에서 받은 한 영상 분량의 묶음. 프레임 이미지와 그에 대응하는 문서가 짝을 이루어 들어 있다. |
| 미리보기 | 폴더를 적재하기 전에 무엇이 몇 건 들어오는지, 처리할 수 없는 항목이 무엇인지 확인하는 단계. 이 단계에서는 아무것도 저장하지 않는다. |
| 분류 대응 | 외부 산출물이 쓰는 분류 이름과 저작도구 라벨 체계의 연결. 한 번 정하면 이후 같은 이름에 자동 적용된다. |
| 이관 이력 | 언제 누가 어떤 폴더를 가져왔고 몇 건이 들어왔는지에 대한 기록. 같은 산출물을 두 번 가져오려 할 때 이를 근거로 거부한다. |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-042]] · [[ADR-048]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-010]] · [[ERD-012]] · [[ERD-017]] · [[ERD-019]] · [[ERD-025]] · [[ERD-031]] |
| 4 | EVT 이벤트 계약 | [[EVT-005]] |
| 5 | API 경계 계약 | [[API-205]] · [[API-206]] · [[API-207]] · [[API-208]] · [[API-209]] · [[API-210]] · [[API-211]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-056]] · [[DFEAT-057]] · [[DFEAT-058]] · [[DFEAT-059]] |
| 7 | SEQ 흐름 배선 | [[SEQ-026]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-039]] |
| 10 | UC 검증 | [[UC-018]] · [[UC-035]] |
| 11 | AC 수용 | [[AC-041]] · [[AC-042]] · [[AC-043]] · [[AC-044]] · [[AC-045]] · [[AC-046]] · [[AC-047]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값 · TEST 통합시험 · CDIAG 클래스 구조 · C4 컴포넌트 · INT 외부 연동 · FEAT 상위 기능 · SD 고충실 시안 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 1 |
| planned | 45 |
| (미기재) | 3 |

| ITEM | type | status | progress |
|---|---|---|---|
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
| [[ERD-010]] | erd | planned | 0 |
| [[ERD-012]] | erd | planned | 0 |
| [[ERD-017]] | erd | planned | 0 |
| [[ERD-019]] | erd | planned | 0 |
| [[ERD-025]] | erd | planned | 0 |
| [[ERD-031]] | erd | planned | 0 |
| [[EVT-005]] | domain_event | implemented | 100 |
| [[API-205]] | api_endpoint | planned | 0 |
| [[API-206]] | api_endpoint | planned | 0 |
| [[API-207]] | api_endpoint | planned | 0 |
| [[API-208]] | api_endpoint | planned | 0 |
| [[API-209]] | api_endpoint | planned | 0 |
| [[API-210]] | api_endpoint | planned | 0 |
| [[API-211]] | api_endpoint | planned | 0 |
| [[DFEAT-056]] | domain_feature | planned | 0 |
| [[DFEAT-057]] | domain_feature | planned | 0 |
| [[DFEAT-058]] | domain_feature | planned | 0 |
| [[DFEAT-059]] | domain_feature | planned | 0 |
| [[SEQ-026]] | diagram_sequence | planned | 0 |
| [[ROLE-001]] | permission_role | planned | 0 |
| [[ROLE-002]] | permission_role | planned | 0 |
| [[ROLE-003]] | permission_role | planned | 0 |
| [[SCREEN-039]] | screen_spec | planned | 0 |
| [[UC-018]] | use_case | planned | 0 |
| [[UC-035]] | use_case | planned | 0 |
| [[AC-041]] | acceptance | planned | 0 |
| [[AC-042]] | acceptance | planned | 0 |
| [[AC-043]] | acceptance | planned | 0 |
| [[AC-044]] | acceptance | planned | 0 |
| [[AC-045]] | acceptance | planned | 0 |
| [[AC-046]] | acceptance | planned | 0 |
| [[AC-047]] | acceptance | planned | 0 |

## ITEM 인덱스

### adr (2)
- [[ADR-042]] — 관제 데이터 참조 전면 제거 — MNG_* 9종 삭제 + LS_DATA_INGEST 평면 수신
- [[ADR-048]] — 외부 산출물 이관은 인입 원장을 거치지 않고 직접 적재한다

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

### erd (6)
- [[ERD-010]] — 라벨링 ERD (고도화, PostgreSQL)
- [[ERD-012]] — 영상·프레임 수집 ERD (고도화, PostgreSQL)
- [[ERD-017]] — 비식별 ERD (고도화, PostgreSQL)
- [[ERD-019]] — 라벨 마스터·버전관리 ERD (고도화, PostgreSQL)
- [[ERD-025]] — 이벤트유형 마스터 ERD (고도화, PostgreSQL)
- [[ERD-031]] — 외부 산출물 이관 ERD

### domain_event (1)
- [[EVT-005]] — VideoIngested

### api_endpoint (7)
- [[API-205]] — 외부 산출물 폴더 검사
- [[API-206]] — 외부 산출물 적재
- [[API-207]] — 이관 이력 목록 조회
- [[API-208]] — 이관 이력 상세 조회
- [[API-209]] — 분류 대응 목록 조회
- [[API-210]] — 분류 대응 확정
- [[API-211]] — 분류 대응 해제

### domain_feature (4)
- [[DFEAT-056]] — 산출물 폴더 검사·미리보기
- [[DFEAT-057]] — 외부 산출물 적재
- [[DFEAT-058]] — 외부 분류 대응 관리
- [[DFEAT-059]] — 이관 이력 조회

### diagram_sequence (1)
- [[SEQ-026]] — 외부 산출물 가져오기 흐름

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (1)
- [[SCREEN-039]] — 외부 산출물 이관

### use_case (2)
- [[UC-018]] — 영상 적재 (관제 인입 테이블 직접 INSERT → 폴링 적재)
- [[UC-035]] — 외부 산출물 가져오기

### acceptance (7)
- [[AC-041]] — 검사는 아무것도 저장하지 않고 미리보기만 돌려준다
- [[AC-042]] — 대응이 정해지지 않은 분류가 남으면 적재하지 않는다
- [[AC-043]] — 한 번 확정한 분류 대응은 다음부터 자동으로 적용된다
- [[AC-044]] — 같은 산출물을 두 번 가져오면 거부한다
- [[AC-045]] — 적재된 영상은 배정 없이 바로 검수할 수 있다
- [[AC-046]] — 원본으로 가져온 영상은 비식별이 끝나기 전에는 승인되지 않는다
- [[AC-047]] — 짝이 없거나 개수가 다른 산출물은 경고만 하고 실제 파일 기준으로 적재한다

### domain (1)
- [[DOMAIN-017]] — 외부 산출물 이관
