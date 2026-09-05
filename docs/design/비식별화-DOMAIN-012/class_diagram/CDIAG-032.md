---
logicraft_item: CDIAG-032
type: class_diagram
version: 1
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:22.419Z
status: NEW
prev_version: null
content_hash: 5a10096a73efda36c389152bd09b6804a9dbc7bb7ca4ecc226c4ab9432af3fe8
stale: false
raw: ./_raw/CDIAG-032.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
---

# 비식별화 구현 계층 — 배치·실행기·보안 오퍼레이션

## theme

neutral

## title

비식별화 구현 계층 — 배치·실행기·보안 오퍼레이션

## classes

### AsyncDeidentifyRunner

- **kind**: service

**methods**:

#### runAsync

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 적재된 영상에 파이프라인 선두의 비식별을 돌린다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 적재 직후 선두 비식별을 띄우는 비동기 실행기

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### KpstDeidentPollJob

- **kind**: service

**methods**:

#### execute

**params**:

- 잡 실행 문맥

- **is_static**: false
- **visibility**: public
- **description**: 외부에 맡긴 비식별이 끝났는지 주기적으로 확인해 결과를 원장에 반영한다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 외부 비식별 위탁의 진행 상태를 주기적으로 확인하는 스케줄 잡

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

적재 직후 선두 비식별을 띄우고 외부 위탁 결과를 회수하는 구현 계층이다.

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이름이 같고 파라미터가 다른 오퍼레이션은 둘 다 적었다 — 받는 값이 다르면 하는 일도 다르기 때문이다. 이 다이어그램은 도메인 객체 모델이 아니라 구현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

비식별화 Implementation

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
