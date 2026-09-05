---
logicraft_item: CDIAG-002
type: class_diagram
version: 7
domain: DOMAIN-011
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:08:57.207Z
status: CHANGED
prev_version: 6
content_hash: e3f84fb3a431e733255a7b98e184c9b96dd111642eda896669f1782da6d74fdb
stale: false
raw: ./_raw/CDIAG-002.json
links:
  belongs_to_domain: ["[[DOMAIN-011]]"]
  depicts: ["[[DFEAT-039]]"]
  references: ["[[DFEAT-039]]"]
---

# 마킹 도메인 모델

## theme

neutral

## title

마킹 도메인 모델

## classes

### Marking

- **kind**: aggregate_root

**methods**:

#### complete

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제 상태 전이는 markVlmRequested/markVlmCompleted/markVlmFailed/markSkipped 가 개별로 담당한다.
- **is_abstract**: false
- **return_type**: void

#### requestVlm

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 실제 메서드명은 markVlmRequested() 다.
- **is_abstract**: false
- **return_type**: void

#### markVlmCompleted

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### toVlmCallbackPayload

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 마킹→VLM 은 위탁(제출)이고 콜백은 VLM→저작도구 방향에만 존재한다. 위탁 요청은 frame_policy 와 event_type 으로 구성되며 별도 페이로드 조립 메서드를 두지 않는다.
- **is_abstract**: false
- **return_type**: VlmCallbackPayload

#### markVlmRequested

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: VLM 시계열 위탁 제출 개시 시 PENDING→VLM_REQUESTED 로 전이.
- **is_abstract**: false
- **return_type**: void

#### markVlmFailed

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: VLM 위탁 확정 실패 시 VLM_REQUESTED→VLM_FAILED 로 종결 전이.
- **is_abstract**: false
- **return_type**: void

#### markSkipped

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 배치 skip 처리로 PENDING/VLM_REQUESTED→SKIPPED 로 종결 전이.
- **is_abstract**: false
- **return_type**: void

#### markSkippedForRedeident

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 비식별 신고 해소 후 재마킹을 위해 활성 마킹을 SKIPPED 로 종결 전이 — 부분 유니크 제약(UK_LS_MARKING_RAW_ACTVTN)을 해제한다.
- **is_abstract**: false
- **return_type**: void

#### createAuto

**params**:

_(empty)_

- **is_static**: true
- **visibility**: public
- **description**: 자동(AUTO) 모드 마킹 생성 팩토리.
- **is_abstract**: false
- **return_type**: Marking

#### createManual

**params**:

_(empty)_

- **is_static**: true
- **visibility**: public
- **description**: 수동(MANUAL) 모드 마킹 생성 팩토리.
- **is_abstract**: false
- **return_type**: Marking

**attributes**:

#### markingSn

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

#### rawSn

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

#### evntNm

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

#### markModeCd

- **type**: MarkMode
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

#### frmeIntvNocs

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

#### videoFilePathNm

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

#### markCn

- **type**: MarkContent
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

#### fps

- **type**: Double
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

#### vrfcEvntQstnSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: VLM 시계열 위탁 시 실을 검증 이벤트 질문(LS_VRFC_EVNT_QSTN) 참조 SN — 마킹에서 고른 질문이 event.question 축으로 조달된다.
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

#### sttsCd

- **type**: MarkingStatus
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

#### createdBy

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

#### mdfcnDt

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

- **description**: 영상별 자동/수동 이벤트 식별 마킹. 영상 1건에 N건 생성 가능, 마킹 완료 시 MarkingCompletedEvent로 잔여 배치(VLM 시계열 위탁 등)를 트리거. fps는 마킹 시점에 고정하는 실 프레임레이트로, 프레임 추출이 재조회 없이 이 값을 읽어 인덱스 산출과 일치시킨다(레거시 행은 null 허용, 폴백 재조회). frmeIntvNocs(마킹 프레임 간격)는 자동 모드 마킹 생성의 기준값(FRME_INTV_NOCS)이며 VLM 위탁 요청에는 싣지 않는다 — 위탁의 frame_policy 는 간격값 없이 프레임 인덱스 목록(selected_frames)으로만 구성된다. (LS_MARKING)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MarkContent

