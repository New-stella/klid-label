---
logicraft_item: CDIAG-034
type: class_diagram
version: 1
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:11.228Z
status: NEW
prev_version: null
content_hash: 94aa7dfcf6eaf242fe854659b821f9214b273455948b2cbc2d2db6e48cc81575
stale: false
raw: ./_raw/CDIAG-034.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
---

# 외부 산출물 이관 표현 계층 — 마킹 반입 창구 오퍼레이션

## theme

neutral

## title

외부 산출물 이관 표현 계층 — 마킹 반입 창구 오퍼레이션

## classes

### MarkingImportController

- **kind**: service

**methods**:

#### scan

**params**:

- 폴더 검사 요청 정보

- **is_static**: false
- **visibility**: public
- **description**: 폴더를 훑어 마킹 문서와 영상의 짝을 찾고 적재할 수 있는지 판정해 돌려준다 — 아무것도 저장하지 않는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### create

**params**:

- 일괄 적재 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 짝이 맞는 항목을 일괄 적재하는 작업을 등록하고 곧바로 반환한다 — 실제 적재는 뒤에서 항목별로 진행한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### progress

**params**:

- 작업 식별번호
- 항목 상태

- **is_static**: false
- **visibility**: public
- **description**: 일괄 적재가 어디까지 되었는지와 무엇이 왜 실패했는지 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 마킹이 끝난 영상 묶음을 폴더째 검사하고 일괄 적재하는 창구

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

2차 신규 — 마킹 반입 창구의 오퍼레이션을 적는다.

## description

마킹이 끝난 영상 묶음을 폴더째 들여오는 창구의 오퍼레이션을 적는다.

이 창구는 라벨링 결과를 받는 외부 산출물 이관과 방향이 다르다 — 그쪽은 끝난 결과를 받아 검수만 하지만 이쪽은 시작점만 받아 비식별부터 라벨링까지 앞 단계를 전부 밟는다.

검사와 적재와 진행 조회로 나뉘며, 검사는 아무것도 저장하지 않고 적재는 작업을 등록한 뒤 곧바로 반환해 실제 처리는 뒤에서 항목별로 진행한다.

권한이 창구마다 갈린다 — 쓰기에 해당하는 적재만 관리자를 요구하고 검사와 진행 조회는 검수자면 된다. 관리자는 검수자 자리를 계층으로 물려받으므로 셋 다 할 수 있다.

## module_name

마킹 반입 Controller

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
