---
logicraft_item: CDIAG-005
type: class_diagram
version: 6
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:09:00.876Z
status: CHANGED
prev_version: 5
content_hash: 5be9e2e4766016adca5363ec8fbb49fc4c2854dae430d7225e6a0632d347b6ae
stale: false
raw: ./_raw/CDIAG-005.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
  depicts: ["[[DFEAT-018]]", "[[DFEAT-019]]", "[[DFEAT-020]]"]
  references: ["[[ADR-019]]", "[[CONST-002]]", "[[DFEAT-019]]", "[[DFEAT-020]]"]
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

#### createAutoBbox

**params**:

- srcSn: Long
- labelId: Long
- label: String
- pointsJson: String
- confScore: BigDecimal
- trackId: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: DataLabel

#### createAutoPolygon

**params**:

- srcSn: Long
- labelId: Long
- label: String
- pointsJson: String
- confScore: BigDecimal

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: DataLabel

#### createAutoInterpolatedBbox

**params**:

- srcSn: Long
- labelId: Long
- label: String
- pointsJson: String
- confScore: BigDecimal
- trackId: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: DataLabel

#### createAutoInterpolatedPolygon

**params**:

- srcSn: Long
- labelId: Long
- label: String
- pointsJson: String
- confScore: BigDecimal
- trackId: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: DataLabel

#### applyAiSource

**params**:

- lblSrcCd: String
- score: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### updateConfScore

**params**:

- newScore: BigDecimal

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### reassignTrack

**params**:

- newTrackId: String

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

#### labelId

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 라벨 마스터(LS_LABEL) 식별자. AI 검출 클래스 축으로 서버가 해석해 채우며, 대응 마스터가 없거나 검출 클래스 매핑이 지정되지 않으면 비어 있다.
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

##### module_paths

_(empty)_

#### mdlNm

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

##### module_paths

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

##### module_paths

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

##### module_paths

_(empty)_

- **description**: 라벨 좌표와 그 라벨이 어떻게 만들어졌는지를 함께 보유하는 Aggregate Root. 라벨 1건은 AI 출처 정보를 최대 1벌 가지므로 별도 객체로 분리하지 않고 같은 라벨에 둔다. TRCK_ID 로 트래커가 부여한 객체 식별자를 보유한다. 라벨 자체의 소유는 라벨링 도메인이며 본 모델은 오토라벨 관점에서 같은 라벨을 본다. 라벨 마스터 식별자(LBL_ID)도 함께 보유하며, 그 값을 AI 검출 클래스 축으로 해석해 채우는 주체는 서버다 — 검출 결과를 내보내는 경로가 그 식별자를 함께 싣고 화면은 검출 클래스명으로 마스터를 다시 찾지 않는다. 대응 마스터가 없거나 검출 클래스 매핑이 지정되지 않은 검출은 이 값을 비운 채 내보내며 값을 지어내지 않는다.

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

##### module_paths

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

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-018
- DFEAT-019
- DFEAT-020

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

- ADR-019
- CONST-002

## realizes_features

- DFEAT-019
- DFEAT-020
