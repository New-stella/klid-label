---
logicraft_item: DFEAT-018
type: domain_feature
version: 9
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:11:17.771Z
status: NEW
prev_version: null
content_hash: a28537d87739d87fa2dbb6f4b2fd3669e674b99b35c5a10aa3b77c3385b2841a
stale: true
raw: ./_raw/DFEAT-018.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
  implements: ["[[API-093]]"]
  migrated_from: ["[[LEGACY-066]]"]
  specializes: ["[[FEAT-001]]"]
  depicts_backward: ["[[CDIAG-005]]"]
  realizes_backward: ["[[UC-005]]", "[[UC-038]]"]
---

# AI Tool (SAM 클릭 세그멘테이션)

## title

AI Tool (SAM 클릭 세그멘테이션)

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

SKKLID-UI-02-02-05

#### legacy_artifact_id

LEGACY-066

## user_story

### as

라벨링 작업자

### i_want

클릭만으로 객체 외곽을 자동 세그멘테이션하기를

### so_that

수작업 경계 설정을 줄인다

## description

SAM 방식으로 객체를 클릭하면 외곽을 자동 세그멘테이션하여 라벨링을 보조한다. (1차 baseline, 화면 SKKLID-UI-02-02-05)

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-354

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.119Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

- AC-005

## specializes_feature

FEAT-001

## implemented_by_endpoints

- API-093

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