- **kind**: value_object

**methods**:

#### toJson

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: String

**attributes**:

#### marks

- **type**: List<MarkPoint>
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

- **description**: 마킹 결과 marks 배열(프레임 인덱스/타임스탬프)을 담는 값 객체. MARK_CN의 JSON 직렬화 형태로 영속. (MARK_CN)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MarkMode

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 마킹 모드. AUTO=프레임간격 기반, MANUAL=작업자 단축키. (MARK_MODE_CD)

**enum_values**:

- AUTO
- MANUAL

**stereotypes**:

_(empty)_

### MarkingStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 마킹 상태. PENDING→VLM_REQUESTED→{VLM_COMPLETED|VLM_FAILED}, PENDING/VLM_REQUESTED→SKIPPED. (STTS_CD, DEFAULT PENDING)

**enum_values**:

- PENDING
- VLM_REQUESTED
- VLM_COMPLETED
- VLM_FAILED
- SKIPPED

**stereotypes**:

_(empty)_

## description

영상 단위(RAW_SN)에 대해 자동(프레임간격)/수동(작업자 단축키) 모드로 이벤트 시점을 식별·마킹하는 LS_MARKING 중심 도메인 클래스 모델.

[★대상은 비식별 영상] 비식별이 파이프라인 선두로 재배치되면서 마킹은 MARKING_READY 이후에만 열리며 작업자는 비식별본을 본다. 잔여 배치 진입은 부모의 deIdntfYn='Y' 가드를 통과해야 한다.

[VLM 연계] 마킹 완료 시 VLM 시계열 위탁 요청은 frame_policy(프레임 선택 정책)와 검증이벤트유형(event_type)으로 구성된다 — 이벤트명·영상 경로·마킹 원문 배열은 위탁 규격 밖이라 싣지 않는다. frame_policy 에는 간격값(framerate)을 두지 않는다. 마킹 본문에서 프레임 인덱스를 얻으면 frame_selected 모드로 그것을 싣고, 하나도 얻지 못하면 frame_interval 모드로 내린다(빈 목록은 규격 위반이라 거부된다) — 마킹 모드는 그 인덱스를 누가 골랐는지만 가른다(수동이면 작업자가 지정한 프레임, 자동이면 간격으로 자동 선택된 프레임). 인덱스는 정렬·중복제거 후 0 이상 최대 600건으로 제한하고 초과분은 절단한다. 위탁은 describe(CoT)와 describe-sub(VQA) 두 건으로 제출하며 각 요청에 서로 다른 요청 식별자(request_id)를 부여한다. ★위탁은 논블로킹 제출이다 — 스텝이 확정적으로 말하는 것은 '제출을 개시했다' 뿐이고 수락·결과는 비동기로 도착한다. 상태 전이는 PENDING→VLM_REQUESTED→{VLM_COMPLETED|VLM_FAILED} 이며, PENDING/VLM_REQUESTED→SKIPPED(배치 skip 종결) 로도 전이한다.

[신고] 마킹 중 개인정보 노출을 발견하면 rawSn 기준으로 비식별 누락 신고를 접수한다(라벨링 단계의 srcSn 신고와 2채널).

## module_name

Marking

## relationships

### [1]

- **to**: MarkContent
- **from**: Marking
- **kind**: composition
- **label**: holds marks
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [2]

- **to**: MarkMode
- **from**: Marking
- **kind**: association
- **label**: mode
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [3]

- **to**: MarkingStatus
- **from**: Marking
- **kind**: association
- **label**: status
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-039

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

- DFEAT-039
