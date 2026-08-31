---
logicraft_item: CDIAG-006
type: class_diagram
version: 12
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:08:52.411Z
status: CHANGED
prev_version: 11
content_hash: 46f4195da0a4aa404cd31ee07075aa98faf5c1a508e07fdf4f7941a652bfac63
stale: false
raw: ./_raw/CDIAG-006.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  depicts: ["[[DFEAT-021]]", "[[DFEAT-023]]", "[[DFEAT-024]]", "[[DFEAT-025]]"]
---

# 검수 도메인 모델

## theme

neutral

## title

검수 도메인 모델

## classes

### RawDataStatus

- **kind**: aggregate_root

**methods**:

#### assignToWorker

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제로는 AssignmentService 가 작업 배정 시 LsRawDataStatus.markAssigned() 를 호출해 ASSIGNED 로 전이시킨다.
- **is_abstract**: false
- **return_type**: void

#### submitForReview

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제로는 ReviewService 가 제출 시 ReviewStateMachine.verify(from,to) 로 전이를 검증한 뒤 LsRawDataStatus.transitionTo(PENDING) 을 호출한다.
- **is_abstract**: false
- **return_type**: void

#### approve

**params**:

- reviewerId: Long

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제로는 ReviewService.approve 가 승인을 수행하며 그 순서는 ①비식별화완료여부(deIdntfCmptnYn)가 완료(Y)인지 판정해 미완료(N)면 거부 ②ReviewStateMachine.verify(IN_REVIEW, APPROVED) ③transitionTo(APPROVED) 다. 가드가 상태 전이보다 앞서므로 거부될 때는 상태 전이도 라벨 버전 스냅샷도 학습데이터 산출물 재생성도 관제 통지도 하나도 만들어지지 않는다. reviewerId 매개변수에 해당하는 승인자 식별은 이 메서드가 받지 않으며 별도 감사 경로가 기록한다.
- **is_abstract**: false
- **return_type**: void

#### reject

**params**:

- issue: DataIssue

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제로는 ReviewService.reject 가 ReviewStateMachine.verify(IN_REVIEW, REJECTED) 검증 후 LsRawDataStatus.transitionTo(REJECTED) 를 호출한다. DataIssue 매개변수에 해당하는 반려 사유는 이 메서드가 받지 않는다(별도 저장 경로).
- **is_abstract**: false
- **return_type**: void

#### incrementReviewCycle

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — STP_CYCL/IGI_CYCL 필드는 존재하지만 생성 시점(initial, 항상 0)에만 값이 정해지고 그 이후 값을 증가시키는 공개 메서드가 실제 엔티티에 없다.
- **is_abstract**: false
- **return_type**: void

#### initial

**params**:

- rawDataId: Long

- **is_static**: true
- **visibility**: public
- **description**: 실제 정적 팩토리. DATA_STTS_CD=PENDING, STP_CYCL=0, IGI_CYCL=0, REVLT_YN=N(재검토불요), DE_IDNTF_CMPTN_YN=Y(비식별화 완료) 로 초기화한 신규 인스턴스를 생성한다. 미완료(N)로 시작하는 것은 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상뿐이다.
- **is_abstract**: false
- **return_type**: RawDataStatus

#### markAssigned

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 배정 완료 전이(검증 없음). DATA_STTS_CD 를 ASSIGNED 로, UPD_DT 를 현재 시각으로 갱신한다. 현재 상태를 보지 않으므로 종결 상태(APPROVED)에도 적용될 수 있다 — 호출 전 상태 검증은 호출자(AssignmentService) 책임이다.
- **is_abstract**: false
- **return_type**: void

#### markBatchQueued

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: DATA_STTS_CD 를 BATCH_QUEUED 로, UPD_DT 를 현재 시각으로 갱신한다.
- **is_abstract**: false
- **return_type**: void

#### transitionTo

**params**:

- newStatus: String

