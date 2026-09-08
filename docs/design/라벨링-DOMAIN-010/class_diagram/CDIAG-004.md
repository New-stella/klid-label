---
logicraft_item: CDIAG-004
type: class_diagram
version: 11
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T12:01:41.963Z
status: CHANGED
prev_version: 10
content_hash: e18a23d22607542c5df404d6b1f3d768039bda7c71566285e38ba5cf88da8d61
stale: false
raw: ./_raw/CDIAG-004.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
  depicts: ["[[DFEAT-012]]", "[[DFEAT-014]]", "[[DFEAT-015]]", "[[DFEAT-016]]", "[[DFEAT-017]]", "[[DFEAT-052]]"]
  references: ["[[DFEAT-012]]", "[[DFEAT-017]]"]
---

# 라벨링 도메인 모델

## theme

neutral

## title

라벨링 도메인 모델

## classes

### DataLabel

- **kind**: aggregate_root

**methods**:

#### updatePoints

**params**:

- pointCn: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### changeLabel

**params**:

- labelId: Long
- labelNm: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### assignAttributeValue

**params**:

- attrId: Long
- value: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### isAutoLabeled

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### lblSn

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

#### lblTypeCd

- **type**: LabelType
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

#### labelId

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

#### labelNm

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

#### pointCn

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

#### trckId

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

- **description**: 프레임 단위 라벨 좌표 Aggregate Root (LS_DATA_LBL). SRC_SN(프레임) 기준, LBL_TYPE_CD로 BBOX/POLYGON/SEGMENT/TRACK 구분, LS_LABEL 마스터를 LBL_ID로 참조하고 객체별 속성값을 보유.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataLabelAttrValue

- **kind**: entity

**methods**:

#### changeValue

**params**:

- attrVl: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### attrValId

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

#### lblSn

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

#### attrId

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

#### attrVl

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

- **description**: 객체별 속성값 (LS_DATA_LBL_ATTR_VAL). (LBL_SN, ATTR_ID) UNIQUE upsert 키로 LS_LABEL_ATTR 정의의 실제 값을 저장.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### Label

- **kind**: aggregate_root

**methods**:

#### rename

**params**:

- labelNm: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### changeColor

**params**:

- colrVl: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### addAttribute

**params**:

- attr: LabelAttr

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### softDelete

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### labelId

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

#### labelNm

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

#### colrVl

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

#### labelTypeCd

- **type**: LabelMasterType
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

#### sortSeq

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

#### useYn

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

- **description**: 전역 단일 라벨 풀 마스터 (LS_LABEL). 이름·색상·도형타입 정의, REVIEWER CRUD·WORKER 조회, soft delete(USE_YN='N')만 허용. 속성정의를 자식으로 보유.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelAttr

- **kind**: entity

**methods**:

#### updateOptions

**params**:

- valuesCn: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### softDelete

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### attrId

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

#### labelId

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

#### attrNm

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

#### inputTypeCd

- **type**: AttrInputType
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

#### valuesCn

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

#### dfltVl

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

#### mutableYn

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

#### useYn

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

- **description**: 라벨별 속성 정의 (LS_LABEL_ATTR, CVAT AttributeSpec 동등). 입력 위젯 타입·옵션·기본값·가변여부 정의. (LBL_ID, ATRB_NM) UNIQUE.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelVersion

- **kind**: aggregate_root

**methods**:

#### create

**params**:

- dataSrcSn: Long
- labelPayload: String
- saveReasonCd: SaveReason

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: LabelVersion

#### computeHash

**params**:

- labelPayload: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: String

#### activate

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### deactivate

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### diff

**params**:

- other: LabelVersion

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: LabelDiff

**attributes**:

#### labelVersionSn

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

#### dataSrcSn

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

#### versionHash

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

#### labelPayload

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

#### versionNo

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

#### saveReasonCd

- **type**: SaveReason
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

#### activeYn

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

- **description**: 라벨 DB 스냅샷 버전 (LS_LABEL_VERSION). ★ 검수 승인(APPROVED) 시점에 영상/프레임(DATA_SRC_SN) 단위로 라벨 전체 JSON(LABEL_PAYLOAD)을 VERSION_HASH(SHA-256)로 멱등 식별해 확정 스냅샷을 생성한다. 라벨 저장 시점에는 생성하지 않음(작업 임시저장은 LS_DATA_LBL 현재본 + FE undo). diff/rollback의 원천, 외부 VCS 미사용.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 라벨 도형 유형 코드 (LS_DATA_LBL.LBL_TYPE_CD).

**enum_values**:

- BBOX
- POLYGON
- SEGMENT
- TRACK
- SKELETON

**stereotypes**:

_(empty)_

### LabelMasterType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 라벨 마스터 도형 타입 코드 (LS_LABEL.LBL_TYPE_CD).

**enum_values**:

- BBOX
- POLYGON
- POINT
- SKELETON

**stereotypes**:

_(empty)_

