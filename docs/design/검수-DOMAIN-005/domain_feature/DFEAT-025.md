---
logicraft_item: DFEAT-025
type: domain_feature
version: 6
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:29.302Z
status: CHANGED
prev_version: 6
content_hash: 3c8ed63ac5b8c66d7ec55f426c83c9067f9b9ae3d9faee6c0b00b1c3a15bef35
stale: false
raw: ./_raw/DFEAT-025.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-011]]"]
  migrated_from: ["[[LEGACY-079]]"]
  specializes: ["[[FEAT-008]]"]
  depicts_backward: ["[[CDIAG-006]]"]
  realizes_backward: ["[[UC-023]]"]
---

# 검수 이력

## title

검수 이력

## status

designed

## consumes

_(empty)_

## priority

should

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

SKKLID-UI-02-02-18

#### legacy_artifact_id

LEGACY-079

## user_story

### as

검수자

### i_want

검수 이력을 조회하기를

### so_that

검수 과정을 추적·감사한다

## description

검수 제출·승인·반려 등 검수 이력을 조회한다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-359

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.824Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATA_ISSUE

## related_acceptances

_(empty)_

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-011

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
