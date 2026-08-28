---
logicraft_item: CDIAG-011
type: class_diagram
version: 11
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-28T11:37:03.064Z
status: CHANGED
prev_version: 10
content_hash: 90abd4bc8f301c0647f8f68ed1e72fc335883940ce4bb3cfc241a099f4524fcf
stale: false
raw: ./_raw/CDIAG-011.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  depicts: ["[[DFEAT-043]]", "[[DFEAT-044]]", "[[DFEAT-053]]"]
  references: ["[[DFEAT-043]]", "[[DFEAT-044]]", "[[DFEAT-053]]"]
---

# 포털 사용자 라벨 도메인 모델

## theme

neutral

## title

포털 사용자 라벨 도메인 모델

## classes

### PortalUserLabel

- **kind**: aggregate_root

**methods**:

#### updateCoordinates

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### isOwnedBy

**params**:

- portalUserNo: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### userLblSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 포털 작업 저장소가 이 행에 부여하는 식별자. 내려받기 산출물에서 어노테이션 항목의 식별자를 채우는 조달처이며, 이 저장소에 담긴 라벨(포털 사용자가 직접 저장한 것)이 산출될 때 그 값이 된다. 다만 문서 사이의 유일성은 보장하지 않는다 — 다른 프레임 문서가 우연히 같은 값을 가질 수 있다. 문서 안에서 항목을 가리키는 용도이지 영상 전체에서 유일한 키가 아니다.
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

#### portalUserNo

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

#### srcRawSn

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

#### srcDataSrcSn

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

#### labelId

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 라벨 마스터를 가리키는 참조값. 데이터마트 원본에서 불러온 라벨이 갖고 있던 분류 연결을 저장 왕복에서 잃지 않기 위한 보존값이며, 내려받기 산출물에서 어노테이션 항목의 분류 식별자를 채우는 조달처다. 외래키를 두지 않는다 — 포털 전용 저장소는 내부 파이프라인과 분리 운영되고 라벨 마스터가 비활성화돼도 포털 사용자의 과거 작업이 지워지면 안 되기 때문이며, 유효성은 조회 시점 결합으로 확인한다. 활성 마스터에 실재하지 않는 참조는 그 값만 비워서 보관하고 저장 자체는 성공시킨다. 값이 없을 수 있고, 라벨명으로 유추해 채우지 않는다.
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

#### trackId

- **type**: String
- **is_static**: false
- **visibility**: private
- **description**: 라벨이 속한 트랙 식별자. 데이터마트 원본에서 불러온 라벨이 갖고 있던 트랙 연결을 저장 왕복에서 잃지 않기 위한 보존값이며, 내려받기 산출물에서 어노테이션 항목의 트랙 식별자를 채우는 조달처다. 데이터 라벨의 같은 이름 항목과 물리명·타입·크기를 그대로 재사용하고 길이 상한은 30자다. 외래키를 두지 않으며 유효성은 조회 시점 결합으로 확인한다. 값이 없을 수 있다 — 트랙에 묶이지 않은 라벨은 비운 채로 둔다. 포털 사용자에게 트랙 번호 변경·병합 수단을 주는 것이 아니다.
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

- **description**: 포털 사용자의 간편 라벨링 작업 데이터. 원본 미수정, 사용자별 적재(데이터마트 미정합). PORTAL_USER_NO로 본인 데이터만 접근. (LS_PORTAL_USER_LABEL) 라벨 마스터 참조(labelId)와 트랙 식별자(trackId)는 포털 사용자에게 새 편집 수단을 주려는 것이 아니라, 데이터마트 원본에서 불러온 라벨이 갖고 있던 연결이 저장 왕복에서 끊기지 않도록 보존하기 위한 것이다. 트랙 번호 변경·병합은 포털에 두지 않는다. 두 값 모두 비어 있을 수 있고 외래키를 두지 않으므로 라벨 마스터와 실선 연관으로 묶지 않고 식별자 보유만으로 표현하며, 참조 무결성은 조회 시점 결합으로 확인한다.

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

- **description**: 포털 라벨 도형 유형 코드 — BBOX·POLYGON 만 둔다. 서버 allowlist 가 그 외 값을 400 으로 거부하므로 이 열거에 다른 값을 두지 않는다. 구 값 SEGMENT·TRACK 은 내부 라벨 도메인의 값이며 포털에는 두지 않는다 — TRACK 은 2026-08-03 보안 판정으로 포털에서 제거된 축이다(ADR-013). 구 서술 "내부 label 도메인과 동일 체계"는 폐기한다: 두 축의 값 집합은 같지 않다.

**enum_values**:

- BBOX
- POLYGON

**stereotypes**:

_(empty)_

## description

포털 도메인은 서로 다른 두 저장모델을 갖는다.

[경로 A — 데이터마트 영상 라벨 작업] 포털 회원이 관제 제공 데이터마트 영상을 선택해 기존 라벨을 확인·수정·저장하되 원본(LS_DATA_LBL)을 수정하지 않고 사용자 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재한다. PORTAL_USER_NO 를 IDOR 차단 키로 본인 데이터만 접근한다. ERD-018 기반.

[★경로 B — 본인 자산 업로드 (ADR-013 예외, 2026-07-17 — 이전에 미등재)] 포털 사용자가 직접 올린 이미지·영상은 전용 테이블군으로 관리된다 — LS_PORTAL_ULD(업로드 마스터) · LS_PORTAL_ULD_FRME(추출 프레임) · LS_PORTAL_ULD_LBL(수동 라벨, BBOX/POLYGON 만) · LS_PORTAL_TUS_ULD(재개 업로드 세션). 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View 와 완전 분리된다. 업로드 상태는 UPLOADED→PROCESSING→READY|FAILED.

[경계] 두 경로는 서로 참조하지 않는다.
★오토라벨링(YOLO/SAM2)·SAM2 분할·SAM2 추적·키포인트·VLM·검수·버전관리는 두 경로 모두 제공하지 않는다(ADR-013). 구 서술은 이 목록을 경로 B 에만 걸어 경로 A 에는 SAM2 가 있는 것처럼 읽혔으나 폐기한다 — 부분 override 는 존재하지 않는다.
라벨 편집은 두 경로 모두 수동 라벨링(BBOX/POLYGON)만이며, 서버 allowlist(경로 A = PortalLabelService.validateAndNormalizeType, 경로 B = PortalUploadLabelService)가 그 외 도형을 400 으로 거부한다.
경로 B 는 추가로 비식별을 적용하지 않고 고정 간격 프레임 추출만 한다.

## module_name

Portal

## relationships

### [1]

- **to**: LabelType
- **from**: PortalUserLabel
- **kind**: dependency
- **label**: 라벨유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-043
- DFEAT-044
- DFEAT-053

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

- DFEAT-043
- DFEAT-044
- DFEAT-053
