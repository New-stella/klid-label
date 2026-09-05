---
logicraft_item: DFEAT-021
type: domain_feature
version: 9
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:40:07.180Z
status: CHANGED
prev_version: 8
content_hash: 92949d2cd7bd570f931f389bf3d2e34e5c3b581f8011a8792c5dba6665cb6da2
stale: false
raw: ./_raw/DFEAT-021.json
links:
  based_on: ["[[ADR-002]]"]
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-008]]", "[[API-009]]", "[[API-010]]", "[[API-012]]", "[[API-013]]", "[[API-138]]", "[[API-178]]", "[[IMPREC-357]]"]
  migrated_from: ["[[LEGACY-077]]"]
  specializes: ["[[FEAT-008]]"]
  verifies: ["[[AC-1040]]", "[[AC-1041]]", "[[AC-1042]]"]
  depicts_backward: ["[[CDIAG-006]]", "[[CMP-005]]"]
  realizes_backward: ["[[UC-023]]"]
  references_backward: ["[[ADR-002]]"]
---

# 검수 (검수자 1인 승인까지 반복)

## title

검수 (검수자 1인 승인까지 반복)

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

modified

### decided_by

ADR-002

### change_kind

- merge
- redesign

### diff_summary

1차 1·2차 단계 검수 → 2차 단계 구분 폐기, 검수자 1인이 승인(OK)할 때까지 반복 검수 (DFEAT-021←021+022 통합)

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-16

#### legacy_artifact_id

LEGACY-077

## user_story

### as

검수자

### i_want

라벨링 결과를 프레임 단위로 검토하기를

### so_that

품질 기준을 충족하는지 확인한다

## description

검수자가 라벨링된 데이터를 프레임 단위로 검토한다. 검수자 1인이 승인할 때까지 반려와 재제출을 반복하는 단일 검수다.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-357

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.543Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_RAW_DATA_STATUS

## related_acceptances

- AC-1040
- AC-1041
- AC-1042

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-008
- API-009
- API-010
- API-012
- API-013
- API-138
- API-178

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