### AttrInputType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 속성 입력 위젯 타입 (LS_LABEL_ATTR.INPUT_TYPE_CD).

**enum_values**:

- TEXT
- NUMBER
- SELECT
- CHECKBOX
- RADIO

**stereotypes**:

_(empty)_

### SaveReason

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 버전 스냅샷 생성 사유 (LS_LABEL_VERSION.SAVE_REASON_CD). APPROVED=검수 승인 시 확정 스냅샷(유일한 신규 생성 경로). DEIDENT_REPORT=레거시 값 — 구 정책(비식별 신고 시 라벨 전량 삭제 직전 복원 스냅샷)이 적재했던 사유로 신규 적재는 중단됐고 운영 DB 에 남은 기존 행 판독용으로만 존치한다. 롤백 사유 값은 이 열거에 두지 않는다 — 롤백은 새 버전 행을 적층하지 않고 대상 스냅샷 행을 재활성화하며 (재계산 해시가 대상 행과 같고 (DATA_SRC_SN, VERSION_HASH) UNIQUE 로 적층이 물리적으로 불가능), 그래서 이 사유로 적재되는 행이 생기지 않는다.

**enum_values**:

- APPROVED
- BATCH
- DEIDENT_REPORT

**stereotypes**:

_(empty)_

## description

프레임 단위 라벨(바운딩박스·폴리곤·세그멘테이션·스켈레톤·트랙) 좌표와 객체별 속성값을 관리하고, 라벨 마스터(LS_LABEL)·속성정의(LS_LABEL_ATTR)를 참조하며 라벨 전체 JSON 스냅샷(LABEL_PAYLOAD)을 VERSION_HASH(SHA-256)로 멱등 식별해 DB 기반 버전관리(diff/rollback)를 수행하는 라벨링 도메인 모델. ERD-010·ERD-019 기반.

[★2계층 저장] 작업 임시저장(LS_DATA_LBL, full-replace 영속)과 학습데이터 버전(LS_LABEL_VERSION 스냅샷)은 별개 계층이다. 버전 스냅샷은 검수 승인(APPROVED) 시점에만 생성되며 라벨 저장 시에는 만들지 않는다.

[★롤백 시맨틱 정정 — SaveReason='ROLLBACK' 폐기] 구 서술은 SaveReason 에 APPROVED/ROLLBACK 두 값이 있다고 적었으나 ROLLBACK 코드는 폐기됐다. 롤백은 새 버전 행을 적층하지 않고 대상 스냅샷 행을 재활성화한다 — 롤백 결과 페이로드가 대상 스냅샷 그 자체라 재계산 해시가 대상 행과 같고 (DATA_SRC_SN, VERSION_HASH) UNIQUE 로 적층이 물리적으로 불가능하기 때문이다. 롤백 행위는 LS_DATA_LBL_HSTRY 에 기록하고, 라벨 본문을 작업본으로 복원할 때 LBL_SN·AI 메타·TRCK_ID 까지 보존한다(PK 재발급 시 diff 가 '전량 교체'로 오분류). 현재 active 가 이미 대상 스냅샷이면 no-op 이다.

[기타 반영] 스켈레톤은 COCO-17 키포인트이고, 트랙은 병합·분할·삭제 편집이 가능하다. 비식별 누락 신고 구간에는 라벨 조회·이력·버전 diff/롤백이 412 로 차단되지만 라벨 자체는 삭제하지 않고 보존한다.

## module_name

Labeling

## relationships

### [1]

- **to**: DataLabelAttrValue
- **from**: DataLabel
- **kind**: composition
- **label**: 객체별 속성값
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [2]

- **to**: Label
- **from**: DataLabel
- **kind**: association
- **label**: 라벨 마스터 참조(LBL_ID)
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [3]

- **to**: LabelAttr
- **from**: Label
- **kind**: composition
- **label**: 라벨 속성 정의
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [4]

- **to**: LabelAttr
- **from**: DataLabelAttrValue
- **kind**: association
- **label**: 속성 정의 참조(ATTR_ID)
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [5]

- **to**: LabelType
- **from**: DataLabel
- **kind**: association
- **label**: 라벨 유형
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [6]

- **to**: LabelMasterType
- **from**: Label
- **kind**: association
- **label**: 도형 타입
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [7]

- **to**: AttrInputType
- **from**: LabelAttr
- **kind**: association
- **label**: 입력 위젯 타입
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [8]

- **to**: SaveReason
- **from**: LabelVersion
- **kind**: association
- **label**: 저장 사유
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [9]

- **to**: DataLabel
- **from**: LabelVersion
- **kind**: aggregation
- **label**: 스냅샷 대상 프레임 라벨(DATA_SRC_SN)
- **to_multiplicity**: 0..*
- **from_multiplicity**: 0..*

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-012
- DFEAT-015
- DFEAT-016
- DFEAT-017
- DFEAT-014
- DFEAT-052

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

- DFEAT-012
- DFEAT-017