- **is_static**: false
- **visibility**: public
- **description**: 검수 워크플로우 상태 전이. 이 메서드는 검증하지 않는다 — 검증은 호출자 책임. ①ReviewService(submit/approve/reject 등) 가 ReviewStateMachine.verify(from,to) 로 사전 검증(허용: ASSIGNED→PENDING, PENDING→IN_REVIEW/ASSIGNED, IN_REVIEW→APPROVED/REJECTED, REJECTED→PENDING, APPROVED→PENDING 재검수뿐. 그 외 APPROVED 출발은 409, 그 밖의 불허 전이는 400). ②배치 경로는 BatchTransitionService 가 조건부 UPDATE 로 검수 소유 상태를 차단해 호출하지 않는다. ⚠검수 종결 영속 상태는 APPROVED — STTS_COMPLETED 로 전이하는 코드는 없다(MarkingBatchBridge 의 재트리거 차단 집합 멤버십 검사에만 읽힘). ⚠승인은 이 전이 전에 비식별화완료여부 판정을 먼저 통과해야 한다.
- **is_abstract**: false
- **return_type**: void

#### markNeedsRecheck

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 재검토 필요로 표시(REVLT_YN=Y). 멱등 — 이미 Y 면 값이 바뀌지 않아 UPDATE 가 나가지 않는다. UPD_DT(상태 전이 시각)는 건드리지 않는다 — 재검토 표시는 다른 축(수정 발생 여부)이다.
- **is_abstract**: false
- **return_type**: void

#### clearNeedsRecheck

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 재검토 표시 해제(REVLT_YN=N). 멱등 — 이미 N 이면 no-op. 재승인 경로에서 호출되며, 그 시점까지 축적된 변경 통지가 flush 된다.
- **is_abstract**: false
- **return_type**: void

#### needsRecheck

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 검수 승인 이후 라벨/메타가 수정되어 재검토가 필요한 상태인지 조회한다(REVLT_YN=='Y').
- **is_abstract**: false
- **return_type**: boolean

#### markDeidentNotCompleted

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 비식별화 미완료로 표시(DE_IDNTF_CMPTN_YN=N). 외부 산출물을 원본이라고 지정해 이관한 영상의 적재 시점에만 부른다. 이 동안 검수 승인이 거부된다. 멱등.
- **is_abstract**: false
- **return_type**: void

#### markDeidentCompleted

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 비식별화 완료로 표시(DE_IDNTF_CMPTN_YN=Y). 승인 보류를 푼다. 산출물 실재를 확인한 뒤에만 부른다 — 확인 없이 부르면 미처리 산출물이 승인을 통과한다(ADR-048). 멱등.
- **is_abstract**: false
- **return_type**: void

#### isDeidentCompleted

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 비식별화가 완료돼 검수 승인이 가능한 상태인가(DE_IDNTF_CMPTN_YN=='Y'). 승인 하나만 가른다 — 라벨 조회·프레임 이미지·영상 스트리밍·산출물 생성은 이 값으로 닫지 않는다.
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### rawDataId

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### dataSttsCd

- **type**: DataStatus
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### stpCycl

- **type**: Integer
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### igiCycl

- **type**: Integer
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### updDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### version

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### revltYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### deIdntfCmptnYn

- **type**: String
- **default**: Y
- **is_static**: false
- **visibility**: private
- **description**: 비식별화완료여부(DE_IDNTF_CMPTN_YN, 한 자리 Y/N). 기본값은 완료(Y)이며, 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상만 미완료(N)로 시작한다. 값이 N 인 동안 그 영상의 검수 승인이 거부된다. 이 값이 막는 것은 검수 승인 하나다 — 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성은 이 값으로 닫지 않는다. 그 통로들을 함께 닫는 것은 비식별 누락 신고 상태이며 별개 축이다.
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

- **description**: 영상별 배치/검수 진행 상태 Aggregate Root (LS_RAW_DATA_STATUS). DATA_STTS_CD 상태 전이의 단일 출처. 영상 1건당 1 row, @Version 낙관적 잠금으로 동시 승인 경합(CWE-362) 방어. APPROVED 전이가 승인 연쇄(스냅샷 → export 전량 재생성 → 산출 성공 후 관제 통지)의 시작점이다. revltYn(재검토여부, 기본 N)은 승인 이후 라벨/메타가 수정되면 Y 로 세워지고, 재승인 시 다시 N 으로 해제되며 그 시점에 축적된 변경 통지가 flush 된다. deIdntfCmptnYn(비식별화완료여부, 기본 Y)은 값이 N 인 동안 검수 승인을 거부하는 전제조건이며, 그 판정이 상태 전이보다 앞서므로 거부 시 승인 연쇄가 시작되지 않는다. 막는 범위는 검수 승인 하나다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataIssue

- **kind**: entity

**methods**:

#### create

**params**:

- dataRawSn: Long
- reason: String
- reportedUserNo: String

