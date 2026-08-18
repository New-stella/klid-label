---
logicraft_item: CDIAG-007
type: class_diagram
version: 7
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:57.794Z
status: NEW
prev_version: null
content_hash: 08d6b2cdd04d1475f1ed18fc38c4c1be116daf1341d776dc3cf6344dd63ebde9
stale: false
raw: ./_raw/CDIAG-007.json
links:
  belongs_to_domain: ["[[DOMAIN-015]]"]
  depicts: ["[[DFEAT-006]]"]
  references: ["[[DFEAT-006]]"]
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

#### reassign

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: TaskEventLog

#### isLabeler

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### isReviewer

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

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

- **description**: 작업 라이프사이클 이벤트 유형. LS_TASK_EVNT_LOG.EVNT_TYPE_CD (ASSIGN=최초배정, REASSIGN=재배정, SUBMIT=검수제출, APPROVE=검수승인, REJECT=검수반려).

**enum_values**:

- ASSIGN
- REASSIGN
- SUBMIT
- APPROVE
- REJECT

**stereotypes**:

_(empty)_

### TaskAssignmentService

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

#### [폐기] findAssignHistory

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<TaskEventLog>

#### findEventTimeline

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
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
- **from**: TaskAssignmentService
- **kind**: dependency
- **label**: 배정 조작
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [5]

- **to**: TaskEventLog
- **from**: TaskAssignmentService
- **kind**: dependency
- **label**: 이벤트 적재
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-006

## referenced_items

- ERD-014

## realizes_features

- DFEAT-006
