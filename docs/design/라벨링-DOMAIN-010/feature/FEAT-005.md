---
logicraft_item: FEAT-005
type: feature
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:11:06.882Z
status: CHANGED
prev_version: 9
content_hash: 231664259e70726d239767b016cd9fbb56cd0a857d9413851f57a47db1f39aa5
stale: false
raw: ./_raw/FEAT-005.json
links:
  based_on: ["[[ADR-006]]"]
  implements: ["[[REQ-012]]", "[[REQ-013]]", "[[REQ-015]]", "[[REQ-021]]", "[[REQ-025]]"]
  migrated_from: ["[[LEGACY-065]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  implements_backward: ["[[API-032]]", "[[API-091]]", "[[API-094]]", "[[API-109]]", "[[API-112]]"]
  realizes_backward: ["[[MOD-005]]"]
  specializes_backward: ["[[DFEAT-041]]", "[[DFEAT-042]]", "[[DFEAT-048]]"]
---

# 개인정보 비식별 처리 (솔루션 연동·옵션 사용)

## priority

must

## main_flow

### [1]

- **step**: 1
- **action**: 영상이 적재되면 대상 선별 없이 전체 영상에 대해 비식별을 선두 단계로 자동 트리거한다

### [2]

- **step**: 2
- **action**: 외부 비식별 솔루션을 연동 호출한다

### [3]

- **step**: 3
- **actor**: 검수자
- **action**: 솔루션이 제공하는 옵션을 관리 화면에서 선택·적용한다

### [4]

- **step**: 4
- **action**: 비식별 처리를 수행한다

### [5]

- **step**: 5
- **action**: 원본과 비식별본을 분리해 저장한다

## brownfield

### status

modified

### decided_by

ADR-006

### change_kind

- component-replace

### diff_summary

1차 라벨링 수동 블러 → 2차 외부 비식별 솔루션 연동·옵션 처리 (SFR-09-01/02/04)

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-04

#### legacy_artifact_id

LEGACY-065

## complexity

complex

## user_story

### goal

외부 비식별 솔루션을 연동해 개인정보를 가리고, 솔루션이 제공하는 옵션을 화면에서 직접 설정·사용하기를 원한다

### actor

시스템·검수자

### benefit

개인정보를 안전하게 보호하면서 비식별 강도를 상황에 맞게 조절할 수 있다

## description

발주기관이 제공하는 외부 비식별 솔루션을 연동해 개인정보를 가린다. 단순 연동에 그치지 않고, 솔루션이 제공하는 옵션을 관리 화면에서 선택·설정·사용하는 기능과 화면을 함께 제공한다. 비식별은 적재 직후 자동 실행되는 선두 단계로, 대상을 좁히는 게이팅 없이 전체 영상을 처리하며 원본과 비식별본을 따로 보관한다.

## business_rules

- 대상을 좁히는 게이팅 없이 적재된 전체 영상을 비식별한다
- 원본은 어떤 경우에도 삭제하지 않는다
- 비식별 옵션은 관리 화면에서 설정한다

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## implements_requirements

- REQ-012
- REQ-013
- REQ-015
- REQ-021
- REQ-025
