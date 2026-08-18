---
logicraft_item: CDIAG-012
type: class_diagram
version: 4
domain: DOMAIN-014
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:57.148Z
status: NEW
prev_version: null
content_hash: 6f4a6231ed0f0deaeb375377e697b4dea2c760f35c4dfc4c180ecc5b11a02703
stale: true
raw: ./_raw/CDIAG-012.json
links:
  belongs_to_domain: ["[[DOMAIN-014]]"]
  depicts: ["[[DFEAT-045]]"]
  references: ["[[DFEAT-045]]"]
---

# 시스템 설정 도메인 모델

## theme

neutral

## title

시스템 설정 도메인 모델

## classes

### SystemConfig

- **kind**: aggregate_root

**methods**:

#### updateValue

**params**:

- configVl: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### validateValue

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### configKey

- **type**: String
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

#### configVl

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

#### configTypeCd

- **type**: ConfigType
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

#### expln

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

#### mdfrId

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

- **description**: 시스템 설정 키-값. 화이트리스트 키만 등록·갱신, STNG_TYPE_CD 유형에 따라 값 검증. (LS_SYSTEM_CONFIG)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ConfigType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 설정 값 유형 코드. (STNG_TYPE_CD)

**enum_values**:

- JSON
- NUMBER
- STRING
- BOOLEAN
- DECIMAL

**stereotypes**:

_(empty)_

## description

저작도구 운영 파라미터를 키-값으로 관리하는 도메인 모델. 화이트리스트 키만 등록·갱신하며 STNG_TYPE_CD 유형별 값 검증과 Caffeine 로컬 캐시(TTL 60s)로 조회. ERD-016(LS_SYSTEM_CONFIG) 기반.

## module_name

SystemConfig

## relationships

### [1]

- **to**: ConfigType
- **from**: SystemConfig
- **kind**: dependency
- **label**: 값유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-045

## referenced_items

_(empty)_

## realizes_features

- DFEAT-045
