---
logicraft_item: CDIAG-011
type: class_diagram
version: 7
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:56.451Z
status: NEW
prev_version: null
content_hash: 3b57ba702e5d53cabd940cbb78909ded22d16d3b472343d721587d77210c9674
stale: true
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

- **description**: 포털 사용자의 간편 라벨링 작업 데이터. 원본 미수정, 사용자별 적재(데이터마트 미정합). PORTAL_USER_NO로 본인 데이터만 접근. (LS_PORTAL_USER_LABEL)

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

## referenced_items

_(empty)_

## realizes_features

- DFEAT-043
- DFEAT-044
- DFEAT-053