- **is_static**: true
- **visibility**: public
- **description**: 검수 반려 사유 등록(정적 팩토리). ISSUE_TYPE_CD=REJECTION, ISSUE_STTS_CD=RESOLVED 고정(상태 전이 비대상). 첫 반려는 UP_DATA_ISSUE_SN=NULL.
- **is_abstract**: false
- **return_type**: DataIssue

#### createWithParent

**params**:

- dataRawSn: Long
- reason: String
- reportedUserNo: String
- upDataIssueSn: Long

- **is_static**: true
- **visibility**: public
- **description**: 동일 영상 재반려 시 직전 반려를 UP_DATA_ISSUE_SN 으로 연결한 REJECTION 을 생성(계층형).
- **is_abstract**: false
- **return_type**: DataIssue

#### createInquiry

**params**:

- dataRawSn: Long
- content: String
- reportedUserNo: String
- srcSn: Long

- **is_static**: true
- **visibility**: public
- **description**: 작업자/검수자 문의 등록(정적 팩토리). ISSUE_TYPE_CD=INQUIRY, ISSUE_STTS_CD=OPEN 으로 시작하는 상태 머신. srcSn 은 프레임 단위 선택 참조(NULL 가능).
- **is_abstract**: false
- **return_type**: DataIssue

#### markAnswered

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: INQUIRY 답변 시 OPEN→ANSWERED 자동 전이(멱등, 이미 ANSWERED/RESOLVED 면 no-op). REJECTION 은 상태 전이 비대상.
- **is_abstract**: false
- **return_type**: void

#### resolve

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 해소 처리(REVIEWER). INQUIRY 의 OPEN/ANSWERED→RESOLVED. 이미 RESOLVED 면 멱등. 역행 없음.
- **is_abstract**: false
- **return_type**: void

#### assertCommentable

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 댓글 작성 가능 여부 검증 — RESOLVED INQUIRY 는 409. REJECTION 은 상태와 무관하게 허용.
- **is_abstract**: false
- **return_type**: void

#### isInquiry

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: ISSUE_TYPE_CD 가 INQUIRY 인가.
- **is_abstract**: false
- **return_type**: boolean

#### isResolved

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: ISSUE_STTS_CD 가 RESOLVED 인가.
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### dataIssueSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### upDataIssueSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### dataRawSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### issueRsn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### reportedUserNo

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### issueTypeCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### issueSttsCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### srcSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### version

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### regDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

- **description**: 검수 이슈 (LS_DATA_ISSUE). issueTypeCd 로 REJECTION(검수 반려 이력, 상태 전이 비대상, RESOLVED 고정)과 INQUIRY(작업자 문의, OPEN→ANSWERED→RESOLVED 상태 머신)를 구분한다. 영상 단위(dataRawSn) 참조 + srcSn 으로 프레임 단위 선택 참조(nullable). upDataIssueSn 자기참조로 동일 영상 재반려 시 직전 반려를 가리키는 계층형. version 은 resolve↔댓글 작성 동시성 보호(낙관적 잠금).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 영상 작업/검수 진행 상태 코드 (LS_RAW_DATA_STATUS.DATA_STTS_CD). 배치·검수 워크플로우 상태 전이 단일 출처. 검수 종결값은 APPROVED 이며 COMPLETED 로 전이하는 경로는 이 축에 없다 — 열거값은 코드 도메인 보존을 위해 남겨 둔다.

**enum_values**:

- PENDING
- BATCH_QUEUED
- PROCESSING
- ASSIGNED
- IN_REVIEW
- APPROVED
- REJECTED
- COMPLETED
- FAILED

**stereotypes**:

_(empty)_

### FrameColor

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 프레임 상태 색상 표기 (DFEAT-023). 연두=라벨 저장됨, 빨강=문의 제기됨. 주황(반려)은 반려가 영상 단위여서 특정 프레임에 매핑되지 않아 프레임 색으로 표시되지 않는다. 모두 연두일 때만 제출 가능. (색상-상태 매핑은 화면 표기 규칙 — 코드값 ERD 미정의, 논리 enum.)

**enum_values**:

- GREEN
- ORANGE
- RED

**stereotypes**:

_(empty)_

### IssueComment

- **kind**: entity

**methods**:

#### create

**params**:

- dataIssueSn: Long
- authorNo: String
- authorRoleCd: String
- content: String

