---
logicraft_item: CDIAG-009
type: class_diagram
version: 5
domain: DOMAIN-006
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:52:17.815Z
status: CHANGED
prev_version: 4
content_hash: 9bf6a8a0895bf56aa1bb46ecd1f6dddd48b43609e2dc3c387dc2273b8688ca90
stale: false
raw: ./_raw/CDIAG-009.json
links:
  belongs_to_domain: ["[[DOMAIN-006]]"]
  depicts: ["[[DFEAT-026]]", "[[DFEAT-027]]", "[[DFEAT-028]]"]
  references: ["[[DFEAT-026]]", "[[DFEAT-027]]", "[[DFEAT-028]]"]
---

# 통계·대시보드 도메인 모델 (개념 모델 — ERD 없음)

## theme

neutral

## title

통계·대시보드 도메인 모델 (개념 모델 — ERD 없음)

## classes

### DashboardService

- **kind**: service

**methods**:

#### getWorkerDashboard

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: WorkerStatistics

#### getReviewerDashboard

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: OperationStatistics

#### getDailyProgress

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<DailyProgress>

#### getStatusBreakdown

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<StatusCount>

**attributes**:

_(empty)_

- **description**: 역할별 대시보드 집계를 조합하는 도메인 서비스(루트 진입점). 작업자/검수자 개인 통계와 검수자 운영 통계를 기간·역할·상태 차원으로 집계. DFEAT-026/027/028 구현.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### WorkerStatistics

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### userNo

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

#### period

- **type**: StatPeriod
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

#### assignedCount

- **type**: long
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

#### completedCount

- **type**: long
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

#### rejectedCount

- **type**: long
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

- **description**: 작업자/검수자 개인 통계 집계 결과(개념 VO). 월별/일별 작업 건수·완료·반려를 집계. DFEAT-026.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### OperationStatistics

- **kind**: entity

**methods**:

#### progressRate

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: double

**attributes**:

#### totalCount

- **type**: long
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

#### byRole

- **type**: Map<Role,Long>
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

#### byStatus

- **type**: List<StatusCount>
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

#### dailyProgress

- **type**: List<DailyProgress>
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

- **description**: 검수자 운영 통계 집계 결과(개념 VO). 전체·권한별·상태별 영상/작업 통계와 일일 진행률 요약. DFEAT-027/028.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### StatusCount

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### status

- **type**: WorkStatus
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

#### count

- **type**: long
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

- **description**: 데이터 상태별 집계 항목(개념 VO). 대기/배정/검수중/검수완료/반려 등 상태별 건수 (LsRawDataStatus.STTS_* 축).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DailyProgress

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### date

- **type**: LocalDate
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

#### completed

- **type**: long
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

#### total

- **type**: long
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

#### rate

- **type**: double
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

- **description**: 일일 진행률 집계 항목(개념 VO). 날짜별 완료 비율(영상/작업 단위).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### WorkStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 데이터(작업) 상태. 상태별 통계 집계 차원이며, 코드값은 검수 도메인의 작업·검수 워크플로 축 LS_RAW_DATA_STATUS.DATA_STTS_CD(상수 LsRawDataStatus.STTS_*)를 따른다 — 이 도메인의 상태별 집계는 그 컬럼을 직접 센다. 검수 종결값은 APPROVED 다. [폐기] IN_PROGRESS·COMPLETED·REVIEW_REQUESTED(구 '확인요청') 세 값은 지금은 두지 않는다 — 이 축에 존재하지 않으며, 그대로 집계하면 검수 완료 작업이 어느 항목에도 잡히지 않는다. ⚠ 배정 목록 응답(AssignmentWorkStatus)은 PENDING·IN_PROGRESS·REVIEW_PENDING·COMPLETED·REJECTED 라는 별개의 표시 축을 쓰며 거기서는 COMPLETED·IN_PROGRESS 가 정상값이다. 두 축을 같은 이름으로 섞어 읽지 말 것.

**enum_values**:

- PENDING
- ASSIGNED
- IN_REVIEW
- APPROVED
- REJECTED
- [폐기] IN_PROGRESS
- [폐기] COMPLETED
- [폐기] REVIEW_REQUESTED

**stereotypes**:

_(empty)_

### StatPeriod

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 통계 집계 기간 단위. 월별/일별 대시보드 차원. DFEAT-026.

**enum_values**:

- DAILY
- MONTHLY

**stereotypes**:

_(empty)_

## description

작업자/검수자 개인 통계(월별·일별)와 검수자 운영 통계(전체·권한별·상태별·일일 진행률)를 집계해 대시보드로 제공하는 개념 모델. 전용 활성 ERD가 없어(1차 ERD-003 폐기) 물리 테이블이 아닌 집계 결과 VO와 집계 서비스 중심으로 구성했으며, 데이터 소스는 영상·프레임·작업 상태 원장에 대한 집계 조회다.

[★운영 통계 액터 표기] 이 모델은 운영 통계의 대상을 검수자로 적는다. ⚠ 당초 근거였던 '별도 ADMIN 역할이 없고 모든 관리 권한이 REVIEWER 에 통합됐다'(ADR-003)는 전제는 무효다 — ADR-055 가 관리자 역할을 신설해 그 결정을 뒤집었고, 관리자는 검수자 권한을 계층으로 물려받는다. 다만 그 계층 상속 덕분에 관리자도 같은 운영 통계에 그대로 접근하므로 검수자 표기는 하한으로서 지금도 참이다 — 표기와 집계 차원은 그대로 유지하고, 관리자 전용 집계 축을 새로 두는 것은 별도 결정이 필요하다.

## module_name

StatisticsDashboard

## relationships

### [1]

- **to**: WorkerStatistics
- **from**: DashboardService
- **kind**: dependency
- **label**: 개인 통계 집계
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [2]

- **to**: OperationStatistics
- **from**: DashboardService
- **kind**: dependency
- **label**: 운영 통계 집계
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [3]

- **to**: StatusCount
- **from**: OperationStatistics
- **kind**: composition
- **label**: 상태별 집계
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [4]

- **to**: DailyProgress
- **from**: OperationStatistics
- **kind**: composition
- **label**: 일일 진행률
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [5]

- **to**: StatPeriod
- **from**: WorkerStatistics
- **kind**: composition
- **label**: 집계 기간
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [6]

- **to**: WorkStatus
- **from**: StatusCount
- **kind**: composition
- **label**: 상태 구분
- **to_multiplicity**: 1
- **from_multiplicity**: 1

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-026
- DFEAT-027
- DFEAT-028

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

- DFEAT-026
- DFEAT-027
- DFEAT-028
