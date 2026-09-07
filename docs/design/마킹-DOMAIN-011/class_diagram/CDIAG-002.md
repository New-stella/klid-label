---
logicraft_item: CDIAG-002
type: class_diagram
version: 10
domain: DOMAIN-011
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T15:15:51.983Z
status: CHANGED
prev_version: 7
content_hash: 6e41a2f391194ab3b84b79536da6900ef801352fb3b6e38053241a44559a3acf
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
- **description**: [폐기] 이 값을 마킹이 보유하지 않는다 — 영상 행에 이미 있는 값을 마킹 행에 베껴 두던 중복이라 영상 쪽이 바뀌면 두 값이 어긋난다(원장도 같은 결정으로 EVNT_NM 칸을 두지 않는다). 마킹 응답의 이벤트 유형 코드는 영상 행을 조인해 조달하므로 화면과 외부 계약은 바뀌지 않는다. VLM 위탁 요청에도 실리지 않는다 — 마킹이 위탁에 기여하는 축은 프레임 선택·질문 선택·검증 이벤트 유형 선택 셋이며 이벤트명은 그 어디에도 들어가지 않는다.
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
- **description**: [폐기] 이 값을 마킹이 보유하지 않는다 — 영상 행에 이미 있는 값을 마킹 행에 베껴 두던 중복이라 영상 쪽이 바뀌면 두 값이 어긋난다(원장도 같은 결정으로 VIDEO_FILE_PATH_NM 칸을 두지 않는다). 마킹 응답의 영상 파일 경로는 영상 행을 조인해 조달하므로 화면과 외부 계약은 바뀌지 않는다. VLM 위탁 요청에도 실리지 않는다 — media.path 는 비식별 처리 이력에서 별도 조달한다.
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

#### vrfcEvntTypeCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **description**: 관제가 검증 이벤트 유형을 보내지 않은 영상에서 작업자가 마킹 화면에서 직접 고른 검증 이벤트 유형 코드(LS_MARKING.VRFC_EVNT_TYPE_CD, 검증이벤트유형코드). 비어 있을 수 있다 — 관제 인입(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)이 값을 실어 온 영상은 그 인입이 진실원이라 마킹 행에 베끼지 않아 이 칸을 쓰지 않고, 마킹 화면을 거치지 않는 경로와 이 칸이 생기기 전에 저장된 기존 행도 비어 있다. 화면이 유형 선택을 노출한 영상에서는 이 선택 없이 마킹을 완료할 수 없다 — 그 필수는 화면 축이고 저장 축이 아니다.
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

- **description**: 영상별 자동/수동 이벤트 식별 마킹. 영상 1건에 N건 생성 가능, 마킹 완료 시 MarkingCompletedEvent로 잔여 배치(VLM 시계열 위탁 등)를 트리거. fps는 마킹 시점에 고정하는 실 프레임레이트로, 프레임 추출이 재조회 없이 이 값을 읽어 인덱스 산출과 일치시킨다(레거시 행은 null 허용, 폴백 재조회). frmeIntvNocs(마킹 프레임 간격)는 자동 모드 마킹 생성의 기준값(FRME_INTV_NOCS)이며 VLM 위탁 요청에는 싣지 않는다 — 위탁의 frame_policy 는 간격값 없이 프레임 인덱스 목록(selected_frames)으로만 구성된다. vrfcEvntTypeCd(검증 이벤트 유형)와 vrfcEvntQstnSn(검증 이벤트 질문)은 위탁에 실리는 두 선택값이며 둘 다 비어 있을 수 있다 — 완료 불변식은 화면 축이라 저장 축을 필수로 올리지 않는다. (LS_MARKING)

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

- **description**: 마킹 상태. RESERVED→PENDING→VLM_REQUESTED→{VLM_COMPLETED|VLM_FAILED}, PENDING/VLM_REQUESTED→SKIPPED, RESERVED→SKIPPED(적용하지 못한 예약 마감). RESERVED 는 외부에서 받은 마킹을 영상 적재 시점에 미리 담아 두는 시작 상태이며, 활성 마킹을 세는 부분 유니크 제약(UK_LS_MARKING_RAW_ACTVTN)이 보는 값이 아니다 — 그 제약은 PENDING 과 VLM_REQUESTED 만 본다. (STTS_CD, DEFAULT PENDING)

**enum_values**:

- RESERVED
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