- **is_static**: true
- **visibility**: public
- **description**: 댓글 생성(정적 팩토리). authorNo/authorRoleCd 는 요청 DTO 가 아니라 인증 토큰(actor)에서 도출한 값만 받는다(CWE-915 Mass Assignment 방어).
- **is_abstract**: false
- **return_type**: IssueComment

**attributes**:

#### issueCommentSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### dataIssueSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### authorNo

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### authorRoleCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### cmntCn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### regDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

- **description**: 이슈 댓글 (LS_ISSUE_COMMENT, V57). 검수자↔작업자 양방향 소통의 단위 메시지. 한 이슈(DATA_ISSUE_SN)에 N개 댓글. AUTHOR_NO/AUTHOR_ROLE_CD 는 인증 토큰에서만 도출(CWE-915 방어). 외래키 미정의(klid_system 공유 DB 정책).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

영상 단위(RAW_SN)로 적재 등록·배치/검수 진행 상태(DATA_STTS_CD)를 추적하고, REVIEWER 의 승인/반려 워크플로우·계층형 반려 사유(자기참조 재반려)·검수 이력을 관리하는 검수 도메인 모델. 낙관적 잠금(VERSION)으로 동시 승인 경합을 방어한다. ERD-015 기반.

[★관리자확인요청 폐기] 구 본문의 '승인/반려/관리자확인요청' 3진 워크플로우는 폐기됐다 — 별도 ADMIN 역할이 없고 모든 관리 권한이 REVIEWER 에 통합됐기 때문이다(ADR-003). 검수자↔작업자 소통은 반려·문의 통합 이슈 스레드가 대신한다. 1차/2차 단계 구분도 없는 단일 검수다(ADR-002).

[★승인 이후 순서 정정] 구 본문은 'APPROVED 전이 시 관제 TASK_COMPLETED 통지를 트리거'라고 적었으나 통지는 export 가 SUCCEEDED 된 뒤에만 나간다. 실제 체인은 ReviewApproved(EVT-006) → 라벨 전체 스냅샷 + export 전량 재생성 → DatasetExportCompleted(EVT-009) → TaskCompleted(EVT-003) 이다. 통지가 먼저 나가면 관제가 구 버전 폴더를 픽업한다.

[상태 소유권] LS_RAW_DATA_STATUS.DATA_STTS_CD 를 검수 종결값 APPROVED 로 전이시키는 유일한 지점이 검수 승인이다 — 배치 완료는 ASSIGNED 로 복귀시킬 뿐 이 상태로 점프하지 않는다. 이 축에서 COMPLETED 로 전이하는 경로는 없다 — 같은 이름의 COMPLETED 는 LS_DATA_RAW 의 배치 단계 완료값이거나 검수 응답 status 의 표시 매핑값이라 축이 다르다.

[승인 전제조건] 검수 워크플로 상태에는 비식별화완료여부(DE_IDNTF_CMPTN_YN, 한 자리 Y/N, 기본 Y)가 함께 놓인다. 값이 N 인 동안 그 영상의 검수 승인은 거부되며, 이 판정은 상태 전이보다 먼저 이뤄지므로 거부될 때 상태 전이도 라벨 버전 스냅샷도 학습데이터 산출물 재생성도 관제 통지도 하나도 생기지 않는다. N 으로 시작하는 것은 외부에서 이미 라벨링이 끝난 산출물을 가져오는 경로로 원본이라고 지정해 들어온 영상뿐이며, 그 경로와 무관한 기존 영상은 기본값 Y 라 이 전제조건 때문에 막히지 않는다. 이 값이 막는 것은 검수 승인뿐이다 — 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성은 이 값으로 닫지 않으며, 그 통로들을 함께 닫는 것은 비식별 누락 신고 상태로서 이 값과는 별개 축이다.

## module_name

Review

## relationships

### [1]

- **to**: RawDataStatus
- **from**: DataIssue
- **kind**: association
- **label**: 영상 단위 반려 사유
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [2]

- **to**: DataIssue
- **from**: DataIssue
- **kind**: association
- **label**: 직전 반려(재반려 계층)
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

### [3]

- **to**: DataStatus
- **from**: RawDataStatus
- **kind**: association
- **label**: 진행 상태
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [4]

- **to**: DataIssue
- **from**: IssueComment
- **kind**: association
- **label**: 이슈 댓글
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-021
- DFEAT-023
- DFEAT-024
- DFEAT-025

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

### module_paths

_(empty)_

## referenced_items

_(empty)_

## realizes_features

_(empty)_
