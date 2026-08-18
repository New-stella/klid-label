---
logicraft_item: DFEAT-020
type: domain_feature
version: 6
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.073Z
status: NEW
prev_version: null
content_hash: bd09291eb926fb4faa92ce8f0fef1c9383cf7961ea17c260feda915f8fd71640
stale: true
raw: ./_raw/DFEAT-020.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
  implements: ["[[API-125]]", "[[API-126]]", "[[API-127]]"]
  migrated_from: ["[[LEGACY-096]]"]
  specializes: ["[[FEAT-001]]"]
  depicts_backward: ["[[CDIAG-005]]"]
  realizes_backward: ["[[MOD-023]]", "[[UC-004]]"]
  references_backward: ["[[CDIAG-005]]"]
---

# 트랙 모드 (선형보간 연속 프레임 추적)

## title

트랙 모드 (선형보간 연속 프레임 추적)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

preserved

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-05-08

#### legacy_artifact_id

LEGACY-096

## user_story

### as

라벨링 작업자

### i_want

객체를 연속 프레임에서 자동 추적하기를

### so_that

프레임마다 수작업 라벨링하지 않아도 된다

## description

선형보간(Linear Interpolation) 기반으로 객체의 프레임 간 이동 경로를 자동 계산해 연속 프레임을 추적한다. 트랙 고유번호로 식별. (1차 baseline, 화면 SKKLID-UI-02-05-08, 인터페이스 II-007)

## invokes_apis

_(empty)_

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

### last_updated

2026-05-30T02:34:41.029Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## specializes_feature

FEAT-001

## implemented_by_endpoints

- API-125
- API-126
- API-127

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
