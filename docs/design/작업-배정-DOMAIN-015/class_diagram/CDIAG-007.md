---
logicraft_item: CDIAG-007
type: class_diagram
version: 14
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:52.300Z
status: CHANGED
prev_version: 9
content_hash: 37bbc0850a7785651659fbf68f4b38af1269dcaeb3230582f524f8e5a566d44e
stale: true
raw: ./_raw/CDIAG-007.json
links:
  belongs_to_domain: ["[[DOMAIN-015]]"]
  depicts: ["[[DFEAT-006]]"]
  references: ["[[ADR-067]]", "[[DFEAT-006]]", "[[ERD-014]]"]
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

- **description**: 영상 1건 단위 작업 배정 루트. (RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 유니크로 중복 배정 차단. 배정의 대상은 작업자뿐이라 검수자가 작업자에게 라벨링 작업(LABELER)을 영상 단위로 배정한다. 검수자 배정(REVIEWER)은 새로 만들지 않는다 — 검수는 배정 없이 전체 대기열에서 집어가므로 배정을 검수의 인가 축으로 쓰지 않는다. 이미 적재된 검수자 배정 행은 지우지 않으며, 유니크 제약에 작업 유형이 들어 있어 그 행이 남아도 작업자 배정과 충돌하지 않는다. (LS_TASK_ALTMNT)

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

#### actorRoleCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **description**: 그 행위를 한 시점의 행위자 역할(ACTOR_ROLE_CD). 조회 시점에 역할을 다시 읽지 않고 행위 시점 값을 그대로 담는다 — 그 사람의 역할이 나중에 바뀌어도 과거 행위의 역할은 그대로다. 계층으로 승격된 값이 아니라 행위자의 실제 역할이라 관리자가 승인하면 관리자로 남는다. 비어 있을 수 있다 — 이 칸이 생기기 전에 적재된 이벤트는 역할을 복원할 수 없어 비워 두며 지어낸 값으로 채우지 않는다.
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

- **description**: 작업(영상) 단위 라이프사이클 이벤트 누적 로그 (SCR-TASK-003 작업 이력 화면용). 배정·재배정·검수 시작·검수 제출·승인·반려를 시간순 단일 타임라인으로 누적. (LS_TASK_EVNT_LOG) 검수 점유도 전용 컬럼이나 별도 표가 아니라 이 원장의 검수 시작 이벤트로 표현한다. 판정은 저장된 값이 아니라 조회 시점 파생이다 — 최신 검수 시작 이벤트가 있고, 그보다 뒤에 승인·반려 같은 종결 이벤트가 없으며, 발생일시에 유예를 더한 시각이 아직 지나지 않았으면 그 행위자가 점유 중이다. 유예는 배포 설정값이고 기본값은 30분이며 점유를 푸는 별도 동작은 두지 않는다. 점유는 잠금이 아니다 — 만료가 있고 승인 시점 동시성 보호가 실제 방어로 남는다.

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

- **description**: 작업 유형 코드. LS_TASK_ALTMNT.TASK_TYPE_CD code_values. 신규 배정은 작업자(LABELER)만 만든다. 검수자(REVIEWER) 값은 이미 적재된 행을 판독하기 위해 값역에 남기며 새로 만들지 않는다 — 값을 지우면 남아 있는 옛 행의 뜻을 읽을 수 없게 된다.

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

- **description**: 작업 라이프사이클/감사 이벤트 유형. LS_TASK_EVNT_LOG.EVNT_TYPE_CD. 배정 계열(ASSIGN=최초배정, REASSIGN=재배정), 검수 계열(START_REVIEW=검수시작, SUBMIT=검수제출, CANCEL_SUBMIT=제출취소, APPROVE=검수승인, REJECT=검수반려), 감사 계열(PRIVACY_META_UPDATE=영상 개인정보 선언 변경, PRIVACY_META_RESET=(구)비식별 신고 리셋·2026-08-04 폐기·과거행 판독용 존치, FRAME_DISCARD=프레임 폐기, FRAME_RESTORE=폐기 복원, START_VERSION_APPLY=시작 버전 선택 적용). 개인정보 선언 변경·비식별 신고 리셋·프레임 폐기·폐기 복원·시작 버전 적용은 배정/검수가 아니라 같은 테이블을 쓰는 감사(OWASP A09) 이벤트다. 검수 시작(START_REVIEW)은 점유를 세운다 — 점유는 전용 컬럼이나 표가 아니라 이 이벤트로만 표현한다.

**enum_values**:

- ASSIGN
- REASSIGN
- START_REVIEW
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
- **description**: 작업(영상) 단위 라이프사이클 이벤트를 시간순(OCRN_DT ASC)으로 조회 — SCR-TASK-003 작업 이력 화면용. LS_TASK_EVNT_LOG 단일 타임라인. 배정·재배정뿐 아니라 검수 시작·검수 제출·승인·반려까지 한 타임라인에 담기며, 항목마다 그 행위를 한 시점의 행위자 역할이 함께 실린다(옛 이력은 비어 있을 수 있다). 이벤트 종류로 거르는 질의 항목을 두지 않으므로 값역이 넓어지면 새 종류도 그대로 실린다.
- **is_abstract**: false
- **return_type**: List<TaskEventLog>

**attributes**:

_(empty)_

- **description**: 배정/재배정/이력 조회 도메인 서비스. REVIEWER 권한 검증 → 배정 INSERT, 재배정 시 기존 배정 행을 갱신하고 이벤트 로그(LS_TASK_EVNT_LOG)에 이전·신규 담당자를 기록 — 재배정 전용 이력 테이블은 두지 않는다. DFEAT-006 구현. 검수자를 영상에 배정하는 연산은 두지 않는다 — 이 서비스가 만드는 배정은 작업자 배정뿐이다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

REVIEWER가 WORKER에게 영상 1건 단위로 작업을 배정·재배정하고, 라이프사이클 이벤트(배정·재배정·검수 시작·검수 제출·승인·반려)를 타임라인으로 누적하는 도메인 모델. ERD-014(LS_TASK_ALTMNT / LS_TASK_EVNT_LOG)의 물리 컬럼을 반영한다. 배정의 대상은 작업자뿐이며 검수자를 영상에 배정하는 절차를 두지 않는다 — 검수는 배정 없이 전체 대기열에서 집어가고 자격은 역할이 정한다. 지금 누가 그 영상을 검수 중인지는 작업 이벤트 로그의 검수 시작 이벤트로 판정하며, 그 판정은 저장된 값이 아니라 조회 시점 파생이라 목록 화면은 행마다 되짚지 않고 영상별 최신 이벤트를 한 번의 조회로 함께 가져온다. 이력 항목마다 그 행위를 한 시점의 행위자 역할이 함께 남고, 역할 칸이 생기기 전에 쌓인 이력은 비어 있을 수 있어 지어내 채우지 않는다. 근거는 ADR-067.

## module_name

TaskAssignment

## relationships

### [1]

- **to**: TaskEventLog
- **from**: TaskAssignment
- **kind**: association
- **label**: RAW_DATA_ID 공유 간접 연관(FK 없음) — 신규 배정은 작업자 배정뿐이고(옛 검수자 배정 행이 남아 있을 수 있다), 검수 시작·승인·반려는 특정 배정에 귀속되지 않는다
- **to_multiplicity**: 0..*
- **from_multiplicity**: 0..*

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
- ADR-067

## realizes_features

- DFEAT-006