[VLM 연계] 마킹 완료 시 VLM 시계열 위탁 요청은 frame_policy(프레임 선택 정책)와 검증이벤트유형(event_type)으로 구성된다 — 이벤트명·영상 경로·마킹 원문 배열은 위탁 규격 밖이라 싣지 않는다. frame_policy 에는 간격값(framerate)을 두지 않는다. 마킹 본문에서 프레임 인덱스를 얻으면 frame_selected 모드로 그것을 싣고, 하나도 얻지 못하면 frame_interval 모드로 내린다(빈 목록은 규격 위반이라 거부된다) — 마킹 모드는 그 인덱스를 누가 골랐는지만 가른다(수동이면 작업자가 지정한 프레임, 자동이면 간격으로 자동 선택된 프레임). 인덱스는 정렬·중복제거 후 0 이상 최대 600건으로 제한하고 초과분은 절단한다. 위탁은 묘사(describe)와 추가 질문(custom) 두 건으로 제출하며 각 요청에 서로 다른 요청 식별자(request_id)를 부여한다. 추가 질문 창구는 이벤트 유형을 싣지 않고 질문 문구를 요청 본문(prompt, 최대 4,000자)에 직접 싣는다 — 위탁 시점에 조달해 원장에 보관하고 결과 수신 시 재조달하지 않는다. 구 추가 질문 창구(describe-sub)는 연동 대상으로 두지 않는다. ★위탁은 논블로킹 제출이다 — 스텝이 확정적으로 말하는 것은 '제출을 개시했다' 뿐이고 수락·결과는 비동기로 도착한다. 상태 전이는 RESERVED→PENDING→VLM_REQUESTED→{VLM_COMPLETED|VLM_FAILED} 이며, PENDING/VLM_REQUESTED→SKIPPED(배치 skip 종결) 와 RESERVED→SKIPPED(적용하지 못한 예약 마감) 로도 전이한다.

[유형·질문 선택과 완료 불변식] 마킹이 위탁에 기여하는 선택값은 둘이다 — 검증 이벤트 유형(vrfcEvntTypeCd)은 묘사 축 요청의 event_type 2순위로, 검증 이벤트 질문(vrfcEvntQstnSn)은 추가 질문 축 요청의 prompt 로 나간다. event_type 조달 순서는 관제 인입 값 → 마킹에서 작업자가 고른 값 → null 이다. 관제가 유형을 보내지 않은 영상에서는 마킹 화면이 유형 선택을 노출하며, 그 영상은 작업자가 유형을 고르지 않으면 마킹을 완료할 수 없다 — 위탁 본문에 실리는 질문 문구는 어느 마킹 방식에서도 값이 있어야 하는데(수동은 작업자가 직접 고르고, 자동은 그 유형의 첫 번째 질문이 자동 선택된다) 질문 목록은 유형이 먼저 정해져야 따라오기 때문이다. ★이 완료 불변식은 화면 축이고 저장 축이 아니다 — 관제 인입 값이 있는 영상은 그 인입이 진실원이라 마킹 행의 유형 칸을 아예 쓰지 않으므로, 두 속성은 모델에서 비어 있을 수 있는 값으로 남는다. 원장 컬럼(VRFC_EVNT_TYPE_CD)의 널 허용과 마킹 등록 요청에서의 선택 필드 지위는 그대로다 — 필수로 올리면 관제 값이 있는 영상의 마킹 저장이 전부 실패한다. 마킹 화면을 거치지 않는 경로와 이 칸이 생기기 전에 저장된 기존 행에서는 값이 없으며, 그때도 값을 지어내지 않고 null 로 보낸다.

[예약 상태와 영상 행 조달] RESERVED 는 외부에서 받은 마킹을 영상 적재 시점에 미리 담아 두는 시작 상태다. 비식별이 끝나 영상이 마킹 가능 상태가 되면 PENDING 으로 전이해 그때부터 활성 마킹이 되고, 적용하지 못한 채 끝난 예약은 RESERVED→SKIPPED 로 마감한다. 활성 마킹을 세는 부분 유니크 제약(UK_LS_MARKING_RAW_ACTVTN)은 PENDING 과 VLM_REQUESTED 만 보므로 RESERVED 는 그 제약을 점유하지 않으며, 비식별이 끝내 실패해도 같은 영상을 다시 마킹할 수 있다. 포털 업로드 경로는 위탁 축 세 값과 RESERVED 를 쓰지 않는다. ★evntNm(이벤트명)과 videoFilePathNm(영상 파일 경로명) 두 값은 마킹이 보유하지 않는다 — 영상 행에 이미 있는 값을 베껴 두던 중복이라 영상 쪽이 바뀌면 두 값이 어긋나기 때문이며, 마킹 응답의 두 값은 영상 행을 조인해 조달한다. 두 속성은 원소째 지우지 않고 폐기 표기로 남겨 그 설계 결정이 그림에서 읽히게 한다.

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
