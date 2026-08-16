---
logicraft_item: CDIAG-005
type: class_diagram
version: 4
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.070Z
status: NEW
prev_version: null
content_hash: 65f9d645fd2cb768cff02a6a22f1bdca2060ce5850eb86d414c1a4b64151ca24
stale: true
raw: ./_raw/CDIAG-005.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
  depicts: ["[[DFEAT-018]]", "[[DFEAT-019]]", "[[DFEAT-020]]"]
  references: ["[[DFEAT-019]]", "[[DFEAT-020]]"]
---

# AI 보조 라벨링 도메인 모델

## theme

neutral

## title

AI 보조 라벨링 도메인 모델

## classes

### DataLabel

- **kind**: aggregate_root

**methods**:

#### isInterpolated

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### updateConfidence

**params**:

- confScore: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markDetected

**params**:

- modelNm: String
- mdlVer: String
- confScore: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markSegmented

**params**:

- modelNm: String
- confScore: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markInterpolated

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

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

#### lblTypeCd

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

#### lblSrcCd

- **type**: LabelSource
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

#### modelNm

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

#### mdlVer

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

#### confScore

- **type**: BigDecimal
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

#### autoLblYn

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

- **description**: 라벨 좌표와 그 라벨이 어떻게 만들어졌는지를 함께 보유하는 Aggregate Root. 라벨 1건은 AI 출처 정보를 최대 1벌 가지므로 별도 객체로 분리하지 않고 같은 라벨에 둔다. TRCK_ID 로 트래커가 부여한 객체 식별자를 보유한다. 라벨 자체의 소유는 라벨링 도메인이며 본 모델은 오토라벨 관점에서 같은 라벨을 본다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ConfidenceScore

- **kind**: value_object

**methods**:

#### of

**params**:

- value: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: ConfidenceScore

#### isLowConfidence

**params**:

- threshold: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### value

- **type**: BigDecimal
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

- **description**: 추론 신뢰도 값 객체 (CONF_SCORE, 0.0~1.0). 보간으로 채운 라벨은 0.0. 생성 시 범위 검증.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelSource

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 라벨 출처 코드 (LS_DATA_LBL.LBL_SRC_CD).

**enum_values**:

- YOLO
- SAM2
- INTERPOLATE
- VLM

**stereotypes**:

_(empty)_

### AutoLabelYn

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 자동 라벨 여부 (LS_DATA_LBL.AUTO_LBL_YN). 값이 비어 있으면 자동 생성이 아니며 조회 응답에서는 'N' 으로 표현한다.

**enum_values**:

- Y
- N

**stereotypes**:

_(empty)_

## description

YOLO 객체탐지·SAM2 클릭 세그멘테이션·트랙 모드(선형보간) 오토라벨 결과를 라벨 좌표(LS_DATA_LBL)가 출처·모델·신뢰도·자동라벨 여부를 함께 보유하는 형태로 관리하는 도메인 모델. 라벨 1건당 AI 출처는 최대 1벌이라 별도 테이블로 나누지 않고 같은 행에 둔다 — 조회가 늘 함께 읽으므로 결합이 붙지 않는다. 보간으로 채운 라벨은 신뢰도 0.0 으로 식별한다. ERD-010의 LS_DATA_LBL 중심. — 오토라벨 프리셋↔검출라벨 매칭축은 마스터 라벨명(findLabelIdByName)이 아닌 COCO 검출클래스(LS_LABEL.DTCT_TYPE_CD)로 일원화(ADR-019): findLabelIdByDtctType 가 findLabelIdByName 을 대체하고 배치(YoloAutolabelStep)·온라인(AutolabelOnlineService)·SAM2·프리셋 토글(PresetLabelLookupService) 4경로가 동일 축으로 통일. AutolabelOnlineService.resolveDetectClasses 가 FE 요청을 신뢰하지 않고 마스터 COCO 매핑 화이트리스트(CONST-002 CocoClasses 80종)와 교집합만 ai-server 로 전달.

## module_name

AiAssistedLabeling

## relationships

### [1]

- **to**: LabelSource
- **from**: DataLabel
- **kind**: association
- **label**: 라벨 출처
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [2]

- **to**: AutoLabelYn
- **from**: DataLabel
- **kind**: association
- **label**: 자동 라벨 여부
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [3]

- **to**: ConfidenceScore
- **from**: DataLabel
- **kind**: composition
- **label**: 추론 신뢰도
- **to_multiplicity**: 0..1
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-018
- DFEAT-019
- DFEAT-020

## referenced_items

- ADR-019
- CONST-002

## realizes_features

- DFEAT-019
- DFEAT-020
