---
logicraft_item: CDIAG-007
type: class_diagram
version: 9
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T12:01:34.632Z
status: CHANGED
prev_version: 8
content_hash: 26b0fbc33a892854c196cdc6825abd4eed9fec89b9433039148d266c876b9b5c
stale: false
raw: ./_raw/CDIAG-007.json
links:
  belongs_to_domain: ["[[DOMAIN-015]]"]
  depicts: ["[[DFEAT-006]]"]
  references: ["[[DFEAT-006]]", "[[ERD-014]]"]
---

# 작업 배정 도메인 모델

## theme

neutral

## title

작업 배정 도메인 모델

## classes

### TaskAssignment

- **kind**: aggregate_root

**methods**:

#### createLabeler

**params**:

- rawDataId: Long
- workerNo: Long
- actorNo: Long

- **is_static**: true
- **visibility**: public
- **description**: 라벨링 작업자 배정 생성(정적 팩토리). TASK_TYPE_CD=LABELER.
- **is_abstract**: false
- **return_type**: TaskAssignment

#### createReviewer

**params**:

- rawDataId: Long
- reviewerNo: Long
- actorNo: Long

- **is_static**: true
- **visibility**: public
- **description**: 검수자 배정 생성(정적 팩토리). TASK_TYPE_CD=REVIEWER.
- **is_abstract**: false
- **return_type**: TaskAssignment

#### reassignTo

**params**:

- newWorkerNo: Long

- **is_static**: false
- **visibility**: public
- **description**: 재배정 — 기존 행의 USER_NO 를 새 담당자로 갱신한다(새 행 INSERT 아님, UK 미발화). 이벤트 로그 적재는 호출자(AssignmentService) 책임.
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### assignmentId

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

#### userNo

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

#### rawDataId

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

#### taskTypeCd

- **type**: TaskTypeCode
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

#### regUserNo

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

#### version

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 낙관적 잠금(VER). 재배정은 기존 행을 갱신하므로 유니크 제약이 발화하지 않고 동일작업자 가드도 커밋 전 값을 함께 읽어 통과한다 — 두 노드 동시 재배정을 직렬화하는 유일한 방어라 빠뜨리면 중복 배정·중복 이력이 남는다.
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

- **description**: 영상 1건 단위 작업 배정 루트. (RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 유니크로 중복 배정 차단. REVIEWER가 LABELER/REVIEWER 배정을 동일 테이블로 관리. (LS_TASK_ALTMNT)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TaskEventLog

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### eventSeq

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

#### rawDataId

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

#### eventTypeCd

- **type**: TaskEventType
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

#### actorUserNo

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

#### subjectUserNo

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

#### prevUserNo

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

#### rsn

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

#### ocrnDt

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

- **description**: 작업(영상) 단위 라이프사이클 이벤트 누적 로그 (SCR-TASK-003 작업 이력 화면용). 배정/재배정/검수 제출/승인/반려를 시간순 단일 타임라인으로 누적. (LS_TASK_EVNT_LOG)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TaskTypeCode

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 작업 유형 코드. LS_TASK_ALTMNT.TASK_TYPE_CD code_values.

**enum_values**:

- LABELER
- REVIEWER

**stereotypes**:

_(empty)_

### TaskEventType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 작업 라이프사이클/감사 이벤트 유형. LS_TASK_EVNT_LOG.EVNT_TYPE_CD. 배정 계열(ASSIGN=최초배정, REASSIGN=재배정), 검수 계열(SUBMIT=검수제출, CANCEL_SUBMIT=제출취소, APPROVE=검수승인, REJECT=검수반려), 감사 계열(PRIVACY_META_UPDATE=영상 개인정보 선언 변경, PRIVACY_META_RESET=(구)비식별 신고 리셋·2026-08-04 폐기·과거행 판독용 존치, FRAME_DISCARD=프레임 폐기, FRAME_RESTORE=폐기 복원, START_VERSION_APPLY=시작 버전 선택 적용). 개인정보 2종·프레임 2종·시작버전은 배정/검수가 아니라 같은 테이블을 쓰는 감사(OWASP A09) 이벤트다.

**enum_values**:

- ASSIGN
- REASSIGN
- SUBMIT
- CANCEL_SUBMIT
- APPROVE
- REJECT
- PRIVACY_META_UPDATE
- PRIVACY_META_RESET
- FRAME_DISCARD
- FRAME_RESTORE
- START_VERSION_APPLY

**stereotypes**:

_(empty)_

### AssignmentService

- **kind**: service

**methods**:

#### assign

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: TaskAssignment

#### reassign

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: TaskAssignment

#### getHistory

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 작업(영상) 단위 라이프사이클 이벤트를 시간순(OCRN_DT ASC)으로 조회 — SCR-TASK-003 작업 이력 화면용. LS_TASK_EVNT_LOG 단일 타임라인.
- **is_abstract**: false
- **return_type**: List<TaskEventLog>

**attributes**:

_(empty)_

- **description**: 배정/재배정/이력 조회 도메인 서비스. REVIEWER 권한 검증 → 배정 INSERT, 재배정 시 기존 배정 행을 갱신하고 이벤트 로그(LS_TASK_EVNT_LOG)에 이전·신규 담당자를 기록 — 재배정 전용 이력 테이블은 두지 않는다. DFEAT-006 구현.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

REVIEWER가 WORKER에게 영상 1건 단위로 작업을 배정·재배정하고, 라이프사이클 이벤트(배정·재배정·검수 제출/승인/반려)를 타임라인으로 누적하는 도메인 모델. ERD-014(LS_TASK_ALTMNT / LS_TASK_EVNT_LOG)의 물리 컬럼을 반영한다.

## module_name

TaskAssignment

## relationships

### [1]

- **to**: TaskEventLog
- **from**: TaskAssignment
- **kind**: association
- **label**: RAW_DATA_ID 공유 간접 연관(FK 없음) — 배정 최대 2건 공존, APPROVE/REJECT 는 특정 배정에 귀속 안 됨
- **to_multiplicity**: 0..*
- **from_multiplicity**: 0..2

### [2]

- **to**: TaskTypeCode
- **from**: TaskAssignment
- **kind**: composition
- **label**: 작업유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [3]

- **to**: TaskEventType
- **from**: TaskEventLog
- **kind**: composition
- **label**: 이벤트유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [4]

- **to**: TaskAssignment
- **from**: AssignmentService
- **kind**: dependency
- **label**: 배정 조작
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [5]

- **to**: TaskEventLog
- **from**: AssignmentService
- **kind**: dependency
- **label**: 이벤트 적재
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-006

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

- ERD-014

## realizes_features

- DFEAT-006
