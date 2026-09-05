---
logicraft_item: CDIAG-030
type: class_diagram
version: 1
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:24.145Z
status: NEW
prev_version: null
content_hash: 3e3c90046e6b2c6a2e4839eede4e5a748ce8da3dcb8e36a261238cae75733cfb
stale: false
raw: ./_raw/CDIAG-030.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
---

# AI 보조 라벨링 구현 계층 — 배치·실행기·보안 오퍼레이션

## theme

neutral

## title

AI 보조 라벨링 구현 계층 — 배치·실행기·보안 오퍼레이션

## classes

### CocoClasses

- **kind**: class

**methods**:

#### isValid

**params**:

- 검출 클래스명

- **is_static**: false
- **visibility**: public
- **description**: 그 이름이 허용목록에 있는 검출 클래스인지 판정한다
- **is_abstract**: false
- **return_type**: 참·거짓

**attributes**:

_(empty)_

- **description**: 라벨 마스터의 검출유형 매핑에 쓰는 검출 클래스 허용목록

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

2차 신규 — 구현 계층 오퍼레이션을 설계 산출물에 처음으로 적는다.

## description

AI 검출 클래스 계약을 다루는 구현 계층이다.

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이름이 같고 파라미터가 다른 오퍼레이션은 둘 다 적었다 — 받는 값이 다르면 하는 일도 다르기 때문이다. 이 다이어그램은 도메인 객체 모델이 아니라 구현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

AI 보조 라벨링 Implementation

## relationships

_(empty)_

## attached_files

_(empty)_

## depicts_dfeats

_(empty)_

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

_(empty)_
