---
logicraft_item: DFEAT-028
type: domain_feature
version: 4
domain: DOMAIN-006
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:30.079Z
status: CHANGED
prev_version: 4
content_hash: 087986c3306c2b5167a3316ce9f4f9d16c36ead6095aee514c07a6721bd54011
stale: false
raw: ./_raw/DFEAT-028.json
links:
  belongs_to_domain: ["[[DOMAIN-006]]"]
  implements: ["[[API-057]]"]
  migrated_from: ["[[LEGACY-114]]"]
  depicts_backward: ["[[CDIAG-009]]"]
  realizes_backward: ["[[UC-033]]"]
  references_backward: ["[[CDIAG-009]]"]
---

# 작업 통계 (전체·권한별·상태별·일일 진행률)

## title

작업 통계 (전체·권한별·상태별·일일 진행률)

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

SKKLID-UI-03-02-16

#### legacy_artifact_id

LEGACY-114

## user_story

### as

검수자(REVIEWER)

### i_want

전체 작업을 권한별·상태별·일일로 통계 보기를

### so_that

진도 관리와 병목 식별을 한다

## description

전체/권한별/상태별 통계와 일일 진행률을 조회한다. (1차 baseline, 화면 SKKLID-UI-03-02-16~20)

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-362

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:21.256Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-057

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
