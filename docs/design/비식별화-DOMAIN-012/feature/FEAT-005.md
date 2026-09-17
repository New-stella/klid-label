---
logicraft_item: FEAT-005
type: feature
version: 12
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:33:11.273Z
status: CHANGED
prev_version: 11
content_hash: bec51767a811b9f85d7b83b3fcfe1ed688f0b46000ac18261dc9a69d0dd334f5
stale: true
raw: ./_raw/FEAT-005.json
links:
  based_on: ["[[ADR-006]]"]
  implements: ["[[REQ-012]]", "[[REQ-013]]", "[[REQ-015]]", "[[REQ-021]]", "[[REQ-025]]", "[[REQ-027]]"]
  migrated_from: ["[[LEGACY-065]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  implements_backward: ["[[API-032]]", "[[API-091]]", "[[API-094]]", "[[API-109]]", "[[API-112]]"]
  realizes_backward: ["[[MOD-005]]", "[[UC-031]]"]
  references_backward: ["[[ADR-009]]"]
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
- **action**: 외부 비식별 솔루션을 연동 호출한다 — 출처유형이 배포 설정의 비식별 제외 목록에 든 영상은 호출하지 않고 원본을 비식별 영상 자리에 복사해 비식별을 완료한다(ADR-066)

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

발주기관이 제공하는 외부 비식별 솔루션을 연동해 개인정보를 가린다. 단순 연동에 그치지 않고, 솔루션이 제공하는 옵션을 관리 화면에서 선택·설정·사용하는 기능과 화면을 함께 제공한다. 비식별은 적재 직후 자동 실행되는 선두 단계로, 대상을 좁히는 게이팅 없이 전체 영상을 처리하며 원본과 비식별본을 따로 보관한다. 다만 출처유형이 배포 설정의 비식별 제외 목록(기본값 GENERATED)에 든 영상은 외부 솔루션에 위탁하지 않고 원본을 비식별 영상 자리에 복사해 비식별을 완료한다(ADR-066) — 전체 영상 비식별 원칙(ADR-006)을 대체하지 않고 예외를 더한 것이다.

## attached_files

_(empty)_

## business_rules

- 대상을 좁히는 게이팅 없이 적재된 전체 영상을 비식별한다
- 원본은 어떤 경우에도 삭제하지 않는다
- 비식별 옵션은 관리 화면에서 설정한다
- 출처유형이 배포 설정 authoring.deidentify.excluded-src-types(기본값 GENERATED)의 제외 목록에 든 영상은 외부 솔루션에 위탁하지 않고 원본을 비식별 영상 자리에 복사해 비식별을 완료한다 — 대상 게이팅을 되살리는 것이 아니라 출처유형 예외다(ADR-066). 검수 완료 영상의 재비식별은 이 예외를 타지 않는다

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

### module_paths

_(empty)_

## implements_requirements

- REQ-012
- REQ-013
- REQ-015
- REQ-021
- REQ-025
- REQ-027
